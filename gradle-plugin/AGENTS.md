# Notes for Agents: gradle-plugin/

The shared plugin rules are in `jvm-plugin-core/AGENTS.md`. Claude Code imports it below.

@../jvm-plugin-core/AGENTS.md

## Gradle Plugin Notes

- Keep the module-task + root-merge shape (`uikaDumpModuleClasspath` per
  project, merged by root `uikaDumpClasspath`). A root task cannot safely
  resolve other projects' configurations at execution time, and the split
  avoids Gradle 9 exclusive-lock failures.
- Coordinates come from `ResolvedArtifactResult`; never recover them from file
  paths. The artifact views are lenient so unbuilt project dependencies are
  listed instead of failing the dump.
- A project dependency is dumped as DIRECTORIES, never its jar: two artifact views of
  the configuration, one asking `LibraryElements=classes` and one `resources`, select
  the secondary variants every Java project publishes on `runtimeElements`, while an
  external jar satisfies either request through Gradle's own compatibility rule (the
  compileClasspath mechanism). `toEntries` merges them per component, classes first, so
  the entry order stays the classpath order, and it takes external jars from the classes
  view only, since the resources view lists them again. The `dependsOn` wiring depends on
  the views' `artifactFiles`, which build compile and processResources of the producer
  and not its jar; depending on the Configuration itself builds the jar (measured with a
  dry run). A producer applying plain `java` still gets its jar built, by the consumer's
  own `compileJava`, which is Gradle's dependency and not ours; the fixture uses
  `java-library` for that reason. With outputs built, a project directory that does not
  exist is dropped from the dump (a module without resources never creates
  build/resources/main); in a resolution-only dump the unbuilt directories stay listed
  so the CLI's project fallback still has an entry to work from.
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
  silently skips fetching. The CLI jar's detached configuration is wired in
  `afterEvaluate` from the final `cliVersion` (the register action runs before the
  root build script's `uika {}` block).
- Users configure through ONE root extension, `uika {}` (`UikaExtension`), and every
  task reads it. Each `-Puika*` property is read once in `apply()` and WINS over its
  extension setting (`UikaPlugin.propertyOr`); a list knob (`excludeFiles`) is replaced,
  not appended to. The tasks' settings are private `Property` fields with
  package-private accessors, so a build script cannot set them and the extension stays
  the one place. That closed two traps: a value both commands need (`jdkRelease`, which
  the dump records and the check passes) lived on the check task alone, and a
  build-script task value silently beat the `-P` property. A private field is not an
  annotated input, so where the value changes a task's OUTPUT it is registered by hand
  (`DumpModuleClasspathTask`'s initializer block, `getInputs().property`). The upgrade-check
  task needs none: it has no outputs, so it always runs. No task setting stays public,
  not even a per-module `configurationName`: the user chose one way to configure over a
  per-module override, so `configuration` is build-wide.
- Do not add an explicit toolchain: the plugin intentionally compiles with the
  JVM running Gradle plus `options.release = 17`, because toolchain
  auto-resolution is not available in every target environment.
- The module dump task depends on its configuration and the main source-set
  output by default (`-PuikaBuildOutputs=false` opts out), so project jars and
  module classes exist when the CLI scans. The dependsOn wiring uses lazy
  providers because the java plugin may not be applied at registration time.
  Project-dependency artifacts are written with a `"project"` key
  (ProjectComponentIdentifier path); external ones keep coordinates only.
