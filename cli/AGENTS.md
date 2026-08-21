# Notes for Agents: cli/

Internal semantics of the check pipeline and linkage model. The repo-root
`CLAUDE.md` holds the cross-cutting invariants; this file holds what only
matters once you are inside `cli/`.

## Check Pipeline

```text
old/new JARs (--old / --new, both repeatable; merged first-wins per side)
  -> ApiIndex x2 (full member tables; library JARs are small enough to hold)

pass 1: stream --classpath / --app / --classpath-file targets in parallel chunks
  (old JARs found among targets are excluded as stale; new JARs stay in — they
  are runtime code, and their classes feed the version-lag check)
  -> ClassGraph: class name -> superclass, interfaces, origin
  -> reference records, only where the owner exists in the old index
  (member tables discarded; member names not interned)

collect_wanted: walk the hierarchy from referenced owners; keep only classes
  that resolution may visit

pass 2: fetch_members: re-read wanted classes from their origin, build a small
  fetched ApiIndex with member tables

verdict: class existence = new + ClassGraph
         member resolution = Scope(new, fetched[, jdk]) / Scope(old, fetched[, jdk])
         (jdk = opt-in ct.sym layer from --jdk-release, same data on both sides)
```

The memory win is not holding member tables for the whole consumer classpath;
pass-2 classes are typically below 0.1% of the scan.

## Per-Module upgrade-check

- `upgrade-check` is per-module by default: each module in the dump whose OWN
  resolution lost a version is checked against its own classpath (own
  classesDirs first, then artifacts in resolution order). The merged-universe
  check remains behind `--merged` and as the automatic fallback (with a
  warning) when a dump has no module with artifacts. Rationale (README states
  the user-facing version): the union mixes several resolved versions of one
  coordinate, which produced false brokens (observed on a real multi-module
  monorepo whose modules resolve two netty lines) AND false negatives. Both are
  pinned by `per_module_upgrade_check_gates_on_each_modules_own_resolution`.
- Gating is per-module `old_jars.is_empty()` -- the same old-version-
  disappeared gate as merged mode, over the module's own version maps.
  Unchanged modules are skipped. An after-only module (renamed or added) is
  NOT skipped outright: it is diffed against the union's before versions
  scoped to its own coordinates (a rename+upgrade must not ship unchecked;
  `per_module_check_covers_renamed_module_via_union_fallback`); only when
  that finds nothing is it counted new. An after module whose artifact list
  vanished while its before had one is skipped as `incomplete` (partial
  dump, e.g. `mvn -pl`), never diffed as total removal. Runs with identical
  (old, new, targets, roots) share one run.
- The version diff excludes `project_coords_union(before, after)` -- the
  UNION of both dumps' project-attributed coordinates -- not a per-side
  filter. One-sided exclusion made a before dump from an older plugin diff an
  unchanged reactor dep as Removed, and run_check then dropped that jar from
  the scan as stale (mass false "class removed"). The coordinates STAY in
  `Universe.versions` so suggest's file->coordinate map still attributes
  referencing reactor jars.
- Violations merge across runs keyed by (source, class, SymbolRef, reason,
  advice) -- NOT reachable: runs that disagree on reachability must count one
  break once, kept at the most-reachable value (`reachable_rank`: Some(true) >
  None > Some(false)). Advice IS in the key because suggestions are annotated
  per run from the module's own change list (exact versions even when the
  global diff is empty -- the swap case -- or unions other modules' moves).
  Module attribution lands in `Violation.modules` (`skip_serializing_if
  empty`, so plain-check JSON and the goldens are byte-identical). Exclude
  rules filter the merged set ONCE, not per run.
- The empty global change list must never swallow the report: upgrade_text
  prints the module summary and violations regardless (a swap between modules
  has changes=none but real per-module breaks;
  `per_module_check_reports_break_when_global_version_set_is_unchanged`).
