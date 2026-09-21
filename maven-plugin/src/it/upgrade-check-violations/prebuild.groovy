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

// Exit 1 is the CLI's "broken references found" code. Every other stub in this suite exits
// 0, which left the exit-1 path -- the whole point of the gate -- unexercised.
// The argv goes next to --before, as in the sibling ITs, so verify.groovy can also assert
// what an invocation asking for nothing does NOT send.
StubCli.writeJar(new File(dir, "uika-cli-${version}.jar").toPath(), "uika-stub: 1 broken reference", 1)

new File(basedir, "before.json").text = "{}"
new File(basedir, "after.json").text = "{}"
return true
