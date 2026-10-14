plugins {
    java
}

// koin-core 3.3.0 made Logger.log final while koin-logger-slf4j 3.2.2 still overrides it,
// the break the koin-core-logger-slf4j golden pins. The version is the only thing that
// moves between the base dump and the PR dump.
val koinVersion = providers.gradleProperty("koinVersion").get()

dependencies {
    implementation("io.insert-koin:koin-core-jvm:$koinVersion")
    implementation("io.insert-koin:koin-logger-slf4j:4.2.2")
    runtimeOnly("org.jetbrains.kotlin:kotlin-stdlib:2.2.20")
}
