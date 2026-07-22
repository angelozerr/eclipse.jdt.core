/*******************************************************************************
 * Copyright (c) 2025 Red Hat, Inc. and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.jdt.internal.core.search.indexing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.jdt.core.search.SearchDocument;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;

/**
 * Standalone comparison tool for ECJ vs Turbine indexing.
 * <p>
 * Usage: Run as Java Application.
 * <pre>
 *   TurbineIndexComparator [directory] [mode]
 *   mode: turbine | ecj | compare (default: compare)
 * </pre>
 */
public class TurbineIndexComparator {

	private static final String DEFAULT_DIR = "C:/Users/AngeloZerr/git/quarkus"; //$NON-NLS-1$
	private static final int PROGRESS_INTERVAL = 100;

	private static final Set<String> DECLARATION_CATEGORIES = Set.of(
		"typeDecl", "methodDecl", "methodDeclPlus", "constructorDecl", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		"fieldDecl", "moduleDecl" //$NON-NLS-1$ //$NON-NLS-2$
	);

	static class RecordingSearchDocument extends SearchDocument {
		private final char[] source;
		final List<String> entries = new ArrayList<>();

		RecordingSearchDocument(String path, char[] source) {
			super(path, null);
			this.source = source;
		}

		@Override
		public void addIndexEntry(char[] category, char[] key) {
			this.entries.add(new String(category) + "/" + new String(key)); //$NON-NLS-1$
		}

		@Override
		public byte[] getByteContents() {
			return null;
		}

		@Override
		public char[] getCharContents() {
			return this.source;
		}

		@Override
		public String getEncoding() {
			return StandardCharsets.UTF_8.name();
		}
	}

	public static void main(String[] args) {
		String dir = args.length > 0 ? args[0] : DEFAULT_DIR;
		String mode = args.length > 1 ? args[1] : "compare"; //$NON-NLS-1$
		Path root = Path.of(dir);

		if (!Files.isDirectory(root)) {
			System.err.println("Directory not found: " + root); //$NON-NLS-1$
			System.exit(1);
		}

		List<Path> javaFiles = scanJavaFiles(root);
		System.out.printf("Files found: %d%n%n", javaFiles.size()); //$NON-NLS-1$

		switch (mode) {
			case "turbine": //$NON-NLS-1$
				runTurbineOnly(javaFiles, root);
				break;
			case "ecj": //$NON-NLS-1$
				runECJOnly(javaFiles, root);
				break;
			case "compare": //$NON-NLS-1$
			default:
				runCompare(javaFiles, root);
				break;
		}
	}

	private static void runTurbineOnly(List<Path> javaFiles, Path root) {
		System.out.println("=== Indexing with Turbine ==="); //$NON-NLS-1$
		int errors = 0;
		int turbineFailures = 0;
		int total = javaFiles.size();
		long startTotal = System.nanoTime();

		for (int i = 0; i < total; i++) {
			if ((i + 1) % PROGRESS_INTERVAL == 0) {
				long elapsedMs = (System.nanoTime() - startTotal) / 1_000_000;
				System.out.printf("  Progress: %d/%d (%d ms)%n", i + 1, total, elapsedMs); //$NON-NLS-1$
			}
			char[] source = readSource(javaFiles.get(i));
			if (source == null) { errors++; continue; }
			RecordingSearchDocument doc = new RecordingSearchDocument(javaFiles.get(i).toString(), source);
			try {
				SourceIndexer indexer = new SourceIndexer(doc);
				if (!indexer.indexDocumentFromTurbine()) {
					turbineFailures++;
				}
			} catch (Exception | Error e) {
				errors++;
			}
		}

		long timeMs = (System.nanoTime() - startTotal) / 1_000_000;
		System.out.printf("Turbine: %d files, %d failures, %d errors, %d ms%n", //$NON-NLS-1$
				total, turbineFailures, errors, timeMs);
	}

