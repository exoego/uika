# [Clojure CLI tool](../clojure-tool/) [![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fclojure-uika%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/clojure-uika)

One of uika's [build-tool integrations](build-tools.md).
The tool is published to Maven Central as `net.exoego.uika/clojure-uika` and
declared as a deps.edn alias, the same shape tools.build uses. The alias
carries `:ns-default`, which keeps the calls below unqualified. A tool installed
with `-Ttools install` instead needs every call qualified, as in
`exoego.uika/dump-classpath`.

```clojure
;; deps.edn
{:aliases
 {:uika {:deps {net.exoego.uika/clojure-uika {:mvn/version "VERSION_PLACEHOLDER"}}
         :ns-default exoego.uika
         ;; optional: settings every call shares
         :exec-args {:fail-on "reachable" :exclude-file "uika-exclude.toml"}}}}
```

```console
$ clojure -T:uika dump-classpath                        # writes target/uika/classpath.json
$ clojure -T:uika dump-classpath :output '"/tmp/after.json"' :aliases '[:prod]'
$ clojure -T:uika upgrade-check :before '"/tmp/before.json"' :after '"/tmp/after.json"'
```

Put your settings in the alias's `:exec-args` once. Every call accepts every
option and ignores the ones it does not use, so `dump-classpath` runs fine with
`:fail-on` set. A key on the command line overrides the same key in
`:exec-args`.

