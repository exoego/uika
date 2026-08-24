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
object Uika extends ExternalModule with CoursierModule {

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
        DumpFormat.buildJvmRelease()
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
   *                   layer and a negative value means "the build JVM's own release", clamped by
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
  ) = Task.Command(exclusive = true) {
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
    // on a shared trait in practice, and the fallback only applies to a build with no
    // JavaModule at all, which has nothing to check either.
    val resolver = javaModules(ev).headOption match {
      case Some(m) => ev.execute(Seq(m.defaultResolver)).values.get.head
      case None => defaultResolver()
    }
    val binary = extractCli(resolver, version, Task.dest)
    // Recordings are converted here, never handed to the CLI: the CLI is JVM-free and must
    // not read binary JFR.
    val classLoadLogs = JfrEvidence.rewrite(
      Option(jfr).filter(_.nonEmpty).map(os.Path(_, workspace).toNIO).toSeq.asJava,
      (Task.dest / JfrEvidence.WORK_DIR_NAME).toNIO,
      log
    )
    val wantedRelease = if (jdkRelease < 0) Runtime.version().feature() else jdkRelease
    val exit = UikaCli.runUpgradeCheck(
      binary,
      os.Path(before, workspace).toNIO,
      os.Path(after, workspace).toNIO,
      failOn,
      excludeFile.map(os.Path(_, workspace).toNIO).asJava,
      UikaCli.effectiveJdkRelease(wantedRelease, log),
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
    val dep = Dep.parse(
      s"${UikaCli.GROUP}:${UikaCli.ARTIFACT}:$version;classifier=$classifier;type=zip"
    )
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
   * `Task.traverse` rather than calling `dep.localClasspath()` in the body: the dependency list
   * is only known at runtime, and Mill's task macro can only lift statically known task calls
   * into edges.
   */
  // withConfiguration is deprecated in coursier 2.1.25, but JavaModule.resolvedRunMvnDeps
  // still builds the runtime dependency exactly this way. Following it verbatim is the point:
  // the coordinates have to describe the same resolution the module actually runs on.
  @nowarn("cat=deprecation")
  private def moduleDumpTask(m: JavaModule): Task[ClasspathDump.Module] = {
    val depModules = m.recursiveRunModuleDeps
    val depLocalClasspaths = Task.traverse(depModules)(_.localClasspath)
    Task.Anon {
      val classesDirs = m.localRunClasspath().map(_.path).filter(os.exists)

      // Coordinates come from the resolution, never from file paths. Mill models module deps as
      // synthetic coursier projects with no publications, so this yields external artifacts only
      // and the internal ones are attributed from `depLocalClasspaths` below.
      val resolved = m.millResolver().fetchArtifacts(Seq(
        BoundDep(
          m.coursierDependencyTask().withConfiguration(cs.Configuration.runtime),
          force = false
        )
      ))
      val coordinates = resolved.fullDetailedArtifacts0.collect {
        case (dep, _, _, Some(file)) =>
          os.Path(file) -> (
            dep.module.organization.value,
            dep.module.name.value,
            dep.versionConstraint.asString
          )
      }.toMap

      val projectOf = depModules.zip(depLocalClasspaths()).flatMap { case (dep, entries) =>
        entries.map(_.path -> moduleLabel(dep))
      }.toMap

      // localClasspath is a superset of localRunClasspath, so nothing of the module's own
      // output can leak into the artifact list and be counted twice.
      // `os.exists` because a module's localClasspath names its resource directories whether or
      // not they were ever created, and an entry pointing at nothing is noise in every report.
      val own = m.localClasspath().map(_.path).toSet
      val entries = m.runClasspath().map(_.path).distinct.filterNot(own).filter(os.exists)
      val artifacts = entries.map { path =>
        val (group, name, version) = coordinates.getOrElse(path, (null, null, null))
        new ClasspathDump.Artifact(group, name, version, path.toString, projectOf.get(path).orNull)
      }

      new ClasspathDump.Module(
        moduleLabel(m),
        classesDirs.map(_.toString).asJava,
        artifacts.asJava
      )
    }
  }

  /** `:foo:bar`, the `:path` shape the dump format uses for Gradle and Maven modules too. */
  private def moduleLabel(m: JavaModule): String = ":" + m.moduleSegments.parts.mkString(":")

  lazy val millDiscover: Discover = Discover[this.type]
}
