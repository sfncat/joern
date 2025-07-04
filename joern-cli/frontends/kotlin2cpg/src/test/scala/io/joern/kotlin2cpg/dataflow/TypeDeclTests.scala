package io.joern.kotlin2cpg.dataflow

import io.joern.dataflowengineoss.language.toExtendedCfgNode
import io.joern.kotlin2cpg.Config
import io.joern.kotlin2cpg.testfixtures.KotlinCode2CpgFixture
import io.shiftleft.semanticcpg.language.*

class TypeDeclTests extends KotlinCode2CpgFixture(withOssDataflow = true) {
  implicit val resolver: ICallResolver = NoResolve

  "CPG for code with class definition with UTF8 characters" should {
    val cpg = code("""
        |class AClass(var x: String) {
        |    // ✅ This is a comment with UTF8.
        |    fun printX() = println(this.x)
        |}
        |fun f1(p: String) {
        |    val aClass = AClass(p)
        |    aClass.printX()
        |}
        |fun main() = f1("SOMETHING")
        |""".stripMargin)
      .withConfig(Config().withDisableFileContent(false))

    "should have the correct offsets set for the ACLass type decl" in {
      cpg.typeDecl.name("AClass").sourceCode.l shouldBe List("""class AClass(var x: String) {
          |    // ✅ This is a comment with UTF8.
          |    fun printX() = println(this.x)
          |}""".stripMargin)
    }

    "should have the correct offsets set for the <global> type decl" in {
      cpg.typeDecl.nameExact("<global>").sourceCode.l shouldBe List("""
          |class AClass(var x: String) {
          |    // ✅ This is a comment with UTF8.
          |    fun printX() = println(this.x)
          |}
          |fun f1(p: String) {
          |    val aClass = AClass(p)
          |    aClass.printX()
          |}
          |fun main() = f1("SOMETHING")
          |""".stripMargin)
    }
  }
  "CPG for code with class definition with member defined inside ctor" should {
    val cpg = code("""
        |class AClass(var x: String) {
        |    fun printX() = println(this.x)
        |}
        |fun f1(p: String) {
        |    val aClass = AClass(p)
        |    aClass.printX()
        |}
        |fun main() = f1("SOMETHING")
        |""".stripMargin)
      .withConfig(Config().withDisableFileContent(false))

    "should find a flow through member assigned in ctor" in {
      val source = cpg.method.name("f1").parameter
      val sink   = cpg.call.methodFullName(".*println.*").argument
      val flows  = sink.reachableByFlows(source)
      flows.map(flowToResultPairs).toSet shouldBe
        Set(
          List(
            ("f1(p)", Some(5)),
            ("AClass(p)", Some(6)),
            ("<init>(this, x)", Some(2)),
            ("this.x = x", Some(2)),
            ("RET", Some(2)),
            ("AClass(p)", Some(6)),
            ("aClass.printX()", Some(7)),
            ("printX(this)", Some(3)),
            ("println(this.x)", Some(3))
          )
        )
    }

    "should have the correct offsets set for the ACLass type decl" in {
      cpg.typeDecl.name("AClass").sourceCode.l shouldBe List("""class AClass(var x: String) {
          |    fun printX() = println(this.x)
          |}""".stripMargin)
    }

    "should have the correct offsets set for the <global> type decl" in {
      cpg.typeDecl.nameExact("<global>").sourceCode.l shouldBe List("""
          |class AClass(var x: String) {
          |    fun printX() = println(this.x)
          |}
          |fun f1(p: String) {
          |    val aClass = AClass(p)
          |    aClass.printX()
          |}
          |fun main() = f1("SOMETHING")
          |""".stripMargin)
    }
  }

