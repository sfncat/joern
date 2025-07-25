package io.joern.x2cpg.frontendspecific.php2cpg

import io.joern.x2cpg.Defines
import io.joern.x2cpg.passes.frontend.*
import io.joern.x2cpg.passes.frontend.XTypeRecovery.AllNodeTypesFromNodeExt
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.generated.{Cpg, DispatchTypes, Operators, PropertyNames}
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.operatorextension.OpNodes
import io.shiftleft.semanticcpg.language.operatorextension.OpNodes.{Assignment, FieldAccess}
import io.shiftleft.codepropertygraph.generated.DiffGraphBuilder

import scala.collection.mutable

class PhpTypeRecoveryPassGenerator(cpg: Cpg, config: XTypeRecoveryConfig = XTypeRecoveryConfig(iterations = 3))
    extends XTypeRecoveryPassGenerator[NamespaceBlock](cpg, config) {

  override protected def generateRecoveryPass(
    state: XTypeRecoveryState,
    iteration: Int
  ): XTypeRecovery[NamespaceBlock] =
    new PhpTypeRecovery(cpg, state, iteration)
}

private class PhpTypeRecovery(cpg: Cpg, state: XTypeRecoveryState, iteration: Int)
    extends XTypeRecovery[NamespaceBlock](cpg, state, iteration) {

  override def compilationUnits: Iterator[NamespaceBlock] = cpg.file.namespaceBlock.iterator

  override def generateRecoveryForCompilationUnitTask(
    unit: NamespaceBlock,
    builder: DiffGraphBuilder
  ): RecoverForXCompilationUnit[NamespaceBlock] = {
    new RecoverForPhpFile(cpg, unit, builder, state)
  }
}

