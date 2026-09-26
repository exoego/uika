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
it works wherever that JVM does.

## PR gate on GitHub Actions

The `linkage-check` job dumps a baseline from the PR's base branch and the
PR's own classpath, and fails on broken references between the two. The
`dump-baseline` job and the marked steps are the optional caching half,
explained in [Caching the baseline](#caching-the-baseline).

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
  push:
    branches: [develop]
  workflow_dispatch:   # backfill the current tip

# a PR update supersedes the running check, and baseline dumps get per-SHA
# groups so a develop push never cancels one
concurrency:
  group: ${{ github.workflow }}-${{ github.event.pull_request.number || github.sha }}
  cancel-in-progress: true

jobs:
  # Optional: dumps the baseline once per push so the PR job can fetch it
  # instead of resolving the base branch. To opt out, delete this job, the
  # push and workflow_dispatch triggers, and the marked steps in
  # linkage-check.
  dump-baseline:
    if: github.event_name != 'pull_request'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      # ... You may need to setup Java/Gradle here ....

      - run: ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/classpath.json -PuikaBuildOutputs=false

      - uses: actions/upload-artifact@v7
        with:
          name: uika-baseline-${{ github.sha }}
          path: /tmp/classpath.json
          retention-days: 30   # a PR's base.sha is always a recent tip

  linkage-check:
    if: github.event_name == 'pull_request'
    runs-on: ubuntu-latest
    permissions:
      contents: read
      actions: read   # to read the baseline artifact
    steps:
      - uses: actions/checkout@v7

      # ... You may need to setup Java/Gradle here ....

      # Cached-baseline fast path. These steps skip while no artifact
      # exists. Delete them together with the dump-baseline job if you do
      # not cache.
      - name: Fetch baseline artifact
        id: baseline-artifact
        continue-on-error: true
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          # To look up the artifact from another run (the develop push)
          id=$(gh api \
            "repos/${{ github.repository }}/actions/artifacts?name=uika-baseline-${{ github.event.pull_request.base.sha }}&per_page=5" \
            --jq '[.artifacts[] | select(.expired == false)][0].id // empty')
          test -n "$id"
          gh api "repos/${{ github.repository }}/actions/artifacts/$id/zip" > /tmp/baseline.zip
          unzip -o /tmp/baseline.zip -d /tmp/baseline
          mv /tmp/baseline/classpath.json /tmp/before-remote.json

      - name: Rehydrate baseline to local paths
        id: rehydrate
        if: steps.baseline-artifact.outcome == 'success'
        continue-on-error: true
        run: >
          ./gradlew uikaResolveClasspath
          -PuikaInput=/tmp/before-remote.json -PuikaResolveOutput=/tmp/before.json

      - name: Dump PR classpath
        # `classes` so the build outputs anchor the reachability ranking
        run: ./gradlew classes uikaDumpClasspath -PuikaOutput=/tmp/after.json

      - name: Dump baseline classpath (fallback)
        id: baseline-fallback
        if: steps.rehydrate.outcome != 'success'
        # a PR whose base cannot produce a baseline skips the check instead
        # of failing it
        continue-on-error: true
        run: |
          git fetch --depth=1 origin ${{ github.event.pull_request.base.sha }}
          git checkout ${{ github.event.pull_request.base.sha }}
          if ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/before.json -PuikaBuildOutputs=false; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Check broken references
        if: steps.rehydrate.outcome == 'success' || steps.baseline-fallback.outcome == 'success'
        run: >
          ./gradlew uikaUpgradeCheck
          -PuikaBefore=/tmp/before.json -PuikaAfter=/tmp/after.json
```

### Caching the baseline

The fallback resolves the base branch on the PR runner, which puts a
second checkout and a cold Gradle start on the PR's critical path every time.
The baseline only feeds the version diff, so the `dump-baseline` job
produces it once per push instead. The fallback stays for SHAs with no
usable baseline. Those are SHAs predating the job, expired artifacts, and
PRs not targeting `develop`. Deleting the marked blocks is also safe,
because an `if:` reads a missing step's outcome as empty, never as
`success`.

A fetched dump names JARs by absolute path, and the old versions it names
are not on the PR runner, because the PR job resolves the new versions and
nothing pulls the old ones in on its own. A compared-pair JAR uika cannot
open exits 2 rather than degrading to a warning. The rehydrate step closes
both gaps by fetching what is missing through the build's own
repositories, mirrors, and credentials, and rewriting the dump to the
resolved local paths.

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
