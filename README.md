# Uika (Unseen Incompatibility, Kick Away)

Catches `NoSuchMethodError` and friends statically, before you ship.

## The problem

When dependency resolution picks conflicting versions, an API that a library
was compiled against can vanish from the runtime classpath and fail at runtime
with `NoSuchMethodError` / `NoClassDefFoundError`.

Real example: Ktor 2.3.13 calls kotlinx-coroutines 1.7.1's
`EventLoopKt.processNextEventInCurrentThread()`, an internal API that is
`public` at the bytecode level. When Gradle resolves coroutines to 1.11.0, the
method is gone and every Ktor HTTP request dies at runtime. With Renovate or
Dependabot bumping versions constantly, auditing transitive dependencies by
hand does not scale. Uika catches this at PR time.

No compilation is needed for the library-vs-library half of the problem: every
reference is recorded in the referencing binary's constant pool, so distributed
JARs can be checked against each other as-is. References from your own code are
the compiler's job — it recompiles on a bump PR anyway (`--app` covers compiled
output when needed). Uika handles the half the compiler never sees: binaries
nobody recompiles.

## Usage

### CI gate on dependency-update PRs (the main use case)

Store a baseline (the resolved-classpath dump of develop) as a build artifact
on every push; the PR job only resolves its own side.

```console
# --- On push to develop (baseline generation) ---
$ ./gradlew uikaDumpClasspath -PuikaOutput=classpath.json   # store as artifact keyed by SHA

# --- On the PR job (after the normal build) ---
$ ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/after.json
$ fetch-artifact <merge-base SHA> classpath.json > /tmp/before.json   # CI-specific retrieval
$ ./gradlew uikaResolveClasspath \
      -PuikaInput=/tmp/before.json -PuikaResolveOutput=/tmp/before-local.json
$ ./gradlew uikaUpgradeCheck -PuikaBefore=/tmp/before-local.json -PuikaAfter=/tmp/after.json
# a violation fails the task and blocks the merge; post the output as a PR comment
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
files — uika is a static binary and does not need a JVM:

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

scanned 372 classes, 1 broken reference(s)

# Detect broken references caused by every artifact whose version changed
$ uika upgrade-check --before /tmp/before.json --after /tmp/after.json
dependency changes: 1
  CHANGED io.opentelemetry:opentelemetry-sdk-common 1.42.1 -> 1.60.1

VIOLATION in .../opentelemetry-exporter-sender-okhttp-1.42.1.jar
  io/opentelemetry/exporter/sender/okhttp/internal/OkHttpUtil
    -> class removed: io/opentelemetry/sdk/internal/DaemonThreadFactory

# Debugging aid: dump the extracted API surface of a JAR
$ uika dump some.jar
```

## Build-tool plugins

The Gradle, sbt, and Maven plugins all write the same dump format: every
module's resolved runtime classpath as coordinate-annotated JSON. Feed two
dumps to `uika upgrade-check`, or one to `uika check --classpath-file` (more
accurate than a hand-assembled classpath, and reduces unverified references).

Each plugin also provides an upgrade-check task that fetches the uika CLI
binary itself, as `net.exoego.uika:uika-cli:<version>:<platform>@zip` through the
build's own dependency resolution — repositories, credentials, and cache are
reused, and no separate install step is needed. The CLI version defaults to
the plugin's own version, so a single coordinate in the build (which Renovate
or Dependabot bumps) updates both.

### Gradle (`gradle-plugin/`)

Implemented in Java; works with Groovy and Kotlin DSL builds (Gradle 9 /
JVM 17+).

```console
$ gradle -p gradle-plugin publishToMavenLocal
$ ./gradlew -I uika-init.gradle.kts uikaDumpClasspath -PuikaOutput=/tmp/after.json
$ ./gradlew -I uika-init.gradle.kts uikaUpgradeCheck \
      -PuikaBefore=/tmp/before.json -PuikaAfter=/tmp/after.json   # -PuikaCliVersion=x.y.z to override
```

The `-I` init script applies the plugin without touching the target repository:

```kotlin
initscript {
    repositories { mavenLocal() }
    dependencies { classpath("net.exoego.uika:uika-gradle-plugin:0.1.0") }
}
rootProject { apply<net.exoego.uika.gradle.UikaPlugin>() }
```

### sbt (`sbt-plugin/`)

An `AutoPlugin` that activates on all projects once on the plugin classpath:

```console
$ cd sbt-plugin && sbt publishLocal
$ echo 'addSbtPlugin("net.exoego.uika" % "sbt-uika" % "0.1.0")' >> project/plugins.sbt
$ sbt uikaDumpClasspath   # writes target/uika/classpath.json; override via the uikaOutput setting
$ sbt "uikaUpgradeCheck /tmp/before.json /tmp/after.json"   # uikaCliVersion setting to override
```

