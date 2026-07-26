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
  (`IllegalAccessError`; https://github.com/SeleniumHQ/selenium/issues/4381)
- okhttp-digest 1.21 calls OkHttp's `RequestLine.requestPath(HttpUrl)` as a
  static method. OkHttp 4.0.1 made it an instance method
  (`IncompatibleClassChangeError`; https://github.com/rburgst/okhttp-digest/issues/57)
- koin-logger-slf4j 3.2.2 overrides `Logger.log(Level, String)`. koin-core
  3.3.0 made that method final
  (`IncompatibleClassChangeError`; https://github.com/InsertKoinIO/koin/issues/1489)
- caffeine 3.2.3 is compiled for Java 11+, so its nest-internal private
  references (anonymous enum bodies calling the private enum constructor, etc.)
  appear as cross-class private accesses in bytecode. Used as a false-positive
  guard: a coordinate rename of the identical JAR must report nothing
- pact-jvm junit5spring 4.2.3 subclasses `PactVerificationExtension`, which
  junit5 4.2.3 opened up but junit5 4.2.2 still declares final. When junit5
  lags behind on the runtime classpath the subclass fails to load
  (`IncompatibleClassChangeError`/`VerifyError`; https://github.com/pact-foundation/pact-jvm/issues/1338).
  The old side is junit5 4.2.3 (the compile-time binding) and the new side is
  4.2.2 (the lagging runtime resolution). junit5spring 4.2.2 covers the same
  incident in the upgrade direction: the subclass does not exist there yet, so
  upgrading junit5spring 4.2.2 -> 4.2.3 while junit5 stays at 4.2.2 introduces
  the broken edge (the version-lag `extends final class` check)
- jetty-http 9.4.49 references the `ArrayTrie`/`ArrayTernaryTrie` classes and
  the `Trie` interface from jetty-util. jetty-util 10 made the Trie classes
  package-private and removed the interface, so mixing jetty module versions
  breaks (`IllegalAccessError`/`NoClassDefFoundError`). One consumer covers
  class access narrowed, class removed, and method access narrowed at once
- sisu.inject 0.3.4 shades asm, while sisu.inject 1.0.0 subclasses the real
  `org.objectweb.asm.ClassVisitor`, whose `int` constructor asm 9 narrowed
  from public to protected. Used as a false-positive guard for duplicate class
  names: with both sisu versions on one classpath the JVM loads only the
  first-wins copy, so references from the shadowed copy must not be reported
  in either classpath order
- ktor-network 2.3.13 calls `io.ktor.utils.io.ByteChannel` through an
  InterfaceMethodref. ktor-io 2.3.13 declares ByteChannel as an interface; 3.1.0
  made it a final class, so mixing ktor-io 3.1.0 under ktor-network 2.3.13
  makes method resolution throw `IncompatibleClassChangeError` (the
  class<->interface flip check). Same ktor module-skew family as the
  coroutines/ktor fixture
- jackson-module-kotlin 2.20.1 made `ValueClassBoxConverter` abstract; the
  module's own `ReflectionCache` and two sibling classes instantiate it with
  `new`. Bytecode compiled against 2.18.2, where it was a concrete final class,
  throws `InstantiationError` under 2.20.1 (the `new`-on-abstract check). The
  2.18.2 jar is both the old side and the consumer here
- kotlin-stdlib 2.2.20 is probe support, not a scan input: the Kotlin-built
  fixtures (coroutines, ktor, koin, okhttp 4, pact) need it on the classpath
  when `tools/jvm-probe` loads their classes in a real JVM

## Contents

| Artifact (Maven Central coordinates) | SHA-256 |
|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.7.1` | `7496cffdd3eb10109acdda1c3212f6ac7815789e09380dc9e2ccdec496dba3fc` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0` | `d1d75aa01dffbb4d1c520e67e4c4e7f5f6174718e7cb4632412503f2f0e604fa` |
| `io.ktor:ktor-io-jvm:2.3.13` | `cd5463381fd9e992e09b59eb0f01e6a241513a8e515c2b7ddecf616bbaa8c3f6` |
| `io.ktor:ktor-io-jvm:3.1.0` | `8a5eff401d0c3a720c271c6281f3be7d05ef967f3fe45955b5698bfb00d2e8c8` |
| `io.ktor:ktor-network-jvm:2.3.13` | `17fc53774c193c11443457bf5dbc9991e95daca09b2d8cd475050dfa0c71f280` |
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
| `com.github.ben-manes.caffeine:caffeine:3.2.3` | `ca70c90a5d1ce1511880ce9c93d4ad22108f61111d3daf91eb52762b571bd179` |
| `org.jetbrains.kotlin:kotlin-stdlib:2.2.20` | `8836ccffd3585fadda9901244b20d42901d2f3cd581058d8434e2ffabcf3a3e7` |
| `com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2` | `77f373478d7f124717863f9545d64974bb9b3062acd7c0f37ed94a3b70433a01` |
| `com.fasterxml.jackson.module:jackson-module-kotlin:2.20.1` | `22d28ca992bfb686d8231fb4972edbcfc1976e8dc806a1b7a58f85abcad3d69c` |
| `au.com.dius.pact.provider:junit5:4.2.2` | `d891060fc5ca9c9d954d9f16d61d0beb854d458515f383d5fc5f8e2f1cbb2f4b` |
| `au.com.dius.pact.provider:junit5:4.2.3` | `5ddb1a3bf4ae29d38ea4b7c649f24bc83ea974fbfb14dd43df4d71f1a2a75add` |
| `au.com.dius.pact.provider:junit5spring:4.2.2` | `e5f767d2d30bd47365ab6b1dd6651babb5db571b5c16502df57171ffa08895f1` |
| `au.com.dius.pact.provider:junit5spring:4.2.3` | `0eb746768b82e57e28176c07ee71bebc6adc717df178ce46711545779a4712da` |
| `org.eclipse.jetty:jetty-util:9.3.26.v20190403` | `2b4c01c9cf018221fb56817e15cbf4f7f5ac7c61bc53ec387b1c237386c27ed7` |
| `org.eclipse.jetty:jetty-util:10.0.26` | `95e2dc9c0d32f8585814272a32128b5328ca3f2b9c31fd8aae06e3476f253cb5` |
| `org.eclipse.jetty:jetty-http:9.4.49.v20220914` | `c39bfec2941a45396bd67da1aea53ea587c97ca31fdcee0d8ea4351b9f043704` |
| `org.ow2.asm:asm:8.0.1` | `ca5b8d11569e53921b0e3486469e7c674361c79845dad3d514f38ab6e0c8c10a` |
| `org.ow2.asm:asm:9.10.1` | `ed825d10ab1399c8c0cb669e688cf0c8c82629b4c8399b58352b68e92ca10fcb` |
| `org.eclipse.sisu:org.eclipse.sisu.inject:0.3.4` | `8c0e6aa7f35593016f2c5e78b604b57f023cdaca3561fe2fe36f2b5dbbae1d16` |
| `org.eclipse.sisu:org.eclipse.sisu.inject:1.0.0` | `3ab8d7bfe68f3b6ec95c1a0a47e628edbc9d76e90634cb0a0ba121fbb11b8e42` |

## Licensing

All of the above are redistributed unmodified. These JARs are test data only;
they are not linked into or shipped with uika.

Under the Apache License, Version 2.0 (`LICENSE-APACHE-2.0.txt`):

- Kotlin standard library — Copyright JetBrains s.r.o.
- kotlinx.coroutines — Copyright JetBrains s.r.o.
- Ktor — Copyright JetBrains s.r.o.
- OpenTelemetry Java — Copyright The OpenTelemetry Authors
- Guava — Copyright Google LLC
- Selenium — Copyright Software Freedom Conservancy
- OkHttp — Copyright Square, Inc.
- Jackson (jackson-module-kotlin) — Copyright FasterXML, LLC
- okhttp-digest — Copyright Rainer Burgstaller
- Koin — Copyright Kotzilla and Koin project contributors
- Caffeine — Copyright Ben Manes
- Pact JVM — Copyright DiUS Computing Pty Ltd
- Eclipse Jetty — Copyright Mort Bay Consulting Pty Ltd and others
  (dual-licensed EPL-2.0 / Apache-2.0; redistributed here under Apache-2.0)

Under the BSD 3-Clause License (`LICENSE-BSD-3-CLAUSE-ASM.txt`):

- ASM — Copyright INRIA, France Telecom

Under the Eclipse Public License:

- Eclipse Sisu 0.3.4 — Copyright Sonatype, Inc. and others
  (EPL-1.0, `LICENSE-EPL-1.0.txt`)
- Eclipse Sisu 1.0.0 — Copyright the Eclipse Sisu contributors
  (EPL-2.0, `LICENSE-EPL-2.0.txt`)
