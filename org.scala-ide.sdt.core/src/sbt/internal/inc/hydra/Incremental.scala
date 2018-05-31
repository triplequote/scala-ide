package sbt.internal.inc.hydra

import java.io.File

import sbt.internal.inc.IncrementalNameHashing;

import sbt.util.{ Level, Logger }
import xsbti.compile.analysis.{ ReadStamps, Stamp => XStamp }
import xsbti.compile.{
  ClassFileManager => XClassFileManager,
  CompileAnalysis,
  DependencyChanges,
  IncOptions
}
import sbt.internal.inc.IncrementalCommon
import sbt.internal.inc.Lookup
import sbt.internal.inc.ClassFileManager
import sbt.internal.inc.Analysis

/**
 * Copied the file from https://github.com/triplequote/hydra repository (v0.10.0 tag)
 */
object Incremental {
  class PrefixingLogger(val prefix: String)(orig: Logger) extends Logger {
    def trace(t: => Throwable): Unit = orig.trace(t)
    def success(message: => String): Unit = orig.success(message)
    def log(level: Level.Value, message: => String): Unit = level match {
      case Level.Debug => orig.log(level, message.replaceAll("(?m)^", prefix))
      case _           => orig.log(level, message)
    }
  }

  /**
   * Runs the incremental compiler algorithm.
   *
   * @param sources   The sources to compile
   * @param lookup
   *              An instance of the `Lookup` that implements looking up both classpath elements
   *              and Analysis object instances by a binary class name.
   * @param previous0 The previous dependency Analysis (or an empty one).
   * @param current  A mechanism for generating stamps (timestamps, hashes, etc).
   * @param compile  The function which can run one level of compile.
   * @param callbackBuilder The builder that builds callback where we report dependency issues.
   * @param log  The log where we write debugging information
   * @param options  Incremental compilation options
   * @param equivS  The means of testing whether two "Stamps" are the same.
   * @return
   *         A flag of whether or not compilation completed succesfully, and the resulting dependency analysis object.
   */
  def compile(
      sources: Set[File],
      lookup: Lookup,
      previous0: CompileAnalysis,
      current: ReadStamps,
      compile: (Set[File], DependencyChanges, xsbti.AnalysisCallback, XClassFileManager) => Unit,
      callbackBuilder: AnalysisCallback.Builder,
      log: sbt.util.Logger,
      options: IncOptions
  )(implicit equivS: Equiv[XStamp]): (Boolean, Analysis) = {
    val previous = previous0 match { case a: Analysis => a }
    val incremental: IncrementalCommon =
      new IncrementalNameHashing(log, options)
    val initialChanges = incremental.changedInitial(sources, previous, current, lookup)
    val binaryChanges = new DependencyChanges {
      val modifiedBinaries = initialChanges.binaryDeps.toArray
      val modifiedClasses = initialChanges.external.allModified.toArray
      def isEmpty = modifiedBinaries.isEmpty && modifiedClasses.isEmpty
    }
    val (initialInvClasses, initialInvSources) =
      incremental.invalidateInitial(previous.relations, initialChanges)
    if (initialInvClasses.nonEmpty || initialInvSources.nonEmpty)
      if (initialInvSources == sources) incremental.log.debug("All sources are invalidated.")
      else
        incremental.log.debug(
          "All initially invalidated classes: " + initialInvClasses + "\n" +
            "All initially invalidated sources:" + initialInvSources + "\n")
    val analysis = manageClassfiles(options) { classfileManager =>
      incremental.cycle(initialInvClasses,
                        initialInvSources,
                        sources,
                        binaryChanges,
                        lookup,
                        previous,
                        doCompile(compile, callbackBuilder, classfileManager),
                        classfileManager,
                        1)
    }
    (initialInvClasses.nonEmpty || initialInvSources.nonEmpty, analysis)
  }

  /**
   * Compilation unit in each compile cycle.
   */
  def doCompile(
      compile: (Set[File], DependencyChanges, xsbti.AnalysisCallback, XClassFileManager) => Unit,
      callbackBuilder: AnalysisCallback.Builder,
      classFileManager: XClassFileManager
  )(srcs: Set[File], changes: DependencyChanges): Analysis = {
    // Note `ClassFileManager` is shared among multiple cycles in the same incremental compile run,
    // in order to rollback entirely if transaction fails. `AnalysisCallback` is used by each cycle
    // to report its own analysis individually.
    val callback = callbackBuilder.build()
    compile(srcs, changes, callback, classFileManager)
    callback.get
  }

  // the name of system property that was meant to enable debugging mode of incremental compiler but
  // it ended up being used just to enable debugging of relations. That's why if you migrate to new
  // API for configuring incremental compiler (IncOptions) it's enough to control value of `relationsDebug`
  // flag to achieve the same effect as using `incDebugProp`.
  @deprecated("Use `IncOptions.relationsDebug` flag to enable debugging of relations.", "0.13.2")
  val incDebugProp = "xsbt.inc.debug"

  private[inc] val apiDebugProp = "xsbt.api.debug"
  private[inc] def apiDebug(options: IncOptions): Boolean =
    options.apiDebug || java.lang.Boolean.getBoolean(apiDebugProp)

  private[sbt] def prune(invalidatedSrcs: Set[File], previous: CompileAnalysis): Analysis =
    prune(invalidatedSrcs, previous, ClassFileManager.deleteImmediately)

  private[sbt] def prune(invalidatedSrcs: Set[File],
                         previous0: CompileAnalysis,
                         classfileManager: XClassFileManager): Analysis = {
    val previous = previous0 match { case a: Analysis => a }
    classfileManager.delete(invalidatedSrcs.flatMap(previous.relations.products).toArray)
    previous -- invalidatedSrcs
  }

  private[this] def manageClassfiles[T](options: IncOptions)(run: XClassFileManager => T): T = {
    val classfileManager = ClassFileManager.getClassFileManager(options)
    val result = try run(classfileManager)
    catch {
      case e: Throwable =>
        classfileManager.complete(false)
        throw e
    }
    classfileManager.complete(true)
    result
  }
}