The dump records the resolved Maven coordinates from the project's own
`deps.edn` basis (`:local/root` and git deps are coordinate-less, like the other
tools' project dependencies), and `upgrade-check` downloads the CLI jar
from Maven Central into `~/.cache/uika` (`UIKA_CLI_URL` to override the URL,
`UIKA_CLI_PATH` to skip the download, or `:cli-path` to do the same from the
call). The jar runs on the JVM that runs the tool, which therefore has to be
Java 17 or newer. On an older one, point `:cli-path` at a script that starts
the jar on a newer JDK. The CLI's version
is taken from the tool's own coordinate in the runtime basis, so the one
`:mvn/version` in the alias pins the tool and the CLI together;
`:cli-version` and `UIKA_CLI_VERSION` override it, in that order.

`dump-classpath` asks the Clojure CLI for the basis with `clojure -X:deps basis`,
so it resolves exactly what your `clojure -M` and `-X` runs do. That needs Clojure
CLI 1.12.1.1558 or newer, with `clojure` on `PATH`. Your `~/.clojure/deps.edn` is
left out, because the application ships without it.

AOT-compile your namespaces before the dump. uika checks class files, so
Clojure code that is only source on the classpath is invisible to it. Run the
tools.build `compile-clj` and point `:class-dir` at its output. Interop calls
without type hints go through runtime reflection and leave no reference in the
class file either, so set `*warn-on-reflection*` and hint the calls you want
checked.

`dump-classpath` builds nothing. Run your own compile step first, such as a
tools.build task, or the dump holds none of your classes. The check then warns
that no application root matched, and `:fail-on reachable` fails like `any`.

## PR gate on GitHub Actions

The `linkage-check` job dumps the PR's classpath and a baseline from the PR's
base branch, and fails on broken references between the two. The alias lives
in the project's committed `deps.edn`, so the job needs no install step.

```yaml
# .github/workflows/linkage-check.yml
name: linkage-check
on:
  pull_request:
    paths:
      - '**/deps.edn'
      - .github/workflows/linkage-check.yml

# a PR update supersedes the running check
concurrency:
  group: ${{ github.workflow }}-${{ github.event.pull_request.number }}
  cancel-in-progress: true

jobs:
  linkage-check:
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      - uses: actions/checkout@v7

      # ... You may need to setup Java and the Clojure CLI here ....

      - name: Dump PR classpath
        # compile your Java or AOT classes first, since the dump builds nothing
        run: clojure -T:uika dump-classpath :output '"/tmp/after.json"'

      - name: Dump baseline classpath
        id: baseline
        # a PR whose base cannot produce a baseline skips the check instead
        # of failing it
        continue-on-error: true
        run: |
          git fetch --depth=1 origin ${{ github.event.pull_request.base.sha }}
          git checkout ${{ github.event.pull_request.base.sha }}
          if clojure -T:uika dump-classpath :output '"/tmp/before.json"'; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Check broken references
        if: steps.baseline.outcome == 'success'
        run: clojure -T:uika upgrade-check :before '"/tmp/before.json"' :after '"/tmp/after.json"'
```

If the PR workflow already compiles the project and later jobs can reuse that
work, through a remote build cache or by running on the same runner, give this
job a `needs:` on those jobs, in the same workflow file. The PR dump then
takes its compiled classes from them instead of compiling again.

`paths:` keeps the check off PRs that touch no dependency declaration. A
required status check cannot use it, because a skipped workflow creates no
check run and the PR waits on it forever. For a required check, trigger on
every PR and skip the job from a job-level `if:` that reads the changed files.
A skipped job counts as passed.

### Caching the baseline

The baseline dump is a second Clojure CLI start on the PR runner. Dumping it
once per push to the base branch instead, as an artifact named by SHA that the
PR job fetches by `base.sha`, moves that invocation off the PR's critical path
at the price of a job on every push, so the example does not do it.

Where that trade is worth it, save the Maven local repository and `~/.gitlibs`
with `actions/cache` under the same SHA and restore it before the check,
because a fetched dump names JARs by absolute path and the old versions are
not on the PR runner. Restore it for same-repo PRs only, since a fork PR's
build code could read private dependencies out of it. A compared-pair JAR uika
cannot open exits 2 rather than degrading to a warning.

## Options

Every option is a keyword argument, set in the alias's `:exec-args` or on the
call. It is used by `upgrade-check` unless the entry says otherwise. Every call
accepts every option below and ignores the ones it does not use. A key that no
call knows is an error rather than a silent no-op, so a misspelling cannot
quietly disable a flag. Watch for the Leiningen plugin's spellings: it says
`:exclude-files` and `:class-load-logs` where this tool says `:exclude-file`
and `:class-load-log`.

- [`:fail-on`](../README.md#violation-tiers-and-the-failon-threshold) is `never`,
  `reachable` or `any`. `reachable` needs the AOT output in the dump. Without
  it your namespaces root nothing, and a break used only from them passes.
- [`:exclude-file`](../README.md#excluding-known-false-positives)
  takes one path or a vector of paths.
- There is no module model to read a compile target from, so
  [`:jdk-release`](build-tools.md#jdkrelease) defaults to the project's
  own JVM release. Set it to override that, or to 0 to disable the API layer.
  `dump-classpath` uses it too, to record the release the application runs on,
  so one value in `:exec-args` serves both calls. There 0 keeps the derived
  value.
- `:merged-classpath true` checks the union of every module's classpath once
  instead of [each module against its own
  resolution](../README.md#per-module-checking). A `deps.edn` project is one
  module, so this only matters for a dump another tool wrote.
- `:jfr` and `:class-load-log` supply
  [runtime load evidence](#runtime-load-evidence-jfr), below.
  `:draft-exclude-file` is where rules drafted from it are written.
- `:cli-version` and `:cli-path` pick the CLI, and `UIKA_CLI_VERSION`,
  `UIKA_CLI_PATH` and `UIKA_CLI_URL` do the same from the environment. The path
  takes the jar, or an executable that runs it. A path that is not a file, or an
  executable that lost its bit, fails naming the one you set, `:cli-path` or the
  variable. `UIKA_CLI_URL` has to name the jar.
- `dump-classpath` alone uses `:output` (default
  `target/uika/classpath.json`), `:dir` to point at another project's
  `deps.edn` (default: where the tool was invoked), `:aliases` to include in
  the resolution, and `:class-dir` for the AOT output of a tools.build
  `compile-clj`. The project's own `:paths` are recorded too, but only those
  that exist as directories. A `:class-dir` is recorded even when it is missing,
  and the check then warns that it skipped it. `upgrade-check` ignores `:dir`,
  so its paths stay relative to where you run it.
- `upgrade-check` alone uses `:evidence-work-dir`, where recordings are
  converted, defaulting to `target/uika`.
- `:output`, `:before` and `:after` change on every call, so pass them on the
  command line rather than in `:exec-args`.

## Runtime load evidence (JFR)

Collect by running the current, not yet upgraded build's test suite (or a
staging soak) with [JFR recording class loads](runtime-load-evidence.md). There
is no test task to inject the flag into, so add it to your own test JVM
invocation, the way the Maven recipe does:

```console
-XX:StartFlightRecording:jdk.ClassLoad#enabled=true,jdk.ClassLoad#stackTrace=true,filename=<dir>
```

Create `<dir>` first: given a missing parent JFR aborts JVM startup, but given
an existing parent it silently records to a single clobbered file at that
path. Quote the `filename` value when the path carries a comma — the comma is
the option delimiter, and an unquoted one silently truncates `filename=` with
exit 0, leaving the directory empty.

Consume with `:jfr`, pointed at that directory or at a single recording:

```console
$ clojure -T:uika upgrade-check :before '"/tmp/before.json"' :after '"/tmp/after.json"' \
      :jfr '"/tmp/uika-jfr"'
```

Recordings are converted with the JDK's own JFR reader before the CLI runs,
text logs in the same directory ride along unchanged, and a recording handed to
`:class-load-log` is converted too. Conversion needs the tool itself on Java
17+, the same floor the recording test JVMs already have for the flag syntax.
[`:draft-exclude-file`](runtime-load-evidence.md) drafts exclude rules from the
same evidence.

The [base-branch-to-PR CI wiring](runtime-load-evidence.md#collecting-on-the-base-branch-consuming-on-the-pr)
is the same for every tool, with this page's two commands inside it.
