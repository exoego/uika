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
