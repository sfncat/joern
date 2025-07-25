package io.joern.php2cpg.passes

import io.shiftleft.codepropertygraph.generated.{Cpg, Properties, PropertyNames}
import io.shiftleft.codepropertygraph.generated.nodes.{AstNode, NamespaceBlock, Method, TypeDecl}
import io.shiftleft.passes.ForkJoinParallelCpgPass
import io.shiftleft.semanticcpg.language.*

class AstParentInfoPass(cpg: Cpg) extends ForkJoinParallelCpgPass[AstNode](cpg) {

  override def generateParts(): Array[AstNode] = {
    (cpg.method ++ cpg.typeDecl).toArray
  }

  override def runOnPart(diffGraph: DiffGraphBuilder, node: AstNode): Unit = {
    findParent(node).foreach { parentNode =>
      val astParentType     = parentNode.label
      val astParentFullName = parentNode.property(Properties.FullName)

      diffGraph.setNodeProperty(node, PropertyNames.AstParentType, astParentType)
      diffGraph.setNodeProperty(node, PropertyNames.AstParentFullName, astParentFullName)
    }
  }

  private def hasValidContainingNodes(nodes: Iterator[AstNode]): Iterator[AstNode] = {
    nodes.collect {
      case m: Method         => m
      case t: TypeDecl       => t
      case n: NamespaceBlock => n
    }
  }

  def findParent(node: AstNode): Option[AstNode] = {
    node.start
      .repeat(_.astParent)(_.until(hasValidContainingNodes(_)).emit(hasValidContainingNodes(_)))
      .find(_ != node)
  }
}
