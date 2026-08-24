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
 * This is the one part of the plugin that needs a mixin. `forkArgs` is a task on the test module
 * itself, and an [[ExternalModule]] command has no way to reach into it the way the Gradle plugin
 * reaches `Test` tasks through `withType(Test).configureEach`.
 *
 * The knob is the `UIKA_JFR` environment variable rather than a task argument, because collection
 * happens on the base branch's ordinary test run and consumption on the PR's check. One value
 * exported once covers the collect side of that flow, which is what the CI recipe is built around.
 *
 * Mix this in LAST, and append to `super.forkArgs()` in any override of your own. `forkArgs` is a
 * plain list, so a `def forkArgs = Seq(...)` further down the linearization drops the injected flag
 * and the collect run records nothing. Mill has no provider-style escape from that (the Gradle
 * plugin uses `jvmArgumentProviders` for the same reason). `./mill testLocal` does not fork and so
 * never records either.
 *
 * Test JVMs need JDK 17+ for the event-settings syntax.
 */
trait UikaTestModule extends TestModule {

  override def forkArgs: T[Seq[String]] = Task { super.forkArgs() ++ uikaJfrArgs() }

  /**
   * The recording flag for forked test JVMs, empty when `UIKA_JFR` is unset or names a
   * recording to consume.
   *
   * `Task.Input`, and reading the environment here rather than through a task of its own,
   * because the `os.makeDir.all` below has to run on EVERY invocation. A cached `Task` keyed
   * on an unchanged `UIKA_JFR` replays its stored value and skips the body, so a directory
   * deleted between two runs would never be recreated. The Gradle plugin puts the same mkdir
   * in `test.doFirst` for the same reason.
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
          Seq(UikaCli.jfrClassLoadJvmArg(dir.toNIO))
        }
    }
  }
}
