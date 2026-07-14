# Benchmarks: uika vs prior art

Head-to-head numbers on real inputs, comparing uika against the tools listed
in the README's [Prior art](README.md#prior-art) section.

These runs are illustrative, not hermetic: timings depend on the machine, the
local package caches, and JVM warmup. Treat the orders of magnitude and the
qualitative differences (what each tool reports) as the takeaway, not the exact
milliseconds. Reproduction commands are at the bottom.

## Setup

- Machine: 10-core Apple Silicon Mac.
- Tool versions: uika (release build), japicmp 0.26.1, roseau 0.7.0 (built
  from source, requires JDK 25), Revapi standalone 0.12.1 (+ revapi-java 0.28.4
  and revapi-reporter-text 0.15.1), Linkage Checker 1.5.13.
- Peak RSS is the `maximum resident set size` from `/usr/bin/time -l`. JVM tools
  include JVM startup; uika is a native binary.
- Canonical case: kotlinx-coroutines-core-jvm 1.7.1 -> 1.11.0. Ktor 2.3.13's
  `BlockingAdapter` calls `EventLoopKt.processNextEventInCurrentThread()`, an
  internal (Kotlin `internal`, public in bytecode) method removed in 1.11.0.
  "Flags the real break" below means the tool surfaced that method.

## What each tool can be measured on

The API diff tools take only two versions of one library (`--old/--new`,
`--v1/--v2`); they have no input for a consumer classpath. Only uika and
Linkage Checker scan a resolved classpath, so the consumer-scale rows cover
just those two. This is structural, not a selection choice.

## Report output on the same case

What each tool actually prints for the coroutines 1.7.1 -> 1.11.0 change. The
line to look for is `EventLoopKt.processNextEventInCurrentThread`, the removed
internal method that Ktor calls.

uika `upgrade-check` (the CI mode, fed resolved dumps that carry coordinates)
resolves the reference, names the artifact on each end, and suggests a fix:

```text
dependency changes: 1
  CHANGED org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm 1.7.1 -> 1.11.0

VIOLATION in ktor-io-jvm-2.3.13.jar
  io/ktor/utils/io/jvm/javaio/BlockingAdapter
    -> method removed: kotlinx/coroutines/EventLoopKt.processNextEventInCurrentThread ()J
       referenced by: io.ktor:ktor-io-jvm:2.3.13
       removed by:    org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm 1.7.1 -> 1.11.0
       suggestion:    upgrade io.ktor:ktor-io-jvm:2.3.13 to a release built against
                      kotlinx-coroutines-core-jvm 1.11.0, or pin it to 1.7.1

scanned 8164 classes, 2 broken reference(s)
```

Plain `uika check`, given bare JAR paths instead of a resolved dump, prints the
same violation without the `referenced by` / `removed by` / `suggestion`
lines (it has file paths, not coordinates).

uika `diff` (library only) lists every bytecode-level removal, the real one
among them:

```text
METHOD REMOVED  kotlinx/coroutines/AbstractTimeSourceKt.getTimeSource ()Lkotlinx/coroutines/AbstractTimeSource;
METHOD REMOVED  kotlinx/coroutines/AwaitAll.access$getNotCompletedCount$FU$p ()Ljava/util/concurrent/atomic/AtomicIntegerFieldUpdater;
METHOD REMOVED  kotlinx/coroutines/EventLoopKt.processNextEventInCurrentThread ()J
... (218 entries)
```

japicmp groups removals under each modified class (bytecode level, finds it):

```text
***! MODIFIED CLASS: PUBLIC FINAL kotlinx.coroutines.AbstractTimeSourceKt
	---! REMOVED METHOD: PUBLIC(-) STATIC(-) FINAL(-) kotlinx.coroutines.AbstractTimeSource getTimeSource()
...
	---! REMOVED METHOD: PUBLIC(-) STATIC(-) FINAL(-) long processNextEventInCurrentThread()
... (~267 incompatible members)
```

