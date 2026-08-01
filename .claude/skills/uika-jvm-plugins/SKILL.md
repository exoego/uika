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

