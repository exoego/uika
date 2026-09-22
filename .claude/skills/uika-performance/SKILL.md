---
name: uika-performance
description: Performance work on the uika CLI - benchmark workloads and expected numbers, the optimization history behind the current two-pass/interning/arena design, and approaches already measured and rejected. Load before profiling, benchmarking, or changing anything on the hot path (parsing, inflate, interning, arenas, span reads).
---

# uika Performance Notes

Benchmarks are not hermetic and depend on the local Gradle cache. Treat
detection-count shifts as semantic regressions first, performance second.

Measure the jar the way the plugins run it: the launcher flags passed by hand and
`-Duika.child=true`, or the number includes a second JVM boot and an idle parent.
`Launcher.flags` in cli-java holds the flags and the measurements behind each.
File-heavy workloads move by 30% with the file cache, so compare back-to-back runs
only, and run both sides of a comparison in one session.

```zsh
UIKA=(java -XX:+UseSerialGC -Xmn32m -XX:-UsePerfData -Xshare:auto -Duika.child=true -jar cli-java/build/libs/uika-cli-0.0.0-dev.jar)
```

Below about 800 scan targets the launcher adds `-XX:TieredStopAtLevel=1`, so add it
when measuring a small run.

## Benchmark Expectations

### Stress: all Gradle cache JARs

```zsh
JAR171=$(echo ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.7.1/*/kotlinx-coroutines-core-jvm-1.7.1.jar)
JAR1110=$(echo ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.11.0/*/kotlinx-coroutines-core-jvm-1.11.0.jar)
BIG_CP=$(find ~/.gradle/caches/modules-2/files-2.1 -name '*.jar' ! -name '*-sources*' ! -name '*-javadoc*' ! -name 'profiler_java_agent*' | tr '\n' ':' | sed 's/:$//')
/usr/bin/time -l $UIKA check --old "$JAR171" --new "$JAR1110" --classpath "$BIG_CP"
```

### Real project scale

```zsh
KTOR_ALL=$(find ~/.gradle/caches/modules-2/files-2.1/io.ktor -path '*2.3.13*' -name '*.jar' ! -name '*sources*' | tr '\n' ':' | sed 's/:$//')
APP_DIRS=("${(@f)$(find /path/to/large-jvm-project -type d -path '*build/classes')}")
ARGS=(); for d in $APP_DIRS; do ARGS+=(--app "$d"); done
/usr/bin/time -l $UIKA check --old "$JAR171" --new "$JAR1110" --classpath "$KTOR_ALL" "${ARGS[@]}"
```

Measured on a 12-core M4 Pro, JDK 21, launcher flags. The last column is the Rust
binary the jar replaced, measured in the same session, and is the baseline the
port was held to.

| Workload | Java | Rust |
|---|---|---|
| Stress: 2,800 jars, 1.95M classes | 1.55s, 315MB | 1.96s, 525MB |
| Stress with `--app` (reachability on) | 1.75s, 400MB | 2.2s, 605MB |
| 164 class dirs (124K files) + 37 jars | 2.0s, 190MB | 2.2s, 230MB |
| 100 jars, 62K classes | 0.26s, 112MB | 0.16s, 145MB |
| 3 jars | 0.08s, 65MB | 0.02s, 33MB |

The last two rows are the JVM floor: about 25ms to reach `main`, then cold code. A
warm JVM runs the 100-jar check in about 0.15s. The runtime matters a little (JDK 17
1.65s, 21 1.55s, 25 1.53s on the stress row); the bytecode target does not
(`--release 21` measured the same as 17).

Pass 1 dominates the stress workload. On a real classpath most scanned classes are
byte-identical duplicates bundled across JARs (about 60% on the stress workload), so
`Dedup` picks one entry per (name, CRC-32) from the central directories in path order
and the scan inflates only those. What remains is bounded by deflate decompression of
the survivors, and of those only the constant pool and header are inflated unless the
class references the checked library (`ClassSource`, `ClassParser.parseHeader`). The
reported scanned-class count still counts the skipped duplicates, so it stays the size
of the whole classpath. Absolute broken/unverified counts are classpath-order
sensitive (duplicate-class first-wins), so compare same-input diffs, not the table's
approximate counts.

Knobs: `UIKA_THREADS` (worker count), `UIKA_CHUNK` (paths processed concurrently in
pass 1, default 16x threads, rationale in `Scan.scanTargetPaths`), `UIKA_FILE_LANES`
(concurrent openers of loose class files, 4 on macOS, one per worker elsewhere),
`UIKA_NO_RELAUNCH` (run in the JVM that was started, for debugging).

Traps already hit in this repository:

- A profiler agent jar in the Gradle cache (`profiler_java_agent-latest.jar`) is
  corrupt and aborts the run; the `find` above excludes it.
- zsh does not split unquoted variables. Use arrays for repeated `--app` args and
  for the `$UIKA` command.
- zsh multios can duplicate stdout into a pipe with `cmd 2>&1 >/dev/null | grep`;
  send stderr to a file first when filtering it.
