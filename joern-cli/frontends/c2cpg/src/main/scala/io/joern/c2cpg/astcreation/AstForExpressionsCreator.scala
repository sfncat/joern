package io.joern.c2cpg.astcreation

import io.joern.x2cpg.Ast
import io.joern.x2cpg.datastructures.Stack.*
import io.joern.x2cpg.Defines as X2CpgDefines
import io.joern.x2cpg.datastructures.VariableScopeManager
import io.shiftleft.codepropertygraph.generated.{ControlStructureTypes, DispatchTypes, EvaluationStrategies, Operators}
import io.shiftleft.codepropertygraph.generated.nodes.ExpressionNew
import org.apache.commons.lang3.StringUtils
import org.eclipse.cdt.core.dom.ast
import org.eclipse.cdt.core.dom.ast.*
import org.eclipse.cdt.core.dom.ast.c.ICArrayType
import org.eclipse.cdt.core.dom.ast.cpp.*
import org.eclipse.cdt.core.dom.ast.gnu.IGNUASTCompoundStatementExpression
import org.eclipse.cdt.internal.core.dom.parser.c.CASTFunctionCallExpression
import org.eclipse.cdt.internal.core.dom.parser.c.CASTIdExpression
import org.eclipse.cdt.internal.core.dom.parser.c.CFunctionType
import org.eclipse.cdt.internal.core.dom.parser.c.CPointerType
import org.eclipse.cdt.internal.core.dom.parser.cpp.CPPASTIdExpression
import org.eclipse.cdt.internal.core.dom.parser.cpp.CPPASTQualifiedName
import org.eclipse.cdt.internal.core.dom.parser.cpp.CPPClosureType
import org.eclipse.cdt.internal.core.dom.parser.cpp.semantics.EvalFunctionCall
import org.eclipse.cdt.internal.core.dom.parser.cpp.CPPASTFoldExpression

import scala.annotation.tailrec
import scala.util.Try

trait AstForExpressionsCreator { this: AstCreator =>

  import FullNameProvider.stripTemplateTags

  private val OperatorMap: Map[Int, String] = Map(
    IASTBinaryExpression.op_multiply         -> Operators.multiplication,
    IASTBinaryExpression.op_divide           -> Operators.division,
    IASTBinaryExpression.op_modulo           -> Operators.modulo,
    IASTBinaryExpression.op_plus             -> Operators.addition,
    IASTBinaryExpression.op_minus            -> Operators.subtraction,
    IASTBinaryExpression.op_shiftLeft        -> Operators.shiftLeft,
    IASTBinaryExpression.op_shiftRight       -> Operators.arithmeticShiftRight,
    IASTBinaryExpression.op_lessThan         -> Operators.lessThan,
    IASTBinaryExpression.op_greaterThan      -> Operators.greaterThan,
    IASTBinaryExpression.op_lessEqual        -> Operators.lessEqualsThan,
    IASTBinaryExpression.op_greaterEqual     -> Operators.greaterEqualsThan,
    IASTBinaryExpression.op_binaryAnd        -> Operators.and,
    IASTBinaryExpression.op_binaryXor        -> Operators.xor,
    IASTBinaryExpression.op_binaryOr         -> Operators.or,
    IASTBinaryExpression.op_logicalAnd       -> Operators.logicalAnd,
    IASTBinaryExpression.op_logicalOr        -> Operators.logicalOr,
    IASTBinaryExpression.op_assign           -> Operators.assignment,
    IASTBinaryExpression.op_multiplyAssign   -> Operators.assignmentMultiplication,
    IASTBinaryExpression.op_divideAssign     -> Operators.assignmentDivision,
    IASTBinaryExpression.op_moduloAssign     -> Operators.assignmentModulo,
    IASTBinaryExpression.op_plusAssign       -> Operators.assignmentPlus,
    IASTBinaryExpression.op_minusAssign      -> Operators.assignmentMinus,
    IASTBinaryExpression.op_shiftLeftAssign  -> Operators.assignmentShiftLeft,
    IASTBinaryExpression.op_shiftRightAssign -> Operators.assignmentArithmeticShiftRight,
    IASTBinaryExpression.op_binaryAndAssign  -> Operators.assignmentAnd,
    IASTBinaryExpression.op_binaryXorAssign  -> Operators.assignmentXor,
    IASTBinaryExpression.op_binaryOrAssign   -> Operators.assignmentOr,
    IASTBinaryExpression.op_equals           -> Operators.equals,
    IASTBinaryExpression.op_notequals        -> Operators.notEquals,
    IASTBinaryExpression.op_pmdot            -> Operators.indirectFieldAccess,
    IASTBinaryExpression.op_pmarrow          -> Operators.indirectFieldAccess,
    IASTBinaryExpression.op_max              -> Defines.OperatorMax,
    IASTBinaryExpression.op_min              -> Defines.OperatorMin,
    IASTBinaryExpression.op_ellipses         -> Defines.OperatorEllipses
  )

