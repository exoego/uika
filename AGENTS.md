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
- Tuning knobs: `UIKA_CHUNK` (paths processed concurrently in pass 1; default =
  rayon threads), `UIKA_WINDOW` (fallback zip-reader window size; default
  1 MiB, two windows).
- `cli/Cargo.toml` stays at the `0.0.0-dev` placeholder; released binaries get
  their version from the `UIKA_VERSION` env var embedded at compile time
  (`option_env!` in `cli/src/cli.rs`). Never bump the placeholder for a
  release or compare it against tags.

## Check Pipeline

```text
old/new JARs (--old / --new, both repeatable; merged first-wins per side)
  -> ApiIndex x2 (full member tables; library JARs are small enough to hold)

pass 1: stream --classpath / --app / --classpath-file targets in parallel chunks
  -> ClassGraph: class name -> superclass, interfaces, origin
  -> reference records, only where the owner exists in the old index
  (member tables discarded; member names not interned)

collect_wanted: walk the hierarchy from referenced owners; keep only classes
  that resolution may visit

pass 2: fetch_members: re-read wanted classes from their origin, build a small
  fetched ApiIndex with member tables

verdict: class existence = new + ClassGraph
         member resolution = Scope(new, fetched) / Scope(old, fetched)
```

The memory win is not holding member tables for the whole consumer classpath;
pass-2 classes are typically below 0.1% of the scan.

## Linkage Semantics

- Visibility is bytecode-level. Kotlin `internal` is public in bytecode;
  detecting such references is core to the tool.
- Member lookup (`index.rs::Scope::resolve`) is a simplified JVMS 5.4.3.2/3.3
  traversal: owner, then superclass and superinterface edges. A member moved to
  a superclass still links at runtime and must not be reported.
- The 11 `java/lang/Object` methods are built in. Kotlin facade classes extend
  Object; without this, real removals could degrade to Unknown when traversal
  reaches Object outside the indexed scope.
- Resolution scope is `new + scanned runtime classpath`, not `new` alone —
  matching flattened-classpath JVM linkage. Moves to another artifact and
  copies in fat JARs are not violations.
- Unknown is conservative OK: if traversal escapes analyzed scope or pass-2
  fetching fails, count the reference as unverified; never report it broken or
  drop it silently.
- References that did not resolve against old are pre-existing inconsistency,
  not breakage introduced by the upgrade.
- Duplicate class names are first-wins in input path order (JVM classpath
  semantics); chunks are merged in path order to keep this deterministic.
- `InvokeDynamic` NameAndType entries are bootstrap synthetic names, not symbol
  references. `MethodHandle` entries point at Methodref-like constants, so
  constant-pool scanning covers them naturally.
- Code attributes are not fully parsed, but the bytecode stream is scanned for
  reference opcodes, giving the expected static/instance kind and whether a
  field reference is a read or write.
- Beyond removals: access narrowing is judged against the referencing class
  (protected needs a subclass, package-private the same package),
  static↔instance mismatches use the opcode-derived expectation, and a write to
  a newly-final field is a violation.
- Newly-final classes/methods break scanned subclasses/overriders even without
  a constant-pool reference; `check.rs::add_final_violations` walks the class
  graph for these.
- Object-array `Class` references unwrap to the element type; primitive arrays
  are ignored; method refs on array owners are ignored (array methods come from
  Object).
- `module-info.class` and `META-INF/versions/` entries are skipped by
  `input.rs::is_scannable`.

## Reachability

- Ranks violations by whether the referencing class is class-load reachable from
  the application, without hiding any: `Violation.reachable` is `Some(false)`
  only when no static path reaches the class. It is an over-approximation, so
  "not proven reachable" (⚠️) is a deprioritize hint, never grounds to drop a
  violation (reflection from external config is invisible). Same conservative
  stance as Unknown. `report.rs` splits the text report into a reachable (💥)
  section then a ⚠️ section.
- On automatically, gated by app roots, not a flag: `run_check` computes
  `reachability = !app_roots.is_empty()` (single policy site). `upgrade-check`
  dumps and `check --app` have roots (on); a bare `check --classpath` has none
  (off, flat list, `reachable = None`). This also keeps the 2M-class
  classpath-only stress run from paying the cost. If roots are supplied but none
  match a scanned class (unbuilt build outputs), `reachable_classes` reports
  `app_root_matched = false` and `check_scanned` emits a warning instead of
  silently reporting every violation as not-proven-reachable.
- Roots are the application: `--app` targets and dump `classesDirs`
  (`Universe.app_roots`). App sources are matched by interning the root path's
  display string, the same string `input.rs` interns as a class's `source`, so a
  root only contributes if it is also a scan target.
