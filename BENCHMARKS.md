# Benchmarks: uika vs prior art

Head-to-head numbers on real inputs, comparing uika against the tools listed
in the README's [Prior art](README.md#prior-art) section.

These runs are illustrative, not hermetic: timings depend on the machine, the
local package caches, and JVM warmup. Treat the orders of magnitude and the
qualitative differences (what each tool reports) as the takeaway, not the exact
milliseconds. Reproduction commands are at the bottom.

## Setup

- Machine: Apple M4 Pro (12 cores), macOS.
- Every number here was measured in one session on 2026-09-22, each command run
  three times back to back, keeping the best wall time and the largest peak RSS.
- Tool versions: uika (the pure-Java jar built from `main` at fbc60d9), japicmp
  0.26.1, roseau 0.7.0 (built from source, requires JDK 25), Revapi standalone
  0.12.1 (+ revapi-java 0.28.4 and revapi-reporter-text 0.15.1), Linkage Checker
  1.5.13. All ran on Temurin 21.0.11 except roseau on Temurin 25.0.3.
- Peak RSS is the `maximum resident set size` from `/usr/bin/time -l`. Every
  tool is a JVM tool, so every number includes JVM startup. uika ran as
  `java -jar uika-cli.jar`, the shipped form, in which the launcher re-runs the
  command in a child JVM with its own flags. Its numbers cover both JVMs.
  `cli-java/AGENTS.md` keeps the last side-by-side against the native binary
  this table measured before the Java port.
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
dependency changes: 5
    CHANGED org.jetbrains.kotlin:kotlin-stdlib 1.8.22 -> 2.2.20
    REMOVED org.jetbrains.kotlin:kotlin-stdlib-common 1.8.22 -> -
    CHANGED org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm 1.7.1 -> 1.11.0
    CHANGED org.jetbrains.kotlinx:kotlinx-coroutines-jdk8 1.7.1 -> 1.11.0
    CHANGED org.jetbrains.kotlinx:kotlinx-coroutines-slf4j 1.7.1 -> 1.11.0

per-module check: 1 of 1 modules changed their resolved versions (0 unchanged)
    :app  scanned 9013 classes, ❌ 1 broken, 0 unverified

💡 suggestion: upgrade io.ktor:ktor-io-jvm:2.3.13 to a release built against org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm 1.11.0, or pin org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm to 1.7.1
    affected modules: :app
    why: org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm changed 1.7.1 -> 1.11.0, which breaks io.ktor:ktor-io-jvm:2.3.13:
        kotlinx.coroutines.EventLoopKt.processNextEventInCurrentThread() was removed, but io.ktor.utils.io.jvm.javaio.BlockingAdapter still calls it

scanned 9013 classes: ❌ 1 broken
```

Plain `uika check`, given bare JAR paths instead of a resolved dump, groups the
same violation under the broken symbol with the runtime error it causes
(`method removed, throws NoSuchMethodError at first call`), without the 💡 fix
block (it has file paths, not coordinates).

uika `diff` (library only) lists every bytecode-level removal, the real one
among them:

```text
METHOD REMOVED         kotlinx/coroutines/AbstractTimeSourceKt.getTimeSource ()Lkotlinx/coroutines/AbstractTimeSource;
METHOD REMOVED         kotlinx/coroutines/AwaitAll.access$getNotCompletedCount$FU$p ()Ljava/util/concurrent/atomic/AtomicIntegerFieldUpdater;
METHOD REMOVED         kotlinx/coroutines/EventLoopKt.processNextEventInCurrentThread ()J
... (230 entries)
```

japicmp groups removals under each modified class (bytecode level, finds it):

```text
***! MODIFIED CLASS: PUBLIC FINAL kotlinx.coroutines.AbstractTimeSourceKt
	---! REMOVED METHOD: PUBLIC(-) STATIC(-) FINAL(-) kotlinx.coroutines.AbstractTimeSource getTimeSource()
...
	---! REMOVED METHOD: PUBLIC(-) STATIC(-) FINAL(-) long processNextEventInCurrentThread()
... (184 removed members in 62 modified classes)
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

Linkage Checker (consumer scan) names the referencing class file per error.
Fed a jar list it has no dependency graph, so it cannot say which version
conflict caused the error, which it does when given Maven coordinates:

```text
(.../kotlinx-coroutines-core-jvm-1.11.0.jar) kotlinx.coroutines.EventLoopKt's method "long processNextEventInCurrentThread()" is not found;
  referenced by 1 class file
    io.ktor.utils.io.jvm.javaio.BlockingAdapter (.../ktor-io-jvm-2.3.13.jar)
```

## 1. Library diff (coroutines 1.7.1 -> 1.11.0)

