# Build-tool integrations

What the Gradle, sbt, Maven, Mill and Leiningen plugins, the Clojure CLI tool
and the Bazel rules all do the same way. Setup, the option spellings and the
tool-specific caveats live on [one page per tool](../README.md#usage). A build
none of them covers drives [the CLI](cli.md) by hand instead.

## The dump

All of them write the same dump format: every module's resolved runtime
classpath as coordinate-annotated JSON, kept per module so a check
[runs each against its own resolution](../README.md#per-module-checking). A dump
also refers to build outputs. Each tool page says how its dump command builds
them, and the PR gate workflow there shows which baseline dumps can skip them.

## Getting the CLI

The upgrade-check task fetches the CLI itself as
`net.exoego.uika:uika-cli:<version>:<platform>@zip` through the build's own
dependency resolution, reusing its repositories, credentials, and cache, so
there is no separate install step. The version defaults to the plugin's own, so
one coordinate bump updates both. The Clojure CLI tool, Leiningen and Bazel
resolve the binary differently, and their pages say how. Every tool takes
`UIKA_CLI_PATH` to run a binary you already have instead, which is what an
air-gapped build needs. A value that is not an executable file fails naming the
variable rather than deep inside process start-up: shipping the binary as a CI
artifact is the usual way to get it onto the runner, and `upload-artifact` does
not preserve the executable bit.

## Options

Every tool spells the same options its own way, listed per page:
[`failOn`](../README.md#violation-tiers-and-the-failon-threshold),
[`excludeFiles`](../README.md#excluding-known-false-positives), [runtime load
evidence](runtime-load-evidence.md) (one directory serving both phases, collect
on the base branch and consume on the PR), and `jdkRelease`.

## `jdkRelease`

`jdkRelease` picks the release of [the JDK API layer](jdk.md), and it needs no
setting at all. The build runs on a JVM, so each tool derives the release from
what the modules compile for. A build with several modules contributes the
LOWEST of them, because one value serves a run that checks all of them, and
under-claiming only costs unverified references while over-claiming drops
findings. The result is clamped to what the selected JDK can serve. Override it
with the setting, or set 0 to disable it.

Each dump also records the release next to every module it lists, read the same
way. That is what lets a check notice the application's own JDK moved
between the two dumps and check that move too, scoped to the modules that made
it. A module left on an older release is never checked against a sibling's
upgrade, and a module that declares no target is recorded as running on the
build's own JVM, which is what it compiles against.

The derivation only sees what the build declares, so a project that compiles
`--release 11` and ships on a 21 runtime looks unchanged when that runtime
moves. The same override says so by hand. A positive value is recorded as the
release every module runs on, while `0` still only switches the API layer off
and leaves the recorded release derived.
