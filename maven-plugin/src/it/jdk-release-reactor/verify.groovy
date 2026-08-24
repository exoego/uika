def args = new File(basedir, "before.json.args")
assert args.isFile() : "stub did not record its arguments: $args"

// The aggregator declares no release; older targets 11 and newer targets 17. One flag serves
// a run that checks every module, so the lowest wins -- reading only the top-level project
// reported the build JVM instead, which no module compiles against.
assert args.text.contains("--jdk-release 11") :
    "expected the lowest reactor release (11), not 17 or the build JVM: ${args.text}"
