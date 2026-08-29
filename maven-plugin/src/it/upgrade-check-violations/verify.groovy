def log = new File(basedir, "build.log")
assert log.isFile() : "invoker build log not found: $log"

// Exit 1 must surface as a MojoFailureException, which Maven prints as BUILD FAILURE with
// the mojo's own message. A MojoExecutionException would read "Internal error" instead and
// would be the wrong failure type for a gate that found what it was told to look for.
assert log.text.contains("uika upgrade-check found broken references") :
    "exit 1 did not fail the build with the gate message:\n${log.text}"
assert log.text.contains("BUILD FAILURE") :
    "exit 1 did not fail the build:\n${log.text}"
// Maven names the exception type in its help link, which is the only place the build log
// distinguishes the two. Asserting the message alone would still pass if the mojo threw the
// wrong type with the right text.
assert log.text.contains("MojoFailureException") :
    "exit 1 was not a MojoFailureException:\n${log.text}"

// The report has to reach the user through the mojo logger even on the failing path,
// otherwise the build fails without saying what broke.
assert log.text.contains("[INFO] uika-stub: 1 broken reference") :
    "CLI output did not go through the mojo logger on the failing path:\n${log.text}"
