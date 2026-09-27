# Notes for Agents: JVM build-tool integrations

Rules every integration shares. Each tool's own notes live in its directory's
`AGENTS.md`, which imports this file.

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
  the build files. Every build guards it: Mill with a unit test reading the class-file
  major, sbt with `checkClassFileVersions` (its tests are scripted builds, which could
  only inspect a resolved jar), Gradle and Maven with ONE shared sweeping test
  (`jvm-plugin-core/src/test/java`, mounted into both like the main sources -- a
  per-build twin whose bound went stale would never fail), the clojure-tool suite
  reading the javac'd JfrEvidence off target/core-classes, and the lein IT reading it
  from target/classes. The guards sweep or pin the BUILD's own output, never ~/.m2,
  and fail on an empty read: BSD od's trailing line once turned the lein guard into a
  permanent no-op on macOS, and Maven's incremental compiler ignores a changed
  `maven.compiler.release`, which is why `make maven-verify` cleans first.
- 17 is the true floor, not a convention: the core uses arrow switch and pattern
  `instanceof`. Raising it means raising the floor every plugin advertises.

## Every Plugin

- `DumpFormat` changes propagate to all four plugins via source inclusion from
  `jvm-plugin-core/` — no core artifact to publish.
- The upgrade-check tasks (`uikaUpgradeCheck`, Maven `uika:upgrade-check`)
  resolve the pure-Java CLI jar `net.exoego.uika:uika-cli:<version>:jvm@jar` through the
  build's own repositories and run it from the tool's cache (`UikaCli` in core). Nothing
  is extracted and no platform is selected. The jar is a CLASSIFIED artifact
  (`UikaCli.JAR_CLASSIFIER`) and the POM keeps `pom` packaging, on purpose: Central
  demands a sources and a javadoc jar for any other packaging, so a main jar would cost
  twelve files per release against four. Do not "tidy" it into the main artifact. The
  stub repositories write `<packaging>pom</packaging>` too, so the suites resolve the
  shape a release really has.
  The CLI version must keep
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
  single module and on the dump. Maven's dump (`JdkReleases.moduleRelease`) gives a
  non-pom module that declares nothing the JVM running Maven, since in-process javac
  targets it. Recording nothing left it on the dump-level value, a sibling's declared
  release whenever one declared any. It still records nothing when javac may run on
  another JDK (maven-toolchains-plugin present, `<jdkToolchain>`, or fork plus
  `<executable>`), because that JDK's release is not read. The FLAG (`lowest`) stays
  on declared releases only.
  sbt gives a module that declares nothing the release of
  its compiling JDK, read from the `release` file of `Compile / javaHome` when set
  (`UikaCli.installedRelease`), else the JVM running sbt. Left without one, such a module
  took the dump-level value, the LOWEST sibling's declaration, so a CI JDK move went
  unchecked for it while a sibling's move was checked in its name. An unreadable javaHome
  records nothing rather than guessing the build JVM. The flag takes the same compiling
  JDKs into its minimum, which only moves it when javaHome names a JDK older than every
  declared release, because a JDK cannot compile for a release newer than itself.
  Mill writes, for a module that declares nothing, the
  release in its `javaHome()`'s `release` file (what `jvmVersion` resolves to, and what
  Mill compiles it with), else the Mill JVM's. Left empty, the CLI gave it the dump-level
  value, a SIBLING's lowest declared release. The Mill `--jdk-release` default still reads
  declared releases only, so an undeclared module on a lower `jvmVersion` can be
  over-claimed by the flag. Kept that way for now: reading `javaHome()` in the check would
  resolve, and may download, that JDK on the machine running the check.
