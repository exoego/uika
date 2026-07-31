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

def libModule = json.modules.find { it.module == ":dummy-maven-lib" }
assert libModule != null
assert libModule.classesDirs.any { dir ->
    json.roots[dir.root] + dir.path == new File(basedir, "lib/target/classes").absolutePath
}
