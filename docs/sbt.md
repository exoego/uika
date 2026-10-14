# [sbt plugin](../sbt-plugin/) [![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fsbt-uika_2.12_1.0%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/sbt-uika_2.12_1.0)

One of uika's [build-tool integrations](build-tools.md).
sbt 1.9 or newer, running on Java 17 or newer.

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
$ sbt uikaDumpClasspath   # writes target/uika/classpath.json (override via uikaOutput, set bare in build.sbt or at ThisBuild)
$ sbt "uikaUpgradeCheck /tmp/before.json /tmp/after.json"   # uikaCliVersion setting to override
```

The dump task compiles as a side effect, so the PR-side dump needs no extra
step. `uikaDumpClasspath` always writes the whole build, whichever subproject
you run it from. It is assembled from `uikaModuleClasspath`, one per subproject.

`uikaUpgradeCheck` fetches the CLI as the `jvm` jar of
`net.exoego.uika:uika-cli:<version>` and runs it on the JVM that runs sbt.

## PR gate on GitHub Actions

The `linkage-check` job dumps the PR's classpath and a baseline from the PR's
base branch, and fails on broken references between the two.

```yaml
# .github/workflows/linkage-check.yml
name: linkage-check
on:
  pull_request:
    paths:
      - '**.sbt'
      - 'project/**.scala'
      - project/build.properties
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

      # ... You may need to setup Java/sbt here ....

      - name: Dump baseline classpath
        # ahead of the PR dump, so the PR's compile replaces the base's classes
        id: baseline
        # a PR whose base cannot produce a baseline skips the check instead
        # of failing it
        continue-on-error: true
        run: |
          git fetch --depth=1 origin ${{ github.event.pull_request.base.sha }}
          git checkout ${{ github.event.pull_request.base.sha }}
          if sbt uikaDumpClasspath && cp target/uika/classpath.json /tmp/before.json; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Dump PR classpath
        # sbt compiles as a side effect of evaluating the dump task
        run: sbt uikaDumpClasspath && cp target/uika/classpath.json /tmp/after.json

      - name: Check broken references
        if: steps.baseline.outcome == 'success'
        run: sbt "uikaUpgradeCheck /tmp/before.json /tmp/after.json"
```

If the PR workflow already compiles the project and later jobs can reuse that
work, through a remote build cache or by running on the same runner, give this
job a `needs:` on those jobs, in the same workflow file. The PR dump then
takes its compiled classes from them instead of compiling again.

`paths:` keeps the check off PRs that touch no dependency declaration. A
required status check cannot use it, because a skipped workflow creates no
check run and the PR waits on it forever. For a required check, trigger on
every PR and skip the job from a job-level `if:` that reads the changed files.
A skipped job counts as passed.

### Caching the baseline

The baseline dump is a second sbt start on the PR runner. Dumping it once per
push to the base branch instead, as an artifact named by SHA that the PR job
fetches by `base.sha`, moves that invocation off the PR's critical path at the
price of a job on every push, so the example does not do it.

Where that trade is worth it, save the coursier and ivy caches with
`actions/cache` under the same SHA and restore it before the check, because a
fetched dump names JARs by absolute path and the old versions are not on the
PR runner. Restore it for same-repo PRs only, since a fork PR's build code
could read private dependencies out of it. A compared-pair JAR uika cannot
open exits 2 rather than degrading to a warning.

## Options

- [`failOn`](../README.md#violation-tiers-and-the-failon-threshold) and
  [`excludeFiles`](../README.md#excluding-known-false-positives)
  are settings, shown above. `set uikaFailOn := "reachable"` works from the
  sbt shell without editing the build.
- [`jdkRelease`](build-tools.md#jdkrelease) is derived from
  `javacOptions` and `scalacOptions`. A module that declares no release in
  either counts as the JDK `javaHome` names, else the JVM running sbt.
  Override with `uikaJdkRelease :=`, or set 0 to disable the API layer.
- `uikaMergedClasspath := true` checks the union of every module's classpath
  once instead of [each module against its own
  resolution](../README.md#per-module-checking). Per-module checking scans once
  per module, so a large build may want the union; the trade is that a break
  only one module's resolution shows can hide behind another module's version of
  the same jar.
- `uikaJfr` also carries [text evidence](runtime-load-evidence.md). Anything in
  that directory that is not a recording is passed on unchanged, so
  `-Xlog:class+load` output and a classlist mix with the recordings. There is
  no separate key.
- `UIKA_CLI_PATH` runs a CLI you already have instead of resolving one, so a
  build can run air-gapped or against a locally built CLI. It takes the jar, or
  an executable that runs it, such as a script that starts the jar on another
  JDK. It wins over the CLI version, nothing is downloaded, and a value that is
  not a file, or an executable that lost its bit, fails naming the variable.

## Runtime load evidence (JFR)

`uikaJfr := Some(file("<dir>"))` in `build.sbt` (bare or `ThisBuild`-scoped)
makes forked test JVMs record class loads into a [JFR
recording](runtime-load-evidence.md) there, and makes `uikaUpgradeCheck`
convert and read the directory back. It needs
`ThisBuild / Test / fork := true`. A bare `Test / fork := true` in `build.sbt`
forks only the root project's tests, and a test that does not fork runs inside
sbt's own JVM, which no flag can reach after startup.
[`--draft-exclude-file`](runtime-load-evidence.md) maps to
`uikaDraftExcludeFile :=`.

The [base-branch-to-PR CI wiring](runtime-load-evidence.md#collecting-on-the-base-branch-consuming-on-the-pr)
is the same for every tool, with this page's two commands inside it.
