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
runs the build, so an
integration works wherever its build tool does, with no per-platform binary to
be missing. The version defaults to the plugin's own, so one coordinate bump
updates both. The Clojure CLI tool, Leiningen and Bazel fetch the jar
differently, and their pages say how.

Every tool takes `UIKA_CLI_PATH` to run a CLI you already have instead, which is
what an air-gapped build needs. It takes the jar, or an executable that runs it,
such as a script that starts the jar on another JDK. A value that is not a file,
or an executable that lost its bit, fails naming the variable rather than deep
inside process start-up: shipping a script as a CI artifact is the usual way to
get it onto the runner, and `upload-artifact` does not preserve the executable
bit. A jar needs no such bit.

## Options

Every tool spells the same options its own way, listed per page:
[`failOn`](../README.md#violation-tiers-and-the-failon-threshold),
[`excludeFiles`](../README.md#excluding-known-false-positives), [runtime load
evidence](runtime-load-evidence.md) (one directory serving both phases, collect
on the base branch and consume on the PR), `jdkRelease`, and
`mergedClasspath`.

Each name is its CLI flag's, written the way the tool writes names, and the
`uika` prefix appears exactly where the namespace is flat and shared with the
whole build: `-PuikaFailOn` and `-Duika.failOn` carry it, while the Gradle task
property, the Maven POM element, and every Mill, Leiningen, Clojure and Bazel
spelling already sit inside something uika owns and do not.

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
| Draft exclude file | `-PuikaDraftExcludeFile` / `draftExcludeFile` | `uikaDraftExcludeFile` | `-Duika.draftExcludeFile` / `<draftExcludeFile>` | `--draftExcludeFile` | `:draft-exclude-file` | `:draft-exclude-file` | `--draftExcludeFile` |
| CLI version | `-PuikaCliVersion` / `cliVersion` | `uikaCliVersion` | `-Duika.cliVersion` / `<cliVersion>` | `--cliVersion` | `:cli-version` | `:cli-version` | `uika.cli(version)` |
| CLI to run instead | `UIKA_CLI_PATH` | `UIKA_CLI_PATH` | `UIKA_CLI_PATH` | `UIKA_CLI_PATH` | `:cli-path`, `UIKA_CLI_PATH` | `:cli-path`, `UIKA_CLI_PATH` | `UIKA_CLI_PATH` |
| Dump output | `-PuikaOutput` | `uikaOutput` | `-Duika.output` | `--output` | `:output` | positional | `--output` |

Some knobs exist in one tool only, each for a reason its page gives, and a
tool lacking one of them is not behind:

- Gradle: `-PuikaConfiguration` picks the configuration the dump resolves, and
  `-PuikaBuildOutputs=false` skips building outputs. Only Gradle both resolves a
  named configuration and builds outputs as part of the dump.
- Gradle and Bazel take text class-load logs directly (`classLoadLogs`,
  `--classLoadLog`). Everywhere else they ride in the JFR directory.
- sbt: `uikaModuleClasspath` answers what one subproject contributes, since the
  dump is a whole-build merge.
- Maven: the JFR collection flag is an `argLine` written by hand. No mojo can
  inject into surefire.
- Mill: `UIKA_JFR` is the environment fallback for `--jfr`, since a Mill command
  has no build-wide setting to read.
- Clojure CLI and Leiningen: `UIKA_CLI_URL` overrides where the jar is
  downloaded from, because these two fetch it themselves rather than through a
  resolver. The Clojure CLI also takes `:dir`, `:aliases` and `:class-dir` to
  describe another project's basis.
- Bazel: `--materialize` rehydrates a baseline dump from the repository cache,
  and the `uika.cli` tag pins the jar's checksum.

`DocPageContractTest` in `jvm-plugin-core` holds each page to the same section
order and checks that every knob scraped from a tool's source is named on its
page, so a knob added to a build fails the build until it is documented.

Two CLI flags are deliberately not exposed anywhere.
[`--json`](cli.md) swaps the report for JSON on stdout, and every tool prints the
CLI's output through its own logger, so it would arrive with `[INFO]` on every
line from Maven and without it from Gradle — parseable from neither.
[`--verdicts-json`](cli.md) streams raw per-reference verdicts for
answer-checking against a real JVM, which is an evaluation aid rather than
something a build acts on.

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
it. A module that declares a release is never checked against a sibling's
upgrade.

A module that declares no release is handled per tool. Gradle, Bazel, the
Clojure CLI and Leiningen record the release of the toolchain or JVM that
compiles it. sbt, Maven and Mill record nothing, and the check then gives it the
lowest release another module declares, or the build's own JVM when no module
declares one. So in those three, such a module moves with its siblings, and a
move of the build's JDK is not checked for it while any module declares a
release. Declare a release in every module, or use the override below.

The derivation only sees what the build declares, so a project that compiles
`--release 11` and ships on a 21 runtime looks unchanged when that runtime
moves. The same override says so by hand. A positive value is recorded as the
release every module runs on, while `0` still only switches the API layer off
and leaves the recorded release derived.

Below 8 is neither. The layer cannot serve it — `ct.sym` carries no older stubs
— and a dump naming it would send the next check to ask for a release that has
never existed there, failing the run outright. So a value under 8 is dropped:
the check says so on its output, the dump does not and keeps its derived value.
The two differ because the check runs once and the dump writes a module at a
time, so saying it there would repeat the same line once per module.
