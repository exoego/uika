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

The three steps of the [PR gate](../README.md#pr-gate-on-github-actions-the-main-use-case)
look like this for Mill. There is no switch to skip build outputs, so the two
dumps differ only in the output path:

```yaml
name: dependency binary incompatibility check
on: pull_request

jobs:
  upgrade-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      # ... You may need to setup Java/Mill here ....

      - name: Dump baseline classpath (base branch)
        id: baseline
        continue-on-error: true
        run: |
          git checkout ${{ github.event.pull_request.base.sha }}
          if ./mill net.exoego.uika.mill.Uika/dumpClasspath --output /tmp/before.json; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Dump PR classpath
        run: ./mill net.exoego.uika.mill.Uika/dumpClasspath --output /tmp/after.json

      - name: Check broken references
        if: steps.baseline.outcome == 'success'
        run: >
          ./mill net.exoego.uika.mill.Uika/upgradeCheck
          --before /tmp/before.json --after /tmp/after.json
```

To keep the base-branch resolution off the PR's critical path, cache the
baseline as an artifact instead:
[BASELINE-CACHING.md](../BASELINE-CACHING.md).

## Options

- [`--failOn`](../README.md#violation-tiers-and---fail-on) and
  [`--excludeFile`](../README.md#excluding-known-false-positives---exclude-file)
  (repeatable) are plain command-line flags, shown above.
- [`--jdkRelease`](../README.md#build-tool-plugins) overrides the release
  derived from `javacOptions` and `scalacOptions` (their mandatory halves
  included, since Mill compiles with both). Set 0 to disable the API layer.

## Runtime load evidence (JFR)

To collect [runtime load evidence](../README.md#runtime-load-evidence-jfr---class-load-log),
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
[`--draft-exclude-file`](../README.md#runtime-load-evidence-jfr---class-load-log)
maps to `--draftExcludeFile`.
