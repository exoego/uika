# Test fixtures

Unmodified third-party JARs vendored from Maven Central, used by the integration
tests (`tests/integration.rs`) as real-world ground truth. Several real GitHub
reports are reproduced from these exact binaries:

- ktor-io 2.3.13 binds to `EventLoopKt.processNextEventInCurrentThread ()J`,
  which exists in kotlinx-coroutines 1.7.1 and is gone in 1.11.0
  (`NoSuchMethodError` at runtime)
- opentelemetry-exporter-sender-okhttp 1.42.1 references
  `io/opentelemetry/sdk/internal/DaemonThreadFactory`, which moved packages
  between opentelemetry-sdk-common 1.42.1 and 1.60.1
  (`NoClassDefFoundError` at runtime)
- Selenium 3.4.0 calls Guava's `SimpleTimeLimiter(ExecutorService)`
  constructor. Guava 23.0-rc1 made that constructor private
  (`IllegalAccessError`; SeleniumHQ/selenium#4381)
- okhttp-digest 1.21 calls OkHttp's `RequestLine.requestPath(HttpUrl)` as a
  static method. OkHttp 4.0.1 made it an instance method
  (`IncompatibleClassChangeError`; rburgst/okhttp-digest#57)
- koin-logger-slf4j 3.2.2 overrides `Logger.log(Level, String)`. koin-core
  3.3.0 made that method final
  (`IncompatibleClassChangeError`; InsertKoinIO/koin#1489)

## Contents

| Artifact (Maven Central coordinates) | SHA-256 |
|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.7.1` | `7496cffdd3eb10109acdda1c3212f6ac7815789e09380dc9e2ccdec496dba3fc` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0` | `d1d75aa01dffbb4d1c520e67e4c4e7f5f6174718e7cb4632412503f2f0e604fa` |
| `io.ktor:ktor-io-jvm:2.3.13` | `cd5463381fd9e992e09b59eb0f01e6a241513a8e515c2b7ddecf616bbaa8c3f6` |
| `io.opentelemetry:opentelemetry-sdk-common:1.42.1` | `0cb2f9e93291ccfe7099ed424b7616e7e80ee51fdbbff99d2b2365f52428b179` |
| `io.opentelemetry:opentelemetry-sdk-common:1.60.1` | `75cc96713e2e11c9a30dda4cb88ccfecc2209367c1e980bb946c5fc8bb71858f` |
| `io.opentelemetry:opentelemetry-exporter-sender-okhttp:1.42.1` | `a548bc2e9eeba69cc0e90d5a0551ac51057fa9a5c27a20b569a19693c04e9cab` |
| `com.google.guava:guava:22.0` | `1158e94c7de4da480873f0b4ab4a1da14c0d23d4b1902cc94a58a6f0f9ab579e` |
| `com.google.guava:guava:23.0-rc1` | `c3187cc4d9a05fec0277452b5cfe7c55f872cb4c033ca3d74dfd030e92c15e56` |
| `org.seleniumhq.selenium:selenium-remote-driver:3.4.0` | `47b88da5cb9c92f832af51db4fdf6b0a6aa70e7a76ed641137c344a8fad5cc03` |
| `com.squareup.okhttp3:okhttp:3.14.1` | `5a6be691653076aa64dcd361d2e445e4060b4b5dc882b1f6ba49e79ddfc3e563` |
| `com.squareup.okhttp3:okhttp:4.0.1` | `0e0392ea5c0d303bca20e13b2340086d7a347b22ad625f967989ee8723b6ac3c` |
| `io.github.rburgst:okhttp-digest:1.21` | `36f450a72810c7b40450820bd40ed646c740fa83f56d4fe917441f49311cb4dc` |
| `io.insert-koin:koin-core-jvm:3.2.2` | `1684443e89400c62cddcaf8c740c6f214c0217baa7c182532bcdaec1524b0fd4` |
| `io.insert-koin:koin-core-jvm:3.3.0` | `9196b5fda5c463f06429bfd5b4b96e6bab11cca1dbd12d4f9a6b555391ec081d` |
| `io.insert-koin:koin-logger-slf4j:3.2.2` | `9a3304f6144ad012c0e6a21e410337078b0e5c044a065354799b606dd71cf765` |

## Licensing

All of the above are redistributed unmodified under the Apache License,
Version 2.0 (see `LICENSE-APACHE-2.0.txt` in this directory):

- kotlinx.coroutines — Copyright JetBrains s.r.o.
- Ktor — Copyright JetBrains s.r.o.
- OpenTelemetry Java — Copyright The OpenTelemetry Authors
- Guava — Copyright Google LLC
- Selenium — Copyright Software Freedom Conservancy
- OkHttp — Copyright Square, Inc.
- okhttp-digest — Copyright Rainer Burgstaller
- Koin — Copyright Kotzilla and Koin project contributors

These JARs are test data only; they are not linked into or shipped with uika.
