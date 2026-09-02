ThisBuild / scalaVersion := "2.12.21"

// Normally uikaCliVersion defaults to the plugin's own version; pin it to the stub repo
// below, which publishes 9.9.9 (exit 0), 9.9.8 (exit 1, i.e. violations found) and 9.9.7
// (exit 2, i.e. the CLI could not run at all).
ThisBuild / uikaCliVersion := "9.9.9"

// Declarative config in build.sbt (not the default "any"): must reach the CLI as --fail-on.
ThisBuild / uikaFailOn := "reachable"

// Declarative config in build.sbt: must reach the CLI as repeated --exclude-file flags.
ThisBuild / uikaExcludeFiles := Seq(baseDirectory.value / "uika-exclude.toml")

// Declarative config in build.sbt (pinned below the build JVM so no clamping applies):
// must reach the CLI as --jdk-release.
ThisBuild / uikaJdkRelease := 11

// Two subprojects compiling for different releases, spelled the two ways that matter. One
// flag serves a run that checks every module, so the LOWEST must reach the CLI. `older` uses
// `Compile / javacOptions`, which sbt delegates Compile -> Zero and never the reverse, so a
// project-scoped read alone cannot see it; `newer` states its target in scalacOptions, which
// is where a Scala module with no Java sources puts it. 12 rather than 11 so the assertion
// cannot pass on the value the pinned override above already wrote.
lazy val older = project.settings(Compile / javacOptions ++= Seq("--release=12"))
lazy val newer = project.settings(scalacOptions ++= Seq("-release", "17"))

lazy val checkJdkReleaseDerived = taskKey[Unit]("Asserts the lowest subproject release reached the CLI")

checkJdkReleaseDerived := {
  val args = IO.read(baseDirectory.value / "before.json.args")
  if (!args.contains("--jdk-release 12"))
    sys.error(s"expected the lowest subproject release (12), not 17 or the build JVM: $args")
}

resolvers += "uika-stub" at (baseDirectory.value / "repo").toURI.toString

lazy val checkCliErrorIsNotAFinding = taskKey[Unit]("Asserts exit 2 fails as a CLI error")

// `-> uikaUpgradeCheck` would only prove the task failed, and exit 1 already does that.
// What has to hold is that the two failures do not read alike, so the task is run through
// .result and the exception message inspected: an unreadable dump reported as "broken
// references" sends the reader looking for a break that was never found.
checkCliErrorIsNotAFinding := {
  (uikaUpgradeCheck.toTask(" before.json after.json")).result.value match {
    case Value(_) => sys.error("exit 2 from the CLI did not fail uikaUpgradeCheck")
    case Inc(incomplete) =>
      val messages = Incomplete.allExceptions(incomplete).map(_.getMessage).mkString("; ")
      if (!messages.contains("failed with exit code 2"))
        sys.error(s"exit 2 was not reported as a CLI error: $messages")
      if (messages.contains("found broken references"))
        sys.error(s"exit 2 was misreported as findings: $messages")
  }
}

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
  // Exit 2 is the CLI could not RUN, not a finding, and the two failures must not read
  // alike: calling an unreadable dump "broken references" sends the reader looking for a
  // break that was never found.
  publish("9.9.7", "#!/bin/sh\necho \"error: cannot open before.json\"\nexit 2\n")
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

lazy val checkJdkReleasePassed = taskKey[Unit]("Asserts the uikaJdkRelease setting reached the CLI as --jdk-release")

// The build.sbt setting uikaJdkRelease := 11 must be forwarded to the CLI invocation.
checkJdkReleasePassed := {
  val args = IO.read(baseDirectory.value / "before.json.args")
  if (!args.contains("--jdk-release 11"))
    sys.error(s"uikaJdkRelease setting was not forwarded to the CLI: $args")
}

lazy val checkRootScopedSettingsPassed = taskKey[Unit]("Asserts root-project-scoped uikaFailOn and uikaExcludeFiles reached the CLI")

