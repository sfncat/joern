package io.joern.c2cpg.astcreation

import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.generated.{DispatchTypes, Operators}
import io.joern.x2cpg.{Ast, ValidationMode}
import io.joern.x2cpg.Defines as X2CpgDefines
import org.eclipse.cdt.core.dom.ast.*
import org.eclipse.cdt.core.dom.ast.cpp.*
import org.eclipse.cdt.internal.core.dom.parser.cpp.CPPASTAliasDeclaration
import org.eclipse.cdt.internal.core.model.ASTStringUtil
import io.joern.x2cpg.datastructures.Stack.*
import org.apache.commons.lang3.StringUtils

trait AstForTypesCreator(implicit withSchemaValidation: ValidationMode) { this: AstCreator =>

  private def parentIsClassDef(node: IASTNode): Boolean = Option(node.getParent) match {
    case Some(_: IASTCompositeTypeSpecifier) => true
    case _                                   => false
  }

  private def isTypeDef(decl: IASTSimpleDeclaration): Boolean = decl.getRawSignature.startsWith("typedef")

  private def templateParameters(e: IASTNode): Option[String] = {
    val templateDeclaration = e match {
      case _: IASTElaboratedTypeSpecifier | _: IASTFunctionDeclarator | _: IASTCompositeTypeSpecifier
          if e.getParent != null =>
        Option(e.getParent.getParent)
      case _: IASTFunctionDefinition if e.getParent != null => Option(e.getParent)
      case _                                                => None
    }

    val decl           = templateDeclaration.collect { case t: ICPPASTTemplateDeclaration => t }
    val templateParams = decl.map(d => ASTStringUtil.getTemplateParameterArray(d.getTemplateParameters))
    templateParams.map(_.mkString("<", ",", ">"))
  }

  private def astForNamespaceDefinition(namespaceDefinition: ICPPASTNamespaceDefinition): Ast = {
    val TypeFullNameInfo(name, fullName) = typeFullNameInfo(namespaceDefinition)
    val codeString                       = code(namespaceDefinition)
    val cpgNamespace =
      newNamespaceBlockNode(namespaceDefinition, name, fullName, codeString, fileName(namespaceDefinition))
    scope.pushNewScope(cpgNamespace)

    val childrenAsts = namespaceDefinition.getDeclarations.flatMap { decl =>
      val declAsts = astsForDeclaration(decl)
      declAsts
    }.toIndexedSeq

    val namespaceAst = Ast(cpgNamespace).withChildren(childrenAsts)
    scope.popScope()
    namespaceAst
  }

  protected def astForNamespaceAlias(namespaceAlias: ICPPASTNamespaceAlias): Ast = {
    val TypeFullNameInfo(name, fullName) = typeFullNameInfo(namespaceAlias)
    if (!isQualifiedName(name)) {
      usingDeclarationMappings.put(name, fullName)
    }
    val codeString   = code(namespaceAlias)
    val cpgNamespace = newNamespaceBlockNode(namespaceAlias, name, fullName, codeString, fileName(namespaceAlias))
    Ast(cpgNamespace)
  }

  private def isAssignmentFromBrokenMacro(declaration: IASTSimpleDeclaration, declarator: IASTDeclarator): Boolean =
    declaration.getParent.isInstanceOf[IASTTranslationUnit] &&
      declarator.getInitializer.isInstanceOf[IASTEqualsInitializer]

