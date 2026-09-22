# Notes for Agents: cli-java/

The pure-Java CLI. One jar instead of a binary per OS and CPU. The repo-root `CLAUDE.md`
holds the cross-cutting invariants. This file holds the check pipeline and linkage
semantics, then what only matters on the JVM.

## Parity

- The goldens (`tests/golden`), the probe (`make probe`) and the integration tests pin the
  behaviour the Rust crate had when it was retired: stdout, JSON, the verdicts stream and
  exit codes are byte-identical to what it printed. Treat a change in any of them as a
  detection change to argue for, never as a port detail.
- `GoldenTest` runs with `cli-java/` as the working directory, because a violation's
  `source` is the path string as given (`tests/fixtures/x.jar`) and the goldens pin it.
  `make java-cli-bless` rewrites them.
- Symbols order by UTF-8 bytes (`Intern.compare`, `Text.compareUtf8`). `String.compareTo`
  orders by UTF-16 unit and disagrees above the surrogate range.

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

Check.collectWanted: walk the hierarchy from referenced owners; keep only classes
  that resolution may visit

pass 2: Check.fetchMembers: re-read wanted classes from their origin, build a small
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
  check remains behind `--merged-classpath` and as the automatic fallback (with a
  warning) when a dump has no module with artifacts. Rationale (README states
  the user-facing version): the union mixes several resolved versions of one
  coordinate, which produced false brokens (observed on a real multi-module
  monorepo whose modules resolve two netty lines) AND false negatives. Both are
  pinned by `UpgradeCheckIntegrationTest.perModuleUpgradeCheckGatesOnEachModulesOwnResolution`.
- Gating is per-module "the old jar list is empty" -- the same old-version-
  disappeared gate as merged mode, over the module's own version maps.
  Unchanged modules are skipped. An after-only module (renamed or added) is
  NOT skipped outright: it is diffed against the union's before versions
  scoped to its own coordinates (a rename+upgrade must not ship unchecked;
  `perModuleCheckCoversRenamedModuleViaUnionFallback`); only when
  that finds nothing is it counted new. An after module whose artifact list
  vanished while its before had one is skipped as `incomplete` (partial
  dump, e.g. `mvn -pl`), never diffed as total removal. Runs with identical
  (old, new, targets, roots) share one run.
- The version diff excludes `Dump.projectCoordsUnion(before, after)` -- the
  UNION of both dumps' project-attributed coordinates -- not a per-side
  filter. One-sided exclusion made a before dump from an older plugin diff an
  unchanged reactor dep as Removed, and `Commands.runCheck` then dropped that jar from
  the scan as stale (mass false "class removed"). The coordinates STAY in
  `Dump.Universe.versions` so suggest's file->coordinate map still attributes
  referencing reactor jars.
- Violations merge across runs keyed by (source, class, SymbolRef, reason,
  advice) -- NOT reachable: runs that disagree on reachability must count one
  break once, kept at the most-reachable value (`Commands.reachableRank`: true >
  null > false). Advice IS in the key because suggestions are annotated
  per run from the module's own change list (exact versions even when the
  global diff is empty -- the swap case -- or unions other modules' moves).
  Module attribution lands in `Violation.modules` (omitted from the JSON when
  empty, so plain-check JSON and the goldens are byte-identical). Exclude
  rules filter the merged set ONCE, not per run.
- The empty global change list must never swallow the report: `Report.upgradeText`
  prints the module summary and violations regardless (a swap between modules
  has changes=none but real per-module breaks;
  `perModuleCheckReportsBreakWhenGlobalVersionSetIsUnchanged`).
- `scannedClasses`/`unknownRefs` in the aggregate are per-run sums (a jar on
  several checked modules' classpaths is counted once per run; that rescan is
  the accepted per-module cost -- old/new library indexes at least are built
  once per distinct pair via the run loop's index cache in `Commands`). The per-run
  broken counts in `Report.ModuleRunSummary` come from the merged post-exclusion set
  so they agree with the listing.
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
  via module attribution) are judged with that run's own `appRootsMatched`,
  so one module whose roots matched nothing degrades `--fail-on reachable`
  to `any` for ITS violations only, not for healthy modules. The merged
  report's `appRootsMatched` stays null (per-run states live on the
  `Commands.RunOutcome`s); anything needing one cross-run axis folds those states
  false-dominant, the way `--draft-exclude-file` gating does.