  private val UnaryOperatorMap: Map[Int, String] = Map(
    IASTUnaryExpression.op_prefixIncr  -> Operators.preIncrement,
    IASTUnaryExpression.op_prefixDecr  -> Operators.preDecrement,
    IASTUnaryExpression.op_plus        -> Operators.plus,
    IASTUnaryExpression.op_minus       -> Operators.minus,
    IASTUnaryExpression.op_star        -> Operators.indirection,
    IASTUnaryExpression.op_amper       -> Operators.addressOf,
    IASTUnaryExpression.op_tilde       -> Operators.not,
    IASTUnaryExpression.op_not         -> Operators.logicalNot,
    IASTUnaryExpression.op_sizeof      -> Operators.sizeOf,
    IASTUnaryExpression.op_postFixIncr -> Operators.postIncrement,
    IASTUnaryExpression.op_postFixDecr -> Operators.postDecrement,
    IASTUnaryExpression.op_typeid      -> Defines.OperatorTypeOf
  )

  protected def astForExpression(expression: IASTExpression): Ast = {
    if (isUnsupportedCoroutineKeyword(expression)) {
      return astForUnsupportedCoroutineNode(expression)
    }

    val r = expression match {
      case lit: IASTLiteralExpression                                                => astForLiteral(lit)
      case un: IASTUnaryExpression if un.getOperator == IASTUnaryExpression.op_throw => astForThrowExpression(un)
      case un: IASTUnaryExpression                                                   => astForUnaryExpression(un)
      case bin: IASTBinaryExpression                                                 => astForBinaryExpression(bin)
      case exprList: IASTExpressionList                                              => astForExpressionList(exprList)
      case idExpr: IASTIdExpression                                                  => astForIdExpression(idExpr)
      case call: IASTFunctionCallExpression                                          => astForCallExpression(call)
      case typeId: IASTTypeIdExpression                                              => astForTypeIdExpression(typeId)
      case fieldRef: IASTFieldReference                                              => astForFieldReference(fieldRef)
      case expr: IASTConditionalExpression             => astForConditionalExpression(expr)
      case arr: IASTArraySubscriptExpression           => astForArrayIndexExpression(arr)
      case castExpression: IASTCastExpression          => astForCastExpression(castExpression)
      case newExpression: ICPPASTNewExpression         => astForNewExpression(newExpression)
      case delExpression: ICPPASTDeleteExpression      => astForDeleteExpression(delExpression)
      case typeIdInit: IASTTypeIdInitializerExpression => astForTypeIdInitExpression(typeIdInit)
      case c: ICPPASTSimpleTypeConstructorExpression   => astForConstructorExpression(c)
      case lambdaExpression: ICPPASTLambdaExpression   => astForLambdaExpression(lambdaExpression)
      case cExpr: IGNUASTCompoundStatementExpression   => astForCompoundStatementExpression(cExpr)
      case pExpr: ICPPASTPackExpansionExpression       => astForPackExpansionExpression(pExpr)
      case foldExpression: CPPASTFoldExpression        => astForFoldExpression(foldExpression)
      case _                                           => notHandledYet(expression)
    }
    asChildOfMacroCall(expression, r)
  }

  protected def astForStaticAssert(a: ICPPASTStaticAssertDeclaration): Ast = {
    val name = "<operator>.staticAssert"
    val call = callNode(a, code(a), name, name, DispatchTypes.STATIC_DISPATCH, None, Some(registerType(Defines.Void)))
    val cond = nullSafeAst(a.getCondition)
    val message = nullSafeAst(a.getMessage)
    callAst(call, List(cond, message))
  }

  private def astForBinaryExpression(bin: IASTBinaryExpression): Ast = {
    val op = OperatorMap.getOrElse(bin.getOperator, Defines.OperatorUnknown)
    val callNode_ =
      callNode(bin, code(bin), op, op, DispatchTypes.STATIC_DISPATCH, None, Some(registerType(Defines.Any)))
    val left  = nullSafeAst(bin.getOperand1)
    val right = nullSafeAst(bin.getOperand2)
    callAst(callNode_, List(left, right))
  }