  protected def astForDeclarator(declaration: IASTSimpleDeclaration, declarator: IASTDeclarator, index: Int): Ast = {
    val name = shortName(declarator)
    declaration match {
      case d if isTypeDef(d) && shortName(d.getDeclSpecifier).nonEmpty =>
        val filename = fileName(declaration)
        val typeDefName = if (name.isEmpty) {
          safeGetBinding(declarator.getName).map(b => registerType(b.getName))
        } else {
          Option(registerType(name))
        }
        val tpe = registerType(typeFor(declarator))
        Ast(
          typeDeclNode(
            declarator,
            typeDefName.getOrElse(name),
            typeDefName.getOrElse(name),
            filename,
            code(d),
            alias = Option(tpe)
          )
        )
      case d if parentIsClassDef(d) =>
        val tpe = declarator match {
          case _: IASTArrayDeclarator => registerType(cleanType(typeFor(declarator)))
          case _ => registerType(cleanType(typeForDeclSpecifier(declaration.getDeclSpecifier, index = index)))
        }
        Ast(memberNode(declarator, name, code(declarator), tpe))
      case d if isAssignmentFromBrokenMacro(d, declarator) && scope.lookupVariable(name).nonEmpty =>
        Ast()
      case _ =>
        val tpe = declarator match {
          case arrayDecl: IASTArrayDeclarator => registerType(cleanType(typeFor(arrayDecl)))
          case _ => registerType(cleanType(typeForDeclSpecifier(declaration.getDeclSpecifier, index = index)))
        }
        val code = codeForDeclarator(declaration, declarator)
        val node = localNode(declarator, name, code, tpe)
        scope.addToScope(name, (node, tpe))
        Ast(node)
    }
  }

  private def codeForDeclarator(declaration: IASTSimpleDeclaration, declarator: IASTDeclarator): String = {
    val specCode    = declaration.getDeclSpecifier.getRawSignature
    val declCodeRaw = declarator.getRawSignature
    val declCode = declarator.getInitializer match {
      case null => declCodeRaw
      case _    => declCodeRaw.replace(declarator.getInitializer.getRawSignature, "")
    }
    val normalizedCode = StringUtils.normalizeSpace(s"$specCode $declCode")
    normalizedCode.strip()
  }

  protected def astForInitializer(declarator: IASTDeclarator, init: IASTInitializer): Ast = init match {
    case i: IASTEqualsInitializer =>
      val operatorName = Operators.assignment
      val callNode_ =
        callNode(
          declarator,
          code(declarator),
          operatorName,
          operatorName,
          DispatchTypes.STATIC_DISPATCH,
          None,
          Some(X2CpgDefines.Any)
        )
      val left  = astForNode(declarator.getName)
      val right = astForNode(i.getInitializerClause)
      callAst(callNode_, List(left, right))
    case i: ICPPASTConstructorInitializer =>
      val name = ASTStringUtil.getSimpleName(declarator.getName)
      val (idAst, tpe) = scope.lookupVariable(name) match {
        case Some((local, tpe)) =>
          val idNode = identifierNode(declarator.getName, name, name, registerType(tpe))
          (Ast(idNode).withRefEdge(idNode, local), tpe)
        case None => (Ast(identifierNode(declarator.getName, name, name, Defines.Any)), Defines.Any)
      }
      val operatorName = Operators.assignment
      val callNode_ =
        callNode(
          declarator,
          s"$name = $tpe${code(i)}",
          operatorName,
          operatorName,
          DispatchTypes.STATIC_DISPATCH,
          None,
          Some(tpe)
        )
      val args = List(idAst, astForNode(i))
      callAst(callNode_, args)
    case i: IASTInitializerList =>
      val operatorName = Operators.assignment
      val callNode_ =
        callNode(
          declarator,
          code(declarator),
          operatorName,
          operatorName,
          DispatchTypes.STATIC_DISPATCH,
          None,
          Some(X2CpgDefines.Any)
        )
      val left  = astForNode(declarator.getName)
      val right = astForNode(i)
      callAst(callNode_, List(left, right))
    case _ => astForNode(init)
  }

  protected def handleUsingDeclaration(usingDecl: ICPPASTUsingDeclaration): Seq[Ast] = {
    val simpleName = ASTStringUtil.getSimpleName(usingDecl.getName)
    val mappedName = lastNameOfQualifiedName(simpleName)
    // we only do the mapping if the declaration is not global because this is already handled by the parser itself
    if (!isQualifiedName(simpleName)) {
      usingDecl.getParent match {
        case ns: ICPPASTNamespaceDefinition =>
          usingDeclarationMappings.put(s"${fullName(ns)}.$mappedName", fixQualifiedName(simpleName))
        case _ =>
          usingDeclarationMappings.put(mappedName, fixQualifiedName(simpleName))
      }
    }
    Seq.empty
  }

