ThisBuild / scalaVersion := "2.12.21"

// Normally uikaCliVersion defaults to the plugin's own version; pin it to the stub repo
// below, which publishes 9.9.9 (exit 0) and 9.9.8 (exit 1, i.e. violations found).
ThisBuild / uikaCliVersion := "9.9.9"

resolvers += "uika-stub" at (baseDirectory.value / "repo").toURI.toString

lazy val prepareStubRepo = taskKey[Unit]("Writes stub uika-cli ZIPs into the file-based test repository")

prepareStubRepo := {
  import java.util.zip.{ZipEntry, ZipOutputStream}
  val classifier = net.exoego.uika.plugin.core.UikaCli.platformClassifier()
  def publish(version: String, script: String): Unit = {
    val dir = baseDirectory.value / "repo" / "net" / "exoego" / "uika" / "uika-cli" / version
    IO.createDirectory(dir)
    IO.write(
      dir / s"uika-cli-$version.pom",
      s"""<project><modelVersion>4.0.0</modelVersion><groupId>net.exoego.uika</groupId><artifactId>uika-cli</artifactId><version>$version</version><packaging>pom</packaging></project>"""
    )
    val out = new ZipOutputStream(new java.io.FileOutputStream(dir / s"uika-cli-$version-$classifier.zip"))
    try {
      out.putNextEntry(new ZipEntry(s"uika-$version-$classifier/uika"))
      out.write(script.getBytes("UTF-8"))
      out.closeEntry()
    } finally out.close()
  }
  // The stub leaves a marker next to the --before argument ($3) to prove it ran.
  publish("9.9.9", "#!/bin/sh\necho ran > \"$3.marker\"\nexit 0\n")
  publish("9.9.8", "#!/bin/sh\nexit 1\n")
}