## Linkage Semantics

- Visibility is bytecode-level. Kotlin `internal` is public in bytecode;
  detecting such references is core to the tool.
- Member lookup (`Scope.resolve`) follows JVMS 5.4.3.2/3.3 order,
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
  inherited: `Scope.resolveMember` resolves them owner-only. Walking the chain would
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
  `IntegrationTest.refsFromShadowedDuplicateJarCopiesAreNotReported` on real JARs.
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
  are compared instead of re-running `Check.isAccessible` against old because the
  subclass walk only sees scanned classes and would demote real
  protected->private narrowing to pre-existing.
- `Check.isSubclass` (the protected-access subclass check) is three-valued
  (`YES`/`NO`/`UNKNOWN`). YES = the target was found on the walk; NO = the full chain
  was walked to Object without it (a provable non-subclass); UNKNOWN = the chain
  reached a class visible in no scope. `isAccessible` propagates UNKNOWN, and
  the verdict then treats the reference as unverified, never broken. Without
  this a caller whose super chain escapes scope was assumed a non-subclass and a
  protected narrowing was falsely reported. NO only when the relationship is
  provable, so a real break still fires. Controlled stress comparison (same
  classpath, before vs after this plus the constructor fix): +6 deduped
  violations, all previously-masked constructor removals, zero lost. The
  absolute stress count is classpath-order sensitive (duplicate-class first-wins
  shifts with `find` order), so only same-input diffs are meaningful.
- Private access allows nestmates (JVMS 5.4.4, Java 11+): both classes must
  share a nest host, read from the NestHost attribute (a class without one
  hosts itself). Hosts live on ClassGraph nodes (one int per node, ~4MB at the stress
  workload's 431K-node graph; hosts are existing class names so interning does
  not grow) and on the entries of resolution-scope classes; a class outside
  both scopes defaults to hosting itself, denying private access. NestHost sits at
  the end of the file, so a class whose tail pass 1 never inflated carries
  `ClassGraph.NEST_HOST_UNREAD` and `Check.NestHosts` reads it on first use. NestMembers
  is not validated: the one-sided check errs toward not reporting, the same
  direction as Unknown.
- Newly-final classes/methods break scanned subclasses/overriders even without
  a constant-pool reference; `Check.addFinalViolations` walks the class
  graph for these.
- Three JVMS class-shape breaks, all old-relative:
  - InstantiationError: `new X` where X became abstract or an interface. The
    `new` opcode (0xbb) is scanned in `ClassParser` and sets `SymbolRef.instantiated`
    on the Class ref (array creation is not tracked — it never throws). Only a
    `new` breaks; a plain type reference to a now-abstract class stays OK.
    Reason `class became abstract`. Real fixture: jackson-module-kotlin
    ValueClassBoxConverter 2.18.2 concrete -> 2.20.1 abstract.
  - class<->interface flip at a call site: a Methodref (compiled against a class)
    whose owner is now an interface, or an InterfaceMethodref whose owner is now
    a class, makes resolution throw IncompatibleClassChangeError. Judged in
    `Check.verdictOf` by comparing `RefKind.INTERFACE_METHOD` against the owner's
    `Acc.INTERFACE`. Reason `class became interface` / `interface became class`.
  - class<->interface flip at a hierarchy edge: a scanned class extends a class
    that became an interface, or implements an interface that became a class,
    fails to load. Found by a graph walk, `Check.addKindFlipViolations`,
    like the newly-final walk. Same two reasons. Real fixtures:
    ktor-io ByteChannel interface -> class (a Methodref-side flip), coroutines
    CancelHandler abstract-class -> interface (an extends-side flip on the
    stress workload).
