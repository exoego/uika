# Notes for Agents

README.md is the source of truth for purpose, workflows, command reference,
build-tool integrations, publishing, and the high-level design. This file holds
what only agents need: invariants, internal semantics, and lessons that must
not be relearned by experiment.

## Development

- Measure with release builds; debug builds are ~10x slower. The `memstats`
  feature replaces mimalloc with the counting allocator in `cli/src/memstats.rs`,
  so never use it for throughput benchmarks.
- Regression-test parser and ordering changes by diffing `uika dump <jar>`
  output before/after. Dump order follows physical entry offsets, so sort both
  sides first if the change can affect read order.
- Golden regression (`cli/tests/golden.rs`; the bless workflow is in
  CONTRIBUTING.md):
  golden inputs use crate-relative fixture paths
  (`cli/tests/common/mod.rs::fixture`) so the JSON stays machine-independent;
  keep it that way. `check_scanned` sorts violations by string value before
  returning, so the JSON report is canonical — without that sort, graph-walk
  violations surface in FxHashMap order and the goldens would go flaky at two
  or more became-final violations.
- The scenario table (name, old, new, consumer, probe-only extra classpath) is
  single-sourced in `cli/tests/scenarios.tsv`, read by both `golden.rs` and
  `tools/jvm-probe/run-fixtures.sh`. Add scenarios there, plus a named golden
  test and a blessed golden file.
- `check --verdicts-json <path>` (also on upgrade-check) streams every
  reference verdict (ok/unknown/broken) as JSON Lines for evaluation. The
  stream carries the raw constant-pool reference (never the collapsed Class
  ref a "class removed" violation reports), is written before --exclude-file
  filtering, and does not include graph-walk violations (class/method became
  final, extends final class, class/interface kind flips, class became sealed,
  method became abstract, conflicting default methods) or the META-INF/services
  provider walk (service provider removed / not instantiable). One line per reference record — call-site
  duplicates are not deduped, so line counts exceed violation counts. It streams one line at a
  time, so it adds no RSS proportional to the scan. A write failure lets the
  scan finish but fails the command afterwards; a truncated stream must never
  pass silently, because the probe would answer-check only a prefix.
- `make probe` (tools/jvm-probe/run-fixtures.sh, debug binary — verdicts are
  optimization-independent) resolves each distinct verdict record via
  `MethodHandles.privateLookupIn` + findVirtual/findStatic/findGetter...
  Broken records that fail on BOTH sides are listed inconclusive, not treated
  as confirmation (the probe could not reproduce the old-side linkage uika
  resolved against). ok/unknown records that fail on new are FN candidates only
  when the old side links them; failing on both sides is pre-existing breakage
  uika deliberately does not report, and an old-side probe ERROR is surfaced
  instead of being folded into pre-existing. The koin and pact breaks are
  graph-walk violations and therefore not probeable; their coverage lives in
  the integration tests. The class-shape breaks are not probeable either:
  `MethodHandles.Lookup` models neither the Methodref/InterfaceMethodref
  owner-kind requirement nor InstantiationError, so it would link a kind-flip
  or `class became abstract` verdict the JVM actually rejects. scenarios.tsv is
  chosen so no fixture produces a class-shape verdict, so `make probe` never
  answer-checks one. The probe is evidence, not truth: findVirtual does not
  model invokespecial, final-field writes are probed as reads when the writer
  is the declaring class, and an unloadable referencing class downgrades to a
  caller-context-free public lookup. The Kotlin fixtures need kotlin-stdlib on
  the probe classpath (vendored in fixtures); the JDK is pinned in `.mise.toml`
  (probe needs 16+).
