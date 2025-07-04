package io.joern.swiftsrc2cpg.astcreation

import io.joern.swiftsrc2cpg.parser.SwiftNodeSyntax.*
import io.joern.x2cpg.{Ast, ValidationMode}
import io.shiftleft.codepropertygraph.generated.{ControlStructureTypes, PropertyNames}

trait AstCreatorHelper(implicit withSchemaValidation: ValidationMode) { this: AstCreator =>

  protected def notHandledYet(node: SwiftNode): Ast = {
    val text =
      s"""Node type '${node.toString}' not handled yet!
         |  Code: '${code(node)}'
         |  File: '${parserResult.fullPath}'
         |  Line: ${line(node).getOrElse(-1)}
         |  Column: ${column(node).getOrElse(-1)}
         |  """.stripMargin
    logger.info(text)
    Ast(unknownNode(node, code(node)))
  }

  protected def astsForBlockElements(elements: List[SwiftNode]): List[Ast] = {
    val (deferElements: List[SwiftNode], otherElements: List[SwiftNode]) = elements.partition(n =>
      n.isInstanceOf[CodeBlockItemSyntax] && n.asInstanceOf[CodeBlockItemSyntax].item.isInstanceOf[DeferStmtSyntax]
    )
    val deferElementsAstsOrdered = deferElements.reverse.map(astForNode)
    val indexOfGuardStmt = otherElements.indexWhere(n =>
      n.isInstanceOf[CodeBlockItemSyntax] && n.asInstanceOf[CodeBlockItemSyntax].item.isInstanceOf[GuardStmtSyntax]
    )
    if (indexOfGuardStmt < 0) {
      otherElements.map(astForNode) ++ deferElementsAstsOrdered
    } else {
      val elementsBeforeGuard = otherElements.slice(0, indexOfGuardStmt)
      val guardStmt =
        otherElements(indexOfGuardStmt).asInstanceOf[CodeBlockItemSyntax].item.asInstanceOf[GuardStmtSyntax]
      val elementsAfterGuard = otherElements.slice(indexOfGuardStmt + 1, otherElements.size)

      val code         = this.code(guardStmt)
      val ifNode       = controlStructureNode(guardStmt, ControlStructureTypes.IF, code)
      val conditionAst = astForNode(guardStmt.conditions)

      val thenAst = astsForBlockElements(elementsAfterGuard) ++ deferElementsAstsOrdered match {
        case Nil => Ast()
        case blockElement :: Nil =>
          setOrderExplicitly(blockElement, 2)
          blockElement
        case blockChildren =>
          val block = blockNode(elementsAfterGuard.head).order(2)
          blockAst(block, blockChildren)
      }
      val elseAst = astForNode(guardStmt.body)
      setOrderExplicitly(elseAst, 3)

      val ifAst = controlStructureAst(ifNode, Option(conditionAst), Seq(thenAst, elseAst))
      astsForBlockElements(elementsBeforeGuard) :+ ifAst
    }
  }

  protected def astParentInfo(): (String, String) = {
    val astParentType     = methodAstParentStack.head.label
    val astParentFullName = methodAstParentStack.head.properties(PropertyNames.FULL_NAME).toString
    (astParentType, astParentFullName)
  }

  protected def registerType(typeFullName: String): Unit = {
    global.usedTypes.putIfAbsent(typeFullName, true)
  }

  protected def scopeLocalUniqueName(targetName: String): String = {
    val name = if (targetName.nonEmpty) { s"<$targetName>" }
    else { "<anonymous>" }
    val key = s"${scope.computeScopePath}:$name"
    val idx = scopeLocalUniqueNames.getOrElseUpdate(key, 0)
    scopeLocalUniqueNames.update(key, idx + 1)
    s"$name$idx"
  }

  protected def calcTypeNameAndFullName(name: String): (String, String) = {
    val fullNamePrefix = s"${parserResult.filename}:${scope.computeScopePath}:"
    val fullName       = s"$fullNamePrefix$name"
    (name, fullName)
  }

  protected def calcMethodNameAndFullName(func: SwiftNode): (String, String) = {
    val name           = calcMethodName(func)
    val fullNamePrefix = s"${parserResult.filename}:${scope.computeScopePath}:"
    val fullName       = s"$fullNamePrefix$name"
    (name, fullName)
  }

  private def calcMethodName(func: SwiftNode): String = func match {
    case f: FunctionDeclSyntax      => code(f.name)
    case a: AccessorDeclSyntax      => code(a.accessorSpecifier)
    case d: DeinitializerDeclSyntax => code(d.deinitKeyword)
    case i: InitializerDeclSyntax   => code(i.initKeyword)
    case _                          => nextClosureName()
  }

}
