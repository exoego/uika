ThisBuild / scalaVersion := "2.12.21"

// No resolver and no uikaCliVersion, unlike the uika/upgrade-check test. That is the
// assertion: nothing here can resolve a uika-cli distribution, so the check can only run
// if UIKA_CLI_PATH short-circuits acquisition before the version is even wanted.

lazy val requireCliPath = taskKey[Unit]("Fails with a pointer when UIKA_CLI_PATH is unset")

// Its own scripted GROUP because the environment is the only way in. sbt's scripted
// framework has no per-test environment hook -- scriptedLaunchOpts carries JVM options
// only, and the forked sbt inherits whatever ran it -- so the variable has to be set for
// the whole invocation, which would defeat the sibling group's resolver test. `make
// sbt-scripted` runs the two groups as two invocations for exactly that reason. A bare
// `sbt scripted` reaches this group without the variable, so say what to run instead of
// failing later with a resolution error that names the wrong problem.
requireCliPath := {
  if (sys.env.get("UIKA_CLI_PATH").forall(_.isEmpty))
    sys.error("this group needs UIKA_CLI_PATH; run `make sbt-scripted`, which sets it")
}

lazy val checkStubRan = taskKey[Unit]("Asserts the check ran the binary UIKA_CLI_PATH names")

checkStubRan := {
  val args = IO.read(baseDirectory.value / "before.json.args")
  // The stub echoes its whole argv. Seeing it at all proves the override beat the
  // resolver; seeing --before proves it was run as the CLI rather than probed.
  if (!args.contains("--before"))
    sys.error(s"the stub was run with the wrong argv: $args")
  // uikaCliVersion is unset here, and the check demands one before it resolves anything.
  // Reaching the stub therefore also pins the ORDER: the override is read ABOVE the
  // version check, which is what the flag is for on an air-gapped build.
}
