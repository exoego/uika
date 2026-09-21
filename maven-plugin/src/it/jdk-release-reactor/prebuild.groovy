// Copied from the upgrade-check IT rather than shared: the invoker clones only the ITs it
// runs, so a relative reference into a sibling breaks under -Dinvoker.test. Keep the stub
// publishing here in step with that one.

// On the script class path through the invoker's addTestClassPath.
import net.exoego.uika.plugin.core.StubCli

def version = "9.9.9"

// The it-repo copy of the jar survives between runs and would shadow an edited stub: Maven
// never re-fetches a cached release version.
new File(basedir, "target").deleteDir()
new File(localRepositoryPath, "net/exoego/uika/uika-cli").deleteDir()

def dir = new File(basedir, "repo/net/exoego/uika/uika-cli/$version")
dir.mkdirs()
new File(dir, "uika-cli-${version}.pom").text =
    "<project><modelVersion>4.0.0</modelVersion><groupId>net.exoego.uika</groupId>" +
    "<artifactId>uika-cli</artifactId><version>$version</version></project>"

// The stub leaves a marker next to the --before argument ($3) to prove it ran and records its
// full argument list ($3.args) so verify.groovy can assert the flags passed to the CLI; the
// echoed line must surface in the build log through the mojo's logger.
StubCli.writeJar(new File(dir, "uika-cli-${version}.jar").toPath(), "uika-stub: dependency changes: 0", 0)

new File(basedir, "before.json").text = "{}"
new File(basedir, "after.json").text = "{}"

return true