Comparing the two library versions and nothing else. Apples-to-apples across
all diff tools.

| Tool      | Time   | Peak RSS | Findings          | Flags the real break | Notes |
|-----------|--------|----------|-------------------|----------------------|-------|
| uika diff | 0.09s  | 58 MB    | 230 entries       | yes | two JVMs, see Setup |
| japicmp   | 0.29s  | 203 MB   | 184 removed members in 62 classes (484-line report) | yes | needs `--ignore-missing-classes`, warns the result may be incomplete |
| roseau    | 0.39s  | 182 MB   | 3 breaking changes | **no** | Kotlin-aware API surface excludes the internal method |
| Revapi    | 3.1s\* | 846 MB   | 18,051 report lines | yes | very noisy without supplementary jars (`missing-class ... POTENTIALLY_BREAKING`) |

\* Steady state. Revapi's first run downloads its extensions, which took 23s
here; the analysis itself is about 3s after that.

Reading the counts: japicmp, Revapi and uika work at the bytecode level, so they
report every JVM-public member, including Kotlin `internal` and synthetic ones.
roseau infers a stricter, Kotlin-aware public API surface, which is arguably
more correct for "did the public API break" but excludes exactly the
internal-but-linked method that crashes Ktor at runtime. That gap is uika's
niche.

## 2. Consumer-scale scan, small coherent classpath

The same 40 JARs (a Ktor 2.3.13 server + client tree resolved by Gradle,
coroutines forced to 1.11.0) given to each tool in its native input form: a
resolved dump for uika `upgrade-check`, a JAR list for Linkage Checker. This is
the fair apples-to-apples for the two classpath scanners. uika ran with
`--jdk-release 20`, which the build-tool plugins pass on their own. Without it
the same run takes 0.19s and reports the same break plus 131 unverified
references whose hierarchy escapes into the JDK.

| Tool               | Time  | Peak RSS | Findings          | Flags the real break |
|--------------------|-------|----------|-------------------|----------------------|
| uika upgrade-check | 0.21s | 110 MB   | 1 broken          | yes |
| Linkage Checker    | 1.5s  | ~1.2 GB  | 15 linkage errors | yes |

Both catch the real break. On a coherent, moderate classpath Linkage Checker is
perfectly healthy (1.5s, most of it JVM startup). The difference is what they
report:

- uika reports the one reference the upgrade newly broke: the removed
  `EventLoopKt.processNextEventInCurrentThread`.
- Linkage Checker reports 15 linkage errors for the whole snapshot. 14 of them
  are `org.conscrypt.*` / `org.bouncycastle.*`, optional TLS dependencies that
  are absent by design and never fail at runtime. They are unrelated to the
  upgrade and appear on every run. The one remaining error is the coroutines
  break.

