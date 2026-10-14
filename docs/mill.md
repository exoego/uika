# [Mill plugin](../mill-plugin/) [![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fmill-uika_mill1_3%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/mill-uika_mill1_3)

One of uika's [build-tool integrations](build-tools.md).
Mill 1.1.8 or newer. Declare one `UikaModule` object in `build.mill`. It holds
every [setting](#options) and carries both commands, which find every non-test
`JavaModule` in the build themselves. Only JFR collection needs a mixin on the
test modules, because `forkArgs` is a task on the test module itself.

```scala
//| mvnDeps: ["net.exoego.uika::mill-uika::VERSION_PLACEHOLDER"]

package build

import mill.*, javalib.*

object uika extends net.exoego.uika.mill.UikaModule {
  def failOn = "reachable"
  def excludeFiles = Seq("uika-exclude.toml")
}
```

An empty `object uika extends net.exoego.uika.mill.UikaModule` works too. Every
setting has a default. In a `build.mill.yaml` build the plugin goes under
`mill-build:`, and the settings are keys of the object. A complete Java project:

```yaml
mill-version: 1.1.8
mill-build:
  mvnDeps: ["net.exoego.uika::mill-uika::VERSION_PLACEHOLDER"]
extends: JavaModule

object uika:
  extends: net.exoego.uika.mill.UikaModule
  failOn: reachable
  excludeFiles: ["uika-exclude.toml"]
```

```console
$ ./mill uika.dumpClasspath                          # writes out/uika/classpath.json
$ ./mill uika.dumpClasspath --output /tmp/after.json
$ ./mill uika.upgradeCheck --before /tmp/before.json --after /tmp/after.json
```

The commands take the object's name, so `object linkage` gives
`./mill linkage.dumpClasspath`. Relative paths, in settings and arguments alike,
resolve against the workspace root.

The dump command compiles as a side effect, so the PR-side dump needs no extra
step.

`upgradeCheck` fetches the CLI as the `jvm` jar of
`net.exoego.uika:uika-cli:<version>` and runs it on the JVM that runs Mill. So it
works wherever that JVM does.

## PR gate on GitHub Actions

The `linkage-check` job dumps the PR's classpath and a baseline from the PR's
base branch, and fails on broken references between the two.

```yaml
# .github/workflows/linkage-check.yml
name: linkage-check
on:
  pull_request:
    paths:
      - '**.mill'
      - '**.sc'
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

      # ... You may need to setup Java/Mill here ....

      - name: Dump baseline classpath
        # ahead of the PR dump, so the PR's compile replaces the base's classes
        id: baseline
        # a PR whose base cannot produce a baseline skips the check instead
        # of failing it
        continue-on-error: true
        run: |
          git fetch --depth=1 origin ${{ github.event.pull_request.base.sha }}
          git checkout ${{ github.event.pull_request.base.sha }}
          if ./mill uika.dumpClasspath --output /tmp/before.json; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Dump PR classpath
        # Mill compiles as a side effect of evaluating the dump command
        run: ./mill uika.dumpClasspath --output /tmp/after.json

      - name: Check broken references
        if: steps.baseline.outcome == 'success'
        run: >
          ./mill uika.upgradeCheck
          --before /tmp/before.json --after /tmp/after.json
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

The baseline dump is a second Mill invocation on the PR runner. Dumping it
once per push to the base branch instead, as an artifact named by SHA that the
PR job fetches by `base.sha`, moves that invocation off the PR's critical path
at the price of a job on every push, so the example does not do it.

Where that trade is worth it, save the coursier cache with `actions/cache`
under the same SHA and restore it before the check, because a fetched dump
names JARs by absolute path and the old versions are not on the PR runner.
Restore it for same-repo PRs only, since a fork PR's build code could read
private dependencies out of it. A compared-pair JAR uika cannot open exits 2
rather than degrading to a warning.

## Options

Settings are tasks on the `UikaModule` object, stated once and read by both
commands. The commands take no options beyond `--output` on `dumpClasspath` and
`--before` and `--after` on `upgradeCheck`.

- [`def failOn`](../README.md#violation-tiers-and-the-failon-threshold) is the
  gate threshold. The default is `"any"`.
- [`def excludeFiles`](../README.md#excluding-known-false-positives) is a
  `Seq[String]` of exclude files. The default is none.
- [`def jdkRelease`](build-tools.md#jdkrelease) overrides the release derived
  from `javacOptions` and `scalacOptions` (their mandatory halves included,
  since Mill compiles with both). The dump records a module that declares no
  release as running on the JDK that compiles it, which is its `jvmVersion` when
  set and the JVM running Mill otherwise. A positive value is also what the dump
  records as the release every module runs on, for a build whose runtime is not
  what it compiles against. Set 0 to disable the check's API layer. The dump then keeps
  its derived values. The default, -1, derives both.
- `def mergedClasspath = true` checks the union of every module's classpath
  once instead of [each module against its own
  resolution](../README.md#per-module-checking). Per-module checking scans once
  per module, so a large build may want the union; the trade is that a break
  only one module's resolution shows can hide behind another module's version of
  the same jar.
- `def cliVersion` picks the uika-cli version to run. The default is the
  plugin's own version.
- `UIKA_JFR` also carries [text evidence](runtime-load-evidence.md). Anything in
  that directory that is not a recording is passed on unchanged, so
  `-Xlog:class+load` output and a classlist mix with the recordings. There is no
  separate setting.
- `UIKA_CLI_PATH` runs a CLI you already have instead of resolving one, so a
  build can run air-gapped or against a locally built CLI. It takes the jar, or
  an executable that runs it, such as a script that starts the jar on another
  JDK. It wins over the CLI version, nothing is downloaded, and a value that is
  not a file, or an executable that lost its bit, fails naming the variable.

## Runtime load evidence (JFR)

To collect [runtime load evidence](runtime-load-evidence.md), mix
`UikaTestModule` into the test modules, last so its `forkArgs` wins:

```scala
object test extends JavaTests, TestModule.Junit5, net.exoego.uika.mill.UikaTestModule
```

Export `UIKA_JFR=<dir>` for the test run, and keep it exported for the
`upgradeCheck` step, which reads the same variable back. One value serves both
phases. It is an environment variable rather than a setting because you switch
it on per run. As a setting it would make every forked test run record.

Collect with `test`. It is a Mill command and always forks, while a cached
`testCached` replays without forking a JVM and records nothing, the same
reason the Bazel recipe needs `--nocache_test_results`.

Your own `override def forkArgs = Seq(...)` replaces the list and drops the
injected flag. Append to `super.forkArgs()` instead. `./mill testLocal` does
not fork, so it records nothing.
[`--draft-exclude-file`](runtime-load-evidence.md) maps to
`def draftExcludeFile`.

The [base-branch-to-PR CI wiring](runtime-load-evidence.md#collecting-on-the-base-branch-consuming-on-the-pr)
is the same for every tool, with this page's two commands inside it.