- Edges (`reach.rs`, BFS over `Sym`-indexed bool marks): constant-pool `Class`
  constants + hierarchy (super/interfaces) + class-name-shaped string constants
  (`extract.rs::slashed_class_name`, a `Class.forName` over-approximation) +
  `META-INF/services` providers. A provider whose service interface is outside
  the scanned scope (JDK SPI like `java.sql.Driver`) becomes a root, because the
  runtime can instantiate it unobserved.
- Edges are collected in pass 1 only when reachability is on
  (`parse_targets(..., collect_edges)`), stored in a shared arena on
  `ClassGraph` like interfaces. They cost ~10-33% extra RSS (up to ~130MB on the
  2M-class stress workload) with negligible extra time, so keep them gated on the
  root-driven flag rather than always building them.
- Class-name-shaped strings are interned unconditionally so an edge does not
  depend on parse order (determinism); non-class strings become dead `Sym`s that
  BFS never marks.

## Suggestions (upgrade-check only)

- `suggest::annotate` fills `Violation.suggestion` after `run_check`, in
  `cmd_upgrade_check` where coordinates exist. Plain `check` has only file paths,
  so its violations stay `suggestion = None` and `report.rs` prints nothing extra.
- `report.rs` is suggestion-first for attributed violations: it groups them by
  `advice` string (one 💡 block lists every reference a fix covers) instead of
  repeating the advice per reference. Identical `advice` implies identical
  `removed_by`/`referenced_by`/`before`/`after` (the advice embeds the
  coordinates and changed versions), so the header is built from any group
  member. Grouping is done inside each reachability section, so a fix spanning
  both tiers prints once under 💥 and once under ⚠️. Violations with no
  suggestion (plain `check`, or unattributed upgrade-check leftovers) fall back
  to the `source -> class` listing.
- `referenced_by` comes from a dump `file-display-string -> "g:n:v"` map (both
  before and after sides). `removed_by` comes from mapping the violation's owner
  class to a changed coordinate by reading the before-side JARs' class names
  (`input::load`, first-wins) — a small, best-effort scan that never blocks the
  report on read failure.
- Advice: same-group referencer and owner (a skew inside one library family,
  e.g. otel core vs incubator) leads with "align the group via its BOM";
  cross-group leads with upgrade-the-referencer-or-pin-the-owner. This mirrors
  the real fixes found for the OpenTelemetry case (BOM-align the 41 skew breaks;
  handle the cross-group firestore/grpc one separately).

## Module Map

| Path                   | Role                                                                                                                                                  |
|------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------|
| `cli/src/classfile.rs` | Minimal class-file parser: constant pool + headers, Code scanned only for reference opcodes, Utf8 borrowed, ASCII unconverted.                        |
| `cli/src/input.rs`     | JAR/class-dir loading. Fast path: parse central directory, group offset spans, one `pread` per span, parallel inflate. Falls back to the `zip` crate. |
| `cli/src/window.rs`    | Fallback `Read + Seek` reader with two LRU windows (the `zip` crate seeks between central directory and local headers).                               |
| `cli/src/intern.rs`    | `Sym = u32` interning in sharded bump arenas kept for process lifetime. Never sort/compare output by Sym id — interning order is nondeterministic.    |
| `cli/src/model.rs`     | Core data model: `MemberKey`, `ClassApi`, `BreakingChange`, `SymbolRef`, `Violation`.                                                                 |
| `cli/src/extract.rs`   | `RawClass` -> API surface / hierarchy data / reference records; owner filter applied inline to avoid throwaway allocations.                           |
| `cli/src/index.rs`     | `ApiIndex`, `ClassGraph`, `Scope`; member/interface tables in shared arenas with range refs and binary search.                                        |
| `cli/src/check.rs`     | Two-pass orchestration: `scan_target_paths`, `collect_wanted`, `fetch_members`, verdicts.                                                             |
| `cli/src/reach.rs`     | Class-load reachability (on when app roots exist): `META-INF/services` collection + BFS from app roots over the `ClassGraph` edge arena. Ranks, never drops. |
| `cli/src/suggest.rs`   | upgrade-check only: attribute each violation to referencing/removing coordinates (via dump `file->coordinate` and old-jar `class->coordinate`) and build fix advice. |
| `cli/src/diff.rs`      | Pure old/new API diff. Private members are indexed but excluded from reports.                                                                         |
| `cli/src/report.rs`    | Text and JSON report formatting.                                                                                                                      |
| `cli/src/memstats.rs`  | Feature-gated counting allocator.                                                                                                                     |
| `cli/src/gradle.rs`    | Reads dump v1/v2 and computes dependency changes. One coordinate may map to several versions (modules can resolve differently).                       |
| `cli/src/cli.rs`       | clap definitions: `diff`, `check`, `upgrade-check`, `dump` (`check`/`upgrade-check` take `--reachability`).                                           |
| `cli/src/lib.rs`       | Command dispatch; `run_check` is shared by `check`/`upgrade-check`. `cli/src/main.rs` picks mimalloc or the memstats allocator.                       |
| `jvm-plugin-core/`     | Shared dump model + v1/v2 reader/writer (`ClasspathDump`, `DumpFormat`) and CLI fetch/run helper (`UikaCli`). Compiled into each plugin by source inclusion; not a published artifact. |
| `gradle-plugin/`       | Java Gradle plugin. `localGroovy()` only, `options.release = 17`, merges per-module fragments into the v2 dump.                                       |
| `sbt-plugin/`          | sbt `AutoPlugin` (`sbt-uika`, Scala 2.12). Tested via `scripted`.                                                                                     |
| `maven-plugin/`        | Aggregator goal `uika:dump-classpath`. Tested via maven-invoker-plugin.                                                                               |
| `binary-publishing/`   | Gradle project staging native CLI ZIPs (`net.exoego.uika:uika-cli`, per-platform classifiers, packaging `pom`) for Maven Central.                     |
| `jreleaser.yml`        | Signs all locally staged artifacts in-memory and uploads them to Maven Central as one deployment; also attaches CLI ZIPs to the GitHub release.       |
| `Makefile`             | Cross-component builds and checks; Gradle/sbt/Maven run via `mise exec`, pinned by `.mise.toml`.                                                      |