So even on a clean tree, the snapshot tool mixes pre-existing dead-code noise
with the upgrade-caused break, while the upgrade-diff tool narrows to what the
bump changed. Gating a build on the snapshot means maintaining an exclusion
list for noise that never goes away. uika's own
[`--exclude-file`](README.md#excluding-known-false-positives)
is narrower in scope: it suppresses a specific, investigated reference inside
a break the upgrade actually introduced (e.g. a reflection-only field access),
not permanent unrelated noise like the conscrypt/bouncycastle case above.

## 3. Stress: large flat classpath (2,801 JARs / 1.95M classes)

A whole local package cache flattened into one classpath: many artifacts,
many conflicting versions. This is uika's design target and, deliberately, not
Linkage Checker's (it expects a coherent resolved tree).

| Tool            | Time   | Peak RSS | Result |
|-----------------|--------|----------|--------|
| uika check      | 1.6s   | 347 MB   | scanned 1,951,880 classes, 140 broken references |
| Linkage Checker | 6.6min | 8.4 GB   | 14,046 linkage errors, heap capped at 8 GB |

Linkage Checker's exhaustive class graph pays minutes and gigabytes on an
arbitrary version-conflict pile, and it refuses the whole list up front when a
single entry is unreadable (this cache holds a directory named `*.jar`, which
had to be filtered out first). uika streams the same input, skips non-scannable
entries silently, and finishes in seconds. This row measures behavior on
pathological input, not Linkage Checker's intended use.

## 4. Class-shape breaks: interface↔class flip and InstantiationError

Two real fixtures exercise the class-shape breaks uika reports, cross-checked
against the tools that can see them:

- **interface→class flip**: `io.ktor.utils.io.ByteChannel` is an interface in
  ktor-io 2.3.13 and a `final class` in 3.1.0; ktor-network 2.3.13 calls it
  through `InterfaceMethodref` constants (`IncompatibleClassChangeError`).
- **`new` on now-abstract**: `ValueClassBoxConverter` is a concrete `final class`
  in jackson-module-kotlin 2.18.2 and `abstract` in 2.20.1; the module's own
  `ReflectionCache` does `new ValueClassBoxConverter(...)` (`InstantiationError`).

| Break | uika `check` | japicmp (library diff) | Linkage Checker (classpath) |
|-------|--------------|------------------------|-----------------------------|
| interface→class flip | `interface became class`, 17 refs | `MODIFIED CLASS (<- INTERFACE)` | per-method `has changed incompatibly` — caught |
| `new` on now-abstract | `class became abstract`, 3 refs | `ABSTRACT (<- NON_ABSTRACT)` + `REMOVED CONSTRUCTOR` | **not reported** |

The library-diff tools flag both as binary-incompatible changes — expected, since
they diff the library and never see a consumer (Revapi classifies them the same
via `java.class.kindChanged`/`nowAbstract`; not re-run). Linkage Checker, the one
other classpath scanner, catches the flip, including the pure flip
`ReaderJob.getChannel()` where the method still resolves and only the owner kind
changed. But it misses the `new`-on-abstract break: it models class/method/field
references, never constructors (`<init>`) or instantiation, so it stays silent on
the `new` site even though it scans the referencing class. Among the classpath
scanners that break is uika-only.

The `InstantiationError` exists only across an upgrade (a coherent classpath never
holds the old referencer and the new abstract class at once), so feeding it to a
snapshot tool needed a hand-built hybrid jar — and even then Linkage Checker did
not report it. No uika false negative surfaced.

## Takeaways

- uika is the only tool here that answers "which changes break the code on my
  classpath" rather than "what changed in this library" or "every linkage error
  in this snapshot". That scoping is why it reports 1 where Linkage Checker
  reports 15 and the diff tools hundreds. In `upgrade-check`
  mode it also names the referencing and owning artifacts and suggests a fix
  (upgrade the referencer or pin the owner), which the diff tools cannot do
  because they never see the referencing side.
- uika's footprint is a third to a twelfth of the other JVM tools' on the same
  input (58-102 MB against 182 MB-1.2 GB), and the two JVM starts it pays are
  inside the 0.1-0.2s its small rows take.
- Each prior-art tool is strong in its lane: roseau is fast and precise about
  the true public API surface, japicmp adds semantic-versioning advice, Revapi
  has the widest set of checks, and Linkage Checker names every referencing
  class on a coherent tree, with the version conflict behind it when it is
  given Maven coordinates rather than jars.
- On the class-shape breaks (section 4), the library-diff tools flag the change
  and Linkage Checker catches the interface↔class flip, but the `new`-on-abstract
  `InstantiationError` is caught by uika alone among the classpath scanners:
  Linkage Checker models class/method/field references, not constructors or
  instantiation.

## Reproduction

```zsh
OLD=.../kotlinx-coroutines-core-jvm-1.7.1.jar
NEW=.../kotlinx-coroutines-core-jvm-1.11.0.jar
alias uika='java -jar uika-cli.jar'

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
#    --jdk-release is what the plugins pass by default, derived from the build.
uika upgrade-check --before before.json --after after.json --jdk-release 20
uika check --old "$OLD" --new "$NEW" --classpath "$TREE"
java -cp <linkage-checker-cp> com.google.cloud.tools.opensource.classpath.LinkageCheckerMain \
     -j "$TREE_COMMA"

# 3. Stress: a whole flattened package cache as the classpath (files only:
#    Linkage Checker refuses the list if a directory named *.jar is on it)
BIG_CP=$(find ~/.gradle/caches/modules-2/files-2.1 -type f -name '*.jar' \
         ! -name '*-sources*' ! -name '*-javadoc*' | tr '\n' ':' | sed 's/:$//')
uika check --old "$OLD" --new "$NEW" --classpath "$BIG_CP"
java -Xmx8g -cp <linkage-checker-cp> com.google.cloud.tools.opensource.classpath.LinkageCheckerMain \
     -j "${BIG_CP//:/,}"

# 4. Class-shape breaks (fixtures in cli-java/tests/fixtures). interface->class flip:
uika check --old ktor-io-jvm-2.3.13.jar --new ktor-io-jvm-3.1.0.jar \
     --classpath ktor-network-jvm-2.3.13.jar
#    new-on-abstract (consumer = the old jar's own ReflectionCache; copy it since
#    a jar equal to --old is skipped as stale):
cp jackson-module-kotlin-2.18.2.jar jackson-consumer-2.18.2.jar
uika check --old jackson-module-kotlin-2.18.2.jar --new jackson-module-kotlin-2.20.1.jar \
     --classpath jackson-consumer-2.18.2.jar:kotlin-stdlib-2.2.20.jar
```
