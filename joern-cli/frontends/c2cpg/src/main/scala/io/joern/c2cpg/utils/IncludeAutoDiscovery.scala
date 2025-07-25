package io.joern.c2cpg.utils

import io.joern.c2cpg.Config
import io.shiftleft.semanticcpg.utils.FileUtil.*
import io.shiftleft.semanticcpg.utils.{ExternalCommand, FileUtil}
import org.slf4j.LoggerFactory

import java.nio.file.{Path, Paths, Files}
import scala.collection.mutable
import scala.util.Failure
import scala.util.Success

object IncludeAutoDiscovery {

  private val logger = LoggerFactory.getLogger(IncludeAutoDiscovery.getClass)

  private val IsWin = scala.util.Properties.isWin

  private val GccVersionCommand = Seq("gcc", "--version")

  private val CppIncludeCommand =
    if (IsWin) Seq("gcc", "-xc++", "-E", "-v", ".", "-o", "nul")
    else Seq("gcc", "-xc++", "-E", "-v", "/dev/null", "-o", "/dev/null")

  private val CIncludeCommand =
    if (IsWin) Seq("gcc", "-xc", "-E", "-v", ".", "-o", "nul")
    else Seq("gcc", "-xc", "-E", "-v", "/dev/null", "-o", "/dev/null")

  private val VsWhereCommand = Seq(
    "cmd.exe",
    "/C",
    "\"%ProgramFiles(x86)%\\Microsoft Visual Studio\\Installer\\vswhere.exe\" -property installationPath"
  )

  private val VcVarsCommand = Seq("cmd.exe", "/C", "VC\\Auxiliary\\Build\\vcvars64.bat")

  private val LanguageSetting = Map("LC_ALL" -> "C")

  // Only check once
  private var isGccAvailable: Option[Boolean] = None

  // Only discover them once
  private var systemIncludePathsC: mutable.LinkedHashSet[Path]   = mutable.LinkedHashSet.empty
  private var systemIncludePathsCPP: mutable.LinkedHashSet[Path] = mutable.LinkedHashSet.empty

  def discoverIncludePathsC(config: Config): mutable.LinkedHashSet[Path] = {
    if (!config.includePathsAutoDiscovery) return mutable.LinkedHashSet.empty
    if (systemIncludePathsC.nonEmpty) return systemIncludePathsC

    if (isMSVCProject(config)) {
      systemIncludePathsCPP = discoverMSVCPaths() // discovers paths for both languages
      systemIncludePathsC = systemIncludePathsCPP
      reportIncludePaths(systemIncludePathsC, "MSVC")
    }
    if (systemIncludePathsC.isEmpty && gccAvailable()) {
      systemIncludePathsC = discoverPaths(CIncludeCommand)
      reportIncludePaths(systemIncludePathsC, "C")
    }
    systemIncludePathsC
  }

  def gccAvailable(): Boolean = {
    isGccAvailable match {
      case Some(value) =>
        value
      case None =>
        isGccAvailable = Option(checkForGcc())
        isGccAvailable.get
    }
  }

  private def checkForGcc(): Boolean = {
    ExternalCommand.run(GccVersionCommand).toTry match {
      case Success(result) =>
        logger.debug(s"GCC is available: ${result.mkString(System.lineSeparator())}")
        true
      case _ =>
        logger.warn("GCC is not installed. Discovery of system include paths will not be available.")
        false
    }
  }

  private def discoverPaths(command: Seq[String]): mutable.LinkedHashSet[Path] =
    GccSpecificExternalCommand.run(command, ".", LanguageSetting) match {
      case Success(output) => extractPaths(output)
      case Failure(exception) =>
        logger.warn(s"Unable to discover system include paths. Running '$command' failed.", exception)
        mutable.LinkedHashSet.empty
    }

  private def extractPaths(output: Seq[String]): mutable.LinkedHashSet[Path] = {
    val startIndex = output.indexWhere(_.contains("#include")) + 2
    val endIndex   = output.indexWhere(_.startsWith("End of search list."))
    mutable.LinkedHashSet.from(output.slice(startIndex, endIndex).map { pathString =>
      val trimmedPathString = pathString.trim
      val macSpecificFix = if (trimmedPathString.contains(" (") && trimmedPathString.endsWith(")")) {
        trimmedPathString.substring(0, trimmedPathString.indexOf(" ("))
      } else trimmedPathString
      Paths.get(macSpecificFix).toRealPath()
    })
  }

  private def discoverMSVCPaths(): mutable.LinkedHashSet[Path] = {
    discoverMSVCInstallPath().map(extractMSVCIncludePaths).getOrElse(mutable.LinkedHashSet.empty)
  }

  private def discoverMSVCInstallPath(): Option[String] = {
    GccSpecificExternalCommand.run(VsWhereCommand, ".") match {
      case Success(output) =>
        output.headOption
      case Failure(exception) =>
        logger.warn(s"Unable to discover MSVC installation path.", exception)
        None
    }
  }

  private def extractMSVCIncludePaths(resolvedInstallationPath: String): mutable.LinkedHashSet[Path] = {
    GccSpecificExternalCommand.run(VcVarsCommand, resolvedInstallationPath, Map("VSCMD_DEBUG" -> "3")) match {
      case Success(results) =>
        results.find(_.startsWith("INCLUDE=")) match {
          case Some(includesLine) =>
            val includesString = includesLine.replaceFirst("INCLUDE=", "")
            mutable.LinkedHashSet.from(includesString.split(";").map(p => Paths.get(p.trim).toRealPath()))
          case None => mutable.LinkedHashSet.empty
        }
      case Failure(exception) =>
        logger.warn(s"Unable to discover MSVC system include paths.", exception)
        mutable.LinkedHashSet.empty
    }
  }

  private def reportIncludePaths(paths: mutable.LinkedHashSet[Path], lang: String): Unit = {
    if (paths.nonEmpty) {
      val ls = System.lineSeparator()
      logger.info(s"Using the following $lang system include paths:${paths.mkString(s"$ls- ", s"$ls- ", ls)}")
    }
  }

  private def isMSVCProject(config: Config): Boolean = {
    if (!IsWin) return false
    val projectDir = Paths.get(config.inputPath)
    List(projectDir / ".vs", projectDir / ".vscode").exists(Files.exists(_)) ||
    projectDir.listFiles().exists(_.extension(includeDot = false).exists(ext => ext == "sln" || ext == "vcxproj"))
  }

  def discoverIncludePathsCPP(config: Config): mutable.LinkedHashSet[Path] = {
    if (!config.includePathsAutoDiscovery) return mutable.LinkedHashSet.empty
    if (systemIncludePathsCPP.nonEmpty) return systemIncludePathsCPP

    if (isMSVCProject(config)) {
      systemIncludePathsCPP = discoverMSVCPaths() // discovers paths for both languages
      systemIncludePathsC = systemIncludePathsCPP
      reportIncludePaths(systemIncludePathsCPP, "MSVC")
    }
    if (systemIncludePathsCPP.isEmpty && gccAvailable()) {
      systemIncludePathsCPP = discoverPaths(CppIncludeCommand)
      reportIncludePaths(systemIncludePathsCPP, "CPP")
    }
    systemIncludePathsCPP
  }

}