  "CPG for code with type alias of class definition with member defined inside ctor" should {
    val cpg = code("""
      |typealias AnAlias = AClass
      |class AClass {
      |    var x: String
      |    constructor(q: String) {
      |        this.x = q
      |    }
      |    fun printX() = println(this.x)
      |}
      |fun f1(p: String) {
      |    val aClass = AnAlias(p)
      |    aClass.printX()
      |}
      |fun main() = f1("SOMETHING"
      |""".stripMargin)
      .withConfig(Config().withDisableFileContent(false))

    "should find a flow through member assigned in ctor" in {
      val source = cpg.method.name("f1").parameter
      val sink   = cpg.call.methodFullName(".*println.*").argument
      val flows  = sink.reachableByFlows(source)
      flows.map(flowToResultPairs).toSet shouldBe
        Set(
          List(
            ("f1(p)", Some(10)),
            ("AnAlias(p)", Some(11)),
            ("<init>(this, q)", Some(5)),
            ("this.x = q", Some(6)),
            ("RET", Some(5)),
            ("AnAlias(p)", Some(11)),
            ("aClass.printX()", Some(12)),
            ("printX(this)", Some(8)),
            ("println(this.x)", Some(8))
          )
        )
    }

    "have the correct offsets set for the AClass typeDecl" in {
      cpg.typeDecl.name("AClass").sourceCode.l shouldBe List("""class AClass {
          |    var x: String
          |    constructor(q: String) {
          |        this.x = q
          |    }
          |    fun printX() = println(this.x)
          |}""".stripMargin)
    }

    "have the correct offsets set for the <global> typeDecl" in {
      cpg.typeDecl.nameExact("<global>").sourceCode.l shouldBe List("""
          |typealias AnAlias = AClass
          |class AClass {
          |    var x: String
          |    constructor(q: String) {
          |        this.x = q
          |    }
          |    fun printX() = println(this.x)
          |}
          |fun f1(p: String) {
          |    val aClass = AnAlias(p)
          |    aClass.printX()
          |}
          |fun main() = f1("SOMETHING"
          |""".stripMargin)
    }
  }

  "CPG for code with class definition with member assignment inside secondary ctor" should {
    val cpg = code("""
      |class AClass {
      |    var x: String
      |    constructor(q: String) {
      |        this.x = q
      |    }
      |    fun printX() = println(this.x)
      |}
      |fun f1(p: String) {
      |    val aClass = AClass(p)
      |    aClass.printX()
      |}
      |""".stripMargin)

    "should find a flow through member assigned in ctor" in {
      val source = cpg.method.name("f1").parameter
      val sink   = cpg.call.methodFullName(".*println.*").argument
      val flows  = sink.reachableByFlows(source)
      flows.map(flowToResultPairs).toSet shouldBe
        Set(
          List(
            ("f1(p)", Some(9)),
            ("AClass(p)", Some(10)),
            ("<init>(this, q)", Some(4)),
            ("this.x = q", Some(5)),
            ("RET", Some(4)),
            ("AClass(p)", Some(10)),
            ("aClass.printX()", Some(11)),
            ("printX(this)", Some(7)),
            ("println(this.x)", Some(7))
          )
        )
    }
  }

  "CPG for classes with secondary constructors" should {
    val cpg = code("""
        |class AClass {
        |    var x: String
        |    constructor(q: String) {
        |        this.x = q
        |    }
        |    constructor(p: String, r: Int) {
        |        this.x = p
        |    }
        |}
        |""".stripMargin)

    "have their own instance of primaryCtorCall nodes" in {
      cpg.typeDecl.nameExact("AClass").method.isConstructor.fullName.l shouldBe List(
        "AClass.<init>:void()",
        "AClass.<init>:void(java.lang.String)",
        "AClass.<init>:void(java.lang.String,int)"
      )
      val List(call1, call2) = cpg.method.nameExact("<init>").filter(_.parameter.size > 1).call.nameExact("<init>").l
      call1.method.fullName shouldBe "AClass.<init>:void(java.lang.String)"
      call2.method.fullName shouldBe "AClass.<init>:void(java.lang.String,int)"
    }
  }

}
