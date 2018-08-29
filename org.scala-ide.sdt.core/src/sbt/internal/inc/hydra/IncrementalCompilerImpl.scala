package sbt.internal.inc.hydra

import java.io.File
import java.util.Optional
import java.util.function.{ Function => JavaFunction }

import sbt.internal.inc.Analysis;
import sbt.internal.inc.Incremental;
import sbt.internal.inc.LookupImpl;
import sbt.internal.inc.MiniSetupUtil;
import sbt.internal.inc.MixedAnalyzingCompiler;
import sbt.internal.inc.ZincUtil;
import sbt.internal.inc.JavaInterfaceUtil._
import sbt.util.InterfaceUtil
import xsbti._
import xsbti.compile.CompileOrder.Mixed
import xsbti.compile.{ ClasspathOptions => XClasspathOptions, JavaTools => XJavaTools, _ }
import sbt.internal.inc.CompileOutput

/**
 * Copied the file from https://github.com/triplequote/hydra repository (v0.10.0 tag)
 */
class IncrementalCompilerImpl extends IncrementalCompiler {
  /**
   * Performs an incremental compilation based on [[xsbti.compile.Inputs]].
   *
   * This is a Scala implementation of [[xsbti.compile.IncrementalCompiler]],
   * check the docs for more information on the specification of this method.
   *
   * @param in An instance of [[xsbti.compile.Inputs]] that collect all the
   *           inputs required to run the compiler (from sources and classpath,
   *           to compilation order, previous results, current setup, etc).
   * @param logger An instance of [[xsbti.Logger]] to log Zinc output.
   *
   * @return An instance of [[xsbti.compile.CompileResult]] that holds
   *         information about the results of the compilation. The returned
   *         [[xsbti.compile.CompileResult]] must be used for subsequent
   *         compilations that depend on the same inputs, check its api and its
   *         field [[xsbti.compile.CompileAnalysis]].
   */
  override def compile(in: Inputs, logger: Logger): CompileResult = {
    val config = in.options()
    val setup = in.setup()
    import config._
    import setup._
    val compilers = in.compilers
    val javacChosen = compilers.javaTools.javac
    val scalac = compilers.scalac
    val extraOptions = extra.toList.map(_.toScalaTuple)
    compileIncrementally(
      scalac,
      javacChosen,
      sources,
      classpath,
      CompileOutput(classesDirectory),
      cache,
      progress().toOption,
      scalacOptions,
      javacOptions,
      in.previousResult.analysis.toOption,
      in.previousResult.setup.toOption,
      perClasspathEntryLookup,
      reporter,
      order,
      skip,
      incrementalCompilerOptions,
      extraOptions
    )(logger)
  }

  /**
   *
   * Performs an incremental compilation based on [[xsbti.compile.Inputs]].
   *
   * This is a Scala implementation of [[xsbti.compile.IncrementalCompiler]],
   * check the docs for more information on the specification of this method.
   *
   * @param scalaCompiler The Scala compiler to compile Scala sources.
   * @param javaCompiler The Java compiler to compile Java sources.
   * @param sources An array of Java and Scala source files to be compiled.
   * @param classpath An array of files representing classpath entries.
   * @param output An instance of [[Output]] to store the compiler outputs.
   * @param cache                   Directory where previous cached compilers are stored.
   * @param scalaOptions            An array of options/settings for the Scala compiler.
   * @param javaOptions             An array of options for the Java compiler.
   * @param previousAnalysis        Optional previous incremental compilation analysis.
   * @param previousSetup           Optional previous incremental compilation setup.
   * @param perClasspathEntryLookup Lookup of data structures and operations
   *                                for a given classpath entry.
   * @param reporter                An instance of [[Reporter]] to report compiler output.
   * @param compileOrder            The order in which Java and Scala sources should
   *                                be compiled.
   * @param skip                    Flag to ignore this compilation run and return previous one.
   * @param progress                An instance of [[CompileProgress]] to keep track of
   *                                the current compilation progress.
   * @param incrementalOptions      An Instance of [[IncOptions]] that configures
   *                                the incremental compiler behaviour.
   * @param extra                   An array of sbt tuples with extra options.
   * @param logger An instance of [[Logger]] that logs Zinc output.
   * @return An instance of [[xsbti.compile.CompileResult]] that holds
   *         information about the results of the compilation. The returned
   *         [[xsbti.compile.CompileResult]] must be used for subsequent
   *         compilations that depend on the same inputs, check its api and its
   *         field [[xsbti.compile.CompileAnalysis]].
   */
  override def compile(
      scalaCompiler: xsbti.compile.ScalaCompiler,
      javaCompiler: xsbti.compile.JavaCompiler,
      sources: Array[File],
      classpath: Array[File],
      output: xsbti.compile.Output,
      cache: xsbti.compile.GlobalsCache,
      scalaOptions: Array[String],
      javaOptions: Array[String],
      previousAnalysis: Optional[xsbti.compile.CompileAnalysis],
      previousSetup: Optional[xsbti.compile.MiniSetup],
      perClasspathEntryLookup: xsbti.compile.PerClasspathEntryLookup,
      reporter: Reporter,
      compileOrder: xsbti.compile.CompileOrder,
      skip: java.lang.Boolean,
      progress: Optional[xsbti.compile.CompileProgress],
      incrementalOptions: xsbti.compile.IncOptions,
      extra: Array[xsbti.T2[String, String]],
      logger: xsbti.Logger
  ) = {
    val extraInScala = extra.toList.map(_.toScalaTuple)
    compileIncrementally(
      scalaCompiler,
      javaCompiler,
      sources,
      classpath.toSeq,
      output,
      cache,
      progress.toOption,
      scalaOptions.toSeq,
      javaOptions.toSeq,
      previousAnalysis.toOption,
      previousSetup.toOption,
      perClasspathEntryLookup,
      reporter,
      compileOrder,
      skip: Boolean,
      incrementalOptions,
      extraInScala
    )(logger)
  }

