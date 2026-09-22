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
    "<artifactId>uika-cli</artifactId><version>$version</version><packaging>pom</packaging></project>"

// The stub leaves a marker next to the --before argument to prove it ran and records its
// full argument list (.args) so verify.groovy can assert the flags passed to the CLI; the
// printed line must surface in the build log through the mojo's logger.
StubCli.writeJar(new File(dir, "uika-cli-${version}-jvm.jar").toPath(), "uika-stub: dependency changes: 0", 0)

new File(basedir, "before.json").text = "{}"
new File(basedir, "after.json").text = "{}"
// The evidence directory the goals point at with -Duika.jfr. Before the
// return: a Groovy script's return ends it, so a statement after one never runs.
new File(basedir, "load-logs").mkdirs()
// A REAL recording inside the log directory: the mojo must convert it (JfrEvidence)
// instead of handing binary JFR to the JVM-free CLI. Whatever classes load during the
// window (JFR internals at least) give it content; verify.groovy asserts plumbing only.
def rec = new jdk.jfr.Recording()
rec.enable("jdk.ClassLoad").withStackTrace().withoutThreshold()
rec.start()
Class.forName("java.util.zip.Adler32", false, this.class.classLoader)
rec.stop()
rec.dump(new File(basedir, "load-logs/rec.jfr").toPath())
rec.close()
return true