private class RecoverForPhpFile(cpg: Cpg, cu: NamespaceBlock, builder: DiffGraphBuilder, state: XTypeRecoveryState)
    extends RecoverForXCompilationUnit[NamespaceBlock](cpg, cu, builder, state) {
  override protected def prepopulateSymbolTableEntry(x: AstNode): Unit = x match {
    case x: Call =>
      x.methodFullName match {
        case Operators.alloc =>
          val allocRecv = x.code.takeWhile(_ != '.')
          symbolTable.append(CallAlias(allocRecv), Set(x.typeFullName))
          symbolTable.append(LocalVar(allocRecv), Set(x.typeFullName))
        case s"<operator>.$_" =>
        case _                => symbolTable.append(x, (x.methodFullName +: x.dynamicTypeHintFullName).toSet)
      }
    case _ => super.prepopulateSymbolTableEntry(x)
  }

  protected val methodTypesTable = mutable.Map[Method, mutable.HashSet[String]]()

  override val pathSep: String = "."

  override def hasTypes(node: AstNode): Boolean = {
    node match {
      case x: Call => !XTypeRecovery.unknownTypePattern.matches(x.methodFullName)
      case _       => super.hasTypes(node)
    }
  }

  override def isConstructor(c: Call): Boolean =
    isConstructor(c.name) && c.code.endsWith(")")

  override protected def isConstructor(name: String): Boolean =
    !name.isBlank && name.charAt(0).isUpper

  override def assignments: Iterator[Assignment] =
    cu.ast.isCall.nameExact(Operators.assignment).cast[Assignment]

  protected def unresolvedDynamicCalls: Iterator[Call] = cu.ast.isCall
    .filter(_.dispatchType == DispatchTypes.DYNAMIC_DISPATCH)
    .filter(_.methodFullName.startsWith(Defines.UnresolvedNamespace))

  /* Register post-processing pass that executes in the super class */
  override protected def postSetTypeInformation(): Unit = {
    unresolvedDynamicCalls.foreach(visitUnresolvedDynamicCall)
  }
  override protected def visitIdentifierAssignedToConstructor(i: Identifier, c: Call): Set[String] = {
    val allocRecv        = c.code.takeWhile(_ != '.')
    val constructorPaths = symbolTable.get(CallAlias(allocRecv))
    associateTypes(i, constructorPaths)
  }

  override protected def visitIdentifierAssignedToCallRetVal(i: Identifier, c: Call): Set[String] = {

    if (symbolTable.contains(c)) {
      val callReturns = methodReturnValues(symbolTable.get(c).toSeq)
      associateTypes(i, callReturns)
    } else if (c.receiver.nonEmpty) {
      val callFullNames = (c.receiver.headOption match {
        case Some(i: Identifier) if symbolTable.contains(LocalVar(i.name))  => symbolTable.get(LocalVar(i.name))
        case Some(i: Identifier) if symbolTable.contains(CallAlias(i.name)) => symbolTable.get(CallAlias(i.name))
        case _                                                              => Set.empty
      }).map(_.concat(s"$pathSep${c.name}")).toSeq
      val callReturns = methodReturnValues(callFullNames)
      associateTypes(i, callReturns)
    } else {
      /* CPG may already contain type info for this method (globally, outside of compilation)
       * unit. If not, use dummy return value.
       */
      val rs = methodReturnValues(Seq(c.methodFullName))
      if (rs.isEmpty) associateTypes(i, Set(s"${c.name}$pathSep${XTypeRecovery.DummyReturnType}"))
      else associateTypes(i, rs)
    }
  }

  override protected def visitReturns(ret: Return): Unit = {
    /* A bug in XTypeRecovery mishandles functions that have multiple return
     * statements. We add a new "symbol table" (methodTypesTable) for method
     * return types as they get collected across the multiple return statements
     * for a single function.
     */
    val m = ret.method
    val existingTypes = mutable.HashSet.from(
      (m.methodReturn.typeFullName +: m.methodReturn.dynamicTypeHintFullName)
        .filterNot(_ == "ANY")
        .filterNot(_.startsWith(Defines.UnresolvedNamespace))
    )
    existingTypes.addAll(methodTypesTable.getOrElse(m, mutable.HashSet()))

    def appendDummyReturn(name: String, xs: Set[String]) = xs.map {
      case fn if fn.endsWith(XTypeRecovery.DummyReturnType) => fn
      case t => Seq(t, name, XTypeRecovery.DummyReturnType).mkString(pathSep)
    }

    def extractTypes(xs: List[CfgNode]): Set[String] = xs match {
      case ::(head: Literal, Nil) if head.typeFullName != "ANY" =>
        Set(head.typeFullName)
      case (head: Call) :: _ if head.name == Operators.fieldAccess =>
        val fieldAccess = head.asInstanceOf[FieldAccess]
        val (sym, ts)   = getSymbolFromCall(fieldAccess)
        val cpgTypes = cpg.typeDecl
          .fullNameExact(ts.map(_.compUnitFullName).toSeq*)
          .member
          .nameExact(sym.identifier)
          .flatMap(m => m.typeFullName +: m.dynamicTypeHintFullName)
          .filterNot { x => x == "ANY" || x == "this" }
          .toSet
        if (cpgTypes.nonEmpty) cpgTypes
        else if (symbolTable.get(sym).nonEmpty) symbolTable.get(sym)
        else if (fieldAccess.argument(1).code == "$this") head.method.typeDecl.fullName.toSet
        else Set.empty
      case (head: Call) :: _ if symbolTable.contains(head) =>
        val callPaths    = symbolTable.get(head)
        val returnValues = methodReturnValues(callPaths.toSeq)
        if (returnValues.isEmpty)
          callPaths.map(c => s"$c$pathSep${XTypeRecovery.DummyReturnType}")
        else
          returnValues
      case (head: Call) :: _ if head.receiver.headOption.exists(symbolTable.contains) =>
        appendDummyReturn(head.name, symbolTable.get(head.receiver.head))
      case ::(identifier: Identifier, Nil) if symbolTable.contains(identifier) =>
        symbolTable.get(identifier)
      case (head: Call) :: _ =>
        val callees = appendDummyReturn(head.name, extractTypes(head.argument.l))
        symbolTable.append(head, callees)
      case _ => Set.empty
    }
    val returnTypes = extractTypes(ret.argumentOut.cast[CfgNode].l)
    existingTypes.addAll(returnTypes)

    /* Check whether method return is already known, and if so, remove dummy value */
    val saveTypes = existingTypes.filterNot { typeName =>
      if (typeName.startsWith(Defines.UnresolvedNamespace)) {
        true
      } else if (typeName.endsWith(XTypeRecovery.DummyReturnType)) {
        if (typeName.contains("<metaclass>")) {
          typeName.split("<metaclass>").toList.reverse match {
            case methodNameWithReturn :: className :: Nil =>
              val methodName     = methodNameWithReturn.split("\\.").dropRight(1).mkString(".")
              val methodFullName = s"$className<metaclass>$methodName"
              val methodReturns =
                methodReturnValues(Seq(methodFullName)).filterNot(_.endsWith(s"${XTypeRecovery.DummyReturnType}"))
              methodReturns.nonEmpty
            case _ => false
          }
        } else {
          typeName.split(s"\\$pathSep").toList.reverse match {
            case _ :: methodFullName :: Nil =>
              val methodReturns = methodReturnValues(Seq(methodFullName))
                .filterNot(_.endsWith(s"${XTypeRecovery.DummyReturnType}"))
              methodReturns.nonEmpty
            case _ :: methodName :: typeFullName =>
              val methodFullName = Seq(s"${typeFullName.mkString(pathSep)}$pathSep$methodName")
              val methodReturns = methodReturnValues(methodFullName)
                .filterNot(_.endsWith(s"${XTypeRecovery.DummyReturnType}"))
              methodReturns.nonEmpty
            case _ => false
          }
        }
      } else {
        false
      }
    }
    methodTypesTable.update(m, saveTypes)
    builder.setNodeProperty(ret.method.methodReturn, PropertyNames.DynamicTypeHintFullName, saveTypes)
  }

  /* Necessary to change the filter regex from (this|self) to (\\$this|this), in order to account for $this PHP
   * convention.
   */
  override protected def associateTypes(symbol: LocalVar, fa: FieldAccess, types: Set[String]): Set[String] = {
    fa.astChildren.filterNot(_.code.matches("(\\$this|this|self)")).headOption.collect {
      case fi: FieldIdentifier =>
        getFieldParents(fa).foreach(t => persistMemberWithTypeDecl(t, fi.canonicalName, types))
      case i: Identifier if isField(i) =>
        getFieldParents(fa).foreach(t => persistMemberWithTypeDecl(t, i.name, types))
    }
    symbolTable.append(symbol, types)
  }

  /* Reference the PythonTypeRecovery implementation. The XTypeRecovery one seems incorrect. */
  override protected def getFieldParents(fa: FieldAccess): Set[String] = {
    if (fa.method.name == "<global>") {
      Set(fa.method.fullName)
    } else if (fa.method.typeDecl.nonEmpty) {
      val parentTypes       = fa.method.typeDecl.fullName.toSet
      val baseTypeFullNames = cpg.typeDecl.fullNameExact(parentTypes.toSeq*).inheritsFromTypeFullName.toSet
      (parentTypes ++ baseTypeFullNames).filterNot(_.matches("(?i)(any|object)"))
    } else {
      super.getFieldParents(fa)
    }
  }

  override protected def getTypesFromCall(c: Call): Set[String] = {
    lazy val methodReturnVals = methodReturnValues(Seq(c.methodFullName))
    c.name match {
      case Operators.fieldAccess => symbolTable.get(LocalVar(getFieldName(c.asInstanceOf[FieldAccess])))
      case Operators.indexAccess => getIndexAccessTypes(c)
      case _ if symbolTable.contains(c) && methodReturnVals.isEmpty => symbolTable.get(c)
      case _                                                        => methodReturnVals
    }
  }

  override protected def indexAccessToCollectionVar(c: Call): Option[CollectionVar] = {
    def callName(x: Call) =
      if (x.name == Operators.fieldAccess)
        getFieldName(x.asInstanceOf[FieldAccess])
      else if (x.name == Operators.indexAccess)
        indexAccessToCollectionVar(x)
          .map(cv => s"${cv.identifier}[${cv.idx}]")
          .getOrElse(XTypeRecovery.DummyIndexAccess)
      else x.name

    val collectionVar = Option(c.argumentOut.cast[CfgNode].l match {
      case List(i: Identifier, idx: Literal)    => CollectionVar(i.name, idx.code)
      case List(i: Identifier, idx: Identifier) => CollectionVar(i.name, idx.code)
      case List(c: Call, idx: Call)             => CollectionVar(callName(c), callName(idx))
      case List(c: Call, idx: Literal)          => CollectionVar(callName(c), idx.code)
      case List(c: Call, idx: Identifier)       => CollectionVar(callName(c), idx.code)
      case xs =>
        logger.debug(s"Unhandled index access ${xs.map(x => (x.label, x.code)).mkString(",")} @ ${c.name}")
        null
    })

    collectionVar
  }
  override protected def assignTypesToCall(x: Call, types: Set[String]): Set[String] = {
    if (types.nonEmpty) {
      getSymbolFromCall(x) match {
        case (lhs, globalKeys) if globalKeys.nonEmpty => {
          globalKeys.foreach { (fieldVar: FieldPath) =>
            persistMemberWithTypeDecl(fieldVar.compUnitFullName, fieldVar.identifier, types)
          }
          symbolTable.append(lhs, types)
        }
        case (lhs, _) => symbolTable.append(lhs, types)
      }
    } else Set.empty
  }

  override protected def methodReturnValues(methodFullNames: Seq[String]): Set[String] = {
    // Check inheritance for resolved method full name patterns
    val fullNames = {
      val foundMethodFullNames = methodFullNames.flatMap {
        case s"${typeFullName}$pathSep${methodName}" =>
          val targetTypes = cpg.typeDecl.fullNameExact(typeFullName).l
          val transtypes  = targetTypes.baseTypeDeclTransitive.l
          val methods     = transtypes.method.nameExact(methodName).l
          methods.fullName.toSet
        case _ => Set.empty
      }
      if foundMethodFullNames.nonEmpty then foundMethodFullNames else methodFullNames
    }

    /* Look up methods in existing CPG */
    val rs = cpg.method
      .fullNameExact(fullNames*)
      .methodReturn
      .flatMap(mr => mr.typeFullName +: mr.dynamicTypeHintFullName)
      .filterNot(_ == "ANY")
      .filterNot(_.endsWith("alloc.<init>"))
      .filterNot(_.endsWith(s"${XTypeRecovery.DummyReturnType}"))
      .toSet
    if (rs.isEmpty)
      /* Return dummy return type if not found */
      fullNames.flatMap {
        case m if m.endsWith(XTypeRecovery.DummyReturnType) => Set(m)
        case m => Set(m.concat(s"$pathSep${XTypeRecovery.DummyReturnType}"))
      }.toSet
    else rs
  }

  /* If we know the type of the method's first parameter, use that to determine the method scope.
   *
   * TODO: Are there methods / instances where this doesn't work? Static methods?
   * TODO: What if the first parameter could take multiple types?
   */
  private def visitUnresolvedDynamicCall(c: Call): Option[String] = {

    def setNodeFullName(tgt: CfgNode, newFullName: String): Option[String] = {
      if (tgt.isCall) builder.setNodeProperty(tgt, PropertyNames.MethodFullName, newFullName)
      builder.setNodeProperty(tgt, PropertyNames.TypeFullName, s"$newFullName$pathSep${XTypeRecovery.DummyReturnType}")
      builder.setNodeProperty(tgt, PropertyNames.DynamicTypeHintFullName, Seq.empty)
      Option(newFullName)
    }

    def setFromKnownTypes(i: CfgNode, tgt: CfgNode): Option[String] = {
      i.getKnownTypes.l match {
        case Nil      => None
        case t :: Nil => setNodeFullName(tgt, s"$t$pathSep${c.name}")
        case x        => None /* TODO: case where multiple possible types are identified */
      }
    }

    c.receiver.headOption.flatMap {
      case rc: Call if rc.methodFullName.startsWith("<operator") =>
        None // ignore operators
      case rc: Call if rc.methodFullName.startsWith(Defines.UnresolvedNamespace) =>
        // Helps deal with long call chains by attempting to perform an immediate resolve
        visitUnresolvedDynamicCall(rc).flatMap { rcFullName =>
          val newFullName = s"$rcFullName$pathSep${c.name}"
          setNodeFullName(c, newFullName)
        }
      case p: Identifier if p.name == "this" =>
        p.start.method.typeDecl
          .flatMap(x => x +: x.baseTypeDeclTransitive.toSeq)
          .where(_.method.nameExact(c.name))
          .fullName
          .headOption
          .flatMap(tfn => setNodeFullName(c, s"$tfn$pathSep${c.name}"))
      case p: Identifier => setFromKnownTypes(p, c)
      case rc: Call      => setFromKnownTypes(rc, c)
      case _             => None
    }
  }
}
