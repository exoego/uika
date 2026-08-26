# Uika (Unseen Incompatibility, Kick Away)

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fuika-cli%2Fmaven-metadata.xml&label=Maven%20Central)](https://central.sonatype.com/namespace/net.exoego.uika)

Ultra-fast and low-memory LinkageError checker for JVM.
Catches `NoSuchMethodError` and friends statically, before you ship.

## The problem to fix

When dependency resolution picks conflicting versions, an API that a library
was compiled against can vanish from the runtime classpath and fail at runtime
with `NoSuchMethodError` / `NoClassDefFoundError`.

With modern practice of using Dependabot, Renovate, or Scala Steward bumping
versions constantly, auditing transitive dependencies by hand does not scale.

Uika catches such `LinkageError`s at PR time by analyzing every class/method
reference recorded in the referencing binary's constant pool.

Detection covers:
- Class/Method removals
- Visibility narrowing (public -> protected -> private)
- Static <-> instance mismatches
- Newly-final classes/members
- Methods that became abstract
- `new` on a class that became abstract or an interface
- Class <-> interface flips
- Subclasses left out of a newly sealed type's `permits` clause
- Conflict of default methods from two unrelated interfaces at once
- `META-INF/services` providers that ServiceLoader can no longer find or
  instantiate (`ServiceConfigurationError` rather than a `LinkageError`)

## Prior art

API diff tools ([Revapi](https://revapi.org/), [japicmp](https://github.com/siom79/japicmp),
[roseau](https://github.com/alien-tools/roseau), [MiMa](https://github.com/scala-garden/mima))
report every API change between two versions of one library. They answer "what
changed in this library", not "which of those changes break **my** app".

Classpath validators (Google's [Linkage Checker](https://github.com/GoogleCloudPlatform/cloud-opensource-java),
Spotify's [missinglink](https://github.com/spotify/missinglink)) scan one fully
resolved snapshot. Every run therefore also surfaces pre-existing
inconsistencies, so a per-PR upgrade gate built on one tends to need a curated
exclusion list.

Uika does both halves in one step. It diffs the changed library old vs new,
then resolves each real reference on your classpath the way the JVM links, and
reports only breakage the upgrade itself introduced. That keeps a PR gate on
Renovate/Dependabot/Scala Steward bumps quiet with no exclusion list.

[BENCHMARKS.md](BENCHMARKS.md) has measured head-to-head runs against these
tools on the same inputs (wall time, peak memory, and what each one reports).

## Usage

Every recipe below drives the Gradle, sbt, Maven, Mill or Leiningen plugin, the
Clojure CLI tool or the Bazel rules, so declare it in your build first: see
[Build-tool plugins](#build-tool-plugins).

### PR gate on GitHub Actions (the main use case)

For Gradle:

```yaml
name: dependency binary incompatibility check
on: pull_request

jobs:
  upgrade-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      # ... You may need to setup Java/Gradle/Maven/Sbt here ....

      - name: Dump baseline classpath (base branch)
        id: baseline
        continue-on-error: true
        run: |
          git checkout ${{ github.event.pull_request.base.sha }}
          if ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/before.json -PuikaBuildOutputs=false; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Dump PR classpath
        run: ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/after.json

      - name: Check broken references
        if: steps.baseline.outcome == 'success'
        run: ./gradlew uikaUpgradeCheck -PuikaBefore=/tmp/before.json -PuikaAfter=/tmp/after.json
```

sbt, Maven, Mill and Bazel use the same three steps with different commands.

| Step | sbt | Maven | Mill | Bazel |
| --- | --- | --- | --- | --- |
| Baseline dump | `sbt uikaDumpClasspath && cp target/uika/classpath.json /tmp/before.json` | `mvn -q uika:dump-classpath -Duika.output=/tmp/before.json` | `./mill net.exoego.uika.mill.Uika/dumpClasspath --output /tmp/before.json` | `bazel run //:uika_resolution_dump -- --output /tmp/before.json --materialize /tmp/uika-baseline` |
| PR dump | `sbt compile uikaDumpClasspath && cp target/uika/classpath.json /tmp/after.json` | `mvn -q compile uika:dump-classpath -Duika.output=/tmp/after.json` | `./mill net.exoego.uika.mill.Uika/dumpClasspath --output /tmp/after.json` | `bazel run //:uika_dump -- --output /tmp/after.json` |
| Check | `sbt "uikaUpgradeCheck /tmp/before.json /tmp/after.json"` | `mvn uika:upgrade-check -Duika.before=/tmp/before.json -Duika.after=/tmp/after.json` | `./mill net.exoego.uika.mill.Uika/upgradeCheck --before /tmp/before.json --after /tmp/after.json` | `bazel run //:uika_upgrade_check -- --before /tmp/before.json --after /tmp/after.json` |

To keep the base-branch resolution off the PR's critical path, dump the
baseline once per push instead and cache it as an artifact keyed by SHA:
[BASELINE-CACHING.md](BASELINE-CACHING.md).

### Runtime load evidence from the base branch (optional)

[Runtime load evidence](#runtime-load-evidence-jfr---class-load-log) rides the
same artifact flow: the base branch runs its test suite with JFR class-load
recording on, uploads the recordings, and the PR job downloads them by
`base.sha` and adds one flag. A ⚠️ class that provably loads during tests then
fails `--fail-on reachable` instead of being deprioritized (a 💤 latent
violation stays latent — loading proves the class reachable, not that anything
invokes the affected member). For Gradle, next to the baseline dump:

```yaml
      - run: ./gradlew test -PuikaJfr=/tmp/uika-jfr
      - uses: actions/upload-artifact@v7
        with:
          name: uika-jfr-${{ github.sha }}
          path: /tmp/uika-jfr
```

and in the PR job, after downloading the artifact into `/tmp/uika-jfr`:

```yaml
      - run: ./gradlew uikaUpgradeCheck -PuikaBefore=/tmp/before.json -PuikaAfter=/tmp/after.json -PuikaJfr=/tmp/uika-jfr
```

The [per-tool knobs](#build-tool-plugins) cover sbt, Maven, Mill and Bazel.

## Command reference

```console
# List breaking changes between old/new versions of a library.
# Each line opens with the change kind, which is also what --json puts in its "kind" field:
# jq '.breaking_changes[] | select(.kind == "class_became_final")' selects those lines.
$ uika diff guava-22.0.jar guava-23.0-rc1.jar [--json]
CLASS BECAME FINAL     com/google/common/collect/BoundType
FIELD REMOVED          com/google/common/graph/GraphConstants.EDGE_CONNECTING_NOT_IN_GRAPH Ljava/lang/String;
METHOD ACCESS NARROWED com/google/common/collect/Iterators$ConcatenatedIterator.<init> (Ljava/util/Iterator;)V (public -> package-private)

breaking changes: 93 (classes: 26, methods: 61, fields: 6)

# Find usages of breaking changes across classpath JARs / your build output
# (--old/--new may be repeated to check several changed libraries in one run)
# Exit codes: 0 = clean, 1 = violations found, 2 = error
$ uika check --old kotlinx-coroutines-core-jvm-1.7.1.jar \
             --new kotlinx-coroutines-core-jvm-1.11.0.jar \
             --classpath ktor-io-jvm-2.3.13.jar:other-dep.jar \
             --app build/classes/kotlin/main
checked kotlinx-coroutines-core-jvm-1.7.1.jar -> kotlinx-coroutines-core-jvm-1.11.0.jar against 3 scan targets

--------------------------------------------------------------------------------
💥 reachable from the application (likely to break)
--------------------------------------------------------------------------------

❌ kotlinx.coroutines.EventLoopKt.processNextEventInCurrentThread()
    method removed, throws NoSuchMethodError at first call
    used by 1 class:
        io.ktor.utils.io.jvm.javaio.BlockingAdapter  (ktor-io-jvm-2.3.13.jar)

scanned 372 classes: ❌ 1 broken (of which 💥 1 reachable, ⚠️ 0 not proven reachable), ❓ 5 unverified references (hierarchy escapes the analyzed scope)

# Detect broken references caused by every artifact whose version changed.
# When application roots are known (build outputs in the dump, or --app), violations
# are ranked: reachable first, then the ones no static path reaches.
$ uika upgrade-check --before /tmp/before.json --after /tmp/after.json
dependency changes: 1
    CHANGED io.opentelemetry:opentelemetry-sdk-common 1.42.1 -> 1.60.1

per-module check: 2 of 41 modules changed their resolved versions (39 unchanged)
    :app  scanned 84013 classes, ❌ 42 broken, ❓ 118 unverified
    :worker  scanned 61200 classes, ✅ 0 broken, ❓ 87 unverified

--------------------------------------------------------------------------------
💥 reachable from the application (likely to break)
--------------------------------------------------------------------------------

💡 suggestion: align all io.opentelemetry artifacts to one version (e.g. via the matching BOM); otherwise upgrade the sender or pin opentelemetry-sdk-common to 1.42.1
    affected modules: :app
    why: io.opentelemetry:opentelemetry-sdk-common changed 1.42.1 -> 1.60.1, which breaks io.opentelemetry:opentelemetry-exporter-sender-okhttp:1.42.1:
        io.opentelemetry.sdk.internal.DaemonThreadFactory was removed, but io.opentelemetry.exporter.sender.okhttp.internal.OkHttpGrpcSender still uses it
        io.opentelemetry.sdk.internal.DaemonThreadFactory was removed, but io.opentelemetry.exporter.sender.okhttp.internal.OkHttpUtil still uses it

--------------------------------------------------------------------------------
⚠️  not proven reachable (no static path found; may still load via reflection)
--------------------------------------------------------------------------------

💡 suggestion: ...
    why: ...

scanned 145213 classes: ❌ 42 broken (of which 💥 25 reachable, ⚠️ 17 not proven reachable), ❓ 205 unverified references (hierarchy escapes the analyzed scope)

# Debugging aid: dump the extracted API surface of a JAR
$ uika dump some.jar
```

### Violation tiers and `--fail-on`

A changed library drags in transitive JARs your application never touches, so
not every violation is worth the same attention. Each one lands in a tier, and
the report prints them in this order:

| Tier | Meaning |
| --- | --- |
| 💥 breaks | reachable from your application, or not provably unreachable |
| 💤 latent | class is reachable, but no scanned code invokes the affected member |
| ⚠️ unproven | no static path from your application reaches the class |

`check` and `upgrade-check` always print the full report; `--fail-on` only
decides the exit code, as a threshold over exactly that split:

- `any` (default, strictest): exit 1 on any violation.
- `reachable`: exit 1 only on 💥.
- `never`: always exit 0, reporting violations as warnings only.

So what fails CI is exactly what the report shows above the warning sections.
Errors always exit 2 regardless of `--fail-on`.

**Reachability (💥 vs ⚠️).** When application roots are available (the module
`classesDirs` in a dump, or `--app` build outputs), uika walks the class-load
graph from them and labels what it never reaches ⚠️. The walk is a deliberate
over-approximation, so ⚠️ is a signal to deprioritize rather than a guarantee,
and reflection driven purely by external configuration stays invisible.
Anything not provably unreachable stays 💥.

Without usable roots every violation stays 💥, so `reachable` behaves like
`any`. That covers a bare `check --classpath ...` (nothing to walk from) and
roots that matched no scanned class (build outputs not compiled, which prints a
warning naming the cause).

**Invocation evidence (💤).** `AbstractMethodError` is the one break that does
not fire when the class loads. A concrete class inheriting an unimplemented
abstract method loads, verifies, and instantiates without complaint, and throws
only when the missing method is actually called. So for `method became
abstract` uika looks for an invocation of the affected member in the scanned
bytecode, and calls the violation latent when there is none. That evidence
comes from bytecode rather than from application roots, so 💤 survives both
degraded cases above. Like ⚠️ it is a confidence tier and not a proof, since a
call through reflection or JNI, or from code outside the scan, stays invisible.
[`--exclude-file`](#excluding-known-false-positives---exclude-file) can drop
the whole category with `kind = "method_became_abstract"`.

### Per-module checking (`upgrade-check`)

Each module gets its own JVM classpath at runtime, and two modules may
legitimately resolve different versions of one coordinate (e.g. one service 
on netty 4.1, a newer one on 4.2). So each module is checked against what it 
actually resolves, not against a flattened union: modules whose versions did 
not move are skipped, and every violation names the modules that exhibit it
(`modules:` in the text report, a `modules` array in JSON). `--merged`
restores the flat union check.

### Excluding known false positives (`--exclude-file`)

Some violations are real breaks in the referenced API but never actually
matter at runtime, because the only reference resolves through reflection
the tool cannot see (see [Violation tiers](#violation-tiers-and---fail-on)).
commons-logging's `LogFactoryImpl` is the recurring example: it reflectively
scans a `String[]` of class names at init, so a field like
`classesToDiscover` shows up as removed even though no bytecode reference to
it survives.

`--fail-on reachable` already keeps that kind of violation from failing the
build, but it is still printed on every run. `--exclude-file <path>`
(repeatable; rules from every file given are merged) drops specific known
false positives from the report entirely, with a required reason so the
entry documents itself for whoever reads it next:

```toml
# uika-exclude.toml
[[exclude]]
owner = "org/apache/commons/logging/impl/LogFactoryImpl"
member = "classesToDiscover"
reason = "reflectively scanned by LogFactoryImpl at init; never referenced from bytecode"

# owner may end with a single trailing '*' to match a whole package/class prefix;
# member is optional, and when set matches by name only (covers every overload).
[[exclude]]
owner = "org/apache/commons/logging/*"
reason = "commons-logging uses reflection-based class discovery throughout"

# add descriptor to pin one overload, so a real break on a sibling overload of
# the same name is still reported.
[[exclude]]
owner = "lib/C"
member = "m"
descriptor = "()V"
reason = "only the no-arg m() is invoked reflectively"

# kind pins a rule to one violation kind. With an owner, both must match:
# this waives only newly-abstract methods from conscrypt, leaving every other
# kind of conscrypt break — and this kind elsewhere — reported.
[[exclude]]
owner = "org/conscrypt/*"
kind = "method_became_abstract"
reason = "conscrypt adds abstract methods its own code never calls; tracked in DEP-142"

# kind alone applies to every owner. Note that exclusion REMOVES a violation from
# the report, it does not merely stop it failing the build, so use this only for a
# category you have decided not to see at all.
[[exclude]]
kind = "extends_final_class"
reason = "we ship a shaded copy of the lagging artifact, so version-lag finals never link"
```

`owner`/`member`/`descriptor` use raw JVM internal forms (`/`-separated owner
names, `$` for nested classes, `<init>` for constructors, undecoded descriptors
like `(Ljava/util/Date;)V`), so copy entries from the `--json` output rather
than from the text report's dotted signatures (each violation's `reference`
carries the raw `owner`, `member.name`, and `member.descriptor`).

`kind` is the violation kind in snake_case, for example `class_removed` or
`method_became_abstract`; an unknown value is rejected at load with the valid
list, while the spaced form the reports print under `reason` is accepted, as is
a kind from before it was split by direction and member kind
(`class_kind_changed` still waives both of the flips that replaced it).

A rule needs an `owner`, a `kind`, or both. The summary line reports how many
violations were suppressed (`N suppressed by --exclude-file`), and a rule that
matched nothing prints a warning, so stale entries do not go unnoticed as the
checked libraries change.

This is for false positives you have actually investigated, not a shortcut
around triaging `⚠️  not proven reachable` violations wholesale; use
`--fail-on reachable` for that instead.

### Runtime load evidence (JFR, `--class-load-log`)

The ⚠️ tier means "no static path found", and its blind spot is reflection. A
JVM can close that gap: run the **current, not yet upgraded** build — its test
suite, or a staging/production soak — with JFR recording every class load:

```console
-XX:StartFlightRecording:jdk.ClassLoad#enabled=true,jdk.ClassLoad#stackTrace=true,filename=<dir>
```

(JDK 17+ syntax; the [build-tool plugins](#build-tool-plugins) inject exactly
this into test JVMs from one knob). JFR generates pid-unique file names for a
directory-valued `filename`, so parallel test JVMs never collide, and the
recorded stacks are what uika turns into the `via ...` trigger on every
promoted violation. Teams already running continuous production JFR only need
the `jdk.ClassLoad` event enabled — the recording they already collect then IS
the evidence, no extra flags.

The intended CI shape mirrors [baseline caching](BASELINE-CACHING.md): the
base branch's test run records once per push and stores the directory as an
artifact, and the dependency PR's `upgrade-check` downloads it and points the
same knob at it. The plugins convert recordings with the JDK's own JFR reader
before invoking the CLI, which stays JVM-free and never reads binary
recordings. A ⚠️ violation whose referencing class appears in the evidence is
promoted out of the tier and marked, trigger included:
`⚡ observed loading at runtime (via java.lang.Class.forName from
com.example.PluginRegistry.discover(...))` — the reflective edge the static
walk could not see, documented for free. `--fail-on reachable` then fails on
it, and `--json` carries the same evidence per violation (`observed_loading`,
`load_trigger`).

Ingestion is promote-only, the same stance reachability takes: absence of a
load entry proves nothing beyond the observed runs (a different code path, a
run that never got there), so no violation is ever demoted or dropped because
of it.

JFR caveats, all bounded: the event is disabled in the default JFC profile
(the flag above enables it — the plugin prints each conversion's event count,
and `0 jdk.ClassLoad events` means a recording made without it); a recording
rotates away its oldest chunks past `maxsize` (250MB by default when
`filename` is set — far above what class-load events reach); a SIGKILLed JVM
never writes its final dump, so a crashed test fork contributes no evidence
(promote-only makes that safe); and stack capture keeps the innermost 64
frames (`-XX:FlightRecorderOptions:stackdepth=` to raise), which truncates the
harness side uika never reads — the trigger sits at the inner end.

**Bring-your-own text logs.** `--class-load-log` also reads text evidence,
mixed freely with recordings in one directory and parsed leniently:
unified-logging `[class,load]` lines with any decorators (`-Xlog:class+load`
output; other `-Xlog` streams sharing the file are skipped), plain class-name
lists dotted or slashed (`-XX:DumpLoadedClassList` classlists), and
`class+load+cause` stack blocks — the JDK 22+ flags
(https://bugs.openjdk.org/browse/JDK-8193513,
`-Xlog:class+load+cause=info -XX:LogClassLoadingCauseFor=<substring>`) remain
the right tool for a *targeted* production look at one class, and uika reads
their output too. Without a build tool, convert a recording by hand:

```console
jfr print --json --events jdk.ClassLoad rec.jfr \
  | jq -r '.recording.events[].values.loadedClass.name
           | select(startswith("[") | not) | "[class,load] \(.)"'
```

yields tagged class-load lines the CLI reads (classes only, no triggers; the
tag keeps default-package names accepted, and the filter drops array classes).

**Drafting an exclude file (`--draft-exclude-file <path>`).** The deliberate
consumer of the opposite signal. After soaking the evidence, symbols whose
every violation is still ⚠️ *and* was never observed loading can be drafted
into [`--exclude-file`](#excluding-known-false-positives---exclude-file)
rules. Every drafted reason opens with `REVIEW:` and records exactly what the
evidence shows (which classes, which logs); a symbol that also breaks a
reachable or observed class is never drafted, because the rule would waive
that real break too. The draft is input for a human: review each entry and
delete what you cannot justify before committing the file. Requires
`--class-load-log`, and refuses to draft when that evidence names no class at
all: an artifact that never downloaded, a test JVM that never forked, or a
directory holding only recordings would otherwise draft every unproven
violation with a reason indistinguishable from a well-evidenced run. Drafting
into a file that is also an `--exclude-file` is refused for the same reason it
looks tempting: it would rewrite that file with only the drafted rules.

A `--class-load-log` path that does not exist is skipped with a warning rather
than failing the run: evidence is data another job produces, so its absence is
an operational state, and the knob can stay in a build that also runs on a
laptop or a fork PR. Nothing is promoted from a path that is not there, which
is what the warning says. Drafting from evidence that named no class at all is
still refused.

## Build-tool plugins

The Gradle, sbt, Maven, Mill and Leiningen plugins, the Clojure CLI tool and the
Bazel rules write the same dump format: every module's resolved runtime
classpath as coordinate-annotated JSON, kept per module so `upgrade-check` can
[check each against its own resolution](#per-module-checking-upgrade-check).
Feed two dumps to `uika upgrade-check`, or one to `uika check
--classpath-file` (more accurate than a hand-assembled classpath, and reduces
unverified references).

A dump also refers to build outputs, so the Gradle task builds them by default
(`-PuikaBuildOutputs=false` for a resolution-only dump); sbt and Mill compile as
a side effect of the dump task, and Maven needs a `compile` phase in the same
invocation.

The upgrade-check task fetches the CLI itself as
`net.exoego.uika:uika-cli:<version>:<platform>@zip` through the build's own
dependency resolution, reusing its repositories, credentials, and cache, so
there is no separate install step. The version defaults to the plugin's own, so
one coordinate bump updates both. The Leiningen plugin and the Clojure CLI tool
are the exception: neither resolver handles a zip-packaged artifact, so they
download it straight from Maven Central (`UIKA_CLI_URL` to override the URL,
`UIKA_CLI_PATH` to point at a binary you already have and skip the download).
Bazel downloads it in a repository rule, so its repository cache holds it and a
second run needs no network; `UIKA_CLI_PATH` works there too.

The settings shown per tool below also have command-line forms:
[`failOn`](#violation-tiers-and---fail-on) (`-PuikaFailOn=`, `set uikaFailOn
:=`, `-Duika.failOn=`, `--failOn`) and
[`excludeFiles`](#excluding-known-false-positives---exclude-file)
(`-PuikaExcludeFile=` for a single file, `--excludeFile` repeated).

[`--jdk-release`](#how-it-works) needs no setting at all. The build runs on a
JVM, so Gradle, Maven, sbt, Mill and Bazel derive the release from what the
modules compile for. Gradle reads `compileJava`'s `options.release` else target
compatibility, Maven reads maven-compiler-plugin's `<release>`/`<target>` else
`maven.compiler.release`/`maven.compiler.target`, sbt and Mill read `javacOptions` and
`scalacOptions`, and Bazel reads a target's `javacopts` else the Java toolchain's
target version. A build with several modules contributes the LOWEST of them,
because one flag serves a run that checks all of them and under-claiming only
costs unverified references while over-claiming drops findings. The result is
clamped to what the selected JDK's `ct.sym` serves, which is the same JDK the
CLI reads through `UIKA_JDK`. Leiningen and the Clojure tool
have no module model to read, so they use the project's own JVM release.
Override with `jdkRelease` / `uikaJdkRelease` / `<jdkRelease>` /
`jdk_release` (`-PuikaJdkRelease=`, `-Duika.jdkRelease=`, `--jdkRelease`), or set
0 to disable it.

Each dump also records the release next to every module it lists, read the same
way. That is what lets `upgrade-check` notice the application's own JDK moved
between the two dumps and check that move too, scoped to the modules that made
it. A module left on an older release is never checked against a sibling's
upgrade, and a module that declares no target is recorded as running on the
build's own JVM, which is what it compiles against.

The derivation only sees what the build declares, so a project that compiles
`--release 11` and ships on a 21 runtime looks unchanged when that runtime
moves. The same override says so by hand. A positive value is recorded as the
release every module runs on, while `0` still only switches the API layer off
and leaves the recorded release derived. The dump commands take it as
`-PuikaJdkRelease=` (Gradle), `-Duika.jdkRelease=` (Maven), `uikaJdkRelease :=`
(sbt), `--jdkRelease` (Mill), `:jdk-release` (Clojure tool and Leiningen).

[Runtime load evidence](#runtime-load-evidence-jfr---class-load-log) is one
knob per tool, pointed at one directory for both phases (collect on the base
branch, consume on the PR):

- Gradle: `-PuikaJfr=<dir>` makes every `Test` task record class loads into a
  JFR recording there (and run for real — an `UP-TO-DATE` or `FROM-CACHE`
  test task forks no JVM and would collect nothing), and makes
  `uikaUpgradeCheck` convert and read the directory back. A bare `-PuikaJfr`
  uses `build/uika/jfr`.
- sbt: `uikaJfr := Some(file("<dir>"))` in `build.sbt` (bare or
  `ThisBuild`-scoped) does the same for forked test JVMs and for
  `uikaUpgradeCheck`. It needs `Test / fork := true`: an in-process test runs
  inside sbt's own JVM, which no flag can reach after startup.
- Maven: collect with the test JVM flag (`mvn test
  -DargLine="-XX:StartFlightRecording:jdk.ClassLoad#enabled=true,jdk.ClassLoad#stackTrace=true,filename=<dir>"`),
  check with `-Duika.jfr=<dir>`. Create `<dir>` first: given a missing parent
  JFR aborts JVM startup, but given an existing parent it silently records to a
  single file at that path, every fork clobbering the last. Make it absolute in
  a multi-module build:
  surefire forks resolve a relative path against each module, the aggregator
  goal against the execution root. A command-line `-DargLine` replaces any
  POM-configured argLine (jacoco's agent included) — append to the POM's
  argLine instead when one exists.
- Mill: mix `UikaTestModule` into the test modules and export `UIKA_JFR=<dir>`
  for the test run, then pass `--jfr <dir>` to `upgradeCheck` (the check reads the
  flag, not the variable). The mixin is needed because `forkArgs` is a task on the
  test module itself, out of reach of a command that finds the modules through the
  evaluator.
- Bazel: collect with `bazel test`, check with `--jfr <dir>`. `--jvmopt` already
  reaches every test JVM, so nothing has to be injected, and the check target
  prints the flag (and creates the directory) so the recipe cannot drift:

  ```console
  $ jvmopt=$(bazel run //:uika_upgrade_check -- jfr-jvmopt /tmp/uika-jfr)
  $ bazel test //... --nocache_test_results \
        --sandbox_writable_path=/tmp/uika-jfr "$jvmopt"
  $ bazel run //:uika_upgrade_check -- --before /tmp/before.json \
        --after /tmp/after.json --jfr /tmp/uika-jfr
  ```

  `--nocache_test_results` is not optional: a cached test forks no JVM and would
  record nothing, with no symptom. `--sandbox_writable_path` is what lets the
  recording land outside the sandbox, where the check can read it afterwards.

JFR generates pid-unique recording names for a directory value, so parallel
test JVMs never collide, and the injected flag needs JDK 17+ test JVMs (the
event-settings syntax; leave the knob off for an older test leg). A `.jfr`
value instead of a directory — a production recording, say — is
consumption-only: the check converts it, test JVMs are left untouched.
[`--draft-exclude-file`](#runtime-load-evidence-jfr---class-load-log)
maps to `-PuikaDraftExcludeFile=` / `uikaDraftExcludeFile :=` /
`-Duika.draftExcludeFile=` / `--draftExcludeFile` (Mill and Bazel) /
`:draft-exclude-file` (both Clojure frontends, which take text logs rather than
JFR).

### Gradle (`gradle-plugin/`) [![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fuika-gradle-plugin%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/uika-gradle-plugin)

Works with Groovy and Kotlin DSL builds (Gradle 9 / JVM 17+).

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

```kotlin
// build.gradle.kts
import net.exoego.uika.gradle.UpgradeCheckTask

plugins {
    id("net.exoego.uika") version "VERSION_PLACEHOLDER"
}

// Optional: gate only on reachable violations, and suppress known false positives.
tasks.withType<UpgradeCheckTask>().configureEach {
    failOn.set("reachable")
    excludeFiles.from("uika-exclude.toml")
}
```

```console
$ ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/after.json
$ ./gradlew uikaUpgradeCheck \
      -PuikaBefore=/tmp/before.json -PuikaAfter=/tmp/after.json   # -PuikaCliVersion=x.y.z to override
```

### sbt (`sbt-plugin/`) [![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fsbt-uika_2.12_1.0%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/sbt-uika_2.12_1.0)

```scala
// project/plugins.sbt
addSbtPlugin("net.exoego.uika" % "sbt-uika" % "VERSION_PLACEHOLDER")
```

```scala
// build.sbt — optional: gate only on reachable violations, and suppress known false positives.
ThisBuild / uikaFailOn := "reachable"
ThisBuild / uikaExcludeFiles := Seq(baseDirectory.value / "uika-exclude.toml")
```

```console
$ sbt uikaDumpClasspath   # writes target/uika/classpath.json (override via the uikaOutput setting)
$ sbt "uikaUpgradeCheck /tmp/before.json /tmp/after.json"   # uikaCliVersion setting to override
```

### Maven (`maven-plugin/`) [![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fuika-maven-plugin%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/uika-maven-plugin)

```xml
<build>
  <plugins>
    <plugin>
      <groupId>net.exoego.uika</groupId>
      <artifactId>uika-maven-plugin</artifactId>
      <version>VERSION_PLACEHOLDER</version>
      <!-- Optional: gate only on reachable violations, and suppress known false positives. -->
      <configuration>
        <failOn>reachable</failOn>
        <excludeFiles>
          <excludeFile>${project.basedir}/uika-exclude.toml</excludeFile>
        </excludeFiles>
      </configuration>
    </plugin>
  </plugins>
</build>
```

```console
$ mvn uika:dump-classpath -Duika.output=/tmp/classpath.json
$ mvn uika:upgrade-check \
      -Duika.before=/tmp/before.json -Duika.after=/tmp/after.json   # -Duika.cliVersion to override
```

### Mill (`mill-plugin/`) [![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fmill-uika_mill1_3%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/mill-uika_mill1_3)

Mill 1.x. One header line wires up a build of any size: the commands find every
non-test `JavaModule` themselves. Only JFR collection needs a mixin, because
`forkArgs` is a task on the test module itself.

```scala
//| mvnDeps: ["net.exoego.uika::mill-uika::VERSION_PLACEHOLDER"]

package build

import mill.*, javalib.*
```

```console
$ ./mill net.exoego.uika.mill.Uika/dumpClasspath                 # writes out/uika/classpath.json
$ ./mill net.exoego.uika.mill.Uika/dumpClasspath --output /tmp/after.json
$ ./mill net.exoego.uika.mill.Uika/upgradeCheck \
      --before /tmp/before.json --after /tmp/after.json \
      --failOn reachable --excludeFile uika-exclude.toml         # --cliVersion to override
```

To collect [runtime load evidence](#runtime-load-evidence-jfr---class-load-log),
mix `UikaTestModule` into the test modules, last so its `forkArgs` wins:

```scala
object test extends JavaTests, TestModule.Junit5, net.exoego.uika.mill.UikaTestModule
```

Your own `override def forkArgs = Seq(...)` replaces the list and drops the injected
flag. Append to `super.forkArgs()` instead. `./mill testLocal` does not fork, so it
records nothing.

### Clojure CLI (`clojure-tool/`)

The tool itself is distributed as a git dependency, so it adds nothing to
uika's Maven Central deployment, and the repo tag pins both the tool and the
CLI version it runs. Its own dependencies and the uika-cli binary still
resolve from Maven Central as usual:

```console
$ clojure -Ttools install io.github.exoego/uika \
      '{:git/tag "vVERSION_PLACEHOLDER" :deps/root "clojure-tool"}' :as uika
```

```console
$ clojure -Tuika dump-classpath                        # writes target/uika/classpath.json
$ clojure -Tuika dump-classpath :output '"/tmp/after.json"' :aliases '[:prod]'
$ clojure -Tuika upgrade-check :before '"/tmp/before.json"' :after '"/tmp/after.json"' \
      :fail-on reachable :exclude-file '"uika-exclude.toml"'   # :cli-version to override
```

The dump records the resolved Maven coordinates from the project's own
`deps.edn` basis (`:local/root` and git deps are coordinate-less, like the other
tools' project dependencies), and `upgrade-check` downloads the platform binary
from Maven Central (`UIKA_CLI_URL` to override the URL, `UIKA_CLI_PATH` to skip
the download) with the version taken from the installed tool's own `:git/tag`.

Two Clojure-specific caveats. Interop calls without type hints go through
runtime reflection and leave no reference in the constant pool, so uika sees
the Java dependencies on the classpath at full strength but only the
type-hinted, AOT-compiled part of the Clojure code itself; point `:class-dir`
at the tools.build `compile-clj` output to include it. JFR recordings are not
converted by this tool yet; pass text logs to `:class-load-log`, and
`:draft-exclude-file` alongside it to draft an exclude file.

### Leiningen (`lein-plugin/`) [![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Flein-uika%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/lein-uika)

```clojure
;; project.clj
:plugins [[net.exoego.uika/lein-uika "VERSION_PLACEHOLDER"]]
;; Optional: gate only on reachable violations, and suppress known false positives.
:uika {:fail-on "reachable"
       :exclude-files ["uika-exclude.toml"]
       ;; Defaults to the plugin's own version; there is no command-line override.
       :cli-version "VERSION_PLACEHOLDER"}
```

```console
$ lein uika dump-classpath                       # writes <:target-path>/uika/classpath.json
$ lein uika dump-classpath /tmp/after.json
$ lein uika upgrade-check /tmp/before.json /tmp/after.json
```

The whole `:uika` map is `:fail-on`, `:exclude-files`, `:jdk-release` (0 disables),
`:class-load-logs` (text format), `:draft-exclude-file` (needs `:class-load-logs`),
`:cli-version` and `:cli-path`. Any other key is an error rather than a silent
no-op, so a misspelling cannot quietly disable a flag. The CLI answers a lone
`:draft-exclude-file` by naming `--class-load-log`, whose keyword form this map
rejects as unknown.

The dump excludes what only development pulls in (the `:base`/`:system`/`:user`/`:dev`
profiles, so no nREPL, and `:provided`, which an uberjar leaves out) and runs the
project's `:prep-tasks` first, so both `:aot` classes and `:java-source-paths` output
are scanned. The reflection caveat above applies here too; `:class-dir` does not,
because the dump takes its class directories from `:compile-path` and the project's
own source and resource paths.

### Bazel (`bazel-rules/`)

Bazel 7 or newer, with bzlmod. The module comes from the GitHub release rather
than from a registry for now:

```python
# MODULE.bazel
bazel_dep(name = "uika", version = "VERSION_PLACEHOLDER")
archive_override(
    module_name = "uika",
    urls = ["https://github.com/exoego/uika/releases/download/vVERSION_PLACEHOLDER/uika-bazel-VERSION_PLACEHOLDER.tar.gz"],
    strip_prefix = "bazel-rules",
)
```

```python
# BUILD.bazel
load("@uika//:defs.bzl", "uika_dump", "uika_upgrade_check")

UIKA_TARGETS = ["//app", "//service"]

uika_dump(
    name = "uika_dump",
    targets = UIKA_TARGETS,
)

# The baseline the PR gate compares against: it only feeds the version diff, so it
# resolves without building anything.
uika_dump(
    name = "uika_resolution_dump",
    build_outputs = False,
    targets = UIKA_TARGETS,
)

uika_upgrade_check(
    name = "uika_upgrade_check",
    exclude_files = ["uika-exclude.toml"],
    fail_on = "reachable",
    targets = UIKA_TARGETS,
)
```

```console
$ bazel run //:uika_dump -- --output /tmp/after.json
$ bazel run //:uika_resolution_dump -- --output /tmp/before.json
$ bazel run //:uika_upgrade_check -- --before /tmp/before.json --after /tmp/after.json
```

The CLI binary comes from a repository rule, so Bazel's repository cache holds it
and the release archive pins its checksum for every platform. `--failOn`,
`--excludeFile`, `--jdkRelease`, `--classLoadLog` and
`--draftExcludeFile` override the rule's settings on the command line, and a
relative path in any of them resolves against the directory you ran `bazel` from,
not the runfiles tree. The check target repeats `targets` only to read the API
release they compile for, so it builds nothing.

Each entry in `targets` becomes one module of the dump, named by its label, so
`upgrade-check` checks each against its own resolution.

A rule cannot expand a target pattern, so for a whole-build dump apply the aspect
from the command line instead of listing anything:

```console
$ find "$(bazel info bazel-bin)" -name '*.uika-manifest.tsv' -delete
$ bazel build //... --aspects=@uika//:defs.bzl%uika_classpath_aspect       --output_groups=uika_dump
$ bazel run @uika//:merge -- --output /tmp/after.json       --execroot "$(bazel info execution_root)"       --fragments "$(bazel info bazel-bin)"
```

Narrow the pattern to keep the module count sane, `kind(java_binary, //...)` for
instance, since `upgrade-check` runs once per module. A target carrying a
`maven_coordinates` tag is skipped, because it is a dependency rather than a
module of the build under check and it already appears in the artifact list of
everything that uses it. The `find -delete` is part of the recipe rather than
tidiness: fragments live in `bazel-out` and nothing prunes them, so a target
deleted since the last sweep would otherwise still contribute its module.

`--materialize` works the same way here. The merge step is a separate command
because it needs `bazel info`, which cannot run inside a `bazel run` without
blocking on the server lock.

Coordinates come from the `maven_coordinates=group:artifact:version` tag that
rules_jvm_external puts on every `jvm_import` it generates — the same tag its own
`java_export` and `pom_file` read. Nothing here is specific to rules_jvm_external,
so a hand-written `java_import` carrying that tag is attributed just as well, and a
target of your own build is recorded by label the way the other tools record a
project dependency. `--jdk-release` is derived per target from its `javacopts`,
falling back to the Java toolchain's target version, and `jdk_release = N` on the
rule overrides every module.

Two Bazel-specific things to know. The dump is written by `bazel run`, never by a
build action, because it names absolute paths and an action's output is cacheable.
`bazel run` lays the classpath out as runfiles, and resolving those symlinks is
where the real paths come from.

And Bazel discards an external repository and refetches it when its lockfile
changes, so the old JARs a baseline dump points at are gone by the time the PR job
compares against them. uika treats a JAR it cannot open as a warning, so the
symptom is *fewer* findings rather than a failure. `--materialize <dir>` is the
answer: it hard-links every JAR the dump names into one directory and points the
dump there, which puts the baseline out of Bazel's reach and makes it portable to
another machine as a bonus. See [BASELINE-CACHING.md](BASELINE-CACHING.md).


## How it works

1. Parse the old/new JARs into full API indexes with class hierarchy.
2. Pass 1 streams the consumer classpath, keeping only a class-hierarchy graph
   and the references whose owner exists in the old index.
3. Pass 2 re-reads just the classes resolution could actually visit (typically
   under 0.1% of the total) for their member tables.
4. Resolve each reference against "new JARs + re-read classes", walking the
   inheritance hierarchy, and report the ones that resolved under old but break
   under new.

Linkage is checked the way the JVM links, against the flattened runtime
classpath. Members moved to a superclass, classes relocated to another
artifact, and copies bundled inside fat JARs are not false positives.
References that escape into unanalyzed classes are counted as "unverified"
rather than silently ignored.

Most escapes lead into the JDK. Passing `--jdk-release N` (on `check` and
`upgrade-check`) layers the JDK API of release N under the resolution scope,
read from the `ct.sym` file of the JDK named by `UIKA_JDK` (checked first,
authoritative when set), else `JAVA_HOME`, so those references conclude as OK
or broken instead of unverified. N must be older than the installed JDK (its
own release is not in `ct.sym`). The layer sits under both the old and the new
side, so gaps in `ct.sym` cancel out instead of producing false positives from
missing stubs. Without the flag nothing changes, and uika still needs no JVM
to run.

```console
$ uika check --old guava-22.0.jar --new guava-23.0-rc1.jar \
             --classpath selenium-remote-driver-3.4.0.jar
...
scanned 205 classes: ❌ 2 broken, ❓ 16 unverified references (hierarchy escapes the analyzed scope)

$ uika check --old guava-22.0.jar --new guava-23.0-rc1.jar \
             --classpath selenium-remote-driver-3.4.0.jar --jdk-release 17
...
scanned 205 classes: ❌ 2 broken
```

### Checking a JDK upgrade

`--jdk-release-old N --jdk-release-new M` makes the JDK upgrade itself the compared
pair, so `--old` and `--new` become optional. A JDK API your classpath still
references and release M dropped is then reported like any other removal.

```console
$ uika check --jdk-release-old 11 --jdk-release-new 17 --classpath app.jar
checked JDK 11 -> JDK 17 against 1 scan target

❌ java.rmi.activation.ActivationGroup
    class removed, throws NoClassDefFoundError at first use
    used by 1 class:
        UsesRemoved  (app.jar)
```

Releases below the installed JDK come from its `ct.sym`; the installed JDK's own
release comes from its `jmods/`, which `ct.sym` never carries. Checking an upgrade
*to* the JDK you now run therefore needs only that one JDK. Sealing changes are
invisible here, because `ct.sym` stubs do not carry `PermittedSubclasses`, and
reporting them from the `jmods` side alone would be a false positive.

From the build-tool plugins this needs no flag. The classpath dump records the
release of the JVM that wrote it, so bumping your toolchain and re-running the
dump is enough — `upgrade-check` sees the two dumps disagree and checks the JDK
move alongside the dependency moves, in one report.

```console
$ uika upgrade-check --before before.json --after after.json
dependency changes: none

per-module check: 0 of 1 modules changed their resolved versions (1 unchanged)
    JDK 11 -> 17  scanned 2 classes, ❌ 1 broken, 0 unverified
...
        UsesRemoved  (app.jar) [JDK 11 -> 17]
```

Dumps written before the plugins recorded the release carry no value, and a
missing value on either side is never read as a JDK move.

## Development

`make check` runs fmt, clippy, and the Rust and plugin test suites.
[CONTRIBUTING.md](CONTRIBUTING.md) covers the rest, including the vendored
real-incident fixtures, the golden-bless workflow, and the JVM probe harness.
Releases are described in [PUBLISHING.md](PUBLISHING.md).

## Known limitations (PoC)

- References whose hierarchy escapes into unanalyzed classes are conservatively
  treated as OK (reported only as an "unverified" count, which passing the
  complete runtime classpath via `--classpath` reduces)
- Multi-release JARs are analyzed at their base classes only
  (`META-INF/versions/` is ignored)
- `InvokeDynamic` bootstrap synthetic names are excluded
- A constant-pool reference does not guarantee the code path executes (optional
  integrations guarded by try/catch may be reported yet never run)