## Gradle Plugin Notes

- Keep the module-task + root-merge shape (`uikaDumpModuleClasspath` per
  project, merged by root `uikaDumpClasspath`). A root task cannot safely
  resolve other projects' configurations at execution time, and the split
  avoids Gradle 9 exclusive-lock failures.
- Coordinates come from `ResolvedArtifactResult`; never recover them from file
  paths. The artifact view is lenient so unbuilt project dependencies are
  skipped instead of failing the dump.
- `uikaResolveClasspath` (rehydration) uses one detached configuration per
  notation: multiple versions of a module in one configuration would be
  conflict-resolved down to the highest. Classifiers are reconstructed from the
  original file name.
- `DumpFormat` changes propagate to all three plugins via source inclusion from
  `jvm-plugin-core/` — no core artifact to publish.
- The upgrade-check tasks (`uikaUpgradeCheck`, Maven `uika:upgrade-check`)
  resolve `net.exoego.uika:uika-cli:<version>:<platform>@zip` through the build's own
  repositories and run it (`UikaCli` in core). The CLI version must keep
  defaulting to the plugin's own version — Implementation-Version manifest
  attribute in the Gradle/sbt jars, `${plugin.version}` in Maven — so one
  coordinate bump updates both; never hardcode a CLI version or URL.
- CLI output must flow through each tool's logger (the line consumer passed to
  `UikaCli.runUpgradeCheck`). Never revert to `inheritIO`: a child process
  inheriting file descriptors writes past the tool's log capture, and under a
  Gradle daemon, sbt server, or mvnd the report silently disappears.
- Their tests stub uika-cli with a shell-script ZIP in a file-based Maven repo
  (Gradle TestKit + sbt scripted + Maven invoker; invoker needs `-U` because
  target/it-repo caches resolution failures across runs, and its pre-build
  hook script must be named `prebuild.groovy`). An edited stub is shadowed by
  two caches, so the upgrade-check prebuild purges both: the clone's `target/`
  (extractBinary skips an already-extracted binary) and the it-repo `uika-cli`
  entry (Maven never re-fetches a cached release version).
- Run builds via `make gradle-check` / `make sbt-scripted` / `make
  maven-verify` (mise-pinned). Without mise, any target project's Gradle
  wrapper works: `/path/to/project/gradlew -p gradle-plugin publishToMavenLocal`.
- Do not add an explicit toolchain: the plugin intentionally compiles with the
  JVM running Gradle plus `options.release = 17`, because toolchain
  auto-resolution is not available in every target environment.

## Memory and Speed Rules

- No `String`, `Box`, or per-class `HashMap` in structures proportional to
  class count — use `Sym` and shared arenas with range references.
- Do not retain inflated bytes beyond one batch of 512 classes when scanning
  the consumer classpath.
