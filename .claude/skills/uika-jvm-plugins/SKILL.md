---
name: uika-jvm-plugins
description: Invariants for the uika Gradle, sbt, Maven, Mill and Leiningen build-tool plugins, the Clojure CLI tool, the Bazel rules, and the shared jvm-plugin-core - task shape, coordinate handling, CLI fetch and version defaulting, logger wiring, --jdk-release derivation, and how the tests stub the CLI. Load before changing anything under gradle-plugin/, sbt-plugin/, maven-plugin/, mill-plugin/, clojure-tool/, lein-plugin/, bazel-rules/, or jvm-plugin-core/.
---

# uika JVM Build-Tool Plugin Notes

## Any Build That Compiles jvm-plugin-core

- No `record` in these sources, and nothing else newer than the sbt plugin's Scala
  can parse. sbt compiles them through zinc, and Scala 2.12's Java source parser
  does not understand record declarations. The symptom is not a syntax error: the
  declaration is skipped and the first USE fails with "not found: type X", which
  reads like a missing import. Plain final classes with explicit accessors, as
  `ClasspathDump.Artifact` and `UikaCli.JdkSource` do it.

- It MUST set an explicit javac release floor of 17, and guard it. Gradle uses
  `options.release`, Maven `maven.compiler.release`, Mill and sbt
  `javacOptions ++= Seq("--release", "17")`. Without one javac targets whatever JDK
  runs the build, and the plugin jar dies with UnsupportedClassVersionError on every
  older build daemon -- the released sbt 0.8.0 shipped `UikaCli.class` as class-file
  major 65, needing a JDK 21 sbt, while Gradle and Maven ran fine on 17. Nothing in a
  new build catches this by itself, which is why it is written here and not only in
  the build files. Mill guards it with a unit test reading the class-file major
  version; sbt with a `checkClassFileVersions` task, because that build has no test
  source set (its tests are scripted builds, which could only inspect a resolved jar).
- 17 is the true floor, not a convention: the core uses arrow switch and pattern
  `instanceof`. Raising it means raising the floor every plugin advertises.

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
- `DumpFormat` changes propagate to all four plugins via source inclusion from
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
  default is safe). It is derived from what the MODULES compile for, never the
  build JVM alone, and from the LOWEST across them. Lowest because
  `--jdk-release` is ONE process-global flag for a run that checks every module,
  so a mixed-toolchain build has no single right answer. Under-claiming turns a
  member into NotFound on both sides and it stays unreported as Unknown, while
  over-claiming makes a member the runtime lacks resolve cleanly and loses the
  finding with nothing to show. Reading only the root/aggregator was the old bug,
  because a multi-module root usually declares no target at all and fell straight
  through to the build JVM.
- The DUMP carries the release too, and per module (`ClasspathDump.Module`'s fourth
  argument, written by the same per-project derivation the flag defaults from). The
  flag stays one value because the layer it switches on is process-wide; the dump can
  afford one each, and upgrade-check uses them to scope a JDK move to the modules that
  made it. `DumpFormat.dumpRelease` is the dump-level value, the lowest of them, and
  the fallback for a module that names none. Do not write `buildJvmRelease()` there
  again: recording the WRITING JVM was issue #128, where a build-image bump
  manufactured a JDK-pair run the application never had while a real application JDK
  upgrade went unseen. It stays the fallback only because a module that declares no
  target really does compile against the build JVM. Gradle emits the release on every
  module it dumps (`getTargetCompatibility()` falls back to the toolchain, so a Java
  project always names one); the Clojure frontends write the same number on their
  single module and on the dump.
- Each tool's release knob (`jdkRelease` / `uikaJdkRelease` / `<jdkRelease>` /
  `:jdk-release`) feeds the DUMP as well as the flag, through
  `UikaCli.overrideRelease` (`core/override-release` in Clojure). It is the only way a
  build can state a runtime the derivation cannot see, such as compiling `--release 11`
  and shipping on 21, and it replaces every module's value rather than sitting beside
  them because it is a statement about the whole build. Zero keeps its old meaning of
  switching the API layer off and leaves the dump derived, so do not fold the two
  meanings together. Mill and the Clojure tool take it on the dump command itself
  (`--jdkRelease`, `:jdk-release`), since neither has a build-wide setting to read.
