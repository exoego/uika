# Mill plugin (`mill-plugin/`)

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fmill-uika_mill1_3%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/mill-uika_mill1_3)

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

## Knobs

- [`--failOn`](../README.md#violation-tiers-and---fail-on) and
  [`--excludeFile`](../README.md#excluding-known-false-positives---exclude-file)
  (repeatable) are plain command-line flags, shown above.
- [`--jdkRelease`](../README.md#build-tool-plugins) overrides the release
  derived from `javacOptions` and `scalacOptions`. Set 0 to disable the API
  layer.

## Runtime load evidence (JFR)

To collect [runtime load evidence](../README.md#runtime-load-evidence-jfr---class-load-log),
mix `UikaTestModule` into the test modules, last so its `forkArgs` wins:

```scala
object test extends JavaTests, TestModule.Junit5, net.exoego.uika.mill.UikaTestModule
```

Export `UIKA_JFR=<dir>` for the test run, then pass `--jfr <dir>` to
`upgradeCheck` (the check reads the flag, not the variable). The mixin is
needed because `forkArgs` is a task on the test module itself, out of reach of
a command that finds the modules through the evaluator.

Your own `override def forkArgs = Seq(...)` replaces the list and drops the injected
flag. Append to `super.forkArgs()` instead. `./mill testLocal` does not fork, so it
records nothing.
[`--draft-exclude-file`](../README.md#runtime-load-evidence-jfr---class-load-log)
maps to `--draftExcludeFile`.
