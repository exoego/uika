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

## Prior art

### API diff tools

There are many tools to inspect binary incompatibility. These diff two versions
of one library and report the API changes between them, and they are excellent
at that job.

Each brings its own strengths: [Revapi](https://revapi.org/) models the API use-chain and
extends beyond Java to XML and other configuration. [japicmp](https://github.com/siom79/japicmp) also advises
which semantic-versioning part to bump. [roseau](https://github.com/alien-tools/roseau) builds its API model from
either source or bytecode with a strong focus on speed and accuracy. 
And [MiMa](https://github.com/scala-garden/mima) supports Scala-specific features.

`uika diff` covers the same ground more narrowly, and any of these is a good
choice a consumer can run against the two versions of a dependency to see
what changed. By design they answer "what changed in this library", not
"which of those changes break **my** app": they report every API change
whether your code, or another artifact on a flattened classpath, actually
depends on it. That second question is the one Uika takes up, and it is 
complementary to these tools rather than a replacement.

### Classpath validators

Other tools scan a fully resolved classpath for references that will not link,
which is exactly what you want for auditing a whole dependency tree at a point in
time. Both are solid at that: Google's [Linkage Checker](https://github.com/GoogleCloudPlatform/cloud-opensource-java), and Spotify's [missinglink](https://github.com/spotify/missinglink).

Because they analyze a single snapshot rather than an upgrade, every run
surfaces all pre-existing inconsistencies, including references in code
paths that never execute, so using one as a per-PR upgrade gate tends to
need a curated exclusion list. 

Uika narrows the same analysis to the breakage the upgrade itself introduces.

### Where Uika fits

Uika does both halves in one step: diff the changed library old vs new, then
resolve each real reference on your classpath the way the JVM links. Only
breakage introduced by the upgrade is reported, which keeps a PR gate on
Renovate/Dependabot/Scala Steward bumps quiet with no exclusion list.

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

[BENCHMARKS.md](BENCHMARKS.md) has measured head-to-head runs against these
tools on the same inputs: wall time, peak memory, and what each one reports,
including how uika narrows to the references an upgrade actually broke while a
snapshot linkage check also surfaces pre-existing, unrelated errors.

## Usage

Every recipe below drives the Gradle, sbt, or Maven plugin, so declare it in
your build first: see [Build-tool plugins](#build-tool-plugins).

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

For sbt:

```yaml
      - name: Dump baseline classpath (base branch)
        id: baseline
        continue-on-error: true
        run: |
          git checkout ${{ github.event.pull_request.base.sha }}
          if sbt uikaDumpClasspath && cp target/uika/classpath.json /tmp/before.json; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Dump PR classpath
        run: sbt compile uikaDumpClasspath && cp target/uika/classpath.json /tmp/after.json

      - name: Check broken references
        if: steps.baseline.outcome == 'success'
        run: sbt "uikaUpgradeCheck /tmp/before.json /tmp/after.json"
```

For Maven:

```yaml
      - name: Dump baseline classpath (base branch)
        id: baseline
        continue-on-error: true
        run: |
          git checkout ${{ github.event.pull_request.base.sha }}
          if mvn -q uika:dump-classpath -Duika.output=/tmp/before.json; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Dump PR classpath
        run: mvn -q compile uika:dump-classpath -Duika.output=/tmp/after.json

      - name: Check broken references
        if: steps.baseline.outcome == 'success'
        run: mvn uika:upgrade-check -Duika.before=/tmp/before.json -Duika.after=/tmp/after.json
```

To keep the base-branch resolution off the PR's critical path, dump the
baseline once per push instead and cache it as an artifact keyed by SHA:
[BASELINE-CACHING.md](BASELINE-CACHING.md).

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
graph from them over constant-pool class references, superclass/interface
links, class-name-shaped string constants (a `Class.forName`
over-approximation), and `META-INF/services` providers. It never hides a
violation: the walk is an over-approximation, so ⚠️ is a signal to deprioritize
rather than a guarantee, and reflection driven purely by external configuration
stays invisible. Anything not provably unreachable therefore stays 💥 — which
is also what happens in the two degraded cases, so `reachable` behaves like
`any` there. Those cases are a bare `check --classpath ...` (no roots, nothing
to walk) and roots that matched no scanned class (build outputs not compiled,
so the ⚠️ labels would have no basis); the second prints a warning naming the
cause.

**Invocation evidence (💤).** Most breaks fire when a class loads, so "is the
class reachable" is the right question for them. `AbstractMethodError` is the
exception: a concrete class that inherits an unimplemented abstract method
loads, verifies, and instantiates without complaint, and throws only when the
missing method is actually called. So for `method became abstract`,  uika
also sweeps every scanned class, plus the new version of the checked library,
for references to the affected member, keeping those whose owner can dispatch
onto the broken class (the class itself, a supertype, or a subtype).
When none exists the violation is latent:

```text
--------------------------------------------------------------------------------
💤 latent (class reachable, but no scanned code invokes the affected member)
--------------------------------------------------------------------------------

❌ app.B  (build/classes/java/main)
    inherits abstract lib.A.heap() without implementing it
        throws AbstractMethodError only when heap is first called (no invocation found in scanned bytecode)
```

That evidence comes from scanned bytecode rather than from application roots,
so 💤 survives both degraded cases above and keeps its own section and count
even in a bare `check --classpath` run. Like ⚠️ it is a confidence tier and not
a proof: a call through reflection or JNI, or from code outside the scan, stays
invisible. It separates "this breaks in production now" from "this is latent
until something starts calling the method".
[`--exclude-file`](#excluding-known-false-positives---exclude-file) can drop
the whole category with `kind = "method_became_abstract"`.

Both downgrades err toward 💥. When a class's hierarchy leaves the scanned
scope the unseen types could relate an otherwise unrelated caller, so the break
stays 💥 rather than being downgraded. The one exception is an escape into
`java.*`, which the JVM reserves: those types are always platform classes, so
they cannot hide a library class. Without that carve-out a class implementing
`Serializable` would never be reported latent.

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

## Build-tool plugins (Gradle, sbt, and Maven)

All three plugins write the same dump format: every module's resolved runtime
classpath as coordinate-annotated JSON, kept per module so `upgrade-check` can
[check each against its own resolution](#per-module-checking-upgrade-check).
Feed two dumps to `uika upgrade-check`, or one to `uika check
--classpath-file` (more accurate than a hand-assembled classpath, and reduces
unverified references).

A dump also refers to build outputs, so the Gradle task builds them by default
(`-PuikaBuildOutputs=false` for a resolution-only dump); sbt compiles as a side
effect of the dump task, and Maven needs a `compile` phase in the same
invocation.

The upgrade-check task fetches the CLI itself as
`net.exoego.uika:uika-cli:<version>:<platform>@zip` through the build's own
dependency resolution, reusing its repositories, credentials, and cache, so
there is no separate install step. The version defaults to the plugin's own, so
one coordinate bump updates both.

The settings shown per tool below also have command-line forms:
[`failOn`](#violation-tiers-and---fail-on) (`-PuikaFailOn=`, `set uikaFailOn
:=`, `-Duika.failOn=`) and
[`excludeFiles`](#excluding-known-false-positives---exclude-file)
(`-PuikaExcludeFile=` for a single file). `--jdk-release` needs no setting at
all: the build runs on a JVM, so the release is derived from the Gradle
toolchain, `maven.compiler.release`/`target`, or the sbt build JVM, clamped to
what that JVM's `ct.sym` serves. Override with `jdkRelease` / `uikaJdkRelease`
/ `<jdkRelease>` (`-PuikaJdkRelease=`, `-Duika.jdkRelease=`); 0 disables it.

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

## How it works

1. Parse the old/new JARs into full API indexes with class hierarchy.
2. Pass 1: stream the consumer classpath, keeping only a class-hierarchy graph
   (a few dozen bytes per class) and the references whose owner exists in the
   old index.
3. Pass 2: re-read just the classes that resolution could actually visit
   (typically under 0.1% of the total) to obtain their member tables.
4. Resolve each reference against "new JARs + re-read classes", walking the
   inheritance hierarchy, and report references that resolved under old but
   break under new.

Linkage is checked the way the JVM links: against the flattened runtime
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

❌ java.rmi.activation.ActivationGroup
    class removed, throws NoClassDefFoundError at first use
    used by 1 class:
        UsesRemoved  (app.jar) [JDK 11 -> 17]
```

Dumps written before the plugins recorded the release carry no value, and a
missing value on either side is never read as a JDK move.

## Development

```console
$ make check   # cargo fmt --check + cargo clippy + cargo test + Gradle/sbt/Maven plugin checks
$ make test    # cargo test + Gradle/sbt/Maven plugin tests
$ make build   # cargo build + Gradle/sbt/Maven plugin builds

$ cargo build --release                       # for benchmarks
$ cargo build --release --features memstats   # memory breakdown (counting allocator, slower)
```

The integration tests replay real incidents against unmodified JARs from Maven
Central, vendored under `cli/tests/fixtures/` (see its README for coordinates,
checksums, and licensing). Golden tests pin the full check JSON for those
scenarios (`cli/tests/golden/`), so any detection shift fails `cargo test`
before it ships. After verifying a diff is an intended semantic change, re-bless with
`UIKA_BLESS=1 cargo test --test golden`. The scenario table is single-sourced
in `cli/tests/scenarios.tsv`, shared with the probe harness.

`make probe` answer-checks the same scenarios against a real JVM:
`check --verdicts-json <path>` streams every reference verdict
(ok/unknown/broken) as JSON Lines, and `tools/jvm-probe/Probe.java` resolves
each one with `MethodHandles.Lookup` on the old-side and new-side classpaths. A
broken verdict the JVM links fine fails the run as a false positive; an
ok/unknown verdict that fails on the new side but linked on the old side is
listed as a false-negative candidate for triage. Violations found by walking
the class graph rather than by resolving a reference never enter the verdict
stream, so those are covered by the integration tests instead.

## Publishing

Refer [PUBLISHING.md](PUBLISHING.md).

## Known limitations (PoC)

- References whose hierarchy escapes into unanalyzed classes are conservatively
  treated as OK (reported only as an "unverified" count, which passing the
  complete runtime classpath via `--classpath` reduces)
- Multi-release JARs are analyzed at their base classes only
  (`META-INF/versions/` is ignored)
- `InvokeDynamic` bootstrap synthetic names are excluded
- A constant-pool reference does not guarantee the code path executes (optional
  integrations guarded by try/catch may be reported yet never run)
