package net.exoego.uika.mill

import coursier.core as cs
import mill.*
import mill.api.{Evaluator, SelectMode}
import mill.javalib.{BoundDep, CoursierModule, Dep, JavaModule, TestModule}
import mill.scalalib.ScalaModule
import net.exoego.uika.plugin.core.{ClasspathDump, DumpFormat, JfrEvidence, UikaCli}

import scala.annotation.nowarn
import scala.jdk.CollectionConverters.*

/**
 * The one entry point to uika in a Mill build. The user declares it once as a top-level
 * object, overrides the settings there, and runs `./mill uika.dumpClasspath` and
 * `./mill uika.upgradeCheck`.
 *
 * The commands find every non-test `JavaModule` through the `Evaluator` themselves, so nothing
 * else in the build mixes anything in. The exception is JFR collection, which has to reach a
 * test JVM's `forkArgs` ([[UikaTestModule]]).
 *
 * Settings live here and never on a command, so the dump and the check cannot disagree. As
 * command arguments, a `--jdkRelease` given to the check and forgotten on the dump silently
 * lost JDK-move detection.
 */
trait UikaModule extends mill.Module {

  /**
   * The JDK API release. Negative derives it: the check uses the lowest release any module
   * compiles for, else the build JVM's, and the dump records each module's own. 0 switches the
   * check's API layer off and leaves the dump derived. A positive value is also recorded as the
   * release every module runs on, for a build whose runtime is not what it compiles against.
   */
  def jdkRelease: T[Int] = Task { -1 }

  /** The check's `--fail-on` threshold. */
  def failOn: T[String] = Task { "any" }

  /** Exclude files for the check, relative to the workspace. */
  def excludeFiles: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * Check the union of every module's classpath once instead of each module against its own
   * resolution. A break only one module's resolution shows can hide behind another module's
   * version of the same jar.
   */
  def mergedClasspath: T[Boolean] = Task { false }

  /** Where the check writes draft exclude rules, relative to the workspace. Empty writes none. */
  def draftExcludeFile: T[String] = Task { "" }

  /** The uika-cli version to run. Empty runs the plugin's own version. */
  def cliVersion: T[String] = Task { "" }

  /**
   * Writes every non-test module's resolved runtime classpath as a uika v2 dump.
   *
   * Evaluating a module's `localRunClasspath` compiles it, so the build outputs the dump points
   * at exist by the time the CLI scans them. That mirrors the sbt plugin; Mill has no
   * resolution-only mode to opt into because a Mill module cannot resolve its own runtime
   * classpath without its upstream modules' compile output existing anyway.
   */
  def dumpClasspath(ev: Evaluator, output: String = "") = Task.Command(exclusive = true) {
    val modules = javaModules(ev)
    if (modules.isEmpty) {
      Task.fail("uika: no JavaModule found in this build")
    }
    val declaredOverride = UikaCli.overrideRelease(Int.box(jdkRelease()))
    val dumps = ev.execute(modules.map(moduleDumpTask(_, declaredOverride))).values.get
    val workspace = Task.ctx().workspace
    val out =
      if (output.isEmpty) workspace / "out" / "uika" / "classpath.json"
      else os.Path(output, workspace)
    os.makeDir.all(out / os.up)
    os.write.over(
      out,
      DumpFormat.writeV2(
        dumps.asJava,
        Seq(workspace.toString).asJava,
        DumpFormat.dumpRelease(dumps.asJava)
      )
    )
    Task.log.info(s"uika classpath dump: $out")
    out.toString
  }

