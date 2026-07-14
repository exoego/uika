# Uika (Unseen Incompatibility, Kick Away)

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fuika-cli%2Fmaven-metadata.xml&label=Maven%20Central)](https://central.sonatype.com/namespace/net.exoego.uika)

Catches `NoSuchMethodError` and friends statically, before you ship.

## The problem

When dependency resolution picks conflicting versions, an API that a library
was compiled against can vanish from the runtime classpath and fail at runtime
with `NoSuchMethodError` / `NoClassDefFoundError`.

Real example: Ktor 2.3.13 calls kotlinx-coroutines 1.7.1's
`EventLoopKt.processNextEventInCurrentThread()`, an internal API that is
`public` at the bytecode level. When Gradle resolves coroutines to 1.11.0, the
method is gone and every Ktor HTTP request dies at runtime. With Renovate,
Dependabot, or Scala Steward bumping versions constantly, auditing transitive
dependencies by hand does not scale. Uika catches this at PR time.

No compilation is needed for the library-vs-library half of the problem: every
reference is recorded in the referencing binary's constant pool, so distributed
JARs can be checked against each other as-is. References from your own code are
the compiler's job: it recompiles on a bump PR anyway (`--app` covers compiled
output when needed). Uika handles the half the compiler never sees: binaries
nobody recompiles.

## Prior art

### API diff tools: [Revapi](https://revapi.org/), [JAPICC](https://github.com/lvc/japi-compliance-checker), [MiMa](https://github.com/scala-garden/mima)

These diff two versions of one library and report API changes that could
break some consumer. Good for the library maintainer gating a release
(`uika diff` covers the same ground). They cannot tell which changes break
**your** app: they never see the consumer's classpath, so a member moved to
a superclass or supplied by another artifact is still reported as breaking
when nothing breaks.

### Classpath validators: [Linkage Checker](https://github.com/GoogleCloudPlatform/cloud-opensource-java), [missinglink](https://github.com/spotify/missinglink)

