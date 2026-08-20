# Uika (Unseen Incompatibility, Kick Away)

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fuika-cli%2Fmaven-metadata.xml&label=Maven%20Central)](https://central.sonatype.com/namespace/net.exoego.uika)

Ultra-fast and low-memory LinkageError checker for JVM.
Catches `NoSuchMethodError` and friends statically, before you ship.

## The problem to fix

When dependency resolution picks conflicting versions, an API that a library
was compiled against can vanish from the runtime classpath and fail at runtime
with `NoSuchMethodError` / `NoClassDefFoundError`.

With modern practice of using Dependabot, Renovate, or Scala Steward bumping
versions constantly, auditing transitive dependencies by hand does not scale.

Uika catches such `LinkageError`s at PR time by analyzing every class/method
reference recorded in the referencing binary's constant pool.

Detection covers:
- Class/Method removals
- Visibility narrowing (public -> protected -> private)
- Static <-> instance mismatches
- Newly-final classes/members
- Methods that became abstract
- `new` on a class that became abstract or an interface
- Class <-> interface flips
- Subclasses left out of a newly sealed type's `permits` clause
- Conflict of default methods from two unrelated interfaces at once
- `META-INF/services` providers that ServiceLoader can no longer find or
  instantiate (`ServiceConfigurationError` rather than a `LinkageError`)

## Prior art

API diff tools ([Revapi](https://revapi.org/), [japicmp](https://github.com/siom79/japicmp),
[roseau](https://github.com/alien-tools/roseau), [MiMa](https://github.com/scala-garden/mima))
report every API change between two versions of one library. They answer "what
changed in this library", not "which of those changes break **my** app".

Classpath validators (Google's [Linkage Checker](https://github.com/GoogleCloudPlatform/cloud-opensource-java),
Spotify's [missinglink](https://github.com/spotify/missinglink)) scan one fully
resolved snapshot. Every run therefore also surfaces pre-existing
inconsistencies, so a per-PR upgrade gate built on one tends to need a curated
exclusion list.

Uika does both halves in one step. It diffs the changed library old vs new,
then resolves each real reference on your classpath the way the JVM links, and
reports only breakage the upgrade itself introduced. That keeps a PR gate on
Renovate/Dependabot/Scala Steward bumps quiet with no exclusion list.

[BENCHMARKS.md](BENCHMARKS.md) has measured head-to-head runs against these
tools on the same inputs (wall time, peak memory, and what each one reports).

## Usage

Every recipe below drives the Gradle, sbt, or Maven plugin, so declare it in
your build first: see [Build-tool plugins](#build-tool-plugins).

### PR gate on GitHub Actions (the main use case)

For Gradle:

```yaml
name: dependency binary incompatibility check
on: pull_request

jobs:
  upgrade-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      # ... You may need to setup Java/Gradle/Maven/Sbt here ....

      - name: Dump baseline classpath (base branch)
        id: baseline
        continue-on-error: true
        run: |
          git checkout ${{ github.event.pull_request.base.sha }}
          if ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/before.json -PuikaBuildOutputs=false; then
            status=0
          else
            status=1
          fi
          git checkout -
          exit $status

      - name: Dump PR classpath
        run: ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/after.json

      - name: Check broken references
        if: steps.baseline.outcome == 'success'
        run: ./gradlew uikaUpgradeCheck -PuikaBefore=/tmp/before.json -PuikaAfter=/tmp/after.json
```

sbt and Maven use the same three steps with different commands.

| Step | sbt | Maven |
| --- | --- | --- |
| Baseline dump | `sbt uikaDumpClasspath && cp target/uika/classpath.json /tmp/before.json` | `mvn -q uika:dump-classpath -Duika.output=/tmp/before.json` |
| PR dump | `sbt compile uikaDumpClasspath && cp target/uika/classpath.json /tmp/after.json` | `mvn -q compile uika:dump-classpath -Duika.output=/tmp/after.json` |
| Check | `sbt "uikaUpgradeCheck /tmp/before.json /tmp/after.json"` | `mvn uika:upgrade-check -Duika.before=/tmp/before.json -Duika.after=/tmp/after.json` |

To keep the base-branch resolution off the PR's critical path, dump the
baseline once per push instead and cache it as an artifact keyed by SHA:
[BASELINE-CACHING.md](BASELINE-CACHING.md).

### Runtime load evidence from the base branch (optional)

[Runtime load evidence](#runtime-load-evidence---class-load-log) rides the same
artifact flow: the base branch runs its test suite with class-load logging and
uploads the logs, the PR job downloads them by `base.sha` and adds one flag. A
⚠️ class that provably loads during tests then fails `--fail-on reachable`
instead of being deprioritized. For Gradle, next to the baseline dump:

```yaml
      - run: ./gradlew test -PuikaClassLoadLog=/tmp/uika-load
      - uses: actions/upload-artifact@v7
        with:
          name: uika-class-load-${{ github.sha }}
          path: /tmp/uika-load
```

and in the PR job, after downloading the artifact into `/tmp/uika-load`:

```yaml
      - run: ./gradlew uikaUpgradeCheck -PuikaBefore=/tmp/before.json -PuikaAfter=/tmp/after.json -PuikaClassLoadLog=/tmp/uika-load
```

The [per-tool knobs](#build-tool-plugins) cover sbt and Maven.

## Command reference

```console
# List breaking changes between old/new versions of a library.
# Each line opens with the change kind, which is also what --json puts in its "kind" field:
# jq '.breaking_changes[] | select(.kind == "class_became_final")' selects those lines.
$ uika diff guava-22.0.jar guava-23.0-rc1.jar [--json]
CLASS BECAME FINAL     com/google/common/collect/BoundType
FIELD REMOVED          com/google/common/graph/GraphConstants.EDGE_CONNECTING_NOT_IN_GRAPH Ljava/lang/String;
METHOD ACCESS NARROWED com/google/common/collect/Iterators$ConcatenatedIterator.<init> (Ljava/util/Iterator;)V (public -> package-private)

breaking changes: 93 (classes: 26, methods: 61, fields: 6)

# Find usages of breaking changes across classpath JARs / your build output
# (--old/--new may be repeated to check several changed libraries in one run)
# Exit codes: 0 = clean, 1 = violations found, 2 = error
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

# Detect broken references caused by every artifact whose version changed.
# When application roots are known (build outputs in the dump, or --app), violations
# are ranked: reachable first, then the ones no static path reaches.
$ uika upgrade-check --before /tmp/before.json --after /tmp/after.json
dependency changes: 1
    CHANGED io.opentelemetry:opentelemetry-sdk-common 1.42.1 -> 1.60.1

per-module check: 2 of 41 modules changed their resolved versions (39 unchanged)
    :app  scanned 84013 classes, ❌ 42 broken, ❓ 118 unverified
    :worker  scanned 61200 classes, ✅ 0 broken, ❓ 87 unverified

--------------------------------------------------------------------------------
💥 reachable from the application (likely to break)
--------------------------------------------------------------------------------

💡 suggestion: align all io.opentelemetry artifacts to one version (e.g. via the matching BOM); otherwise upgrade the sender or pin opentelemetry-sdk-common to 1.42.1
    affected modules: :app
    why: io.opentelemetry:opentelemetry-sdk-common changed 1.42.1 -> 1.60.1, which breaks io.opentelemetry:opentelemetry-exporter-sender-okhttp:1.42.1:
        io.opentelemetry.sdk.internal.DaemonThreadFactory was removed, but io.opentelemetry.exporter.sender.okhttp.internal.OkHttpGrpcSender still uses it
        io.opentelemetry.sdk.internal.DaemonThreadFactory was removed, but io.opentelemetry.exporter.sender.okhttp.internal.OkHttpUtil still uses it

--------------------------------------------------------------------------------
⚠️  not proven reachable (no static path found; may still load via reflection)
--------------------------------------------------------------------------------

💡 suggestion: ...
    why: ...

scanned 145213 classes: ❌ 42 broken (of which 💥 25 reachable, ⚠️ 17 not proven reachable), ❓ 205 unverified references (hierarchy escapes the analyzed scope)

# Debugging aid: dump the extracted API surface of a JAR
$ uika dump some.jar
```

### Violation tiers and `--fail-on`

A changed library drags in transitive JARs your application never touches, so
not every violation is worth the same attention. Each one lands in a tier, and
the report prints them in this order:

| Tier | Meaning |
| --- | --- |
| 💥 breaks | reachable from your application, or not provably unreachable |
| 💤 latent | class is reachable, but no scanned code invokes the affected member |
| ⚠️ unproven | no static path from your application reaches the class |

`check` and `upgrade-check` always print the full report; `--fail-on` only
decides the exit code, as a threshold over exactly that split:

- `any` (default, strictest): exit 1 on any violation.
- `reachable`: exit 1 only on 💥.
- `never`: always exit 0, reporting violations as warnings only.

So what fails CI is exactly what the report shows above the warning sections.
Errors always exit 2 regardless of `--fail-on`.

**Reachability (💥 vs ⚠️).** When application roots are available (the module
`classesDirs` in a dump, or `--app` build outputs), uika walks the class-load
graph from them and labels what it never reaches ⚠️. The walk is a deliberate
over-approximation, so ⚠️ is a signal to deprioritize rather than a guarantee,
and reflection driven purely by external configuration stays invisible.
Anything not provably unreachable stays 💥.

Without usable roots every violation stays 💥, so `reachable` behaves like
`any`. That covers a bare `check --classpath ...` (nothing to walk from) and
roots that matched no scanned class (build outputs not compiled, which prints a
warning naming the cause).

**Invocation evidence (💤).** `AbstractMethodError` is the one break that does
not fire when the class loads. A concrete class inheriting an unimplemented
abstract method loads, verifies, and instantiates without complaint, and throws
only when the missing method is actually called. So for `method became
abstract` uika looks for an invocation of the affected member in the scanned
bytecode, and calls the violation latent when there is none. That evidence
comes from bytecode rather than from application roots, so 💤 survives both
degraded cases above. Like ⚠️ it is a confidence tier and not a proof, since a
call through reflection or JNI, or from code outside the scan, stays invisible.
[`--exclude-file`](#excluding-known-false-positives---exclude-file) can drop
the whole category with `kind = "method_became_abstract"`.

### Per-module checking (`upgrade-check`)

Each module gets its own JVM classpath at runtime, and two modules may
legitimately resolve different versions of one coordinate (e.g. one service 
on netty 4.1, a newer one on 4.2). So each module is checked against what it 
actually resolves, not against a flattened union: modules whose versions did 
not move are skipped, and every violation names the modules that exhibit it
(`modules:` in the text report, a `modules` array in JSON). `--merged`
restores the flat union check.

### Excluding known false positives (`--exclude-file`)

Some violations are real breaks in the referenced API but never actually
matter at runtime, because the only reference resolves through reflection
the tool cannot see (see [Violation tiers](#violation-tiers-and---fail-on)).
commons-logging's `LogFactoryImpl` is the recurring example: it reflectively
scans a `String[]` of class names at init, so a field like
`classesToDiscover` shows up as removed even though no bytecode reference to
it survives.

`--fail-on reachable` already keeps that kind of violation from failing the
build, but it is still printed on every run. `--exclude-file <path>`
(repeatable; rules from every file given are merged) drops specific known
false positives from the report entirely, with a required reason so the
entry documents itself for whoever reads it next:

```toml
# uika-exclude.toml
[[exclude]]
owner = "org/apache/commons/logging/impl/LogFactoryImpl"
member = "classesToDiscover"
reason = "reflectively scanned by LogFactoryImpl at init; never referenced from bytecode"

# owner may end with a single trailing '*' to match a whole package/class prefix;
# member is optional, and when set matches by name only (covers every overload).
[[exclude]]
owner = "org/apache/commons/logging/*"
reason = "commons-logging uses reflection-based class discovery throughout"

# add descriptor to pin one overload, so a real break on a sibling overload of
# the same name is still reported.
[[exclude]]
owner = "lib/C"
member = "m"
descriptor = "()V"
reason = "only the no-arg m() is invoked reflectively"

# kind pins a rule to one violation kind. With an owner, both must match:
# this waives only newly-abstract methods from conscrypt, leaving every other
# kind of conscrypt break — and this kind elsewhere — reported.
[[exclude]]
owner = "org/conscrypt/*"
kind = "method_became_abstract"
reason = "conscrypt adds abstract methods its own code never calls; tracked in DEP-142"

# kind alone applies to every owner. Note that exclusion REMOVES a violation from
# the report, it does not merely stop it failing the build, so use this only for a
# category you have decided not to see at all.
[[exclude]]
kind = "extends_final_class"
reason = "we ship a shaded copy of the lagging artifact, so version-lag finals never link"
```

`owner`/`member`/`descriptor` use raw JVM internal forms (`/`-separated owner
names, `$` for nested classes, `<init>` for constructors, undecoded descriptors
like `(Ljava/util/Date;)V`), so copy entries from the `--json` output rather
than from the text report's dotted signatures (each violation's `reference`
carries the raw `owner`, `member.name`, and `member.descriptor`).

`kind` is the violation kind in snake_case, for example `class_removed` or
`method_became_abstract`; an unknown value is rejected at load with the valid
list, while the spaced form the reports print under `reason` is accepted, as is
a kind from before it was split by direction and member kind
(`class_kind_changed` still waives both of the flips that replaced it).

A rule needs an `owner`, a `kind`, or both. The summary line reports how many
violations were suppressed (`N suppressed by --exclude-file`), and a rule that
matched nothing prints a warning, so stale entries do not go unnoticed as the
checked libraries change.

This is for false positives you have actually investigated, not a shortcut
around triaging `⚠️  not proven reachable` violations wholesale; use
`--fail-on reachable` for that instead.

### Runtime load evidence (`--class-load-log`)

The ⚠️ tier means "no static path found", and its blind spot is reflection. A
JVM can close that gap: run the **current, not yet upgraded** build — its test
suite, or a staging/production soak — with

```
-Xlog:class+load=info:file=class-load.log
```

(JDK 9+; every loaded class, one line each, negligible overhead) and feed the
log to the check:

```console
$ uika check ... --class-load-log class-load.log
$ uika upgrade-check ... --class-load-log class-load.log
```

The intended CI shape mirrors [baseline caching](BASELINE-CACHING.md): the base
branch's test run produces the log once per push and stores it as an artifact,
and the dependency PR's `upgrade-check` downloads it. A ⚠️ violation whose
referencing class appears in the log is promoted out of the tier and marked
`⚡ observed loading at runtime` — the class provably loads, reflection
included, so `--fail-on reachable` now fails on it. `--json` carries the same
evidence per violation (`observed_loading`, `load_trigger`).

Ingestion is promote-only, the same stance reachability takes: absence of a
load entry proves nothing beyond the observed runs (a different code path, a
run that never got there), so no violation is ever demoted or dropped because
of a log. Accepted formats, mixed freely and parsed leniently: unified-logging
`[class,load]` lines with any decorators (other `-Xlog` streams sharing the
file are skipped), plain class-name lists (dotted or slashed, so
`-XX:DumpLoadedClassList` classlists work), and `class+load+cause` stack
blocks. Passing a directory reads every file under it, so a downloaded artifact
directory works as-is; when parallel test JVMs share one target, put `%p` in
the file name (each JVM truncates a shared file on open):
`-Xlog:class+load=info:file=logs/load-%p.log`.

On JDK 22+ the log can also say *what loads each class*
(https://bugs.openjdk.org/browse/JDK-8193513):

```
-Xlog:class+load+cause=info:file=class-load.log -XX:LogClassLoadingCauseFor=*
```

captures a Java stack per load (`*` logs every class; the flag also takes a
substring to narrow it — stacks are not free, so prefer the narrow form
outside test suites). uika then names the trigger in the marker, e.g.
`⚡ observed loading at runtime (via java.lang.Class.forName from
com.example.PluginRegistry.discover(PluginRegistry.java:42))` — the reflective
edge the static walk could not see, documented for free.

**Drafting an exclude file (`--draft-exclude-file <path>`).** The deliberate
consumer of the opposite signal. After soaking the evidence, symbols whose
every violation is still ⚠️ *and* was never observed loading can be drafted
into [`--exclude-file`](#excluding-known-false-positives---exclude-file)
rules. Every drafted reason opens with `REVIEW:` and records exactly what the
evidence shows (which classes, which logs); a symbol that also breaks a
reachable or observed class is never drafted, because the rule would waive
that real break too. The draft is input for a human: review each entry and
delete what you cannot justify before committing the file. Requires
`--class-load-log`.

## Build-tool plugins

The Gradle, sbt, and Maven plugins write the same dump format: every module's resolved runtime
classpath as coordinate-annotated JSON, kept per module so `upgrade-check` can
[check each against its own resolution](#per-module-checking-upgrade-check).
Feed two dumps to `uika upgrade-check`, or one to `uika check
--classpath-file` (more accurate than a hand-assembled classpath, and reduces
unverified references).

A dump also refers to build outputs, so the Gradle task builds them by default
(`-PuikaBuildOutputs=false` for a resolution-only dump); sbt compiles as a side
effect of the dump task, and Maven needs a `compile` phase in the same
invocation.

The upgrade-check task fetches the CLI itself as
`net.exoego.uika:uika-cli:<version>:<platform>@zip` through the build's own
dependency resolution, reusing its repositories, credentials, and cache, so
there is no separate install step. The version defaults to the plugin's own, so
one coordinate bump updates both.

The settings shown per tool below also have command-line forms:
[`failOn`](#violation-tiers-and---fail-on) (`-PuikaFailOn=`, `set uikaFailOn
:=`, `-Duika.failOn=`) and
[`excludeFiles`](#excluding-known-false-positives---exclude-file)
(`-PuikaExcludeFile=` for a single file).

[`--jdk-release`](#how-it-works) needs no setting at all. The build runs on a
JVM, so the release is derived from the Gradle toolchain,
`maven.compiler.release`/`target`, or the sbt build JVM, clamped to what that
JVM's `ct.sym` serves. Override with `jdkRelease` / `uikaJdkRelease` /
`<jdkRelease>` (`-PuikaJdkRelease=`, `-Duika.jdkRelease=`), or set 0 to disable
it.

[Runtime load evidence](#runtime-load-evidence---class-load-log) is one knob
per tool, pointed at one directory for both phases (collect on the base
branch, consume on the PR):

- Gradle: `-PuikaClassLoadLog=<dir>` makes every `Test` task write a
  per-process class-load log there, and makes `uikaUpgradeCheck` read the
  directory back as `--class-load-log`. A bare `-PuikaClassLoadLog` uses
  `build/uika/class-load`.
- sbt: `uikaClassLoadLog := Some(file("<dir>"))` does the same for forked test
  JVMs and for `uikaUpgradeCheck`. It needs `Test / fork := true`: an
  in-process test runs inside sbt's own JVM, which no flag can reach after
  startup.
- Maven: collect with the test JVM flag
  (`mvn test -DargLine="-Xlog:class+load=info:file=<dir>/load-%p.log"`), check
  with `-Duika.classLoadLog=<dir>`.

The `%p` in the file names keeps parallel test JVMs from truncating each
other's log. [`--draft-exclude-file`](#runtime-load-evidence---class-load-log)
maps to `-PuikaDraftExcludeFile=` / `uikaDraftExcludeFile :=` /
`-Duika.draftExcludeFile=`.

### Gradle (`gradle-plugin/`) [![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fuika-gradle-plugin%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/uika-gradle-plugin)

Works with Groovy and Kotlin DSL builds (Gradle 9 / JVM 17+).

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

```kotlin
// build.gradle.kts
import net.exoego.uika.gradle.UpgradeCheckTask

plugins {
    id("net.exoego.uika") version "VERSION_PLACEHOLDER"
}

// Optional: gate only on reachable violations, and suppress known false positives.
tasks.withType<UpgradeCheckTask>().configureEach {
    failOn.set("reachable")
    excludeFiles.from("uika-exclude.toml")
}
```

```console
$ ./gradlew uikaDumpClasspath -PuikaOutput=/tmp/after.json
$ ./gradlew uikaUpgradeCheck \
      -PuikaBefore=/tmp/before.json -PuikaAfter=/tmp/after.json   # -PuikaCliVersion=x.y.z to override
```

### sbt (`sbt-plugin/`) [![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fsbt-uika_2.12_1.0%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/sbt-uika_2.12_1.0)

```scala
// project/plugins.sbt
addSbtPlugin("net.exoego.uika" % "sbt-uika" % "VERSION_PLACEHOLDER")
```

```scala
// build.sbt — optional: gate only on reachable violations, and suppress known false positives.
ThisBuild / uikaFailOn := "reachable"
ThisBuild / uikaExcludeFiles := Seq(baseDirectory.value / "uika-exclude.toml")
```

```console
$ sbt uikaDumpClasspath   # writes target/uika/classpath.json (override via the uikaOutput setting)
$ sbt "uikaUpgradeCheck /tmp/before.json /tmp/after.json"   # uikaCliVersion setting to override
```

### Maven (`maven-plugin/`) [![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fuika-maven-plugin%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/uika-maven-plugin)

```xml
<build>
  <plugins>
    <plugin>
      <groupId>net.exoego.uika</groupId>
      <artifactId>uika-maven-plugin</artifactId>
      <version>VERSION_PLACEHOLDER</version>
      <!-- Optional: gate only on reachable violations, and suppress known false positives. -->
      <configuration>
        <failOn>reachable</failOn>
        <excludeFiles>
          <excludeFile>${project.basedir}/uika-exclude.toml</excludeFile>
        </excludeFiles>
      </configuration>
    </plugin>
  </plugins>
</build>
```

```console
$ mvn uika:dump-classpath -Duika.output=/tmp/classpath.json
$ mvn uika:upgrade-check \
      -Duika.before=/tmp/before.json -Duika.after=/tmp/after.json   # -Duika.cliVersion to override
```

## How it works

1. Parse the old/new JARs into full API indexes with class hierarchy.
2. Pass 1 streams the consumer classpath, keeping only a class-hierarchy graph
   and the references whose owner exists in the old index.
3. Pass 2 re-reads just the classes resolution could actually visit (typically
   under 0.1% of the total) for their member tables.
4. Resolve each reference against "new JARs + re-read classes", walking the
   inheritance hierarchy, and report the ones that resolved under old but break
   under new.

Linkage is checked the way the JVM links, against the flattened runtime
classpath. Members moved to a superclass, classes relocated to another
artifact, and copies bundled inside fat JARs are not false positives.
References that escape into unanalyzed classes are counted as "unverified"
rather than silently ignored.

Most escapes lead into the JDK. Passing `--jdk-release N` (on `check` and
`upgrade-check`) layers the JDK API of release N under the resolution scope,
read from the `ct.sym` file of the JDK named by `UIKA_JDK` (checked first,
authoritative when set), else `JAVA_HOME`, so those references conclude as OK
or broken instead of unverified. N must be older than the installed JDK (its
own release is not in `ct.sym`). The layer sits under both the old and the new
side, so gaps in `ct.sym` cancel out instead of producing false positives from
missing stubs. Without the flag nothing changes, and uika still needs no JVM
to run.

```console
$ uika check --old guava-22.0.jar --new guava-23.0-rc1.jar \
             --classpath selenium-remote-driver-3.4.0.jar
...
scanned 205 classes: ❌ 2 broken, ❓ 16 unverified references (hierarchy escapes the analyzed scope)

$ uika check --old guava-22.0.jar --new guava-23.0-rc1.jar \
             --classpath selenium-remote-driver-3.4.0.jar --jdk-release 17
...
scanned 205 classes: ❌ 2 broken
```

### Checking a JDK upgrade

`--jdk-release-old N --jdk-release-new M` makes the JDK upgrade itself the compared
pair, so `--old` and `--new` become optional. A JDK API your classpath still
references and release M dropped is then reported like any other removal.

```console
$ uika check --jdk-release-old 11 --jdk-release-new 17 --classpath app.jar
checked JDK 11 -> JDK 17 against 1 scan target

❌ java.rmi.activation.ActivationGroup
    class removed, throws NoClassDefFoundError at first use
    used by 1 class:
        UsesRemoved  (app.jar)
```

Releases below the installed JDK come from its `ct.sym`; the installed JDK's own
release comes from its `jmods/`, which `ct.sym` never carries. Checking an upgrade
*to* the JDK you now run therefore needs only that one JDK. Sealing changes are
invisible here, because `ct.sym` stubs do not carry `PermittedSubclasses`, and
reporting them from the `jmods` side alone would be a false positive.

From the build-tool plugins this needs no flag. The classpath dump records the
release of the JVM that wrote it, so bumping your toolchain and re-running the
dump is enough — `upgrade-check` sees the two dumps disagree and checks the JDK
move alongside the dependency moves, in one report.

```console
$ uika upgrade-check --before before.json --after after.json
dependency changes: none

per-module check: 0 of 1 modules changed their resolved versions (1 unchanged)
    JDK 11 -> 17  scanned 2 classes, ❌ 1 broken, 0 unverified
...
        UsesRemoved  (app.jar) [JDK 11 -> 17]
```

Dumps written before the plugins recorded the release carry no value, and a
missing value on either side is never read as a JDK move.

## Development

`make check` runs fmt, clippy, and the Rust and plugin test suites.
[CONTRIBUTING.md](CONTRIBUTING.md) covers the rest, including the vendored
real-incident fixtures, the golden-bless workflow, and the JVM probe harness.
Releases are described in [PUBLISHING.md](PUBLISHING.md).

## Known limitations (PoC)

- References whose hierarchy escapes into unanalyzed classes are conservatively
  treated as OK (reported only as an "unverified" count, which passing the
  complete runtime classpath via `--classpath` reduces)
- Multi-release JARs are analyzed at their base classes only
  (`META-INF/versions/` is ignored)
- `InvokeDynamic` bootstrap synthetic names are excluded
- A constant-pool reference does not guarantee the code path executes (optional
  integrations guarded by try/catch may be reported yet never run)