  /**
   * Runs `uika upgrade-check` over a before/after pair of dumps, fetching the CLI itself as
   * the `jvm` jar of `net.exoego.uika:uika-cli:<version>` through Mill's own resolution.
   *
   * Runtime load evidence comes from `UIKA_JFR`, the variable that made the tests record, so
   * one value serves both phases. It is not a setting because a value in the build file
   * would make every forked test run record.
   */
  // persistent so `Task.dest` survives: Mill wipes a non-persistent dest before every run,
  // which would defeat JfrEvidence.rewrite's stale-conversion sweep.
  def upgradeCheck(ev: Evaluator, before: String, after: String) =
    Task.Command(exclusive = true, persistent = true) {
    // Task.env, never System.getenv: the latter is the DAEMON's environment, captured when
    // the server started, so `UIKA_CLI_PATH=... ./mill` would be ignored against a warm
    // daemon. This file already reads UIKA_JFR that way.
    val overrideBinary = Option(UikaCli.overrideFrom(Task.env.getOrElse(UikaCli.CLI_PATH_ENV, null)))
    val wantedVersion = cliVersion()
    // Demanded only when something has to be resolved: with an override there is no version
    // to want, and failing here would contradict the documented "it wins over the version".
    lazy val version = wantedVersion match {
      case "" =>
        // classOf, not getClass: the latter is the user's object, in the build's package.
        Option(classOf[UikaModule].getPackage.getImplementationVersion).filter(_.nonEmpty).getOrElse(
          Task.fail("uika-cli version is unknown; set cliVersion on the uika module")
        )
      case v => v
    }
    val workspace = Task.ctx().workspace
    val log: java.util.function.Consumer[String] = line => Task.log.info(line)
    // The CLI jar goes through a build module's own resolver, so custom `repositories`,
    // mirrors and credentials are the build's. Any module will do: repositories are declared
    // on a shared trait in practice. Failing rather than falling back to a resolver of this
    // plugin's own keeps that promise -- a `defaultResolver()` call here would be lifted into
    // an unconditional task edge by the command macro and evaluated even on the Some branch.
    val modules = javaModules(ev)
    val binary = overrideBinary.getOrElse {
      val resolver = modules.headOption match {
        case Some(m) => ev.execute(Seq(m.defaultResolver)).values.get.head
        case None => Task.fail("uika: no JavaModule found in this build")
      }
      resolveCli(resolver, version)
    }
    // Recordings are converted here, never handed to the CLI: the CLI must not read binary JFR.
    val classLoadLogs = JfrEvidence.rewrite(
      Task.env.get("UIKA_JFR").filter(_.nonEmpty).map(os.Path(_, workspace).toNIO).toSeq.asJava,
      (Task.dest / JfrEvidence.WORK_DIR_NAME).toNIO,
      log
    )
    // The LOWEST release any module compiles for, because one flag serves a run that checks
    // every module. Under-claiming only costs Unknowns, while over-claiming makes a member
    // the runtime lacks resolve cleanly and loses the finding with nothing to show. A build
    // declaring nothing falls back to the JVM, the only evidence left. The dump keeps each
    // module's own release next to it (moduleDumpTask); the flag stays one value because the
    // layer it switches on is process-wide.
    //
    // mandatoryJavacOptions as well as javacOptions, since Mill compiles with both and a
    // trait that pins the release commonly does it there. The scalac list too, because a
    // pure-Scala module has no javacOptions at all and states its target in scalacOptions
    // (`-release` / `-java-output-version`), which declaredRelease parses. allScalacOptions
    // rather than the two halves: mandatoryScalacOptions is protected, and the combined
    // list is what Mill actually hands scalac.
    val jdk = UikaCli.JdkSource.current()
    val setRelease = jdkRelease()
    val wantedRelease =
      if (setRelease >= 0) setRelease
      else {
        val optionTasks = modules.map(_.javacOptions) ++ modules.map(_.mandatoryJavacOptions) ++
          modules.collect { case s: ScalaModule => s.allScalacOptions }
        val declared = ev.execute(optionTasks).values.get
          .flatMap(options => Option(UikaCli.declaredRelease(options.asJava)).map(_.intValue))
        if (declared.isEmpty) Runtime.version().feature() else declared.min
      }
    val exit = UikaCli.runUpgradeCheck(
      binary,
      os.Path(before, workspace).toNIO,
      os.Path(after, workspace).toNIO,
      failOn(),
      excludeFiles().map(os.Path(_, workspace).toNIO).asJava,
      UikaCli.effectiveJdkRelease(wantedRelease, jdk, log),
      jdk,
      classLoadLogs,
      Option(draftExcludeFile()).filter(_.nonEmpty).map(os.Path(_, workspace).toNIO).orNull,
      mergedClasspath(),
      log
    )
    exit match {
      case 0 => ()
      case 1 => Task.fail("uika upgrade-check found broken references (see output above)")
      case n => Task.fail(s"uika upgrade-check failed with exit code $n")
    }
  }

  /**
   * The pure-Java CLI jar, resolved through Mill's coursier setup so mirrors, credentials and
   * the cache are the build's own. It runs from that cache on the JVM running Mill.
   */
  private def resolveCli(
      resolver: CoursierModule.Resolver,
      version: String
  )(using mill.api.TaskCtx): java.nio.file.Path = {
    // Intransitive, as in all three sibling plugins: the jar has no dependencies, and
    // anything the POM ever gains would be downloaded and could win the pick below.
    val dep = Dep.parse(
      s"${UikaCli.GROUP}:${UikaCli.ARTIFACT}:$version;classifier=${UikaCli.JAR_CLASSIFIER}"
    ).exclude("*" -> "*")
    val resolved = resolver.classpath(Seq(dep)).map(_.path)
    resolved
      .find(p => p.last.startsWith(UikaCli.ARTIFACT) && p.last.endsWith(".jar"))
      .getOrElse(Task.fail(s"uika-cli jar not found among ${resolved.mkString(", ")}"))
      .toNIO
  }