- A debug-shaped run (`gradle run`, `-cp build/classes` without the flags) is not
  a benchmark; the collector defaults alone move RSS by 80MB.

## Optimization History (Java)

The port started from the same two-pass, int-symbol, arena design the Rust crate had
arrived at (its history is below). What the Java side added, each measured on the
stress workload unless noted:

| Measured problem | Solution |
|---|---|
| Live data on the heap costs its size twice in RSS: the collector commits a multiple of the live set | Everything proportional to the scan lives in chunked direct buffers (`IntArena`, `Intern`). Heap-backed arenas were measured and rejected. |
| Per-class allocation on the scan path promoted garbage into the old generation, which nothing collects in a 2s run | Per-thread `Scratch`; leaf output as packed ints in pooled arrays (`IntPool`); no object per class. |
| `java/lang/Object` serialized every worker on one intern shard | Per-thread lookup cache in `Scratch` in front of the 64 shard locks. |
| A second central-directory parse per jar, and big directory buffers | The directory is streamed once (`Jar.readEntries`, 256 KiB window); dedup, the scan and the next chunk's directory reads share one parallel region (`Scan.scanTargetPaths`), with chunk k scanned while chunk k+1 is prepared. |
| Inflating whole class files when 0.3% reference the checked library, and the pool plus header is ~69% of a file | Partial inflate: `ClassSource` inflates on demand and `ClassParser.parseHeader` resumes; NestHost sits at the file end, so an unread class carries `ClassGraph.NEST_HOST_UNREAD` and `Check.NestHosts` reads it lazily. |
| The JDK's `Inflater` (a plain C zlib, not the system one) was ~12 of ~14 core-seconds | Hand-written decoder `Inflate.java`: 64-bit bit buffer, 10-bit litlen table, word copies. 450 against 350 MB/s. Pinned against the JDK on every fixture entry plus fuzzed input. Needs `Inflate.SLACK` readable bytes after the stream. |
| A `ZipFile` open per jar for `META-INF/services` when reachability is on, 715MB RSS with `--app` | Provider files are read out of the same central-directory stream. 400MB. |
| Opening 124K loose class files from 12 threads: 3.2s wall, 26s system time (macOS kernel contention on parallel `open`) | `Input.DirectoryScan` lanes, 4 on macOS: 1.7s wall, 4.5s system. A per-file semaphore was measured first and lost to park/unpark cost. |
| Large jars serialized on one worker | Spans of up to 2 MiB are parallel tasks of their own. |
| G1 sizes the young generation from the machine's RAM, touched but never needed | Launcher flags `-XX:+UseSerialGC -Xmn32m`: 2,800-jar run 380MB to 300MB, same speed. |
| Tiered compilation profiles every method before C2 compiles it, never paid back on a few jars | `-XX:TieredStopAtLevel=1` below ~800 targets: 3-jar check 0.17s to 0.08s, 100-jar 0.45s to 0.28s. C2 wins from roughly a thousand jars (1.6s against 1.9s at 2,800). |
| Boxed `Integer[]` sorts and per-node boxing in the graph walks | Primitive sorts with binary search; shared visitors and reusable seen-sets in `Check`. |

Rules that fall out of this are in `cli-java/AGENTS.md`. The one that is easiest to
break by accident: path-level code must not keep `Scratch` state across a fork or
join, because a worker that waits may run another path's task on the same thread.

## History from the Rust implementation

The crate the port replaced went from ~60s / 11GB to ~1.8s / 450MB on the stress
workload. The lessons transfer, since the Java port keeps the design:

| Measured problem | Solution |
|---|---|
| Duplicate classes kept full API values until index construction | Merge per chunk; discard later duplicates immediately. |
| Member tables for all consumer classes cost 100s of MB; resolution needed few | Two-pass scan: keep the class graph, fetch only wanted members. |
| Shared string handles caused contention and duplicate strings | Int symbols plus arena interning. |
| Per-class maps and boxes dominated at ~100K+ classes | Shared arenas, ranges, binary search. |
| General parsers structured every attribute | Skip attribute structure; scan only the needed Code bytes. |
| Read syscalls and buffer churn inflated system time | Group physical spans; one read per span. |
| Per-JAR sequential inflate underused the CPU | Inflate entries in parallel. |
| Interning every constant-pool owner just to reject it serialized pass 1 on the intern lock | Test owners by raw name against the old index's name set (`NameSet`); intern only the few matches. |
| ~60% of scanned classes are byte-identical duplicates, all inflated and parsed only to lose first-wins | One entry per (name, CRC) from central directories in path order; the scan inflates only those. Name-only dedup was rejected: a losing duplicate's distinct bytecode can carry invocation evidence, and CRC-keyed skipping is safe only because byte-identical copies carry identical evidence. |
| One barrier per pass-1 chunk parked workers where paths finished unevenly | `UIKA_CHUNK` default raised to 16x thread count: ~8% less wall, ~12% more peak RSS, output byte-identical across chunk sizes. No knee. A profile's parked-thread share overstated the reclaimable wall; trust the wall-clock diff. |

