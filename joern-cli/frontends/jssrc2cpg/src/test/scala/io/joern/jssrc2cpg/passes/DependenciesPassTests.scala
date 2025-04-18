package io.joern.jssrc2cpg.passes

import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.semanticcpg.language.*
import io.joern.jssrc2cpg.utils.PackageJsonParser
import io.joern.jssrc2cpg.Config
import io.joern.jssrc2cpg.testfixtures.JsSrc2CpgSuite
import io.joern.x2cpg.X2Cpg.newEmptyCpg
import io.shiftleft.semanticcpg.utils.FileUtil.*
import io.shiftleft.semanticcpg.utils.FileUtil

import java.nio.file.{Files, Path}

class DependenciesPassTests extends JsSrc2CpgSuite {

  "DependenciesPass" should {

    "ignore empty package.json" in {
      FileUtil.usingTemporaryDirectory("jssrc2cpgTest") { dir =>
        val json = dir / PackageJsonParser.PackageJsonFilename
        Files.writeString(json, "")
        PackageJsonParser.isValidProjectPackageJson(json) shouldBe false
      }
    }

    "ignore package.json without any useful content" in {
      FileUtil.usingTemporaryDirectory("jssrc2cpgTest") { dir =>
        val json = dir / PackageJsonParser.PackageJsonFilename
        val content = """
                        |{
                        |  "name": "something",
                        |  "version": "0.1.0",
                        |  "description": "foobar",
                        |  "main": "./target_node/index.js",
                        |  "private": true
                        |}
                        |""".stripMargin
        Files.writeString(json, content)
        PackageJsonParser.isValidProjectPackageJson(json) shouldBe false
      }
    }

    "ignore package.json without dependencies" in {
      FileUtil.usingTemporaryDirectory("jssrc2cpgTest") { dir =>
        val json = dir / PackageJsonParser.PackageJsonFilename
        Files.writeString(json, "{}")
        PackageJsonParser.isValidProjectPackageJson(json) shouldBe false
      }
    }

    "generate dependency nodes correctly (no dependencies at all)" in {
      val cpg = code("").moreCode("{}", PackageJsonParser.PackageJsonFilename)
      cpg.dependency.size shouldBe 0
    }

    "generate dependency nodes correctly (empty dependency)" in {
      val cpg = code("").moreCode(
        """
        |{
        |  "dependencies": {
        |  }
        |}
        |""".stripMargin,
        PackageJsonParser.PackageJsonFilename
      )
      cpg.dependency.size shouldBe 0
    }

    "generate dependency nodes correctly (simple lock dependencies)" in {
      val cpg = code("").moreCode(
        """
        |{
        |  "dependencies": {
        |    "dep1": {
        |      "version": "0.1"
        |    },
        |    "dep2": {
        |      "version": "0.2"
        |    }
        |  }
        |}
        |""".stripMargin,
        PackageJsonParser.PackageJsonLockFilename
      )
      val List(depA, depB) = cpg.dependency.l
      depA.name shouldBe "dep1"
      depA.version shouldBe "0.1"
      depB.name shouldBe "dep2"
      depB.version shouldBe "0.2"
    }

    "generate dependency nodes correctly (simple dependency)" in {
      val cpg = code("").moreCode(
        """
        |{
        |  "dependencies": {
        |    "dep1": "0.1"
        |  }
        |}
        |""".stripMargin,
        PackageJsonParser.PackageJsonFilename
      )
      val List(depA) = cpg.dependency.l
      depA.name shouldBe "dep1"
      depA.version shouldBe "0.1"
    }

    "generate dependency nodes correctly (different types of dependencies)" in {
      val cpg = code("").moreCode(
        """
        {
          "dependencies": {
            "dep1": "0.1"
          },
          "devDependencies": {
            "dep2": "0.2"
          },
          "peerDependencies": {
            "dep3": "0.3"
          },
          "optionalDependencies": {
            "dep4": "0.4"
          }
        }
        """.stripMargin,
        PackageJsonParser.PackageJsonFilename
      )
      val List(depA, depB, depC, depD) = cpg.dependency.l
      depA.name shouldBe "dep1"
      depA.version shouldBe "0.1"
      depB.name shouldBe "dep2"
      depB.version shouldBe "0.2"
      depC.name shouldBe "dep3"
      depC.version shouldBe "0.3"
      depD.name shouldBe "dep4"
      depD.version shouldBe "0.4"
    }

  }

}
