package net.exoego.uika.mill

import mill.*
import mill.api.BuildCtx
import mill.javalib.TestModule
import net.exoego.uika.plugin.core.{JfrEvidence, UikaCli}

/**
 * Makes a test module's forked JVMs record every class load into JFR, so
 * `Uika/upgradeCheck --jfr <dir>` can tell a reference that never loads at runtime from one
 * that does.
 *
 * The one part of the plugin that needs a mixin: `forkArgs` is a task on the test module
 * itself, out of reach of an [[ExternalModule]] command. Collection is keyed by the
 * `UIKA_JFR` environment variable because an ordinary test run has no task argument to
 * carry it, and `upgradeCheck` reads the same variable back when `--jfr` is not given, so
 * one option serves both phases.
 *
 * Mix this in LAST, and append to `super.forkArgs()` in any override of your own: `forkArgs`
 * is a plain list, so a `def forkArgs = Seq(...)` later in the linearization silently drops
 * the injected flag. `./mill testLocal` does not fork and never records. Test JVMs need
 * JDK 17+ for the event-settings syntax.
 */
trait UikaTestModule extends TestModule {

  override def forkArgs: T[Seq[String]] = Task { super.forkArgs() ++ uikaJfrArgs() }

  /**
   * The recording flag for forked test JVMs, empty when `UIKA_JFR` is unset or names a
   * recording to consume.
   *
   * `Task.Input`, not a cached `Task`: the `os.makeDir.all` below must run on EVERY
   * invocation, or a directory deleted between two runs with an unchanged `UIKA_JFR`
   * would never be recreated (the cached value replays and the body is skipped).
   */
  def uikaJfrArgs: T[Seq[String]] = Task.Input {
    Task.env.get("UIKA_JFR").filter(_.nonEmpty) match {
      case None => Seq.empty[String]
      case Some(value) =>
        val dir = os.Path(value, Task.ctx().workspace)
        if (JfrEvidence.valueNamesRecording(dir.toNIO)) {
          // Said out loud because the skip is otherwise symptomless: test JVMs cannot record
          // into an existing recording, so a collect run against a .jfr value records nothing.
          Task.log.info(
            s"uika: UIKA_JFR names a .jfr recording (consumption-only); test JVMs will not record: $dir"
          )
          Seq.empty[String]
        } else {
          // Load-bearing: a missing PARENT aborts JVM startup, but a missing leaf directory
          // under an existing parent makes JFR silently record to a single clobbered FILE.
          BuildCtx.withFilesystemCheckerDisabled(os.makeDir.all(dir))
          // The nonce defeats testCached replay: a cached test forks no JVM and records
          // nothing, with no symptom (Gradle closes this with upToDateWhen(false), Bazel
          // with --nocache_test_results). A fresh value per evaluation keeps a cached
          // test's inputs from ever matching while collection is on; without UIKA_JFR
          // the args stay stable and ordinary caching is untouched.
          Seq(
            UikaCli.jfrClassLoadJvmArg(dir.toNIO),
            s"-Duika.jfr.collect=${System.nanoTime()}"
          )
        }
    }
  }
}
