# [sbt plugin](../sbt-plugin/)

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fsbt-uika_2.12_1.0%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/sbt-uika_2.12_1.0)

One of uika's [build-tool integrations](../README.md#build-tool-plugins).

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

The dump task compiles as a side effect, so the PR-side dump needs no extra
step.

## PR gate on GitHub Actions

The three steps of the [PR gate](../README.md#pr-gate-on-github-actions-the-main-use-case)
look like this for sbt:

```yaml
name: dependency binary incompatibility check
on: pull_request

jobs:
  upgrade-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      # ... You may need to setup Java/sbt here ....

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

To keep the base-branch resolution off the PR's critical path, cache the
baseline as an artifact instead:
[BASELINE-CACHING.md](../BASELINE-CACHING.md).

## Knobs

- [`failOn`](../README.md#violation-tiers-and---fail-on) and
  [`excludeFiles`](../README.md#excluding-known-false-positives---exclude-file)
  are settings, shown above. `set uikaFailOn := "reachable"` works from the
  sbt shell without editing the build.
- [`jdkRelease`](../README.md#build-tool-plugins) is derived from
  `javacOptions` and `scalacOptions`. Override with `uikaJdkRelease :=`, or
  set 0 to disable the API layer.

## Runtime load evidence (JFR)

`uikaJfr := Some(file("<dir>"))` in `build.sbt` (bare or `ThisBuild`-scoped)
makes forked test JVMs record class loads into a
[JFR recording](../README.md#runtime-load-evidence-jfr---class-load-log)
there, and makes `uikaUpgradeCheck` convert and read the directory back. It
needs `Test / fork := true`: an in-process test runs inside sbt's own JVM,
which no flag can reach after startup.
[`--draft-exclude-file`](../README.md#runtime-load-evidence-jfr---class-load-log)
maps to `uikaDraftExcludeFile :=`.
