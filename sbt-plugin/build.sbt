ThisBuild / organization := "net.exoego.uika"
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "2.12.20"

lazy val root = (project in file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-uika",
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
    publishTo := Some("GitHub Packages" at "https://maven.pkg.github.com/exoego/uika"),
    credentials += Credentials(
      "GitHub Package Registry",
      "maven.pkg.github.com",
      sys.env.getOrElse("GITHUB_ACTOR", ""),
      sys.env.getOrElse("GITHUB_TOKEN", "")
    )
  )
