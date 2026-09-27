# Notes for Agents: mill-plugin/

The shared plugin rules are in `jvm-plugin-core/AGENTS.md`. Claude Code imports it below.

@../jvm-plugin-core/AGENTS.md

## Mill Plugin Notes

Most Mill invariants live as comments at their point of use (`UikaModule.scala`,
`UikaTestModule.scala`, `build.mill`) or are locked by tests in `UikaTests.scala`.
This section keeps only what neither can hold.

- The one entry point is the `UikaModule` trait the user declares as a top-level
  object. Settings are its tasks and never command arguments, so the dump and the check
  read one value. The earlier `ExternalModule` took every setting per command, and a
  `--jdkRelease` given to the check but not the dump silently lost JDK-move detection.
  Do not add a command argument that duplicates a setting. Verified on Mill 1.1.8: the
  commands get the short selector (`uika.dumpClasspath`), read the object's settings,
  and re-run on edits. A YAML build takes the object too (`extends:` plus the settings
  as keys), with the plugin under `mill-build: mvnDeps:`. Custom `//|` header keys fail
  or are silently ignored, so they are no way to pass settings.
- In the command bodies `getClass` is the USER's object, in the build's package, so
  the plugin's own manifest is read through `classOf[UikaModule]`.
- JFR stays an environment variable (`UIKA_JFR`) read by both `UikaTestModule` and
  `upgradeCheck`, not a setting. A test module has no public way to reach the build's
  `UikaModule` (`ModuleCtx.enclosingModule` is `private[mill]`), and a value in the
  build file would make every forked test run record.

- `Task.ctx().workspace`, NEVER `BuildCtx.workspaceRoot`, for the default output
  path and for resolving relative arguments. The latter is the launcher's root and
  does not follow `UnitTester`, so the bug shows as tests that still pass while the
  dump lands in the plugin's own tree.
- The command macro lifts every statically visible `task()` call into an
  UNCONDITIONAL edge, so a `defaultResolver()` in a dead fallback branch is still
  built on every run. Tasks on runtime-valued modules cannot be lifted at all;
  collect them with `Task.traverse(...)` outside the body.
- No mise backend installs the Mill launcher (`ubi:`/`github:` find no matching
  release asset). The committed `mill-plugin/mill` bootstrap script reads the
  `//| mill-version:` header in `build.mill`, so mise only supplies the JVM.