- Each plugin reads the spelling that pins the API, never the one that names the
  COMPILER. Gradle takes `compileJava`'s `options.release` else
  `targetCompatibility`, over `getAllprojects`, and deliberately NOT the
  toolchain: Gradle's own recommended shape pairs a 21 toolchain with an 11
  target, and reading the toolchain claimed 21 for bytecode that runs on 11
  (`getTargetCompatibility()` already falls back to the toolchain when nothing
  else is set, so nothing is lost). Maven takes maven-compiler-plugin's
  `<release>`/`<target>`, including per-execution ones, else
  `maven.compiler.release`/`.target`, over `getAllProjects`, skipping
  pom-packaged projects because a BOM compiles nothing and would otherwise drag
  the whole reactor under. sbt and Mill parse the raw option lists through the
  one shared `UikaCli.declaredRelease`, which handles the `--release=N` form
  javac also accepts, scalac's `-release`/`-java-output-version`, and normalizes
  `1.8`/`jvm-1.8` to 8 rather than dropping it (8 IS servable, and dropping it
  made an all-Java-8 build fall through to the build JVM). Anything below 8 is
  reported as no declaration at all, so one legacy module cannot drag the
  minimum under the floor and switch the layer off for the whole build. sbt must
  read the Compile configuration axis as well as the project axis, since
  delegation runs Compile -> Zero and never the reverse; Mill must read
  `mandatoryJavacOptions` as well as `javacOptions`, since it compiles with both.
- The result is clamped by `UikaCli.effectiveJdkRelease` to the ct.sym ceiling of
  the `JdkSource` passed in (feature - 1) and skipped with a log line when that
  JDK has no ct.sym. That same JdkSource's home is exported as UIKA_JDK, and the
  two must never be split, or the flag claims a release the CLI's ct.sym cannot
  serve. `clojure-tool/src-core/exoego/uika/core.clj` carries a hand port of the
  clamp for the lein plugin and the Clojure tool; its messages name the JDK's
  home, never "the build JVM", because for lein those two differ by design.
  Opt out with 0.
- `uikaUpgradeCheck` is NOT aggregated in sbt (`uikaUpgradeCheck / aggregate :=
  false`). Every value it reads is ThisBuild- or root-scoped and the dumps
  already cover the whole build, so aggregating spawned one identical CLI run
  per project, in parallel, racing on the shared retrieve directory and on the
  JFR work directory whose stale-conversion sweep deletes a sibling's fresh
  output. For the same scoping reason the task reads `uikaJdkRelease` through
  `LocalRootProject /`, as it already did for `uikaJfr`.
