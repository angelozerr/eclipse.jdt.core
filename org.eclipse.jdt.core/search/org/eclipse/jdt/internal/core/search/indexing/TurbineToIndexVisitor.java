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

import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.*;
import com.google.turbine.tree.TurbineModifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.lookup.ExtraCompilerModifiers;
import org.eclipse.jdt.internal.compiler.lookup.TypeConstants;

/**
 * Visits a Turbine AST to feed the JDT index. Similar to {@link DOMToIndexVisitor} but using
 * Google Turbine's lightweight parser instead of ECJ or DOM.
 * <p>
 * Turbine only parses declarations (class/method/field signatures) and skips method bodies,
 * making it significantly faster than ECJ for indexing purposes. The trade-off is that
 * references within method bodies (method calls, field accesses, type usages) are not indexed.
 */
class TurbineToIndexVisitor implements Tree.Visitor<Void, Void> {

	private final SourceIndexer sourceIndexer;
	private final String documentPath;

	private char[] packageName = CharOperation.NO_CHAR;
	private final List<TyDecl> enclosingTypes = new ArrayList<>();

	TurbineToIndexVisitor(SourceIndexer sourceIndexer, String documentPath) {
		this.sourceIndexer = sourceIndexer;
		this.documentPath = documentPath;
	}

	// --- Core visitors that feed the index ---

	@Override
	public Void visitCompUnit(CompUnit node, Void input) {
		node.pkg().ifPresent(pkg -> pkg.accept(this, null));
		for (ImportDecl imp : node.imports()) {
			imp.accept(this, null);
		}
		node.mod().ifPresent(mod -> mod.accept(this, null));
		for (TyDecl decl : node.decls()) {
			decl.accept(this, null);
		}
		return null;
	}

	@Override
	public Void visitPkgDecl(PkgDecl node, Void input) {
		this.packageName = identsToQualifiedName(node.name());
		return null;
	}

	@Override
	public Void visitTyDecl(TyDecl node, Void input) {
		char[] typeName = node.name().value().toCharArray();
		int modifiers = turbineModsToJDTMods(node.mods()) | maybeDeprecated(node.annos());
		boolean secondary = isSecondary(typeName);
		char[][] enclosing = buildEnclosingTypeNames();

		char[][] typeParameterSignatures = null;
		if (!node.typarams().isEmpty()) {
			typeParameterSignatures = new char[node.typarams().size()][];
			for (int i = 0; i < node.typarams().size(); i++) {
				TyParam tp = node.typarams().get(i);
				char[][] bounds = tp.bounds().isEmpty() ? CharOperation.NO_CHAR_CHAR
						: tp.bounds().stream()
								.map(this::treeToTypeName)
								.toArray(char[][]::new);
				typeParameterSignatures[i] = Signature.createTypeParameterSignature(
						tp.name().value().toCharArray(), bounds);
			}
		}

		switch (node.tykind()) {
			case CLASS:
			case RECORD: {
				char[] superclass = node.xtnds().map(this::classTyToSimpleName).orElse(null);
				char[][] superinterfaces = node.impls().stream()
						.map(this::classTyToSimpleName).toArray(char[][]::new);
				this.sourceIndexer.addClassDeclaration(modifiers, this.packageName, typeName,
						enclosing, superclass, superinterfaces, typeParameterSignatures, secondary);
				if (superclass != null) {
					this.sourceIndexer.addTypeReference(superclass);
					this.sourceIndexer.addConstructorReference(superclass, 0);
				}
				addTypeReferences(superinterfaces);
				if (node.tykind() == TurbineTyKind.RECORD) {
					addRecordConstructor(node, typeName, modifiers);
				} else {
					addDefaultConstructorIfNecessary(node, typeName, modifiers);
				}
				break;
			}
			case INTERFACE: {
				char[][] superinterfaces = node.impls().stream()
						.map(this::classTyToSimpleName).toArray(char[][]::new);
				this.sourceIndexer.addInterfaceDeclaration(modifiers, this.packageName, typeName,
						enclosing, superinterfaces, typeParameterSignatures, secondary);
				addTypeReferences(superinterfaces);
				break;
			}
			case ENUM: {
				char[] superclass = CharOperation.concatWith(TypeConstants.JAVA_LANG_ENUM, '.');
				char[][] superinterfaces = node.impls().stream()
						.map(this::classTyToSimpleName).toArray(char[][]::new);
				this.sourceIndexer.addEnumDeclaration(modifiers, this.packageName, typeName,
						enclosing, superclass, superinterfaces, secondary);
				addTypeReferences(superinterfaces);
				addDefaultConstructorIfNecessary(node, typeName, modifiers);
				break;
			}
			case ANNOTATION: {
				this.sourceIndexer.addAnnotationTypeDeclaration(modifiers, this.packageName,
						typeName, enclosing, secondary);
				break;
			}
		}

		// Visit members
		this.enclosingTypes.add(node);
		for (Tree member : node.members()) {
			member.accept(this, null);
		}
		this.enclosingTypes.remove(this.enclosingTypes.size() - 1);
		return null;
	}

