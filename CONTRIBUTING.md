# Contributing

## Build and test

```console
$ make check   # cargo fmt --check + cargo clippy + cargo test + build-tool plugin checks
$ make test    # cargo test + build-tool plugin tests
$ make build   # cargo build + Gradle/sbt/Maven/Mill plugin builds

$ cargo build --release                       # for benchmarks
$ cargo build --release --features memstats   # memory breakdown (counting allocator, slower)
```

Measure with release builds. Debug builds are roughly 10x slower, and
`memstats` swaps in a counting allocator, so it is not a throughput benchmark.

Java sources are kept in shape by the OpenRewrite recipes in
`tools/openrewrite/rewrite.gradle`. `make test` applies them in place before
running anything, and `make check` runs the verify-only variant that CI
enforces. `make rewrite` applies them on demand.

## Coverage

```console
$ make coverage   # writes the reports CI uploads to Codecov
```

All eight front ends are instrumented. The CLI uses cargo-llvm-cov, the Clojure
tool and the Leiningen plugin use cloverage, the Bazel rules use `bazel
coverage`, and the rest use JaCoCo.

Every JVM front end runs its tests in a second JVM, so every one of them has to
pass an agent into it. The Gradle build writes a `gradle.properties` in the
TestKit dir, which doubles as the daemon's Gradle user home. The Maven build
hands the invoker ITs a `mavenOpts`. sbt and Mill take the agent path from
`UIKA_JACOCO_AGENT`, which `make jacoco-tools` fetches, because neither has a
JaCoCo binding of its own. Coverage stays opt-in everywhere, so `make check`
runs uninstrumented.

Three numbers are over less than the whole component.

- The Leiningen plugin is measured by `lein-plugin/test/` alone. Cloverage
  instruments namespaces in the JVM it reports from, and `it/run.sh` forks `lein
  uika`. `make lein-test` runs both halves.
- The Bazel rules are measured by `//java:manifest_test` alone. The shell ITs
  drive `bazel run` in a temp workspace, so the three mains read as untested.
- The Mill plugin is JaCoCo over Scala 3, which reaches 74 of `Uika.scala`'s 125
  executable lines. The rest sit in synthetic methods JaCoCo skips. scoverage
  reaches fewer still, 25, because Mill's task bodies are inline macros and Scala
  3 does not instrument inlined code. sbt does not have this problem: Scala 2.12
  puts its task bodies in ordinary methods and JaCoCo sees all 133 of
  `UikaPlugin.scala`'s executable lines.

## Test fixtures and goldens

The integration tests replay real incidents against unmodified JARs from Maven
Central, vendored under `cli/tests/fixtures/` (see its README for coordinates,
checksums, and licensing). Golden tests pin the full check JSON for those
scenarios (`cli/tests/golden/`), so any detection shift fails `cargo test`
before it ships. After verifying a diff is an intended semantic change, re-bless
with `UIKA_BLESS=1 cargo test --test golden`. The scenario table is
single-sourced in `cli/tests/scenarios.tsv`, shared with the probe harness.

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