### Maven (`maven-plugin/`)

```console
$ mvn -f maven-plugin/pom.xml install
$ mvn net.exoego.uika:uika-maven-plugin:0.1.0:dump-classpath -Duika.output=/tmp/classpath.json
$ mvn net.exoego.uika:uika-maven-plugin:0.1.0:upgrade-check \
      -Duika.before=/tmp/before.json -Duika.after=/tmp/after.json   # -Duika.cliVersion to override
```

Declaring the plugin in the root `pom.xml` lets you invoke the shorter
`mvn uika:dump-classpath` / `mvn uika:upgrade-check` and keeps the version in
the build where bots bump it.

### GitHub Actions

A PR-triggered job can resolve both sides on the same runner: dump the base
branch, dump the PR, and compare. Resolution needs no compilation, and since
both dumps are local, `uikaResolveClasspath` is not needed (it is only for the
split pattern in [Usage](#ci-gate-on-dependency-update-prs-the-main-use-case),
where the baseline comes from another runner). The check task downloads the
CLI binary through the build, so the workflow installs nothing and pins no
version.

```yaml
name: uika
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
        run: |
          git checkout ${{ github.event.pull_request.base.sha }}
          ./gradlew -I uika-init.gradle.kts uikaDumpClasspath -PuikaOutput=/tmp/before.json
          git checkout -

      - name: Dump PR classpath
        run: ./gradlew -I uika-init.gradle.kts uikaDumpClasspath -PuikaOutput=/tmp/after.json

      - name: Check broken references
        run: >
          ./gradlew -I uika-init.gradle.kts uikaUpgradeCheck
          -PuikaBefore=/tmp/before.json -PuikaAfter=/tmp/after.json
        # a violation fails the job and blocks the merge
```

For sbt (the plugin must be in `project/plugins.sbt` on both branches), replace
the three uika steps with:

```yaml
      - name: Dump baseline classpath (base branch)
        run: |
          git checkout ${{ github.event.pull_request.base.sha }}
          sbt uikaDumpClasspath && cp target/uika/classpath.json /tmp/before.json
          git checkout -

      - name: Dump PR classpath
        run: sbt uikaDumpClasspath && cp target/uika/classpath.json /tmp/after.json

      - name: Check broken references
        run: sbt "uikaUpgradeCheck /tmp/before.json /tmp/after.json"
```

For Maven (with the plugin declared in the root `pom.xml` so the version lives
in the build):

```yaml
      - name: Dump baseline classpath (base branch)
        run: |
          git checkout ${{ github.event.pull_request.base.sha }}
          mvn -q uika:dump-classpath -Duika.output=/tmp/before.json
          git checkout -

      - name: Dump PR classpath
        run: mvn -q uika:dump-classpath -Duika.output=/tmp/after.json

      - name: Check broken references
        run: mvn uika:upgrade-check -Duika.before=/tmp/before.json -Duika.after=/tmp/after.json
```

The build must be able to resolve the `net.exoego.uika` plugin and `uika-cli` ZIP
from its configured repositories; until they are on Maven Central, that means
the usual GitHub Packages credentials in the build configuration.

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
   break under new: removals, visibility narrowing, static↔instance changes,
   writes to newly-final fields, and subclassing/overriding of newly-final
   classes/methods.

Linkage is checked the way the JVM links — against the flattened runtime
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
JARs from Maven Central, vendored under `tests/fixtures/` (see its README for
coordinates, checksums, and licensing).

## Publishing native binaries

`.github/workflows/publish-native-binaries.yml` builds each platform on its
native GitHub Actions runner and publishes the ZIPs to GitHub Packages
(`https://maven.pkg.github.com/exoego/uika`) as `net.exoego.uika:uika-cli:<version>`
with classifiers `linux-x86_64`, `macos-aarch64`, `macos-x86_64`, and
`windows-x86_64`.

```console
$ make native-publish-local UIKA_VERSION=0.1.0    # dry-run; expects ZIPs under dist/native/<classifier>/
$ make native-publish-github UIKA_VERSION=0.1.0   # needs GITHUB_ACTOR / GITHUB_TOKEN (packages: write)
```

## Known limitations (PoC)

- References whose hierarchy escapes into unanalyzed classes are conservatively
  treated as OK (reported only as an "unverified" count; passing the complete
  runtime classpath via `--classpath` reduces it)
- Multi-release JARs are analyzed at their base classes only
  (`META-INF/versions/` is ignored)
- `InvokeDynamic` bootstrap synthetic names are excluded
- A constant-pool reference does not guarantee the code path executes (optional
  integrations guarded by try/catch may be reported yet never run)
