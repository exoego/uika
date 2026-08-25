package net.exoego.uika.mill

import coursier.core as cs
import mill.*
import mill.api.{Discover, Evaluator, ExternalModule, SelectMode}
import mill.javalib.{BoundDep, CoursierModule, Dep, JavaModule, TestModule}
import net.exoego.uika.plugin.core.{ClasspathDump, DumpFormat, JfrEvidence, UikaCli}

import scala.annotation.nowarn
import scala.jdk.CollectionConverters.*

/**
 * Mill entry points for uika, invoked as `./mill net.exoego.uika.mill.Uika/dumpClasspath`.
 *
 * An [[ExternalModule]] rather than a trait users mix in, so a build of any size is wired up by
 * the `//| mvnDeps` header alone: the commands find every non-test `JavaModule` through the
 * `Evaluator` themselves. The one thing this shape cannot reach is a test JVM's `forkArgs`, so
 * JFR class-load collection is the one part that does need a mixin ([[UikaTestModule]]).
 */
object Uika extends ExternalModule {

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
    val dumps = ev.execute(modules.map(moduleDumpTask)).values.get
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
   * `net.exoego.uika:uika-cli:<version>:<platform>@zip` through Mill's own resolution.
   *
   * @param jdkRelease resolve JDK hierarchy escapes against this API release; 0 disables the
   *                   layer and a negative value, the default, derives the lowest release any
   *                   module compiles for, else the build JVM's, clamped by
   *                   [[UikaCli.effectiveJdkRelease]] to what its ct.sym serves
   * @param jfr        a directory of JFR recordings from a test run of the current, not yet
   *                   upgraded build, or a single `.jfr` recording
   */
  def upgradeCheck(
      ev: Evaluator,
      before: String,
      after: String,
      failOn: String = "any",
      excludeFile: Seq[String] = Nil,
      jdkRelease: Int = -1,
      jfr: String = "",
      draftExcludeFile: String = "",
      cliVersion: String = ""
  // persistent so `Task.dest` survives: Mill wipes a non-persistent dest before every run,
  // which would defeat both UikaCli.extractBinary's skip-if-present and JfrEvidence.rewrite's
  // stale-conversion sweep. The other three plugins extract into their build directory.
  ) = Task.Command(exclusive = true, persistent = true) {
    val version = cliVersion match {
      case "" =>
        Option(getClass.getPackage.getImplementationVersion).filter(_.nonEmpty).getOrElse(
          Task.fail("uika-cli version is unknown; pass --cliVersion <version>")
        )
      case v => v
    }
    val workspace = Task.ctx().workspace
    val log: java.util.function.Consumer[String] = line => Task.log.info(line)
    // The CLI ZIP goes through a build module's own resolver, so custom `repositories`,
    // mirrors and credentials are the build's. Any module will do: repositories are declared
    // on a shared trait in practice. Failing rather than falling back to this ExternalModule's
    // own resolver keeps that promise -- a `defaultResolver()` call here would be lifted into
    // an unconditional task edge by the command macro and evaluated even on the Some branch.
    val modules = javaModules(ev)
    val resolver = modules.headOption match {
      case Some(m) => ev.execute(Seq(m.defaultResolver)).values.get.head
      case None => Task.fail("uika: no JavaModule found in this build")
    }
    val binary = extractCli(resolver, version, Task.dest)
    // Recordings are converted here, never handed to the CLI: the CLI is JVM-free and must
    // not read binary JFR.
    val classLoadLogs = JfrEvidence.rewrite(
      Option(jfr).filter(_.nonEmpty).map(os.Path(_, workspace).toNIO).toSeq.asJava,
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
    // trait that pins the release commonly does it there.
    val jdk = UikaCli.JdkSource.current()
    val wantedRelease =
      if (jdkRelease >= 0) jdkRelease
      else {
        val optionTasks = modules.map(_.javacOptions) ++ modules.map(_.mandatoryJavacOptions)
        val declared = ev.execute(optionTasks).values.get
          .flatMap(options => Option(UikaCli.declaredRelease(options.asJava)).map(_.intValue))
        if (declared.isEmpty) Runtime.version().feature() else declared.min
      }
    val exit = UikaCli.runUpgradeCheck(
      binary,
      os.Path(before, workspace).toNIO,
      os.Path(after, workspace).toNIO,
      failOn,
      excludeFile.map(os.Path(_, workspace).toNIO).asJava,
      UikaCli.effectiveJdkRelease(wantedRelease, jdk, log),
      jdk,
      classLoadLogs,
      Option(draftExcludeFile).filter(_.nonEmpty).map(os.Path(_, workspace).toNIO).orNull,
      log
    )
    exit match {
      case 0 => ()
      case 1 => Task.fail("uika upgrade-check found broken references (see output above)")
      case n => Task.fail(s"uika upgrade-check failed with exit code $n")
    }
  }

  /**
   * Extracts the platform's uika binary, resolving the ZIP through Mill's coursier setup so
   * mirrors, credentials and the cache are the build's own. `artifactTypes` has to name zip:
   * coursier's default set is jar-shaped and would drop the distribution entirely.
   */
  private def extractCli(
      resolver: CoursierModule.Resolver,
      version: String,
      dest: os.Path
  )(using mill.api.TaskCtx): java.nio.file.Path = {
    val classifier = UikaCli.platformClassifier()
    // Intransitive, as in all three sibling plugins: the distribution is a native binary, and
    // anything the POM ever gains would be downloaded and could win the zip pick below.
    val dep = Dep.parse(
      s"${UikaCli.GROUP}:${UikaCli.ARTIFACT}:$version;classifier=$classifier;type=zip"
    ).exclude("*" -> "*")
    val resolved =
      resolver.classpath(Seq(dep), artifactTypes = Some(Set(coursier.Type("zip")))).map(_.path)
    val zip = resolved.find(_.last.endsWith(".zip")).getOrElse(
      Task.fail(s"uika-cli zip not found among ${resolved.mkString(", ")}")
    )
    UikaCli.extractBinary(zip.toNIO, (dest / s"cli-$version-$classifier").toNIO)
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
  private def moduleDumpTask(m: JavaModule): Task[ClasspathDump.Module] = {
    val depModules = m.recursiveRunModuleDeps
    val depOutputs = Task.traverse(depModules)(_.localRunClasspath)
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
      // for the reason upgradeCheck spells out: Mill compiles with both.
      val declared = Seq(m.javacOptions(), m.mandatoryJavacOptions())
        .flatMap(options => Option(UikaCli.declaredRelease(options.asJava)))
      new ClasspathDump.Module(
        moduleLabel(m),
        classesDirs.map(_.toString).asJava,
        artifacts.asJava,
        if (declared.isEmpty) null else declared.minBy(_.intValue)
      )
    }
  }

  /** `:foo:bar`, the `:path` shape the dump format uses for Gradle and Maven modules too. */
  private def moduleLabel(m: JavaModule): String = ":" + m.moduleSegments.parts.mkString(":")

  lazy val millDiscover: Discover = Discover[this.type]
}
