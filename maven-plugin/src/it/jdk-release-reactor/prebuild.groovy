// Copied from the upgrade-check IT rather than shared: the invoker clones only the ITs it
// runs, so a relative reference into a sibling breaks under -Dinvoker.test. Keep the stub
// publishing here in step with that one.

// On the script class path through the invoker's addTestClassPath.
import net.exoego.uika.plugin.core.StubCli

def version = "9.9.9"

// The it-repo copy of the jar survives between runs and would shadow an edited stub. Maven
// never re-fetches a cached release version.
new File(localRepositoryPath, "net/exoego/uika/uika-cli").deleteDir()

def dir = new File(basedir, "repo/net/exoego/uika/uika-cli/$version")
dir.mkdirs()
new File(dir, "uika-cli-${version}.pom").text =
    "<project><modelVersion>4.0.0</modelVersion><groupId>net.exoego.uika</groupId>" +
    "<artifactId>uika-cli</artifactId><version>$version</version><packaging>pom</packaging></project>"

// The stub leaves a marker next to the --before argument to prove it ran and records its
// full argument list (.args) so verify.groovy can assert the flags passed to the CLI. The
// printed line must surface in the build log through the mojo's logger.
StubCli.writeJar(new File(dir, "uika-cli-${version}-jvm.jar").toPath(), "uika-stub: dependency changes: 0", 0)

new File(basedir, "before.json").text = "{}"
new File(basedir, "after.json").text = "{}"
// The clone survives between runs too. What the stub recorded last time would otherwise
// satisfy verify.groovy without the stub running.
(basedir as File).listFiles().findAll { it.name.startsWith("before.json.") }.each { it.delete() }

return true