  protected def astForConditionExpression(
    expression: IASTExpression,
    explicitArgumentIndex: Option[Int] = None
  ): Ast = {
    val ast = expression match {
      case exprList: IASTExpressionList => astForExpressionList(exprList)
      case other                        => nullSafeAst(other)
    }
    explicitArgumentIndex.foreach { i =>
      ast.root.foreach { case expr: ExpressionNew => expr.argumentIndex = i }
    }
    ast
  }

  private def astForExpressionList(exprList: IASTExpressionList): Ast = {
    exprList.getExpressions.toSeq match {
      case Nil         => blockAst(blockNode(exprList))
      case expr :: Nil => nullSafeAst(expr)
      case other =>
        val blockNode_ = blockNode(exprList)
        scope.pushNewBlockScope(blockNode_)
        val childAsts = other.map(nullSafeAst)
        scope.popScope()
        blockAst(blockNode(exprList), childAsts.toList)
    }
  }

  @tailrec
  private def isConstType(tpe: IType): Boolean = {
    tpe match {
      case t: ICPPFunctionType  => t.isConst
      case t: IPointerType      => t.isConst
      case t: IQualifierType    => t.isConst
      case t: ICArrayType       => t.isConst
      case t: ICPPReferenceType => isConstType(t.getType)
      case _                    => false
    }
  }

  private def astForCppCallExpression(call: ICPPASTFunctionCallExpression): Ast = {
    val functionNameExpr = call.getFunctionNameExpression
    Try(functionNameExpr.getExpressionType).toOption match {
      case Some(_: IPointerType) => createPointerCallAst(call, safeGetType(call.getExpressionType))
      case Some(functionType: ICPPFunctionType) =>
        functionNameExpr match {
          case idExpr: CPPASTIdExpression if safeGetBinding(idExpr).exists(_.isInstanceOf[ICPPFunction]) =>
            val function = idExpr.getName.getBinding.asInstanceOf[ICPPFunction]
            val name     = idExpr.getName.getLastName.toString
            val signature = if (function.isExternC) { "" }
            else {
              function match {
                case functionInstance: ICPPFunctionInstance =>
                  functionInstanceToSignature(functionInstance, functionType)
                case _ => functionTypeToSignature(functionType)
              }
            }
            val fullName = if (function.isExternC) {
              StringUtils.normalizeSpace(name)
            } else {
              val fullNameNoSig = stripTemplateTags(StringUtils.normalizeSpace(function.getQualifiedName.mkString(".")))
              s"$fullNameNoSig:$signature"
            }
            val callCpgNode = callNode(
              call,
              code(call),
              name,
              fullName,
              DispatchTypes.STATIC_DISPATCH,
              Some(signature),
              Some(registerType(safeGetType(call.getExpressionType)))
            )
            val args = call.getArguments.toList.map(a => astForNode(a))
            createCallAst(callCpgNode, args)
          case fieldRefExpr: ICPPASTFieldReference
              if safeGetBinding(fieldRefExpr.getFieldName).exists(_.isInstanceOf[ICPPMethod]) =>
            val instanceAst = astForExpression(fieldRefExpr.getFieldOwner)
            val args        = call.getArguments.toList.map(a => astForNode(a))

            val method = fieldRefExpr.getFieldName.getBinding.asInstanceOf[ICPPMethod]
            val constFlag = if (isConstType(method.getType)) { Defines.ConstSuffix }
            else { "" }
            // TODO This wont do if the name is a reference.
            val name          = stripTemplateTags(fieldRefExpr.getFieldName.toString)
            val signature     = s"${functionTypeToSignature(functionType)}$constFlag"
            val classFullName = safeGetType(fieldRefExpr.getFieldOwnerType)
            val fullName      = s"$classFullName.$name:$signature"

            val (dispatchType, receiver) =
              if (method.isVirtual || method.isPureVirtual) {
                (DispatchTypes.DYNAMIC_DISPATCH, Some(instanceAst))
              } else {
                (DispatchTypes.STATIC_DISPATCH, None)
              }
            val callCpgNode = callNode(
              call,
              code(call),
              name,
              fullName,
              dispatchType,
              Some(signature),
              Some(registerType(safeGetType(call.getExpressionType)))
            )
            createCallAst(callCpgNode, args, base = Some(instanceAst), receiver)
          case _ =>
            astForCppCallExpressionUntyped(call)
        }
      case Some(classType: ICPPClassType) if safeGetEvaluation(call).exists(_.isInstanceOf[EvalFunctionCall]) =>
        val evaluation        = call.getEvaluation.asInstanceOf[EvalFunctionCall]
        val functionType      = Try(evaluation.getOverload.getType).toOption
        val functionSignature = functionType.map(functionTypeToSignature).getOrElse(X2CpgDefines.UnresolvedSignature)
        val name              = Defines.OperatorCall
        classType match {
          case lambdaType: CPPClosureType =>
            val lambdaSignature = lambdaType.getDefinition match {
              case l: ICPPASTLambdaExpression => signature(returnType(l), l)
              case _                          => functionSignature
            }
            val fullName = s"$name:$lambdaSignature"
            val callCpgNode = callNode(
              call,
              code(call),
              name,
              fullName,
              DispatchTypes.DYNAMIC_DISPATCH,
              Some(lambdaSignature),
              Some(registerType(safeGetType(call.getExpressionType)))
            )
            val receiverAst = astForExpression(functionNameExpr)
            val args        = call.getArguments.toList.map(a => astForNode(a))
            createCallAst(callCpgNode, args, receiver = Some(receiverAst))
          case _ =>
            val classFullName = safeGetType(classType)
            val fullName      = s"$classFullName.$name:$functionSignature"
            val dispatchType = evaluation.getOverload match {
              case method: ICPPMethod =>
                if (method.isVirtual || method.isPureVirtual) {
                  DispatchTypes.DYNAMIC_DISPATCH
                } else {
                  DispatchTypes.STATIC_DISPATCH
                }
              case _ =>
                DispatchTypes.STATIC_DISPATCH
            }
            val callCpgNode = callNode(
              call,
              code(call),
              name,
              fullName,
              dispatchType,
              Some(functionSignature),
              Some(registerType(safeGetType(call.getExpressionType)))
            )
            val instanceAst = astForExpression(functionNameExpr)
            val args        = call.getArguments.toList.map(a => astForNode(a))
            createCallAst(callCpgNode, args, base = Some(instanceAst), receiver = Some(instanceAst))
        }
      case _ => astForCppCallExpressionUntyped(call)
    }
  }

