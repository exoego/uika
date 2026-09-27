# Notes for Agents: clojure-tool/

The shared plugin rules are in `jvm-plugin-core/AGENTS.md`. Claude Code imports it below.

@../jvm-plugin-core/AGENTS.md

## Clojure Tool Notes

Same rule as `mill-plugin/AGENTS.md`: point-of-use comments and the tests in
`clojure-tool/test/` hold the invariants. Only the experiment-only lessons live here.

- To resolve ANOTHER project's basis, wrap `create-basis` in
  `clojure.tools.deps.util.dir/with-dir` and keep `:project "deps.edn"` bare. Every
  relative path resolves against `*the-dir*` -- a dir-joined `:project` resolves twice
  and silently falls back to the root deps (org.clojure/clojure alone in the dump is
  the symptom). Relative `:local/root` entries need the same binding.
- The tool and the lein plugin download the CLI jar from Maven Central directly
  (`UIKA_CLI_URL` overrides), unlike the JVM plugins, which reuse the build's resolver.
  That started because tools.deps and lein resolve jars only and the CLI was a
  zip-packaged classifier artifact. It is a jar now, so either resolver could take it,
  but `core.clj` is shared by both front ends and stays free of both resolvers' APIs.
  Moving the fetch into each front end is open work, not a decision against it.
- Both front ends are Clojure source and load on any JVM, so nothing stops them on a
  JVM older than the 17 the CLI jar needs. `launch-command` checks the feature version
  itself, because the JVM's own answer is an UnsupportedClassVersionError at exit 1,
  which is also the CLI's code for broken references. The four JVM plugins need no such
  check: their own classes have the same floor and would not have loaded.
- The tool is published to Central as `net.exoego.uika/clojure-uika` (build.clj's
  `stage`, wired into stage-all and jreleaser.yml like the other plugins; it rides the
  shared deployment, so it costs files but no extra release against the Central
  quota). The documented consumption is a deps.edn ALIAS carrying `:ns-default
  exoego.uika`, never `-Ttools install` from Maven: tools.deps has no usage lookup for
  :mvn coordinates (`ext/coord-usage :mvn` is TBD upstream, checked through 0.31.1642
  and the CLI-bundled copy), so a Maven-installed tool needs every call
  ns-qualified. `:tools/usage` in deps.edn is for DEVELOPMENT `:local/root` (or git)
  `-Ttools` installs, so manual testing matches the documented UX; it was missing
  before the Maven switch, so the unqualified call the docs showed had never actually
  worked. The CLI version default comes from the tool's own :mvn/version in
  `clojure.java.basis/current-basis` (`version-from-libs`), so the alias's one
  coordinate pins tool and CLI together. Git and :local/root installs are deliberately
  UNSUPPORTED for version derivation (the tag branch was removed with zero released
  users); they fall back to `:cli-version` / `UIKA_CLI_VERSION` with the usage hint
  naming the former.
- build.clj strips the empty `<repositories/>` element write-pom leaves behind even
  with :mvn/repos dissoc'd from the basis (PomChecker rejects the element's presence,
  the same rule lein-stage works around), and throws on a POPULATED block so a
  regression dies in `make clojure-stage` instead of in the all-or-nothing Central
  validation. The strip runs before b/jar so the jar-embedded pom matches the staged
  .pom byte for byte.
- Both front ends REJECT an unknown option key, through the one `core/unknown-options`,
  and only the way they raise differs (lein aborts the task, the `-T` tool throws).
  Destructuring drops what it does not name, so without the check a typo disables the
  flag it was meant to set and the run continues on CLI defaults with nothing said. The
  likeliest typo is the sibling's spelling, since the two deliberately differ:
  `:exclude-file`/`:exclude-files` and `:class-load-log`/`:class-load-logs`. That is why
  the message lists the accepted keys rather than only naming the rejected one.
  "Accepted" is the UNION over every command, for both front ends: tools.deps merges
  the alias's `:exec-args` into every `-T` call, and lein reads one `:uika` map for
  every subtask. A per-command set made `:fail-on` in `:exec-args` fail both dump steps
  of the PR gate. The union only works while a shared key means the same thing to every
  command that reads it. Today only `:jdk-release` is shared, and it names the stated
  runtime in both (0 turns the check's layer off and leaves the dump's release derived).
- The port of `UikaCli.runUpgradeCheck`'s COMMAND BUILDING is pinned mechanically, by
  `the-command-port-carries-every-uikacli-flag` in the clojure-tool suite. It scrapes the
  quoted `"--flag"` literals out of both sources (quoted only, since each file also names
  flags in prose) and compares them as SEQUENCES, then checks each flag reaches the `-T`
  tool's destructuring form and, through the translation table its plural spellings force,
  Leiningen's `option-keys`. Without it a flag added to `UikaCli` reached five
  integrations for free and neither Clojure front end at all, with every suite green.
- jvm-plugin-core is mostly NOT shared here: tools.deps does not compile Java sources
  in a git or :local/root install, so the small ports (classifier, ct.sym clamp,
  extract) live in the ns with "keep in sync" markers. The ONE compiled exception is
  JfrEvidence, because binary JFR parsing is the JDK reader's job and not
  hand-portable: build.clj copies the single source out of jvm-plugin-core and javacs
  it (--release 17) into the published jar, and lein-plugin compiles the same file
  from a committed symlink under `java-src/`. core.clj calls it REFLECTIVELY
  (`jfr-evidence` / `rewrite-evidence`): an (:import ...) would fail the whole ns
  load on a source install without the class, taking the text-log flow down with it.
  Class absent or JVM below 17 (which throws UnsupportedClassVersionError, not
  ClassNotFoundException) degrades to text-only, and an explicit :jfr then fails
  with the specific reason instead of forwarding a binary the CLI skips.
  The whole class-load list goes through JfrEvidence.rewrite when the class is
  present — the JVM plugins' shape — so a recording handed to :class-load-log
  converts too, and the workdir leaf comes from the class's own WORK_DIR_NAME.
  Tests need `clojure -T:build javac` first (the Makefile's clojure-test runs it;
  the :test alias adds target/core-classes), and both frontends' JFR tests record
  REAL recordings by running `java -XX:StartFlightRecording:... -version` — on some
  JDKs every startup event carries a stack, so assertions must accept framed blocks
  as well as bare `[class,load]` lines.