  protected def astForAliasDeclaration(aliasDeclaration: ICPPASTAliasDeclaration): Ast = {
    val name       = aliasDeclaration.getAlias.toString
    val mappedName = registerType(typeFor(aliasDeclaration.getMappingTypeId))
    val typeDeclNode_ =
      typeDeclNode(
        aliasDeclaration,
        name,
        registerType(name),
        fileName(aliasDeclaration),
        code(aliasDeclaration),
        alias = Option(mappedName)
      )
    Ast(typeDeclNode_)
  }

  protected def astForASMDeclaration(asm: IASTASMDeclaration): Ast = Ast(unknownNode(asm, code(asm)))

  private def astForStructuredBindingDeclaration(decl: ICPPASTStructuredBindingDeclaration): Ast = {
    val node = blockNode(decl, Defines.Empty, Defines.Void)
    scope.pushNewScope(node)
    val childAsts = decl.getNames.toList.map { name =>
      astForNode(name)
    }
    scope.popScope()
    setArgumentIndices(childAsts)
    blockAst(node, childAsts)
  }

  protected def astsForDeclaration(decl: IASTDeclaration): Seq[Ast] = {
    val declAsts = decl match {
      case sb: ICPPASTStructuredBindingDeclaration => Seq(astForStructuredBindingDeclaration(sb))
      case declaration: IASTSimpleDeclaration =>
        declaration.getDeclSpecifier match {
          case spec: IASTCompositeTypeSpecifier =>
            astsForCompositeType(spec, declaration.getDeclarators.toList)
          case spec: IASTEnumerationSpecifier =>
            astsForEnum(spec, declaration.getDeclarators.toList)
          case spec: IASTElaboratedTypeSpecifier =>
            astsForElaboratedType(spec, declaration.getDeclarators.toList)
          case spec: IASTNamedTypeSpecifier if declaration.getDeclarators.isEmpty =>
            val filename = fileName(spec)
            val name     = ASTStringUtil.getSimpleName(spec.getName)
            Seq(Ast(typeDeclNode(spec, name, registerType(name), filename, code(spec), alias = Option(name))))
          case _ if declaration.getDeclarators.nonEmpty =>
            declaration.getDeclarators.toIndexedSeq.zipWithIndex.map {
              case (d: IASTFunctionDeclarator, _) =>
                astForFunctionDeclarator(d)
              case (d: IASTSimpleDeclaration, _) if d.getInitializer != null =>
                Ast() // we do the AST for this down below with initAsts
              case (d, i) =>
                astForDeclarator(declaration, d, i)
            }
          case _ if code(declaration) == ";" =>
            Seq.empty // dangling decls from unresolved macros; we ignore them
          case _ if declaration.getDeclarators.isEmpty && declaration.getParent.isInstanceOf[IASTTranslationUnit] =>
            Seq.empty // dangling decls from unresolved macros; we ignore them
          case _ if declaration.getDeclarators.isEmpty => Seq(astForNode(declaration))
        }
      case alias: CPPASTAliasDeclaration                   => Seq(astForAliasDeclaration(alias))
      case functionDefinition: IASTFunctionDefinition      => Seq(astForFunctionDefinition(functionDefinition))
      case namespaceAlias: ICPPASTNamespaceAlias           => Seq(astForNamespaceAlias(namespaceAlias))
      case namespaceDefinition: ICPPASTNamespaceDefinition => Seq(astForNamespaceDefinition(namespaceDefinition))
      case a: ICPPASTStaticAssertDeclaration               => Seq(astForStaticAssert(a))
      case asm: IASTASMDeclaration                         => Seq(astForASMDeclaration(asm))
      case t: ICPPASTTemplateDeclaration                   => astsForDeclaration(t.getDeclaration)
      case l: ICPPASTLinkageSpecification                  => astsForLinkageSpecification(l)
      case u: ICPPASTUsingDeclaration                      => handleUsingDeclaration(u)
      case _: ICPPASTVisibilityLabel                       => Seq.empty
      case _: ICPPASTUsingDirective                        => Seq.empty
      case _: ICPPASTExplicitTemplateInstantiation         => Seq.empty
      case _                                               => Seq(astForNode(decl))
    }

    val initAsts = decl match {
      case declaration: IASTSimpleDeclaration if declaration.getDeclarators.nonEmpty =>
        declaration.getDeclarators.toList.map {
          case d: IASTDeclarator if d.getInitializer != null =>
            astForInitializer(d, d.getInitializer)
          case arrayDecl: IASTArrayDeclarator =>
            val op = Operators.arrayInitializer
            val initCallNode =
              callNode(arrayDecl, code(arrayDecl), op, op, DispatchTypes.STATIC_DISPATCH, None, Some(X2CpgDefines.Any))
            val initArgs =
              arrayDecl.getArrayModifiers.toList.filter(m => m.getConstantExpression != null).map(astForNode)
            callAst(initCallNode, initArgs)
          case _ => Ast()
        }
      case _ => Nil
    }
    declAsts ++ initAsts
  }

