import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Same platform mapping as UikaCli.platformClassifier (not on the script classpath).
def os = System.getProperty("os.name").toLowerCase()
def arch = System.getProperty("os.arch").toLowerCase()
def classifier
if (os.contains("linux")) {
    classifier = "linux-x86_64"
} else if (os.contains("mac")) {
    classifier = (arch in ["aarch64", "arm64"]) ? "macos-aarch64" : "macos-x86_64"
} else {
    println "unsupported test platform: $os/$arch"
    return false
}

def version = "9.9.9"

// Two caches survive between runs and would shadow an edited stub: the extracted binary
// (extractBinary skips existing files) and the it-repo copy of the ZIP (Maven never
// re-fetches a cached release version).
new File(basedir, "target").deleteDir()
new File(localRepositoryPath, "net/exoego/uika/uika-cli").deleteDir()

def dir = new File(basedir, "repo/net/exoego/uika/uika-cli/$version")
dir.mkdirs()
new File(dir, "uika-cli-${version}.pom").text =
    "<project><modelVersion>4.0.0</modelVersion><groupId>net.exoego.uika</groupId>" +
    "<artifactId>uika-cli</artifactId><version>$version</version><packaging>pom</packaging></project>"

// The stub leaves a marker next to the --before argument ($3) to prove it ran; the echoed
// line must surface in the build log through the mojo's logger.
def zip = new File(dir, "uika-cli-$version-${classifier}.zip")
new ZipOutputStream(zip.newOutputStream()).withCloseable { out ->
    out.putNextEntry(new ZipEntry("uika-$version-$classifier/uika"))
    out.write('#!/bin/sh\necho ran > "$3.marker"\necho "uika-stub: dependency changes: 0"\nexit 0\n'.getBytes("UTF-8"))
    out.closeEntry()
}

new File(basedir, "before.json").text = "{}"
new File(basedir, "after.json").text = "{}"
return true