- `--jdk-release N` (check/upgrade-check; user-facing behaviour in README)
  layers a JDK API index under both resolution scopes, built lazily from the
  escape closure inside the JDK's ct.sym (stubs are plain class files, parsed
  by the normal parser). Strictly opt-in: the default run stays byte-identical
  (goldens) and the no-JVM claim holds. Because the SAME index sits in the old
  and new scope, ct.sym gaps resolve NotFound on both sides and the
  old-relative gate keeps them unreported — for reference verdicts the layer
  can only conclude Unknowns, never invent violations. The one single-sided
  consumer is the version-lag extends-final check: a lag super's ACC_FINAL
  comes from the declared release's stub alone (no old-side JDK to compare), so
  finality that changed between JDK releases (java/awt/PointerInfo became
  final in 19) is judged as of N — correct for the declared release, possibly
  not for the runtime actually deployed. Layer order is (new, fetched, jdk),
  first-wins: ct.sym is modeled as the LAST classpath entry, deliberately
  inverting real boot delegation, because jdk-first would resolve a checked
  javax pair (a jaxb-api upgrade under `--jdk-release 8`) from the JDK's
  bundled copy and mask the pair's own changes. Do not reorder. ct.sym layout
  notes live in `cli/src/jdk.rs` (12+ has module dirs, 9-11 does not, 8 is
  unsupported; codes are base-36 and real files keep 6/7 in joint dirs).
  Fixture evidence: guava-selenium 16→0, koin 5→0, jetty 14→0 unverified with
  broken counts unchanged, and the probe links every converted verdict.
  kotlin/* and spring/* escapes correctly stay Unknown. On the stress workload
  unverified went 294→0 and 106 real removals surfaced (owners whose hierarchy
  escapes into java.util.concurrent).
- `--jdk-release-old N --jdk-release-new M` (check) makes the JDK upgrade the
  compared pair instead of layering one release under both sides. clap REJECTS
  `--old`/`--new` alongside it rather than accepting and ignoring them: only one
  pair reaches `run_check_with_indexes`, and checking a library pair and a JDK
  pair at once is two runs, which is what upgrade-check does from the dumps. The
  JDK indexes therefore go in with empty path lists (nothing to exclude as stale,
  nothing to sweep for invocation evidence). A `.jmod` is a zip of class files
  under `classes/`, and the running JDK's feature version is read from its
  `release` file, not from a JVM. jmods is a SUPERSET of ct.sym (unexported
  internals included), which only cancels removals while it is the new side; as
  the old side against a ct.sym new side it would invent them, so that
  combination warns. `level_to_ct_sym_fidelity` drops PermittedSubclasses from
  jmods classes because stubs strip it (java.lang.constant.ConstantDesc has been
  sealed since 12 and its stub carries none); without that, every sealed JDK
  class reads as newly sealed. NestHost IS in stubs, so it stays. Evidence: an
  app calling java.rmi.activation.ActivationGroup reports `class removed` on
  11 -> 17, while a subclass of java.awt.event.ComponentAdapter reports nothing
  despite the 98 public -> protected constructor narrowings in that pair (the
  subclass-aware access check absorbs them).
- The dump records `jdkRelease` (additive) TWICE: once per module, once for the dump.
  Both name the API release the checked application runs on, never the JVM that wrote
  the file — that was issue #128, where a build-image JDK bump manufactured a JDK-pair
  run the application never had while a real application JDK upgrade went unseen. A
  module's is what it compiles for (the same per-tool derivation `--jdk-release`
  defaults from, so the two cannot disagree), absent when it declares nothing; the
  dump's is `DumpFormat.dumpRelease`, the lowest across the modules, else
  `buildJvmRelease()`. That fallback is not a compromise: a module declaring no target
  compiles against whatever JDK runs the build, so for it the build JVM IS the
  application's release. The CLI applies the dump-level value as each module's fallback
  at load time (`gradle.rs`), so `ModuleUniverse::jdk_release` always has the one
  answer. The derivation cannot see a runtime that differs from what the build
  declares, so the plugins' existing release knob doubles as the escape hatch:
  `UikaCli.overrideRelease` (ported as `core/override-release`) replaces every module's
  value with an explicit positive one. Zero stays "switch the API layer off" and leaves
  the recorded release derived, since going silent there would take JDK-move detection
  down with the layer, and anything below `MIN_RELEASE` is dropped because a dump
  naming it sends `jdk::release_index` after a release ct.sym never carried.
- `upgrade-check` turns a before/after disagreement into extra runs appended by
  `plan_jdk_runs` with `jdk_pair` set and both jar lists empty: ONE PER DISTINCT MOVE,
  over the union of the targets of the modules that made it. Per module because a build
  may mix releases, and a module still on 11 must not be checked against a sibling's
  17 -> 21 move; a build that moved as a whole still plans exactly one run. The run's
  name is the pair (`JDK 11 -> 17`), not the module names, because that string is also
  the key violations are attributed by and the real names would fold a module's
  dependency run into the JDK run's broken count. `release_change` requires BOTH sides
  to name a release, so a dump predating the field, or a module the before dump does
  not have, never manufactures a JDK move. The runs are excluded from the "N of M
  modules changed" count (`ModuleOutcome.jdk`); they are not the dump's modules. They
  are also printed in their OWN section rather than as more rows of the per-module
  table, with `ModuleOutcome.jdk_modules` naming the modules that moved: a JDK run
  compares two releases of the JDK while a module row compares two versions of a jar, so
  side by side their broken counts read as parts of one total that does not add up.
  Merged mode has no per-module data and compares the dump-level values instead. Gradle
  rehydration carries the input dump's values forward instead of stamping the
  rehydrating JVM.
- Tuning knobs: `UIKA_CHUNK` (paths processed concurrently in pass 1; default =
  16x rayon threads, rationale in `check.rs::scan_target_paths`), `UIKA_WINDOW`
  (fallback zip-reader window size; default 1 MiB, two windows).
- `cli/Cargo.toml` stays at the `0.0.0-dev` placeholder; released binaries get
  their version from the `UIKA_VERSION` env var embedded at compile time
  (`option_env!` in `cli/src/cli.rs`). Never bump the placeholder for a
  release or compare it against tags.

## Memory and Speed Rules

- No `String`, `Box`, or per-class `HashMap` in structures proportional to
  class count — use `Sym` and shared arenas with range references.
- Do not retain inflated bytes beyond one batch of 512 classes when scanning
  the consumer classpath.
- Preserve both parallelism layers: chunks across input paths, batches within
  each JAR/dir. Nested rayon provides the load balancing.
- Preserve determinism: output sorted by string value; duplicates first-wins by
  input path order. Never sort or compare output by `Sym` id — interning order
  is nondeterministic. The same rule covers `model::Reason`: order via
  `as_str()`, never variant position (it deliberately derives no `Ord`).
- Keep old/new library indexing simple and complete — the two-pass savings are
  for the huge consumer classpath, not the small compared-library set.
- Reachability edges are the one arena proportional to the whole scan that is
  not always built; keep them gated behind `collect_edges` (driven by app-root
  presence) so a bare classpath-only run never pays the ~130MB (2M-class stress)
  cost.

## Where the rest lives

Read the relevant one before working in that area. Each is plain markdown, so
an agent whose harness does not auto-load it can just read the path.

- `cli/AGENTS.md` (symlinked as `cli/CLAUDE.md`) — check pipeline, per-module
  upgrade-check, linkage semantics, reachability, suggestions. Claude Code
  loads it automatically when working under `cli/`.
- `.claude/skills/uika-performance/SKILL.md` — benchmark workloads and expected
  numbers, optimization history, rejected approaches; before profiling or
  touching the hot path.
- `.claude/skills/uika-jvm-plugins/SKILL.md` — Gradle/sbt/Maven/Mill/Leiningen
  plugin, Clojure CLI tool and `jvm-plugin-core` invariants; before changing
  anything under `gradle-plugin/`, `sbt-plugin/`, `maven-plugin/`,
  `mill-plugin/`, `clojure-tool/`, `lein-plugin/`, or `jvm-plugin-core/`.

Module layout is not documented here on purpose: `ls cli/src/` plus the
"How it works" section of README.md is the current answer, and a hand-kept
table drifts.