  private def astsForLinkageSpecification(l: ICPPASTLinkageSpecification): Seq[Ast] =
    l.getDeclarations.toList.flatMap { d =>
      astsForDeclaration(d)
    }

  private def filterNameAlias(
    nameAlias: Option[String],
    nameWithTemplateParams: Option[String],
    fullName: String
  ): Option[String] = {
    (nameAlias.toList ++ nameWithTemplateParams.toList).filter(n => n != fullName).distinct.headOption
  }

  private def astsForCompositeType(typeSpecifier: IASTCompositeTypeSpecifier, decls: List[IASTDeclarator]): Seq[Ast] = {
    val filename = fileName(typeSpecifier)
    val declAsts = decls.zipWithIndex.map { case (d, i) =>
      astForDeclarator(typeSpecifier.getParent.asInstanceOf[IASTSimpleDeclaration], d, i)
    }

    val lineNumber                       = line(typeSpecifier)
    val columnNumber                     = column(typeSpecifier)
    val TypeFullNameInfo(name, fullName) = typeFullNameInfo(typeSpecifier)
    val codeString                       = code(typeSpecifier)
    val nameAlias                        = decls.headOption.map(d => registerType(shortName(d))).filter(_.nonEmpty)
    val nameWithTemplateParams           = templateParameters(typeSpecifier).map(t => registerType(s"$fullName$t"))
    val alias                            = filterNameAlias(nameAlias, nameWithTemplateParams, fullName)

    val typeDecl = typeSpecifier match {
      case cppClass: ICPPASTCompositeTypeSpecifier =>
        val baseClassList =
          cppClass.getBaseSpecifiers.toSeq.map(s => registerType(s.getNameSpecifier.toString))
        typeDeclNode(typeSpecifier, name, fullName, filename, codeString, inherits = baseClassList, alias = alias)
      case _ =>
        typeDeclNode(typeSpecifier, name, fullName, filename, codeString, alias = alias)
    }

    methodAstParentStack.push(typeDecl)
    scope.pushNewScope(typeDecl)

    val memberAsts = typeSpecifier.getDeclarations(true).toList.flatMap(astsForDeclaration)

    methodAstParentStack.pop()
    scope.popScope()

    val (calls, member) = memberAsts.partition(_.nodes.headOption.exists(_.isInstanceOf[NewCall]))
    if (calls.isEmpty) {
      Ast(typeDecl).withChildren(member) +: declAsts
    } else {
      val init = staticInitMethodAst(
        calls,
        s"$fullName.${io.joern.x2cpg.Defines.StaticInitMethodName}",
        None,
        Defines.Any,
        Some(filename),
        lineNumber,
        columnNumber
      )
      Ast(typeDecl).withChildren(member).withChild(init) +: declAsts
    }
  }

