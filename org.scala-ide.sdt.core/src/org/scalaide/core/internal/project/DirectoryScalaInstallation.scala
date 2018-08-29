package org.scalaide.core.internal.project

import java.io.File
import java.io.FileFilter

import scala.tools.nsc.settings.NoScalaVersion
import scala.tools.nsc.settings.ScalaVersion
import scala.util.Try

import org.eclipse.core.runtime.IPath
import org.eclipse.core.runtime.Path
import org.scalaide.core.internal.project.ScalaInstallation.extractVersion
import org.scalaide.util.internal.CompilerUtils.isBinarySame
import scala.collection.mutable.ListBuffer

/**
 * This class tries to collect a valid scala installation (library, compiler jars) from a directory.
 * The currently supported format is Just a Bunch of Jars: jars should be at the root of the directory,
 * there should be at least a scala-library and scala compiler, and the library should have a valid library.properties file.
 * The source jars and other jars (reflect, swing ...) are collected as best effort.
 *
 * TODO: support a more thorough lookup, such as when the jars are in lib/, maven, ivy ...
 * @param directory The directory in which to look for scala compiler & library jars.
 *
 */
class DirectoryScalaInstallation(val directory: IPath) extends ScalaInstallation {

  final val scalaLibraryPrefix = "scala-library"
  final val scalaReflectPrefix = "scala-reflect"
  final val scalaCompilerPrefix = "scala-compiler"
  final val scalaSwingPrefix = "scala-swing"

  //Hydra specific jars
  final val fullScalaLibraryPrefix = "org.scala-lang.scala-library"
  final val hydraReflectPrefix = "com.triplequote.scala-reflect"
  final val hydraCompilerPrefix = "com.triplequote.scala-compiler"
  final val hydraPrefix = "com.triplequote.hydra"
  final val hydraBridgePrefix = "hydra-bridge_1_0"
  final val scalaLoggingPrefix = "com.typesafe.scala-logging.scala-logging_"
  final val scalaXmlPrefix = "org.scala-lang.modules.scala-xml_"
  final val logbackClassicPrefix = "ch.qos.logback.logback-classic"
  final val logbackCorePrefix = "ch.qos.logback.logback-core"
  final val slf4jPrefix = "org.slf4j.slf4j-api"
  final val license4jPrefix = "com.license4j.license4j-runtime-library"
  final val hydraDashboardPrefix = "com.triplequote.dashboard-model_"
  final val hydraLicenseCheckingPrefix = "com.triplequote.license-checking"

  private val dirAsValidFile: Option[File] = {
    val f = directory.toFile()
    if (f.isDirectory()) Some(f) else None
  }

  private val extantJars: Option[Array[File]] = dirAsValidFile.map { f =>
    val fF = new FileFilter() { override def accept(p: File) = p.isFile && p.getName().endsWith(".jar") }
    f.listFiles(fF)
  }

  private def versionOfFileName(f: File): Option[String] = {
    val versionedRegex = """.*scala-\w+(.2\.\d+(?:\.\d*)?(?:-.*)?).jar""".r
    f.getName() match {
      case versionedRegex(version) => Some(version)
      case _ => None
    }
  }

  private def looksBinaryCompatible(version: ScalaVersion, module: ScalaModule) = {
    extractVersion(module.classJar) forall (isBinarySame(version, _))
  }

  /**
   * Returns a Option[ScalaModule] for the given prefix
   * @see [[findScalaJars(List[String]): List[ScalaModule]]
   */
  private def findScalaJars(prefix: String, presumedVersion: Option[String]): (Option[ScalaModule]) = {
    val res = findScalaJars(List(prefix), presumedVersion)
    if (res.nonEmpty) Some(res.head) else None
  }

  /**
   * Returns a List of whichever ScalaModule elements it could build from a string prefix in this instance's directory,
   * usually provided from the prefix constants defined in this class.
   *
   * @param prefix The intended jar prefix
   * @param presumedVersion a version string which will be preferred in filenames
   *        It should match """.2\.\d+(?:\.\d*)?(?:-.*)?""". If None, any version will be accepted.
   * @return A list of ScalaModule elements where class and source jars exist and start with the `prefix`
   */
  private def findScalaJars(prefixes: List[String], presumedVersion: Option[String]): List[ScalaModule] = {
    presumedVersion foreach { s => require(""".2\.\d+(?:\.\d*)?(?:-.*)?""".r.pattern.matcher(s).matches) }
    // for now this means we return whatever we could find: it may not be enough (missing scala-reflect, etc)

    prefixes flatMap { p =>
      val optionalVersion = """(?:.2\.\d+(?:\.\d*)?(?:-.*)?)?"""
      val requiredVersion = presumedVersion.fold(optionalVersion)(s => s.replaceAll("""\.""", """\\."""))
      val versionedString = s"$p$requiredVersion\\.jar"
      val versionedRegex = versionedString.r

      // Beware : the 'find' below indicates we're returning for the first matching option
      def jarLookup(r: scala.util.matching.Regex): Option[File] =
        (extantJars flatMap (_.find { f => r.pattern.matcher(f.getName()).matches }))

      // Try with any version if the presumed String can't be matched
      val classJarResult = jarLookup(versionedRegex) match {
        case s @ Some(_) => s
        case None => jarLookup((s"$p$optionalVersion\\.jar").r)
      }
      val foundVersion = classJarResult flatMap versionOfFileName
      val requiredSrcVersion = foundVersion getOrElse ""

      val versionedSrcString = s"$p-src$requiredSrcVersion.jar"
      val versionedSrcRegex = versionedSrcString.replaceAll("""\.""", """\\.""").r

      classJarResult map { j =>
        ScalaModule(new Path(j.getCanonicalPath()), jarLookup(versionedSrcRegex) map { f => new Path(f.getCanonicalPath()) })
      }
    }
  }