These scan a resolved classpath for references that will not link. Good for
auditing a whole dependency tree at a point in time (Linkage Checker mainly
serves Google's own library ecosystem, and missinglink is Maven-only). They
have no notion of an upgrade: every run reports all pre-existing
inconsistencies, dead code included, so gating a build on them means
maintaining exclusion lists.

### Where uika fits

Uika does both halves in one step: diff the changed library old vs new, then
resolve each real reference on your classpath the way the JVM links. Only
breakage introduced by the upgrade is reported, which keeps a PR gate on
Renovate/Dependabot/Scala Steward bumps quiet with no exclusion list. Gradle, sbt, and
Maven plugins produce the classpath dumps (neither validator supports sbt),
and detection covers visibility narrowing, static<->instance mismatches, and
newly-final classes/members as well as removals. It is also a
dependency-free static binary: no JVM, about 7s for a 2M-class classpath.

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
plugins {
    id("net.exoego.uika") version "VERSION_PLACEHOLDER"
}
```

```console
$ ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/after.json
$ ./gradlew uikaUpgradeCheck \
      -PuikaBefore=/tmp/before.json -PuikaAfter=/tmp/after.json   # -PuikaCliVersion=x.y.z to override
```

### sbt (`sbt-plugin/`) [![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fsbt-uika_2.12_1.0%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/sbt-uika_2.12_1.0)

```scala
addSbtPlugin("net.exoego.uika" % "sbt-uika" % "VERSION_PLACEHOLDER")
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
    </plugin>
  </plugins>
</build>
```

```console
$ mvn uika:dump-classpath -Duika.output=/tmp/classpath.json
$ mvn uika:upgrade-check \
      -Duika.before=/tmp/before.json -Duika.after=/tmp/after.json   # -Duika.cliVersion to override
```

### GitHub Actions

A PR-triggered job can resolve both sides on the same runner: dump the base
branch, dump the PR, and compare. The PR side is compiled first so its build
outputs anchor the reachability ranking (violations reachable from your own
code are surfaced first); the baseline only needs dependency resolution, so it
stays compile-free. Since both dumps are local, `uikaResolveClasspath` is not
needed (it is only for the split pattern in
[Usage](#ci-gate-on-dependency-update-prs-the-main-use-case), where the baseline
comes from another runner). The check task downloads the CLI binary through the
build, so the workflow installs nothing and pins no version.

The job checks out the base commit to dump it, so the plugin must already be
declared there too — except on the PR that introduces this workflow, whose
base branch has no plugin yet. That baseline step is expected to fail there,
so it's marked `continue-on-error` and the final check step is skipped
instead of failing the PR.

```yaml
name: dependency binary incompatibility check
on: pull_request

jobs:
  upgrade-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21

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

For sbt (the plugin must be in `project/plugins.sbt` on both branches, with the
same bootstrap handling), replace the three uika steps with:

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

For Maven (with the plugin declared in the root `pom.xml` so the version lives
in the build, and the same bootstrap handling):

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

### Dump format

The v2 format deduplicates artifacts into a single table with path-prefix
roots (a 59-module project's 2.6MB v1 dump becomes 140KB). Uika reads both v1
and v2.

```json
{"version": 2,
 "roots": ["/Users/.../modules-2/files-2.1/", "/repo/"],
 "artifacts": [
   {"group": "io.ktor", "name": "ktor-io-jvm", "version": "2.3.13",
    "root": 0, "path": "io.ktor/ktor-io-jvm/2.3.13/c72b.../ktor-io-jvm-2.3.13.jar"}
 ],
 "modules": [
   {"module": ":app", "classesDirs": [{"root": 1, "path": "app/build/classes/kotlin/main"}],
    "artifactRefs": [0]}
 ]}
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

## Publishing (Maven Central)

Everything under the `net.exoego.uika` group is published to Maven Central in
one shot when a GitHub release is published: the native CLI ZIPs (`uika-cli`
with classifiers `linux-x86_64`, `macos-aarch64`, `macos-x86_64`,
`windows-x86_64`), the Gradle plugin, the sbt plugin, and the Maven plugin.

Release procedure: create a GitHub release with tag `vX.Y.Z`. That is all.
`.github/workflows/publish-release.yml` builds each platform on its native
runner, stages all Maven artifacts locally, then JReleaser signs everything
in-memory and uploads a single deployment to the Central Portal
(all-or-nothing validation) and attaches the ZIPs to the GitHub release.

Versions are derived from the tag alone. No source file is rewritten.
`cli/Cargo.toml` stays at the `0.0.0-dev` placeholder: release builds embed
the tag version into `uika --version` at compile time through the
`UIKA_VERSION` environment variable (`option_env!` in `cli/src/cli.rs`), and
JVM plugin versions are injected via `-PuikaVersion` /
`set ThisBuild / version` / `-Drevision`. Every module publishes to a local
`staging-deploy` directory, and `jreleaser.yml` lists those directories as
staging repositories.

Required repository secrets: `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD`
(a [Central Portal token](https://central.sonatype.com/account) for the
verified `net.exoego` namespace) and `JRELEASER_GPG_SECRET_KEY` /
`JRELEASER_GPG_PUBLIC_KEY` / `JRELEASER_GPG_PASSPHRASE` (ASCII-armored key
pair). Publish the public key to `keyserver.ubuntu.com` so Central can verify
signatures.

Local verification:

```console
$ make native-publish-local UIKA_VERSION=0.1.0   # publish CLI ZIPs to ~/.m2 (expects ZIPs under dist/native/<classifier>/)
$ make stage-all UIKA_VERSION=0.1.0              # stage all Maven artifacts locally
$ mise exec -- jreleaser deploy --dry-run        # needs JRELEASER_* env vars. Validates POMs and signs without uploading
```

## Known limitations (PoC)

- References whose hierarchy escapes into unanalyzed classes are conservatively
  treated as OK (reported only as an "unverified" count, which passing the
  complete runtime classpath via `--classpath` reduces)
- Multi-release JARs are analyzed at their base classes only
  (`META-INF/versions/` is ignored)
- `InvokeDynamic` bootstrap synthetic names are excluded
- A constant-pool reference does not guarantee the code path executes (optional
  integrations guarded by try/catch may be reported yet never run)
