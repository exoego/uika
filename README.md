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

Declare uika in your build, then run its dump and check tasks. Setup, the
option spellings, the tool-specific caveats and a copy-pasteable GitHub Actions
workflow that gates PRs (the main use case) live on one page per tool:

- [Gradle](docs/gradle.md)
- [sbt](docs/sbt.md)
- [Maven](docs/maven.md)
- [Mill](docs/mill.md)
- [Clojure CLI](docs/clojure.md)
- [Leiningen](docs/leiningen.md)
- [Bazel](docs/bazel.md)

A build none of them covers drives [the CLI](docs/cli.md) by hand instead.
[What every integration shares](docs/build-tools.md) covers the dump format, how
the CLI is fetched, and where `jdkRelease` comes from.

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

### Checking a JDK upgrade

A JDK upgrade breaks an application the same way a library upgrade does, and it
needs no configuration to catch. Each dump records the API release every module
runs on, so bumping what your build compiles for and re-running the dump is
enough. The check sees the two dumps disagree and covers that move alongside
the dependency moves in one report, scoped to the modules that made it. A JDK
API your classpath still references and the new release dropped is then
reported like any other removal:

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
JVM closes that gap: run the **current, not yet upgraded** build with JFR
recording every class load, and a ⚠️ violation whose referencing class appears
in the recording is promoted out of the tier and marked with the reflective
edge the static walk could not see:

```console
⚡ observed loading at runtime (via java.lang.Class.forName from com.example.PluginRegistry.discover(...))
```

Ingestion is promote-only, the same stance reachability takes: absence of a
load entry proves nothing beyond the observed runs, so no violation is ever
demoted or dropped because of it. The same evidence drafts an exclude file
from the opposite signal, the symbols that stayed unproven.

[Runtime load evidence](docs/runtime-load-evidence.md) covers the flag, the
plugins' one option, the text-log formats read alongside recordings, the CI
shape that collects on the base branch, and the JFR caveats.

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