  private val libraryCandidate = findScalaJars(scalaLibraryPrefix, None).orElse(findScalaJars(fullScalaLibraryPrefix, None))
  private val presumedLibraryVersionString = libraryCandidate flatMap (l => versionOfFileName(l.classJar.toFile))
  private val versionCandidate: Option[ScalaVersion] = libraryCandidate.flatMap(l => extractVersion(l.classJar))
  private val compilerCandidate = findScalaJars(scalaCompilerPrefix, presumedLibraryVersionString).orElse(findScalaJars(hydraCompilerPrefix, presumedLibraryVersionString)) filter {
    module => (versionCandidate forall (looksBinaryCompatible(_, module)))
  }

  /* initialization checks*/
  if (!dirAsValidFile.isDefined) throw new IllegalArgumentException("The provided path does not point to a valid directory.")
  if (!extantJars.isDefined) throw new IllegalArgumentException("No jar files found. Please place Scala library, compiler jar at the root of the directory.")
  if (!libraryCandidate.isDefined) throw new IllegalArgumentException("Can not recognize a valid Scala library jar in this directory.")
  if (!compilerCandidate.isDefined) throw new IllegalArgumentException("Can not recognize a valid Scala compiler jar in this directory.")
  if (!versionCandidate.isDefined) throw new IllegalArgumentException("The Scala library jar in this directory has incorrect or missing version information, aborting.")
  // TODO : this hard-coded hook will need changing
  if (versionCandidate.isDefined && versionCandidate.get < ScalaVersion("2.10.0")) throw new IllegalArgumentException("This Scala version is too old for the presentation compiler to use. Please provide a 2.10 scala (or later).")
  // Hydra initialization checks
  if (isHydraInstallation) {
    if (allJars.filter(module => module.classJar.toFile().getName.contains(hydraBridgePrefix)).isEmpty)
      throw new IllegalArgumentException("Can not recognize a valid Hydra Bridge jar in this directory.")
    if (allJars.filter(module => module.classJar.toFile().getName.contains(hydraPrefix)).isEmpty)
      throw new IllegalArgumentException("Can not recognize a valid Hydra jar in this directory.")
    if (allJars.filter(module => module.classJar.toFile().getName.contains(hydraLicenseCheckingPrefix)).isEmpty)
      throw new IllegalArgumentException("Can not recognize a valid Hydra License Checking jar in this directory.")
    if (allJars.filter(module => module.classJar.toFile().getName.contains(license4jPrefix)).isEmpty)
      throw new IllegalArgumentException("Can not recognize a valid License4j jar in this directory.")
    if (allJars.filter(module => module.classJar.toFile().getName.contains(hydraDashboardPrefix)).isEmpty)
      throw new IllegalArgumentException("Can not recognize a valid Hydra Dashboard jar in this directory.")
  }

  private lazy val vanillaScalaExtraJars: List[ScalaModule] = findScalaJars(List(
    scalaReflectPrefix,
    scalaSwingPrefix,
    scalaXmlPrefix
  ), presumedLibraryVersionString).filter {
    module => versionCandidate forall (looksBinaryCompatible(_, module))
  }


  lazy val hydraJars: List[ScalaModule] = (extantJars.map { allJars =>
    val jars = ListBuffer.empty[File]
    val vanillaFiles: Set[File] = (for (module <- vanillaScalaExtraJars) yield {
      module.sourceJar.toSeq.map(_.toFile()) :+ module.classJar.toFile()
    }).flatten.toSet

    for (f <- extantJars.getOrElse(Array[File]()) if !vanillaFiles(f))
      jars += f

    jars.toList
  }).getOrElse(List()).map(jar => ScalaModule(new Path(jar.getCanonicalPath), None))

  override lazy val extraJars = if (isHydraInstallation)
    vanillaScalaExtraJars ++ hydraJars
  else
    vanillaScalaExtraJars

  override lazy val allJars: Seq[ScalaModule] = library +: compiler +: (extraJars ++ hydraJars)

  override lazy val compiler = compilerCandidate.get
  override lazy val library = libraryCandidate.get
  //If Hydra is used the version must be retrieved from the compiler jar
  override lazy val version = if (compiler.classJar.toFile().getName.contains(hydraCompilerPrefix))
    extractVersion(compiler.classJar).getOrElse(NoScalaVersion) else versionCandidate.get
}

object DirectoryScalaInstallation {

  def directoryScalaInstallationFactory(dir: IPath): Try[DirectoryScalaInstallation] = Try(new DirectoryScalaInstallation(dir))

}

class LabeledDirectoryScalaInstallation(name: String, directory: IPath) extends DirectoryScalaInstallation(directory) with LabeledScalaInstallation {
  override val label = CustomScalaInstallationLabel(name)

  def this(name: String, dsi: DirectoryScalaInstallation) = this(name, dsi.directory)
}
