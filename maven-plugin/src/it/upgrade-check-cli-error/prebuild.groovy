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

// Exit 2 is what clap answers for a usage error, so it means the CLI could not run rather
// than that it found something. The mojo has to separate it from exit 1, and no stub in
// this suite produced any non-zero code before.
StubCli.writeJar(new File(dir, "uika-cli-${version}-jvm.jar").toPath(), "uika-stub: usage error", true, 2)

new File(basedir, "before.json").text = "{}"
new File(basedir, "after.json").text = "{}"

// An evidence directory whose walk cannot finish: the converter follows links, and this
// one points back at its own parent.
def looped = new File(basedir, "looped-logs")
looped.mkdirs()
java.nio.file.Files.createSymbolicLink(new File(looped, "loop").toPath(), looped.toPath())
return true
