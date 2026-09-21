plugins {
    java
    application
    jacoco
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

group = "net.exoego.uika"
version = providers.gradleProperty("uikaVersion")
    .orElse(providers.environmentVariable("UIKA_VERSION"))
    .getOrElse("0.0.0-dev")

// Same floor as the build-tool plugins, so the one jar runs on whatever JVM runs the build.
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
}

application {
    mainClass = "net.exoego.uika.cli.Main"
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "net.exoego.uika.cli.Main",
            "Implementation-Version" to version,
        )
    }
}

// Coverage is opt-in, like the Gradle plugin's: `make java-cli-test` should not pay for it.
val coverageEnabled = providers.gradleProperty("uikaCoverage").map(String::toBoolean).getOrElse(false)

tasks.test {
    useJUnitPlatform()
    extensions.getByType<JacocoTaskExtension>().isEnabled = coverageEnabled
    // Fixtures, goldens and scenarios.tsv are shared with the Rust crate. Tests address them
    // as tests/fixtures/x.jar: that string is interned as a violation's source, and the
    // goldens pin it byte for byte.
    workingDir = rootDir.resolve("../cli")
    maxHeapSize = "1g"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = false
        csv.required = false
    }
}
