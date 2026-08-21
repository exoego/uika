---
name: uika-jvm-plugins
description: Invariants for the uika Gradle, sbt, and Maven build-tool plugins and the shared jvm-plugin-core - task shape, coordinate handling, CLI fetch and version defaulting, logger wiring, --jdk-release derivation, and how the tests stub the CLI. Load before changing anything under gradle-plugin/, sbt-plugin/, maven-plugin/, or jvm-plugin-core/.
---

# uika JVM Build-Tool Plugin Notes

## Gradle Plugin Notes

- Keep the module-task + root-merge shape (`uikaDumpModuleClasspath` per
  project, merged by root `uikaDumpClasspath`). A root task cannot safely
  resolve other projects' configurations at execution time, and the split
  avoids Gradle 9 exclusive-lock failures.
- Coordinates come from `ResolvedArtifactResult`; never recover them from file
  paths. The artifact view is lenient so unbuilt project dependencies are
  skipped instead of failing the dump.
- `uikaResolveClasspath` (rehydration) uses one detached configuration per
  notation: multiple versions of a module in one configuration would be
  conflict-resolved down to the highest. Classifiers are reconstructed from the
  original file name.
- All Gradle tasks are configuration-cache compatible; keep them that way. Task
  actions never touch `getProject()`: UikaPlugin wires project state in as task
  properties once configuration settles (`projectsEvaluated` +
  `TaskProvider.configure` for the per-module dump so dependency projects'
  variants exist; `afterEvaluate` for resolve/upgrade-check root wiring, after
  user build-script config). Artifact lists flow through
  `ArtifactCollection.getResolvedArtifacts().map(static method ref)` into
  serializable record entries — mapper lambdas must capture nothing or the
  cache cannot serialize the provider.
- The root tasks are soft-ordered (`uikaUpgradeCheck.mustRunAfter(merge,
  resolve)`) so dump/resolve/check compose into a single invocation, paying one
  configuration instead of one per task. Standalone invocations are unaffected;
  keep the ordering when reshaping the tasks.
- The resolution provider refuses ANY query (configuration or execution time)
  while a producer task has not run ("Querying the mapped value ... before task
  ':lib:jar' has completed"). The default dump is safe because the
  uikaBuildOutputs dependsOn builds the producers first; the
  `-PuikaBuildOutputs=false` resolution-only dump instead iterates
  `ArtifactCollection.getArtifacts()` directly at configuration time (plain
  eager resolution has no producer guard) and stores the extracted entries.
  Do not "simplify" the two paths into one provider.
- Rehydration's missing notations come from the input dump's content, so the
  detached configurations are created at configuration time and the input file
  must exist before the build starts. The content is read through
  `providers.fileContents` so it is a tracked configuration input (a cached
  entry is never reused for a changed dump). If the file appears mid-build, the
  task fails with an explicit message (`getWiredAtConfiguration()`), never
  silently skips fetching. The CLI-zip detached configuration is wired in
  `afterEvaluate` from the final `cliVersion` (register action would miss
  `configureEach` overrides); `platformClassifier()` is guarded there because
  wiring runs on any task realization (IDE sync, `gradle tasks`) and an
  unsupported platform must only fail the task action.
- `DumpFormat` changes propagate to all three plugins via source inclusion from
  `jvm-plugin-core/` — no core artifact to publish.
- The upgrade-check tasks (`uikaUpgradeCheck`, Maven `uika:upgrade-check`)
  resolve `net.exoego.uika:uika-cli:<version>:<platform>@zip` through the build's own
  repositories and run it (`UikaCli` in core). The CLI version must keep
  defaulting to the plugin's own version — Implementation-Version manifest
  attribute in the Gradle/sbt jars, `${plugin.version}` in Maven — so one
  coordinate bump updates both; never hardcode a CLI version or URL.
- CLI output must flow through each tool's logger (the line consumer passed to
  `UikaCli.runUpgradeCheck`). Never revert to `inheritIO`: a child process
  inheriting file descriptors writes past the tool's log capture, and under a
  Gradle daemon, sbt server, or mvnd the report silently disappears.
- The upgrade-check tasks pass `--jdk-release` by default (the CLI keeps it
  opt-in; the plugins run on a JVM, which is exactly the environment where a
  default is safe). Derivation: Gradle root toolchain/targetCompatibility,
  Maven `maven.compiler.release`/`.target` (1.x values skipped), sbt the build
  JVM; all clamped by `UikaCli.effectiveJdkRelease` to the build JVM's ct.sym
  ceiling (feature - 1) and skipped with a log line when no ct.sym exists.
  Clamping down is the conservative direction (missing-on-both-sides stays
  unreported). The build JVM's home is exported as UIKA_JDK so the child CLI
  never depends on the caller's JAVA_HOME. Opt out with 0.
