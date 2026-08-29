# [Leiningen plugin](../lein-plugin/) [![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Flein-uika%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/lein-uika)

One of uika's [build-tool integrations](../README.md#build-tool-plugins).

```clojure
;; project.clj
:plugins [[net.exoego.uika/lein-uika "VERSION_PLACEHOLDER"]]
;; Optional: gate only on reachable violations, and suppress known false positives.
:uika {:fail-on "reachable"
       :exclude-files ["uika-exclude.toml"]
       ;; Defaults to the plugin's own version; there is no command-line override.
       :cli-version "VERSION_PLACEHOLDER"}
```

```console
$ lein uika dump-classpath                       # writes <:target-path>/uika/classpath.json
$ lein uika dump-classpath /tmp/after.json
$ lein uika upgrade-check /tmp/before.json /tmp/after.json
```

The whole `:uika` map is
[`:fail-on`](../README.md#violation-tiers-and---fail-on),
[`:exclude-files`](../README.md#excluding-known-false-positives---exclude-file),
`:jdk-release` (0 disables), `:class-load-logs` (text format), `:jfr`,
`:draft-exclude-file` (needs `:class-load-logs` or `:jfr`), `:cli-version` and
`:cli-path`. Any other key is an error rather than a silent no-op, so a
misspelling cannot quietly disable a flag. The CLI answers a lone
`:draft-exclude-file` by naming `--class-load-log`, whose keyword form this map
rejects as unknown.

Leiningen's resolver does not handle a zip-packaged artifact, so the plugin
downloads the CLI binary straight from Maven Central (`UIKA_CLI_URL` to
override the URL, `:cli-path` or `UIKA_CLI_PATH` to point at a binary you
already have and skip the download). `:jdk-release` defaults to the release
`:javac-options` pins (`--release`, or `-target`); a project declaring neither
falls back to the JVM the project's own code runs on (`:java-cmd`, probed).

For [runtime load evidence](../README.md#runtime-load-evidence-jfr---class-load-log),
collect by running the current build's tests with the JFR flag on the test JVM
(there is no test task to inject it into, so add it by hand, the way the Maven
recipe does), then point `:jfr` at the recording directory. Recordings are
converted with the JDK's own JFR reader before the CLI runs, which needs lein
itself on Java 17+; `:class-load-logs` still takes text logs alongside.

The dump excludes what only development pulls in (the `:base`/`:system`/`:user`/`:dev`
profiles, so no nREPL, and `:provided`, which an uberjar leaves out) and runs the
project's `:prep-tasks` first, so both `:aot` classes and `:java-source-paths` output
are scanned. The [reflection caveat](clojure.md) of the Clojure code itself applies
here too; `:class-dir` does not, because the dump takes its class directories
from `:compile-path` and the project's own source and resource paths.

## PR gate on GitHub Actions

The `linkage-check` job dumps a baseline from the PR's base branch and the
PR's own classpath, and fails on broken references between the two. The
`dump-baseline` job and the marked steps are the optional caching half,
explained in [Caching the baseline](#caching-the-baseline).

```yaml
# .github/workflows/linkage-check.yml
name: linkage-check
on:
  pull_request:
    paths:
      - '**/project.clj'
      - '**/profiles.clj'
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

      # ... You may need to setup Java/Leiningen here ....

      # lein preps (javac, :prep-tasks) as a side effect of the dump
      - run: lein uika dump-classpath /tmp/classpath.json

      - uses: actions/upload-artifact@v7
        with:
          name: uika-baseline-${{ github.sha }}
          path: /tmp/classpath.json
          retention-days: 30   # a PR's base.sha is always a recent tip

      # the dump names JARs by absolute path, and this local repository is the
      # only place the old versions are guaranteed to exist
      - uses: actions/cache/save@v6
        with:
          path: ~/.m2/repository
          key: uika-baseline-m2-${{ github.sha }}

  linkage-check:
    if: github.event_name == 'pull_request'
    runs-on: ubuntu-latest
    permissions:
      contents: read
      actions: read   # to read the baseline artifact
    steps:
      - uses: actions/checkout@v7

      # ... You may need to setup Java/Leiningen here ....

      # Cached-baseline fast path. These steps skip while no artifact
      # exists. Delete them together with the dump-baseline job if you do
      # not cache.
      - name: Restore baseline dependencies
        id: baseline-deps
        uses: actions/cache/restore@v6
        with:
          path: ~/.m2/repository
          key: uika-baseline-m2-${{ github.event.pull_request.base.sha }}

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
        run: lein uika dump-classpath /tmp/after.json

      - name: Dump baseline classpath (fallback)
        id: baseline-fallback
        if: steps.baseline-artifact.outcome != 'success'
        # a PR whose base cannot produce a baseline skips the check instead
        # of failing it
        continue-on-error: true
        run: |
          git fetch --depth=1 origin ${{ github.event.pull_request.base.sha }}
          git checkout ${{ github.event.pull_request.base.sha }}
          if lein uika dump-classpath /tmp/before.json; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Check broken references
        if: steps.baseline-artifact.outcome == 'success' || steps.baseline-fallback.outcome == 'success'
        run: lein uika upgrade-check /tmp/before.json /tmp/after.json
```

### Caching the baseline

The fallback resolves the base branch on the PR runner, which puts a
second checkout and a cold Leiningen start on the PR's critical path every
time. The baseline only feeds the version diff, so the `dump-baseline` job
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
