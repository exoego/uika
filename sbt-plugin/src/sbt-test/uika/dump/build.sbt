ThisBuild / scalaVersion := "2.12.21"

lazy val core = (project in file("core"))
  .settings(
    // On the Compile axis on purpose: sbt delegates Compile to Zero and never the reverse,
    // so a derivation reading the project axis alone would miss this, the idiomatic spelling.
    Compile / javacOptions := Seq("--release", "11"),
    Compile / sourceGenerators += Def.task {
      val out = (Compile / sourceManaged).value / "example" / "Core.scala"
      IO.write(
        out,
        """package example
          |
          |final class Core {
          |  def name: String = "core"
          |}
          |""".stripMargin
      )
      Seq(out)
    }.taskValue
  )

lazy val app = (project in file("app"))
  .dependsOn(core)
  .settings(
    javacOptions := Seq("--release", "17"),
    libraryDependencies += "org.apache.commons" % "commons-lang3" % "3.20.0",
    Compile / sourceGenerators += Def.task {
      val out = (Compile / sourceManaged).value / "example" / "App.scala"
      IO.write(
        out,
        """package example
          |
          |final class App {
          |  def message: String = new Core().name
          |}
          |""".stripMargin
      )
      Seq(out)
    }.taskValue
  )

lazy val prepareVendoredJar = taskKey[Unit]("Writes an unmanaged jar into app/lib")

// lib/ is sbt's default unmanagedBase, the everyday spelling of a vendored jar. It is on
// the runtime classpath but has no update.value entry, which is exactly the shape that
// used to vanish from the dump.
prepareVendoredJar := {
  import java.util.zip.{ZipEntry, ZipOutputStream}
  val dir = (app / baseDirectory).value / "lib"
  IO.createDirectory(dir)
  val out = new ZipOutputStream(new java.io.FileOutputStream(dir / "vendored.jar"))
  try {
    out.putNextEntry(new ZipEntry("marker.txt"))
    out.write("vendored".getBytes("UTF-8"))
    out.closeEntry()
  } finally out.close()
}

lazy val checkDump = taskKey[Unit]("Checks the generated uika dump")

checkDump := {
  val out = uikaDumpClasspath.value
  val json = scala.util.parsing.json.JSON.parseFull(IO.read(out))
    .getOrElse(sys.error(s"dump is not JSON: $out"))
    .asInstanceOf[Map[String, Any]]

  assert(json("version") == 2.0, json)
  val roots = json("roots").asInstanceOf[List[String]]
  val artifacts = json("artifacts").asInstanceOf[List[Map[String, Any]]]
  val module = json("modules")
    .asInstanceOf[List[Map[String, Any]]]
    .find(_("module") == ":app")
    .getOrElse(sys.error(s":app module is missing from $json"))

  val classesDirs = module("classesDirs").asInstanceOf[List[Map[String, Any]]]
  assert(classesDirs.exists { dir =>
    roots(dir("root").asInstanceOf[Double].toInt) + dir("path") == (app / Compile / classDirectory).value.getAbsolutePath
  }, module)

  val artifactRefs = module("artifactRefs").asInstanceOf[List[Double]].map(_.toInt)
  assert(artifactRefs.map(artifacts).exists { artifact =>
    artifact.get("group").contains("org.apache.commons") &&
      artifact.get("name").contains("commons-lang3") &&
      artifact.get("version").contains("3.20.0") &&
      (roots(artifact("root").asInstanceOf[Double].toInt) + artifact("path")).endsWith("commons-lang3-3.20.0.jar")
  }, module)

  // The inter-module dependency appears on :app's own classpath as a coordinate-less
  // entry pointing at :core's class directory, so per-module checking sees it.
  val coreClassesDir = (core / Compile / classDirectory).value.getAbsolutePath
  assert(artifactRefs.map(artifacts).exists { artifact =>
    !artifact.contains("group") &&
      roots(artifact("root").asInstanceOf[Double].toInt) + artifact("path") == coreClassesDir
  }, s"no internal-dependency entry for $coreClassesDir in $module")

  // The vendored jar is not in update.value, so it can only appear through the
  // coordinate-less fallback: nothing the version diff compares, but a scan target all
  // the same. Without it the jar is on the classpath :app runs on and absent from the dump.
  val vendored = ((app / baseDirectory).value / "lib" / "vendored.jar").getAbsolutePath
  assert(artifactRefs.map(artifacts).exists { artifact =>
    !artifact.contains("group") &&
      roots(artifact("root").asInstanceOf[Double].toInt) + artifact("path") == vendored
  }, s"no unmanaged-jar entry for $vendored in $module")

  // Each module records the release IT compiles for, so upgrade-check can scope a JDK
  // move to the modules that made it; the dump-level value is the lowest of them.
  assert(module("jdkRelease") == 17.0, module)
  assert(json("jdkRelease") == 11.0, json)

  // :core itself is dumped as a module with its own classesDirs.
  val coreModule = json("modules")
    .asInstanceOf[List[Map[String, Any]]]
    .find(_("module") == ":core")
    .getOrElse(sys.error(s":core module is missing from $json"))
  assert(coreModule("classesDirs").asInstanceOf[List[Map[String, Any]]].exists { dir =>
    roots(dir("root").asInstanceOf[Double].toInt) + dir("path") == coreClassesDir
  }, coreModule)
  assert(coreModule("jdkRelease") == 11.0, coreModule)
}
