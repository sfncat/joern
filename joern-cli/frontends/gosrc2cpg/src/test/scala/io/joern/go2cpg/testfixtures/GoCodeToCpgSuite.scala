package io.joern.go2cpg.testfixtures

import io.joern.dataflowengineoss.DefaultSemantics
import io.joern.dataflowengineoss.semanticsloader.{FlowSemantic, Semantics}
import io.joern.dataflowengineoss.testfixtures.{SemanticCpgTestFixture, SemanticTestCpg}
import io.joern.gosrc2cpg.datastructures.GoGlobal
import io.joern.gosrc2cpg.model.GoModHelper
import io.joern.gosrc2cpg.{Config, GoSrc2Cpg}
import io.joern.x2cpg.testfixtures.{Code2CpgFixture, DefaultTestCpg}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.semanticcpg.language.{ICallResolver, NoResolve}
import io.shiftleft.semanticcpg.utils.FileUtil
import org.scalatest.Inside
class DefaultTestCpgWithGo(val fileSuffix: String) extends DefaultTestCpg with SemanticTestCpg {

  private var goGlobal: Option[GoGlobal]   = None
  private var goSrc2Cpg: Option[GoSrc2Cpg] = None
  override protected def applyPasses(): Unit = {
    super.applyPasses()
    applyOssDataFlow()
  }

  def withGoGlobal(goGlobal: GoGlobal): this.type = {
    setGoGlobal(goGlobal)
    this
  }

  private def setGoGlobal(goGlobal: GoGlobal): Unit = {
    if (this.goGlobal.isDefined) {
      throw new RuntimeException("Frontend GoGlobal may only be set once per test")
    }
    this.goGlobal = Some(goGlobal)
  }

  def execute(sourceCodePath: java.io.File): Cpg = {
    val cpgOutFile = FileUtil.newTemporaryFile("go2cpg.bin")
    FileUtil.deleteOnExit(cpgOutFile)
    goSrc2Cpg = Some(new GoSrc2Cpg(this.goGlobal))
    val config = getConfig()
      .collectFirst { case x: Config => x }
      .getOrElse(Config())
      .withInputPath(sourceCodePath.getAbsolutePath)
      .withOutputPath(cpgOutFile.toString)
    goSrc2Cpg.get.createCpg(config).get
  }

  def getModHelper(): GoModHelper = goSrc2Cpg.get.getGoModHelper
}

class GoCodeToCpgSuite(
  fileSuffix: String = ".go",
  withOssDataflow: Boolean = false,
  semantics: Semantics = DefaultSemantics()
) extends Code2CpgFixture(() =>
      new DefaultTestCpgWithGo(fileSuffix).withOssDataflow(withOssDataflow).withSemantics(semantics)
    )
    with SemanticCpgTestFixture(semantics)
    with Inside {
  implicit val resolver: ICallResolver = NoResolve

}
