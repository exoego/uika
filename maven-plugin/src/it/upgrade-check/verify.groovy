def marker = new File(basedir, "before.json.marker")
assert marker.isFile() : "stub uika binary did not run: $marker"

// The <failOn>reachable</failOn> in the POM must reach the CLI as --fail-on reachable.
def args = new File(basedir, "before.json.args")
assert args.isFile() : "stub did not record its arguments: $args"
assert args.text.contains("--fail-on reachable") :
    "POM <configuration><failOn> was not forwarded to the CLI: ${args.text}"

def excludeFile = new File(basedir, "uika-exclude.toml")
assert args.text.contains("--jdk-release 11") :
    "POM <configuration><jdkRelease> was not forwarded to the CLI: ${args.text}"
assert args.text.contains("--exclude-file ${excludeFile.absolutePath}") :
    "POM <configuration><excludeFiles> was not forwarded to the CLI: ${args.text}"

def log = new File(basedir, "build.log")
assert log.isFile() : "invoker build log not found: $log"
// The [INFO] prefix proves the line went through the mojo's logger. Inherited stdio also
// lands in build.log here (the invoker pipes the forked mvn), but unprefixed, and it would
// be lost entirely under mvnd.
assert log.text.contains("[INFO] uika-stub: dependency changes: 0") :
    "CLI output did not go through the mojo logger"

// -Duika.classLoadLog and -Duika.draftExcludeFile must reach the CLI, resolved against
// the project basedir like the other file properties.
def classLoadDir = new File(basedir, "load-logs")
assert args.text.contains("--class-load-log ${classLoadDir.absolutePath}") :
    "-Duika.classLoadLog was not forwarded to the CLI: ${args.text}"
def draftFile = new File(basedir, "uika-draft.toml")
assert args.text.contains("--draft-exclude-file ${draftFile.absolutePath}") :
    "-Duika.draftExcludeFile was not forwarded to the CLI: ${args.text}"
