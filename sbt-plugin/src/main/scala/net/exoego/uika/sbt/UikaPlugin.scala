package net.exoego.uika.sbt

import net.exoego.uika.plugin.core.ClasspathDump
import net.exoego.uika.plugin.core.DumpFormat
import net.exoego.uika.plugin.core.JfrEvidence
import net.exoego.uika.plugin.core.UikaCli
import sbt._
import sbt.Keys._

import scala.jdk.CollectionConverters._

object UikaPlugin extends AutoPlugin {
  override def trigger: PluginTrigger = allRequirements

  object autoImport {
    val uikaDumpClasspath = taskKey[File]("Writes the resolved classpath as uika JSON")
    val uikaModuleClasspath = taskKey[ClasspathDump.Module]("Builds this module's uika classpath model")
    val uikaOutput = settingKey[File]("Output file for uikaDumpClasspath")
    val uikaCliVersion = settingKey[String]("uika-cli version for uikaUpgradeCheck (defaults to the plugin's own version)")
    val uikaFailOn = settingKey[String]("When uikaUpgradeCheck fails the build: never, reachable, or any (default)")
    val uikaExcludeFiles = settingKey[Seq[File]]("TOML files of known false positives to suppress, passed as repeated --exclude-file")
    val uikaJdkRelease = settingKey[Int]("JDK API release for --jdk-release, and the release the dump records the application as running on (0 disables the layer but leaves the dump derived; a negative value, the default, derives the lowest release any subproject compiles for from its javacOptions and scalacOptions, else the build JVM's, clamped to what its ct.sym serves)")
    val uikaJfr = settingKey[Option[File]]("JFR class-load evidence location: a directory makes forked Test JVMs record jdk.ClassLoad there (pid-unique file names; needs Test/fork := true and a JDK 17+ test JVM) and uikaUpgradeCheck converts and reads it back; a .jfr file is consumed only. Set it bare in build.sbt or at ThisBuild; either reaches every project's forked tests and the check. A per-subproject value overrides where that project's tests record, and never reaches the check")
    val uikaDraftExcludeFile = settingKey[Option[File]]("File for uikaUpgradeCheck to write draft exclude rules to (--draft-exclude-file); only meaningful with uikaJfr. Set it bare in build.sbt or at ThisBuild")
    val uikaUpgradeCheck = inputKey[Unit]("Runs uika upgrade-check: uikaUpgradeCheck <before.json> <after.json>")
  }

  import autoImport._