  private def astForCppCallExpressionUntyped(call: ICPPASTFunctionCallExpression): Ast = {
    call.getFunctionNameExpression match {
      case fieldRefExpr: ICPPASTFieldReference =>
        val instanceAst = astForExpression(fieldRefExpr.getFieldOwner)
        val args        = call.getArguments.toList.map(a => astForNode(a))
        val name        = stripTemplateTags(StringUtils.normalizeSpace(fieldRefExpr.getFieldName.toString))
        val signature   = X2CpgDefines.UnresolvedSignature
        val fullName    = s"${X2CpgDefines.UnresolvedNamespace}.$name:$signature(${args.size})"
        val callCpgNode =
          callNode(
            call,
            code(call),
            name,
            fullName,
            DispatchTypes.STATIC_DISPATCH,
            Some(signature),
            Some(registerType(Defines.Any))
          )
        createCallAst(callCpgNode, args, base = Some(instanceAst), receiver = Some(instanceAst))
      case idExpr: CPPASTIdExpression =>
        val args      = call.getArguments.toList.map(a => astForNode(a))
        val name      = stripTemplateTags(StringUtils.normalizeSpace(idExpr.getName.getLastName.toString))
        val signature = X2CpgDefines.UnresolvedSignature
        val fullName  = s"${X2CpgDefines.UnresolvedNamespace}.$name:$signature(${args.size})"
        val callCpgNode =
          callNode(
            call,
            code(call),
            name,
            fullName,
            DispatchTypes.STATIC_DISPATCH,
            Some(signature),
            Some(registerType(Defines.Any))
          )
        createCallAst(callCpgNode, args)
      case otherExpr =>
        // This could either be a pointer or an operator() call we do not know at this point
        // but since it is CPP we opt for the latter.
        val args      = call.getArguments.toList.map(a => astForNode(a))
        val name      = Defines.OperatorCall
        val signature = X2CpgDefines.UnresolvedSignature
        val fullName  = s"${X2CpgDefines.UnresolvedNamespace}.$name:$signature(${args.size})"
        val callCpgNode =
          callNode(
            call,
            code(call),
            name,
            fullName,
            DispatchTypes.STATIC_DISPATCH,
            Some(signature),
            Some(registerType(Defines.Any))
          )
        val instanceAst = astForExpression(otherExpr)
        createCallAst(callCpgNode, args, base = Some(instanceAst), receiver = Some(instanceAst))
    }
  }