  /**
   * Handle the compilation error in an independent method to avoid
   * confusing compilation execution logic with error handling logic.
   */
  private def handleCompilationError(
      sources: Array[File],
      output: Output,
      logger: Logger
  )(compilerRun: => CompileResult): CompileResult = {
    try {
      compilerRun
    } catch {
      case e: CompileFailed => throw e // just ignore
      case e: Throwable =>
        val ex = e // For Intellij debugging purpose
        val numberSources = s"${sources.length} sources"
        val outputString = output match {
          case singleOutput: SingleOutput =>
            singleOutput.getOutputDirectory().toString
          case multiOutput: MultipleOutput =>
            multiOutput
              .getOutputGroups()
              .map(_.getOutputDirectory().toString)
              .mkString("[", ", ", "]")
          case _ =>
            s"other output ($output)"
        }

        val msg =
          s"""## Exception when compiling $numberSources to $outputString
             |${e.getMessage}
             |${ex.getStackTrace.mkString("\n")}
           """
        logger.error(InterfaceUtil.toSupplier(msg.stripMargin))
        throw ex
    }
  }

  /**
   * Run the mixed compilation of Java and Scala sources. This is the
   * actual implementation of the `compile` methods and they proxy to it.
   *
   * @param scalaCompiler The Scala compiler to compile Scala sources.
   * @param javaCompiler The Java compiler to compile Java sources.
   * @param sources An array of Java and Scala source files to be compiled.
   * @param classpath An array of files representing classpath entries.
   * @param output An instance of [[Output]] to store the compiler outputs.
   * @param cache                   Directory where previous cached compilers are stored.
   * @param scalaOptions            An array of options/settings for the Scala compiler.
   * @param javaOptions             An array of options for the Java compiler.
   * @param previousAnalysis        Optional previous incremental compilation analysis.
   * @param previousSetup           Optional previous incremental compilation setup.
   * @param perClasspathEntryLookup Lookup of data structures and operations
   *                                for a given classpath entry.
   * @param reporter                An instance of [[Reporter]] to report compiler output.
   * @param compileOrder            The order in which Java and Scala sources should
   *                                be compiled.
   * @param skip                    Flag to ignore this compilation run and return previous one.
   * @param progress                An instance of [[CompileProgress]] to keep track of
   *                                the current compilation progress.
   * @param incrementalOptions      An Instance of [[IncOptions]] that configures
   *                                the incremental compiler behaviour.
   * @param extra                   An array of sbt tuples with extra options.
   * @param logger An instance of [[Logger]] that logs Zinc output.
   * @return An instance of [[xsbti.compile.CompileResult]] that holds
   *         information about the results of the compilation. The returned
   *         [[xsbti.compile.CompileResult]] must be used for subsequent
   *         compilations that depend on the same inputs, check its api and its
   *         field [[xsbti.compile.CompileAnalysis]].
   */
  private[sbt] def compileIncrementally(
      scalaCompiler: xsbti.compile.ScalaCompiler,
      javaCompiler: xsbti.compile.JavaCompiler,
      sources: Array[File],
      classpath: Seq[File],
      output: Output,
      cache: GlobalsCache,
      progress: Option[CompileProgress] = None,
      scalaOptions: Seq[String] = Nil,
      javaOptions: Seq[String] = Nil,
      previousAnalysis: Option[CompileAnalysis],
      previousSetup: Option[MiniSetup],
      perClasspathEntryLookup: PerClasspathEntryLookup,
      reporter: Reporter,
      compileOrder: CompileOrder = Mixed,
      skip: Boolean = false,
      incrementalOptions: IncOptions,
      extra: List[(String, String)]
  )(implicit logger: Logger): CompileResult = {
    handleCompilationError(sources, output, logger) {
      val prev = previousAnalysis match {
        case Some(previous) => previous
        case None           => Analysis.empty
      }
      val config = MixedAnalyzingCompiler.makeConfig(
        scalaCompiler,
        javaCompiler,
        sources,
        classpath,
        output,
        cache,
        progress,
        scalaOptions,
        javaOptions,
        prev,
        previousSetup,
        perClasspathEntryLookup,
        reporter,
        compileOrder,
        skip,
        incrementalOptions,
        extra
      )
      if (skip) CompileResult.of(prev, config.currentSetup, false)
      else {
        val (analysis, changed) = compileInternal(
          MixedAnalyzingCompiler(config)(logger),
          MiniSetupUtil.equivCompileSetup,
          MiniSetupUtil.equivPairs,
          logger
        )
        CompileResult.of(analysis, config.currentSetup, changed)
      }
    }
  }

