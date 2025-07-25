package io.joern.jssrc2cpg.passes

import io.joern.jssrc2cpg.Config
import io.joern.jssrc2cpg.astcreation.AstCreator
import io.joern.jssrc2cpg.parser.BabelJsonParser
import io.joern.jssrc2cpg.utils.AstGenRunner.AstGenRunnerResult
import io.joern.x2cpg.ValidationMode
import io.joern.x2cpg.datastructures.Global
import io.joern.x2cpg.frontendspecific.jssrc2cpg.Defines
import io.joern.x2cpg.utils.Report
import io.joern.x2cpg.utils.TimeUtils
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.passes.ForkJoinParallelCpgPass
import io.shiftleft.utils.IOUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.file.Paths
import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

class AstCreationPass(cpg: Cpg, astGenRunnerResult: AstGenRunnerResult, config: Config, report: Report = new Report())(
  implicit withSchemaValidation: ValidationMode
) extends ForkJoinParallelCpgPass[(String, String)](cpg) {

  private val logger: Logger = LoggerFactory.getLogger(classOf[AstCreationPass])

  private val global = new Global()

  def typesSeen(): List[String] = global.usedTypes.keys().asScala.filterNot(_ == Defines.Any).toList

  override def generateParts(): Array[(String, String)] = astGenRunnerResult.parsedFiles.toArray

  override def finish(): Unit = {
    astGenRunnerResult.skippedFiles.foreach { skippedFile =>
      val (rootPath, fileName) = skippedFile
      val filePath             = Paths.get(rootPath, fileName)
      val fileLOC = Try(IOUtils.readLinesInFile(filePath)) match {
        case Success(fileContent) => fileContent.size
        case Failure(exception) =>
          logger.warn(s"Failed to read file: '$filePath'", exception)
          -1
      }
      report.addReportInfo(fileName, fileLOC)
    }
  }

  override def runOnPart(diffGraph: DiffGraphBuilder, input: (String, String)): Unit = {
    val (rootPath, jsonFilename) = input
    val parseResultMaybe         = BabelJsonParser.readFile(Paths.get(rootPath), Paths.get(jsonFilename))
    val ((gotCpg, filename), duration) = TimeUtils.time {
      parseResultMaybe match {
        case Success(parseResult) =>
          report.addReportInfo(parseResult.filename, parseResult.fileLoc, parsed = true)
          Try {
            val localDiff = new AstCreator(config, global, parseResult).createAst()
            diffGraph.absorb(localDiff)
          } match {
            case Failure(exception) =>
              logger.warn(s"Failed to generate a CPG for: '${parseResult.filename}'", exception)
              (false, parseResult.filename)
            case Success(_) =>
              logger.debug(s"Generated a CPG for: '${parseResult.filename}'")
              (true, parseResult.filename)
          }
        case Failure(exception) =>
          val pathOfFailedFile = jsonFilename.replaceAll("\\.[^.]*$", "")
          logger.warn(s"Failed to read json parse result for: '$pathOfFailedFile'", exception)
          (false, pathOfFailedFile)
      }
    }
    report.updateReport(filename, cpg = gotCpg, duration)
  }

}