	@Override
	public Void visitMethDecl(MethDecl node, Void input) {
		char[] methodName = node.name().value().toCharArray();
		boolean isConstructor = node.ret().isEmpty();

		char[][] parameterTypes = node.params().stream()
				.map(p -> treeToTypeName(p.ty()))
				.toArray(char[][]::new);
		char[][] parameterNames = node.params().stream()
				.map(p -> p.name().value().toCharArray())
				.toArray(char[][]::new);
		char[][] exceptionTypes = node.exntys().stream()
				.map(this::classTyToSimpleName)
				.toArray(char[][]::new);
		int modifiers = turbineModsToJDTMods(node.mods()) | maybeDeprecated(node.annos());

		addTypeReferences(parameterTypes);
		addTypeReferences(exceptionTypes);

		if (isConstructor) {
			int typeModifiers = this.enclosingTypes.isEmpty() ? 0
					: turbineModsToJDTMods(currentType().mods());
			this.sourceIndexer.addConstructorDeclaration(
					methodName,
					parameterTypes.length,
					null,
					parameterTypes,
					parameterNames,
					modifiers,
					this.packageName,
					typeModifiers,
					exceptionTypes,
					0);
		} else {
			char[] returnType = node.ret().map(this::treeToTypeName).orElse(null);
			if (returnType != null) {
				this.sourceIndexer.addTypeReference(returnType);
			}
			this.sourceIndexer.addMethodDeclaration(methodName, parameterTypes, returnType, exceptionTypes);
			if (!this.enclosingTypes.isEmpty()) {
				TyDecl enclosing = currentType();
				this.sourceIndexer.addMethodDeclaration(
						enclosing.name().value().toCharArray(),
						null,
						methodName,
						parameterTypes.length,
						null,
						parameterTypes,
						parameterNames,
						returnType,
						modifiers,
						this.packageName,
						turbineModsToJDTMods(enclosing.mods()),
						exceptionTypes,
						0);
			}
		}

		// Index annotations on the method
		for (Anno anno : node.annos()) {
			anno.accept(this, null);
		}
		// Index annotations and types on parameters
		for (VarDecl param : node.params()) {
			for (Anno anno : param.annos()) {
				anno.accept(this, null);
			}
		}
		return null;
	}

	@Override
	public Void visitVarDecl(VarDecl node, Void input) {
		if (this.enclosingTypes.isEmpty()) {
			return null;
		}
		char[] typeName = treeToTypeName(node.ty());
		char[] fieldName = node.name().value().toCharArray();
		this.sourceIndexer.addFieldDeclaration(typeName, fieldName);
		if (typeName != null) {
			this.sourceIndexer.addTypeReference(typeName);
		}

		// If this is an enum constant, add a constructor reference
		if (currentType().tykind() == TurbineTyKind.ENUM
				&& node.mods().contains(TurbineModifier.ACC_ENUM)) {
			this.sourceIndexer.addConstructorReference(
					currentType().name().value().toCharArray(), 0);
		}

		// Index annotations on the field
		for (Anno anno : node.annos()) {
			anno.accept(this, null);
		}
		return null;
	}

	@Override
	public Void visitImportDecl(ImportDecl node, Void input) {
		if (node.stat() && !node.wild()) {
			// static non-wildcard import: could be method or field
			char[] simpleName = node.type().get(node.type().size() - 1).value().toCharArray();
			this.sourceIndexer.addMethodReference(simpleName, 0);
		}
		if (!node.wild()) {
			char[] fullName = identsToQualifiedName(node.type());
			this.sourceIndexer.addTypeReference(fullName);
		}
		return null;
	}

