def log = new File(basedir, "build.log")
assert log.isFile() : "invoker build log not found: $log"

// Exit 1 must surface as a MojoFailureException. Maven renders both mojo exception types
// identically ("Failed to execute goal ... : <message>"), so the message alone cannot tell
// them apart -- the [Help N] link below is the only place the log names the type.
assert log.text.contains("uika upgrade-check found broken references") :
    "exit 1 did not fail the build with the gate message:\n${log.text}"
assert log.text.contains("BUILD FAILURE") :
    "exit 1 did not fail the build:\n${log.text}"
// Hence asserting the type, not just the wording: a MojoExecutionException carrying the
// gate's message would otherwise pass.
assert log.text.contains("MojoFailureException") :
    "exit 1 was not a MojoFailureException:\n${log.text}"

// This invocation asks for nothing beyond the dumps, so the knobs that default off must
// send no flag at all. Per-module checking is the default and the expensive one; sending
// --merged-classpath unasked would quietly change what is checked.
def args = new File(basedir, "before.json.args")
assert args.isFile() : "stub did not record its arguments: $args"
assert !args.text.contains("--merged-classpath") :
    "--merged-classpath was sent without being asked for: ${args.text}"

// The report has to reach the user through the mojo logger even on the failing path,
// otherwise the build fails without saying what broke.
assert log.text.contains("[INFO] uika-stub: 1 broken reference") :
    "CLI output did not go through the mojo logger on the failing path:\n${log.text}"
