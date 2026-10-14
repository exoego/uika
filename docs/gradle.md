# [Gradle plugin](../gradle-plugin/) [![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fuika-gradle-plugin%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/uika-gradle-plugin)

One of uika's [build-tool integrations](build-tools.md).
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

// Optional: gate only on reachable violations, and suppress known false positives.
uika {
    failOn = "reachable"
    excludeFiles.from("uika-exclude.toml")
}
```

Every setting lives in this one `uika {}` block of the root build script, and
every uika task reads it. Most settings also have a `-Puika*` property, such as
`-PuikaFailOn` for `failOn`, and [Options](#options) names each one. The
property wins over the block for that one invocation.

`uikaUpgradeCheck` fetches the CLI jar, and `uikaResolveClasspath` fetches
baseline JARs, through the root project's repositories. A build that declares
repositories only in subprojects or convention plugins fails there with "no
repositories are defined". Declare them in `settings.gradle.kts` under
`dependencyResolutionManagement`, or in the root build script.

```console
$ ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/after.json
$ ./gradlew uikaUpgradeCheck \
      -PuikaBefore=/tmp/before.json -PuikaAfter=/tmp/after.json   # -PuikaCliVersion=x.y.z to override
```

The dump task builds the module outputs by default. Pass
`-PuikaBuildOutputs=false` for a resolution-only dump, which is what the
[PR gate](#pr-gate-on-github-actions) uses on the base branch.
`buildOutputs = false` in the `uika {}` block does the same for every dump.

`uikaUpgradeCheck` fetches the CLI as the `jvm` jar of
`net.exoego.uika:uika-cli:<version>` and runs it on the JVM that runs Gradle. So
it works wherever that JVM does. [The JDK API layer](jdk.md) reads that JVM's
`ct.sym` too, unless a module compiles for a release that `ct.sym` does not
carry. Then it reads the `ct.sym` of the JDK that module's toolchain names, so a
daemon pinned to an older JDK in `gradle-daemon-jvm.properties` still checks a
move to the newer one. A JDK 21 `ct.sym` stops at release 20, so a daemon on 21
hands a module on 21 to its toolchain as well. A toolchain at or below the
daemon's release changes nothing.

## PR gate on GitHub Actions

The `linkage-check` job dumps the PR's classpath and a baseline from the PR's
base branch, and fails on broken references between the two.

```yaml
# .github/workflows/linkage-check.yml
name: linkage-check
on:
  pull_request:
    # every place a dependency version can be declared
    paths:
      - '**.gradle'
      - '**.gradle.kts'
      - '**.versions.toml'
      - '**/gradle.properties'
      - '**/gradle.lockfile'
      - .github/workflows/linkage-check.yml

# a PR update supersedes the running check
concurrency:
  group: ${{ github.workflow }}-${{ github.event.pull_request.number }}
  cancel-in-progress: true

jobs:
  linkage-check:
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      - uses: actions/checkout@v7

      # ... You may need to setup Java/Gradle here ....

      - name: Dump PR classpath
        run: ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/after.json

      - name: Dump baseline classpath
        id: baseline
        # a PR whose base cannot produce a baseline skips the check instead
        # of failing it
        continue-on-error: true
        run: |
          git fetch --depth=1 origin ${{ github.event.pull_request.base.sha }}
          git checkout ${{ github.event.pull_request.base.sha }}
          # the baseline only feeds the version diff, so build nothing
          if ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/before.json -PuikaBuildOutputs=false; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Check broken references
        if: steps.baseline.outcome == 'success'
        run: >
          ./gradlew uikaUpgradeCheck
          -PuikaBefore=/tmp/before.json -PuikaAfter=/tmp/after.json
