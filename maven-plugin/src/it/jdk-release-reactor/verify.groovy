def args = new File(basedir, "before.json.args")
assert args.isFile() : "stub did not record its arguments: $args"

// The aggregator declares no release; older targets 11 through the property, newer 17 through
// maven-compiler-plugin's own configuration, and the pom-packaged bom declares 8 while
// compiling nothing. One flag serves a run that checks every module, so the lowest of the two
// that ship wins. Reading only the top-level project reported the build JVM instead.
assert args.text.contains("--jdk-release 11") :
    "expected the lowest shipping reactor release (11), not 8, 17 or the build JVM: ${args.text}"

// `overridden` sets plugin-level release 9 and overrides it to 17 in every execution. A
// plugin-level value every execution replaces is never compiled with, so counting it as a
// candidate would report 9 here and gut the layer for the whole reactor.
assert !args.text.contains("--jdk-release 9") :
    "a plugin-level release that every execution overrides was counted: ${args.text}"

// The dump keeps each module's own release. `undeclared` names none, so it records the JVM
// running Maven, which is what javac targets for it. Recording nothing left it on the lowest
// sibling's 11, so a CI JDK move went unseen for it while the siblings' moves were charged to it.
def dump = new groovy.json.JsonSlurper().parse(new File(basedir, "target/uika/classpath.json"))
def releases = dump.modules.collectEntries { [(it.module): it.jdkRelease] }
assert releases == [":older": 11, ":newer": 17, ":overridden": 17, ":undeclared": Runtime.version().feature()] :
    "unexpected per-module releases: $releases"
// The dump-level value is still the lowest across the modules.
assert dump.jdkRelease == 11 : "unexpected dump-level release: ${dump.jdkRelease}"
