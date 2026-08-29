# [Mill plugin](../mill-plugin/) [![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fmill-uika_mill1_3%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/mill-uika_mill1_3)

One of uika's [build-tool integrations](../README.md#build-tool-plugins).
Mill 1.x. One header line wires up a build of any size: the commands find every
non-test `JavaModule` themselves. Only JFR collection needs a mixin, because
`forkArgs` is a task on the test module itself.

```scala
//| mvnDeps: ["net.exoego.uika::mill-uika::VERSION_PLACEHOLDER"]

package build

import mill.*, javalib.*
```

```console
$ ./mill net.exoego.uika.mill.Uika/dumpClasspath                 # writes out/uika/classpath.json
$ ./mill net.exoego.uika.mill.Uika/dumpClasspath --output /tmp/after.json
$ ./mill net.exoego.uika.mill.Uika/upgradeCheck \
      --before /tmp/before.json --after /tmp/after.json \
      --failOn reachable --excludeFile uika-exclude.toml         # --cliVersion to override
```

The dump command compiles as a side effect, so the PR-side dump needs no extra
step.

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
    paths:
      - '**.mill'
      - '**.sc'
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

      # ... You may need to setup Java/Mill here ....

      # Mill compiles as a side effect of evaluating the dump command
      - run: ./mill net.exoego.uika.mill.Uika/dumpClasspath --output /tmp/classpath.json

      - uses: actions/upload-artifact@v7
        with:
          name: uika-baseline-${{ github.sha }}
          path: /tmp/classpath.json
          retention-days: 30   # a PR's base.sha is always a recent tip

      # the dump names JARs by absolute path, and this cache is the only place
      # the old versions are guaranteed to exist
      - uses: actions/cache/save@v6
        with:
          path: ~/.cache/coursier
          key: uika-baseline-coursier-${{ github.sha }}

  linkage-check:
    if: github.event_name == 'pull_request'
    runs-on: ubuntu-latest
    permissions:
      contents: read
      actions: read   # to read the baseline artifact
    steps:
      - uses: actions/checkout@v7

      # ... You may need to setup Java/Mill here ....

      # Cached-baseline fast path. These steps skip while no artifact
      # exists. Delete them together with the dump-baseline job if you do
      # not cache.
      - name: Restore baseline dependencies
        id: baseline-deps
        # a fork PR's build code could read private dependencies out of this
        # cache, so only same-repo PRs take the fast path
        if: github.event.pull_request.head.repo.full_name == github.repository
        uses: actions/cache/restore@v6
        with:
          path: ~/.cache/coursier
          key: uika-baseline-coursier-${{ github.event.pull_request.base.sha }}

      - name: Fetch baseline artifact
        id: baseline-artifact
        # without the old JARs the baseline would under-report, so skip it
        if: steps.baseline-deps.outputs.cache-hit == 'true'
        continue-on-error: true
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          id=$(gh api \
            "repos/${{ github.repository }}/actions/artifacts?name=uika-baseline-${{ github.event.pull_request.base.sha }}&per_page=5" \
            --jq '[.artifacts[] | select(.expired == false)][0].id // empty')
          test -n "$id"
          gh api "repos/${{ github.repository }}/actions/artifacts/$id/zip" > /tmp/baseline.zip
          unzip -o /tmp/baseline.zip -d /tmp/baseline
          mv /tmp/baseline/classpath.json /tmp/before.json

      - name: Dump PR classpath
        run: ./mill net.exoego.uika.mill.Uika/dumpClasspath --output /tmp/after.json

      - name: Dump baseline classpath (fallback)
        id: baseline-fallback
        if: steps.baseline-artifact.outcome != 'success'
        # a PR whose base cannot produce a baseline skips the check instead
        # of failing it
        continue-on-error: true
        run: |
          git fetch --depth=1 origin ${{ github.event.pull_request.base.sha }}
          git checkout ${{ github.event.pull_request.base.sha }}
          if ./mill net.exoego.uika.mill.Uika/dumpClasspath --output /tmp/before.json; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Check broken references
        if: steps.baseline-artifact.outcome == 'success' || steps.baseline-fallback.outcome == 'success'
        run: >
          ./mill net.exoego.uika.mill.Uika/upgradeCheck
          --before /tmp/before.json --after /tmp/after.json
```

### Caching the baseline

The fallback resolves the base branch on the PR runner, which puts a
second checkout and a cold Mill start on the PR's critical path every time.
The baseline only feeds the version diff, so the `dump-baseline` job
produces it once per push instead. The fallback stays for SHAs with no
usable baseline. Those are SHAs predating the job, expired artifacts, and
PRs not targeting `develop`. Deleting the marked blocks is also safe,
because an `if:` reads a missing step's outcome as empty, never as
`success`.

A fetched dump names JARs by absolute path. On GitHub Actions both jobs
run on the same runner image under the same `$HOME`, so those paths line
up as long as the files exist. The old-version JARs are the gap, because
the PR job resolves the new versions and nothing pulls the old ones in on
its own, and a compared-pair JAR uika cannot open exits 2 rather than
degrading to a warning. The cache save and restore close that gap.

## Options

- [`--failOn`](../README.md#violation-tiers-and-the-failon-threshold) and
  [`--excludeFile`](../README.md#excluding-known-false-positives)
  (repeatable) are plain command-line flags, shown above.
- [`--jdkRelease`](../README.md#build-tool-plugins) overrides the release
  derived from `javacOptions` and `scalacOptions` (their mandatory halves
  included, since Mill compiles with both). Set 0 to disable the API layer.
  `dumpClasspath` takes it too, where it names the release every module is
  recorded as running on, for a build whose runtime is not what it compiles
  against. There 0 means "keep the derived value" instead, because recording
  nothing would take JDK move detection down with the API layer.
- `--jfr` (or `UIKA_JFR`) also carries
  [text evidence](../README.md#runtime-load-evidence-jfr).
  Anything in that directory that is not a recording is passed on unchanged, so
  `-Xlog:class+load` output and a classlist mix with the recordings. There is no
  separate flag.
- `UIKA_CLI_PATH` runs a binary you already have instead of resolving one, so a
  build can run air-gapped or against a locally built CLI. It wins over the CLI
  version, nothing is downloaded, and a value that is not an executable file
  fails naming the variable.

## Runtime load evidence (JFR)

To collect [runtime load evidence](../README.md#runtime-load-evidence-jfr),
mix `UikaTestModule` into the test modules, last so its `forkArgs` wins:

```scala
object test extends JavaTests, TestModule.Junit5, net.exoego.uika.mill.UikaTestModule
```

Export `UIKA_JFR=<dir>` for the test run, and keep it exported for the
`upgradeCheck` step, which reads the same variable back (`--jfr` is the
explicit override). One option serves both phases. The mixin is needed
because `forkArgs` is a task on the test module itself, out of reach of a
command that finds the modules through the evaluator.

Collect with `test`. It is a Mill command and always forks, while a cached
`testCached` replays without forking a JVM and records nothing, the same
reason the Bazel recipe needs `--nocache_test_results`.

Your own `override def forkArgs = Seq(...)` replaces the list and drops the injected
flag. Append to `super.forkArgs()` instead. `./mill testLocal` does not fork, so it
records nothing.
[`--draft-exclude-file`](../README.md#runtime-load-evidence-jfr)
maps to `--draftExcludeFile`.