	private static void runECJOnly(List<Path> javaFiles, Path root) {
		System.out.println("=== Indexing with ECJ ==="); //$NON-NLS-1$
		int errors = 0;
		int total = javaFiles.size();
		long startTotal = System.nanoTime();

		CompilerOptions options = new CompilerOptions();
		options.sourceLevel = CompilerOptions.versionToJdkLevel("21"); //$NON-NLS-1$
		options.complianceLevel = options.sourceLevel;
		DefaultProblemFactory problemFactory = new DefaultProblemFactory();

		for (int i = 0; i < total; i++) {
			if ((i + 1) % PROGRESS_INTERVAL == 0) {
				long elapsedMs = (System.nanoTime() - startTotal) / 1_000_000;
				System.out.printf("  Progress: %d/%d (%d ms)%n", i + 1, total, elapsedMs); //$NON-NLS-1$
			}
			char[] source = readSource(javaFiles.get(i));
			if (source == null) { errors++; continue; }
			RecordingSearchDocument doc = new RecordingSearchDocument(javaFiles.get(i).toString(), source);
			try {
				SourceIndexer indexer = new SourceIndexer(doc);
				IndexingParser parser = new IndexingParser(
						indexer.requestor, problemFactory, options,
						true, true, false);
				parser.reportOnlyOneSyntaxError = true;
				parser.scanner.taskTags = null;
				doc.setParser(parser);
				indexer.indexDocument();
			} catch (Exception | Error e) {
				errors++;
			}
		}

		long timeMs = (System.nanoTime() - startTotal) / 1_000_000;
		System.out.printf("ECJ: %d files, %d errors, %d ms%n", total, errors, timeMs); //$NON-NLS-1$
	}

