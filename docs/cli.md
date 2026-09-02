# CLI

The `uika` binary is the engine every
[build-tool integration](build-tools.md) runs. The plugins
fetch it, derive its arguments from the build, and print what it reports, so a
project with a supported build tool never has to invoke it by hand.

Use it directly when there is no plugin for your build, when you want to check
a hand-assembled classpath with `check`, or when you are reproducing what a
plugin ran. A plugin knows each module's resolved classpath, the release it
compiles for, and where its build outputs are. A command line knows none of the
three until you pass it.

## Getting the binary

Native binaries are attached to every [GitHub
release](https://github.com/exoego/uika/releases) and published to Maven
Central as `net.exoego.uika:uika-cli:<version>:<platform>@zip`, with the
classifiers `linux-x86_64`, `macos-aarch64`, `macos-x86_64` and
`windows-x86_64`. Unzip one and put it on the path. Nothing else is needed,
because the CLI runs no JVM.

## Commands

`uika <command> --help` lists every flag. The recipes below cover the ones
worth explaining.

### `uika diff`

Lists breaking changes between two versions of one library. Each line opens
with the change kind, which is also what `--json` puts in its `kind` field, so
`jq '.breaking_changes[] | select(.kind == "class_became_final")'` selects
those lines. The same snake_case kinds are what
[`--exclude-file`](../README.md#excluding-known-false-positives)
rules match on.

```console
$ uika diff guava-22.0.jar guava-23.0-rc1.jar [--json]
CLASS BECAME FINAL     com/google/common/collect/BoundType
FIELD REMOVED          com/google/common/graph/GraphConstants.EDGE_CONNECTING_NOT_IN_GRAPH Ljava/lang/String;
METHOD ACCESS NARROWED com/google/common/collect/Iterators$ConcatenatedIterator.<init> (Ljava/util/Iterator;)V (public -> package-private)

breaking changes: 93 (classes: 26, methods: 61, fields: 6)
```

### `uika check`

Finds uses of those breaking changes across classpath JARs and your build
output. This is the command no build-tool integration exposes, because it takes
the compared pair and the classpath as arguments rather than reading them from
a resolved build.

```console
$ uika check --old kotlinx-coroutines-core-jvm-1.7.1.jar \
             --new kotlinx-coroutines-core-jvm-1.11.0.jar \
             --classpath ktor-io-jvm-2.3.13.jar:other-dep.jar \
             --app build/classes/kotlin/main
checked kotlinx-coroutines-core-jvm-1.7.1.jar -> kotlinx-coroutines-core-jvm-1.11.0.jar against 3 scan targets

--------------------------------------------------------------------------------
💥 reachable from the application (likely to break)
--------------------------------------------------------------------------------

❌ kotlinx.coroutines.EventLoopKt.processNextEventInCurrentThread()
    method removed, throws NoSuchMethodError at first call
    used by 1 class:
        io.ktor.utils.io.jvm.javaio.BlockingAdapter  (ktor-io-jvm-2.3.13.jar)

scanned 372 classes: ❌ 1 broken (of which 💥 1 reachable, ⚠️ 0 not proven reachable), ❓ 5 unverified references (hierarchy escapes the analyzed scope)
```

- `--old` and `--new` name the compared pair. Both are repeatable, so several
  changed libraries can be checked in one run.
- `--classpath` takes the transitive dependencies, `:`-separated and
  repeatable.
- `--app` takes your own build outputs, as class directories or JARs. They are
  the roots the [reachability
  ranking](../README.md#violation-tiers-and-the-failon-threshold) walks from. Without
  them every violation stays 💥.
- `--classpath-file` reads a dump written by any of the plugins and adds its
  artifacts and build outputs to the scan targets. It is more accurate than a
  hand-assembled classpath and reduces unverified references, so prefer it
  whenever a build can produce one.

### `uika upgrade-check`

Compares two dumps and checks every artifact whose version changed. This is
what every plugin's check task runs.

```console
$ uika upgrade-check --before /tmp/before.json --after /tmp/after.json
```

The report is the same one the [README
shows](../README.md#what-a-check-reports).

- `--merged-classpath` checks the union of all modules' classpaths as one flat
  classpath, instead of [checking each module against its own
  resolution](../README.md#per-module-checking). It is also the automatic
  fallback for dumps that carry no per-module artifact data. Every plugin
  exposes it, since per-module checking costs one scan per module and a large
  monorepo may want the union instead.

### `uika dump`

Prints the API surface extracted from a JAR or directory. A debugging aid, and
unrelated to the classpath dumps the plugins write.

```console
$ uika dump some.jar
```

## Options shared by `check` and `upgrade-check`

- [`--fail-on`](../README.md#violation-tiers-and-the-failon-threshold) decides the exit
  code only. The report is printed the same way regardless.
- [`--exclude-file`](../README.md#excluding-known-false-positives)
  is repeatable, and rules from every file given are merged.
- [`--class-load-log`](runtime-load-evidence.md) is repeatable and takes text
  evidence, or a directory of it. The CLI reads no binary JFR, which is why the
  plugins convert recordings before invoking it. Without one, convert a
  recording by hand:

  ```console
  jfr print --json --events jdk.ClassLoad rec.jfr \
    | jq -r '.recording.events[].values.loadedClass.name
             | select(startswith("[") | not) | "[class,load] \(.)"'
  ```

  That yields tagged class-load lines the CLI reads (classes only, no triggers;
  the tag keeps default-package names accepted, and the filter drops array
  classes).
- [`--draft-exclude-file`](runtime-load-evidence.md) writes draft exclude rules
  from that evidence. It requires `--class-load-log`.
- [`--jdk-release N`](jdk.md) layers the JDK API of release N under the
  resolution scope, so hierarchy escapes into the JDK conclude instead of
  counting as unverified. It is opt-in here and defaults to on in every plugin,
  because a build already runs on a JVM and the CLI does not. The API is read
  from `$UIKA_JDK` when set, else `$JAVA_HOME`.
- `--json` prints the report as JSON instead of text. CLI-only, deliberately:
  each plugin prints the CLI's output through its own logger, so the JSON would
  come out of Maven with `[INFO]` on every line and out of Gradle without,
  parseable from neither. A report destination the plugins could point at a file
  would be the fix, and this flag is not it.
- `--verdicts-json <path>` streams every reference verdict (ok, unknown, broken)
  as JSON Lines to a file, for answer-checking against a real JVM
  (`tools/jvm-probe`, `make probe`). CLI-only, deliberately: it is written
  before `--exclude-file` filtering, carries neither graph-walk violations nor
  the service-provider walk, and does not dedupe call sites, so it is an
  evaluation stream rather than a report a build should act on.

## Checking a JDK upgrade

`--jdk-release-old N --jdk-release-new M` makes the JDK upgrade itself the
compared pair, which supplies both sides, so `--old` and `--new` must be
omitted. Passing them alongside is rejected rather than ignored. A JDK API your
classpath still references and release M dropped is then reported like any
other removal.

```console
$ uika check --jdk-release-old 11 --jdk-release-new 17 --classpath app.jar
checked JDK 11 -> JDK 17 against 1 scan target

❌ java.rmi.activation.ActivationGroup
    class removed, throws NoClassDefFoundError at first use
    used by 1 class:
        UsesRemoved  (app.jar)
```

Both releases are read from the one JDK uika finds, so checking an upgrade *to*
the JDK you now run needs only that JDK. [The JDK API
layer](jdk.md#where-the-stubs-come-from) covers the two stub sources and the one
change they cannot show, a class that became sealed.

From a build tool this needs no flag at all. Each dump records the API release
the application runs on, per module, which is what lets `upgrade-check` see the
move and check it on its own. See [Checking a JDK
upgrade](../README.md#checking-a-jdk-upgrade).

## Exit codes

`0` clean, `1` violations found per `--fail-on`, `2` error. Errors always exit
2 regardless of `--fail-on`.
