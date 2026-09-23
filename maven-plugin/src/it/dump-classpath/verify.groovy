import groovy.json.JsonSlurper

def output = new File(basedir, "target/uika/classpath.json")
assert output.isFile()

def json = new JsonSlurper().parse(output)
assert json.version == 2
def module = json.modules.find { it.module == ":dummy-maven-app" }
assert module != null
assert module.classesDirs.any { dir ->
    json.roots[dir.root] + dir.path == new File(basedir, "app/target/classes").absolutePath
}
assert module.artifactRefs.collect { json.artifacts[it] }.any { artifact ->
    artifact.group == "org.apache.commons" &&
        artifact.name == "commons-lang3" &&
        artifact.version == "3.20.0" &&
        (json.roots[artifact.root] + artifact.path).endsWith("commons-lang3-3.20.0.jar")
}

// Scope: the runtime dependency is on the classpath the application runs with and must be
// dumped; the test one is not. The goal has no scope filter of its own. Maven resolves
// each project for it afresh at the RUNTIME scope the goal declares, whatever surefire
// resolved earlier, and that declaration is what these two checks pin.
def scopesAreRespected = { artifacts, which ->
    assert artifacts.any { it.group == "org.slf4j" && it.name == "slf4j-api" && it.version == "1.7.36" } :
            "a runtime-scoped dependency was dropped from the ${which} dump: ${artifacts*.name}"
    assert !artifacts.any { it.group == "org.hamcrest" } :
            "a test-scoped dependency reached the ${which} dump: ${artifacts*.name}"
}

// The reactor dependency is attributed to its producing module, so uika can check
// :dummy-maven-app against its own classpath and fall back to :dummy-maven-lib's
// classesDirs if the jar is ever missing.
def libArtifact = module.artifactRefs.collect { json.artifacts[it] }.find { artifact ->
    artifact.group == "net.exoego.uika.it" && artifact.name == "dummy-maven-lib"
}
assert libArtifact != null
assert libArtifact.project == ":dummy-maven-lib"
def libPath = json.roots[libArtifact.root] + libArtifact.path
assert new File(libPath).exists()

// The aggregator compiles nothing, so it is not a module. Left in, it becomes a check run
// of its own with no application roots, and every violation that run finds fails
// --fail-on reachable however unreachable the real modules proved it.
assert json.modules.collect { it.module } ==
        [":dummy-maven-lib", ":dummy-maven-app", ":dummy-maven-nosources"] :
        "the pom-packaged aggregator must not be dumped as a module: ${json.modules*.module}"

// ...but a JAR module with no sources IS one, with no classesDirs, and that is where the
// line is drawn. The skip tests PACKAGING rather than a missing output directory because
// this goal binds to no lifecycle phase: on an unbuilt tree every module looks like this
// one, and an emptiness test would leave the dump with nothing in it.
def noSources = json.modules.find { it.module == ":dummy-maven-nosources" }
assert noSources.classesDirs.isEmpty() :
        "a module that compiled nothing should carry no classesDirs: ${noSources}"

def libModule = json.modules.find { it.module == ":dummy-maven-lib" }
assert libModule != null
assert libModule.classesDirs.any { dir ->
    json.roots[dir.root] + dir.path == new File(basedir, "lib/target/classes").absolutePath
}
scopesAreRespected(libModule.artifactRefs.collect { json.artifacts[it] }, "reactor")

// The -pl invocation: Maven resolved dependencies for the selected project only, so an
// unselected module in the dump could only carry zero artifacts. The dump must therefore
// hold the selected module alone, not the whole reactor.
def selectedFile = [new File(basedir, "target/uika/selected.json"),
                    new File(basedir, "lib/target/uika/selected.json")].find { it.isFile() }
assert selectedFile != null
def selected = new JsonSlurper().parse(selectedFile)
assert selected.modules.collect { it.module } == [":dummy-maven-lib"]
assert selected.modules[0].classesDirs.any { dir ->
    selected.roots[dir.root] + dir.path == new File(basedir, "lib/target/classes").absolutePath
}
scopesAreRespected(selected.modules[0].artifactRefs.collect { selected.artifacts[it] }, "-pl lib")

// The third invocation named an output under a regular file. The write's own exception
// names a path and a reason, so what the goal adds is which knob pointed there.
def unwritable = new File(basedir, "pom.xml/classpath.json")
assert !unwritable.exists()
assert new File(basedir, "build.log").text.contains(
        "failed to write uika classpath dump: ${unwritable.absolutePath}") :
        "an unwritable output did not fail with the goal's message naming the path"
