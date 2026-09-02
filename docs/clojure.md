# [Clojure CLI tool](../clojure-tool/) [![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fclojure-uika%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/clojure-uika)

One of uika's [build-tool integrations](build-tools.md).
The tool is published to Maven Central as `net.exoego.uika/clojure-uika` and
declared as a deps.edn alias, the same shape tools.build uses. The alias
carries `:ns-default` itself, which is what keeps the invocations below
unqualified (tools.deps resolves no usage data for a Maven coordinate, so a
`-Ttools`-installed Maven tool would need every call written as
`exoego.uika/dump-classpath`):

```clojure
;; deps.edn
{:aliases
 {:uika {:deps {net.exoego.uika/clojure-uika {:mvn/version "VERSION_PLACEHOLDER"}}
         :ns-default exoego.uika}}}
```

```console
$ clojure -T:uika dump-classpath                        # writes target/uika/classpath.json
$ clojure -T:uika dump-classpath :output '"/tmp/after.json"' :aliases '[:prod]'
$ clojure -T:uika upgrade-check :before '"/tmp/before.json"' :after '"/tmp/after.json"' \
      :fail-on reachable :exclude-file '"uika-exclude.toml"'   # :cli-version to override
```

The dump records the resolved Maven coordinates from the project's own
`deps.edn` basis (`:local/root` and git deps are coordinate-less, like the other
tools' project dependencies), and `upgrade-check` downloads the platform binary
from Maven Central (`UIKA_CLI_URL` to override the URL, `UIKA_CLI_PATH` to skip
the download, or `:cli-path` to do the same from the call). The binary's version
is taken from the tool's own coordinate in the runtime basis, so the one
`:mvn/version` in the alias pins the tool and the CLI together;
`:cli-version` and `UIKA_CLI_VERSION` override it, in that order.

One Clojure-specific caveat. Interop calls without type hints go through
runtime reflection and leave no reference in the constant pool, so uika sees
the Java dependencies on the classpath at full strength but only the
type-hinted, AOT-compiled part of the Clojure code itself; point `:class-dir`
at the tools.build `compile-clj` output to include it.

## PR gate on GitHub Actions

The `linkage-check` job dumps a baseline from the PR's base branch and the
PR's own classpath, and fails on broken references between the two. The
alias lives in the project's committed `deps.edn`, so the jobs need no
install step. The `dump-baseline` job and the marked steps are the
optional caching half, explained in
[Caching the baseline](#caching-the-baseline).

```yaml
# .github/workflows/linkage-check.yml
name: linkage-check
on:
  pull_request:
    paths:
      - '**/deps.edn'
      - .github/workflows/linkage-check.yml
  push:
    branches: [develop]
  workflow_dispatch:   # backfill the current tip

# a PR update supersedes the running check, and baseline dumps get per-SHA
# groups so a develop push never cancels one
concurrency:
  group: ${{ github.workflow }}-${{ github.event.pull_request.number || github.sha }}
  cancel-in-progress: true

jobs:
  # Optional: dumps the baseline once per push so the PR job can fetch it
  # instead of resolving the base branch. To opt out, delete this job, the
  # push and workflow_dispatch triggers, and the marked steps in
  # linkage-check.
  dump-baseline:
    if: github.event_name != 'pull_request'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      # ... You may need to setup Java and the Clojure CLI here ....

      - run: clojure -T:uika dump-classpath :output '"/tmp/classpath.json"'

      - uses: actions/upload-artifact@v7
        with:
          name: uika-baseline-${{ github.sha }}
          path: /tmp/classpath.json
          retention-days: 30   # a PR's base.sha is always a recent tip

      # the dump names JARs by absolute path, and these caches are the only
      # place the old versions are guaranteed to exist (tools.deps resolves
      # :mvn deps into the Maven local repository, git deps into ~/.gitlibs)
      - uses: actions/cache/save@v6
        with:
          path: |
            ~/.m2/repository
            ~/.gitlibs
          key: uika-baseline-deps-${{ github.sha }}

  linkage-check:
    if: github.event_name == 'pull_request'
    runs-on: ubuntu-latest
    permissions:
      contents: read
      actions: read   # to read the baseline artifact
    steps:
      - uses: actions/checkout@v7

      # ... You may need to setup Java and the Clojure CLI here ....

      # Cached-baseline fast path. These steps skip while no artifact
      # exists. Delete them together with the dump-baseline job if you do
      # not cache.
      - name: Restore baseline dependencies
        id: baseline-deps
        # a fork PR's build code could read private dependencies out of this
        # cache, so only same-repo PRs take the fast path
        if: github.event.pull_request.head.repo.full_name == github.repository
        uses: actions/cache/restore@v6
        with:
          path: |
            ~/.m2/repository
            ~/.gitlibs
          key: uika-baseline-deps-${{ github.event.pull_request.base.sha }}

      - name: Fetch baseline artifact
        id: baseline-artifact
        # without the old JARs the baseline would under-report, so skip it
        if: steps.baseline-deps.outputs.cache-hit == 'true'
        continue-on-error: true
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          id=$(gh api \
            "repos/${{ github.repository }}/actions/artifacts?name=uika-baseline-${{ github.event.pull_request.base.sha }}&per_page=5" \
            --jq '[.artifacts[] | select(.expired == false)][0].id // empty')
          test -n "$id"
          gh api "repos/${{ github.repository }}/actions/artifacts/$id/zip" > /tmp/baseline.zip
          unzip -o /tmp/baseline.zip -d /tmp/baseline
          mv /tmp/baseline/classpath.json /tmp/before.json

      - name: Dump PR classpath
        run: clojure -T:uika dump-classpath :output '"/tmp/after.json"'

      - name: Dump baseline classpath (fallback)
        id: baseline-fallback
        if: steps.baseline-artifact.outcome != 'success'
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
        if: steps.baseline-artifact.outcome == 'success' || steps.baseline-fallback.outcome == 'success'
        run: clojure -T:uika upgrade-check :before '"/tmp/before.json"' :after '"/tmp/after.json"'
```

### Caching the baseline

The fallback resolves the base branch on the PR runner, which puts a
second checkout and a cold resolution on the PR's critical path every time.
The baseline only feeds the version diff, so the `dump-baseline` job
produces it once per push instead. The fallback stays for SHAs with no
usable baseline. Those are SHAs predating the job, expired artifacts, and
PRs not targeting `develop`. Deleting the marked blocks is also safe,
because an `if:` reads a missing step's outcome as empty, never as
`success`.

A fetched dump names JARs by absolute path. On GitHub Actions both jobs
run on the same runner image under the same `$HOME`, so those paths line
up as long as the files exist. The old-version JARs are the gap, because
the PR job resolves the new versions and nothing pulls the old ones in on
its own, and a compared-pair JAR uika cannot open exits 2 rather than
degrading to a warning. The cache save and restore close that gap.

## Options

Every option is a keyword argument on the call, `upgrade-check` unless the
entry says otherwise. A key neither call accepts is an error rather than a
silent no-op, so a misspelling cannot quietly disable a flag. Watch for the
Leiningen plugin's spellings: it says `:exclude-files` and `:class-load-logs`
where this tool says `:exclude-file` and `:class-load-log`.

- [`:fail-on`](../README.md#violation-tiers-and-the-failon-threshold) is `never`,
  `reachable` or `any`.
- [`:exclude-file`](../README.md#excluding-known-false-positives)
  takes one path or a vector of paths.
- There is no module model to read a compile target from, so
  [`:jdk-release`](build-tools.md#jdkrelease) defaults to the project's
  own JVM release. Set it to override that, or to 0 to disable the API layer.
  `dump-classpath` takes it too, where it names the release the application is
  recorded as running on. There 0 means "keep the derived value" instead,
  because recording nothing would take JDK move detection down with the API
  layer.
- `:merged-classpath true` checks the union of every module's classpath once
  instead of [each module against its own
  resolution](../README.md#per-module-checking). A `deps.edn` project is one
  module, so this only matters for a dump another tool wrote.
- `:jfr` and `:class-load-log` supply
  [runtime load evidence](#runtime-load-evidence-jfr), below.
  `:draft-exclude-file` is where rules drafted from it are written.
- `:cli-version` and `:cli-path` pick the binary, and `UIKA_CLI_VERSION`,
  `UIKA_CLI_PATH` and `UIKA_CLI_URL` do the same from the environment. A path that
  is not an executable file fails naming the one you set, `:cli-path` or the
  variable.
- `dump-classpath` alone takes `:output` (default
  `target/uika/classpath.json`), `:dir` to point at another project's
  `deps.edn` (default: where the tool was invoked), `:aliases` to include in
  the resolution, and `:class-dir` for the AOT output of a tools.build
  `compile-clj`. The project's own `:paths` are recorded either way.
- `upgrade-check` alone takes `:evidence-work-dir`, where recordings are
  converted, defaulting to `target/uika`.

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
