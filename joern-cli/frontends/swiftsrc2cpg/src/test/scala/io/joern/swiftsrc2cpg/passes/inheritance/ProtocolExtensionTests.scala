package io.joern.swiftsrc2cpg.passes.inheritance

import io.joern.swiftsrc2cpg.testfixtures.SwiftSrc2CpgSuite
import io.shiftleft.codepropertygraph.generated.*
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*
import org.scalatest.Assertion

class ProtocolExtensionTests extends SwiftSrc2CpgSuite {

  private val protocolACode =
    """
      |public protocol A {}
      |""".stripMargin

  private val protocolBCode =
    """
      |private protocol B {
      |  var b = 0.0
      |}
      |""".stripMargin

  private val protocolFooCode =
    """
      |protocol Foo: Bar { // implicitly internal (private)
      |  public var a = A()
      |  // some comment ✅
      |  private var b = false
      |  var c = 0.0
      |  var d: String?
      |
      |  static var e = 1
      |  // some comment ©
      |  static var f = true
      |
      |  var g: Double { return self * 1_000.0 }
      |
      |  init(paramA: String, paramB: Int) {
      |    self.init()
      |  }
      |
      |  private func someFunc() {}
      |
      |  override internal func someMethod() {
      |    super.someMethod()
      |  }
      |
      |  mutating func square() {
      |    self = self * self
      |  }
      |}
      |""".stripMargin

  private val protocolFooExtensionCode =
    """
      |extension Foo: SomeProtocol, AnotherProtocol {
      |  var h = 0.0
      |  var i: String
      |  static var j = 2
      |  func someOtherFunc() {}
      |}
      |""".stripMargin

  private def testProtocolExtension(cpg: Cpg): Assertion = {
    val List(typeDeclA) = cpg.typeDecl.nameExact("A").l
    typeDeclA.fullName shouldBe "Test0.swift:<global>.A"
    typeDeclA.member.l shouldBe empty
    typeDeclA.inheritsFromTypeFullName.l shouldBe empty
    typeDeclA.modifier.modifierType.l shouldBe List(ModifierTypes.PUBLIC)

    val List(typeDeclB) = cpg.typeDecl.nameExact("B").l
    typeDeclB.fullName shouldBe "Test0.swift:<global>.B"
    typeDeclB.member.name.l shouldBe List("b")
    typeDeclB.inheritsFromTypeFullName.l shouldBe empty
    typeDeclB.modifier.modifierType.l shouldBe List(ModifierTypes.PRIVATE)
    val List(bConstructor) = typeDeclB.method.isConstructor.l
    bConstructor.fullName shouldBe s"Test0.swift:<global>.B.${io.joern.x2cpg.Defines.ConstructorMethodName}:Test0.swift:<global>.B()"
    bConstructor.block.astChildren.code.l shouldBe List("var b = 0.0")

    val List(typeDeclFoo) = cpg.typeDecl.nameExact("Foo").l
    typeDeclFoo.fullName shouldBe "Test0.swift:<global>.Foo"
    typeDeclFoo.member.name.l.sorted shouldBe List(
      "a",
      "b",
      "c",
      "d",
      "e",
      "f",
      "g",
      "someFunc",
      "someMethod",
      "square"
    )
    typeDeclFoo.inheritsFromTypeFullName.l shouldBe List("Bar", "Test0.swift:<global>.Foo<extension>")
    typeDeclFoo.modifier.modifierType.l shouldBe List(ModifierTypes.PRIVATE)

    val List(fooConstructor) = typeDeclFoo.method.isConstructor.l
    fooConstructor.fullName shouldBe "Test0.swift:<global>.Foo.init:Test0.swift:<global>.Foo(paramA:String,paramB:Int)"
    fooConstructor.block.astChildren.assignment.code.l.sorted shouldBe List(
      "var a = A()",
      "var b = false",
      "var c = 0.0",
      "var g: Double { return self * 1_000.0 }" // lowered as assignment
    )

    val List(fooStaticInit) = typeDeclFoo.method.nameExact(io.joern.x2cpg.Defines.StaticInitMethodName).l
    fooStaticInit.fullName shouldBe s"Test0.swift:<global>.Foo.${io.joern.x2cpg.Defines.StaticInitMethodName}"
    fooStaticInit.block.astChildren.assignment.code.l.sorted shouldBe List("var e = 1", "var f = true")

    val List(typeDeclFooExtension) = cpg.typeDecl.nameExact("Foo<extension>").l
    typeDeclFooExtension.fullName shouldBe "Test0.swift:<global>.Foo<extension>"
    typeDeclFooExtension.member.name.l.sorted shouldBe List("h", "i", "j", "someOtherFunc")
    typeDeclFooExtension.inheritsFromTypeFullName.l shouldBe List("AnotherProtocol", "SomeProtocol")
    typeDeclFooExtension.modifier.modifierType.l shouldBe List(ModifierTypes.PRIVATE)

    val List(fooExtensionConstructor) = typeDeclFooExtension.method.isConstructor.l
    fooExtensionConstructor.fullName shouldBe s"Test0.swift:<global>.Foo<extension>.${io.joern.x2cpg.Defines.ConstructorMethodName}:Test0.swift:<global>.Foo<extension>()"
    fooExtensionConstructor.block.astChildren.assignment.code.l.sorted shouldBe List("var h = 0.0")

    val List(fooExtensionStaticInit) =
      typeDeclFooExtension.method.nameExact(io.joern.x2cpg.Defines.StaticInitMethodName).l
    fooExtensionStaticInit.fullName shouldBe s"Test0.swift:<global>.Foo<extension>.${io.joern.x2cpg.Defines.StaticInitMethodName}"
    fooExtensionStaticInit.block.astChildren.assignment.code.l.sorted shouldBe List("var j = 2")
  }

  "ProtocolExtensionTests" should {

    "test Protocol and Extension defined afterwards" in {
      val cpg = code(s"""
        |$protocolACode
        |$protocolBCode
        |$protocolFooCode
        |$protocolFooExtensionCode
        |""".stripMargin)
      testProtocolExtension(cpg)
    }

    "test Protocol and Extension defined beforehand" in {
      val cpg = code(s"""
        |$protocolACode
        |$protocolBCode
        |$protocolFooExtensionCode
        |$protocolFooCode
        |""".stripMargin)
      testProtocolExtension(cpg)
    }

  }

}