// The documented shell form `set uikaFailOn := ...` scopes to the root project, not
// ThisBuild, and the ThisBuild values above stay set underneath. So these assertions can
// only pass if the check reads the keys through LocalRootProject, where root-project scope
// wins over the ThisBuild defaults by delegation.
checkRootScopedSettingsPassed := {
  val args = IO.read(baseDirectory.value / "before.json.args")
  if (!args.contains("--fail-on never"))
    sys.error(s"a root-project-scoped uikaFailOn was not forwarded to the CLI: $args")
  val expected = (baseDirectory.value / "uika-exclude2.toml").getAbsolutePath
  if (!args.contains(s"--exclude-file $expected"))
    sys.error(s"a root-project-scoped uikaExcludeFiles was not forwarded to the CLI: $args")
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

// Declarative config in build.sbt: the directory must reach the CLI as --class-load-log,
// the draft file as --draft-exclude-file, and forked Test JVMs must get the
// StartFlightRecording flag pointing into the same directory.
ThisBuild / uikaJfr := Some(baseDirectory.value / "load-logs")
ThisBuild / uikaDraftExcludeFile := Some(baseDirectory.value / "uika-draft.toml")

lazy val checkClassLoadLogPassed = taskKey[Unit]("Asserts uikaJfr and uikaDraftExcludeFile reached the CLI")

checkClassLoadLogPassed := {
  val args = IO.read(baseDirectory.value / "before.json.args")
  val dir = (baseDirectory.value / "load-logs").getAbsolutePath
  if (!args.contains(s"--class-load-log $dir"))
    sys.error(s"uikaJfr setting was not forwarded to the CLI: $args")
  val draft = (baseDirectory.value / "uika-draft.toml").getAbsolutePath
  if (!args.contains(s"--draft-exclude-file $draft"))
    sys.error(s"uikaDraftExcludeFile setting was not forwarded to the CLI: $args")
}

lazy val checkTestJavaOptionsInjected = taskKey[Unit]("Asserts uikaJfr injected the StartFlightRecording flag into Test/javaOptions")

// The flag only reaches tests with Test/fork := true, but the injected option must be
// there either way; JFR generates pid-unique file names for the directory value. contains,
// not endsWith: the core helper quotes the value when the path carries a comma (the
// StartFlightRecording option delimiter), so the option can end with a quote.
checkTestJavaOptionsInjected := {
  val opts = (Test / javaOptions).value
  val dir = (baseDirectory.value / "load-logs").getAbsolutePath
  if (!opts.exists(o => o.startsWith("-XX:StartFlightRecording:jdk.ClassLoad#enabled=true") && o.contains(dir)))
    sys.error(s"uikaJfr did not inject Test/javaOptions: $opts")
}

lazy val checkSubprojectTestJavaOptionsInjected = taskKey[Unit]("Asserts a root-project-scoped uikaJfr reaches subproject test JVMs")

// A bare `uikaJfr := Some(...)` in build.sbt is root-project-scoped, and its directory has
// to reach EVERY project's forked tests, not only the root's. The test script clears the
// ThisBuild value and sets a root-scoped one first, so this can only pass when the
// injection falls back to LocalRootProject.
checkSubprojectTestJavaOptionsInjected := {
  val opts = (older / Test / javaOptions).value
  val dir = (baseDirectory.value / "load-logs-root").getAbsolutePath
  if (!opts.exists(o => o.startsWith("-XX:StartFlightRecording:jdk.ClassLoad#enabled=true") && o.contains(dir)))
    sys.error(s"a root-project-scoped uikaJfr did not reach the subproject's Test/javaOptions: $opts")
}

lazy val prepareJfr = taskKey[Unit]("Records a real JFR recording with jdk.ClassLoad into load-logs/rec.jfr")

// A REAL recording inside the log directory: the task must convert it (JfrEvidence)
// instead of handing binary JFR to the JVM-free CLI. Whatever classes load during the
// window (JFR internals at least) give it content; checkJfrConverted asserts plumbing,
// not specific classes.
prepareJfr := {
  val rec = new jdk.jfr.Recording()
  rec.enable("jdk.ClassLoad").withStackTrace().withoutThreshold()
  rec.start()
  Class.forName("java.util.zip.Adler32", false, getClass.getClassLoader)
  rec.stop()
  rec.dump((baseDirectory.value / "load-logs" / "rec.jfr").toPath)
  rec.close()
}

lazy val checkJfrConverted = taskKey[Unit]("Asserts the recording in the log directory reached the CLI as converted text, never raw")

checkJfrConverted := {
  val args = IO.read(baseDirectory.value / "before.json.args")
  if (!args.contains("jfr-class-load"))
    sys.error(s"the recording was not converted for the CLI: $args")
  if (args.contains("rec.jfr"))
    sys.error(s"the raw recording reached the CLI: $args")
}
