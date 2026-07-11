ThisBuild / scalaVersion := "2.12.21"

lazy val app = (project in file("app"))
  .settings(
    libraryDependencies += "org.apache.commons" % "commons-lang3" % "3.20.0",
    Compile / sourceGenerators += Def.task {
      val out = (Compile / sourceManaged).value / "example" / "App.scala"
      IO.write(
        out,
        """package example
          |
          |final class App {
          |  def message: String = "ok"
          |}
          |""".stripMargin
      )
      Seq(out)
    }.taskValue
  )

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
    artifact("group") == "org.apache.commons" &&
      artifact("name") == "commons-lang3" &&
      artifact("version") == "3.20.0" &&
      (roots(artifact("root").asInstanceOf[Double].toInt) + artifact("path")).endsWith("commons-lang3-3.20.0.jar")
  }, module)
}
