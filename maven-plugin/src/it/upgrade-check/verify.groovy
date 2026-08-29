def marker = new File(basedir, "before.json.marker")
assert marker.isFile() : "stub uika binary did not run: $marker"

// The <failOn>reachable</failOn> in the POM must reach the CLI as --fail-on reachable.
def args = new File(basedir, "before.json.args")
assert args.isFile() : "stub did not record its arguments: $args"
assert args.text.contains("--fail-on reachable") :
    "POM <configuration><failOn> was not forwarded to the CLI: ${args.text}"

assert args.text.contains("--jdk-release 11") :
    "POM <configuration><jdkRelease> was not forwarded to the CLI: ${args.text}"

// -Duika.excludeFiles is a real CLI property here (test.properties), comma-separated by
// plexus and basedir-aligned by its FileConverter, with the empty entry dropped.
for (name in ["cli-exclude.toml", "second-exclude.toml"]) {
    def fromProperty = new File(basedir, name)
    assert args.text.contains("--exclude-file ${fromProperty.absolutePath}") :
        "-Duika.excludeFiles entry ${name} was not forwarded to the CLI: ${args.text}"
}
assert args.text.count("--exclude-file") == 2 :
    "expected exactly two --exclude-file flags, the empty entry dropped: ${args.text}"

// The recording inside the log directory must reach the CLI as converted text, never raw.
assert args.text.contains("jfr-class-load") :
    "the recording was not converted for the CLI: ${args.text}"
assert !args.text.contains("rec.jfr") :
    "the raw recording reached the CLI: ${args.text}"
