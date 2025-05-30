package io.joern.jssrc2cpg.astcreation

import io.joern.jssrc2cpg.Config
import io.joern.jssrc2cpg.parser.BabelAst.*
import io.joern.jssrc2cpg.parser.BabelJsonParser.ParseResult
import io.joern.jssrc2cpg.parser.BabelNodeInfo
import io.joern.x2cpg.Ast
import io.joern.x2cpg.AstCreatorBase
import io.joern.x2cpg.ValidationMode
import io.joern.x2cpg.datastructures.Global
import io.joern.x2cpg.datastructures.Stack.*
import io.joern.x2cpg.datastructures.VariableScopeManager
import io.joern.x2cpg.frontendspecific.jssrc2cpg.Defines
import io.shiftleft.codepropertygraph.generated.EvaluationStrategies
import io.shiftleft.codepropertygraph.generated.ModifierTypes
import io.shiftleft.codepropertygraph.generated.NodeTypes
import io.shiftleft.codepropertygraph.generated.nodes.NewBlock
import io.shiftleft.codepropertygraph.generated.nodes.NewFile
import io.shiftleft.codepropertygraph.generated.nodes.NewNode
import io.shiftleft.codepropertygraph.generated.nodes.NewTypeDecl
import io.shiftleft.codepropertygraph.generated.nodes.NewTypeRef
import io.shiftleft.codepropertygraph.generated.DiffGraphBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ujson.Value

import scala.collection.mutable

