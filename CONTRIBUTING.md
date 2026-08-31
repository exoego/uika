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

Four of the eight front ends are instrumented: the CLI (cargo-llvm-cov), the
Gradle plugin and the Maven plugin (JaCoCo), and the Clojure tool (cloverage).
The Gradle and Maven reports also cover the shared `jvm-plugin-core` sources.
Both run their tests in a second JVM, so both pass an agent into it: the Gradle
build writes a `gradle.properties` in the TestKit dir, which doubles as the
daemon's Gradle user home, and the Maven build hands the invoker ITs a
`mavenOpts`. Without those the plugin task classes and the mojos read as
untested.

Coverage is opt-in on both (`-PuikaCoverage=true`, `-Pcoverage`), so `make
check` keeps running uninstrumented.

The remaining four are tested only through builds a coverage agent cannot
follow: sbt is scripted-only, the Leiningen and Bazel integration tests are
shell scripts driving their own toolchains, and Mill has no JaCoCo binding.

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
