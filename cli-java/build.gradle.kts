plugins {
    `maven-publish`
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
    // Tests address fixtures as tests/fixtures/x.jar: that string is interned as a violation's
    // source, and the goldens pin it byte for byte.
    workingDir = rootDir
    maxHeapSize = "1g"
    // -PuikaBless=true makes GoldenTest rewrite the goldens instead of comparing.
    systemProperty("uika.bless", providers.gradleProperty("uikaBless").getOrElse("false"))
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = false
        csv.required = false
    }
}

publishing {
    publications {
        create<MavenPublication>("uikaCli") {
            artifactId = "uika-cli"
            // The jar is a classified artifact, not the main one, so the POM can keep "pom"
            // packaging. Central demands a sources and a javadoc jar for any
            // other packaging, and each would be four more files per release: twelve for a
            // main jar against four for this one. Nothing in the jar is public API, so
            // nobody needs it as a plain dependency. See PUBLISHING.md.
            artifact(tasks.jar) {
                classifier = "jvm"
            }
            pom {
                packaging = "pom"
                name.set("uika-cli")
                description.set("uika command-line interface, a runnable jar (classifier jvm)")
                url.set("https://github.com/exoego/uika")
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("exoego")
                        name.set("TATSUNO Yasuhiro")
                        url.set("https://github.com/exoego")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/exoego/uika.git")
                    developerConnection.set("scm:git:ssh://git@github.com/exoego/uika.git")
                    url.set("https://github.com/exoego/uika")
                }
            }
        }
    }

    repositories {
        // Local staging directory; JReleaser signs and uploads it to Maven Central.
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}
