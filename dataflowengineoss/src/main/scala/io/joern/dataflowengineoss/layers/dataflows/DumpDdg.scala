package io.joern.dataflowengineoss.layers.dataflows

import io.shiftleft.semanticcpg.utils.FileUtil.*
import io.joern.dataflowengineoss.DefaultSemantics
import io.joern.dataflowengineoss.language.*
import io.joern.dataflowengineoss.semanticsloader.Semantics
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.layers.{LayerCreator, LayerCreatorContext, LayerCreatorOptions}

import java.nio.file.{Files, Paths}

case class DdgDumpOptions(var outDir: String) extends LayerCreatorOptions {}

object DumpDdg {

  val overlayName = "dumpDdg"

  val description = "Dump data dependence graphs to out/"

  def defaultOpts: DdgDumpOptions = DdgDumpOptions("out")
}

class DumpDdg(options: DdgDumpOptions)(implicit semantics: Semantics = DefaultSemantics()) extends LayerCreator {
  override val overlayName: String       = DumpDdg.overlayName
  override val description: String       = DumpDdg.description
  override val storeOverlayName: Boolean = false

  override def create(context: LayerCreatorContext): Unit = {
    val cpg = context.cpg
    cpg.method.zipWithIndex.foreach { case (method, i) =>
      val str        = method.dotDdg.head
      val outputPath = Paths.get(options.outDir) / s"$i-ddg.dot"
      Files.writeString(outputPath, str)
    }
  }
}
