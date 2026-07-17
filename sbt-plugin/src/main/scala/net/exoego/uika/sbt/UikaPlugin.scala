package net.exoego.uika.sbt

import net.exoego.uika.plugin.core.ClasspathDump
import net.exoego.uika.plugin.core.DumpFormat
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
    uikaUpgradeCheck := {
      val args = Def.spaceDelimited("<before.json> <after.json>").parsed
      if (args.length != 2) sys.error("usage: uikaUpgradeCheck <before.json> <after.json>")
      val version = uikaCliVersion.value match {
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
      val excludeFiles = uikaExcludeFiles.value.map(_.toPath).asJava
      UikaCli.runUpgradeCheck(binary, file(args.head).toPath, file(args(1)).toPath, uikaFailOn.value, excludeFiles, (line: String) => log.info(line)) match {
        case 0 => ()
        case 1 => sys.error("uika upgrade-check found broken references (see output above)")
        case n => sys.error(s"uika upgrade-check failed with exit code $n")
      }
    }
  )

  override def projectSettings: Seq[Setting[_]] = Seq(
    uikaModuleClasspath := {
      val modulePath = thisProject.value.id
      val classDirs = (Compile / products).value
        .filter(_.exists)
        .map(_.getAbsolutePath)
      val runtimeFiles = (Runtime / dependencyClasspath).value
        .map(_.data.getAbsoluteFile)
        .toSet
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
      new ClasspathDump.Module(":" + modulePath, classDirs.asJava, artifacts.asJava)
    },
    uikaDumpClasspath := {
      val modules = uikaModuleClasspath.all(ScopeFilter(inAnyProject)).value
      val out = uikaOutput.value
      IO.createDirectory(out.getParentFile)
      IO.write(
        out,
        DumpFormat.writeV2(
          modules.asJava,
          List(baseDirectory.value.getAbsolutePath).asJava
        )
      )
      streams.value.log.info(s"uika classpath dump: $out")
      out
    }
  )
}
