def marker = new File(basedir, "before.json.marker")
assert marker.isFile() : "stub uika binary did not run: $marker"

def log = new File(basedir, "build.log")
assert log.isFile() : "invoker build log not found: $log"
// The [INFO] prefix proves the line went through the mojo's logger. Inherited stdio also
// lands in build.log here (the invoker pipes the forked mvn), but unprefixed, and it would
// be lost entirely under mvnd.
assert log.text.contains("[INFO] uika-stub: dependency changes: 0") :
    "CLI output did not go through the mojo logger"