  override def buildSettings: Seq[Setting[_]] = Seq(
    uikaOutput := baseDirectory.value / "target" / "uika" / "classpath.json",
    // Implementation-Version is written by build.sbt packageOptions; empty when the plugin
    // classes are loaded outside a packaged jar. Checked at task time, not here: a setting
    // default must not throw during project load.
    uikaCliVersion := Option(getClass.getPackage.getImplementationVersion).getOrElse(""),
    uikaFailOn := "any",
    uikaExcludeFiles := Seq.empty,
    // The build runs on a JVM, so the JDK API layer defaults ON (the bare CLI keeps it
    // opt-in), and UikaCli.effectiveJdkRelease clamps to what the build JVM's ct.sym serves.
    // Negative means "derive", and that happens in the task below because javacOptions is a
    // TASK and a setting cannot depend on one.
    uikaJdkRelease := -1,
    uikaJfr := None,
    uikaDraftExcludeFile := None,
    // One check per BUILD, not per project. Every value the task reads is ThisBuild- or
    // root-scoped and the dumps already cover the whole build, so aggregating it just spawns
    // N identical CLI runs in parallel, racing on the shared retrieve directory and on the
    // JFR work directory whose stale-conversion sweep deletes a sibling's fresh output.
    uikaUpgradeCheck / aggregate := false,
    uikaUpgradeCheck := {
      val args = Def.spaceDelimited("<before.json> <after.json>").parsed
      if (args.length != 2) sys.error("usage: uikaUpgradeCheck <before.json> <after.json>")
      // LocalRootProject scope on every user knob this task reads, for the reason the
      // uikaJdkRelease read below spells out: read bare, a buildSettings task resolves
      // ThisBuild only, and the documented `set uikaFailOn := ...` shell form scopes to the
      // root project, which was silently ignored. Root-project scope still delegates to
      // ThisBuild, so both spellings work.
      val version = (LocalRootProject / uikaCliVersion).value match {
        case "" => sys.error("""uika-cli version is unknown; set uikaCliVersion := "<version>"""")
        case v  => v
      }
      val classifier = UikaCli.platformClassifier()
      val log = streams.value.log
      val lm = (LocalRootProject / dependencyResolution).value
      val module = ModuleID(UikaCli.GROUP, UikaCli.ARTIFACT, version)
        .intransitive()
        .artifacts(Artifact(UikaCli.ARTIFACT, "zip", "zip", classifier))
      val uikaDir = (LocalRootProject / target).value / "uika"
      val files = lm
        .retrieve(lm.wrapDependencyInModule(module), uikaDir / "cli-retrieve", log)
        .fold(warning => throw warning.resolveException, identity)
      val zip = files
        .find(_.getName.endsWith(".zip"))
        .getOrElse(sys.error(s"uika-cli zip not found among ${files.mkString(", ")}"))
      val binary = UikaCli.extractBinary(zip.toPath, (uikaDir / s"cli-$version-$classifier").toPath)
      val excludeFiles = (LocalRootProject / uikaExcludeFiles).value.map(_.toPath).asJava
      val jdk = UikaCli.JdkSource.current()
      // The LOWEST release any subproject compiles for, because one flag serves a run that
      // checks every module. Under-claiming only costs Unknowns, while over-claiming makes a
      // member the runtime lacks resolve cleanly and loses the finding with nothing to show.
      // A build declaring nothing falls back to the JVM, the only evidence left. The dump
      // keeps each module's own release next to it (uikaModuleClasspath below); the flag
      // stays one value because the layer it switches on is process-wide.
      //
      // Four reads, because missing any one of them falls through to the build JVM, the
      // over-claiming direction this default exists to avoid. ScopeFilter leaves the
      // configuration axis at Zero and sbt delegates Compile to Zero and never the reverse,
      // so the idiomatic `Compile / javacOptions` is invisible to the bare filter alone. A
      // Scala module has no javacOptions at all and states its target in scalacOptions.
      // Above the branch that uses it, since sbt's task macro lifts every `.value` into the
      // dependency graph and putting them inside the branch would only hide that they are
      // evaluated even when uikaJdkRelease is explicit. Each filter is spelled out at its
      // call because a local `val` holding one cannot be lifted ("Could not find proxy for
      // val ...").
      val declared = (javacOptions.all(ScopeFilter(inAnyProject)).value
        ++ javacOptions.all(ScopeFilter(inAnyProject, inConfigurations(Compile))).value
        ++ scalacOptions.all(ScopeFilter(inAnyProject)).value
        ++ scalacOptions.all(ScopeFilter(inAnyProject, inConfigurations(Compile))).value)
        .flatMap(options => Option(UikaCli.declaredRelease(options.asJava)).map(_.intValue))
      // LocalRootProject scope for the same reason uikaJfr uses it below: read bare, a
      // buildSettings task sees ThisBuild only, and a root-scoped override in build.sbt would
      // be silently replaced by the derived value.
      val wantedRelease = (LocalRootProject / uikaJdkRelease).value match {
        case explicit if explicit >= 0 => explicit
        case _ if declared.isEmpty => java.lang.Runtime.version().feature()
        case _ => declared.min
      }
      val jdkRelease = UikaCli.effectiveJdkRelease(wantedRelease, jdk, (line: String) => log.info(line))
      // LocalRootProject scope, not bare: this task lives in buildSettings, and a bare
      // read resolves ThisBuild only — the README's plain `uikaJfr := Some(...)` in
      // build.sbt is root-project-scoped, and missing it here silently ran the check
      // without --class-load-log while forked tests kept recording. Root-project scope
      // still delegates to ThisBuild, so both spellings work. Absolutized like the
      // javaOptions side so both halves and the CLI agree on one directory. Recordings
      // (a .jfr value, or recordings inside the directory) are converted to the CLI's
      // text format here: the CLI is JVM-free and never reads binary JFR.
      val classLoadLogs = JfrEvidence.rewrite(
        (LocalRootProject / uikaJfr).value.map(_.getAbsoluteFile.toPath).toSeq.asJava,
        (uikaDir / JfrEvidence.WORK_DIR_NAME).toPath,
        (line: String) => log.info(line)
      )
      val draftExcludeFile =
        (LocalRootProject / uikaDraftExcludeFile).value.map(_.getAbsoluteFile.toPath).orNull
      UikaCli.runUpgradeCheck(binary, file(args.head).toPath, file(args(1)).toPath, (LocalRootProject / uikaFailOn).value, excludeFiles, jdkRelease, jdk, classLoadLogs, draftExcludeFile, (line: String) => log.info(line)) match {
        case 0 => ()
        case 1 => sys.error("uika upgrade-check found broken references (see output above)")
        case n => sys.error(s"uika upgrade-check failed with exit code $n")
      }
    }
  )

  override def projectSettings: Seq[Setting[_]] = Seq(
    // Forked Test JVMs record jdk.ClassLoad into uikaJfr via the shared core helper (JFR
    // generates pid-unique file names for a directory-valued filename, so parallel forks
    // never clobber each other). javaOptions only reaches the tests with
    // Test/fork := true; in-process tests run in sbt's own JVM, where no flag can be
    // added after startup. The directory is absolutized: a relative value would
    // otherwise split three ways (createDirectory and the CLI child resolve against
    // sbt's launch directory, each forked test JVM against its own subproject's
    // baseDirectory working dir), scattering recordings the check then never reads — or
    // aborting forks whose per-subproject directory does not exist.
    Test / javaOptions ++= {
      val log = sLog.value
      // The bare read resolves this subproject and ThisBuild only, while a bare
      // `uikaJfr := Some(...)` in build.sbt is root-project-scoped -- exactly the value the
      // check side reads through LocalRootProject. Without the fallback that spelling
      // recorded only the root project's tests while the check still passed
      // --class-load-log, quietly feeding it near-empty evidence. The per-subproject read
      // stays first so a subproject can still override where its own tests record.
      uikaJfr.value.orElse((LocalRootProject / uikaJfr).value) match {
        // A .jfr value is consumption-only: test JVMs cannot record into an existing
        // recording, so injection is skipped for it. The truth table (a directory named
        // logs.jfr is still a directory and keeps injection) is shared with the Gradle
        // plugin via core so the two cannot drift.
        case Some(dir) if !JfrEvidence.valueNamesRecording(dir.toPath) =>
          val abs = dir.getAbsoluteFile
          IO.createDirectory(abs)
          Seq(UikaCli.jfrClassLoadJvmArg(abs.toPath))
        case Some(recording) =>
          // Said out loud because the skip is otherwise symptomless: a collect run
          // against a .jfr value records nothing and uploads an empty artifact.
          log.info(s"uika: uikaJfr names a .jfr recording (consumption-only); forked test JVMs will not record: $recording")
          Seq.empty
        case None => Seq.empty
      }
    },
    uikaModuleClasspath := {
      val modulePath = thisProject.value.id
      val ownProducts = (Compile / products).value
      val classDirs = ownProducts
        .filter(_.exists)
        .map(_.getAbsolutePath)
      // Inter-module dependencies (classes dirs, or jars with exportJars := true). They are
      // not in update.value, so without these entries a module's classpath would be missing
      // its sibling modules and per-module checking could not resolve inter-module
      // references. Evaluating the task also compiles those siblings, so the paths exist.
      // internalDependencyClasspath also returns this project's OWN Compile products
      // (Runtime extends Compile); classesDirs already carries those, so keep siblings only.
      val ownProductSet = ownProducts.map(_.getAbsoluteFile).toSet
      val internalArtifacts = (Runtime / internalDependencyClasspath).value
        .map(_.data.getAbsoluteFile)
        .distinct
        .filterNot(ownProductSet)
        .map(file => new ClasspathDump.Artifact(null, null, null, file.getAbsolutePath))
      val runtimeEntries = (Runtime / dependencyClasspath).value
        .map(_.data.getAbsoluteFile)
        .distinct
      val runtimeFiles = runtimeEntries.toSet
      val artifacts = update.value.configurations
        .flatMap(_.modules)
        .flatMap { module =>
          module.artifacts.collect {
            case (_, file) if runtimeFiles(file.getAbsoluteFile) =>
              new ClasspathDump.Artifact(
                module.module.organization,
                module.module.name,
                module.module.revision,
                file.getAbsolutePath
              )
          }
        }
      // Unmanaged jars (lib/*.jar, unmanagedClasspath additions) have no update.value
      // entry, so without a fallback they vanish from the dump although they are on the
      // classpath the module runs on. Coordinate-less like the internal entries: nothing
      // the version diff compares, but a scan target all the same. Mill guards against
      // the same drop by subtracting only what the module itself produces.
      val attributed = ownProductSet ++
        (internalArtifacts ++ artifacts).map(a => file(a.file()).getAbsoluteFile)
      val unmanagedArtifacts = runtimeEntries
        .filterNot(attributed)
        .map(f => new ClasspathDump.Artifact(null, null, null, f.getAbsolutePath))
      // What THIS module compiles for, in the dump next to it, so upgrade-check can scope a
      // JDK move to the modules that made it. Both axes and both compilers, for the reason
      // uikaUpgradeCheck spells out above: sbt delegates Compile to Zero and never the
      // reverse, and a Scala module states its target in scalacOptions alone.
      val declared = Seq(
        javacOptions.value,
        (Compile / javacOptions).value,
        scalacOptions.value,
        (Compile / scalacOptions).value
      ).flatMap(options => Option(UikaCli.declaredRelease(options.asJava)))
      // uikaJdkRelease replaces every module's own value when it is set, because it is a
      // statement about the whole build. LocalRootProject for the reason uikaUpgradeCheck
      // gives: read bare from here it would resolve this subproject, not the root override.
      val declaredOverride =
        UikaCli.overrideRelease(Int.box((LocalRootProject / uikaJdkRelease).value))
      new ClasspathDump.Module(
        ":" + modulePath,
        classDirs.asJava,
        (internalArtifacts ++ artifacts ++ unmanagedArtifacts).asJava,
        if (declaredOverride != null) declaredOverride
        else if (declared.isEmpty) null
        else declared.minBy(_.intValue)
      )
    },
    uikaDumpClasspath := {
      val modules = uikaModuleClasspath.all(ScopeFilter(inAnyProject)).value
      val out = uikaOutput.value
      IO.createDirectory(out.getParentFile)
      IO.write(
        out,
        DumpFormat.writeV2(
          modules.asJava,
          List(baseDirectory.value.getAbsolutePath).asJava,
          DumpFormat.dumpRelease(modules.asJava)
        )
      )
      streams.value.log.info(s"uika classpath dump: $out")
      out
    }
  )
}
