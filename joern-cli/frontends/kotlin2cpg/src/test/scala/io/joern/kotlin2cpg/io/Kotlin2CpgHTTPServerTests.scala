package io.joern.kotlin2cpg.io

import io.joern.x2cpg.utils.server.FrontendHTTPClient
import io.shiftleft.semanticcpg.utils.FileUtil.*
import io.shiftleft.codepropertygraph.cpgloading.CpgLoader
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.utils.FileUtil
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path}

import scala.collection.parallel.CollectionConverters.RangeIsParallelizable
import scala.util.Failure
import scala.util.Success

class Kotlin2CpgHTTPServerTests extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private var port: Int = -1

  private def newProjectUnderTest(index: Option[Int] = None): Path = {
    val dir  = Files.createTempDirectory("kotlin2cpgTestsHttpTest")
    val file = dir / "main.kt"
    file.createWithParentsIfNotExists(createParents = true)
    val indexStr = index.map(_.toString).getOrElse("")
    val content = s"""
                     |package mypkg
                     |fun main(args : Array<String>) {
                     |  println($indexStr)
                     |}
                     |""".stripMargin
    Files.writeString(file, content)
    FileUtil.deleteOnExit(file)
    FileUtil.deleteOnExit(dir)
    dir
  }

  override def beforeAll(): Unit = {
    // Start server
    port = io.joern.kotlin2cpg.Main.startup()
  }

  override def afterAll(): Unit = {
    // Stop server
    io.joern.kotlin2cpg.Main.stop()
  }

  "Using kotlin2cpg in server mode" should {
    "build CPGs correctly (single test)" in {
      val cpgOutFile = FileUtil.newTemporaryFile("kotlin2cpg.bin")
      FileUtil.deleteOnExit(cpgOutFile)
      val projectUnderTest = newProjectUnderTest()
      val input            = projectUnderTest.absolutePathAsString
      val output           = cpgOutFile.toString
      val client           = FrontendHTTPClient(port)
      val req              = client.buildRequest(Array(s"input=$input", s"output=$output"))
      client.sendRequest(req) match {
        case Failure(exception) => fail(exception.getMessage)
        case Success(out) =>
          out shouldBe output
          val cpg = CpgLoader.load(output)
          cpg.method.name.l should contain("main")
          cpg.call.code.l shouldBe List("println()")
      }
    }

    "build CPGs correctly (multi-threaded test)" in {
      (0 until 10).par.foreach { index =>
        val cpgOutFile = FileUtil.newTemporaryFile("kotlin2cpg.bin")
        FileUtil.deleteOnExit(cpgOutFile)
        val projectUnderTest = newProjectUnderTest(Some(index))
        val input            = projectUnderTest.absolutePathAsString
        val output           = cpgOutFile.toString
        val client           = FrontendHTTPClient(port)
        val req              = client.buildRequest(Array(s"input=$input", s"output=$output", "no-default-exclude"))
        client.sendRequest(req) match {
          case Failure(exception) => fail(exception.getMessage)
          case Success(out) =>
            out shouldBe output
            val cpg = CpgLoader.load(output)
            cpg.method.name.l should contain("main")
            cpg.call.code.l shouldBe List(s"println($index)")
        }
      }
    }
  }

}
