# [Clojure CLI tool](../clojure-tool/) [![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fclojure-uika%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/clojure-uika)

One of uika's [build-tool integrations](../README.md#build-tool-plugins).
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
the download). The binary's version is taken from the tool's own coordinate in
the runtime basis, so the one `:mvn/version` in the alias pins the tool and the
CLI together.

There is no module model to read a compile target from, so
[`:jdk-release`](../README.md#build-tool-plugins) defaults to the project's
own JVM release. Set it to override that, or to 0 to disable the API layer.

One Clojure-specific caveat. Interop calls without type hints go through
runtime reflection and leave no reference in the constant pool, so uika sees
the Java dependencies on the classpath at full strength but only the
type-hinted, AOT-compiled part of the Clojure code itself; point `:class-dir`
at the tools.build `compile-clj` output to include it.

## Runtime load evidence (JFR)

Collect by running the current, not yet upgraded build's test suite (or a
staging soak) with
[JFR recording class loads](../README.md#runtime-load-evidence-jfr---class-load-log).
There is no test task to inject the flag into, so add it to your own test JVM
invocation, the way the Maven recipe does:

```console
-XX:StartFlightRecording:jdk.ClassLoad#enabled=true,jdk.ClassLoad#stackTrace=true,filename=<dir>
```

Consume with `:jfr`, pointed at that directory or at a single recording:

```console
$ clojure -T:uika upgrade-check :before '"/tmp/before.json"' :after '"/tmp/after.json"' \
      :jfr '"/tmp/uika-jfr"'
```

Recordings are converted with the JDK's own JFR reader before the CLI runs,
text logs in the same directory ride along unchanged, and a recording handed
to `:class-load-log` is converted too. Conversion needs the tool itself on
Java 17+, the same floor the recording test JVMs already have for the flag
syntax.
[`:draft-exclude-file`](../README.md#runtime-load-evidence-jfr---class-load-log)
drafts exclude rules from the same evidence.

## PR gate on GitHub Actions

The three steps of the [PR gate](../README.md#pr-gate-on-github-actions-the-main-use-case)
look like this for the Clojure CLI. The alias lives in the project's committed
`deps.edn`, so the job needs no install step, and there is no switch to skip
build outputs, so the two dumps differ only in the output path:

```yaml
name: dependency binary incompatibility check
on: pull_request

jobs:
  upgrade-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      # ... You may need to setup Java and the Clojure CLI here ....

      - name: Dump baseline classpath (base branch)
        id: baseline
        continue-on-error: true
        run: |
          git checkout ${{ github.event.pull_request.base.sha }}
          if clojure -T:uika dump-classpath :output '"/tmp/before.json"'; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Dump PR classpath
        run: clojure -T:uika dump-classpath :output '"/tmp/after.json"'

      - name: Check broken references
        if: steps.baseline.outcome == 'success'
        run: clojure -T:uika upgrade-check :before '"/tmp/before.json"' :after '"/tmp/after.json"'
```

To keep the base-branch resolution off the PR's critical path, cache the
baseline as an artifact instead:
[BASELINE-CACHING.md](../BASELINE-CACHING.md).