  private def astForCCallExpression(call: CASTFunctionCallExpression): Ast = {
    val functionNameExpr = call.getFunctionNameExpression
    Try(functionNameExpr.getExpressionType).toOption match {
      case Some(_: CPointerType) =>
        createPointerCallAst(call, safeGetType(call.getExpressionType))
      case Some(_: CFunctionType) =>
        functionNameExpr match {
          case idExpr: CASTIdExpression =>
            createCFunctionCallAst(call, idExpr, safeGetType(call.getExpressionType))
          case _ =>
            createPointerCallAst(call, safeGetType(call.getExpressionType))
        }
      case _ =>
        astForCCallExpressionUntyped(call)
    }
  }

  private def createCFunctionCallAst(
    call: CASTFunctionCallExpression,
    idExpr: CASTIdExpression,
    callTypeFullName: String
  ): Ast = {
    val name         = idExpr.getName.getLastName.toString
    val dispatchType = DispatchTypes.STATIC_DISPATCH
    val callCpgNode =
      callNode(call, code(call), name, name, dispatchType, Some(""), Some(registerType(callTypeFullName)))
    val args = call.getArguments.toList.map(a => astForNode(a))
    createCallAst(callCpgNode, args)
  }

  private def createPointerCallAst(call: IASTFunctionCallExpression, callTypeFullName: String): Ast = {
    val functionNameExpr = call.getFunctionNameExpression
    val name             = Defines.OperatorPointerCall
    val dispatchType     = DispatchTypes.DYNAMIC_DISPATCH
    val callCpgNode = callNode(call, code(call), name, name, dispatchType, None, Some(registerType(callTypeFullName)))
    val args        = call.getArguments.toList.map(a => astForNode(a))
    val receiverAst = astForExpression(functionNameExpr)
    createCallAst(callCpgNode, args, receiver = Some(receiverAst))
  }

  private def astForCCallExpressionUntyped(call: CASTFunctionCallExpression): Ast = {
    call.getFunctionNameExpression match {
      case idExpr: CASTIdExpression => createCFunctionCallAst(call, idExpr, Defines.Any)
      case _                        => createPointerCallAst(call, Defines.Any)
    }
  }

  private def astForCallExpression(call: IASTFunctionCallExpression): Ast = {
    call match {
      case cppCall: ICPPASTFunctionCallExpression => astForCppCallExpression(cppCall)
      case cCall: CASTFunctionCallExpression      => astForCCallExpression(cCall)
    }
  }

  private def astForThrowExpression(expression: IASTUnaryExpression): Ast = {
    val operand = nullSafeAst(expression.getOperand)
    Ast(controlStructureNode(expression, ControlStructureTypes.THROW, code(expression))).withChild(operand)
  }

  private def astForUnaryExpression(unary: IASTUnaryExpression): Ast = {
    val operatorMethod = UnaryOperatorMap.getOrElse(unary.getOperator, Defines.OperatorUnknown)
    if (unary.getOperator == IASTUnaryExpression.op_bracketedPrimary) {
      nullSafeAst(unary.getOperand)
    } else {
      val cpgUnary = callNode(
        unary,
        code(unary),
        operatorMethod,
        operatorMethod,
        DispatchTypes.STATIC_DISPATCH,
        None,
        Some(registerType(Defines.Any))
      )
      val operand = nullSafeAst(unary.getOperand)
      callAst(cpgUnary, List(operand))
    }
  }

  private def astForTypeIdExpression(typeId: IASTTypeIdExpression): Ast = {
    typeId.getOperator match {
      case op
          if op == IASTTypeIdExpression.op_sizeof ||
            op == IASTTypeIdExpression.op_sizeofParameterPack ||
            op == IASTTypeIdExpression.op_typeid ||
            op == IASTTypeIdExpression.op_alignof ||
            op == IASTTypeIdExpression.op_typeof =>
        val call =
          callNode(
            typeId,
            code(typeId),
            Operators.sizeOf,
            Operators.sizeOf,
            DispatchTypes.STATIC_DISPATCH,
            None,
            Some(registerType(Defines.Any))
          )
        val arg = astForNode(typeId.getTypeId.getDeclSpecifier)
        callAst(call, List(arg))
      case _ => notHandledYet(typeId)
    }
  }

  private def astForConditionalExpression(expr: IASTConditionalExpression): Ast = {
    val name = Operators.conditional
    val call =
      callNode(expr, code(expr), name, name, DispatchTypes.STATIC_DISPATCH, None, Some(registerType(Defines.Any)))

    val condAst = nullSafeAst(expr.getLogicalConditionExpression)
    val posAst  = nullSafeAst(expr.getPositiveResultExpression)
    val negAst  = nullSafeAst(expr.getNegativeResultExpression)

    val children = List(condAst, posAst, negAst)
    callAst(call, children)
  }