	@Override
	public Void visitAnno(Anno node, Void input) {
		char[] simpleName = node.name().get(node.name().size() - 1).value().toCharArray();
		this.sourceIndexer.addAnnotationTypeReference(simpleName);
		return null;
	}

	// --- Module visitors ---

	@Override
	public Void visitModDecl(ModDecl node, Void input) {
		this.sourceIndexer.addModuleDeclaration(node.moduleName().toCharArray());
		for (ModDirective directive : node.directives()) {
			directive.accept(this, null);
		}
		return null;
	}

	@Override
	public Void visitModRequires(ModRequires node, Void input) {
		this.sourceIndexer.addModuleReference(node.moduleName().toCharArray());
		return null;
	}

	@Override
	public Void visitModExports(ModExports node, Void input) {
		this.sourceIndexer.addModuleExportedPackages(node.packageName().toCharArray());
		for (String moduleName : node.moduleNames()) {
			this.sourceIndexer.addModuleReference(moduleName.toCharArray());
		}
		return null;
	}

	@Override
	public Void visitModOpens(ModOpens node, Void input) {
		this.sourceIndexer.addModuleExportedPackages(node.packageName().toCharArray());
		for (String moduleName : node.moduleNames()) {
			this.sourceIndexer.addModuleReference(moduleName.toCharArray());
		}
		return null;
	}

	@Override
	public Void visitModUses(ModUses node, Void input) {
		this.sourceIndexer.addTypeReference(identsToQualifiedName(node.typeName()));
		return null;
	}

	@Override
	public Void visitModProvides(ModProvides node, Void input) {
		this.sourceIndexer.addTypeReference(identsToQualifiedName(node.typeName()));
		for (var implName : node.implNames()) {
			this.sourceIndexer.addTypeReference(identsToQualifiedName(implName));
		}
		return null;
	}

	// --- No-op visitors for nodes we don't need to index ---

	@Override
	public Void visitIdent(Ident node, Void input) { return null; }
	@Override
	public Void visitWildTy(WildTy node, Void input) { return null; }
	@Override
	public Void visitArrTy(ArrTy node, Void input) { return null; }
	@Override
	public Void visitPrimTy(PrimTy node, Void input) { return null; }
	@Override
	public Void visitVoidTy(VoidTy node, Void input) { return null; }
	@Override
	public Void visitClassTy(ClassTy node, Void input) { return null; }
	@Override
	public Void visitLiteral(com.google.turbine.tree.Tree.Literal node, Void input) { return null; }
	@Override
	public Void visitParen(Paren node, Void input) { return null; }
	@Override
	public Void visitTypeCast(TypeCast node, Void input) { return null; }
	@Override
	public Void visitUnary(Unary node, Void input) { return null; }
	@Override
	public Void visitBinary(Binary node, Void input) { return null; }
	@Override
	public Void visitConstVarName(ConstVarName node, Void input) { return null; }
	@Override
	public Void visitClassLiteral(ClassLiteral node, Void input) { return null; }
	@Override
	public Void visitAssign(Assign node, Void input) { return null; }
	@Override
	public Void visitConditional(Conditional node, Void input) { return null; }
	@Override
	public Void visitArrayInit(ArrayInit node, Void input) { return null; }
	@Override
	public Void visitTyParam(TyParam node, Void input) { return null; }

	// --- Helper methods ---

	private void addTypeReferences(char[][] typeNames) {
		for (char[] typeName : typeNames) {
			if (typeName != null) {
				this.sourceIndexer.addTypeReference(typeName);
			}
		}
	}

	private TyDecl currentType() {
		return this.enclosingTypes.get(this.enclosingTypes.size() - 1);
	}

	private char[][] buildEnclosingTypeNames() {
		if (this.enclosingTypes.isEmpty()) {
			return null;
		}
		return this.enclosingTypes.stream()
				.map(ty -> ty.name().value().toCharArray())
				.toArray(char[][]::new);
	}

	private boolean isSecondary(char[] typeName) {
		if (!this.enclosingTypes.isEmpty()) {
			return false;
		}
		String fileName = Path.of(this.documentPath).getFileName().toString();
		return !fileName.equals(new String(typeName) + ".java"); //$NON-NLS-1$
	}

	private void addDefaultConstructorIfNecessary(TyDecl typeDecl, char[] typeName, int modifiers) {
		boolean hasExplicitConstructor = typeDecl.members().stream()
				.anyMatch(m -> m instanceof MethDecl meth && meth.ret().isEmpty());
		if (!hasExplicitConstructor) {
			this.sourceIndexer.addDefaultConstructorDeclaration(typeName,
					this.packageName, modifiers, 0);
		}
	}

