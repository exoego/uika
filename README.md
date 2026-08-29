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

A `linkage-check` workflow dumps a baseline from the PR's base branch and
the PR's own classpath, and fails the PR on broken references between the
two. Each tool's page carries the copy-pasteable workflow, including an
optional `dump-baseline` job that takes the base-branch resolution off the
PR's critical path:

- [Gradle](docs/gradle.md#pr-gate-on-github-actions)
- [sbt](docs/sbt.md#pr-gate-on-github-actions)
- [Maven](docs/maven.md#pr-gate-on-github-actions)
- [Mill](docs/mill.md#pr-gate-on-github-actions)
- [Clojure CLI](docs/clojure.md#pr-gate-on-github-actions)
- [Leiningen](docs/leiningen.md#pr-gate-on-github-actions)
- [Bazel](docs/bazel.md#pr-gate-on-github-actions)

### Runtime load evidence from the base branch (optional)

[Runtime load evidence](#runtime-load-evidence-jfr) rides
the same artifact flow as the
[cached baseline](docs/gradle.md#caching-the-baseline): the base branch runs
its test suite with JFR class-load
recording on, uploads the recordings, and the PR job downloads them by
`base.sha` and adds one flag. A ⚠️ class that provably loads during tests then
fails `failOn = reachable` instead of being deprioritized (a 💤 latent
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

The [per-tool options](#build-tool-plugins) cover the other tools.

## What a check reports

Every tool's check task runs the same report over the two dumps. A broken
reference names the member, what it throws, and every class that still uses it:

```console
❌ kotlinx.coroutines.EventLoopKt.processNextEventInCurrentThread()
    method removed, throws NoSuchMethodError at first call
    used by 1 class:
        io.ktor.utils.io.jvm.javaio.BlockingAdapter  (ktor-io-jvm-2.3.13.jar)
```

Violations that share one cause are collapsed into a suggestion naming the fix.
When application roots are known (build outputs in the dump), they are ranked:
reachable first, then the ones no static path reaches.

```console
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
```

The plugins run a binary they fetch themselves, so the command line behind
this is an implementation detail. [The CLI page](docs/cli.md) has it, for a
build no plugin covers.

### Violation tiers and the `failOn` threshold

A changed library drags in transitive JARs your application never touches, so
not every violation is worth the same attention. Each one lands in a tier, and
the report prints them in this order:

| Tier | Meaning |
| --- | --- |
| 💥 breaks | reachable from your application, or not provably unreachable |
| 💤 latent | class is reachable, but no scanned code invokes the affected member |
| ⚠️ unproven | no static path from your application reaches the class |

The report always prints in full. `failOn` only decides the exit code, as a
threshold over exactly that split:

- `any` (default, strictest): exit 1 on any violation.
- `reachable`: exit 1 only on 💥.
- `never`: always exit 0, reporting violations as warnings only.

So what fails CI is exactly what the report shows above the warning sections.
An error always fails the run, whatever the threshold.

**Reachability (💥 vs ⚠️).** When application roots are available (the build
outputs a dump records for each module), uika walks the class-load
graph from them and labels what it never reaches ⚠️. The walk is a deliberate
over-approximation, so ⚠️ is a signal to deprioritize rather than a guarantee,
and reflection driven purely by external configuration stays invisible.
Anything not provably unreachable stays 💥.

Without usable roots nothing can be labelled ⚠️, so `reachable` behaves like
`any` over everything except the 💤 tier below, which comes from the scanned
bytecode and survives. That is what a dump taken without building the outputs
gives you, and what roots matching no scanned class give you (a warning names
the cause).

**Invocation evidence (💤).** `AbstractMethodError` is the one break that does
not fire when the class loads. A concrete class inheriting an unimplemented
abstract method loads, verifies, and instantiates without complaint, and throws
only when the missing method is actually called. So for `method became
abstract` uika looks for an invocation of the affected member in the scanned
bytecode, and calls the violation latent when there is none. That evidence
comes from bytecode rather than from application roots, so 💤 survives both
degraded cases above. Like ⚠️ it is a confidence tier and not a proof, since a
call through reflection or JNI, or from code outside the scan, stays invisible.
An [exclude file](#excluding-known-false-positives) can drop the whole
category with `kind = "method_became_abstract"`.

### Per-module checking

Each module gets its own JVM classpath at runtime, and two modules may
legitimately resolve different versions of one coordinate (e.g. one service 
on netty 4.1, a newer one on 4.2). So each module is checked against what it 
actually resolves, not against a flattened union: modules whose versions did 
not move are skipped, and every violation names the modules that exhibit it
(`modules:` in the report). Dumps carrying no per-module data fall back to the
flat union check.

### Excluding known false positives

Some violations are real breaks in the referenced API but never actually
matter at runtime, because the only reference resolves through reflection
the tool cannot see (see [Violation tiers](#violation-tiers-and-the-failon-threshold)).
commons-logging's `LogFactoryImpl` is the recurring example: it reflectively
scans a `String[]` of class names at init, so a field like
`classesToDiscover` shows up as removed even though no bytecode reference to
it survives.

`failOn = reachable` already keeps that kind of violation from failing the
build, but it is still printed on every run. An exclude file drops specific
known false positives from the report entirely, with a required reason so the
entry documents itself for whoever reads it next. Every tool takes several, and
the rules from all of them are merged:

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
like `(Ljava/util/Date;)V`), rather than the dotted signatures the report
prints. [`uika diff`](docs/cli.md#uika-diff) prints them in that form.

`kind` is the violation kind in snake_case, for example `class_removed` or
`method_became_abstract`; an unknown value is rejected at load with the valid
list, while the spaced form the reports print under `reason` is accepted, as is
a kind from before it was split by direction and member kind
(`class_kind_changed` still waives both of the flips that replaced it).

A rule needs an `owner`, a `kind`, or both. The summary line reports how many
violations were suppressed, and a rule that matched nothing prints a warning,
so stale entries do not go unnoticed as the checked libraries change.

This is for false positives you have actually investigated, not a shortcut
around triaging `⚠️  not proven reachable` violations wholesale; use
`failOn = reachable` for that instead.

### Runtime load evidence (JFR)

The ⚠️ tier means "no static path found", and its blind spot is reflection. A
JVM can close that gap: run the **current, not yet upgraded** build — its test
suite, or a staging/production soak — with JFR recording every class load:

```console
-XX:StartFlightRecording:jdk.ClassLoad#enabled=true,jdk.ClassLoad#stackTrace=true,filename=<dir>
```

(JDK 17+ syntax; Gradle and sbt inject exactly this into test JVMs from one
option, Mill through its test-module mixin, Bazel prints it ready to paste, and
Maven, Leiningen and the Clojure CLI take it by hand — each page carries the
recipe). Quote the `filename` value when the directory path contains a comma:
the comma is the option delimiter, and an unquoted one silently truncates
`filename=` with exit 0, leaving the directory empty. The injecting tools quote
it for you. JFR generates pid-unique file names for a
directory-valued `filename`, so parallel test JVMs never collide, and the
recorded stacks are what uika turns into the `via ...` trigger on every
promoted violation. Teams already running continuous production JFR only need
the `jdk.ClassLoad` event enabled — the recording they already collect then IS
the evidence, no extra flags.

The plugins take the option as a directory or a single `.jfr` file. A file — a
production recording, say — is consumption-only: the check converts it, test
JVMs are left untouched. The injected flag needs JDK 17+ test JVMs (the
event-settings syntax), so leave the option off for an older test leg.

The intended CI shape mirrors
[baseline caching](docs/gradle.md#caching-the-baseline): the
base branch's test run records once per push and stores the directory as an
artifact, and the dependency PR's `upgrade-check` downloads it and points the
same option at it. The plugins convert recordings with the JDK's own JFR reader
before invoking the CLI, which stays JVM-free and never reads binary
recordings. A ⚠️ violation whose referencing class appears in the evidence is
promoted out of the tier and marked, trigger included:
`⚡ observed loading at runtime (via java.lang.Class.forName from
com.example.PluginRegistry.discover(...))` — the reflective edge the static
walk could not see, documented for free. `failOn = reachable` then fails on
it.

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

**Bring-your-own text logs.** Text evidence is read too, mixed freely with
recordings in one directory and parsed leniently: unified-logging
`[class,load]` lines with any decorators (`-Xlog:class+load` output; other
`-Xlog` streams sharing the file are skipped), plain class-name lists dotted or
slashed (`-XX:DumpLoadedClassList` classlists), and `class+load+cause` stack
blocks — the JDK 22+ flags
(https://bugs.openjdk.org/browse/JDK-8193513,
`-Xlog:class+load+cause=info -XX:LogClassLoadingCauseFor=<substring>`) remain
the right tool for a *targeted* production look at one class, and uika reads
their output too.

**Drafting an exclude file.** The deliberate consumer of the opposite signal.
After soaking the evidence, symbols whose every violation is still ⚠️ *and* was
never observed loading can be drafted into
[exclude rules](#excluding-known-false-positives). Every drafted reason opens
with `REVIEW:` and records exactly what the evidence shows (which classes,
which logs); a symbol that also breaks a reachable or observed class is never
drafted, because the rule would waive that real break too. The draft is input
for a human: review each entry and delete what you cannot justify before
committing the file. Drafting is refused when the evidence names no class at
all, because an artifact that never downloaded or a test JVM that never forked
would otherwise draft every unproven violation with a reason indistinguishable
from a well-evidenced run. Drafting into a file that is also an exclude file is
refused for the same reason it looks tempting: it would rewrite that file with
only the drafted rules.

An evidence path that does not exist is skipped with a warning rather than
failing the run. Evidence is data another job produces, so its absence is an
operational state, and the option can stay in a build that also runs on a
laptop or a fork PR. Nothing is promoted from a path that is not there, which
is what the warning says.

## Build-tool plugins

Setup, per-tool option spellings and tool-specific caveats live on one page per
tool:

- [Gradle](docs/gradle.md)
- [sbt](docs/sbt.md)
- [Maven](docs/maven.md)
- [Mill](docs/mill.md)
- [Clojure CLI](docs/clojure.md)
- [Leiningen](docs/leiningen.md)
- [Bazel](docs/bazel.md)

A build none of them covers drives [the CLI](docs/cli.md) by hand instead.

All of them write the same dump format: every module's resolved runtime
classpath as coordinate-annotated JSON, kept per module so a check
[runs each against its own resolution](#per-module-checking). A dump also
refers to build outputs. Each page says how its dump command builds them, and
the [PR gate](#pr-gate-on-github-actions-the-main-use-case) shows which
baseline dumps can skip them.

The upgrade-check task fetches the CLI itself as
`net.exoego.uika:uika-cli:<version>:<platform>@zip` through the build's own
dependency resolution, reusing its repositories, credentials, and cache, so
there is no separate install step. The version defaults to the plugin's own, so
one coordinate bump updates both. The Clojure CLI tool, Leiningen and Bazel
resolve the binary differently, and their pages say how. Every tool takes
`UIKA_CLI_PATH` to run a binary you already have instead, which is what an
air-gapped build needs.

Every tool spells the same options its own way, listed per page:
[`failOn`](#violation-tiers-and-the-failon-threshold),
[`excludeFiles`](#excluding-known-false-positives),
[runtime load evidence](#runtime-load-evidence-jfr) (one
directory serving both phases, collect on the base branch and consume on the
PR), and `jdkRelease`.

`jdkRelease` needs no setting at all. The build runs on a JVM, so each tool
derives the release from what the modules compile for. A build with several
modules contributes the LOWEST of them, because one value serves a run that
checks all of them, and under-claiming only costs unverified references while
over-claiming drops findings. The result is clamped to what the selected JDK's
`ct.sym` serves. Override it with the setting, or set 0 to disable it.

Each dump also records the release next to every module it lists, read the same
way. That is what lets a check notice the application's own JDK moved
between the two dumps and check that move too, scoped to the modules that made
it. A module left on an older release is never checked against a sibling's
upgrade, and a module that declares no target is recorded as running on the
build's own JVM, which is what it compiles against.

The derivation only sees what the build declares, so a project that compiles
`--release 11` and ships on a 21 runtime looks unchanged when that runtime
moves. The same override says so by hand. A positive value is recorded as the
release every module runs on, while `0` still only switches the API layer off
and leaves the recorded release derived.

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

Most escapes lead into the JDK, so every tool layers the JDK API of one release
under the resolution scope and lets those references conclude as OK or broken.
The stubs come from the `ct.sym` file of the JDK named by `UIKA_JDK` (checked
first, authoritative when set), else `JAVA_HOME`, and the release has to be
older than that JDK, whose own release `ct.sym` does not carry. The layer sits
under both the old and the new side, so gaps in `ct.sym` cancel out instead of
producing false positives from missing stubs. It is what turns 16 unverified
references into 0 on a guava 22 -> 23 check of selenium-remote-driver, with the
broken count unchanged.

Each tool derives the release from what its modules compile for, so nothing has
to be configured. The `jdkRelease` setting overrides it, and 0 switches the
layer off. Either way uika still needs no JVM to run.

### Checking a JDK upgrade

A JDK upgrade breaks an application the same way a library upgrade does, and it
needs no configuration to catch. Each dump records the API release every module
runs on, so bumping what your build compiles for and re-running the dump is
enough. The check sees the two dumps disagree and covers that move alongside
the dependency moves in one report, scoped to the modules that made it. A JDK API your classpath still references and the new release dropped is
then reported like any other removal:

```console
dependency changes: none

per-module check: 0 of 1 modules changed their resolved versions (1 unchanged)
    JDK 11 -> 17  scanned 2 classes, ❌ 1 broken, 0 unverified
...
❌ java.rmi.activation.ActivationGroup
    class removed, throws NoClassDefFoundError at first use
    used by 1 class:
        UsesRemoved  (app.jar) [JDK 11 -> 17]
```

Dumps written before the plugins recorded the release carry no value, and a
missing value on either side is never read as a JDK move. A build whose runtime
is not what it compiles for says so with the tool's `jdkRelease` setting, which
is recorded as the release of every module.

Releases below the installed JDK come from its `ct.sym`; the installed JDK's own
release comes from its `jmods/`, which `ct.sym` never carries. Checking an upgrade
*to* the JDK you now run therefore needs only that one JDK. Sealing changes are
invisible here, because `ct.sym` stubs do not carry `PermittedSubclasses`, and
reporting them from the `jmods` side alone would be a false positive.

## Development

`make check` runs fmt, clippy, and the Rust and plugin test suites.
[CONTRIBUTING.md](CONTRIBUTING.md) covers the rest, including the vendored
real-incident fixtures, the golden-bless workflow, and the JVM probe harness.
Releases are described in [PUBLISHING.md](PUBLISHING.md).

## Known limitations (PoC)

- References whose hierarchy escapes into unanalyzed classes are conservatively
  treated as OK (reported only as an "unverified" count, which a complete
  runtime classpath reduces, and which is what a dump gives you)
- Multi-release JARs are analyzed at their base classes only
  (`META-INF/versions/` is ignored)
- `InvokeDynamic` bootstrap synthetic names are excluded
- A constant-pool reference does not guarantee the code path executes (optional
  integrations guarded by try/catch may be reported yet never run)