- Sealing: a scanned class extends or implements a type that is now sealed without
  naming it in `permits`, so it fails to load (JVMS 5.3.5). `Check.addSealedViolations`
  is another direct-edge graph walk, direct because that is the edge the JVM checks.
  Old-relative over the permits lists, which covers both shapes: the type gained a
  PermittedSubclasses attribute, or kept one and dropped a name. Adding names only
  widens (JLS 13.4.2). Enums and final classes are excluded as SUBJECTS on BOTH sides:
  javac has sealed enums with constant-specific bodies since JDK 17
  (https://issues.apache.org/jira/browse/GROOVY-10194), so without the guard a bare
  recompile reports a break; a final super is already `extends final class` on the new
  side; and an old-final super had no subclasses to strand, so a scanned one was broken
  before this upgrade. Sealing that could not be READ (truncated attribute table,
  malformed attribute) sets `sealingUnknown` and is skipped rather than read as
  unsealed, which is the direction that would turn a corrupt old class file into a
  violation.
  Reason `class became sealed`. No version-lag variant exists: `permits` targets must
  resolve when the sealed type compiles, so a sealed type and its permitted
  subclasses ship in one artifact and cannot skew apart. Sealing's same-module
  condition is not modeled, which can only lose a violation. Not probeable
  (a class-load break), so coverage is `CheckTest` plus the
  JVM-confirmed `synthetic-sealed-*` fixture.
- AbstractMethodError: a concrete scanned class ends up inheriting an abstract
  method with no concrete implementation, so invoking it throws. Two upgrade
  shapes cause it, both handled by `Check.addSelectionViolations`
  (`methodsNewlyAbstract` = abstract in new, not abstract in old, owner present
  in old): shape 1, a concrete method turned abstract; shape 2, a new abstract
  method added to an interface (or class) the consumer already extends/implements
  but does not provide. Like the newly-final and kind-flip walks it needs no
  constant-pool reference; the break is structural. The decision (`implementationStatus`,
  `CONCRETE`/`ABSTRACT_ONLY`/`ABSENT`/`UNKNOWN`) follows JVMS 5.4.6 selection order,
  not a flat closure scan: phase 1 walks the superclass chain, which WINS over
  interfaces (the first class declaring the method decides it, even an abstract one,
  so a superclass abstract beats an interface default — JVM-confirmed), and phase 2
  consults superinterfaces only if no class declared it. Interface method specificity
  is NOT modeled, so a phase-2 mix of abstract and concrete declarations is
  inconclusive (`UNKNOWN`) rather than guessed. That one rule avoids both a
  first-match false positive (a sibling default read as unimplemented) and a
  re-abstraction false positive (a shadowed default read as an implementation), at the
  cost of a rare false negative. `static`/`private` declarations never override, so
  they do not count. java/lang/Object supplies concrete versions of its own 11
  methods, so an interface redeclaring `equals`/`hashCode`/`toString` as abstract is
  not a break, while a non-Object superclass re-abstracting one still is.
  Old-relative: report only when new is `ABSTRACT_ONLY` and old was `CONCRETE`
  (shape 1) or `ABSENT` (shape 2); abstract-on-both-sides is pre-existing and any
  scope escape stays Unknown. `collectAbstractWanted` fetches the candidate
  class plus its scanned supertype closure in pass 2 so the scan has member
  tables, otherwise it escapes and answers Unknown (a silent FN). Only concrete
  classes are flagged; an abstract subclass is caught through its own concrete
  subclasses. Reason `method became abstract`. Not probeable
  (`MethodHandles.Lookup` does not model AbstractMethodError selection), so
  coverage is `CheckSelectionTest`, JVM-confirmed synthetic scenarios (both
  shapes), and the koin fixture (koin 3.3.0 renamed the abstract `Logger.log` to
  `display`, so `SLF4JLogger` inherits an unimplemented abstract method — a real
  shape-2 break the golden pins alongside the log-became-final one).
- Conflicting default methods: two unrelated superinterfaces supply a default for the
  same signature and the concrete class overrides neither, so selection has no winner.
  Same walk (`addSelectionViolations`), driven by `methodsNewlyDefault` alongside
  `methodsNewlyAbstract`, and `implementationStatus` grew an `ImplStatus.CONFLICT`
  for it. Which error the JVM raises depends on the CALL SITE, JVM-confirmed on the
  fixture: `invokevirtual` on the class throws IncompatibleClassChangeError
  ("Conflicting default methods"), `invokeinterface` on either interface throws
  AbstractMethodError. The report names both. Reason `conflicting default methods`.
  Conflict is decided over maximally-specific declarations only
  (`maximallySpecificCount`, JVMS 5.4.3.3), so a subinterface redeclaring the default
  shadows its parents and is not a conflict. The abstract/concrete mix still answers
  Unknown ahead of this, so a conflict alongside an abstract sibling is a deliberate FN
  rather than a specificity guess. Old-relative: a conflict already present in old is
  pre-existing, which is also why the old-side `CONCRETE` arm now excludes it. Adding a
  default method is not itself an API change, so `Diff` gains nothing.
- Invocation evidence (the latent tier) makes `method became abstract` and
  `conflicting default methods` the violations judged on more than reachability,
  because both throw at INVOCATION, not at class load
  (https://github.com/exoego/uika/issues/81).
  `Violation.invocationFound` is `false` when no scanned method reference
  can dispatch the affected member onto the broken class.
  - The probe (`selectionMemberProbe`) is NOT empty on a typical upgrade: it
    covers newly ADDED abstract methods, unlike `Diff`'s `METHOD_BECAME_ABSTRACT`
    (concrete->abstract only). Do not treat the sweep as rare. ~3% CPU on the
    stress workload, wall time and RSS unchanged. Collecting it inside pass 1's
    existing pass is load-bearing: reparsing the batch in a second pass cost
    ~21%, and a per-class record cost ~35MB RSS.
  - Matching is by name+descriptor for ANY owner, since `Extract.extractRefs` keeps only
    owners in the old index and the call may go through the subclass. The whole
    constant pool is scanned, not just `codeRefs`: a method reference names the
    member only via a MethodHandle constant, which the parser's entry model does not
    follow.
  - `Scan.Result.invocations` is a second arena proportional to the whole scan when
    a probed signature is common, gated only on "something became abstract" —
    weaker than the `collectEdges` gate on reachability edges.
  - Evidence is deliberately NOT filtered by first-wins: the duplicate fast path
    still sweeps. Filtering there would tie `invocationFound` to whether two
    copies landed in the same chunk, and chunk size scales with the thread count.
  - `onDispatchChain` restricts evidence to the broken class's dispatch chain,
    otherwise one `close ()V` call anywhere suppresses every latent case. Escapes
    force "related", except into `java/*` (`Escapes`) — the JVM reserves that
    package, so such an escape cannot hide a library class. Without the carve-out
    the filter is nearly inert. `javax/`, `sun/`, `com/sun/` do not qualify.
  - `libraryInvocationEvidence` sweeps the --new jars, which need not be scan
    targets; koin-core calls its own `Logger.display`. NOT cached across
    per-module runs, so upgrade-check re-sweeps once per run.
  - Losing evidence is the WEAK direction: no evidence means latent means
    `--fail-on reachable` passes. The swallowed errors here (unreadable jar,
    undecodable pool) are tolerated only because such a class warned elsewhere.
  - Per-module merging folds `true` as dominant, like `reachableRank`.
- Bridge/synthetic guard: a method that is `ACC_BRIDGE`/`ACC_SYNTHETIC` on every
  side where it exists is not the SUBJECT of the library-side `became abstract` and
  `became final` inferences (`Check.compilerGeneratedOnly`), because a generic-signature
  edit can add or reshape a bridge without a source-visible API change. A real method on
  EITHER side keeps the inference, since three JVM-confirmed breaks go through a bridge.
  Generifying `LoggingHandler extends Handler<String>` to `LoggingHandler<T>` with
  `abstract handle(T)` makes a subclass that inherited the old javac bridge throw
  AbstractMethodError. The same edit with `final handle(T)` rejects a subclass whose own
  bridge now overrides a final method. Porting a Java `AbstractList` subclass to Kotlin
  makes `size()` a final kotlinc bridge, which rejects a Java subclass overriding it. The
  guard can still hide a method that is a bridge on both sides and final in new. javac
  never emits a final or abstract bridge, so only kotlinc's final bridges reach that. The
  guard never touches reference verdicts, and a bridge still counts as a concrete
  implementation in `implementationStatus`.
- Version lag from the upgraded artifacts themselves: classes scanned from
  new-version JARs get an extra check — newly extending a class that is final
  on the runtime classpath is `extends final class`
  (`Check.addExtendsFinalViolations`). The pair diff cannot see this
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
  `Input.isScannable`.

## Reachability

- `Violation.reachable` is `false` only when no static path reaches the
  referencing class. Over-approximate by design, so ⚠️ is a deprioritize hint,
  never grounds to drop a violation — the same conservative stance as Unknown.
- `Tier.of(v, reachableAxisValid)` is the single policy site turning a
  violation's evidence into its report/gating tier (💥 Breaks / 💤 Latent /
  ⚠️ Unproven). `Report` sections and `Commands.shouldFail` make the SAME call with the
  same `appRootsMatched`, so the gate always matches the displayed grouping —
  `--fail-on reachable` is nothing but "fails on Breaks". Keep it that way; a gate
  that reasons about fields directly instead of about the tier is how the two
  drift apart. Proven-unreachable wins over latent (an unreachable class cannot
  load at all). The reachable axis is dropped when `appRootsMatched` is
  `false` — roots were given and matched nothing, so `reachable =
  false` on every violation means nothing. The latent axis survives that,
  and reachability-off entirely, because its evidence comes from scanned
  bytecode rather than from app roots. Pinned by
  `CommandsTest.gateThresholdMatchesTheReportedTier`. Exclude rules, not `--fail-on`,
  are where a kind-level policy lives, so the gate stays a pure tier threshold.
- On automatically, gated by app roots, not a flag: `Commands.runCheck` computes
  reachability as "app roots given" (single policy site). `upgrade-check`
  dumps and `check --app` have roots (on); a bare `check --classpath` has none
  (off, flat list, `reachable = null`). This also keeps the 2M-class
  classpath-only stress run from paying the cost. If roots are supplied but none
  match a scanned class (unbuilt build outputs), `Reach.reachableClasses` reports
  `appRootMatched = false` and `Check.checkScanned` emits the warning that goes
  with the axis-dropping above.
- Roots are the application: `--app` targets and dump `classesDirs`
  (`Dump.Universe.appRoots`). App sources are matched by interning the root path's
  display string, the same string `Input` interns as a class's `source`, so a
  root only contributes if it is also a scan target.
- Edges (`Reach`, BFS over a `BitSet` indexed by symbol): constant-pool `Class`
  constants + hierarchy (super/interfaces) + class-name-shaped string constants
  (`Extract`, a `Class.forName` over-approximation) +
  `META-INF/services` providers. A provider whose service interface is outside
  the scanned scope (JDK SPI like `java.sql.Driver`) becomes a root, because the
  runtime can instantiate it unobserved.
- Edges are collected in pass 1 only when reachability is on
  (`Scan.scanTargetPaths(..., collectEdges)`), stored in a shared arena on
  `ClassGraph` like interfaces. They cost ~10-33% extra RSS (up to ~130MB on the
  2M-class stress workload) with negligible extra time, so keep them gated on the
  root-driven flag rather than always building them.
- Class-name-shaped strings are interned unconditionally so an edge does not
  depend on parse order (determinism); non-class strings become dead symbols that
  BFS never marks.

## Runtime load evidence (--class-load-log)

- Promote-only, enforced in two places: `Evidence.apply` only ever sets
  `observedLoading = true`, and `Tier.of` reads it only to defeat the
  Unproven arm — never to demote, and never to touch the latent axis (loading
  proves nothing about invocation, so an observed `method became abstract`
  without invocation evidence stays 💤). The one deliberate consumer of
  absence-of-evidence is `--draft-exclude-file`, which writes a REVIEW-prefixed
  file for a human and nothing else.
- Applied ONCE per command by the command layer
  (`Commands.applyEvidenceAndDraft`), to the FINAL violation set: after per-module
  merging and exclusion, before printing and the exit decision. `Commands.runCheck` is
  untouched, which is what keeps the goldens byte-identical (the evidence fields
  are omitted from the JSON at their defaults) and the verdicts stream stable.
  Do not push evidence into `runCheck`: per-module runs would re-apply it per
  run and the single-policy-site rule would be lost.
- The parser (`Evidence.parseLine`) is deliberately lenient — decorated UL
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
  so lookups match `Violation.sourceClass` directly. Cause stacks are never
  retained: the trigger is computed as frames stream by and one record per
  class is stored (`-XX:LogClassLoadingCauseFor=*` names every loaded class of
  a run, and retaining even capped stacks cost hundreds of MB), which also
  finds the trigger past arbitrarily deep delegation chains. First stack with
  frames wins. Lines are read with a length cap so a stray newline-less binary
  in the directory cannot be buffered whole.
- Two rules exist because REAL JDK 25 cause output broke the synthetic
  assumptions (`EvidenceTest.realJdkStackShapeParsesThroughMonitorsAndCustomLoaders`
  pins the verbatim shape): monitor annotations (`- locked <0x...>`) interleave
  a stack's frames and must not end the block, and `Evidence.isMachinery` matches
  loader METHOD NAMES (loadClass/findClass/defineClass) on top of the JDK
  loader packages, because a custom loader (javac's source-launcher
  MemoryClassLoader, an app server's) sits in the delegation chain under its
  own package and would otherwise be picked as the trigger. Real-format drift
  is pinned by `IntegrationTest.aRealJvmEmittedClassLoadLogPromotesTheViolation`,
  whose `class+load+cause` half needs JDK 22+, above the pinned toolchain —
  run it against a newer JDK when touching the cause parsing.
- Drafting groups by the referenced symbol because that is what an exclude rule
  matches: a symbol also broken by a reachable or observed class is never
  drafted, since the rule would waive the real break too. A member-less rule is
  wider than its symbol — `Exclude.filter` reads it as the owner outright — so
  a class-level symbol is drafted only when every symbol on its owner is
  draftable. Per-module drafting judges the axis from the false-dominating
  fold of the per-run roots states, never the merged report's `appRootsMatched`
  (which stays null): a module whose roots matched nothing has meaningless
  `reachable = false` violations that the per-run gate fails as Breaks,
  and drafting them would propose waiving exactly those. Drafted TOML must
  round-trip through `Exclude.load` (pinned by
  `EvidenceTest.draftsOnlyFullyUnprovenUnobservedSymbols`). A requested draft file is
  created upfront like --verdicts-json (`Evidence.createDraftPlaceholder`), so an
  errored run leaves a fresh placeholder, never a stale draft from an earlier
  run; quiet runs (no changes, empty plan) write the empty draft through the
  same `applyEvidenceAndDraft` call as checked runs.
- JFR recordings never reach this CLI: the build-tool plugins convert
  `jdk.ClassLoad` events into this parser's own trusted text shapes
  (`jvm-plugin-core/JfrEvidence`, invariants in the uika-jvm-plugins skill), so
  the CLI stays JFR-ignorant and needs no JDK module beyond `java.base`. A binary
  `.jfr` inside a log directory is skipped by name in the directory walk (recordings
  are large and guaranteed residents of the evidence directory, so byte-scanning them
  for newlines every run was pure waste); any other binary is still absorbed by the
  bounded reader, and an explicitly passed `.jfr` path is still read line by
  line. The converter-parser contract is pinned by
  `IntegrationTest.aJfrRecordingConvertedByThePluginConverterPromotesTheViolation`,
  which compiles the real JfrEvidence from its source — the one deliberate
  cross-component test dependency, like scenarios.tsv.

## SPI (ServiceLoader) provider breaks

- A class named in `META-INF/services/<iface>` that ServiceLoader could construct under
  old but not under new throws `ServiceConfigurationError` at load()/iteration time.
  Deliberately not part of "Linkage Semantics" above: SCE extends Error, not LinkageError —
  ServiceLoader resolves the provider reflectively and wraps the failure itself, so no
  constant-pool reference or class-load edge carries the break; the "reference" is a text
  line in a resource file. JVM-confirmed on the `synthetic-spi-*` fixture and the real
  kotest relocation-shim pair (tests/fixtures/README.md).
- `Check.addSpiViolations` is fed the service files read from the old/new library JARs
  in `Commands.runCheckWithIndexes` — always, unlike the reachability-gated consumer-side
  read (library JARs are small). Candidates are only providers BOTH sides list for the
  same service (`stillListedProviders`, deduplicated across files the way ServiceLoader
  dedups names, first registration wins): a dropped line is a deliberate library
  decision, not provably a break (the unreported "Arm B"); a newly added one has nothing
  to regress against.
- Instantiability is the CLASS-PATH rule: public, concrete, assignable to the service
  type, public no-arg constructor. A public static `provider()` factory is deliberately
  ignored: the JDK honors factories only for explicit-module providers resolved from
  `provides` directives (`ServiceLoader.loadProvider` gates on `inExplicitModule`), while
  `LazyClassPathLookupIterator` — the only reader of META-INF/services — and the
  automatic-module path derived from those files require the constructor unconditionally.
  A factory therefore neither rescues a lost constructor nor breaks a valid provider; an
  earlier version modeled factory precedence and had both directions wrong, pinned now by
  `CheckTest.aProviderFactoryDoesNotRescueALostConstructor` and
  `aStrayProviderFactoryOnAValidProviderIsNotReported`.
- Only a PROVEN old-side YES gates the check and only a PROVEN new-side NO is reported;
  `UNKNOWN` suppresses both ways, including a provider only the scan graph knows
  (still on the runtime classpath, member table unfetched — never "removed"). Resolution
  uses the same old/runtime scopes as every other check, so a provider moved to an
  unchanged classpath JAR is not a violation; `collectSpiWanted` fetches member tables
  for still-listed providers missing from either library index (the moved case, and a
  shadow copy surviving a library-side removal). The old side's assignability walk
  (`isAssignable`) is scope-first: the scan graph carries the NEW hierarchy whenever the
  upgraded jar is a scan target, and graph-first re-judged the old side by the new shape,
  hiding every dropped-interface break
  (`CheckTest.aDroppedInterfaceIsReportedWhenTheNewJarIsAScanTarget`).
- Violation shape: `sourceClass` = provider, `reference.owner` = service interface,
  `source` = the jar whose service file still names it — the sealed/kind-flip subject-first
  shape, rendered via `Report.structuralLines`. A scanned jar's own registrations are
  already reachability edges in `Reach`, so the provider is ranked for free; when no
  scan target registers it the mark is unobservable and `Check.checkScanned` leaves
  `reachable = null` rather than claiming proven-unreachable
  (`IntegrationTest.anUnscannedProviderIsNotProvenUnreachable`). In upgrade-check, a
  suggestion whose referencing coordinate IS the changed coordinate is skipped as
  self-referential (`Suggest`).
- Not in the verdicts stream (the root CLAUDE.md exclusion list names both reasons) and
  not golden-coverable: `Check.check`, the goldens' entry point, has no JAR paths to read
  META-INF/services from. Coverage is `detectsAProviderThatBecameAbstract` (synthetic,
  not instantiable), `detectsKotestStaleEngineRegistrationInTheRelocationShim`
  (real pair, removed) and `detectsSshdsStaleFileSystemProviderRegistration`
  (real pair whose service is a JDK class outside every scope: the provider names it as
  its direct superclass, so the walk proves the old side from the edge alone), all
  path-based through `Commands.runCheck` in `IntegrationTest`, plus `CheckTest`.

## Suggestions (upgrade-check only)

- `Suggest.annotate` fills `Violation.suggestion` after `runCheck`, in
  `Commands.cmdUpgradeCheck` where coordinates exist. Plain `check` has only file paths,
  so its violations stay `suggestion = null` and `Report` prints nothing extra.
- `Report` groups attributed violations suggestion-first (README shows the
  shape). The group key is the advice PLUS every field the "why:" line quotes
  (`removedBy`/`before`/`after`/`referencedBy`), never the advice alone: the
  removed-coordinate advice embeds no versions and the changed-coordinate
  advice embeds only the moved delta, so per-module runs over different
  resolved version lists can produce byte-identical advice whose versions
  differ. Violations with no suggestion (plain `check`, or unattributed
  upgrade-check leftovers) fall back to the per-symbol / per-class ❌ blocks.
  The text format itself is defined by `Report` plus its tests, not here.
- `referencedBy` comes from a dump `file-display-string -> "g:n:v"` map (both
  before and after sides). `removedBy` comes from mapping the violation's owner
  class to a changed coordinate by reading the before-side JARs' class names
  (`Input.load`, first-wins) — a small, best-effort scan that never blocks the
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
- `Pom` reads a POM already sitting beside the scanned JAR in the local
  artifact cache, so there is still no resolver and no network, and every
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
- The POM is read by a string scan, not an XML parser. The rules above came out of
  differential-testing every edit of the scan against the previous implementation over
  the whole local POM cache (8,740 POMs, 125,938 probes), and that is the check to
  repeat before changing it.

## Memory and speed rules

- Nothing proportional to the scan lives on the heap. Graph rows, reference records, intern
  strings and edges sit in chunked direct buffers (`IntArena`, `Intern`). The collector
  commits a multiple of the live set, so heap data costs its size twice in RSS.
- The scan path allocates nothing per class. Per-thread state is `Scratch`. Leaf output is
  packed ints in pooled arrays (`IntPool`), because a leaf outlives a young collection and
  nothing collects the old generation in a run this short.
- Path-level code must not keep `Scratch` state across a fork or join. A worker that waits
  may run another path's task on the same thread.
- `Intern` takes a shard lock per call. The per-thread lookup cache in `Scratch` exists
  because `java/lang/Object` would otherwise serialize every worker on one shard.
- Inflate is `Inflate.java`, not `java.util.zip`. The JDK bundles a plain C zlib and does
  not use the system one. `InflateTest` pins it against the JDK on every fixture entry plus
  fuzzed input. It needs `Inflate.SLACK` readable bytes after the stream.
- Partial inflate: `ClassSource` inflates on demand and `ClassParser.parseHeader` resumes.
  NestHost is at the end of the file, so an unread class carries
  `ClassGraph.NEST_HOST_UNREAD` and `Check.NestHosts` reads it lazily.
- The central directory is streamed once per jar (`Jar.readEntries`). Dedup, the scan and
  the next chunk's directory reads share one parallel region (`Scan.scanTargetPaths`).
  Provider files come out of the same pass when reachability is on.
- Loose class files go through `Input.DirectoryScan` lanes, 4 on macOS. Opening small files
  from 12 threads took 3.2s and 26s of system time there, against 1.7s and 4.5s on 4.
- `Launcher` re-runs the command in a child JVM with `-XX:+UseSerialGC -Xmn32m`, plus
  `-XX:TieredStopAtLevel=1` below about 800 scan targets. A caller that passes flags itself
  sets `-Duika.child=true`. Measure with that property set, or you measure two JVMs.

## Measured (M4 Pro, 12 cores, JDK 21, launcher flags)

The Rust column is the binary the jar replaced, measured in the same session on
2026-09-21.

| Workload | Java | Rust |
|---|---|---|
| Stress: 2,800 jars, 1.95M classes | 1.55s, 315MB | 1.96s, 525MB |
| Stress with `--app` (reachability on) | 1.75s, 400MB | 2.2s, 605MB |
| 164 class dirs (124K files) + 37 jars | 2.0s, 190MB | 2.2s, 230MB |
| 100 jars, 62K classes | 0.26s, 112MB | 0.16s, 145MB |
| 3 jars | 0.08s, 65MB | 0.02s, 33MB |

The last two rows are the JVM floor: about 25ms to reach `main`, then cold code. A warm JVM
runs the 100-jar check in about 0.15s. File-heavy rows move by 30% with the file cache, so
compare back-to-back runs only.

Bytecode target does not matter: `--release 21` measured the same as 17. The runtime does a
little (JDK 17 1.65s, 21 1.55s, 25 1.53s on the stress row). FFM is final only in 22, so it
is not available at this floor.
