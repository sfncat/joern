package io.joern.dataflowengineoss.queryengine

import flatgraph.{GNode, Graph}
import flatgraph.misc.TestUtils.*
import io.shiftleft.codepropertygraph.generated.PropertyNames
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.generated.{Cpg, EdgeTypes, NodeTypes, Operators}
import io.joern.dataflowengineoss.queryengine.AccessPathUsage.toTrackedBaseAndAccessPathSimple
import io.shiftleft.semanticcpg.accesspath.*
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

class AccessPathUsageTests extends AnyWordSpec {

  def E(elements: AccessElement*): AccessPath = {
    new AccessPath(Elements.normalized(elements), Nil)
  }

  private val V  = VariableAccess
  private val I  = IndirectionAccess
  private val C  = ConstantAccess
  private val A  = AddressOf
  private val VS = VariablePointerShift
  private val S  = PointerShift

  private val g = Cpg.empty.graph

  private def genCALL(graph: Graph, op: String, args: GNode*): Call = {
    val diffGraphBuilder = Cpg.newDiffGraphBuilder
    val newCall          = NewCall().name(op)
    diffGraphBuilder.addNode(newCall)
    args.reverse.zipWithIndex.foreach { case (arg, idx) =>
      diffGraphBuilder.setNodeProperty(arg, PropertyNames.ArgumentIndex, idx + 1)
      diffGraphBuilder.addEdge(newCall, arg, EdgeTypes.ARGUMENT)
    }
    diffGraphBuilder.apply(graph)
    newCall.storedRef.get
  }

  private def genLit(graph: Graph, payload: String): Literal =
    graph.addNode(NewLiteral().code(payload))

  private def genID(graph: Graph, payload: String): Identifier =
    graph.addNode(NewIdentifier().name(payload))

  private def genFID(graph: Graph, payload: String): FieldIdentifier =
    graph.addNode(NewFieldIdentifier().canonicalName(payload))

  private def toTrackedAccessPath(node: StoredNode): AccessPath = toTrackedBaseAndAccessPathSimple(node)._2

  "memberAccess" should {
    "work" in {
      val call =
        genCALL(
          g,
          Operators.memberAccess,
          genID(g, "a"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), C("a"))
    }
  }

  "indirectMemberAccess" should {
    "work" in {
      val call =
        genCALL(
          g,
          Operators.indirectMemberAccess,
          genID(g, "a"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), C("a"))
    }
  }

  "computedMemberAccess" should {
    "work with Literal" in {
      val call =
        genCALL(
          g,
          Operators.computedMemberAccess,
          genLit(g, "a"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), C("a"))
    }
    "overtaint with others" in {
      val call =
        genCALL(
          g,
          Operators.computedMemberAccess,
          genID(g, "a"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), V)
    }
  }

  "indirectComputedMemberAccess" should {
    "work with Literal" in {
      val call =
        genCALL(
          g,
          Operators.indirectComputedMemberAccess,
          genLit(g, "a"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), C("a"))
    }
    "overtaint with others" in {
      val call =
        genCALL(
          g,
          Operators.indirectComputedMemberAccess,
          genID(g, "a"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), V)
    }

  }

  "indirection" should {
    "work" in {
      val call =
        genCALL(g, Operators.indirection, genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo")))

      toTrackedAccessPath(call) shouldBe E(C("b"), I)
    }
  }

  "addressOf" should {
    "work" in {
      val call =
        genCALL(g, Operators.addressOf, genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo")))

      toTrackedAccessPath(call) shouldBe E(C("b"), A)
    }
  }
  // new style

  "FieldAccess" should {
    "work with Literal" in {
      val call =
        genCALL(
          g,
          Operators.fieldAccess,
          genLit(g, "a"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), C("a"))
    }

    "work with FieldIdentifier" in {
      val call =
        genCALL(
          g,
          Operators.fieldAccess,
          genFID(g, "a"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), C("a"))
    }

    "overtaint with others" in {
      val call =
        genCALL(
          g,
          Operators.fieldAccess,
          genID(g, "a"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), C("a"))
    }
  }

  "IndirectFieldAccess" should {
    "work with Literal" in {
      val call =
        genCALL(
          g,
          Operators.indirectFieldAccess,
          genLit(g, "a"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), I, C("a"))
    }

    "work with FieldIdentifier" in {
      val call =
        genCALL(
          g,
          Operators.indirectFieldAccess,
          genFID(g, "a"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), I, C("a"))
    }

    "overtaint with others" in {
      val call =
        genCALL(
          g,
          Operators.indirectFieldAccess,
          genID(g, "a"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), I, C("a"))
    }
  }

  "indexAccess" should {
    "work with Literal" in {
      val call =
        genCALL(
          g,
          Operators.indexAccess,
          genLit(g, "a"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), C("a"))
    }

    "work with FieldIdentifier" in {
      val call =
        genCALL(
          g,
          Operators.indexAccess,
          genFID(g, "a"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), C("a"))
    }

    "overtaint with others" in {
      val call =
        genCALL(
          g,
          Operators.indexAccess,
          genID(g, "a"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), V)
    }
  }

  "indirectIndexAccess" should {
    "work with Literal" in {
      val call =
        genCALL(
          g,
          Operators.indirectIndexAccess,
          genLit(g, "12"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), S(12), I)
    }

    "work with FieldIdentifier" in {
      val call =
        genCALL(
          g,
          Operators.indirectIndexAccess,
          genFID(g, "12"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), S(12), I)
    }

    "overtaint with others" in {
      val call =
        genCALL(
          g,
          Operators.indirectIndexAccess,
          genID(g, "a"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), VS, I)
    }
    "overtaint on parsing failure" in {
      val call =
        genCALL(
          g,
          Operators.indirectIndexAccess,
          genLit(g, "a"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), VS, I)
    }

  }

  "pointerShft" should {
    // fixme
    "work with Literal" in {
      val call =
        genCALL(
          g,
          Operators.pointerShift,
          genLit(g, "12"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), S(12))
    }

    "work with FieldIdentifier" in {
      val call =
        genCALL(
          g,
          Operators.pointerShift,
          genFID(g, "12"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), S(12))
    }

    "overtaint with others" in {
      val call =
        genCALL(
          g,
          Operators.pointerShift,
          genID(g, "a"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), VS)
    }
    "overtaint with parsing fails" in {
      val call =
        genCALL(
          g,
          Operators.pointerShift,
          genLit(g, "abc"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), VS)
    }

  }

  "getElementPointer" should {
    // fixme
    "work with Literal" in {
      val call =
        genCALL(
          g,
          Operators.getElementPtr,
          genLit(g, "a"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), I, C("a"), A)
    }

    "work with FieldIdentifier" in {
      val call =
        genCALL(
          g,
          Operators.getElementPtr,
          genFID(g, "a"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), I, C("a"), A)
    }

    "overtaint with others" in {
      val call =
        genCALL(
          g,
          Operators.getElementPtr,
          genID(g, "a"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E(C("b"), I, C("a"), A)
    }
  }

  "Others" should {
    "not expand through" in {
      val call =
        genCALL(
          g,
          Operators.addition,
          genID(g, "a"),
          genCALL(g, Operators.computedMemberAccess, genLit(g, "b"), genCALL(g, "foo"))
        )

      toTrackedAccessPath(call) shouldBe E()
    }
  }

}