	private void addRecordConstructor(TyDecl typeDecl, char[] typeName, int modifiers) {
		boolean hasExplicitConstructor = typeDecl.members().stream()
				.anyMatch(m -> m instanceof MethDecl meth && meth.ret().isEmpty());
		if (!hasExplicitConstructor && !typeDecl.components().isEmpty()) {
			int argCount = typeDecl.components().size();
			char[][] parameterTypes = new char[argCount][];
			char[][] parameterNames = new char[argCount][];
			for (int i = 0; i < argCount; i++) {
				VarDecl comp = typeDecl.components().get(i);
				parameterTypes[i] = treeToTypeName(comp.ty());
				parameterNames[i] = comp.name().value().toCharArray();
			}
			this.sourceIndexer.addConstructorDeclaration(
					typeName, argCount, null,
					parameterTypes, parameterNames,
					modifiers, this.packageName, modifiers,
					CharOperation.NO_CHAR_CHAR, 0);
		} else if (!hasExplicitConstructor) {
			this.sourceIndexer.addDefaultConstructorDeclaration(typeName,
					this.packageName, modifiers, 0);
		}
	}

	private char[] classTyToSimpleName(ClassTy classTy) {
		return classTy.name().value().toCharArray();
	}

	private char[] treeToTypeName(Tree tree) {
		if (tree == null) {
			return null;
		}
		if (tree instanceof ClassTy classTy) {
			return classTyToSimpleName(classTy);
		}
		if (tree instanceof PrimTy primTy) {
			return primTy.tykind().toString().toLowerCase().toCharArray();
		}
		if (tree instanceof VoidTy) {
			return "void".toCharArray(); //$NON-NLS-1$
		}
		if (tree instanceof ArrTy arrTy) {
			char[] elemType = treeToTypeName(arrTy.elem());
			if (elemType != null) {
				char[] result = new char[elemType.length + 2];
				System.arraycopy(elemType, 0, result, 0, elemType.length);
				result[elemType.length] = '[';
				result[elemType.length + 1] = ']';
				return result;
			}
		}
		return tree.toString().toCharArray();
	}

	private static char[] identsToQualifiedName(
			com.google.common.collect.ImmutableList<Ident> idents) {
		if (idents.size() == 1) {
			return idents.get(0).value().toCharArray();
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < idents.size(); i++) {
			if (i > 0) sb.append('.');
			sb.append(idents.get(i).value());
		}
		return sb.toString().toCharArray();
	}

	private static int turbineModsToJDTMods(Set<TurbineModifier> mods) {
		int result = 0;
		for (TurbineModifier mod : mods) {
			switch (mod) {
				case PUBLIC: result |= ClassFileConstants.AccPublic; break;
				case PRIVATE: result |= ClassFileConstants.AccPrivate; break;
				case PROTECTED: result |= ClassFileConstants.AccProtected; break;
				case STATIC: result |= ClassFileConstants.AccStatic; break;
				case FINAL: result |= ClassFileConstants.AccFinal; break;
				case SYNCHRONIZED: result |= ClassFileConstants.AccSynchronized; break;
				case VOLATILE: result |= ClassFileConstants.AccVolatile; break;
				case TRANSIENT: result |= ClassFileConstants.AccTransient; break;
				case NATIVE: result |= ClassFileConstants.AccNative; break;
				case INTERFACE: result |= ClassFileConstants.AccInterface; break;
				case ABSTRACT: result |= ClassFileConstants.AccAbstract; break;
				case STRICTFP: result |= ClassFileConstants.AccStrictfp; break;
				case DEFAULT: result |= ClassFileConstants.AccDefault; break;
				case SEALED: result |= ExtraCompilerModifiers.AccSealed; break;
				case NON_SEALED: result |= ExtraCompilerModifiers.AccNonSealed; break;
				default: break;
			}
		}
		return result;
	}

	private static int maybeDeprecated(
			com.google.common.collect.ImmutableList<Anno> annos) {
		for (Anno anno : annos) {
			String simpleName = anno.name().get(anno.name().size() - 1).value();
			if ("Deprecated".equals(simpleName)) { //$NON-NLS-1$
				return ClassFileConstants.AccDeprecated;
			}
		}
		return 0;
	}
}