Rejected there, and still not worth retrying here: whole-file mmap (every touched
page stayed resident, `madvise` did not reduce file-backed RSS peaks, span reads won on
both speed and memory); full attribute parsing (CPU and temporary memory, the
replacement was validated by exact `dump` diffs); a single fallback window (seek
amplification, hence two LRU windows in the fallback reader).

## Measured and Rejected on the Semantics Side

These hold regardless of implementation, because they are about what the scan
records, not how.

Narrowing pass 1's owner filter from the old index's whole class set to the classes
whose entry differs between the compared sides plus their subtype closure. The premise
was that a JDK-pair check records a reference for every mention of `java/lang/String`,
so most of pass 1's records are for classes the upgrade never touched. Implemented and
validated: every violation stayed byte-identical across all ten goldens, `make probe`
still found no false-positive candidates, and unverified dropped because the escapes
it removed were provably harmless (guava-selenium 16 -> 0, all sixteen being
`ImmutableList.get/size` and `ImmutableSet.stream`, inherited from `java.util` through
a hierarchy identical in 22.0 and 23.0-rc1). Three reasons it was dropped anyway:

- The premise was wrong. Stress user time went down 2.3% and a 50-module JDK run 7.1%,
  because inflate of the surviving entries is the wall, exactly as the pass-1 paragraph
  above says. It does not touch the linear factor it was meant to fix.
- It costs the safety net that would catch it being wrong. The `--verdicts-json` stream
  for guava-selenium went from 528 records to 13, so `make probe` answer-checks 13
  references where it used to check 528. A filter whose soundness rests on a
  hand-argued case analysis should not also shrink the evidence for that analysis.
- `unverified` is documented as a completeness signal that a fuller `--classpath` or
  `--jdk-release` reduces, by ANSWERING more. This reduced it by ASKING less, so the
  number and the guidance around it stop lining up.

Revisiting it means arguing it as a detection-semantics change, with the README
definition and Known limitations rewritten, not as an optimization.

Invocation evidence for the latent tier (`Extract.invocationEvidence`,
https://github.com/exoego/uika/issues/81) adds a second constant-pool sweep per
scanned class. Measured at about 3% of user time on the stress workload, wall time and
RSS unchanged, detection byte-identical. Do not assume the sweep is rare: its probe
covers newly ADDED abstract methods, so it is non-empty on ordinary upgrades
(`Diff`'s `METHOD_BECAME_ABSTRACT` counts only concrete->abstract and is a much
narrower set; do not use it to reason about this cost). Collecting it inside pass 1's
existing pass is load-bearing: a separate pass that reparses each batch cost 21% more
user time, and a per-class record cost ~35MB RSS.

## Deliberate Costs

- Per-module JDK runs (`Commands.planJdkRuns`) scan each moved module's whole
  classpath, so a jar shared by N modules is inflated and parsed N times. Measured on a
  synthetic monorepo worst case, every module carrying the same classpath, 11 -> 17,
  no dependency changes so the numbers are the JDK runs alone (Rust binary, one
  session, the shape is what holds):

  | modules x jars | union, one run per pair | per module |
  |----------------|------------------------:|-----------:|
  | 1 x 300        |         0.27s / 149MB   | 0.29s / 151MB |
  | 10 x 300       |         0.27s / 141MB   |  1.6s / 262MB |
  | 25 x 300       |         0.29s / 149MB   |  4.7s / 336MB |
  | 50 x 300       |         0.29s / 170MB   | 7.7-9.8s / 348MB |
  | 10 x 1000      |         1.15s / 418MB   | 12.0s / 548MB |

  Do not compare these absolutes against a later session: a `find | head -300` over
  the Gradle cache picks a different 300 jars once anything downloads, which moved the
  same 1 x 300 point from 0.29s to 0.61s.

  The union is flat in module count and per module is linear, so a 50-module build on
  a 2000-jar classpath extrapolates to minutes. User time confirms the work is
  genuinely redundant rather than an overhead artifact: 3.96s at 1 module and 191s at
  50, a factor of 48. Accepted on purpose. A run is the unit the report counts and the
  `--fail-on` gate decides on, so only a module-shaped run gives a module its own
  scanned, broken and unverified numbers, and the cost lands only on a PR that moves a
  JDK release, never on a dependency upgrade. Real builds are cheaper than this table
  because their module classpaths are not identical.

  Where the remaining headroom is, measured rather than guessed:

  - NOT in cross-run parallelism. One run already reaches user/real 6.5x on 12 cores
    and 50 sequential runs reach 6.8x, so running runs concurrently is worth under 2x
    wall and multiplies peak RSS by the concurrency.
  - The JDK release indexes are already read once per distinct pair
    (`Commands.jdkReleasePair`), which would otherwise repeat per module.
  - Sharing the SCAN across runs would mean composing scan results per path, and
    `Dedup` plus the parse both resolve duplicate classes first-wins in path order
    WITHIN a run, so per-path pieces are not independent.
  - Narrowing pass 1's owner filter looked promising and was tried in full. Measured
    and rejected, above. Nothing else on this list is worth more than the linear factor
    itself.