  /**
   * Run the incremental compiler using the given mixed compiler.
   *
   * This operation prunes the inputs based on [[MiniSetup]].
   */
  private[sbt] def compileInternal(
      mixedCompiler: MixedAnalyzingCompiler,
      equiv: Equiv[MiniSetup],
      equivPairs: Equiv[Array[T2[String, String]]],
      log: Logger
  ): (Analysis, Boolean) = {
    import mixedCompiler.config._
    import mixedCompiler.config.currentSetup.output
    val lookup = new LookupImpl(mixedCompiler.config, previousSetup)
    val srcsSet = sources.toSet
    val analysis = previousSetup match {
      case Some(previous) =>
        // Return an empty analysis if values of extra have changed
        if (equiv.equiv(previous, currentSetup))
          previousAnalysis
        else if (!equivPairs.equiv(previous.extra, currentSetup.extra))
          Analysis.empty
        else Incremental.prune(srcsSet, previousAnalysis)
      case None =>
        Incremental.prune(srcsSet, previousAnalysis)
    }

    // Run the incremental compilation
    val compile = IncrementalCompile(
      srcsSet,
      lookup,
      mixedCompiler.compile,
      analysis,
      output,
      log,
      incOptions
    )
    compile.swap
  }

  def setup(
      lookup: PerClasspathEntryLookup,
      skip: Boolean,
      cacheFile: File,
      cache: GlobalsCache,
      incOptions: IncOptions,
      reporter: Reporter,
      optionProgress: Option[CompileProgress],
      extra: Array[T2[String, String]]
  ): Setup = {
    val progress = optionProgress.toOptional
    Setup.of(lookup, skip, cacheFile, cache, incOptions, reporter, progress, extra)
  }

  def inputs(
      options: CompileOptions,
      compilers: Compilers,
      setup: Setup,
      pr: PreviousResult
  ): Inputs = {
    Inputs.of(compilers, options, setup, pr)
  }

  def inputs(
      classpath: Array[File],
      sources: Array[File],
      classesDirectory: File,
      scalacOptions: Array[String],
      javacOptions: Array[String],
      maxErrors: Int,
      sourcePositionMappers: Array[JavaFunction[Position, Optional[Position]]],
      order: CompileOrder,
      compilers: Compilers,
      setup: Setup,
      pr: PreviousResult
  ): Inputs = {
    val compileOptions = {
      CompileOptions.of(
        classpath,
        sources,
        classesDirectory,
        scalacOptions,
        javacOptions,
        maxErrors,
        foldMappers(sourcePositionMappers),
        order
      )
    }
    inputs(compileOptions, compilers, setup, pr)
  }

  def previousResult(result: CompileResult): PreviousResult = {
    PreviousResult.of(
      Optional.of[CompileAnalysis](result.analysis),
      Optional.of[MiniSetup](result.setup)
    )
  }

  def emptyPreviousResult: PreviousResult = {
    PreviousResult.of(
      Optional.empty[CompileAnalysis],
      Optional.empty[MiniSetup]
    )
  }

  def compilers(
      instance: xsbti.compile.ScalaInstance,
      cpOptions: XClasspathOptions,
      javaHome: Option[File],
      scalac: ScalaCompiler
  ): Compilers =
    ZincUtil.compilers(instance, cpOptions, javaHome, scalac)

  def compilers(javaTools: XJavaTools, scalac: ScalaCompiler): Compilers =
    ZincUtil.compilers(javaTools, scalac)

  /* *********************************************************************** */
  /* * Define helpers to convert from sbt Java interface to the Scala one  * */
  /* *********************************************************************** */

  private[sbt] def foldMappers[A](mappers: Array[JavaFunction[A, Optional[A]]]) = {
    mappers.foldRight(InterfaceUtil.toJavaFunction[A, A](identity)) { (mapper, mappers) =>
      InterfaceUtil.toJavaFunction[A, A]({ p: A =>
        mapper(p).toOption.getOrElse(mappers(p))
      })
    }
  }
}