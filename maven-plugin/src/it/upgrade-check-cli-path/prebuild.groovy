// No ZIP and no stub repository, unlike the sibling ITs: this one asserts that
// UIKA_CLI_PATH is honoured, so there must be nothing for the resolver to find.
def os = System.getProperty("os.name").toLowerCase()
if (!os.contains("linux") && !os.contains("mac")) {
    println "unsupported test platform: $os"
    return false
}

def stub = new File(basedir, "uika-stub")
stub.text = '#!/bin/sh\necho "uika-stub: dependency changes: 0"\nprintf \'%s\\n\' "$@" > "$1.args"\nexit 0\n'
// The bit the mojo now insists on. A hand-supplied binary usually loses it on an artifact
// round trip, which is how this variable is meant to be used in CI.
assert stub.setExecutable(true, false) : "could not mark the stub executable"

new File(basedir, "before.json").text = "{}"
new File(basedir, "after.json").text = "{}"
return true
