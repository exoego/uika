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
  warning) when a dump has no module with artifacts. Rationale: the union
  mixes several resolved versions of one coordinate, which produced false
  brokens (a jar's self-consistent internal references judged against a
  sibling module's newer version -- the henry-backend netty case) AND false
  negatives (an upgrade invisible to the flat diff because a sibling still
  resolves the old version). Both are pinned by
  `per_module_upgrade_check_gates_on_each_modules_own_resolution`.
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
  report's `app_roots_matched` keeps the Some(false)-dominating fold for the
  display warning.

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
    `ACC_INTERFACE`. Reason `class kind changed`.
  - class<->interface flip at a hierarchy edge: a scanned class extends a class
    that became an interface, or implements an interface that became a class,
    fails to load. Found by a graph walk, `check.rs::add_kind_flip_violations`,
    like the newly-final walk. Reason `class kind changed`. Real fixtures:
    ktor-io ByteChannel interface -> class (a Methodref-side flip), coroutines
    CancelHandler abstract-class -> interface (an extends-side flip on the
    stress workload).
- AbstractMethodError: a concrete scanned class ends up inheriting an abstract
  method with no concrete implementation, so invoking it throws. Two upgrade
  shapes cause it, both handled by `check.rs::add_abstract_method_violations`
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
- `report.rs` is suggestion-first for attributed violations: it groups them
  (one 💡 block lists every reference a fix covers) instead of repeating the
  advice per reference. The group key is the advice PLUS every field the
  "why:" line quotes (`removed_by`/`before`/`after`/`referenced_by`), never
  the advice alone: the removed-coordinate advice embeds no versions and the
  changed-coordinate advice embeds only the moved delta, so per-module runs
  over different resolved version lists can produce byte-identical advice
  whose versions differ. Grouping is done inside each reachability section,
  so a fix spanning both tiers prints once under 💥 and once under ⚠️. Violations with no
  suggestion (plain `check`, or unattributed upgrade-check leftovers) fall back
  to the per-symbol / per-class ❌ blocks. The text format itself is not
  documented here: the README examples show it, and report.rs plus its tests
  define it.
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