```

If the PR workflow already compiles the project and later jobs can reuse that
work, through a remote build cache or by running on the same runner, give this
job a `needs:` on those jobs, in the same workflow file. The PR dump then
takes its compiled classes from them instead of compiling again. On an
85-module build, that took a dependency bump's dump from 7 minutes to 76
seconds.

`paths:` keeps the check off PRs that touch no dependency declaration. A
required status check cannot use it, because a skipped workflow creates no
check run and the PR waits on it forever. For a required check, trigger on
every PR and skip the job from a job-level `if:` that reads the changed files.
A skipped job counts as passed.

### Caching the baseline

The baseline dump runs on the daemon the PR dump started, and took 5 to 9
seconds on an 85-module build. Dumping it once per push to the base branch
instead, as an artifact named by SHA that the PR job fetches by `base.sha`,
moves that invocation off the PR's critical path at the price of a job on
every push, so the example does not do it.

Where that trade is worth it, a fetched dump names JARs by absolute path and
the old versions are not on the PR runner. `uikaResolveClasspath` rewrites a
fetched dump's absolute paths to the PR runner and fetches the old JARs it
names through the build's own repositories, mirrors and credentials, since the
PR job resolves the new versions and nothing pulls the old ones in on its own.
A compared-pair JAR uika cannot open exits 2 rather than degrading to a
warning.

## Options

The settings of the `uika {}` block, each with its `-P` form:

- [`failOn`](../README.md#violation-tiers-and-the-failon-threshold)
  (`-PuikaFailOn=`) is `any` by default.
- [`excludeFiles`](../README.md#excluding-known-false-positives)
  (`-PuikaExcludeFile=`) takes TOML files. The property is comma-separated and
  replaces the block's files for that run, so a one-off run that still wants the
  committed file lists it too. A blank property counts as unset. A path containing a comma has to go in the
  block, since the comma is the delimiter in the property.
- [`jdkRelease`](build-tools.md#jdkrelease) (`-PuikaJdkRelease=`) is the
  release the application runs on. The dump records it for every module and
  the check passes it as `--jdk-release`. Unset, each module records what
  `compileJava`'s `options.release`, else target compatibility, says, and the
  check takes the lowest of them. 0 disables the check's API layer and leaves
  the dump derived.
- `mergedClasspath` (`-PuikaMergedClasspath`) checks the union of every
  module's classpath once instead of [each module against its own
  resolution](../README.md#per-module-checking). Per-module checking scans once
  per module, so a large build may want the union; the trade is that a break
  only one module's resolution shows can hide behind another module's version of
  the same jar. A bare property means on, `=false` turns it back off.
- `configuration` (`-PuikaConfiguration=`) picks which configuration the dump
  resolves, default `runtimeClasspath`. A configuration that a project lacks or
  cannot resolve contributes no artifacts, which would leave that module in the
  dump with an empty classpath and nothing to check, so the dump task fails
  naming the project instead. Only the dump fails, and only for a project the
  java plugin touches. The setting is build-wide, so it has to name a
  configuration every such module has.
- `buildOutputs` (`-PuikaBuildOutputs=false`) builds the module outputs before
  dumping, default true.
- `cliVersion` (`-PuikaCliVersion=`) is the uika-cli version to fetch, default
  the plugin's own.
- `classLoadLogs` takes [text evidence](runtime-load-evidence.md) you produced
  some other way, such as `-Xlog:class+load` output or a classlist. `-PuikaJfr`
  adds its directory to the same list, so recordings and text logs mix freely
  there. Only `classLoadLogs` takes a bare text file: `-PuikaJfr` rejects one,
  because a test JVM told to record into it aborts at startup.
- `draftExcludeFile` (`-PuikaDraftExcludeFile=`) is described
  [below](#runtime-load-evidence-jfr).

The dump and resolve paths (`-PuikaOutput`, `-PuikaBefore`, `-PuikaAfter`,
`-PuikaInput`, `-PuikaResolveOutput`) and `-PuikaJfr` change from run to run,
so they are properties only.
- `UIKA_CLI_PATH` runs a CLI you already have instead of resolving one, so a
  build can run air-gapped or against a locally built CLI. It takes the jar, or
  an executable that runs it, such as a script that starts the jar on another
  JDK. It wins over the CLI version, nothing is downloaded, and a value that is
  not a file, or an executable that lost its bit, fails naming the variable.

## Runtime load evidence (JFR)

`-PuikaJfr=<dir>` makes every `Test` task record class loads into a [JFR
recording](runtime-load-evidence.md) there (and run for real — an `UP-TO-DATE`
or `FROM-CACHE` test task forks no JVM and would collect nothing), and makes
`uikaUpgradeCheck` convert and read the directory back. A bare `-PuikaJfr` uses
`build/uika/jfr`. [`--draft-exclude-file`](runtime-load-evidence.md) maps to
`draftExcludeFile` in the `uika {}` block and to `-PuikaDraftExcludeFile=`.

The [base-branch-to-PR CI wiring](runtime-load-evidence.md#collecting-on-the-base-branch-consuming-on-the-pr)
is the same for every tool, with this page's two commands inside it.
