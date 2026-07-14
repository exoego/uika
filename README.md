# Uika (Unseen Incompatibility, Kick Away)

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fuika-cli%2Fmaven-metadata.xml&label=Maven%20Central)](https://central.sonatype.com/namespace/net.exoego.uika)

Ultra-fast and low-memory LinkageError checker for JVM.
Catches `NoSuchMethodError` and friends statically, before you ship.

## The problem

When dependency resolution picks conflicting versions, an API that a library
was compiled against can vanish from the runtime classpath and fail at runtime
with `NoSuchMethodError` / `NoClassDefFoundError`.

With modern practice of using Dependabot, Renovate, or Scala Steward bumping
versions constantly, auditing transitive dependencies by hand does not scale.

Uika catches this at PR time by analyzing every class/method reference recorded
in the referencing binary's constant pool.

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
Renovate/Dependabot/Scala Steward bumps quiet with no exclusion list. Gradle,
sbt, and Maven plugins produce the classpath dumps (neither validator supports sbt),
and detection covers visibility narrowing, static <-> instance mismatches, and
newly-final classes/members as well as removals. It is also a
dependency-free static binary: no JVM.

[BENCHMARKS.md](BENCHMARKS.md) has measured head-to-head runs against these
tools on the same inputs: wall time, peak memory, and what each one reports,
including how uika narrows to the references an upgrade actually broke while a
snapshot linkage check also surfaces pre-existing, unrelated errors.

## Usage

### CI gate on dependency-update PRs (the main use case)

Store a baseline (the resolved-classpath dump of develop) as a build artifact
on every push. The PR job only resolves its own side.

```console
# --- On push to develop (baseline generation) ---
$ ./gradlew uikaDumpClasspath -PuikaOutput=classpath.json   # store as artifact keyed by SHA

# --- On the PR job (after the normal build, so build outputs exist and
#     anchor the reachability ranking) ---
$ ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/after.json
$ fetch-artifact <merge-base SHA> classpath.json > /tmp/before.json   # CI-specific retrieval
$ ./gradlew uikaResolveClasspath \
      -PuikaInput=/tmp/before.json -PuikaResolveOutput=/tmp/before-local.json
$ ./gradlew uikaUpgradeCheck -PuikaBefore=/tmp/before-local.json -PuikaAfter=/tmp/after.json
# a violation fails the task and blocks the merge. Post the output as a PR comment
```

`uikaResolveClasspath` rewrites a baseline recorded on another machine to local
paths, and has Gradle itself fetch any missing old-version JARs using this
build's repositories and credentials.

### Local check before pushing

After bumping `libs.versions.toml`, verify with resolution only, no
compilation:

```console
$ git stash && ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/before.json && git stash pop
$ ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/after.json
$ ./gradlew uikaUpgradeCheck -PuikaBefore=/tmp/before.json -PuikaAfter=/tmp/after.json
```

### Ad-hoc investigation

"What breaks between these two versions, and who dies?" needs only the JAR
files. Uika is a static binary and does not need a JVM:

```console
$ uika diff old.jar new.jar
$ uika check --old old.jar --new new.jar --classpath ~/.gradle/caches/.../suspect.jar
```

## Command reference

```console
# List breaking changes between old/new versions of a library
# (removals, access narrowing, static/instance changes, newly-final classes/members)
$ uika diff old.jar new.jar [--json]

# Find usages of breaking changes across classpath JARs / your build output
# (--old/--new may be repeated to check several changed libraries in one run)
# Exit codes: 0 = clean, 1 = violations found, 2 = error
$ uika check --old kotlinx-coroutines-core-jvm-1.7.1.jar \
             --new kotlinx-coroutines-core-jvm-1.11.0.jar \
             --classpath ktor-io-jvm-2.3.13.jar:other-dep.jar \
             --app build/classes/kotlin/main
VIOLATION in ktor-io-jvm-2.3.13.jar
  io/ktor/utils/io/jvm/javaio/BlockingAdapter
    -> method removed: kotlinx/coroutines/EventLoopKt.processNextEventInCurrentThread ()J

scanned 372 classes, 1 broken reference(s), 5 unverified (hierarchy escapes scope)

# Detect broken references caused by every artifact whose version changed.
# When application roots are known (build outputs in the dump, or --app), violations
# are ranked: reachable first, then the ones no static path reaches.
$ uika upgrade-check --before /tmp/before.json --after /tmp/after.json
dependency changes: 1
  CHANGED io.opentelemetry:opentelemetry-sdk-common 1.42.1 -> 1.60.1

💥 reachable from the application (likely to break)
VIOLATION in .../opentelemetry-exporter-sender-okhttp-1.42.1.jar
  io/opentelemetry/exporter/sender/okhttp/internal/OkHttpUtil
    -> class removed: io/opentelemetry/sdk/internal/DaemonThreadFactory
       referenced by: io.opentelemetry:opentelemetry-exporter-sender-okhttp:1.42.1
       removed by:    io.opentelemetry:opentelemetry-sdk-common 1.42.1 -> 1.60.1
       suggestion:    align all io.opentelemetry artifacts to one version (e.g. via the matching BOM); otherwise upgrade the sender or pin opentelemetry-sdk-common to 1.42.1

⚠️  not proven reachable (no static path found; may still load via reflection)
VIOLATION in .../some-transitive-dep.jar
  ...

scanned 168496 classes, 42 broken reference(s) (💥 25 reachable, ⚠️ 17 not proven reachable)

# Debugging aid: dump the extracted API surface of a JAR
$ uika dump some.jar
```

### Reachability ranking

A changed library often drags in transitive JARs the application never touches,
so a run can report violations in code that is present on the classpath but
never loaded. When application roots are available (the module `classesDirs` in
a dump, or `--app` build outputs), uika walks the class-load graph from them and
splits the report into two sections: reachable violations (💥, likely to break)
first, then the ones it could not prove reachable (⚠️). Edges are constant-pool
class references, superclass/interface links, class-name-shaped string constants
(an over-approximation of `Class.forName`), and `META-INF/services` providers.

It never hides a violation: reachability is an over-approximation, so ⚠️ means
"no static path from the application reaches this class" (reflection driven
purely by external configuration stays invisible), a signal to deprioritize
rather than a guarantee. With no application roots (a bare
`check --classpath ...`) there is nothing to rank from, so the report stays a
single flat list.

### Exit code policy (`--fail-on`)

`check` and `upgrade-check` always print the full report; `--fail-on` only
controls whether the run exits non-zero (so CI fails). It has three values:

- `any` (default, strictest): exit 1 if any violation is found.
- `reachable`: exit 1 only when a reachable violation exists (💥). Violations
  that are not proven reachable (⚠️) do not fail the run.
- `never`: always exit 0, reporting violations as warnings only.

Because reachability is an over-approximation (reflection driven purely by
external configuration is invisible), `reachable` treats a violation whose
reachability could not be determined as reachable, consistent with the
report's 💥 grouping. Two cases feed into that: with no application roots
nothing is walked, so `reachable` behaves like `any`; and when application
roots are supplied but none matched a scanned class (the build outputs were
not compiled, so the ⚠️ labels have no basis), `reachable` again falls back
to `any` rather than passing every violation off as unreachable. Errors always
exit 2 regardless of `--fail-on`.

### Actionable suggestions

`upgrade-check` also attributes each break to the two artifacts involved and
proposes a fix: which coordinate holds the broken reference (`referenced by`),
which coordinate's version bump removed the symbol (`removed by`), and what to
do (`suggestion`). When the referencing artifact and the removed one share a
group (a version skew inside one library family, like OpenTelemetry core vs its
incubator), the advice leads with aligning the whole group via its BOM;
otherwise it suggests upgrading the referencer or pinning the removed coordinate
back. This needs coordinates, so it appears only for `upgrade-check` (the dumps
carry them), not for a bare `check --classpath`.

## Build-tool plugins

The Gradle, sbt, and Maven plugins all write the same dump format: every
module's resolved runtime classpath as coordinate-annotated JSON. Feed two
dumps to `uika upgrade-check`, or one to `uika check --classpath-file` (more
accurate than a hand-assembled classpath, and reduces unverified references).

Each plugin also provides an upgrade-check task that fetches the uika CLI
binary itself, as `net.exoego.uika:uika-cli:<version>:<platform>@zip` through the
build's own dependency resolution: repositories, credentials, and cache are
reused, and no separate install step is needed. The CLI version defaults to
the plugin's own version, so a single coordinate in the build (which Renovate,
Dependabot, or Scala Steward bumps) updates both.

