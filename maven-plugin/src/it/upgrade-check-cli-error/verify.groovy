def log = new File(basedir, "build.log")
assert log.isFile() : "invoker build log not found: $log"

// Any non-zero code other than 1 is a MojoExecutionException carrying the code, so a user
// reading the log can tell "the CLI found breaks" from "the CLI could not run". Sharing one
// message for both would make a usage error look like a linkage finding.
assert log.text.contains("uika upgrade-check failed with exit code 2") :
    "exit 2 did not report the exit code:\n${log.text}"
assert !log.text.contains("found broken references") :
    "exit 2 was reported as a linkage finding:\n${log.text}"
assert log.text.contains("BUILD FAILURE") :
    "exit 2 did not fail the build:\n${log.text}"
// The help link is the only place the build log names the type. The split is what tells a
// reader whether the gate found something or the CLI never ran, so it is worth pinning.
assert log.text.contains("MojoExecutionException") :
    "exit 2 was not a MojoExecutionException:\n${log.text}"
