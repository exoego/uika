# Runtime load evidence (JFR)

The ⚠️ tier means "no static path found", and its blind spot is reflection. A
JVM can close that gap: run the **current, not yet upgraded** build — its test
suite, or a staging/production soak — with JFR recording every class load:

```console
-XX:StartFlightRecording:jdk.ClassLoad#enabled=true,jdk.ClassLoad#stackTrace=true,filename=<dir>
```

(JDK 17+ syntax; Gradle and sbt inject exactly this into test JVMs from one
option, Mill through its test-module mixin, Bazel prints it ready to paste, and
Maven, Leiningen and the Clojure CLI take it by hand — each page carries the
recipe). Quote the `filename` value when the directory path contains a comma:
the comma is the option delimiter, and an unquoted one silently truncates
`filename=` with exit 0, leaving the directory empty. The injecting tools quote
it for you. JFR generates pid-unique file names for a directory-valued
`filename`, so parallel test JVMs never collide, and the recorded stacks are
what uika turns into the `via ...` trigger on every promoted violation. Teams
already running continuous production JFR only need the `jdk.ClassLoad` event
enabled — the recording they already collect then IS the evidence, no extra
flags.

The plugins take the option as a directory or a single `.jfr` file. A file — a
production recording, say — is consumption-only: the check converts it, test
JVMs are left untouched. The injected flag needs JDK 17+ test JVMs (the
event-settings syntax), so leave the option off for an older test leg.

## What a promoted violation looks like

The plugins convert recordings with the JDK's own JFR reader before invoking
the CLI, which stays JVM-free and never reads binary recordings. A
[⚠️ violation](../README.md#violation-tiers-and-the-failon-threshold) whose
referencing class appears in the evidence is promoted out of the tier and
marked, trigger included: `⚡ observed loading at runtime (via
java.lang.Class.forName from com.example.PluginRegistry.discover(...))` — the
reflective edge the static walk could not see, documented for free.
`failOn = reachable` then fails on it. A 💤 latent violation stays latent,
because loading proves the class reachable, not that anything invokes the
affected member.

Ingestion is promote-only, the same stance reachability takes: absence of a
load entry proves nothing beyond the observed runs (a different code path, a
run that never got there), so no violation is ever demoted or dropped because
of it.

An evidence path that does not exist is skipped with a warning rather than
failing the run. Evidence is data another job produces, so its absence is an
operational state, and the option can stay in a build that also runs on a
laptop or a fork PR. Nothing is promoted from a path that is not there, which
is what the warning says.

## Collecting on the base branch, consuming on the PR

Evidence rides the same artifact flow as the
[cached baseline](gradle.md#caching-the-baseline): the base branch runs its
test suite with class-load recording on and uploads the recordings, and the
[PR job](gradle.md#pr-gate-on-github-actions) downloads them by `base.sha` and
adds one flag. For Gradle, next to the baseline dump:

```yaml
      - run: ./gradlew test -PuikaJfr=/tmp/uika-jfr
      - uses: actions/upload-artifact@v7
        with:
          name: uika-jfr-${{ github.sha }}
          path: /tmp/uika-jfr
```

and in the PR job, after downloading the artifact into `/tmp/uika-jfr`:

```yaml
      - run: ./gradlew uikaUpgradeCheck -PuikaBefore=/tmp/before.json -PuikaAfter=/tmp/after.json -PuikaJfr=/tmp/uika-jfr
```

Each tool page carries the same two steps in its own spelling.

## JFR caveats

All bounded: the event is disabled in the default JFC profile (the flag above
enables it — the plugin prints each conversion's event count, and
`0 jdk.ClassLoad events` means a recording made without it); a recording
rotates away its oldest chunks past `maxsize` (250MB by default when
`filename` is set — far above what class-load events reach); a SIGKILLed JVM
never writes its final dump, so a crashed test fork contributes no evidence
(promote-only makes that safe); and stack capture keeps the innermost 64 frames
(`-XX:FlightRecorderOptions:stackdepth=` to raise), which truncates the harness
side uika never reads — the trigger sits at the inner end.

## Bring-your-own text logs

Text evidence is read too, mixed freely with recordings in one directory and
parsed leniently: unified-logging `[class,load]` lines with any decorators
(`-Xlog:class+load` output; other `-Xlog` streams sharing the file are
skipped), plain class-name lists dotted or slashed (`-XX:DumpLoadedClassList`
classlists), and `class+load+cause` stack blocks — the JDK 22+ flags
(https://bugs.openjdk.org/browse/JDK-8193513,
`-Xlog:class+load+cause=info -XX:LogClassLoadingCauseFor=<substring>`) remain
the right tool for a *targeted* production look at one class, and uika reads
their output too.

## Drafting an exclude file

The deliberate consumer of the opposite signal. After soaking the evidence,
symbols whose every violation is still ⚠️ *and* was never observed loading can
be drafted into [exclude rules](../README.md#excluding-known-false-positives).
Every drafted reason opens with `REVIEW:` and records exactly what the evidence
shows (which classes, which logs); a symbol that also breaks a reachable or
observed class is never drafted, because the rule would waive that real break
too. The draft is input for a human: review each entry and delete what you
cannot justify before committing the file. Drafting is refused when the
evidence names no class at all, because an artifact that never downloaded or a
test JVM that never forked would otherwise draft every unproven violation with
a reason indistinguishable from a well-evidenced run. Drafting into a file that
is also an exclude file is refused for the same reason it looks tempting: it
would rewrite that file with only the drafted rules.
