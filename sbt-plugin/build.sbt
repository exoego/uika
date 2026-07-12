ThisBuild / organization := "net.exoego.uika"
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "2.12.20"
ThisBuild / versionScheme := Some("early-semver")
// Maven Central rejects the legacy sbt-uika-<version>.jar file name; publish
// POM-consistent sbt-uika_2.12_1.0-<version>.jar instead (resolvable by sbt 1.9+).
ThisBuild / sbtPluginPublishLegacyMavenStyle := false

lazy val root = (project in file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-uika",
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
    publishMavenStyle := true,
    // Local staging directory; JReleaser signs and uploads it to Maven Central.
    publishTo := Some(MavenCache("local-staging", target.value / "staging-deploy"))
  )
