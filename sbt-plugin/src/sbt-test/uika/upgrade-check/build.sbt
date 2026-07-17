ThisBuild / scalaVersion := "2.12.21"

// Normally uikaCliVersion defaults to the plugin's own version; pin it to the stub repo
// below, which publishes 9.9.9 (exit 0) and 9.9.8 (exit 1, i.e. violations found).
ThisBuild / uikaCliVersion := "9.9.9"

// Declarative config in build.sbt (not the default "any"): must reach the CLI as --fail-on.
ThisBuild / uikaFailOn := "reachable"

// Declarative config in build.sbt: must reach the CLI as repeated --exclude-file flags.
ThisBuild / uikaExcludeFiles := Seq(baseDirectory.value / "uika-exclude.toml")

resolvers += "uika-stub" at (baseDirectory.value / "repo").toURI.toString

lazy val prepareStubRepo = taskKey[Unit]("Writes stub uika-cli ZIPs into the file-based test repository")

prepareStubRepo := {
  import java.util.zip.{ZipEntry, ZipOutputStream}
  val classifier = net.exoego.uika.plugin.core.UikaCli.platformClassifier()
  def publish(version: String, script: String): Unit = {
    val dir = baseDirectory.value / "repo" / "net" / "exoego" / "uika" / "uika-cli" / version
    IO.createDirectory(dir)
    IO.write(
      dir / s"uika-cli-$version.pom",
      s"""<project><modelVersion>4.0.0</modelVersion><groupId>net.exoego.uika</groupId><artifactId>uika-cli</artifactId><version>$version</version><packaging>pom</packaging></project>"""
    )
    val out = new ZipOutputStream(new java.io.FileOutputStream(dir / s"uika-cli-$version-$classifier.zip"))
    try {
      out.putNextEntry(new ZipEntry(s"uika-$version-$classifier/uika"))
      out.write(script.getBytes("UTF-8"))
      out.closeEntry()
    } finally out.close()
  }
  // The stub leaves a marker next to the --before argument ($3) to prove it ran and records
  // its full argument list ($3.args) so checkFailOnPassed can assert the flags; the echoed
  // line must surface through the task logger (checked by checkCliOutputLogged).
  publish("9.9.9", "#!/bin/sh\necho ran > \"$3.marker\"\necho \"$@\" > \"$3.args\"\necho \"uika-stub: dependency changes: 0\"\nexit 0\n")
  publish("9.9.8", "#!/bin/sh\nexit 1\n")
}

lazy val checkFailOnPassed = taskKey[Unit]("Asserts the uikaFailOn setting reached the CLI as --fail-on")

// The build.sbt setting uikaFailOn := "reachable" must be forwarded to the CLI invocation.
checkFailOnPassed := {
  val args = IO.read(baseDirectory.value / "before.json.args")
  if (!args.contains("--fail-on reachable"))
    sys.error(s"uikaFailOn setting was not forwarded to the CLI: $args")
}

lazy val checkExcludeFilesPassed = taskKey[Unit]("Asserts the uikaExcludeFiles setting reached the CLI as --exclude-file")

// The build.sbt setting uikaExcludeFiles must be forwarded to the CLI invocation.
checkExcludeFilesPassed := {
  val args = IO.read(baseDirectory.value / "before.json.args")
  val expected = (baseDirectory.value / "uika-exclude.toml").getAbsolutePath
  if (!args.contains(s"--exclude-file $expected"))
    sys.error(s"uikaExcludeFiles setting was not forwarded to the CLI: $args")
}

lazy val checkCliOutputLogged = taskKey[Unit]("Asserts the stub CLI's output went through the task logger")

// log.info from uikaUpgradeCheck is persisted to the task's streams file. Inherited stdio
// would bypass the logger entirely (and is lost under an sbt server), so finding the echoed
// line in the streams proves the output took the logger path.
checkCliOutputLogged := {
  val marker = "uika-stub: dependency changes: 0"
  val outs = ((baseDirectory.value / "target") ** "out").get.filter(_.isFile)
  if (!outs.exists(f => IO.read(f).contains(marker)))
    sys.error(s"CLI output did not reach the task logger (searched ${outs.size} stream files)")
}