- Runtime load evidence is ONE knob per tool pointed at one directory, serving
  both phases (collect on the base branch's test run, consume on the PR's
  check): Gradle `-PuikaClassLoadLog` (bare value defaults to
  `build/uika/class-load`; the value must be a directory, an existing-file
  value fails fast at configuration), sbt `uikaClassLoadLog := Some(dir)`,
  Maven `-Duika.classLoadLog` plus a hand-written `argLine` for collection (no
  mojo can inject into surefire), so Maven alone bypasses the shared composer —
  its README/javadoc recipe must be kept in sync with it by hand. Gradle and
  sbt compose the test-JVM argument via `UikaCli.classLoadLogJvmArg` in core —
  per-process `%p` file names because `-Xlog` truncates a shared file on open,
  and the value is quoted when the path carries a colon (a Windows drive; the
  JVM strips the quotes, verified against a real -Xlog run). Gradle injects via
  `withType(Test).configureEach` using `jvmArgumentProviders` (a build script's
  `jvmArgs = [...]` setter silently wiped a plain jvmArgs injection) plus
  `upToDateWhen(false)`/`doNotCacheIf` (provider args are not fingerprinted,
  and an UP-TO-DATE or FROM-CACHE test forks no JVM, so a collect run would
  upload an empty artifact with no symptom) plus a doFirst mkdirs lambda
  capturing a plain File — all verified configuration-cache safe on 9.7.0, and
  the mkdirs is load-bearing (-Xlog aborts JVM startup on a missing directory).
  sbt appends to `Test/javaOptions`, which only reaches tests under
  `Test/fork := true`; both sbt halves absolutize the directory (a relative
  value split across launch dir vs each fork's baseDirectory), and the
  buildSettings task reads the keys via `LocalRootProject / ...` so the
  README's bare `uikaClassLoadLog := Some(...)` (root-project scope) reaches
  the check, not only ThisBuild.
  `UpgradeCheckTask.getDraftExcludeFile` is `@Internal`, NOT `@OutputFile`:
  declaring an output made a second invocation UP-TO-DATE and silently skipped
  the whole check (caught by `configurationCacheReusesUpgradeCheck`, which sets
  the draft property for exactly that reason).
- Their tests stub uika-cli with a shell-script ZIP in a file-based Maven repo
  (Gradle TestKit + sbt scripted + Maven invoker; invoker needs `-U` because
  target/it-repo caches resolution failures across runs, and its pre-build
  hook script must be named `prebuild.groovy`). An edited stub is shadowed by
  two caches, so the upgrade-check prebuild purges both: the clone's `target/`
  (extractBinary skips an already-extracted binary) and the it-repo `uika-cli`
  entry (Maven never re-fetches a cached release version).
- Run builds via `make gradle-check` / `make sbt-scripted` / `make
  maven-verify` (mise-pinned). Without mise, any target project's Gradle
  wrapper works: `/path/to/project/gradlew -p gradle-plugin publishToMavenLocal`.
- Do not add an explicit toolchain: the plugin intentionally compiles with the
  JVM running Gradle plus `options.release = 17`, because toolchain
  auto-resolution is not available in every target environment.
- The module dump task depends on its configuration and the main source-set
  output by default (`-PuikaBuildOutputs=false` opts out), so project jars and
  module classes exist when the CLI scans. The dependsOn wiring uses lazy
  providers because the java plugin may not be applied at registration time.
  Project-dependency artifacts are written with a `"project"` key
  (ProjectComponentIdentifier path); external ones keep coordinates only.
- sbt: `internalDependencyClasspath` entries are emitted as coordinate-less
  artifacts in each module's list. They are NOT in `update.value`, and without
  them per-module checking cannot resolve inter-module references (the merged
  check never noticed because every module contributes its own classesDirs to
  the union). Evaluating the dump task compiles those siblings.
- Maven: reactor dependencies are attributed with `"project"` and are never
  dropped from the dump; an unpackaged sibling falls back to its output
  directory. Keeping their coordinates is safe because project-attributed
  coordinates are excluded from the version DIFF (they stay in the version
  maps on purpose -- suggest's file->coordinate attribution reads them).

