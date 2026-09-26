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

The upgrade-check task fetches the CLI itself, the pure-Java jar published as
the `jvm` classifier of `net.exoego.uika:uika-cli:<version>`, through the
build's own dependency resolution, reusing its repositories, credentials, and
cache, so there is no separate install step. It runs the jar on the JVM that
runs the build. The dump and the check both need that JVM to be Java 17 or
newer. The version defaults to the plugin's own, so one coordinate bump updates
both. The Clojure CLI tool, Leiningen and Bazel fetch the jar differently, and
their pages say how.

Every tool takes `UIKA_CLI_PATH` to run a CLI you already have instead, which is
what an air-gapped build needs. It takes the jar, or an executable that runs it,
such as a script that starts the jar on another JDK. A value that is not a file,
or a script without its executable bit, fails naming the variable.
`actions/upload-artifact` does not keep that bit, so run `chmod +x` on a script
fetched as an artifact, or point the variable at the jar, which needs none.

## Options

Every tool spells the same options its own way, listed per page:
[`failOn`](../README.md#violation-tiers-and-the-failon-threshold),
[`excludeFiles`](../README.md#excluding-known-false-positives), [runtime load
evidence](runtime-load-evidence.md) (one directory serving both phases, collect
on the base branch and consume on the PR), `jdkRelease`, and
`mergedClasspath`.

One row per option, one column per tool. A cell is how that tool spells the
option; the Gradle task property and the Maven POM element are the spelling a
build script uses, and the `-P` and `-D` forms are the command line.

| Option | Gradle | sbt | Maven | Mill | Clojure CLI | Leiningen | Bazel |
|---|---|---|---|---|---|---|---|
| Gate threshold | `-PuikaFailOn` / `failOn` | `uikaFailOn` | `-Duika.failOn` / `<failOn>` | `--failOn` | `:fail-on` | `:fail-on` | `fail_on` |
| Exclude files | `-PuikaExcludeFile` / `excludeFiles` | `uikaExcludeFiles` | `-Duika.excludeFiles` / `<excludeFiles>` | `--excludeFile` | `:exclude-file` | `:exclude-files` | `exclude_files` |
| JDK API release | `-PuikaJdkRelease` / `jdkRelease` | `uikaJdkRelease` | `-Duika.jdkRelease` / `<jdkRelease>` | `--jdkRelease` | `:jdk-release` | `:jdk-release` | `jdk_release` |
| Merged classpath | `-PuikaMergedClasspath` / `mergedClasspath` | `uikaMergedClasspath` | `-Duika.mergedClasspath` / `<mergedClasspath>` | `--mergedClasspath` | `:merged-classpath` | `:merged-classpath` | `merged_classpath` |
| Runtime evidence (JFR) | `-PuikaJfr` | `uikaJfr` | `-Duika.jfr` / `<jfr>` | `--jfr` | `:jfr` | `:jfr` | `--jfr` |
| Text class-load logs | `classLoadLogs` | in `uikaJfr` | in `<jfr>` | in `--jfr` | `:class-load-log` | `:class-load-logs` | `--classLoadLog` |
| Draft exclude file | `-PuikaDraftExcludeFile` / `draftExcludeFile` | `uikaDraftExcludeFile` | `-Duika.draftExcludeFile` / `<draftExcludeFile>` | `--draftExcludeFile` | `:draft-exclude-file` | `:draft-exclude-file` | `--draftExcludeFile` |
| CLI version | `-PuikaCliVersion` / `cliVersion` | `uikaCliVersion` | `-Duika.cliVersion` / `<cliVersion>` | `--cliVersion` | `:cli-version`, `UIKA_CLI_VERSION` | `:cli-version`, `UIKA_CLI_VERSION` | `uika.cli(version)` |
| CLI to run instead | `UIKA_CLI_PATH` | `UIKA_CLI_PATH` | `UIKA_CLI_PATH` | `UIKA_CLI_PATH` | `:cli-path`, `UIKA_CLI_PATH` | `:cli-path`, `UIKA_CLI_PATH` | `UIKA_CLI_PATH` |
| Dump output | `-PuikaOutput` | `uikaOutput` | `-Duika.output` | `--output` | `:output` | positional | `--output` |

Some options exist in a few tools only:

- Gradle: `-PuikaConfiguration` picks the configuration the dump resolves.
  `uikaResolveClasspath` (`-PuikaInput`, `-PuikaResolveOutput`) rehydrates a
  baseline dump written on another machine, fetching the JARs it names through
  the build's own repositories.
- Gradle and Bazel: the dump builds the modules' outputs, and a baseline dump
  skips that with `-PuikaBuildOutputs=false` or the `:<name>_baseline_dump`
  target.
- Maven, Leiningen and the Clojure CLI: the JFR collection flag goes on the test
  JVM by hand. Gradle and sbt add it themselves, Mill through its test-module
  mixin, and Bazel prints it.
- Mill: `UIKA_JFR` is the environment fallback for `--jfr`, since a Mill command
  has no build-wide setting to read.
- Clojure CLI and Leiningen: `UIKA_CLI_URL` overrides where the jar is
  downloaded from, because these two fetch it themselves rather than through a
  resolver. The Clojure CLI also takes `:dir` and `:aliases` to pick the basis it
  dumps, `:class-dir` for classes outside `:paths`, and `:evidence-work-dir` for
  where recordings are converted.
- Bazel: `--materialize <dir>` on a dump hard-links or copies every JAR it names
  into one directory, so a baseline artifact carries its own JARs. The `uika.cli`
  tag pins the jar's checksum.

Two CLI flags have no plugin option,
[`--json`](cli.md#options-shared-by-check-and-upgrade-check) and
[`--verdicts-json`](cli.md#options-shared-by-check-and-upgrade-check). To get
either, run the CLI on the dumps your build writes.

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

Below 8 is neither, since `ct.sym` carries no older stubs. The check skips the
API layer and says so on its output. The dump drops the value without a word and
keeps its derived one.