  private def astForArrayIndexExpression(arrayIndexExpression: IASTArraySubscriptExpression): Ast = {
    val name = Operators.indirectIndexAccess
    val cpgArrayIndexing =
      callNode(
        arrayIndexExpression,
        code(arrayIndexExpression),
        name,
        name,
        DispatchTypes.STATIC_DISPATCH,
        None,
        Some(registerType(Defines.Any))
      )

    val expr = astForExpression(arrayIndexExpression.getArrayExpression)
    val arg  = astForNode(arrayIndexExpression.getArgument)
    callAst(cpgArrayIndexing, List(expr, arg))
  }

  private def astForCastExpression(castExpression: IASTCastExpression): Ast = {
    val op  = Operators.cast
    val tpe = typeFor(castExpression.getTypeId.getDeclSpecifier)
    val cpgCastExpression = callNode(
      castExpression,
      code(castExpression),
      op,
      op,
      DispatchTypes.STATIC_DISPATCH,
      None,
      Some(registerType(tpe))
    )
    val expr         = astForExpression(castExpression.getOperand)
    val typeRefNode_ = typeRefNode(castExpression.getTypeId, code(castExpression.getTypeId), tpe)
    val arg          = Ast(typeRefNode_)
    callAst(cpgCastExpression, List(arg, expr))
  }

  protected def astsForConstructorInitializer(initializer: IASTInitializer): List[Ast] = {
    initializer match {
      case init: ICPPASTConstructorInitializer => astsForInitializerClauses(init.getArguments)
      case init: ICPPASTInitializerList        => astsForInitializerClauses(init.getClauses)
      case _                                   => Nil // null or unexpected type
    }
  }

  protected def astsForInitializerClauses(initializerClauses: Array[IASTInitializerClause]): List[Ast] = {
    if (initializerClauses != null) initializerClauses.toList.map(x => astForNode(x))
    else Nil
  }

  protected def initializerSignature(init: ICPPASTConstructorInitializer): String = {
    val initParamTypes =
      init.getArguments.collect { case e: IASTExpression => e }.map(t => cleanType(safeGetType(t.getExpressionType)))
    StringUtils.normalizeSpace(initParamTypes.mkString(","))
  }

  protected def initializerSignature(init: ICPPASTSimpleTypeConstructorExpression): String = {
    val initParamTypes = init.getInitializer match {
      case init: ICPPASTInitializerList =>
        init.getClauses.collect { case e: IASTExpression => e }.map(t => cleanType(safeGetType(t.getExpressionType)))
      case _ => Array.empty[String]
    }
    StringUtils.normalizeSpace(initParamTypes.mkString(","))
  }

  protected def initializerSignature(newExpression: ICPPASTNewExpression): String = {
    val initParamTypes = newExpression.getInitializer match {
      case init: ICPPASTConstructorInitializer =>
        init.getArguments.collect { case e: IASTExpression => e }.map(t => cleanType(safeGetType(t.getExpressionType)))
      case _ => Array.empty[String]
    }
    StringUtils.normalizeSpace(initParamTypes.mkString(","))
  }

  protected def constructorInvocationBlockAst(
    node: IASTNode,
    typeFullName: String,
    fullName: String,
    signature: String,
    constructorCallCode: String,
    args: List[Ast]
  ): Ast = {
    val blockNode_ = blockNode(node, constructorCallCode, registerType(Defines.Any))
    scope.pushNewBlockScope(blockNode_)

    val tmpNodeName  = scopeLocalUniqueName("tmp")
    val tmpNode      = identifierNode(node, tmpNodeName, tmpNodeName, typeFullName)
    val localTmpNode = localNode(node, tmpNodeName, tmpNodeName, typeFullName)
    scope.addVariable(tmpNodeName, localTmpNode, typeFullName, VariableScopeManager.ScopeType.BlockScope)

    val allocOp          = Operators.alloc
    val allocCallNode    = callNode(node, allocOp, allocOp, allocOp, DispatchTypes.STATIC_DISPATCH)
    val assignmentCallOp = Operators.assignment
    val assignmentCallNode =
      callNode(node, s"$tmpNodeName = $allocOp", assignmentCallOp, assignmentCallOp, DispatchTypes.STATIC_DISPATCH)
    val assignmentAst = callAst(assignmentCallNode, List(Ast(tmpNode), Ast(allocCallNode)))

    val baseNode = identifierNode(node, tmpNodeName, tmpNodeName, typeFullName)
    scope.addVariableReference(tmpNodeName, baseNode, typeFullName, EvaluationStrategies.BY_SHARING)
    val addrOp       = Operators.addressOf
    val addrCallNode = callNode(node, s"&$tmpNodeName", addrOp, addrOp, DispatchTypes.STATIC_DISPATCH)
    val addrCallAst  = callAst(addrCallNode, List(Ast(baseNode)))

    val constructorCallNode = callNode(
      node,
      constructorCallCode,
      typeFullName,
      fullName,
      DispatchTypes.STATIC_DISPATCH,
      Some(signature),
      Some(registerType(Defines.Void))
    )
    val constructorCallAst = createCallAst(constructorCallNode, args, base = Some(addrCallAst))

    val retNode = identifierNode(node, tmpNodeName, tmpNodeName, typeFullName)
    scope.addVariableReference(tmpNodeName, retNode, typeFullName, EvaluationStrategies.BY_SHARING)
    val retAst = Ast(retNode)

    scope.popScope()
    Ast(blockNode_).withChildren(Seq(assignmentAst, constructorCallAst, retAst))
  }

