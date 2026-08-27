# Clojure CLI tool (`clojure-tool/`)

One of uika's [build-tool integrations](../README.md#build-tool-plugins).
The tool itself is distributed as a git dependency, so it adds nothing to
uika's Maven Central deployment, and the repo tag pins both the tool and the
CLI version it runs. Its own dependencies and the uika-cli binary still
resolve from Maven Central as usual:

```console
$ clojure -Ttools install io.github.exoego/uika \
      '{:git/tag "vVERSION_PLACEHOLDER" :deps/root "clojure-tool"}' :as uika
```

```console
$ clojure -Tuika dump-classpath                        # writes target/uika/classpath.json
$ clojure -Tuika dump-classpath :output '"/tmp/after.json"' :aliases '[:prod]'
$ clojure -Tuika upgrade-check :before '"/tmp/before.json"' :after '"/tmp/after.json"' \
      :fail-on reachable :exclude-file '"uika-exclude.toml"'   # :cli-version to override
```

The dump records the resolved Maven coordinates from the project's own
`deps.edn` basis (`:local/root` and git deps are coordinate-less, like the other
tools' project dependencies), and `upgrade-check` downloads the platform binary
from Maven Central (`UIKA_CLI_URL` to override the URL, `UIKA_CLI_PATH` to skip
the download) with the version taken from the installed tool's own `:git/tag`.

There is no module model to read a compile target from, so
[`:jdk-release`](../README.md#build-tool-plugins) defaults to the project's
own JVM release. Set it to override that, or to 0 to disable the API layer.

Two Clojure-specific caveats. Interop calls without type hints go through
runtime reflection and leave no reference in the constant pool, so uika sees
the Java dependencies on the classpath at full strength but only the
type-hinted, AOT-compiled part of the Clojure code itself; point `:class-dir`
at the tools.build `compile-clj` output to include it. JFR recordings are not
converted by this tool yet; record
[text class-load logs](../README.md#runtime-load-evidence-jfr---class-load-log)
with `-Xlog:class+load` and pass them to `:class-load-log`, with
`:draft-exclude-file` alongside to draft an exclude file.

## PR gate on GitHub Actions

The three steps of the [PR gate](../README.md#pr-gate-on-github-actions-the-main-use-case)
look like this for the Clojure CLI. The job installs the tool first, and there
is no switch to skip build outputs, so the two dumps differ only in the output
path:

```yaml
name: dependency binary incompatibility check
on: pull_request

jobs:
  upgrade-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      # ... You may need to setup Java and the Clojure CLI here ....

      - name: Install uika tool
        run: |
          clojure -Ttools install io.github.exoego/uika \
              '{:git/tag "vVERSION_PLACEHOLDER" :deps/root "clojure-tool"}' :as uika

      - name: Dump baseline classpath (base branch)
        id: baseline
        continue-on-error: true
        run: |
          git checkout ${{ github.event.pull_request.base.sha }}
          if clojure -Tuika dump-classpath :output '"/tmp/before.json"'; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Dump PR classpath
        run: clojure -Tuika dump-classpath :output '"/tmp/after.json"'

      - name: Check broken references
        if: steps.baseline.outcome == 'success'
        run: clojure -Tuika upgrade-check :before '"/tmp/before.json"' :after '"/tmp/after.json"'
```

To keep the base-branch resolution off the PR's critical path, cache the
baseline as an artifact instead:
[BASELINE-CACHING.md](../BASELINE-CACHING.md).