roseau reports only its inferred public API surface, so the internal method is
absent (its 3 findings do not include it):

```text
Breaking Changes found: 3 (3 binary-breaking, 3 source-breaking)
✗ kotlinx.coroutines.JobKt.cancelFutureOnCompletion(...) EXECUTABLE_REMOVED
✗ kotlinx.coroutines.flow.FlowKt.asFlow(BroadcastChannel<T>) EXECUTABLE_REMOVED
✗ kotlinx.coroutines.flow.FlowKt.fixedPeriodTicker(...) EXECUTABLE_REMOVED
```

Revapi finds it, but the report is dominated by missing-class warnings when the
library's own dependencies are not supplied:

```text
java.missing.oldClass: Class 'kotlin.Pair' could not be found in the archives of the old API...
SOURCE: POTENTIALLY_BREAKING, BINARY: POTENTIALLY_BREAKING
...
old: method long kotlinx.coroutines.EventLoopKt::processNextEventInCurrentThread()
new: <none>
java.method.removed: Method was removed.
SOURCE: BREAKING, BINARY: BREAKING
... (18,051 lines total)
```

Linkage Checker (consumer scan) gives the most detailed root cause per error:

```text
(kotlinx-coroutines-core-jvm:1.11.0) kotlinx.coroutines.EventLoopKt's method
  "long processNextEventInCurrentThread()" is not found;
  referenced by 1 class file
    io.ktor.utils.io.jvm.javaio.BlockingAdapter (io.ktor:ktor-io-jvm:2.3.13)
  Cause:
    Dependency conflict: ...coroutines...1.11.0 does not define the method
    but ...coroutines...1.7.1 defines it.
      selected:   ...coroutines-core-jvm:1.11.0 (compile)
      unselected: ktor-io-jvm:2.3.13 (compile) / ...coroutines-core-jvm:1.7.1 (compile)
```

## 1. Library diff (coroutines 1.7.1 -> 1.11.0)

Comparing the two library versions and nothing else. Apples-to-apples across
all diff tools.

| Tool      | Time   | Peak RSS | Findings          | Flags the real break | Notes |
|-----------|--------|----------|-------------------|----------------------|-------|
| uika diff | 0.02s  | 23 MB    | 218 entries       | yes | native |
| japicmp   | 0.38s  | 254 MB   | ~267 incompatible (484-line report) | yes | needs `--ignore-missing-classes`, warns the result may be incomplete |
| roseau    | 0.42s  | 195 MB   | 3 breaking changes | **no** | Kotlin-aware API surface excludes the internal method |
| Revapi    | ~15s\* | 1077 MB  | 18,051 report lines | yes | very noisy without supplementary jars (`missing-class ... POTENTIALLY_BREAKING`) |

\* Revapi's wall time is dominated by a one-time extension download (~12s); the
analysis itself is a few seconds.

Reading the counts: japicmp, Revapi and uika work at the bytecode level, so they
report every JVM-public member, including Kotlin `internal` and synthetic ones.
roseau infers a stricter, Kotlin-aware public API surface, which is arguably
more correct for "did the public API break" but excludes exactly the
internal-but-linked method that crashes Ktor at runtime. That gap is uika's
niche.

## 2. Consumer-scale scan, small coherent classpath

The same 42 JARs (a coherent Ktor server + client tree, coroutines pinned to
1.11.0) given to each tool in its native input form: a resolved dump for uika
`upgrade-check`, a JAR list for Linkage Checker. This is the fair
apples-to-apples for the two classpath scanners.

| Tool               | Time       | Peak RSS  | Findings          | Flags the real break |
|--------------------|------------|-----------|-------------------|----------------------|
| uika upgrade-check | 0.03-0.04s | ~46 MB    | 2 broken          | yes |
| Linkage Checker    | ~1.5s      | ~1.2 GB   | 15 linkage errors | yes |

Both catch the real break. On a coherent, moderate classpath Linkage Checker is
perfectly healthy (~1.5s, most of it JVM startup). The difference is what they
report:

