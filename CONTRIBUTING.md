# Contributing

## Build and test

```console
$ make check   # OpenRewrite dry run + CLI tests + build-tool plugin checks
$ make test    # CLI tests + build-tool plugin tests
$ make build   # CLI jar + Gradle/sbt/Maven/Mill plugin builds
```

Benchmark the jar the way the plugins run it, with the launcher's flags and
`-Duika.child=true`. `cli-java/AGENTS.md` has the numbers to expect.

Java sources are kept in shape by the OpenRewrite recipes in
`tools/openrewrite/rewrite.gradle`. `make test` applies them in place before
running anything, and `make check` runs the verify-only variant that CI
enforces. `make rewrite` applies them on demand.

## Coverage

```console
$ make coverage   # writes the reports CI uploads to Codecov
```

All eight front ends are instrumented. The Clojure tool and the Leiningen plugin
use cloverage, and the rest, the CLI and the Bazel rules included, use JaCoCo.

Every JVM front end runs its tests in a second JVM, so every one of them has to
pass an agent into it. The Gradle build writes a `gradle.properties` in the
TestKit dir, which doubles as the daemon's Gradle user home. The Maven build
hands the invoker ITs a `mavenOpts`. sbt, Mill and the Bazel integration test
take the agent path from `UIKA_JACOCO_AGENT`, which `make jacoco-tools`
fetches, because none of them has a JaCoCo binding of its own. Coverage stays opt-in everywhere, so `make check`
runs uninstrumented. CI runs the instrumented targets instead, one job per build
tool, so each suite runs once there.

Three numbers are over less than the whole component.

- The Leiningen plugin is measured by `lein-plugin/test/` alone. Cloverage
  instruments namespaces in the JVM it reports from, and `it/run.sh` forks `lein
  uika`. `make lein-test` runs both halves.
- The Bazel rules are measured by `//java:manifest_test` alone. The shell ITs
  drive `bazel run` in a temp workspace, so the three mains read as untested.
- The Mill plugin is JaCoCo over Scala 3, which reached 74 of the entry point's 125
  executable lines. The rest sit in synthetic methods JaCoCo skips. scoverage
  reaches fewer still, 25, because Mill's task bodies are inline macros and Scala
  3 does not instrument inlined code. sbt does not have this problem: Scala 2.12
  puts its task bodies in ordinary methods and JaCoCo sees all 133 of
  `UikaPlugin.scala`'s executable lines.

## Test fixtures and goldens

The integration tests replay real incidents against unmodified JARs from Maven
Central, vendored under `cli-java/tests/fixtures/` (see its README for
coordinates, checksums, and licensing). Golden tests pin the full check JSON for
those scenarios (`cli-java/tests/golden/`), so any detection shift fails
`make java-cli-test` before it ships. After verifying a diff is an intended
semantic change, re-bless with `make java-cli-bless`. The scenario table is
single-sourced in `cli-java/tests/scenarios.tsv`, shared with the probe harness.

## JVM probe

`make probe` answer-checks the same scenarios against a real JVM.
`check --verdicts-json <path>` streams every reference verdict
(ok/unknown/broken) as JSON Lines, and `tools/jvm-probe/Probe.java` resolves
each one with `MethodHandles.Lookup` on the old-side and new-side classpaths. A
broken verdict the JVM links fine fails the run as a false positive. An
ok/unknown verdict that fails on the new side but linked on the old side is
listed as a false-negative candidate for triage. Violations found by walking
the class graph rather than by resolving a reference never enter the verdict
stream, so those are covered by the integration tests instead.

## Releasing

Refer [PUBLISHING.md](PUBLISHING.md).
