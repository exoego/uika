---
name: uika-performance
description: Performance work on the uika CLI - benchmark workloads and expected numbers, the optimization history behind the current two-pass/interning/arena design, and approaches already measured and rejected. Load before profiling, benchmarking, or changing anything on the hot path (parsing, inflate, interning, arenas, span reads).
---

# uika Performance Notes

Benchmarks are not hermetic and depend on the local Gradle cache. Treat
detection-count shifts as semantic regressions first, performance second.

`[profile.release]` in the workspace `Cargo.toml` is tuned for published size,
not for speed: `opt-level = "s"` costs about 7% throughput on the stress
workload in exchange for 38% smaller published bytes, because the Maven Central
quota is shared across the whole `net.exoego` namespace. See PUBLISHING.md. The
numbers below are quoted against that profile, so they are not comparable to
anything measured before it landed. When bisecting a suspected regression,
confirm the profile is the same on both sides before blaming a code change.

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
| Stress: ~2,334 JARs / 1.94M classes                   |   ~311 broken / 294 unverified | ~1.8s | ~450MB |
| Real project: ~50 modules / 48.5K classes + 38 JARs   |   ~1 broken / 347 unverified | ~0.9s | ~110MB |

Pass 1 dominates the stress workload. On a real classpath most scanned classes
are byte-identical duplicates bundled across JARs (about 60% on the stress
workload), so `input::representative_offsets` reads central directories only (no
inflate) and skips inflating any (entry name, CRC-32) already seen at an earlier
path. What remains is bounded by deflate decompression of the survivors. The
reported scanned-class count still counts the skipped duplicates, so it stays the
size of the whole classpath. Absolute broken/unverified counts are
classpath-order sensitive (duplicate-class first-wins), so compare same-input
diffs, not the table's approximate counts.

Traps already hit in this repository:

- Always benchmark release builds; never with `--features memstats`.
- zsh does not split unquoted variables — use arrays for repeated `--app` args.
- zsh multios can duplicate stdout into a pipe with `cmd 2>&1 >/dev/null |
  grep`; send stderr to a file first when filtering it.

## Optimization History

~60s / 11GB -> ~1.8s / 450MB on the stress workload. Causal changes:

| Measured problem                                                              | Solution                                                          |
|-------------------------------------------------------------------------------|--------------------------------------------------------------------|
| Duplicate classes kept full `ClassApi` values until index construction       | Merge per chunk; discard later duplicates immediately.            |
| Member tables for all consumer classes cost 100s of MB; resolution needed few | Two-pass scan: keep `ClassGraph`, fetch only wanted members.      |
| `Arc<str>` cloning caused atomic contention and duplicate strings            | `Sym = u32` + bump-arena interning.                               |
| Per-class `HashMap`/`Box` overhead dominated at ~100K+ classes               | Shared arenas + ranges + binary search.                           |
| General parsers structured every attribute                                   | `RawClass` skips attribute structure; scans only needed Code bytes. |
| Read syscalls and buffer churn inflated system time                          | Group physical spans; one `pread` per span.                       |
| Per-JAR sequential inflate underused the CPU                                 | Inflate entries in parallel.                                      |
| miniz_oxide inflate (per-entry Huffman-tree init) dominated pass 1           | flate2 `zlib-rs` backend (pure Rust, keeps the no-C static build). |
| Interning every constant-pool owner just to reject it serialized pass 1 on the intern shard mutex (most wall-clock `sys` time) | `extract_refs` tests owners by raw name against `ApiIndex::class_name_set` (old); intern only the few matches. |
| SipHash shard selection + redundant `from_utf8` validation of ASCII names    | FxHash for intern shard choice; `from_utf8_unchecked` for ASCII (is_ascii proves soundness). |
| A duplicate class already in the graph still had its references extracted before merge dropped them | Return early in `parse_targets` for a class the chunk-immutable graph already holds. |
| ~60% of scanned classes are byte-identical duplicates bundled across JARs, all inflated and parsed only to lose first-wins | `representative_offsets` picks one entry per (name, CRC) from central directories in path order; the scan inflates only those. |
| Pass-1 chunking (`UIKA_CHUNK`, one rayon barrier per chunk) defaulted to 1x thread count, parking workers at every boundary where paths finished unevenly | Default raised to 16x thread count (`check.rs::scan_target_paths`): ~8% less wall (sys 2.4s -> 1.6s), ~+12% peak RSS, output byte-identical across chunk sizes. No knee — 8x captures most, 32x still improves. A `sample` profile's parked-thread share suggested ~half the wall was reclaimable; trust the wall-clock diff, not the parking share. Rejected alongside: name-only dedup in `representative_offsets` (vs (name, CRC)) would drop invocation evidence a losing duplicate's distinct bytecode carries — CRC-keyed skipping is safe only because byte-identical copies carry identical evidence. |