- `scanned_classes`/`unknown_refs` in the aggregate are per-run sums (a jar on
  several checked modules' classpaths is counted once per run; that rescan is
  the accepted per-module cost -- old/new library indexes at least are built
  once per distinct pair via the run loop's index cache). The per-run broken
  counts in `ModuleRunSummary` come from the merged post-exclusion set so
  they agree with the listing.
- Dump artifact entries may carry a `"project"` key (additive in v2; written
  by Gradle for current-build ProjectComponentIdentifiers only -- an included
  build's ":lib" would collide with the consuming build's module and the
  fallback would scan the wrong classes -- and by Maven for reactor deps; sbt
  emits internal deps as coordinate-less entries instead, minus the module's
  own products which internalDependencyClasspath also returns). A missing
  project jar falls back to the producing module's classesDirs. Maven module
  names disambiguate colliding artifactIds with the groupId; the CLI still
  warns and keeps first on duplicate names. A v2 dump with an unnamed module
  drops to the merged universe (positional pairing would mispair).
- The verdicts stream gains a `module` field only on per-module runs (comma-
  joined names of the run's module group); plain `check` records are
  unchanged, so the probe's evaluation surface is stable. A requested
  --verdicts-json file is created even when no module needs a run.
- The exit decision is per run: each run's post-exclusion violations (rebuilt
  via module attribution) are judged with that run's own `app_roots_matched`,
  so one module whose roots matched nothing degrades `--fail-on reachable`
  to `any` for ITS violations only, not for healthy modules. The merged
  report's `app_roots_matched` stays None (per-run states live on the
  `RunOutcome`s); anything needing one cross-run axis folds those states
  Some(false)-dominant, the way `--draft-exclude-file` gating does.

## Linkage Semantics

- Visibility is bytecode-level. Kotlin `internal` is public in bytecode;
  detecting such references is core to the tool.
- Member lookup (`index.rs::Scope::resolve`) follows JVMS 5.4.3.2/3.3 order,
  which differs by kind. Fields: the class, then its superinterfaces
  (recursively, before the superclass), then the superclass. Methods: the full
  superclass chain first, then maximally-specific superinterface methods
  (first-match, not the strict most-specific tie-break — enough for existence
  and access). The order matters only when the same name+descriptor lives on
  both a superclass and an interface with different static/access, so it fixes
  a latent owner-misattribution FP/FN; on the current fixtures and stress it is
  unobservable (goldens unchanged). A member moved to a superclass or interface
  still links at runtime and must not be reported. Escapes are conservative: a
  superclass-chain break (methods) or a superinterface-branch escape (fields,
  which have priority) yields Unknown rather than trusting a lower-priority
  match, since the unseen higher-priority type could shadow it.
- Constructors (`<init>`) and the class initializer (`<clinit>`) are NOT
  inherited: `resolve_member` resolves them owner-only. Walking the chain would
  bind a removed constructor to a superclass copy and misreport a real
  NoSuchMethodError as access-narrowed (jetty ArrayTernaryTrie dropped its
  `(Z)V` constructor while its super AbstractTrie kept a protected one, so the
  `new` call at PathMap/PathMappings is a removal, not a narrowing).
- The 11 `java/lang/Object` methods are built in. Kotlin facade classes extend
  Object; without this, real removals could degrade to Unknown when traversal
  reaches Object outside the indexed scope.
- Resolution scope is `new + scanned runtime classpath`, not `new` alone —
  matching flattened-classpath JVM linkage. Moves to another artifact and
  copies in fat JARs are not violations.
- Unknown is conservative OK: if traversal escapes analyzed scope or pass-2
  fetching fails, count the reference as unverified; never report it broken or
  drop it silently. `--jdk-release` shrinks the escape set by resolving JDK
  types from ct.sym; escapes into anything else (kotlin-stdlib off the scan,
  spring, servlet APIs) still end as Unknown.
- References that did not resolve against old are pre-existing inconsistency,
  not breakage introduced by the upgrade.
- Duplicate class names are first-wins in input path order (JVM classpath
  semantics); chunks are merged in path order to keep this deterministic.
  A losing duplicate definition is dropped whole — hierarchy, entry location,
  and its reference records — because the JVM never loads that copy. Judging a
  shadowed copy's bytecode against the winner's hierarchy produced false
  positives (sisu 0.3.4 shades asm while sisu 1.0.0 subclasses the real
  `ClassVisitor`; with 0.3.4 winning, 1.0.0's protected `super(int)` call
  looked like a method-access-narrowed violation). Guarded by
  `refs_from_shadowed_duplicate_jar_copies_are_not_reported` on real JARs.
  This also means the verdicts stream only carries references from winning
  definitions.
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
- Access narrowing is old-relative, like the static/final checks: report only
  when the access level decreased versus old resolution (private <
  package-private < protected < public). Equally inaccessible on both sides is
  pre-existing. The big real-world source is Java 11+ nest-internal private
  references (protobuf builders, anonymous enum bodies) when a copy of the
  checked library is itself scanned as classpath — a coordinate rename leaves
  the removed side pair-less, so the identical new JAR enters the scan. Levels
  are compared instead of re-running `is_accessible` against old because the
  subclass walk only sees scanned classes and would demote real
  protected->private narrowing to pre-existing.