- Each tool's release knob (`jdkRelease` / `uikaJdkRelease` / `<jdkRelease>` /
  `:jdk-release`) feeds the DUMP as well as the flag, through
  `UikaCli.overrideRelease` (`core/override-release` in Clojure). It is the only way a
  build can state a runtime the derivation cannot see, such as compiling `--release 11`
  and shipping on 21, and it replaces every module's value rather than sitting beside
  them because it is a statement about the whole build. Zero keeps its old meaning of
  switching the API layer off and leaves the dump derived, so do not fold the two
  meanings together. The Clojure tool takes it on the dump command itself
  (`:jdk-release`), since it has no build-wide setting to read. Mill reads it from the
  one `UikaModule` setting both commands share.
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
- A knob's name is its CLI FLAG's name, written the way the tool writes names, and the
  `uika` prefix appears exactly where the namespace is flat and shared with the whole
  build. `-PuikaFailOn`, `uikaFailOn` and `-Duika.failOn` carry it (Gradle project
  properties, sbt's `autoImport`, and system properties are one space every plugin shares);
  the Gradle `uika {}` extension, the Maven POM element, Mill's `UikaModule` settings, the two
  Clojure maps and the Bazel macro's parameters do not, since each already sits inside something
  uika owns. Renaming a knob away from its flag would break the mechanical correspondence
  the clojure-tool sync test checks, so do not do it for readability alone -- rename the
  CLI flag instead, which is how `--merged` became `--merged-classpath`.
- `--json` and `--verdicts-json` stay CLI-only ON PURPOSE. The user docs only say that no
  plugin exposes them. `--json` writes the report to stdout, and no integration passes that
  stream on untouched. Each merges the CLI's stderr into it and adds its own `uika:` lines,
  and the Gradle, sbt, Maven and Mill plugins print through a logger that may prefix every
  line (`[INFO]` in Maven). A report DESTINATION the plugins could point at a file is the
  missing piece, and this flag is not it. `--verdicts-json` is an evaluation
  stream for `tools/jvm-probe` -- written before exclude filtering, without graph-walk
  violations, call-site duplicates not deduped -- so it is not a report a build acts on.
- Runtime load evidence is ONE knob per tool pointed at one directory, serving
  both phases (collect on the base branch's test run, consume on the PR's
  check): Gradle `-PuikaJfr` (bare value defaults to `build/uika/jfr`; the
  value must be a directory or a `.jfr` recording — any other existing-file
  value fails fast at configuration, and a DIRECTORY named `x.jfr` still
  counts as a directory), sbt `uikaJfr := Some(dir)`, Maven `-Duika.jfr` plus
  a hand-written `argLine` for collection (the plugin has no goal that sets
  surefire's `argLine` the way jacoco's prepare-agent does, and a POM
  `<argLine>` ignores both that property and a command-line `-DargLine`),
  so Maven alone bypasses the shared composer — its `docs/maven.md`/javadoc
  recipe must be kept in sync with it by hand. Collection is JFR, not -Xlog, on purpose:
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
  `docs/sbt.md`'s bare `uikaJfr := Some(...)` (root-project scope) reaches the
  check, not only ThisBuild.
  `UpgradeCheckTask`'s `draftExcludeFile` must never be an output:
  declaring an output made a second invocation UP-TO-DATE and silently skipped
  the whole check (caught by `configurationCacheReusesUpgradeCheck`, which sets
  the draft property for exactly that reason).
- Recordings are converted PLUGIN-side
  (`JfrEvidence` in core, `jdk.jfr.consumer.RecordingFile`), never CLI-side:
  the CLI must not read binary JFR. The converter emits the
  CLI's own trusted text shapes (`[class,load] name` per stackless event, a
  `Java stack when loading X:` block per stacked one), so the whole evidence
  pipeline including trigger composition is reused unchanged — and unlike
  -Xlog cause stacks, jdk.ClassLoad stacks start at the loading call site with
  no defineClass machinery on top. A `.jfr` knob value is consumption-only
  (Test-JVM injection is skipped for it; tests cannot record into an existing
  recording); recordings inside the directory are found following symlinks
  (the CLI follows them too) and by content, not name alone (`FLR\0` magic —
  `jcmd JFR.dump` and a file-valued `filename=` write suffixless recordings),
  then converted with the binary left in place (the CLI's walk skips them by
  name or magic), and any text logs in the directory still reach the CLI
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
- A CLI path ending in `.jar` is the pure-Java CLI, and `UikaCli.launchCommand` puts a JVM
  in front of it: the JVM running the build, never the `JdkSource` one, which is the API
  the application is checked against and may be older than the 17 the jar needs. It passes
  the jar launcher's flags itself with `-Duika.child=true`, because the jar otherwise
  re-runs itself in a child JVM to get them and the parent idles for the whole run. That
  makes the flags a hand copy of `Launcher.flags` in cli-java, where the measurements
  live. `LauncherFlagsSyncTest` reads the launcher's source and fails on drift, so retune
  the flags there and let the test point here. `overrideFrom` asks a jar for no
  executable bit, since no download leaves a jar with one.
- `UIKA_CLI_PATH` goes through `UikaCli.overrideFrom` in every integration, the Bazel
  check binary and both Clojure front ends included, so a path that is not an
  executable FILE fails naming the knob instead of inside ProcessBuilder. Losing the
  executable bit is the everyday case, not a corner one: actions/upload-artifact does
  not preserve it, and shipping the binary as an artifact is the documented air-gapped
  route. The Clojure port names the SOURCE (`:cli-path` or the variable) rather than
  always the variable, since blaming the environment for a project.clj value sends the
  reader to inspect something that was never read. Testing the plumbing needs a
  different seam per tool and two of them have none: `System.getenv` is unstubbable,
  so Gradle wires the value through `providers.environmentVariable` and Mill through
  `Task.env`, the Maven invoker sets it with `invoker.environmentVariables.*` (3.2.2+,
  per project), the Bazel and lein ITs are shell scripts that just export it, and sbt
  gets its OWN scripted group run by a second `make sbt-scripted` invocation, because
  scripted has no per-test environment hook (`scriptedLaunchOpts` is JVM options only)
  and exporting it for the whole run would defeat the resolver test in the `uika`
  group. The contract itself is pinned once in `jvm-plugin-core`'s
  `BinaryOverrideTest`, which the Gradle and Maven builds both run.
