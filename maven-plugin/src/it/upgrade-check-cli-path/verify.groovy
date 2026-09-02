def log = new File(basedir, "build.log")
assert log.isFile() : "invoker build log not found: $log"
assert log.text.contains("BUILD SUCCESS") : "the build did not succeed:\n${log.text}"

// The stub ran, so the override reached ProcessBuilder rather than the resolver. Its argv
// lands next to the first argument it was given, which is "upgrade-check".
def args = new File(basedir, "upgrade-check.args")
assert args.isFile() : "UIKA_CLI_PATH was not run; the goal resolved a binary instead:\n${log.text}"
assert args.readLines().contains("--before") : "the stub was run with the wrong argv: ${args.text}"

// And its output still went through the mojo logger, the reason no integration uses
// inheritIO.
assert log.text.contains("[INFO] uika-stub: dependency changes: 0") :
    "CLI output did not go through the mojo logger:\n${log.text}"
