# Notes for Agents: lein-plugin/

The shared plugin rules are in `jvm-plugin-core/AGENTS.md`. Claude Code imports it below.

@../jvm-plugin-core/AGENTS.md

The lein plugin shares `core.clj` with the Clojure tool, so `clojure-tool/AGENTS.md`
applies here too.

@../clojure-tool/AGENTS.md

## Leiningen Plugin Notes

Point-of-use comments and `lein-plugin/it/run.sh` hold the invariants; only the
experiment-only lessons live here.

- The staged pom must not carry `<repositories>`: PomChecker rejects it outright
  ("The <repositories> block should not be present"), so `jreleaser release` aborts
  the whole all-or-nothing deployment, and nothing before the release run executes
  that path. `:repositories ^:replace []` in project.clj is NOT the fix -- it also
  empties the map lein resolves the plugin's own deps from, which fails on the cold
  `~/.m2` a release runner always has. `lein update-in :repositories empty -- deploy
  staging` works because `:eval-in-leiningen` has already put those deps on the
  classpath before update-in rewrites the map.
- `lein deploy` prompts for credentials unless the repo URL matches
  `#"(file|scp|scpexe)://"` -- note the DOUBLE slash, which `file:target/...` does not
  have. That is why it "worked twice, then hung twice" on identical invocations:
  the prompt always fires, only the blocking depends on the stdin shape. `:no-auth
  true` is the fix. Spelling the URL `file://target/...` is not: `target` becomes the
  URI authority and the deploy lands in `/staging-deploy`.
- `lein compile` is NOT enough to build the outputs a dump should record. It
  short-circuits when `:aot` yields no stale namespace and so never reaches
  `eval/prep`, the only thing that runs `:prep-tasks` -- a `:java-source-paths`
  project would never run javac. `leiningen.core.eval/prep` is the task-agnostic
  entry point and subsumes the compile call.
- `:eval-in-leiningen` pins the plugin to LEIN's JVM, while project code runs on
  `(or (:java-cmd project) JAVA_CMD "java")` (leiningen.core.eval, eval.clj:254).
  Both the dump's `jdkRelease` and the `--jdk-release` default have to describe the
  latter, so the plugin probes it with `-XshowSettings:properties -version`.
  Measured before the fix, with `:java-cmd` on a 25 and lein on a 21: the dump said
  21 and the flag came out 20 instead of 24. The ct.sym ceiling and the `UIKA_JDK`
  export must come from that same JVM, since UIKA_JDK is the ct.sym the CLI reads.
  All of that holds while the probe SUCCEEDS; a JVM that cannot be run or whose
  output carries no `java.home` falls back to lein's own with a warning, because a
  failed probe must not fail the check. The parse lives in
  `core/parse-jvm-properties` so it can be unit-tested: values run to end of line,
  since a `java.home` containing a space (`C:\Program Files\...`) truncated at the
  space makes the ct.sym probe miss and the layer switch off blaming a missing
  ct.sym.
- mise's leiningen `2.13.0` package is broken (its script 404s on a
  `2.12.1-SNAPSHOT` standalone jar); `.mise.toml` pins 2.12.0 and says why.
