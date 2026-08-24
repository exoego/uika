package net.exoego.uika.mill

import mill.*
import mill.api.{Discover, Evaluator, ExecResult}
import mill.javalib.*
import mill.testkit.{TestRootModule, UnitTester}
import net.exoego.uika.plugin.core.UikaCli
import utest.*

import java.util.zip.{ZipEntry, ZipOutputStream}
import scala.util.Using
import scala.jdk.CollectionConverters.*

object UikaTests extends TestSuite {

  object multiModule extends TestRootModule {
    object core extends JavaModule
    object app extends JavaModule {
      override def moduleDeps = Seq(core)
      override def mvnDeps = Seq(mvn"org.apache.commons:commons-lang3:3.20.0")
    }
    lazy val millDiscover: Discover = Discover[this.type]
  }

  object stubCliBuild extends TestRootModule {
    object app extends JavaModule {
      // The stub uika-cli lives in a file-based repository created per test run, so the
      // repository list has to be an Input rather than a literal.
      override def repositories = Task.Input { Task.env.get("UIKA_TEST_REPO").toSeq }
    }
    object testJvm extends JavaModule {
      object test extends JavaTests, UikaTestModule {
        override def testFramework = "com.novocode.junit.JUnitFramework"
      }
    }
    lazy val millDiscover: Discover = Discover[this.type]
  }

  private val systemEnv: Map[String, String] = System.getenv().asScala.toMap

  private def value[T](result: Either[ExecResult.Failing[?], UnitTester.Result[T]]): T =
    result.fold(failure => throw new RuntimeException(s"evaluation failed: $failure"), _.value)

  private def writeJava(dir: os.Path, name: String, body: String): Unit =
    os.write.over(dir / "src" / "example" / s"$name.java", body, createFolders = true)

  /** Publishes a stub uika-cli distribution: a shell script the plugin extracts and runs. */
  private def publishStubCli(repo: os.Path, version: String, script: String): Unit = {
    val dir = repo / "net" / "exoego" / "uika" / "uika-cli" / version
    os.makeDir.all(dir)
    os.write.over(
      dir / s"uika-cli-$version.pom",
      s"""<project><modelVersion>4.0.0</modelVersion><groupId>net.exoego.uika</groupId>""" +
        s"""<artifactId>uika-cli</artifactId><version>$version</version>""" +
        s"""<packaging>pom</packaging></project>"""
    )
    val classifier = UikaCli.platformClassifier()
    val out = new ZipOutputStream(
      java.nio.file.Files.newOutputStream((dir / s"uika-cli-$version-$classifier.zip").toNIO)
    )
    try {
      out.putNextEntry(new ZipEntry(s"uika-$version-$classifier/uika"))
      out.write(script.getBytes("UTF-8"))
      out.closeEntry()
    } finally out.close()
  }

  def tests: Tests = Tests {

    test("dump records coordinates, module outputs and inter-module edges") {
      Using.resource(UnitTester(multiModule, null)) { tester =>
      writeJava(
        multiModule.moduleDir / "core",
        "Core",
        "package example; public final class Core { public String name() { return \"core\"; } }"
      )
      writeJava(
        multiModule.moduleDir / "app",
        "App",
        "package example; public final class App { public String message() { return new Core().name(); } }"
      )

      val out = os.Path(value(tester(Uika.dumpClasspath(tester.evaluator))))
      val json = ujson.read(os.read(out))

      assert(json("version").num == 2)
      val roots = json("roots").arr.map(_.str)
      val artifacts = json("artifacts").arr
      def fullPath(node: ujson.Value): String = roots(node("root").num.toInt) + node("path").str

      val app = json("modules").arr.find(_("module").str == ":app").get
      val appArtifacts = app("artifactRefs").arr.map(ref => artifacts(ref.num.toInt))

      // Coordinates come from the resolution, so an external jar carries all three parts.
      assert(appArtifacts.exists { a =>
        a.obj.get("group").map(_.str).contains("org.apache.commons") &&
        a.obj.get("name").map(_.str).contains("commons-lang3") &&
        a.obj.get("version").map(_.str).contains("3.20.0") &&
        fullPath(a).endsWith("commons-lang3-3.20.0.jar")
      })

      // The inter-module dependency is on :app's own classpath as a coordinate-less entry
      // attributed to :core, so per-module checking can resolve references across the edge.
      val coreClasses = value(tester(multiModule.core.compile)).classes.path.toString
      assert(appArtifacts.exists { a =>
        !a.obj.contains("group") &&
        a.obj.get("project").map(_.str).contains(":core") &&
        fullPath(a) == coreClasses
      })

      // :core is dumped as a module in its own right, and no module lists its own output twice.
      val core = json("modules").arr.find(_("module").str == ":core").get
      assert(core("classesDirs").arr.map(fullPath).contains(coreClasses))
      assert(!core("artifactRefs").arr.map(ref => fullPath(artifacts(ref.num.toInt)))
        .contains(coreClasses))
      }
    }

    test("upgrade-check forwards every flag to the CLI and reports its exit code") {
      val repo = os.temp.dir(prefix = "uika-stub-repo")
      // The stub records its full argument list next to the --before argument so the flags can
      // be asserted, and echoes a line that must surface through Mill's logger.
      publishStubCli(
        repo,
        "9.9.9",
        "#!/bin/sh\necho \"$@\" > \"$3.args\"\necho \"uika-stub: dependency changes: 0\"\nexit 0\n"
      )
      publishStubCli(repo, "9.9.8", "#!/bin/sh\nexit 1\n")

      Using.resource(UnitTester(
        stubCliBuild,
        null,
        env = systemEnv + ("UIKA_TEST_REPO" -> repo.toNIO.toUri.toASCIIString)
      )) { tester =>
      val before = stubCliBuild.moduleDir / "before.json"
      val after = stubCliBuild.moduleDir / "after.json"
      val exclude = stubCliBuild.moduleDir / "uika-exclude.toml"
      os.write.over(before, "{}", createFolders = true)
      os.write.over(after, "{}")
      os.write.over(exclude, "")

      value(tester(Uika.upgradeCheck(
        tester.evaluator,
        before.toString,
        after.toString,
        failOn = "reachable",
        excludeFile = Seq(exclude.toString),
        jdkRelease = 11,
        cliVersion = "9.9.9"
      )))

      val args = os.read(os.Path(s"$before.args"))
      assert(args.contains("--fail-on reachable"))
      assert(args.contains(s"--exclude-file $exclude"))
      assert(args.contains("--jdk-release 11"))

      // A CLI that found violations must fail the command, not pass silently.
      val failed = tester(Uika.upgradeCheck(
        tester.evaluator,
        before.toString,
        after.toString,
        cliVersion = "9.9.8"
      ))
      assert(failed.isLeft)
      }
    }

    test("UikaTestModule injects the JFR flag into forked test JVMs") {
      val jfrDir = os.temp.dir(prefix = "uika-jfr")
      Using.resource(UnitTester(
        stubCliBuild,
        null,
        env = systemEnv + ("UIKA_JFR" -> jfrDir.toString)
      )) { tester =>
        val forkArgs = value(tester(stubCliBuild.testJvm.test.forkArgs))
        assert(forkArgs.exists { arg =>
          arg.startsWith("-XX:StartFlightRecording:jdk.ClassLoad#enabled=true") &&
          arg.contains(jfrDir.toString)
        })
      }
    }
  }
}