- Their tests stub uika-cli with a jar in a file-based Maven repo (Gradle TestKit + sbt
  scripted + Maven invoker + Mill; invoker needs `-U` because target/it-repo caches
  resolution failures across runs, and its pre-build hook script must be named
  `prebuild.groovy`). The stub is ONE class, `StubCli` in `jvm-plugin-core/src/test/java`.
  It packs its own class file into a runnable jar, so it must stay a single class file
  that needs nothing but the JDK: no lambda, no nested class, no record. Each build
  reaches it its own way. Gradle mounts the directory. The Maven invoker scripts get it
  through `addTestClassPath`. Mill's test module mounts that one FILE, since the rest of
  the directory is JUnit. An sbt scripted build's meta-build compiles the file named by
  the `uika.stub.source` property from `scriptedLaunchOpts`. It records argv, `UIKA_JDK`
  and the `uika.child` property next to the `--before` file. An edited stub is shadowed
  by the it-repo `uika-cli` entry (Maven never re-fetches a cached release version),
  which the upgrade-check prebuild purges. The `UIKA_CLI_PATH` tests keep shell stubs on
  purpose: they stand in for an executable.
- Where each build's UNIT tests live, and why sbt has none. Gradle and Maven mount
  `jvm-plugin-core/src/test/java` alongside the main sources, so one copy of the shared
  logic's tests runs in both. Maven adds `maven-plugin/src/test/java` for `JdkReleases`,
  which is Maven's alone: every other tool reads a flat option list through
  `UikaCli.declaredRelease`, while Maven walks maven-compiler-plugin's configuration per
  EXECUTION and skips pom packaging, none of it reachable from a list. Mill, the two
  Clojure front ends and the Bazel rules each have their own suite. sbt deliberately does
  NOT: every line of `UikaPlugin.scala` sits inside a `:=` body, driven by sbt values
  (`UpdateReport`, `ScopeFilter`) that a unit test would have to fabricate, and the one
  thing scripted genuinely cannot see is the compile output's class-file version, which
  `checkClassFileVersions` reads directly. Do not add an empty test source set there to
  even up a table.
- Run builds via `make gradle-check` / `make sbt-scripted` / `make
  maven-verify` (mise-pinned). Without mise, any target project's Gradle
  wrapper works: `/path/to/project/gradlew -p gradle-plugin publishToMavenLocal`.
- A project that compiles NOTHING is not a module, in every tool that can have
  one. It is not a cosmetic filter: upgrade-check makes each module its own
  check RUN, and a run whose module has no classesDirs has no application roots,
  so `reachable_axis_valid` degrades and `--fail-on reachable` fails on breaks
  the real modules proved unreachable. Measured on guava 22 -> 23 with
  selenium-remote-driver as the consumer: adding one classesDirs-empty module
  carrying the same two artifacts turned `⚠️ 2 not proven reachable` / exit 0
  into `💥 2 reachable` / exit 1, and doubled the reported scanned and
  unverified counts because the run rescans every jar. Each tool needs its OWN
  predicate: Gradle `emptyDump` (`javaExt == null && conf == null`, written as
  a blank fragment the merge skips), Mill by collecting only `JavaModule`, sbt
  by empty classesDirs (`uikaModuleClasspath` evaluates `Compile / products`,
  so nothing compiled means nothing to compile), Maven by `packaging == pom`
  (the goal has no lifecycle phase, so an unbuilt tree has empty classesDirs
  everywhere and that test would empty the whole dump). The sbt filter must not
  move `DumpFormat.dumpRelease`, so the release is computed over ALL modules
  and only the emitted list is filtered; Maven needs no such care because
  `JdkReleases.moduleRelease` already answers null for pom packaging.

## Every Tool's Doc Page

- One shape, in one order: setup, `## PR gate on GitHub Actions` (with
  `### Caching the baseline`), `## Options`, `## Runtime load evidence (JFR)`. A page may
  add sections after those (Bazel has three), but not reorder them. The gate comes before
  the knobs because it is what a reader arrives for, and drift here is invisible to every
  test, so it is only ever caught by reading all seven side by side.
- Every user-facing knob of a tool appears on its page. That includes the ones a build
  script reaches rather than the command line, which is how sbt's `uikaModuleClasspath` sat
  in `autoImport` undocumented through two audits.
- Both rules are enforced by `jvm-plugin-core`'s `DocPageContractTest` (run by the Gradle
  and the Maven build): the section order on all seven pages, and every knob scraped from
  each tool's source (Gradle property lookups, sbt keys, Maven `@Parameter` properties,
  Mill `UikaModule` settings and command parameters, the two Clojure key sets, the Bazel macro parameters) named on
  its page. A new knob fails that test until its page names it. The tool-by-option table
  and the "one tool only" list in `docs/build-tools.md` are the parity contract the audits
  used to reconstruct by hand; keep them current when a knob is added or renamed.