  private def astForNewExpression(newExpression: ICPPASTNewExpression): Ast = {
    val name = Defines.OperatorNew
    val newCallNode =
      callNode(
        newExpression,
        code(newExpression),
        name,
        name,
        DispatchTypes.STATIC_DISPATCH,
        None,
        Some(registerType(Defines.Any))
      )

    val typeId = newExpression.getTypeId
    val newCallArgAst =
      if (newExpression.isArrayAllocation || isFundamentalTypeKeywords(typeFor(typeId.getDeclSpecifier))) {
        val name  = Operators.alloc
        val idAst = astForIdentifier(typeId.getDeclSpecifier)
        val allocCallNode =
          callNode(
            newExpression,
            code(newExpression),
            name,
            name,
            DispatchTypes.STATIC_DISPATCH,
            None,
            Some(registerType(typeFor(newExpression)))
          )
        val arrayModArgs = typeId.getAbstractDeclarator match {
          case arr: IASTArrayDeclarator if hasValidArrayModifier(arr) =>
            arr.getArrayModifiers.toIndexedSeq.map(astForNode)
          case _ => Seq.empty
        }
        val args = astsForConstructorInitializer(newExpression.getInitializer) ++ arrayModArgs
        callAst(allocCallNode, idAst +: args)
      } else {
        val constructorCallName = shortName(typeId.getDeclSpecifier)
        val typeFullName        = fullName(typeId.getDeclSpecifier)
        val signature           = s"${Defines.Void}(${initializerSignature(newExpression)})"
        val fullNameWithSig     = s"$typeFullName.$constructorCallName:$signature"
        val constructorCallCode = code(newExpression)
        val args                = astsForConstructorInitializer(newExpression.getInitializer)
        constructorInvocationBlockAst(
          newExpression,
          typeFullName,
          fullNameWithSig,
          signature,
          constructorCallCode,
          args
        )
      }
    val placementArgs = astsForInitializerClauses(newExpression.getPlacementArguments)
    callAst(newCallNode, newCallArgAst +: placementArgs)
  }

  private def astForDeleteExpression(delExpression: ICPPASTDeleteExpression): Ast = {
    val name = Operators.delete
    val cpgDeleteNode =
      callNode(
        delExpression,
        code(delExpression),
        name,
        name,
        DispatchTypes.STATIC_DISPATCH,
        None,
        Some(registerType(Defines.Void))
      )
    val arg = astForExpression(delExpression.getOperand)
    callAst(cpgDeleteNode, List(arg))
  }

  private def astForTypeIdInitExpression(typeIdInit: IASTTypeIdInitializerExpression): Ast = {
    val op  = Operators.cast
    val tpe = typeFor(typeIdInit.getTypeId.getDeclSpecifier)
    val cpgCastExpression =
      callNode(typeIdInit, code(typeIdInit), op, op, DispatchTypes.STATIC_DISPATCH, None, Some(registerType(tpe)))
    val typeRefAst = Ast(typeRefNode(typeIdInit.getTypeId, code(typeIdInit.getTypeId), tpe))
    val expr       = astForNode(typeIdInit.getInitializer)
    callAst(cpgCastExpression, List(typeRefAst, expr))
  }