- uika reports the 2 references the upgrade newly broke: the removed
  `EventLoopKt.processNextEventInCurrentThread`, plus an access-narrowing on
  `FlowKt__TransformKt`.
- Linkage Checker reports 15 linkage errors for the whole snapshot. 14 of them
  are `org.conscrypt.*` / `org.bouncycastle.*`, optional TLS dependencies that
  are absent by design and never fail at runtime. They are unrelated to the
  upgrade and appear on every run. The one remaining error is the coroutines
  break.

So even on a clean tree, the snapshot tool mixes pre-existing dead-code noise
with the upgrade-caused break, while the upgrade-diff tool narrows to what the
bump changed. Gating a build on the snapshot means maintaining an exclusion
list for the noise.

## 3. Stress: large flat classpath (~2,400 JARs / ~2M classes)

A whole local package cache flattened into one classpath: many artifacts,
many conflicting versions. This is uika's design target and, deliberately, not
Linkage Checker's (it expects a coherent resolved tree).

| Tool            | Time    | Peak RSS  | Result |
|-----------------|---------|-----------|--------|
| uika check      | ~7s     | ~436 MB   | scanned ~2.05M classes, 1,163 broken references |
| Linkage Checker | >4.5min | ~7.5 GB   | did not complete (stopped near the 8 GB heap cap) |

Linkage Checker's exhaustive class-graph does not scale to an arbitrary
version-conflict pile, and it hard-fails up front if any single entry is
unreadable. uika streams the same input, skips non-scannable entries silently,
and finishes in seconds. This row measures behavior on pathological input, not
Linkage Checker's intended use.

## Takeaways

- uika is the only tool here that answers "which changes break the code on my
  classpath" rather than "what changed in this library" or "every linkage error
  in this snapshot". That scoping is why it reports 2 where Linkage Checker
  reports 15, and 1 where the diff tools report hundreds. In `upgrade-check`
  mode it also names the referencing and owning artifacts and suggests a fix
  (upgrade the referencer or pin the owner), which the diff tools cannot do
  because they never see the referencing side.
- Being native, uika's footprint is one to two orders of magnitude below the
  JVM tools (23-46 MB vs 195 MB-1.2 GB), and its startup is negligible, which
  matters for a per-PR CI gate.
- Each prior-art tool is strong in its lane: roseau is fast and precise about
  the true public API surface, japicmp adds semantic-versioning advice, Revapi
  has the widest set of checks, and Linkage Checker gives the most detailed
  root-cause for each linkage error on a coherent tree.

## Reproduction

```zsh
OLD=.../kotlinx-coroutines-core-jvm-1.7.1.jar
NEW=.../kotlinx-coroutines-core-jvm-1.11.0.jar

# 1. Library diff
uika diff "$OLD" "$NEW"
java -jar japicmp-0.26.1-jar-with-dependencies.jar --old "$OLD" --new "$NEW" \
     --only-incompatible --ignore-missing-classes
java -jar roseau.jar --diff --v1 "$OLD" --v2 "$NEW" --plain          # JDK 25
revapi.sh -e org.revapi:revapi-java:0.28.4,org.revapi:revapi-reporter-text:0.15.1 \
          -o "$OLD" -n "$NEW"

# 2. Small coherent classpath (resolve a Ktor server tree, coroutines pinned 1.11.0).
#    upgrade-check takes resolved dumps (from the build-tool plugins), which
#    carry coordinates, so it can print the referenced-by/removed-by lines and a
#    fix suggestion. Plain check takes bare JAR paths and finds the same breaks
#    without those lines.
uika upgrade-check --before before.json --after after.json
uika check --old "$OLD" --new "$NEW" --classpath "$TREE"
java -cp <linkage-checker-cp> com.google.cloud.tools.opensource.classpath.LinkageCheckerMain \
     -j "$TREE_COMMA"

# 3. Stress: a whole flattened package cache as the classpath
uika check --old "$OLD" --new "$NEW" --classpath "$BIG_CP"
```
