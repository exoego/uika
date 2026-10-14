# [Leiningen plugin](../lein-plugin/) [![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Flein-uika%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/lein-uika)

One of uika's [build-tool integrations](build-tools.md).

```clojure
;; project.clj
:plugins [[net.exoego.uika/lein-uika "VERSION_PLACEHOLDER"]]
;; uika checks class files, so AOT-compile your namespaces.
:aot :all
;; Optional: suppress known false positives.
:uika {:exclude-files ["uika-exclude.toml"]}
```

```console
$ lein uika dump-classpath                       # writes <:target-path>/uika/classpath.json
$ lein uika dump-classpath /tmp/after.json
$ lein uika upgrade-check /tmp/before.json /tmp/after.json
```

The plugin downloads the CLI jar straight from Maven Central into
`~/.cache/uika` (`UIKA_CLI_URL` to override the URL, `:cli-path` or
`UIKA_CLI_PATH` to point at a CLI you already have and skip the download). The
jar runs on the JVM that runs lein, which therefore has to be Java 17 or newer.
On an older one, point `:cli-path` at a script that starts the jar on a newer JDK.
`UIKA_CLI_URL` has to name the jar.

The dump excludes what only development pulls in (the `:base`/`:system`/`:user`/`:dev`
profiles, so no nREPL, and `:provided`, which an uberjar leaves out) and runs the
project's `:prep-tasks` first, so both `:aot` classes and `:java-source-paths` output
are scanned. uika checks class files, so set `:aot :all` (or list your
namespaces). A namespace left out of `:aot` is invisible to it. Interop calls
without type hints leave no reference in the class file either, so set
`:global-vars {*warn-on-reflection* true}` and hint the calls you want checked.

## PR gate on GitHub Actions

The `linkage-check` job dumps the PR's classpath and a baseline from the PR's
base branch, and fails on broken references between the two.

```yaml
# .github/workflows/linkage-check.yml
name: linkage-check
on:
  pull_request:
    paths:
      - '**/project.clj'
      - '**/profiles.clj'
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

      # ... You may need to setup Java/Leiningen here ....

      - name: Dump PR classpath
        # lein preps (javac, :prep-tasks) as a side effect of the dump
        run: lein uika dump-classpath /tmp/after.json

      - name: Dump baseline classpath
        id: baseline
        # a PR whose base cannot produce a baseline skips the check instead
        # of failing it
        continue-on-error: true
        run: |
          git fetch --depth=1 origin ${{ github.event.pull_request.base.sha }}
          git checkout ${{ github.event.pull_request.base.sha }}
          # no :prep-tasks, so the base branch compiles nothing over the PR's classes
          if lein update-in : assoc :prep-tasks '[]' -- uika dump-classpath /tmp/before.json; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Check broken references
        if: steps.baseline.outcome == 'success'
        run: lein uika upgrade-check /tmp/before.json /tmp/after.json
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

The baseline dump is a second Leiningen start on the PR runner. Dumping it
once per push to the base branch instead, as an artifact named by SHA that the
PR job fetches by `base.sha`, moves that invocation off the PR's critical path
at the price of a job on every push, so the example does not do it.

Where that trade is worth it, save the local repository with `actions/cache`
under the same SHA and restore it before the check, because a fetched dump
names JARs by absolute path and the old versions are not on the PR runner.
Restore it for same-repo PRs only, since a fork PR's build code could read
private dependencies out of it. A compared-pair JAR uika cannot open exits 2
rather than degrading to a warning.

## Options

Every option is a key of the `:uika` map in `project.clj`. Any other key is an
error rather than a silent no-op, so a misspelling cannot quietly disable a
flag. Watch for the Clojure CLI tool's spellings: it says `:exclude-file` and
`:class-load-log` where this map says `:exclude-files` and `:class-load-logs`.
Leiningen's own `update-in` changes a key for one run:

```console
$ lein update-in :uika assoc :fail-on '"never"' -- uika upgrade-check /tmp/before.json /tmp/after.json
```

- [`:fail-on`](../README.md#violation-tiers-and-the-failon-threshold) is `"never"`,
  `"reachable"` or `"any"`. `"reachable"` needs your namespaces in `:aot`. A
  namespace left out roots nothing, and a break used only from it passes.
- [`:exclude-files`](../README.md#excluding-known-false-positives)
  takes a vector of paths.
- [`:jdk-release`](build-tools.md#jdkrelease) defaults to the release
  `:javac-options` pins (`--release`, or `-target`); a project declaring neither
  falls back to the JVM the project's own code runs on (`:java-cmd`, else
  `JAVA_CMD`, probed; a probe that fails warns and falls back to lein's own
  JVM). 0 disables the API layer.
- `:merged-classpath true` checks the union of every module's classpath once
  instead of [each module against its own
  resolution](../README.md#per-module-checking). A lein project is one module, so
  this only matters for a dump another tool wrote.
- `:jfr` takes a recording or a directory of recordings mixed with text logs,
  and `:class-load-logs` takes text [evidence](runtime-load-evidence.md) on its
  own. `:draft-exclude-file` drafts exclude rules from either, and needs one of
  them. The CLI answers a lone `:draft-exclude-file` by naming
  `--class-load-log`, whose keyword form this map rejects as unknown.
- `:cli-version` and `:cli-path` pick the CLI, as do `UIKA_CLI_VERSION` and
  `UIKA_CLI_PATH` from the environment. A key in `project.clj` wins over its
  variable, and a path wins over a version. With no `:cli-version` and no
  `UIKA_CLI_VERSION`, the CLI version is the plugin's own. The path takes the
  jar, or an executable that runs it. A path that is not a file, or an
  executable that lost its bit, fails naming the one you set, `:cli-path` or the
  variable.

## Runtime load evidence (JFR)

For [runtime load evidence](runtime-load-evidence.md), collect by running the
current build's tests with the JFR flag on the test JVM. The plugin injects
nothing, so add it yourself; a profile keeps it out of everyday runs:

```clojure
:profiles {:uika-jfr {:jvm-opts ["-XX:StartFlightRecording:jdk.ClassLoad#enabled=true,jdk.ClassLoad#stackTrace=true,filename=target/uika-jfr"]}}
```

```console
$ mkdir -p target/uika-jfr
$ lein with-profile +uika-jfr test
```

Create the directory first (given a missing parent JFR aborts JVM startup, but
given an existing parent it silently records to a single clobbered file), quote
the `filename` value if the path carries a comma (the option delimiter;
unquoted it silently truncates with exit 0), and the test JVM needs JDK 17+ for
the event-settings syntax. Then point `:jfr` at the directory. Recordings are
converted with the JDK's own JFR reader before the CLI runs, which needs lein
itself on Java 17+; `:class-load-logs` still takes text logs alongside.

The [base-branch-to-PR CI wiring](runtime-load-evidence.md#collecting-on-the-base-branch-consuming-on-the-pr)
is the same for every tool, with this page's two commands inside it.
