package io.joern.kotlin2cpg.types

import io.joern.kotlin2cpg.testfixtures.KotlinCode2CpgFixture
import io.shiftleft.semanticcpg.language.*

class DefaultRegisteredTypesTests extends KotlinCode2CpgFixture(withOssDataflow = false) {

  "CPG for code with simple user-defined class" should {
    lazy val cpg = code("""
        |package mypkg
        |
        |class AClass
        |""".stripMargin)

    "should contain a TYPE node for `java.lang.Object`" in {
      cpg.typ.fullNameExact("java.lang.Object").size shouldBe 1
    }
  }

}