- Preserve both parallelism layers: chunks across input paths, batches within
  each JAR/dir. Nested rayon provides the load balancing.
- Preserve determinism: output sorted by string value; duplicates first-wins by
  input path order.
- Keep old/new library indexing simple and complete — the two-pass savings are
  for the huge consumer classpath, not the small compared-library set.
- Reachability edges are the one arena proportional to the whole scan that is
  not always built; keep them gated behind `collect_edges` (driven by app-root
  presence) so a bare classpath-only run never pays the ~130MB (2M-class stress)
  cost.

## Benchmark Expectations

Not hermetic (depends on the local Gradle cache). Treat detection-count shifts
as semantic regressions first, performance second; large deviations need
investigation.

### Stress: all Gradle cache JARs

```zsh
JAR171=$(echo ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.7.1/*/kotlinx-coroutines-core-jvm-1.7.1.jar)
JAR1110=$(echo ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.11.0/*/kotlinx-coroutines-core-jvm-1.11.0.jar)
BIG_CP=$(find ~/.gradle/caches/modules-2/files-2.1 -name '*.jar' ! -name '*-sources*' ! -name '*-javadoc*' | tr '\n' ':' | sed 's/:$//')
/usr/bin/time -l target/release/uika check --old "$JAR171" --new "$JAR1110" --classpath "$BIG_CP"
```

### Real project scale

```zsh
KTOR_ALL=$(find ~/.gradle/caches/modules-2/files-2.1/io.ktor -path '*2.3.13*' -name '*.jar' ! -name '*sources*' | tr '\n' ':' | sed 's/:$//')
APP_DIRS=("${(@f)$(find /path/to/large-jvm-project -type d -path '*build/classes')}")
ARGS=(); for d in $APP_DIRS; do ARGS+=(--app "$d"); done
/usr/bin/time -l target/release/uika check --old "$JAR171" --new "$JAR1110" --classpath "$KTOR_ALL" "${ARGS[@]}"
```

Expected on a 10-core Apple Silicon Mac:

| Workload                                              |                      Result |  Time |    RSS |
|-------------------------------------------------------|-----------------------------:|------:|-------:|
| Stress: ~1,873 JARs / 1.8M classes                    | ~462 broken / 564 unverified | ~4.9s | ~400MB |
| Real project: ~50 modules / 48.5K classes + 38 JARs   |   ~1 broken / 347 unverified | ~0.9s | ~110MB |

Traps already hit in this repository:

- Always benchmark release builds; never with `--features memstats`.
- zsh does not split unquoted variables — use arrays for repeated `--app` args.
- zsh multios can duplicate stdout into a pipe with `cmd 2>&1 >/dev/null |
  grep`; send stderr to a file first when filtering it.

## Optimization History

~60s / 11GB -> ~4.9s / 400MB on the stress workload. Causal changes:

| Measured problem                                                              | Solution                                                          |
|-------------------------------------------------------------------------------|--------------------------------------------------------------------|
| Duplicate classes kept full `ClassApi` values until index construction       | Merge per chunk; discard later duplicates immediately.            |
| Member tables for all consumer classes cost 100s of MB; resolution needed few | Two-pass scan: keep `ClassGraph`, fetch only wanted members.      |
| `Arc<str>` cloning caused atomic contention and duplicate strings            | `Sym = u32` + bump-arena interning.                               |
| Per-class `HashMap`/`Box` overhead dominated at ~100K+ classes               | Shared arenas + ranges + binary search.                           |
| General parsers structured every attribute                                   | `RawClass` skips attribute structure; scans only needed Code bytes. |
| Read syscalls and buffer churn inflated system time                          | Group physical spans; one `pread` per span.                       |
| Per-JAR sequential inflate underused the CPU                                 | Inflate entries in parallel.                                      |

A Java port with the same two-pass/int-intern/span-read architecture matched
Rust on CPU time (the `experiments/` comparison, since removed). Rust's real
advantages here: memory footprint, startup time for short CLI runs, and static
binary distribution.

## Rejected Approaches

- `jclassfile` crate: full attribute parsing cost too much CPU and temporary
  memory. Replacement validated by exact golden diffs of `dump` output.
- Whole-JAR mmap: every touched page stayed resident; on macOS
  `madvise(DONTNEED)` did not reduce file-backed RSS peaks. Span reads won on
  both speed and memory. (madvise residency control and chunk-size caps were
  removed along with mmap.)
- Single fallback window: the `zip` crate's seek pattern amplified reads badly;
  the fallback keeps two LRU windows.
- Tuple-based dump output: object-shaped JSON is more readable, and gzip
  handles repeated keys well.