  private def astsForElaboratedType(
    typeSpecifier: IASTElaboratedTypeSpecifier,
    decls: List[IASTDeclarator]
  ): Seq[Ast] = {
    val filename = fileName(typeSpecifier)
    val declAsts = decls.zipWithIndex.map { case (d, i) =>
      astForDeclarator(typeSpecifier.getParent.asInstanceOf[IASTSimpleDeclaration], d, i)
    }
    val TypeFullNameInfo(name, fullName) = typeFullNameInfo(typeSpecifier)
    val nameAlias                        = decls.headOption.map(d => registerType(shortName(d))).filter(_.nonEmpty)
    val nameWithTemplateParams           = templateParameters(typeSpecifier).map(t => registerType(s"$fullName$t"))
    val alias                            = filterNameAlias(nameAlias, nameWithTemplateParams, fullName)
    val typeDecl = typeDeclNode(typeSpecifier, name, fullName, filename, code(typeSpecifier), alias = alias)
    Ast(typeDecl) +: declAsts
  }

  private def astsForEnumerator(enumerator: IASTEnumerationSpecifier.IASTEnumerator): Seq[Ast] = {
    val tpe = enumerator.getParent match {
      case enumeration: ICPPASTEnumerationSpecifier if enumeration.getBaseType != null =>
        enumeration.getBaseType.toString
      case _ => typeFor(enumerator)
    }
    val cpgMember = memberNode(
      enumerator,
      ASTStringUtil.getSimpleName(enumerator.getName),
      code(enumerator),
      registerType(cleanType(tpe))
    )

    if (enumerator.getValue != null) {
      val operatorName = Operators.assignment
      val callNode_ =
        callNode(
          enumerator,
          code(enumerator),
          operatorName,
          operatorName,
          DispatchTypes.STATIC_DISPATCH,
          None,
          Some(X2CpgDefines.Any)
        )
      val left  = astForNode(enumerator.getName)
      val right = astForNode(enumerator.getValue)
      val ast   = callAst(callNode_, List(left, right))
      Seq(Ast(cpgMember), ast)
    } else {
      Seq(Ast(cpgMember))
    }
  }

  private def astsForEnum(typeSpecifier: IASTEnumerationSpecifier, decls: List[IASTDeclarator]): Seq[Ast] = {
    val filename = fileName(typeSpecifier)
    val declAsts = decls.zipWithIndex.map { case (d, i) =>
      astForDeclarator(typeSpecifier.getParent.asInstanceOf[IASTSimpleDeclaration], d, i)
    }

    val lineNumber                       = line(typeSpecifier)
    val columnNumber                     = column(typeSpecifier)
    val TypeFullNameInfo(name, fullName) = typeFullNameInfo(typeSpecifier)
    val nameAlias                        = decls.headOption.map(d => registerType(shortName(d))).filter(_.nonEmpty)
    val alias                            = filterNameAlias(nameAlias, None, fullName)

    val (deAliasedName, deAliasedFullName, newAlias) = if (name.contains("anonymous_enum") && alias.isDefined) {
      (alias.get, fullName.substring(0, fullName.indexOf("anonymous_enum")) + alias.get, None)
    } else { (name, fullName, alias) }

    val typeDecl =
      typeDeclNode(
        typeSpecifier,
        deAliasedName,
        registerType(deAliasedFullName),
        filename,
        code(typeSpecifier),
        alias = newAlias
      )
    methodAstParentStack.push(typeDecl)
    scope.pushNewScope(typeDecl)

    val memberAsts = typeSpecifier.getEnumerators.toList.flatMap { e =>
      astsForEnumerator(e)
    }
    methodAstParentStack.pop()
    scope.popScope()

    val (calls, member) = memberAsts.partition(_.nodes.headOption.exists(_.isInstanceOf[NewCall]))
    if (calls.isEmpty) {
      Ast(typeDecl).withChildren(member) +: declAsts
    } else {
      val init = staticInitMethodAst(
        calls,
        s"$deAliasedFullName:${io.joern.x2cpg.Defines.StaticInitMethodName}",
        None,
        Defines.Any,
        Some(filename),
        lineNumber,
        columnNumber
      )
      Ast(typeDecl).withChildren(member).withChild(init) +: declAsts
    }
  }

}
