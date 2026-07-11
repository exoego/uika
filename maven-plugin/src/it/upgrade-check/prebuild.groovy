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
def dir = new File(basedir, "repo/net/exoego/uika/uika-cli/$version")
dir.mkdirs()
new File(dir, "uika-cli-${version}.pom").text =
    "<project><modelVersion>4.0.0</modelVersion><groupId>net.exoego.uika</groupId>" +
    "<artifactId>uika-cli</artifactId><version>$version</version><packaging>pom</packaging></project>"

// The stub leaves a marker next to the --before argument ($3) to prove it ran.
def zip = new File(dir, "uika-cli-$version-${classifier}.zip")
new ZipOutputStream(zip.newOutputStream()).withCloseable { out ->
    out.putNextEntry(new ZipEntry("uika-$version-$classifier/uika"))
    out.write('#!/bin/sh\necho ran > "$3.marker"\nexit 0\n'.getBytes("UTF-8"))
    out.closeEntry()
}

new File(basedir, "before.json").text = "{}"
new File(basedir, "after.json").text = "{}"
return true
