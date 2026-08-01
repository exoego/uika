ThisBuild / organization := "net.exoego.uika"
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "2.12.21"
ThisBuild / versionScheme := Some("early-semver")
// Maven Central rejects the legacy sbt-uika-<version>.jar file name; publish
// POM-consistent sbt-uika_2.12_1.0-<version>.jar instead (resolvable by sbt 1.9+).
ThisBuild / sbtPluginPublishLegacyMavenStyle := false

// CodeQL has no Scala extractor, so the compiler is this module's static analysis.
// Warnings are fatal in Compile only. The console and the scripted test builds
// keep their own defaults.
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xlint",
  "-Ywarn-unused:imports,privates,locals"
)

lazy val root = (project in file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-uika",
    Compile / compile / scalacOptions += "-Xfatal-warnings",
    description := "sbt plugin for writing uika resolved classpath dumps and running upgrade checks",
    homepage := Some(url("https://github.com/exoego/uika")),
    licenses := Seq("Apache License 2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
      Developer("exoego", "TATSUNO Yasuhiro", "", url("https://github.com/exoego"))
    ),
    scmInfo := Some(
      ScmInfo(url("https://github.com/exoego/uika"), "scm:git:https://github.com/exoego/uika.git")
    ),
    Compile / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "jvm-plugin-core" / "src" / "main" / "java",
    // uikaUpgradeCheck defaults the CLI version to the plugin's own version, read from here.
    Compile / packageBin / packageOptions += Package.ManifestAttributes("Implementation-Version" -> version.value),
    scriptedLaunchOpts ++= {
      val inherited = Seq("sbt.ivy.home", "sbt.global.base", "sbt.boot.directory").flatMap { name =>
        sys.props.get(name).map(value => s"-D$name=$value")
      }
      inherited :+ s"-Dplugin.version=${version.value}"
    },
    scriptedBufferLog := false,
    // Central requires the doc jar to exist, not to have content. Scaladoc put
    // 1.18 MB on every release, 19% of the whole deployment: 1.9 MB of bundled
    // fonts and jQuery plus a 498 KB index of the entire sbt API, against only
    // 236 KB of uika's own docs. The Maven Central quota is shared across the
    // net.exoego namespace, so publish the jar empty and let readers use the
    // sources jar. See PUBLISHING.md.
    // packageDoc maps whatever sits in the doc output directory, and emptying
    // the sources only stops that directory being regenerated, never cleaned.
    // Without the mappings line a tree that built docs before this change keeps
    // publishing the old jar. Both are needed: sources skips the work, mappings
    // decides the contents.
    Compile / doc / sources := Seq.empty,
    Compile / packageDoc / mappings := Seq.empty,
    publishMavenStyle := true,
    // Local staging directory; JReleaser signs and uploads it to Maven Central.
    publishTo := Some(MavenCache("local-staging", target.value / "staging-deploy"))
  )