- Runtime load evidence is ONE knob per tool pointed at one directory, serving
  both phases (collect on the base branch's test run, consume on the PR's
  check): Gradle `-PuikaJfr` (bare value defaults to `build/uika/jfr`; the
  value must be a directory or a `.jfr` recording — any other existing-file
  value fails fast at configuration, and a DIRECTORY named `x.jfr` still
  counts as a directory), sbt `uikaJfr := Some(dir)`, Maven `-Duika.jfr` plus
  a hand-written `argLine` for collection (no mojo can inject into surefire),
  so Maven alone bypasses the shared composer — its README/javadoc recipe must
  be kept in sync with it by hand. Collection is JFR, not -Xlog, on purpose:
  `jdk.ClassLoad` with stacks is an information superset of both -Xlog
  variants (stacks for every class, no single-substring filter, JDK 17+
  instead of 22+ for triggers), JFR generates pid-unique file names for a
  directory-valued `filename` (no `%p`, no Windows colon quoting — but a COMMA
  in the path is the option delimiter, silently truncating `filename=` with
  exit 0, so the composer quotes the value exactly when it carries one), and
  the known trade-offs are bounded (maxsize rotation defaults to 250MB, far
  above class-load volume; a SIGKILLed fork loses its recording, which
  promote-only absorbs; stackdepth 64 truncates the OUTER frames only — pinned
  by `deepCallerStacksKeepTheTriggerFrames`). Gradle and sbt compose the
  test-JVM argument via `UikaCli.jfrClassLoadJvmArg` in core, and both decide
  the consumption-only skip via `JfrEvidence.valueNamesRecording` (also core,
  and announced with a log line — the skip is otherwise a symptomless empty
  collect run); the test JVMs
  need JDK 17+ (the event-settings syntax). Gradle injects via
  `withType(Test).configureEach` using `jvmArgumentProviders` (a build
  script's `jvmArgs = [...]` setter silently wiped a plain jvmArgs injection)
  plus `upToDateWhen(false)`/`doNotCacheIf` (provider args are not
  fingerprinted, and an UP-TO-DATE or FROM-CACHE test forks no JVM, so a
  collect run would upload an empty artifact with no symptom) plus a doFirst
  mkdirs lambda — all verified configuration-cache safe on 9.7.0, and the
  mkdirs is load-bearing (a missing PARENT aborts JVM startup, but a missing
  leaf directory under an existing parent makes JFR silently record to a
  single clobbered FILE at that path — the Maven recipe says "create it
  first" for the same reason).
  sbt appends to `Test/javaOptions`, which only reaches tests under
  `Test/fork := true`; both sbt halves absolutize the directory (a relative
  value split across launch dir vs each fork's baseDirectory), and the
  buildSettings task reads the keys via `LocalRootProject / ...` so the
  README's bare `uikaJfr := Some(...)` (root-project scope) reaches the
  check, not only ThisBuild.
  `UpgradeCheckTask.getDraftExcludeFile` is `@Internal`, NOT `@OutputFile`:
  declaring an output made a second invocation UP-TO-DATE and silently skipped
  the whole check (caught by `configurationCacheReusesUpgradeCheck`, which sets
  the draft property for exactly that reason).
- Recordings are converted PLUGIN-side
  (`JfrEvidence` in core, `jdk.jfr.consumer.RecordingFile`), never CLI-side:
  the CLI is JVM-free and must not read binary JFR. The converter emits the
  CLI's own trusted text shapes (`[class,load] name` per stackless event, a
  `Java stack when loading X:` block per stacked one), so the whole evidence
  pipeline including trigger composition is reused unchanged — and unlike
  -Xlog cause stacks, jdk.ClassLoad stacks start at the loading call site with
  no defineClass machinery on top. A `.jfr` knob value is consumption-only
  (Test-JVM injection is skipped for it; tests cannot record into an existing
  recording); recordings inside the directory are found following symlinks
  (the CLI follows them too) and by content, not name alone (`FLR\0` magic —
  `jcmd JFR.dump` and a file-valued `filename=` write suffixless recordings),
  then converted with the binary left in place (the CLI skips `.jfr` names in
  its directory walk), and any text logs in the directory still reach the CLI
  as-is (bring-your-own -Xlog). Conversion dedups per batch to exactly what
  the CLI keeps (first bare line per class, first framed stack block —
  evidence.rs is or_insert / first-framed-wins, so every test fork repeating
  the JDK load set would otherwise write hundreds of MB the CLI drops), a
  truncated or unreadable recording is logged and skipped, never fatal (a fork
  killed mid-dump must not cost the intact recordings' evidence), and stale
  `jfr-*.log` conversions are deleted from the workdir (`JfrEvidence.WORK_DIR_NAME`,
  shared by all four plugins) before converting, since pid-unique recording
  names would otherwise accumulate orphans. Every conversion logs
  its event count because the event is disabled in the default JFC profile and
  an empty conversion is otherwise symptomless. Tests must record REAL
  recordings with a runtime-compiled probe class loaded through a fresh
  URLClassLoader (`JfrTestRecordings`): a nested test class cannot serve —
  JUnit discovery loads nested classes via getDeclaredClasses() before any
  test body runs, so its load never lands in the recording.
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


## Mill Plugin Notes

Most Mill invariants live as comments at their point of use (`Uika.scala`,
`UikaTestModule.scala`, `build.mill`) or are locked by tests in `UikaTests.scala`.
This section keeps only what neither can hold.

- `Task.ctx().workspace`, NEVER `BuildCtx.workspaceRoot`, for the default output
  path and for resolving relative arguments. The latter is the launcher's root and
  does not follow `UnitTester`, so the bug shows as tests that still pass while the
  dump lands in the plugin's own tree.
- The command macro lifts every statically visible `task()` call into an
  UNCONDITIONAL edge, so a `defaultResolver()` in a dead fallback branch is still
  built on every run. Tasks on runtime-valued modules cannot be lifted at all;
  collect them with `Task.traverse(...)` outside the body.
- `mill.api.ExternalModule.Alias` does NOT give a working short selector (`uika/`,
  `uika.`, and `build.uika/` all fail to resolve). Do not document one.
- No mise backend installs the Mill launcher (`ubi:`/`github:` find no matching
  release asset). The committed `mill-plugin/mill` bootstrap script reads the
  `//| mill-version:` header in `build.mill`, so mise only supplies the JVM.

## Clojure Tool Notes

Same rule as the Mill section: point-of-use comments and the tests in
`clojure-tool/test/` hold the invariants. Only the experiment-only lessons live here.

- To resolve ANOTHER project's basis, wrap `create-basis` in
  `clojure.tools.deps.util.dir/with-dir` and keep `:project "deps.edn"` bare. Every
  relative path resolves against `*the-dir*` -- a dir-joined `:project` resolves twice
  and silently falls back to the root deps (org.clojure/clojure alone in the dump is
  the symptom). Relative `:local/root` entries need the same binding.
- tools.deps resolves jar artifacts only; a zip-packaged classifier artifact like the
  uika-cli distribution cannot go through it. The tool downloads from Maven Central
  directly (`UIKA_CLI_URL` overrides), unlike the JVM plugins which reuse the build's
  resolver.
- The tool is distributed as a git dep (`:deps/root "clojure-tool"`), costing nothing
  against the Central release quota. The CLI version default comes from the tool's own
  `:git/tag` in `clojure.java.basis/current-basis` -- a tool installed from a tag knows
  the matching CLI release with no stamping step. A `:local/root` install has no tag,
  hence `:cli-version` / `UIKA_CLI_VERSION`.
- jvm-plugin-core is NOT shared here: tools.deps does not compile Java sources and a
  prep step would burden every consumer, so the small ports (classifier, ct.sym clamp,
  extract) live in the ns with "keep in sync" markers. JFR conversion is deliberately
  absent until that changes.

## Bazel Rules Notes

Point-of-use comments and `bazel-rules/it/` hold the invariants. Only what neither can
hold lives here.

- The dump is written by `bazel run`, NEVER by a build action. It names absolute paths,
  and an action's output is cacheable and may be replayed into another output base or
  served from a remote cache, so an action that wrote one would hand a second machine
  another machine's paths. `bazel run` lays the classpath out as runfiles, and
  `toRealPath` on those symlinks is where the absolute paths come from, with no
  `bazel info execution_root` to guess at (a nested `bazel info` inside a `bazel run`
  would block on the server lock anyway).
- Coordinates come from the `maven_coordinates=` TAG, not from a provider, because
  rules_jvm_external exposes none. That is deliberate rather than a workaround: the same
  tag is what its own `java_export` and `pom_file` read, so anything carrying it is
  attributed and the integration test needs no resolver and no network.
- A `java_binary`'s `JavaInfo.transitive_runtime_jars` is EMPTY. Its classpath is in
  `JavaRuntimeClasspathInfo` instead. The symptom is a dump with no artifacts at all and
  no error, so `_runtime_jars` in `private/dump.bzl` checks that provider first.
- `build_outputs = False` skips GENERATED jars, never main-repository ones. A vendored
  jar in the main repository needs no build and carries the coordinates the version diff
  runs on, so a main-repository test would empty a baseline dump of the artifacts it
  exists for.
- The release derivation is single-sourced through the SAME manifest rule the dump uses,
  in a `releases_only` mode. Reading only the Java toolchain would be shorter and wrong,
  because a target that pins a lower release than the toolchain would then be
  over-claimed, and over-claiming drops findings silently.
- `javacopts = ["--release", "17"]` on the ruleset's own java_library targets is not
  decoration. Bazel's default `--java_language_version` is 11, so without it the core's
  arrow switches do not compile in a user's workspace. Bazel strips the toolchain's
  `-source`/`-target` when javacopts name `--release`, so the two do not conflict.
- The integration test cleans the output base before asserting that
  `build_outputs = False` built nothing. The workspace copy is recreated per run but its
  output base is keyed by that stable path and is not, so the assertion would otherwise
  read the jars the previous run left behind and pass unconditionally.
- JFR collection needs `--nocache_test_results`: a cached test forks no JVM and records
  nothing, with no symptom. It also needs `--sandbox_writable_path`, since the recording
  lands outside the sandbox on purpose. The `jfr-jvmopt` subcommand prints the flag AND
  creates the directory, so the README recipe cannot drift from
  `UikaCli.jfrClassLoadJvmArg` the way Maven's hand-written argLine can.
- The release archive is cut with `cp -RL` (`make bazel-stage`). The four jvm-plugin-core
  sources under `bazel-rules/java` are committed symlinks pointing OUT of the module root,
  which is fine in this repository and useless to a consumer.
- The archive is built from the tag before the CLI binaries exist, so `@uika_cli` cannot
  carry their checksums and downloads unpinned by default. A build that wants them states
  them itself through the `uika.cli` tag.

## Leiningen Plugin Notes

Point-of-use comments and `lein-plugin/it/run.sh` hold the invariants; only the
experiment-only lessons live here.

- The staged pom must not carry `<repositories>`: PomChecker rejects it outright
  ("The <repositories> block should not be present"), so `jreleaser release` aborts
  the whole all-or-nothing deployment, and nothing before the release run executes
  that path. `:repositories ^:replace []` in project.clj is NOT the fix -- it also
  empties the map lein resolves the plugin's own deps from, which fails on the cold
  `~/.m2` a release runner always has. `lein update-in :repositories empty -- deploy
  staging` works because `:eval-in-leiningen` has already put those deps on the
  classpath before update-in rewrites the map.
- `lein deploy` prompts for credentials unless the repo URL matches
  `#"(file|scp|scpexe)://"` -- note the DOUBLE slash, which `file:target/...` does not
  have. That is why it "worked twice, then hung twice" on identical invocations:
  the prompt always fires, only the blocking depends on the stdin shape. `:no-auth
  true` is the fix. Spelling the URL `file://target/...` is not: `target` becomes the
  URI authority and the deploy lands in `/staging-deploy`.
- `lein compile` is NOT enough to build the outputs a dump should record. It
  short-circuits when `:aot` yields no stale namespace and so never reaches
  `eval/prep`, the only thing that runs `:prep-tasks` -- a `:java-source-paths`
  project would never run javac. `leiningen.core.eval/prep` is the task-agnostic
  entry point and subsumes the compile call.
- `:eval-in-leiningen` pins the plugin to LEIN's JVM, while project code runs on
  `(or (:java-cmd project) JAVA_CMD "java")` (leiningen.core.eval, eval.clj:254).
  Both the dump's `jdkRelease` and the `--jdk-release` default have to describe the
  latter, so the plugin probes it with `-XshowSettings:properties -version`.
  Measured before the fix, with `:java-cmd` on a 25 and lein on a 21: the dump said
  21 and the flag came out 20 instead of 24. The ct.sym ceiling and the `UIKA_JDK`
  export must come from that same JVM, since UIKA_JDK is the ct.sym the CLI reads.
  All of that holds while the probe SUCCEEDS; a JVM that cannot be run or whose
  output carries no `java.home` falls back to lein's own with a warning, because a
  failed probe must not fail the check. The parse lives in
  `core/parse-jvm-properties` so it can be unit-tested: values run to end of line,
  since a `java.home` containing a space (`C:\Program Files\...`) truncated at the
  space makes the ct.sym probe miss and the layer switch off blaming a missing
  ct.sym.
- mise's leiningen `2.13.0` package is broken (its script 404s on a
  `2.12.1-SNAPSHOT` standalone jar); `.mise.toml` pins 2.12.0 and says why.