class AstCreator(val config: Config, val global: Global, val parserResult: ParseResult)(implicit
  withSchemaValidation: ValidationMode
) extends AstCreatorBase[BabelNodeInfo, AstCreator](parserResult.filename)
    with AstForExpressionsCreator
    with AstForPrimitivesCreator
    with AstForTypesCreator
    with AstForFunctionsCreator
    with AstForDeclarationsCreator
    with AstForStatementsCreator
    with AstForTemplateDomCreator
    with AstNodeBuilder
    with TypeHelper
    with AstCreatorHelper {

  protected val logger: Logger = LoggerFactory.getLogger(classOf[AstCreator])

  protected val scope = new VariableScopeManager()

  // TypeDecls with their bindings (with their refs) for lambdas and methods are not put in the AST
  // where the respective nodes are defined. Instead, we put them under the parent TYPE_DECL in which they are defined.
  // To achieve this we need this extra stack.
  protected val methodAstParentStack          = new Stack[NewNode]()
  protected val typeRefIdStack                = new Stack[NewTypeRef]
  protected val dynamicInstanceTypeStack      = new Stack[String]
  protected val localAstParentStack           = new Stack[NewBlock]()
  protected val rootTypeDecl                  = new Stack[NewTypeDecl]()
  protected val functionNodeToNameAndFullName = mutable.HashMap.empty[BabelNodeInfo, (String, String)]
  protected val usedVariableNames             = mutable.HashMap.empty[String, Int]
  protected val seenAliasTypes                = mutable.HashSet.empty[NewTypeDecl]
  protected val functionFullNames             = mutable.HashSet.empty[String]

  // we track line and column numbers manually because astgen / @babel-parser sometimes
  // fails to deliver them at all -  strange, but this even happens with its latest version
  protected val (positionToLineNumberMapping, positionToFirstPositionInLineMapping) =
    positionLookupTables(parserResult.fileContent)

  override def createAst(): DiffGraphBuilder = {
    val fileContent = if (!config.disableFileContent) Option(parserResult.fileContent) else None
    val fileNode    = NewFile().name(parserResult.filename).order(0)
    fileContent.foreach(fileNode.content(_))
    val namespaceBlock = globalNamespaceBlock()
    methodAstParentStack.push(namespaceBlock)
    val ast = Ast(fileNode).withChild(Ast(namespaceBlock).withChild(createProgramMethod()))
    Ast.storeInDiffGraph(ast, diffGraph)
    scope.createVariableReferenceLinks(diffGraph, parserResult.filename)
    diffGraph
  }

  private def createProgramMethod(): Ast = {
    val path        = parserResult.filename
    val astNodeInfo = createBabelNodeInfo(parserResult.json("ast"))
    val name        = Defines.Program
    val fullName    = s"$path:$name"

    val programMethod =
      methodNode(astNodeInfo, name, name, fullName, None, path, Some(NodeTypes.TYPE_DECL), Some(fullName)).order(1)

    val functionTypeAndTypeDeclAst =
      createFunctionTypeAndTypeDeclAst(astNodeInfo, programMethod, methodAstParentStack.head, name, fullName, path)
    rootTypeDecl.push(functionTypeAndTypeDeclAst.nodes.head.asInstanceOf[NewTypeDecl])

    methodAstParentStack.push(programMethod)

    val blockNode = NewBlock().typeFullName(Defines.Any)

    scope.pushNewMethodScope(fullName, name, blockNode, None)
    localAstParentStack.push(blockNode)

    val thisParam =
      parameterInNode(astNodeInfo, "this", "this", 0, false, EvaluationStrategies.BY_VALUE)
        .dynamicTypeHintFullName(typeHintForThisExpression())
    scope.addVariable("this", thisParam, Defines.Any, VariableScopeManager.ScopeType.MethodScope)

    val methodChildren = astsForFile(astNodeInfo)
    setArgumentIndices(methodChildren)

    val methodReturn = methodReturnNode(astNodeInfo, Defines.Any)

    localAstParentStack.pop()
    scope.popScope()
    methodAstParentStack.pop()

    functionTypeAndTypeDeclAst.withChild(
      methodAst(
        programMethod,
        Ast(thisParam) :: Nil,
        blockAst(blockNode, methodChildren),
        methodReturn,
        modifierNode(astNodeInfo, ModifierTypes.MODULE) :: Nil
      )
    )
  }

  private def astsForFile(file: BabelNodeInfo): List[Ast] = astsForProgram(createBabelNodeInfo(file.json("program")))

  private def astsForProgram(program: BabelNodeInfo): List[Ast] = createBlockStatementAsts(program.json("body"))

  protected def astForNodeWithFunctionReferenceAndCall(json: Value): Ast = {
    val nodeInfo = createBabelNodeInfo(json)
    nodeInfo.node match {
      case _: FunctionLike =>
        astForFunctionDeclaration(nodeInfo, shouldCreateFunctionReference = true, shouldCreateAssignmentCall = true)
      case _ => astForNode(json)
    }
  }

  protected def astForNode(json: Value): Ast = {
    val nodeInfo = createBabelNodeInfo(json)
    nodeInfo.node match {
      case ClassDeclaration          => astForClass(nodeInfo, shouldCreateAssignmentCall = true)
      case DeclareClass              => astForClass(nodeInfo, shouldCreateAssignmentCall = true)
      case ClassExpression           => astForClass(nodeInfo)
      case TSInterfaceDeclaration    => astForInterface(nodeInfo)
      case TSModuleDeclaration       => astForModule(nodeInfo)
      case TSExportAssignment        => astForExportAssignment(nodeInfo)
      case ExportNamedDeclaration    => astForExportNamedDeclaration(nodeInfo)
      case ExportDefaultDeclaration  => astForExportDefaultDeclaration(nodeInfo)
      case ExportAllDeclaration      => astForExportAllDeclaration(nodeInfo)
      case ImportDeclaration         => astForImportDeclaration(nodeInfo)
      case FunctionDeclaration       => astForFunctionDeclaration(nodeInfo)
      case TSDeclareFunction         => astForTSDeclareFunction(nodeInfo)
      case VariableDeclaration       => astForVariableDeclaration(nodeInfo)
      case ArrowFunctionExpression   => astForFunctionDeclaration(nodeInfo)
      case FunctionExpression        => astForFunctionDeclaration(nodeInfo)
      case TSEnumDeclaration         => astForEnum(nodeInfo)
      case DeclareTypeAlias          => astForTypeAlias(nodeInfo)
      case TypeAlias                 => astForTypeAlias(nodeInfo)
      case TypeCastExpression        => astForCastExpression(nodeInfo)
      case TSTypeAssertion           => astForCastExpression(nodeInfo)
      case TSTypeCastExpression      => astForCastExpression(nodeInfo)
      case TSTypeAliasDeclaration    => astForTypeAlias(nodeInfo)
      case NewExpression             => astForNewExpression(nodeInfo)
      case ThisExpression            => astForThisExpression(nodeInfo)
      case MemberExpression          => astForMemberExpression(nodeInfo)
      case OptionalMemberExpression  => astForMemberExpression(nodeInfo)
      case MetaProperty              => astForMetaProperty(nodeInfo)
      case CallExpression            => astForCallExpression(nodeInfo)
      case OptionalCallExpression    => astForCallExpression(nodeInfo)
      case SequenceExpression        => astForSequenceExpression(nodeInfo)
      case AssignmentExpression      => astForAssignmentExpression(nodeInfo)
      case AssignmentPattern         => astForAssignmentExpression(nodeInfo)
      case BinaryExpression          => astForBinaryExpression(nodeInfo)
      case LogicalExpression         => astForLogicalExpression(nodeInfo)
      case TSAsExpression            => astForCastExpression(nodeInfo)
      case UpdateExpression          => astForUpdateExpression(nodeInfo)
      case UnaryExpression           => astForUnaryExpression(nodeInfo)
      case ArrayExpression           => astForArrayExpression(nodeInfo)
      case AwaitExpression           => astForAwaitExpression(nodeInfo)
      case ConditionalExpression     => astForConditionalExpression(nodeInfo)
      case TaggedTemplateExpression  => astForTemplateExpression(nodeInfo)
      case ObjectExpression          => astForObjectExpression(nodeInfo)
      case TSNonNullExpression       => astForTSNonNullExpression(nodeInfo)
      case YieldExpression           => astForReturnStatement(nodeInfo)
      case ExpressionStatement       => astForExpressionStatement(nodeInfo)
      case IfStatement               => astForIfStatement(nodeInfo)
      case BlockStatement            => astForBlockStatement(nodeInfo)
      case ReturnStatement           => astForReturnStatement(nodeInfo)
      case TryStatement              => astForTryStatement(nodeInfo)
      case ForStatement              => astForForStatement(nodeInfo)
      case WhileStatement            => astForWhileStatement(nodeInfo)
      case DoWhileStatement          => astForDoWhileStatement(nodeInfo)
      case SwitchStatement           => astForSwitchStatement(nodeInfo)
      case BreakStatement            => astForBreakStatement(nodeInfo)
      case ContinueStatement         => astForContinueStatement(nodeInfo)
      case LabeledStatement          => astForLabeledStatement(nodeInfo)
      case ThrowStatement            => astForThrowStatement(nodeInfo)
      case ForInStatement            => astForInOfStatement(nodeInfo)
      case ForOfStatement            => astForInOfStatement(nodeInfo)
      case ObjectPattern             => astForObjectExpression(nodeInfo)
      case ArrayPattern              => astForArrayExpression(nodeInfo)
      case Identifier                => astForIdentifier(nodeInfo)
      case PrivateName               => astForPrivateName(nodeInfo)
      case Super                     => astForSuperKeyword(nodeInfo)
      case Import                    => astForImportKeyword(nodeInfo)
      case TSImportEqualsDeclaration => astForTSImportEqualsDeclaration(nodeInfo)
      case StringLiteral             => astForStringLiteral(nodeInfo)
      case NumericLiteral            => astForNumericLiteral(nodeInfo)
      case NumberLiteral             => astForNumberLiteral(nodeInfo)
      case DecimalLiteral            => astForDecimalLiteral(nodeInfo)
      case NullLiteral               => astForNullLiteral(nodeInfo)
      case BooleanLiteral            => astForBooleanLiteral(nodeInfo)
      case RegExpLiteral             => astForRegExpLiteral(nodeInfo)
      case RegexLiteral              => astForRegexLiteral(nodeInfo)
      case BigIntLiteral             => astForBigIntLiteral(nodeInfo)
      case TemplateLiteral           => astForTemplateLiteral(nodeInfo)
      case TemplateElement           => astForTemplateElement(nodeInfo)
      case SpreadElement             => astForSpreadOrRestElement(nodeInfo)
      case TSSatisfiesExpression     => astForTSSatisfiesExpression(nodeInfo)
      case JSXElement                => astForJsxElement(nodeInfo)
      case JSXOpeningElement         => astForJsxOpeningElement(nodeInfo)
      case JSXClosingElement         => astForJsxClosingElement(nodeInfo)
      case JSXText                   => astForJsxText(nodeInfo)
      case JSXExpressionContainer    => astForJsxExprContainer(nodeInfo)
      case JSXSpreadChild            => astForJsxExprContainer(nodeInfo)
      case JSXSpreadAttribute        => astForJsxSpreadAttribute(nodeInfo)
      case JSXFragment               => astForJsxFragment(nodeInfo)
      case JSXAttribute              => astForJsxAttribute(nodeInfo)
      case WithStatement             => astForWithStatement(nodeInfo)
      case EmptyStatement            => Ast()
      case DebuggerStatement         => Ast()
      case _                         => notHandledYet(nodeInfo)
    }
  }

  protected def astForNodes(jsons: List[Value]): List[Ast] = jsons.map(astForNodeWithFunctionReference)

  protected def astForNodeWithFunctionReference(json: Value): Ast = {
    val nodeInfo = createBabelNodeInfo(json)
    nodeInfo.node match {
      case _: FunctionLike => astForFunctionDeclaration(nodeInfo, shouldCreateFunctionReference = true)
      case _               => astForNode(json)
    }
  }

  protected def line(node: BabelNodeInfo): Option[Int]      = node.lineNumber
  protected def column(node: BabelNodeInfo): Option[Int]    = node.columnNumber
  protected def lineEnd(node: BabelNodeInfo): Option[Int]   = node.lineNumberEnd
  protected def columnEnd(node: BabelNodeInfo): Option[Int] = node.columnNumberEnd
  protected def code(node: BabelNodeInfo): String           = node.code

  override protected def offset(node: BabelNodeInfo): Option[(Int, Int)] = {
    Option
      .when(!config.disableFileContent) {
        nodeOffsets(node.json)
      }
      .flatten
  }

  protected def nodeOffsets(node: Value): Option[(Int, Int)] = {
    for {
      startOffset <- start(node)
      endOffset   <- end(node)
    } yield (math.max(startOffset, 0), math.min(endOffset, parserResult.fileContent.length))
  }
}