The upgrade-check task fails the build on any violation by default. This maps
to the CLI's [`--fail-on`](#exit-code-policy---fail-on) policy
(`never` / `reachable` / `any`, default `any`): use `reachable` to gate only on
violations reachable from your own build outputs, or `never` to report without
ever failing the build. Set it in the build file (shown per tool below), or on
the command line when it is not fixed there (`-PuikaFailOn=`, `set uikaFailOn :=`,
`-Duika.failOn=`).

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

// Optional: fail only on reachable violations instead of the default `any`.
tasks.withType<UpgradeCheckTask>().configureEach {
    failOn.set("reachable")
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
// build.sbt — optional: fail only on reachable violations instead of the default `any`
ThisBuild / uikaFailOn := "reachable"
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
      <!-- Optional: fail only on reachable violations instead of the default `any`. -->
      <configuration>
        <failOn>reachable</failOn>
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

## PR gate on GitHub Actions

A typical setup involves:

1. Dump the base branch and run `uikaResolveClasspath` to output the resolved dependency.
2. Dump the PR branch, compile and run `uikaResolveClasspath`.
3. Compare the two dumps.

The job checks out the base commit to dump it, so the plugin must already be
declared there too, except on the PR that introduces this workflow, whose
base branch has no plugin yet. That baseline step is expected to fail there,
so it's marked `continue-on-error` and the final check step is skipped
instead of failing the PR.

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
          if ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/before.json; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Dump PR classpath
        # Compile so the PR module build outputs exist: they anchor reachability
        # ranking (violations reachable from your code are surfaced first).
        run: ./gradlew classes uikaDumpClasspath -PuikaOutput=/tmp/after.json

      - name: Check broken references
        if: steps.baseline.outcome == 'success'
        run: >
          ./gradlew uikaUpgradeCheck
          -PuikaBefore=/tmp/before.json -PuikaAfter=/tmp/after.json
        # a violation fails the job and blocks the merge
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
        # compile first so the build outputs anchor reachability ranking
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
        # compile first so the build outputs anchor reachability ranking
        run: mvn -q compile uika:dump-classpath -Duika.output=/tmp/after.json

      - name: Check broken references
        if: steps.baseline.outcome == 'success'
        run: mvn uika:upgrade-check -Duika.before=/tmp/before.json -Duika.after=/tmp/after.json
```

## As CLI

### Local check before pushing

After bumping `libs.versions.toml`, verify with resolution only, no
compilation:

```console
$ git stash && ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/before.json && git stash pop
$ ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/after.json
$ ./gradlew uikaUpgradeCheck -PuikaBefore=/tmp/before.json -PuikaAfter=/tmp/after.json
```

### Ad-hoc investigation

"What breaks between these two versions, and who dies?" needs only the JAR
files. Uika is a static binary and does not need a JVM:

```console
$ uika diff old.jar new.jar
$ uika check --old old.jar --new new.jar --classpath ~/.gradle/caches/.../suspect.jar
```

### Command reference

```console
# List breaking changes between old/new versions of a library
# (removals, access narrowing, static/instance changes, newly-final classes/members)
$ uika diff old.jar new.jar [--json]

# Find usages of breaking changes across classpath JARs / your build output
# (--old/--new may be repeated to check several changed libraries in one run)
# Exit codes: 0 = clean, 1 = violations found, 2 = error
$ uika check --old kotlinx-coroutines-core-jvm-1.7.1.jar \
             --new kotlinx-coroutines-core-jvm-1.11.0.jar \
             --classpath ktor-io-jvm-2.3.13.jar:other-dep.jar \
             --app build/classes/kotlin/main
VIOLATION in ktor-io-jvm-2.3.13.jar
  io/ktor/utils/io/jvm/javaio/BlockingAdapter
    -> method removed: kotlinx/coroutines/EventLoopKt.processNextEventInCurrentThread ()J

scanned 372 classes, 1 broken reference(s), 5 unverified (hierarchy escapes scope)

# Detect broken references caused by every artifact whose version changed.
# When application roots are known (build outputs in the dump, or --app), violations
# are ranked: reachable first, then the ones no static path reaches.
$ uika upgrade-check --before /tmp/before.json --after /tmp/after.json
dependency changes: 1
  CHANGED io.opentelemetry:opentelemetry-sdk-common 1.42.1 -> 1.60.1

💥 reachable from the application (likely to break)
VIOLATION in .../opentelemetry-exporter-sender-okhttp-1.42.1.jar
  io/opentelemetry/exporter/sender/okhttp/internal/OkHttpUtil
    -> class removed: io/opentelemetry/sdk/internal/DaemonThreadFactory
       referenced by: io.opentelemetry:opentelemetry-exporter-sender-okhttp:1.42.1
       removed by:    io.opentelemetry:opentelemetry-sdk-common 1.42.1 -> 1.60.1
       suggestion:    align all io.opentelemetry artifacts to one version (e.g. via the matching BOM); otherwise upgrade the sender or pin opentelemetry-sdk-common to 1.42.1

⚠️  not proven reachable (no static path found; may still load via reflection)
VIOLATION in .../some-transitive-dep.jar
  ...

scanned 168496 classes, 42 broken reference(s) (💥 25 reachable, ⚠️ 17 not proven reachable)

# Debugging aid: dump the extracted API surface of a JAR
$ uika dump some.jar
```

### Reachability ranking

A changed library often drags in transitive JARs the application never touches,
so a run can report violations in code that is present on the classpath but
never loaded. When application roots are available (the module `classesDirs` in
a dump, or `--app` build outputs), uika walks the class-load graph from them and
splits the report into two sections: reachable violations (💥, likely to break)
first, then the ones it could not prove reachable (⚠️). Edges are constant-pool
class references, superclass/interface links, class-name-shaped string constants
(an over-approximation of `Class.forName`), and `META-INF/services` providers.

It never hides a violation: reachability is an over-approximation, so ⚠️ means
"no static path from the application reaches this class" (reflection driven
purely by external configuration stays invisible), a signal to deprioritize
rather than a guarantee. With no application roots (a bare
`check --classpath ...`) there is nothing to rank from, so the report stays a
single flat list.

## How it works

1. Parse the old/new JARs into full API indexes with class hierarchy.
2. Pass 1: stream the consumer classpath, keeping only a class-hierarchy graph
   (a few dozen bytes per class) and the references whose owner exists in the
   old index.
3. Pass 2: re-read just the classes that resolution could actually visit
   (typically under 0.1% of the total) to obtain their member tables.
4. Resolve each reference against "new JARs + re-read classes", walking the
   inheritance hierarchy, and report references that resolved under old but
   break under new: removals, visibility narrowing, static<->instance changes,
   writes to newly-final fields, and subclassing/overriding of newly-final
   classes/methods.

Linkage is checked the way the JVM links: against the flattened runtime
classpath. Members moved to a superclass, classes relocated to another
artifact, and copies bundled inside fat JARs are not false positives.
References that escape into unanalyzed classes are counted as "unverified"
rather than silently ignored.

## Development

```console
$ make check   # cargo fmt --check + cargo test + Gradle/sbt/Maven plugin checks
$ make test    # cargo test + Gradle/sbt/Maven plugin tests
$ make build   # cargo build + Gradle/sbt/Maven plugin builds

$ cargo build --release                       # for benchmarks
$ cargo build --release --features memstats   # memory breakdown (counting allocator, slower)
```

The integration tests replay five real incidents (ktor-io/coroutines,
OpenTelemetry, Selenium/Guava, okhttp-digest/OkHttp, Koin) against unmodified
JARs from Maven Central, vendored under `cli/tests/fixtures/` (see its README
for coordinates, checksums, and licensing).

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
