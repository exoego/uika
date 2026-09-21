# Notes for Agents: cli-java/

The pure-Java twin of `cli/`. One jar instead of a binary per OS and CPU. The Rust crate is
still the specification: `cli/AGENTS.md` holds the check pipeline and linkage semantics, and
they apply here unchanged. This file holds what differs on the JVM.

## Parity

- stdout, JSON, the verdicts stream and exit codes are byte-identical to the Rust binary.
  `make java-cli-difftest MODE=dump|diff|check` runs both over the local Gradle cache. Any
  difference is a bug in the port until proven otherwise.
- `GoldenTest` reads the same `cli/tests/golden/*.json` the Rust crate blesses. Tests run
  with `cli/` as the working directory, because a violation's `source` is the path string
  as given and the goldens pin it.
- One known deviation. Pass 1 stops reading a class once its constant pool names no class of
  the checked library, so a class whose MEMBER section is malformed is kept where Rust warns
  and drops it. Output only differs for a corrupt class file.
- Symbols order by UTF-8 bytes (`Intern.compare`, `Text.compareUtf8`). `String.compareTo`
  orders by UTF-16 unit and disagrees above the surrogate range.

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
