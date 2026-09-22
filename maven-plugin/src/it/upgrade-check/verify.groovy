def marker = new File(basedir, "before.json.marker")
assert marker.isFile() : "stub uika-cli jar did not run: $marker"

// The jar must be told it is the child, or it starts a second JVM for its flags and the one
// the mojo started idles for the whole check.
assert new File(basedir, "before.json.child").text.trim() == "true" :
    "the CLI jar was not started with -Duika.child=true"

// The <failOn>reachable</failOn> in the POM must reach the CLI as --fail-on reachable.
def args = new File(basedir, "before.json.args")
assert args.isFile() : "stub did not record its arguments: $args"
assert args.text.contains("--fail-on reachable") :
    "POM <configuration><failOn> was not forwarded to the CLI: ${args.text}"

assert args.text.contains("--jdk-release 11") :
    "POM <configuration><jdkRelease> was not forwarded to the CLI: ${args.text}"

// --jdk-release is sent, so UIKA_JDK must name the JDK the release was clamped against.
// For this mojo that is the JVM running Maven, which is also the JVM the jar runs on.
def jdk = new File(basedir, "before.json.env").text.trim()
assert jdk : "UIKA_JDK was not exported alongside --jdk-release"
assert new File(jdk).toPath().toRealPath().toString() == new File(basedir, "before.json.home").text.trim() :
    "UIKA_JDK ($jdk) does not name the JVM the jar ran on"

// -Duika.excludeFiles is a real CLI property here (test.properties), comma-separated by
// plexus and basedir-aligned by its FileConverter, with the empty entry dropped.
for (name in ["cli-exclude.toml", "second-exclude.toml"]) {
    def fromProperty = new File(basedir, name)
    assert args.text.contains("--exclude-file ${fromProperty.absolutePath}") :
        "-Duika.excludeFiles entry ${name} was not forwarded to the CLI: ${args.text}"
}
assert args.text.count("--exclude-file") == 2 :
    "expected exactly two --exclude-file flags, the empty entry dropped: ${args.text}"

// Per-module checking is the default, so this one is only sent because the invocation asks
// for it. A large reactor pays one scan per module, which is what the knob is for.
assert args.text.contains("--merged-classpath") :
    "-Duika.mergedClasspath was not forwarded to the CLI: ${args.text}"

// The recording inside the log directory must reach the CLI as converted text, never raw.
assert args.text.contains("jfr-class-load") :
    "the recording was not converted for the CLI: ${args.text}"
assert !args.text.contains("rec.jfr") :
    "the raw recording reached the CLI: ${args.text}"
