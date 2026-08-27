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
