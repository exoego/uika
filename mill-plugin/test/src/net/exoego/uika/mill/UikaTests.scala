package net.exoego.uika.mill

import mill.*
import mill.api.{Discover, Evaluator, ExecResult}
import mill.javalib.*
import mill.scalalib.ScalaModule
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
      // A test module with a dependency of its own, so the dump can be asserted to carry
      // neither. uika checks what ships, and a test-only jar is never on the runtime
      // classpath the check compares.
      object test extends JavaTests {
        override def testFramework = "com.novocode.junit.JUnitFramework"
        override def mvnDeps = Seq(mvn"org.apache.commons:commons-collections4:4.5.0")
      }
    }
    lazy val millDiscover: Discover = Discover[this.type]
  }

  object stubCliBuild extends TestRootModule {
    object app extends JavaModule {
      // The stub uika-cli lives in a file-based repository created per test run, so the
      // repository list has to be an Input rather than a literal.
      override def repositories = Task.Input { Task.env.get("UIKA_TEST_REPO").toSeq }
    }
    // Two modules compiling for different releases, with jdkRelease left to derive: the
    // LOWEST must reach the CLI. One flag serves a run that checks every module, and
    // reading only the build JVM reported a release nothing compiles against. `older` states
    // it through mandatoryJavacOptions, which is half of what Mill actually compiles with and
    // where a shared trait usually pins it, and in the single-token `--release=N` spelling
    // javac also accepts.
    object older extends JavaModule {
      override def mandatoryJavacOptions = Seq("--release=11")
    }
    object newer extends JavaModule {
      override def javacOptions = Seq("--release", "17")
    }
    object testJvm extends JavaModule {
      object test extends JavaTests, UikaTestModule {
        override def testFramework = "com.novocode.junit.JUnitFramework"
      }
    }
    lazy val millDiscover: Discover = Discover[this.type]
  }

  object mixedLangBuild extends TestRootModule {
    // A pure-Scala module has no javacOptions to read: its release pin lives in
    // scalacOptions alone, and it declares the LOWER one here so the derived flag can only
    // come out 11 if that spelling is actually read. The plugin's own Scala version, so the
    // compiler is already in the cache mill-test warmed by building the plugin itself.
    object scalamod extends ScalaModule {
      override def scalaVersion = "3.8.2"
      override def scalacOptions = Seq("-release", "11")
      override def repositories = Task {
        super.repositories() ++ Task.env.get("UIKA_TEST_REPO").toSeq
      }
    }
    object javamod extends JavaModule {
      override def javacOptions = Seq("--release", "17")
      override def repositories = Task {
        super.repositories() ++ Task.env.get("UIKA_TEST_REPO").toSeq
      }
    }
    lazy val millDiscover: Discover = Discover[this.type]
  }

  // Minus UIKA_JFR: upgradeCheck falls back to that variable, so a developer's exported
  // collection directory must not leak into stub runs. Tests that want it add it back.
  private val systemEnv: Map[String, String] = System.getenv().asScala.toMap - "UIKA_JFR"

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

      // Test modules are dropped, and so is everything only they pull in.
      val labels = json("modules").arr.map(_("module").str)
      assert(!labels.contains(":app:test"))
      assert(!artifacts.exists(a => a.obj.get("name").map(_.str).contains("commons-collections4")))

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
      // Exit 2 is the CLI could not RUN -- a flag it rejected, a dump it could not open --
      // and it must not read like a finding.
      publishStubCli(repo, "9.9.7", "#!/bin/sh\nexit 2\n")

      Using.resource(UnitTester(
        stubCliBuild,
        null,
        env = systemEnv + ("UIKA_TEST_REPO" -> repo.toNIO.toUri.toASCIIString)
      )) { tester =>
      val before = stubCliBuild.moduleDir / "before.json"
      val after = stubCliBuild.moduleDir / "after.json"
      val exclude = stubCliBuild.moduleDir / "uika-exclude.toml"
      val jfr = stubCliBuild.moduleDir / "jfr-logs"
      val draft = stubCliBuild.moduleDir / "draft-exclude.toml"
      os.write.over(before, "{}", createFolders = true)
      os.write.over(after, "{}")
      os.write.over(exclude, "")
      os.makeDir.all(jfr)

      value(tester(Uika.upgradeCheck(
        tester.evaluator,
        before.toString,
        after.toString,
        failOn = "reachable",
        excludeFile = Seq(exclude.toString),
        jdkRelease = 11,
        jfr = jfr.toString,
        draftExcludeFile = draft.toString,
        cliVersion = "9.9.9"
      )))

      val args = os.read(os.Path(s"$before.args"))
      assert(args.contains("--fail-on reachable"))
      // Per-module checking is the default and the expensive one, so its absence matters as
      // much as its presence: sending --merged-classpath unasked quietly changes what is
      // checked.
      assert(!args.contains("--merged-classpath"))
      assert(args.contains(s"--exclude-file $exclude"))
      assert(args.contains("--jdk-release 11"))
      // A directory of evidence is forwarded as-is. Only recordings inside it are converted.
      assert(args.contains(s"--class-load-log $jfr"))
      assert(args.contains(s"--draft-exclude-file $draft"))

      value(tester(Uika.upgradeCheck(
        tester.evaluator,
        before.toString,
        after.toString,
        cliVersion = "9.9.9",
        mergedClasspath = mainargs.Flag(true)
      )))
      assert(os.read(os.Path(s"$before.args")).contains("--merged-classpath"))

      // A CLI that found violations must fail the command, not pass silently.
      val failed = tester(Uika.upgradeCheck(
        tester.evaluator,
        before.toString,
        after.toString,
        cliVersion = "9.9.8"
      ))
      assert(failed.isLeft)
      assert(failed.fold(_.toString.contains("found broken references"), _ => false))

      // And exit 2 must fail for its OWN reason. Both codes fail the command, so the
      // message is all that separates a finding from a rejected flag or an unreadable
      // dump, and calling the second one "broken references" sends the reader looking for
      // a break that was never found.
      val errored = tester(Uika.upgradeCheck(
        tester.evaluator,
        before.toString,
        after.toString,
        cliVersion = "9.9.7"
      ))
      assert(errored.isLeft)
      assert(errored.fold(_.toString.contains("failed with exit code 2"), _ => false))
      assert(errored.fold(!_.toString.contains("found broken references"), _ => false))
      }
    }

    test("the plugin is compiled to the JDK 17 floor, not the build JVM") {
      // Guards build.mill's `--release 17` (javac) and `-release 17` (scalac). Without them
      // the jar carries the mise-pinned JDK's class-file version and dies with
      // UnsupportedClassVersionError on any older Mill daemon -- exactly how the released
      // sbt 0.8.0 jar shipped major 65.
      for (cls <- Seq(classOf[UikaCli], Uika.getClass)) {
        val in = cls.getResourceAsStream(cls.getName.split('.').last + ".class")
        val header = try in.readNBytes(8) finally in.close()
        val major = ((header(6) & 0xff) << 8) | (header(7) & 0xff)
        assert(major <= 61) // 61 = JDK 17
      }
    }

    test("jdk-release is derived from the lowest module target, not the build JVM") {
      val repo = os.temp.dir(prefix = "uika-stub-repo")
      publishStubCli(repo, "9.9.9", "#!/bin/sh\necho \"$@\" > \"$3.args\"\nexit 0\n")
      Using.resource(UnitTester(
        stubCliBuild,
        null,
        env = systemEnv + ("UIKA_TEST_REPO" -> repo.toNIO.toUri.toASCIIString)
      )) { tester =>
        val before = stubCliBuild.moduleDir / "before.json"
        val after = stubCliBuild.moduleDir / "after.json"
        os.write.over(before, "{}", createFolders = true)
        os.write.over(after, "{}")

        value(tester(Uika.upgradeCheck(
          tester.evaluator, before.toString, after.toString, cliVersion = "9.9.9")))

        val args = os.read(os.Path(s"$before.args"))
        assert(args.contains("--jdk-release 11"))
      }
    }

    test("the dump records the release each module compiles for") {
      // Same mixed build as the flag test above. The flag can only carry the lowest, but the
      // dump keeps every module's own, which is what lets upgrade-check scope a JDK move to
      // the modules that made it.
      Using.resource(UnitTester(stubCliBuild, null, env = systemEnv)) { tester =>
        val out = os.Path(value(tester(Uika.dumpClasspath(tester.evaluator))))
        val json = ujson.read(os.read(out))
        def release(module: String): Option[Int] =
          json("modules").arr
            .find(_("module").str == module)
            .get
            .obj
            .get("jdkRelease")
            .map(_.num.toInt)

        assert(release(":older").contains(11))
        assert(release(":newer").contains(17))
        // Declares no target of its own, so it falls back to the dump-level release, which
        // is the lowest any module declares.
        assert(release(":app").isEmpty)
        assert(json("jdkRelease").num.toInt == 11)
      }
    }

    test("UIKA_CLI_PATH runs a binary instead of resolving one") {
      // Task.env, not System.getenv: the daemon's environment is captured at server start,
      // so a System.getenv read would ignore this map entirely and the harness could not
      // express the test at all.
      val stub = os.temp(
        "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$3.args\"\nexit 0\n",
        prefix = "uika-stub",
        perms = os.PermSet.fromString("rwxr-xr-x")
      )
      Using.resource(
        UnitTester(stubCliBuild, null, env = systemEnv + (UikaCli.CLI_PATH_ENV -> stub.toString))
      ) { tester =>
        val before = os.temp("{}", suffix = ".json")
        val after = os.temp("{}", suffix = ".json")
        // A version that was never published: resolving it would fail, so the run passing
        // is what proves resolution was skipped.
        value(tester(Uika.upgradeCheck(
          tester.evaluator,
          before = before.toString,
          after = after.toString,
          cliVersion = "0.0.0-never-published"
        )))
        assert(os.exists(os.Path(before.toString + ".args")))
      }
    }

    test("--jdkRelease overrides what the modules declare in the dump") {
      // The derivation only sees what the build declares, so a build compiling for 11 and
      // shipping on 21 has no other way to say so. The override is a statement about the
      // whole build, so it replaces every module's own value.
      Using.resource(UnitTester(stubCliBuild, null, env = systemEnv)) { tester =>
        val out = os.Path(value(tester(Uika.dumpClasspath(tester.evaluator, jdkRelease = 21))))
        val json = ujson.read(os.read(out))
        assert(json("jdkRelease").num.toInt == 21)
        assert(json("modules").arr.forall(_.obj.get("jdkRelease").map(_.num.toInt).contains(21)))
      }
    }

    test("--jdkRelease 0 leaves the dump's recorded release derived") {
      // 0 only switches the API layer off. On the DUMP it has to keep the derived values:
      // recording nothing there would take JDK move detection down with the layer, which is
      // a different feature. This is the half docs/mill.md left unsaid.
      Using.resource(UnitTester(stubCliBuild, null, env = systemEnv)) { tester =>
        val out = os.Path(value(tester(Uika.dumpClasspath(tester.evaluator, jdkRelease = 0))))
        val json = ujson.read(os.read(out))
        assert(json("jdkRelease").num.toInt == 11)
        assert(json("modules").arr.exists(_.obj.get("jdkRelease").map(_.num.toInt).contains(11)))
      }
    }

    test("jdk-release derivation reads a Scala module's scalacOptions") {
      // sbt reads the scalac option lists and docs/mill.md promises the same, so a pure-Scala
      // module pinning `-release` must reach the flag. Over-claiming is the silent direction:
      // without this the minimum came from the Java siblings or the build JVM.
      val repo = os.temp.dir(prefix = "uika-stub-repo")
      publishStubCli(repo, "9.9.9", "#!/bin/sh\necho \"$@\" > \"$3.args\"\nexit 0\n")
      Using.resource(UnitTester(
        mixedLangBuild,
        null,
        env = systemEnv + ("UIKA_TEST_REPO" -> repo.toNIO.toUri.toASCIIString)
      )) { tester =>
        val before = mixedLangBuild.moduleDir / "before.json"
        val after = mixedLangBuild.moduleDir / "after.json"
        os.write.over(before, "{}", createFolders = true)
        os.write.over(after, "{}")

        value(tester(Uika.upgradeCheck(
          tester.evaluator, before.toString, after.toString, cliVersion = "9.9.9")))

        val args = os.read(os.Path(s"$before.args"))
        assert(args.contains("--jdk-release 11"))
      }
    }

    test("the dump records a Scala module's scalacOptions release") {
      Using.resource(UnitTester(mixedLangBuild, null, env = systemEnv)) { tester =>
        val out = os.Path(value(tester(Uika.dumpClasspath(tester.evaluator))))
        val json = ujson.read(os.read(out))
        def release(module: String): Option[Int] =
          json("modules").arr
            .find(_("module").str == module)
            .get
            .obj
            .get("jdkRelease")
            .map(_.num.toInt)

        assert(release(":scalamod").contains(11))
        assert(release(":javamod").contains(17))
        assert(json("jdkRelease").num.toInt == 11)
      }
    }

    test("the injected args stay stable so watch mode never re-triggers on them") {
      // Mill watches every evaluated Task.Input by hash and its poll re-runs on any
      // change, so a per-evaluation nonce here would make `./mill -w` loop forever
      // while UIKA_JFR is set. Stability is the contract. The cost is that a CACHED
      // test replay records nothing, which is why docs/mill.md says to collect with
      // `test` (a command, never cached).
      val jfrDir = os.temp.dir(prefix = "uika-jfr") / "recordings"
      Using.resource(UnitTester(
        stubCliBuild,
        null,
        env = systemEnv + ("UIKA_JFR" -> jfrDir.toString)
      )) { tester =>
        def evaluate(): Seq[String] = value(tester(stubCliBuild.testJvm.test.forkArgs))
        assert(evaluate() == evaluate())
      }
      Using.resource(UnitTester(stubCliBuild, null, env = systemEnv)) { tester =>
        def evaluate(): Seq[String] = value(tester(stubCliBuild.testJvm.test.forkArgs))
        assert(evaluate() == evaluate())
      }
    }

    test("a file-valued UIKA_JFR fails loudly instead of dying inside makeDir") {
      // A text log or a suffixless recording passes the suffix-only recording check,
      // and os.makeDir.all on a regular file dies with a raw FileAlreadyExistsException
      // naming neither uika nor the variable. The Gradle plugin fails fast on the same
      // value shape, and so must this.
      val log = os.temp.dir(prefix = "uika-jfr") / "loads.log"
      os.write(log, "[class,load] example.App\n")
      Using.resource(UnitTester(
        stubCliBuild,
        null,
        env = systemEnv + ("UIKA_JFR" -> log.toString)
      )) { tester =>
        val failed = tester(stubCliBuild.testJvm.test.forkArgs)
        assert(failed.isLeft)
        assert(failed.fold(
          failure => failure.toString.contains("UIKA_JFR must name a directory"),
          _ => false
        ))
      }
    }

    test("upgradeCheck --jfr falls back to UIKA_JFR") {
      // One knob serving both phases, like the sibling tools' single option: the same
      // variable that made the tests record is read back by the check, so a CI recipe
      // sets UIKA_JFR once. --jfr stays the explicit override.
      val repo = os.temp.dir(prefix = "uika-stub-repo")
      publishStubCli(repo, "9.9.9", "#!/bin/sh\necho \"$@\" > \"$3.args\"\nexit 0\n")
      val jfrDir = os.temp.dir(prefix = "uika-jfr-consume")
      Using.resource(UnitTester(
        stubCliBuild,
        null,
        env = systemEnv ++ Map(
          "UIKA_TEST_REPO" -> repo.toNIO.toUri.toASCIIString,
          "UIKA_JFR" -> jfrDir.toString
        )
      )) { tester =>
        val before = stubCliBuild.moduleDir / "before.json"
        val after = stubCliBuild.moduleDir / "after.json"
        os.write.over(before, "{}", createFolders = true)
        os.write.over(after, "{}")

        value(tester(Uika.upgradeCheck(
          tester.evaluator, before.toString, after.toString, cliVersion = "9.9.9")))

        val args = os.read(os.Path(s"$before.args"))
        assert(args.contains(s"--class-load-log $jfrDir"))

        // --jfr stays the explicit override: with both set, the flag's directory must
        // reach the CLI and the variable's must not.
        val explicit = os.temp.dir(prefix = "uika-jfr-explicit")
        value(tester(Uika.upgradeCheck(
          tester.evaluator, before.toString, after.toString,
          jfr = explicit.toString, cliVersion = "9.9.9")))
        val overridden = os.read(os.Path(s"$before.args"))
        assert(overridden.contains(s"--class-load-log $explicit"))
        assert(!overridden.contains(jfrDir.toString))
      }
    }

    test("UikaTestModule injects the JFR flag into forked test JVMs") {
      // A leaf that does not exist yet, and deleted again between the two evaluations: JFR
      // silently records to a single clobbered FILE when the leaf is missing under an
      // existing parent, so the mkdir has to happen on EVERY run and not once per cache miss.
      val jfrDir = os.temp.dir(prefix = "uika-jfr") / "recordings"
      Using.resource(UnitTester(
        stubCliBuild,
        null,
        env = systemEnv + ("UIKA_JFR" -> jfrDir.toString)
      )) { tester =>
        def evaluate(): Seq[String] = value(tester(stubCliBuild.testJvm.test.forkArgs))
        val forkArgs = evaluate()
        assert(forkArgs.exists { arg =>
          arg.startsWith("-XX:StartFlightRecording:jdk.ClassLoad#enabled=true") &&
          arg.contains(jfrDir.toString)
        })
        assert(os.isDir(jfrDir))

        os.remove.all(jfrDir)
        evaluate()
        assert(os.isDir(jfrDir))
      }
    }
  }
}