  /**
   * Every module the dump covers. Test modules are excluded on purpose: uika checks what ships,
   * and a test-only dependency is never on the runtime classpath the check compares.
   */
  private def javaModules(ev: Evaluator): Seq[JavaModule] =
    ev.resolveModulesOrTasks(Seq("__"), SelectMode.Multi).get.collect {
      case Left(m: JavaModule) if !m.isInstanceOf[TestModule] => m
    }

  /**
   * One module's dump entry.
   *
   * `Task.traverse` rather than calling `dep.localRunClasspath()` in the body: the dependency list
   * is only known at runtime, and Mill's task macro can only lift statically known task calls
   * into edges.
   */
  private def moduleDumpTask(
      m: JavaModule,
      declaredOverride: Integer
  ): Task[ClasspathDump.Module] = {
    val depModules = m.recursiveRunModuleDeps
    val depOutputs = Task.traverse(depModules)(_.localRunClasspath)
    // Collected outside the body: the task macro can only lift statically known task calls,
    // and a call scoped inside a pattern-match branch is not one.
    val scalacOptionTasks: Seq[Task[Seq[String]]] = m match {
      case s: ScalaModule => Seq(s.allScalacOptions)
      case _ => Nil
    }
    val scalacDeclared = Task.traverse(scalacOptionTasks)(identity)
    Task.Anon {
      val ownOutput = m.localRunClasspath().map(_.path)
      val classesDirs = ownOutput.filter(os.exists)

      // Coordinates come from the resolution, never from file paths. Mill models module deps as
      // synthetic coursier projects with no publications, so this yields external artifacts only
      // and the internal ones are attributed from `depOutputs` below.
      //
      // withConfiguration is deprecated in coursier 2.1.25, but JavaModule.resolvedRunMvnDeps
      // still builds the runtime dependency exactly this way, and matching it verbatim is the
      // point: the coordinates must describe the resolution the module actually runs on.
      val runtimeDep: cs.Dependency =
        m.coursierDependencyTask().withConfiguration(cs.Configuration.runtime): @nowarn(
          "cat=deprecation"
        )
      val resolved = m.millResolver().fetchArtifacts(Seq(BoundDep(runtimeDep, force = false)))
      // The RESOLVED version, not `dep.versionConstraint.asString`: a declared range or a
      // dynamic version would otherwise be written verbatim, and two dumps taken either side
      // of a real upgrade would carry the same constraint string and diff to no change.
      val resolvedVersions = resolved.resolution.projectCache0.map {
        case (key, (_, project)) => key -> project.version0.asString
      }
      val coordinates = resolved.fullDetailedArtifacts0.collect {
        case (dep, _, _, Some(file)) =>
          os.Path(file) -> (
            dep.module.organization.value,
            dep.module.name.value,
            resolvedVersions.getOrElse(
              (dep.module, dep.versionConstraint),
              dep.versionConstraint.asString
            )
          )
      }.toMap

      // Only a dep module's own OUTPUT carries its `project` label: the key tells uika it may
      // substitute that module's classesDirs when the file is missing, which is a lie for a
      // jar the dep merely puts on the classpath (`unmanagedClasspath`, `compileResources`).
      val projectOf = depModules.zip(depOutputs()).flatMap { case (dep, entries) =>
        entries.map(_.path -> moduleLabel(dep))
      }.toMap

      // Subtract exactly what this module produces, and NOT `localClasspath()` -- that also
      // holds `unmanagedClasspath()`, so subtracting it dropped a module's vendored jars from
      // the dump altogether even though they are on the classpath it runs on.
      // `os.exists` because a module names its resource directories whether or not they were
      // ever created, and an entry pointing at nothing is noise in every report.
      val own = (ownOutput ++ m.compileResources().map(_.path)).toSet
      val entries = m.runClasspath().map(_.path).distinct.filterNot(own).filter(os.exists)
      val artifacts = entries.map { path =>
        val (group, name, version) = coordinates.getOrElse(path, (null, null, null))
        new ClasspathDump.Artifact(group, name, version, path.toString, projectOf.get(path).orNull)
      }

      // What THIS module compiles for, in the dump next to it, so upgrade-check can scope a
      // JDK move to the modules that made it. mandatoryJavacOptions as well as javacOptions,
      // for the reason upgradeCheck spells out: Mill compiles with both. The scalac lists
      // too, since a pure-Scala module states its target there alone.
      val declared = (Seq(m.javacOptions(), m.mandatoryJavacOptions()) ++ scalacDeclared())
        .flatMap(options => Option(UikaCli.declaredRelease(options.asJava)))
      new ClasspathDump.Module(
        moduleLabel(m),
        classesDirs.map(_.toString).asJava,
        artifacts.asJava,
        if (declaredOverride != null) declaredOverride
        else if (declared.isEmpty) null
        else declared.minBy(_.intValue)
      )
    }
  }

  /** `:foo:bar`, the `:path` shape the dump format uses for Gradle and Maven modules too. */
  private def moduleLabel(m: JavaModule): String = ":" + m.moduleSegments.parts.mkString(":")
}