  private def astForConstructorExpression(constructorExpression: ICPPASTSimpleTypeConstructorExpression): Ast = {
    constructorExpression.getInitializer match {
      case l: ICPPASTInitializerList if l.getClauses.forall(_.isInstanceOf[ICPPASTDesignatedInitializer]) =>
        val name = stripTemplateTags(constructorExpression.getDeclSpecifier.toString)
        val node = blockNode(constructorExpression)
        scope.pushNewBlockScope(node)

        val inits = l.getClauses.collect { case i: ICPPASTDesignatedInitializer => i }.toSeq
        val calls = inits.flatMap { init =>
          val designatorIds = init.getDesignators.collect { case d: ICPPASTFieldDesignator =>
            val name = code(d.getName)
            fieldIdentifierNode(d, name, name)
          }
          designatorIds.map { memberId =>
            val rhsAst = astForNode(init.getOperand)
            val specifierId = identifierNode(
              constructorExpression.getDeclSpecifier,
              name,
              name,
              registerType(typeFor(constructorExpression.getDeclSpecifier))
            )
            val op         = Operators.fieldAccess
            val accessCode = s"$name.${memberId.code}"
            val ma =
              callNode(init, accessCode, op, op, DispatchTypes.STATIC_DISPATCH, None, Some(registerType(Defines.Any)))
            val maAst = callAst(ma, List(Ast(specifierId), Ast(memberId)))
            val assignmentCallNode =
              callNode(
                constructorExpression,
                s"$accessCode = ${code(init.getOperand)}",
                Operators.assignment,
                Operators.assignment,
                DispatchTypes.STATIC_DISPATCH,
                None,
                Some(registerType(Defines.Void))
              )
            callAst(assignmentCallNode, List(maAst, rhsAst))
          }
        }

        scope.popScope()
        blockAst(node, calls.toList)
      case _ =>
        val typeId              = constructorExpression.getDeclSpecifier
        val constructorCallName = shortName(typeId)
        val typeFullName        = typeForDeclSpecifier(typeId)
        val signature           = s"${Defines.Void}(${initializerSignature(constructorExpression)})"
        val fullNameWithSig     = s"$typeFullName.$constructorCallName:$signature"
        val constructorCallCode = code(constructorExpression)
        val args                = astsForConstructorInitializer(constructorExpression.getInitializer)
        constructorInvocationBlockAst(
          constructorExpression,
          typeFullName,
          fullNameWithSig,
          signature,
          constructorCallCode,
          args
        )
    }
  }

  private def astForCompoundStatementExpression(compoundExpression: IGNUASTCompoundStatementExpression): Ast =
    nullSafeAst(compoundExpression.getCompoundStatement).headOption.getOrElse(Ast())

  private def astForPackExpansionExpression(packExpansionExpression: ICPPASTPackExpansionExpression): Ast =
    astForExpression(packExpansionExpression.getPattern)

  private def astForFoldExpression(foldExpression: CPPASTFoldExpression): Ast = {
    def valueFromField[T](obj: Any, fieldName: String): Option[T] = {
      // we need this hack because fields are all private at CPPASTExpression
      Try {
        val field = obj.getClass.getDeclaredField(fieldName)
        field.setAccessible(true)
        field.get(obj).asInstanceOf[T]
      }.toOption.filter(_ != null)
    }

    val foldOp = "<operator>.fold"
    val tpe    = registerType(typeFor(foldExpression))
    val callNode_ =
      callNode(foldExpression, code(foldExpression), foldOp, foldOp, DispatchTypes.STATIC_DISPATCH, None, Some(tpe))

    val left  = valueFromField[ICPPASTExpression](foldExpression, "fLhs")
    val right = valueFromField[ICPPASTExpression](foldExpression, "fRhs")

    val args = (left, right) match {
      case (Some(l), None) => List(astForNode(l), astForNode(l))
      case (None, Some(r)) => List(astForNode(r), astForNode(r))
      case _               => List(left.map(astForNode).getOrElse(Ast()), right.map(astForNode).getOrElse(Ast()))
    }

    val op = valueFromField[Int](foldExpression, "fOperator")
      .map(operatorId => OperatorMap.getOrElse(operatorId, Defines.OperatorUnknown))
      .getOrElse(Defines.OperatorUnknown)
    registerType(op)
    val opRef = methodRefNode(foldExpression, op, op, op)
    callAst(callNode_, Ast(opRef) +: args)
  }

  private def astForIdExpression(idExpression: IASTIdExpression): Ast = idExpression.getName match {
    case name: CPPASTQualifiedName                                => astForQualifiedName(name)
    case name: ICPPASTName if name.getRawSignature == "constinit" => Ast()
    case _                                                        => astForIdentifier(idExpression)
  }

}