- `is_subclass` (the protected-access subclass check) is three-valued
  (Yes/No/Unknown). Yes = the target was found on the walk; No = the full chain
  was walked to Object without it (a provable non-subclass); Unknown = the chain
  reached a class visible in no scope. `is_accessible` propagates Unknown, and
  the verdict then treats the reference as unverified, never broken. Without
  this a caller whose super chain escapes scope was assumed a non-subclass and a
  protected narrowing was falsely reported. No only when the relationship is
  provable, so a real break still fires. Controlled stress comparison (same
  classpath, before vs after this plus the constructor fix): +6 deduped
  violations, all previously-masked constructor removals, zero lost. The
  absolute stress count is classpath-order sensitive (duplicate-class first-wins
  shifts with `find` order), so only same-input diffs are meaningful.
- Private access allows nestmates (JVMS 5.4.4, Java 11+): both classes must
  share a nest host, read from the NestHost attribute (a class without one
  hosts itself). Hosts live on ClassGraph nodes (+8B/node, ~4MB at the stress
  workload's 431K-node graph; hosts are existing class names so interning does
  not grow) and on ClassEntry for resolution-scope classes; a class outside
  both scopes defaults to hosting itself, denying private access. NestMembers
  is not validated: the one-sided check errs toward not reporting, the same
  direction as Unknown.
- Newly-final classes/methods break scanned subclasses/overriders even without
  a constant-pool reference; `check.rs::add_final_violations` walks the class
  graph for these.
- Three JVMS class-shape breaks, all old-relative:
  - InstantiationError: `new X` where X became abstract or an interface. The
    `new` opcode (0xbb) is scanned in `classfile.rs` and sets `SymbolRef.instantiated`
    on the Class ref (array creation is not tracked — it never throws). Only a
    `new` breaks; a plain type reference to a now-abstract class stays OK.
    Reason `class became abstract`. Real fixture: jackson-module-kotlin
    ValueClassBoxConverter 2.18.2 concrete -> 2.20.1 abstract.
  - class<->interface flip at a call site: a Methodref (compiled against a class)
    whose owner is now an interface, or an InterfaceMethodref whose owner is now
    a class, makes resolution throw IncompatibleClassChangeError. Judged in
    `verdict` by comparing `RefKind::InterfaceMethod` against the owner's
    `ACC_INTERFACE`. Reason `class became interface` / `interface became class`.
  - class<->interface flip at a hierarchy edge: a scanned class extends a class
    that became an interface, or implements an interface that became a class,
    fails to load. Found by a graph walk, `check.rs::add_kind_flip_violations`,
    like the newly-final walk. Same two reasons. Real fixtures:
    ktor-io ByteChannel interface -> class (a Methodref-side flip), coroutines
    CancelHandler abstract-class -> interface (an extends-side flip on the
    stress workload).