	/**
	 * Compare mode: process each file with BOTH indexers in a single pass.
	 * Only one file's source is in memory at a time — no preloading needed.
	 */
	private static void runCompare(List<Path> javaFiles, Path root) {
		int total = javaFiles.size();
		int readErrors = 0;
		int turbineFailures = 0;
		int ecjErrors = 0;
		int filesWithDeclDiffs = 0;
		long turbineTotalNs = 0;
		long ecjTotalNs = 0;

		CompilerOptions options = new CompilerOptions();
		options.sourceLevel = CompilerOptions.versionToJdkLevel("21"); //$NON-NLS-1$
		options.complianceLevel = options.sourceLevel;
		DefaultProblemFactory problemFactory = new DefaultProblemFactory();

		Map<String, List<String>> differences = new LinkedHashMap<>();

		for (int i = 0; i < total; i++) {
			if ((i + 1) % PROGRESS_INTERVAL == 0) {
				System.out.printf("  Progress: %d/%d (Turbine: %d ms, ECJ: %d ms)%n", //$NON-NLS-1$
						i + 1, total,
						turbineTotalNs / 1_000_000,
						ecjTotalNs / 1_000_000);
			}
			Path file = javaFiles.get(i);
			char[] source = readSource(file);
			if (source == null) { readErrors++; continue; }

			// --- Turbine ---
			Set<String> turbineDecls;
			{
				RecordingSearchDocument doc = new RecordingSearchDocument(file.toString(), source);
				long t0 = System.nanoTime();
				try {
					SourceIndexer indexer = new SourceIndexer(doc);
					if (!indexer.indexDocumentFromTurbine()) {
						turbineFailures++;
					}
				} catch (Exception | Error e) {
					turbineFailures++;
				}
				turbineTotalNs += System.nanoTime() - t0;
				turbineDecls = filterDeclarations(doc.entries);
			}

			// --- ECJ ---
			Set<String> ecjDecls;
			{
				RecordingSearchDocument doc = new RecordingSearchDocument(file.toString(), source);
				long t0 = System.nanoTime();
				try {
					SourceIndexer indexer = new SourceIndexer(doc);
					IndexingParser parser = new IndexingParser(
							indexer.requestor, problemFactory, options,
							true, true, false);
					parser.reportOnlyOneSyntaxError = true;
					parser.scanner.taskTags = null;
					doc.setParser(parser);
					indexer.indexDocument();
				} catch (Exception | Error e) {
					ecjErrors++;
				}
				ecjTotalNs += System.nanoTime() - t0;
				ecjDecls = filterDeclarations(doc.entries);
			}

			// --- Compare ---
			if (!ecjDecls.equals(turbineDecls)) {
				filesWithDeclDiffs++;
				if (differences.size() < 50) {
					String key = root.relativize(file).toString();
					List<String> diffs = new ArrayList<>();
					TreeSet<String> ecjOnly = new TreeSet<>(ecjDecls);
					ecjOnly.removeAll(turbineDecls);
					TreeSet<String> turbineOnly = new TreeSet<>(turbineDecls);
					turbineOnly.removeAll(ecjDecls);
					for (String e : ecjOnly) {
						diffs.add("  ECJ only:     " + e); //$NON-NLS-1$
					}
					for (String e : turbineOnly) {
						diffs.add("  Turbine only: " + e); //$NON-NLS-1$
					}
					differences.put(key, diffs);
				}
			}
		}

		// --- Report ---
		long turbineMs = turbineTotalNs / 1_000_000;
		long ecjMs = ecjTotalNs / 1_000_000;

		System.out.println();
		System.out.println("=== Results ==="); //$NON-NLS-1$
		System.out.printf("Files:                 %d (%d read errors)%n", total, readErrors); //$NON-NLS-1$
		System.out.printf("Turbine time:          %d ms (%d failures)%n", turbineMs, turbineFailures); //$NON-NLS-1$
		System.out.printf("ECJ time:              %d ms (%d errors)%n", ecjMs, ecjErrors); //$NON-NLS-1$
		if (turbineMs > 0) {
			System.out.printf("Speedup:               %.1fx%n", (double) ecjMs / turbineMs); //$NON-NLS-1$
		}
		if (turbineFailures == total - readErrors) {
			System.err.println("ERROR: Turbine failed for ALL files. Is org.eclipse.jdt.turbine on the classpath?"); //$NON-NLS-1$
		}
		System.out.printf("Files with decl diffs: %d%n", filesWithDeclDiffs); //$NON-NLS-1$

		if (!differences.isEmpty()) {
			System.out.println();
			System.out.println("=== Declaration differences ==="); //$NON-NLS-1$
			for (Map.Entry<String, List<String>> entry : differences.entrySet()) {
				System.out.println("[" + entry.getKey() + "]"); //$NON-NLS-1$ //$NON-NLS-2$
				for (String diff : entry.getValue()) {
					System.out.println(diff);
				}
			}
			if (filesWithDeclDiffs > differences.size()) {
				System.out.printf("... and %d more files with differences%n", //$NON-NLS-1$
						filesWithDeclDiffs - differences.size());
			}
		} else {
			System.out.println("No declaration differences found!"); //$NON-NLS-1$
		}
	}

	private static List<Path> scanJavaFiles(Path root) {
		System.out.println("Scanning: " + root); //$NON-NLS-1$
		List<Path> javaFiles = new ArrayList<>();
		try {
			Files.walkFileTree(root, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					if (file.toString().endsWith(".java")) { //$NON-NLS-1$
						javaFiles.add(file);
					}
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			System.err.println("Error scanning directory: " + e.getMessage()); //$NON-NLS-1$
			System.exit(1);
		}
		return javaFiles;
	}

	private static char[] readSource(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8).toCharArray();
		} catch (IOException e) {
			return null;
		}
	}

	private static Set<String> filterDeclarations(List<String> entries) {
		Set<String> result = new TreeSet<>();
		for (String entry : entries) {
			int slash = entry.indexOf('/');
			if (slash > 0) {
				String category = entry.substring(0, slash);
				if (DECLARATION_CATEGORIES.contains(category)) {
					result.add(entry);
				}
			}
		}
		return result;
	}
}
