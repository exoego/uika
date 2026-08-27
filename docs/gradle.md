# Gradle plugin (`gradle-plugin/`)

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fexoego%2Fuika%2Fuika-gradle-plugin%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/net.exoego.uika/uika-gradle-plugin)

One of uika's [build-tool integrations](../README.md#build-tool-plugins).
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

The dump task builds the module outputs by default. Pass
`-PuikaBuildOutputs=false` for a resolution-only dump, which is what the
[PR gate](../README.md#pr-gate-on-github-actions-the-main-use-case) uses on
the base branch.

## Knobs

- [`failOn`](../README.md#violation-tiers-and---fail-on) and
  [`excludeFiles`](../README.md#excluding-known-false-positives---exclude-file)
  are shown in the build script above. The command-line forms are
  `-PuikaFailOn=` and `-PuikaExcludeFile=` (single file).
- [`jdkRelease`](../README.md#build-tool-plugins) is derived from
  `compileJava`'s `options.release`, else target compatibility. Override with
  `-PuikaJdkRelease=` on both the dump and the check, or set 0 to disable the
  API layer.

## Runtime load evidence (JFR)

`-PuikaJfr=<dir>` makes every `Test` task record class loads into a
[JFR recording](../README.md#runtime-load-evidence-jfr---class-load-log) there
(and run for real — an `UP-TO-DATE` or `FROM-CACHE` test task forks no JVM and
would collect nothing), and makes `uikaUpgradeCheck` convert and read the
directory back. A bare `-PuikaJfr` uses `build/uika/jfr`.
[`--draft-exclude-file`](../README.md#runtime-load-evidence-jfr---class-load-log)
maps to `-PuikaDraftExcludeFile=`.
