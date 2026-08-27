# [Leiningen plugin](../lein-plugin/)

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Flein-uika%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/lein-uika)

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
`:jdk-release` (0 disables), `:class-load-logs` (text format),
`:draft-exclude-file` (needs `:class-load-logs`), `:cli-version` and
`:cli-path`. Any other key is an error rather than a silent no-op, so a
misspelling cannot quietly disable a flag. The CLI answers a lone
`:draft-exclude-file` by naming `--class-load-log`, whose keyword form this map
rejects as unknown.

Leiningen's resolver does not handle a zip-packaged artifact, so the plugin
downloads the CLI binary straight from Maven Central (`UIKA_CLI_URL` to
override the URL, `:cli-path` or `UIKA_CLI_PATH` to point at a binary you
already have and skip the download). There is no module model to read a
compile target from, so `:jdk-release` defaults to the project's own JVM
release. JFR recordings are not converted by this plugin yet; record
[text class-load logs](../README.md#runtime-load-evidence-jfr---class-load-log)
with `-Xlog:class+load` and point `:class-load-logs` at them.

The dump excludes what only development pulls in (the `:base`/`:system`/`:user`/`:dev`
profiles, so no nREPL, and `:provided`, which an uberjar leaves out) and runs the
project's `:prep-tasks` first, so both `:aot` classes and `:java-source-paths` output
are scanned. The [reflection caveat](clojure.md) of the Clojure code itself applies
here too; `:class-dir` does not, because the dump takes its class directories
from `:compile-path` and the project's own source and resource paths.

## PR gate on GitHub Actions

The three steps of the [PR gate](../README.md#pr-gate-on-github-actions-the-main-use-case)
look like this for Leiningen. There is no switch to skip build outputs, so the
two dumps differ only in the output path:

```yaml
name: dependency binary incompatibility check
on: pull_request

jobs:
  upgrade-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      # ... You may need to setup Java/Leiningen here ....

      - name: Dump baseline classpath (base branch)
        id: baseline
        continue-on-error: true
        run: |
          git checkout ${{ github.event.pull_request.base.sha }}
          if lein uika dump-classpath /tmp/before.json; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Dump PR classpath
        run: lein uika dump-classpath /tmp/after.json

      - name: Check broken references
        if: steps.baseline.outcome == 'success'
        run: lein uika upgrade-check /tmp/before.json /tmp/after.json
```

To keep the base-branch resolution off the PR's critical path, cache the
baseline as an artifact instead:
[BASELINE-CACHING.md](../BASELINE-CACHING.md).