- Sealing: a scanned class extends or implements a type that is now sealed without
  naming it in `permits`, so it fails to load (JVMS 5.3.5). `check.rs::add_sealed_violations`
  is another direct-edge graph walk, direct because that is the edge the JVM checks.
  Old-relative over the permits lists, which covers both shapes: the type gained a
  PermittedSubclasses attribute, or kept one and dropped a name. Adding names only
  widens (JLS 13.4.2). Enums and final classes are excluded as SUBJECTS on BOTH sides:
  javac has sealed enums with constant-specific bodies since JDK 17
  (https://issues.apache.org/jira/browse/GROOVY-10194), so without the guard a bare
  recompile reports a break; a final super is already `extends final class` on the new
  side; and an old-final super had no subclasses to strand, so a scanned one was broken
  before this upgrade. Sealing that could not be READ (truncated attribute table,
  malformed attribute) sets `sealing_unknown` and is skipped rather than read as
  unsealed, which is the direction that would turn a corrupt old class file into a
  violation.
  Reason `class became sealed`. No version-lag variant exists: `permits` targets must
  resolve when the sealed type compiles, so a sealed type and its permitted
  subclasses ship in one artifact and cannot skew apart. Sealing's same-module
  condition is not modeled, which can only lose a violation. Not probeable
  (a class-load break), so coverage is check.rs unit tests plus the
  JVM-confirmed `synthetic-sealed-*` fixture.
- AbstractMethodError: a concrete scanned class ends up inheriting an abstract
  method with no concrete implementation, so invoking it throws. Two upgrade
  shapes cause it, both handled by `check.rs::add_selection_violations`
  (`methods_newly_abstract` = abstract in new, not abstract in old, owner present
  in old): shape 1, a concrete method turned abstract; shape 2, a new abstract
  method added to an interface (or class) the consumer already extends/implements
  but does not provide. Like the newly-final and kind-flip walks it needs no
  constant-pool reference; the break is structural. The decision (`implementation_status`,
  `Concrete`/`AbstractOnly`/`Absent`/`Unknown`) follows JVMS 5.4.6 selection order,
  not a flat closure scan: phase 1 walks the superclass chain, which WINS over
  interfaces (the first class declaring the method decides it, even an abstract one,
  so a superclass abstract beats an interface default — JVM-confirmed), and phase 2
  consults superinterfaces only if no class declared it. Interface method specificity
  is NOT modeled, so a phase-2 mix of abstract and concrete declarations is
  inconclusive (`Unknown`) rather than guessed. That one rule avoids both a
  first-match false positive (a sibling default read as unimplemented) and a
  re-abstraction false positive (a shadowed default read as an implementation), at the
  cost of a rare false negative. `static`/`private` declarations never override, so
  they do not count. java/lang/Object supplies concrete versions of its own 11
  methods, so an interface redeclaring `equals`/`hashCode`/`toString` as abstract is
  not a break, while a non-Object superclass re-abstracting one still is.
  Old-relative: report only when new is `AbstractOnly` and old was `Concrete`
  (shape 1) or `Absent` (shape 2); abstract-on-both-sides is pre-existing and any
  scope escape stays Unknown. `collect_abstract_wanted` fetches the candidate
  class plus its scanned supertype closure in pass 2 so the scan has member
  tables, otherwise it escapes and answers Unknown (a silent FN). Only concrete
  classes are flagged; an abstract subclass is caught through its own concrete
  subclasses. Reason `method became abstract`. Not probeable
  (`MethodHandles.Lookup` does not model AbstractMethodError selection), so
  coverage is check.rs unit tests, JVM-confirmed synthetic scenarios (both
  shapes), and the koin fixture (koin 3.3.0 renamed the abstract `Logger.log` to
  `display`, so `SLF4JLogger` inherits an unimplemented abstract method — a real
  shape-2 break the golden pins alongside the log-became-final one).
- Conflicting default methods: two unrelated superinterfaces supply a default for the
  same signature and the concrete class overrides neither, so selection has no winner.
  Same walk (`add_selection_violations`), driven by `methods_newly_default` alongside
  `methods_newly_abstract`, and `implementation_status` grew an `ImplStatus::Conflict`
  for it. Which error the JVM raises depends on the CALL SITE, JVM-confirmed on the
  fixture: `invokevirtual` on the class throws IncompatibleClassChangeError
  ("Conflicting default methods"), `invokeinterface` on either interface throws
  AbstractMethodError. The report names both. Reason `conflicting default methods`.
  Conflict is decided over maximally-specific declarations only
  (`maximally_specific_count`, JVMS 5.4.3.3), so a subinterface redeclaring the default
  shadows its parents and is not a conflict. The abstract/concrete mix still answers
  Unknown ahead of this, so a conflict alongside an abstract sibling is a deliberate FN
  rather than a specificity guess. Old-relative: a conflict already present in old is
  pre-existing, which is also why the old-side `Concrete` arm now excludes it. Adding a
  default method is not itself an API change, so `diff.rs` gains nothing.
- Invocation evidence (the latent tier) makes `method became abstract` and
  `conflicting default methods` the violations judged on more than reachability,
  because both throw at INVOCATION, not at class load
  (https://github.com/exoego/uika/issues/81).
  `Violation.invocation_found` is `Some(false)` when no scanned method reference
  can dispatch the affected member onto the broken class.
  - The probe (`selection_member_probe`) is NOT empty on a typical upgrade: it
    covers newly ADDED abstract methods, unlike `diff.rs`'s `MethodBecameAbstract`
    (concrete->abstract only). Do not treat the sweep as rare. ~3% CPU on the
    stress workload, wall time and RSS unchanged. Collecting it inside pass 1's
    existing closure is load-bearing: reparsing the batch in a second pass cost
    ~21%, and a per-class Vec on `ParsedTarget` cost ~35MB RSS.
  - Matching is by name+descriptor for ANY owner, since `extract_refs` keeps only
    owners in the old index and the call may go through the subclass. The whole
    constant pool is scanned, not just `code_refs`: a method reference names the
    member only via a MethodHandle constant, which `CpEntry` does not model.
  - `ScanResult.invocations` is a second arena proportional to the whole scan when
    a probed signature is common, gated only on "something became abstract" —
    weaker than the `collect_edges` gate on reachability edges.
  - Evidence is deliberately NOT filtered by first-wins: the duplicate fast path
    still sweeps. Filtering there would tie `invocation_found` to whether two
    copies landed in the same chunk, and chunk size scales with the thread count.
  - `on_dispatch_chain` restricts evidence to the broken class's dispatch chain,
    otherwise one `close ()V` call anywhere suppresses every latent case. Escapes
    force "related", except into `java/*` (`Escapes`) — the JVM reserves that
    package, so such an escape cannot hide a library class. Without the carve-out
    the filter is nearly inert. `javax/`, `sun/`, `com/sun/` do not qualify.
  - `library_invocation_evidence` sweeps the --new jars, which need not be scan
    targets; koin-core calls its own `Logger.display`. NOT cached across
    per-module runs, so upgrade-check re-sweeps once per run.
  - Losing evidence is the WEAK direction: no evidence means latent means
    `--fail-on reachable` passes. The swallowed errors here (unreadable jar,
    undecodable pool) are tolerated only because such a class warned elsewhere.
  - Per-module merging folds `Some(true)` as dominant, like `reachable_rank`.
- Bridge/synthetic guard: `ACC_BRIDGE`/`ACC_SYNTHETIC` methods are excluded as
  the SUBJECT of the library-side `became abstract`/`became final` inferences
  (`methods_newly_abstract`/`newly_final_methods`), since a generic-signature
  edit can add or reshape a bridge without a source-visible API change. The
  guard is one-sided: a consumer's explicit Methodref to such a member is still
  resolved and reported, and such a method still counts as a concrete
  implementation in `implementation_status` (so it correctly suppresses an
  AbstractMethodError). Applied to the inference only, never to reference
  verdicts, so it removes false positives without adding false negatives.
- Version lag from the upgraded artifacts themselves: classes scanned from
  new-version JARs get an extra check — newly extending a class that is final
  on the runtime classpath is `extends final class`
  (`check.rs::add_extends_final_violations`). The pair diff cannot see this
  when the final class lives in an artifact the upgrade did not change
  (https://github.com/pact-foundation/pact-jvm/issues/1338: junit5spring 4.2.3
  introduced a subclass of a class the lagging junit5 4.2.2 still declares
  final). Old-relative gate: the same super edge in the changed artifact's old
  version is pre-existing. A super outside analyzed scope has no access flags
  and is skipped, the same direction as Unknown. Only the direct superclass
  matters, because a final class can have no subclasses at any depth.
- Object-array `Class` references unwrap to the element type; primitive arrays
  are ignored; method refs on array owners are ignored (array methods come from
  Object).
- `module-info.class` and `META-INF/versions/` entries are skipped by
  `input.rs::is_scannable`.

## Reachability

- `Violation.reachable` is `Some(false)` only when no static path reaches the
  referencing class. Over-approximate by design, so ⚠️ is a deprioritize hint,
  never grounds to drop a violation — the same conservative stance as Unknown.
- `model::tier(v, reachable_axis_valid)` is the single policy site turning a
  violation's evidence into its report/gating tier (💥 Breaks / 💤 Latent /
  ⚠️ Unproven). `report.rs` sections and `should_fail` make the SAME call with the
  same `app_roots_matched`, so the gate always matches the displayed grouping —
  `--fail-on reachable` is nothing but "fails on Breaks". Keep it that way; a gate
  that reasons about fields directly instead of about the tier is how the two
  drift apart. Proven-unreachable wins over latent (an unreachable class cannot
  load at all). The reachable axis is dropped when `app_roots_matched ==
  Some(false)` — roots were given and matched nothing, so `reachable =
  Some(false)` on every violation means nothing. The latent axis survives that,
  and reachability-off entirely, because its evidence comes from scanned
  bytecode rather than from app roots. Pinned by
  `gate_threshold_matches_the_reported_tier`. Exclude rules, not `--fail-on`, are
  where a kind-level policy lives, so the gate stays a pure tier threshold.
- On automatically, gated by app roots, not a flag: `run_check` computes
  `reachability = !app_roots.is_empty()` (single policy site). `upgrade-check`
  dumps and `check --app` have roots (on); a bare `check --classpath` has none
  (off, flat list, `reachable = None`). This also keeps the 2M-class
  classpath-only stress run from paying the cost. If roots are supplied but none
  match a scanned class (unbuilt build outputs), `reachable_classes` reports
  `app_root_matched = false` and `check_scanned` emits the warning that goes
  with the axis-dropping above.
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

## Runtime load evidence (--class-load-log)

- Promote-only, enforced in two places: `evidence::apply` only ever sets
  `observed_loading = true`, and `model::tier` reads it only to defeat the
  Unproven arm — never to demote, and never to touch the latent axis (loading
  proves nothing about invocation, so an observed `method became abstract`
  without invocation evidence stays 💤). The one deliberate consumer of
  absence-of-evidence is `--draft-exclude-file`, which writes a REVIEW-prefixed
  file for a human and nothing else.
- Applied ONCE per command by the lib.rs command layer
  (`apply_evidence_and_draft`), to the FINAL violation set: after per-module
  merging and exclusion, before printing and the exit decision. `run_check` is
  untouched, which is what keeps the goldens byte-identical (the new Violation
  fields are skip-serialized at their defaults) and the verdicts stream stable.
  Do not push evidence into run_check: per-module runs would re-apply it per
  run and the single-policy-site rule would be lost.
- The parser (`evidence.rs::parse_line`) is deliberately lenient — decorated UL
  lines, `class+load+cause` stack blocks (`at ...` frames are NEVER read as
  loaded classes), and bare class-name tokens. The tags decorator is matched by
  exact comma-membership ("class" and "load" both present), not substring:
  `class,loader,data` contains the substring but is another stream. Only a
  multi-member group counts as a FOREIGN tags decorator (a single word could be
  a level or hostname decorator), so a tags-less decorator set still parses via
  the token path, and a skipped foreign line does not end an open stack block
  (streams interleave per line). Tokens are segment-validated (IPs and versions
  in mixed logs do not register; non-ASCII bytes are identifier characters,
  since JVM names are barely restricted); a bare token needs two segments,
  while a JVM-labeled one (tagged line or stack header) accepts one, so
  default-package classes still promote. Names normalize to the slashed form
  so lookups match `Violation.source_class` directly. Cause stacks are never
  retained: the trigger is computed as frames stream by and one record per
  class is stored (`-XX:LogClassLoadingCauseFor=*` names every loaded class of
  a run, and retaining even capped stacks cost hundreds of MB), which also
  finds the trigger past arbitrarily deep delegation chains. First stack with
  frames wins. Lines are read with a length cap so a stray newline-less binary
  in the directory cannot be buffered whole.
- Two rules exist because REAL JDK 25 cause output broke the synthetic
  assumptions (`real_jdk_stack_shape_parses_through_monitors_and_custom_loaders`
  pins the verbatim shape): monitor annotations (`- locked <0x...>`) interleave
  a stack's frames and must not end the block, and `is_machinery` matches
  loader METHOD NAMES (loadClass/findClass/defineClass) on top of the JDK
  loader packages, because a custom loader (javac's source-launcher
  MemoryClassLoader, an app server's) sits in the delegation chain under its
  own package and would otherwise be picked as the trigger. The class-load
  guard test, `a_real_jvm_emitted_class_load_log_promotes_the_violation`,
  feeds a log a real `java -Xlog` run EMITTED into the full report and skips
  without a JVM (JAVA_HOME, PATH, or mise) — keep it, because promote-only
  means parser-vs-format drift has no symptom besides silently promoting
  nothing. Its `class+load+cause` half needs JDK 22+, above the pinned
  toolchain, so run it against a newer JDK when touching the cause parsing.
- Drafting groups by the referenced symbol because that is what an exclude rule
  matches: a symbol also broken by a reachable or observed class is never
  drafted, since the rule would waive the real break too. A member-less rule is
  wider than its symbol — `exclude::filter` reads it as the owner outright — so
  a class-level symbol is drafted only when every symbol on its owner is
  draftable. Per-module drafting judges the axis from the Some(false)-dominating
  fold of the per-run roots states, never `merged.app_roots_matched` (which
  stays None): a module whose roots matched nothing has meaningless
  `reachable = Some(false)` violations that the per-run gate fails as Breaks,
  and drafting them would propose waiving exactly those. Drafted TOML must
  round-trip through `exclude::load` (pinned by
  `drafts_only_fully_unproven_unobserved_symbols`). A requested draft file is
  created upfront like --verdicts-json (`create_draft_placeholder`), so an
  errored run leaves a fresh placeholder, never a stale draft from an earlier
  run; quiet runs (no changes, empty plan) write the empty draft through the
  same `apply_evidence_and_draft` call as checked runs.

## SPI (ServiceLoader) provider breaks

- A class named in `META-INF/services/<iface>` that ServiceLoader could construct under
  old but not under new throws `ServiceConfigurationError` at load()/iteration time.
  Deliberately not part of "Linkage Semantics" above: SCE extends Error, not LinkageError —
  ServiceLoader resolves the provider reflectively and wraps the failure itself, so no
  constant-pool reference or class-load edge carries the break; the "reference" is a text
  line in a resource file. JVM-confirmed on the `synthetic-spi-*` fixture and the real
  kotest relocation-shim pair (tests/fixtures/README.md).
- `check.rs::add_spi_violations` is fed `ServiceFile`s read from the old/new library JARs
  in `run_check_with_indexes` — always, unlike the reachability-gated consumer-side read
  (library JARs are small). Candidates are only providers BOTH sides list for the same
  service (`still_listed_providers`, deduplicated across files the way ServiceLoader dedups
  names, first registration wins): a dropped line is a deliberate library decision, not
  provably a break (the unreported "Arm B"); a newly added one has nothing to regress
  against.
- Instantiability is the CLASS-PATH rule: public, concrete, assignable to the service
  type, public no-arg constructor. A public static `provider()` factory is deliberately
  ignored: the JDK honors factories only for explicit-module providers resolved from
  `provides` directives (`ServiceLoader.loadProvider` gates on `inExplicitModule`), while
  `LazyClassPathLookupIterator` — the only reader of META-INF/services — and the
  automatic-module path derived from those files require the constructor unconditionally.
  A factory therefore neither rescues a lost constructor nor breaks a valid provider; an
  earlier version modeled factory precedence and had both directions wrong, pinned now by
  `a_provider_factory_does_not_rescue_a_lost_constructor` and
  `a_stray_provider_factory_on_a_valid_provider_is_not_reported`.
- Only a PROVEN old-side Yes gates the check and only a PROVEN new-side No is reported;
  `Subclass::Unknown` suppresses both ways, including a provider only the scan graph knows
  (still on the runtime classpath, member table unfetched — never "removed"). Resolution
  uses the same old/runtime scopes as every other check, so a provider moved to an
  unchanged classpath JAR is not a violation; `collect_spi_wanted` fetches member tables
  for still-listed providers missing from either library index (the moved case, and a
  shadow copy surviving a library-side removal). The old side's assignability walk
  (`is_assignable`) is scope-first: the scan graph carries the NEW hierarchy whenever the
  upgraded jar is a scan target, and graph-first re-judged the old side by the new shape,
  hiding every dropped-interface break
  (`a_dropped_interface_is_reported_when_the_new_jar_is_a_scan_target`).
- Violation shape: `source_class` = provider, `reference.owner` = service interface,
  `source` = the jar whose service file still names it — the sealed/kind-flip subject-first
  shape, rendered via `report.rs::structural_lines`. A scanned jar's own registrations are
  already reachability edges in `reach.rs`, so the provider is ranked for free; when no
  scan target registers it the mark is unobservable and `check_scanned` leaves
  `reachable = None` rather than claiming proven-unreachable
  (`an_unscanned_provider_is_not_proven_unreachable`). In upgrade-check, a suggestion whose
  referencing coordinate IS the changed coordinate is skipped as self-referential
  (suggest.rs).
- Not in the verdicts stream (the root CLAUDE.md exclusion list names both reasons) and
  not golden-coverable: `check::check`, the goldens' entry point, has no JAR paths to read
  META-INF/services from. Coverage is `detects_a_provider_that_became_abstract` (synthetic,
  not instantiable), `detects_kotest_stale_engine_registration_in_the_relocation_shim`
  (real pair, removed) and `detects_sshds_stale_file_system_provider_registration`
  (real pair whose service is a JDK class outside every scope: the provider names it as
  its direct superclass, so the walk proves the old side from the edge alone), all
  path-based `run_check`, plus the check.rs unit tests.

## Suggestions (upgrade-check only)

- `suggest::annotate` fills `Violation.suggestion` after `run_check`, in
  `cmd_upgrade_check` where coordinates exist. Plain `check` has only file paths,
  so its violations stay `suggestion = None` and `report.rs` prints nothing extra.
- `report.rs` groups attributed violations suggestion-first (README shows the
  shape). The group key is the advice PLUS every field the "why:" line quotes
  (`removed_by`/`before`/`after`/`referenced_by`), never the advice alone: the
  removed-coordinate advice embeds no versions and the changed-coordinate
  advice embeds only the moved delta, so per-module runs over different
  resolved version lists can produce byte-identical advice whose versions
  differ. Violations with no suggestion (plain `check`, or unattributed
  upgrade-check leftovers) fall back to the per-symbol / per-class ❌ blocks.
  The text format itself is defined by report.rs plus its tests, not here.
- `referenced_by` comes from a dump `file-display-string -> "g:n:v"` map (both
  before and after sides). `removed_by` comes from mapping the violation's owner
  class to a changed coordinate by reading the before-side JARs' class names
  (`input::load`, first-wins) — a small, best-effort scan that never blocks the
  report on read failure.
- The same-group / cross-group advice split (README describes it) mirrors the
  real fixes found for the OpenTelemetry case: BOM-align the 41 skew breaks,
  handle the cross-group firestore/grpc one separately.
- When the upgrade drops a coordinate the REFERENCING artifact declares
  `<optional>true</optional>`, the advice drops the "still needs it / upgrade to
  a release that no longer requires it" claim
  (https://github.com/exoego/uika/issues/96). Wording only — an optional
  integration that IS used still throws, so the violation, its tier and the exit
  code are untouched. What it asserts stops at what the POM states. It must not
  say the coordinate arrived through some other dependency: the dump has no
  requested-by edges, and a build that declared it directly and dropped that
  declaration makes it false. That is #96's own mistake pointed the other way.
- `pom.rs` reads a POM already sitting beside the scanned JAR in the local
  artifact cache, so there is still no resolver, network or JVM, and every
  failure path falls back to the original wording. Plumbing the flag through the
  dump was rejected: a format change plus three plugin implementations, rewording
  nothing until both sides are re-dumped. The cost is that advice — a serialized
  field AND part of the 💡 grouping key and the cross-run merge key — now depends
  on the checking machine's cache, so identical dumps can differ on a runner
  holding only jars.
- Two rules keep profile looseness (activation is not evaluated) from becoming
  false POSITIVES, and neither is redundant: an always-active non-optional
  declaration overrides every profile-scoped optional one, and a `<classifier>`
  block is skipped. netty-transport-native-epoll needs both — it requires
  unix-common at top level and declares the classifier-ed native variant optional
  in its OS profiles, so first-match told the user a hard requirement "was never
  required transitively".
- Ignored regions (`<dependencyManagement>`, `<plugins>`, `<exclusions>`,
  comments, CDATA, PIs) are removed by BLANKING, never by collecting spans or
  truncating. Blanking is what makes an unterminated element swallow the
  remainder and read as not-optional; spans failed OPEN, and since XML permits
  `</dependencyManagement >`, one well-formed POM promoted every managed entry to
  a real declaration.
- `roxmltree` was measured as the alternative to the string scan and rejected:
  +21,516 B zipped (+3.0%) against the published-size budget, versus +321 B for
  this file. The trade is paid for by differential-testing every edit here
  against the previous implementation over the whole local POM cache; the rules
  above came out of one such run (8,740 POMs, 125,938 probes).