Not helpful, measured and rejected: `lto`/`codegen-units=1` (inflate is the wall
and lives in a self-contained crate, so cross-crate inlining gained nothing while
tripling release build time).

Invocation evidence for the latent tier (`extract_invocation_evidence`, issue
81) adds a second constant-pool sweep per scanned class. Measured on the stress
workload against the same classpath: user time 8.86s -> 9.15s (+3.2%), wall
time and RSS unchanged, detection byte-identical. Do not assume the sweep is
rare — its probe covers newly ADDED abstract methods, so it is non-empty on
ordinary upgrades (`diff.rs`'s `MethodBecameAbstract` counts only
concrete->abstract and is a much narrower set; do not use it to reason about
this cost). Two alternatives were measured and rejected: a per-class `Vec` on
`ParsedTarget` (extra time and RSS, and it violates the per-class structure
rule), and a separate rayon pass that reparses each batch (+21% user time,
since reparsing costs far more than the sweep it avoids). `#[inline(never)]`
on the sweep changed nothing.

A Java port with the same two-pass/int-intern/span-read architecture matched
Rust on CPU time (the `experiments/` comparison, since removed). Rust's real
advantages here: memory footprint, startup time for short CLI runs, and static
binary distribution.

## Deliberate Costs

- Per-module JDK runs (`plan_jdk_runs`) scan each moved module's whole classpath, so a
  jar shared by N modules is inflated and parsed N times. Measured on a synthetic
  monorepo worst case, every module carrying the same classpath, 11 -> 17, no dependency
  changes so the numbers are the JDK runs alone:

  | modules x jars | union, one run per pair | per module |
  |----------------|------------------------:|-----------:|
  | 1 x 300        |         0.27s / 149MB   | 0.29s / 151MB |
  | 10 x 300       |         0.27s / 141MB   |  1.6s / 262MB |
  | 25 x 300       |         0.29s / 149MB   |  4.7s / 336MB |
  | 50 x 300       |         0.29s / 170MB   | 7.7-9.8s / 348MB |
  | 10 x 1000      |         1.15s / 418MB   | 12.0s / 548MB |

  One session, both binaries back to back on the same dumps. Do not compare these
  absolutes against a later session: a `find | head -300` over the Gradle cache picks a
  different 300 jars once anything downloads, which moved the same 1 x 300 point from
  0.29s to 0.61s. The ratios and the shape are what hold.

  The union is flat in module count and per module is linear, so a 50-module build on a
  2000-jar classpath extrapolates to minutes. User time confirms the work is genuinely
  redundant rather than an overhead artifact: 3.96s at 1 module and 191s at 50, a factor
  of 48. Accepted on purpose. A run is the unit the report counts and the `--fail-on`
  gate decides on, so only a module-shaped run gives a module its own scanned, broken
  and unverified numbers, and the cost lands only on a PR that moves a JDK release,
  never on a dependency upgrade. Real builds are cheaper than this table because their
  module classpaths are not identical.

  Where the remaining headroom is, measured rather than guessed:

  - NOT in cross-run parallelism. One run already reaches user/real 6.5x on 12 cores and
    50 sequential runs reach 6.8x, so running runs concurrently is worth under 2x wall
    and multiplies peak RSS by the concurrency.
  - `cached_jdk_pair` already removes the other half, reading a release out of ct.sym,
    which would otherwise repeat per module.
  - Sharing the SCAN across runs would mean composing `ScanResult` arenas per path, and
    `representative_offsets` plus `parse_targets` both resolve duplicate classes
    first-wins in path order WITHIN a run, so per-path pieces are not independent.
  - Unmeasured and the most promising: pass 1's owner filter is `old.class_name_set()`,
    i.e. for a JDK run the ENTIRE old JDK API, so every reference to `java.lang.String`
    becomes a reference record. Narrowing it to the classes whose API actually differs
    between the two releases would shrink every JDK run, union or per module. It changes
    what pass 1 records, so it needs golden validation and a check that hierarchy-escape
    Unknown counts do not move.

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
