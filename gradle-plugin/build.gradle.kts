plugins {
    `java-gradle-plugin`
    `maven-publish`
    jacoco
}

dependencies {
    // Use Gradle-bundled Groovy (JsonSlurper) to read JSON.
    implementation(localGroovy())
    testImplementation(gradleTestKit())
    testImplementation(localGroovy())
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

repositories {
    mavenCentral()
}

group = "net.exoego.uika"
version = providers.gradleProperty("uikaVersion")
    .orElse(providers.environmentVariable("UIKA_VERSION"))
    .getOrElse("0.0.0-dev")

sourceSets {
    main {
        java.srcDir("../jvm-plugin-core/src/main/java")
    }
    test {
        // The shared class-file floor guard, mounted like the main sources so the Gradle
        // and Maven builds run one copy instead of drifting twins.
        java.srcDir("../jvm-plugin-core/src/test/java")
    }
}

// Do not pin a toolchain; emit Java 17-compatible bytecode with the JVM running Gradle.
// Gradle 9 requires at least JVM 17, so this works in every target environment.
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

// Maven Central requires sources and javadoc jars alongside every jar artifact.
java {
    withSourcesJar()
    withJavadocJar()
}

// Central requires the javadoc jar to exist, not to have content. The real API
// docs were 133 KB published, over half of it the jQuery and stylesheet
// boilerplate the standard doclet emits, and the Maven Central quota is shared
// across the whole net.exoego namespace. Publish the jar empty and let readers
// use the sources jar. See PUBLISHING.md.
tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    setSource(files())
}

// Emptying the source only stops the doclet running, it never cleans the output
// directory, and the jar packages whatever is left there. Excluding everything
// makes the jar empty whatever state the tree is in. The manifest is written by
// the Jar task itself, so it survives.
tasks.named<Jar>("javadocJar") {
    exclude("**")
}

// uikaUpgradeCheck defaults the CLI version to the plugin's own version, read from here.
tasks.jar {
    manifest {
        attributes("Implementation-Version" to version)
    }
}

// Coverage is opt-in: instrumenting the TestKit builds costs a daemon JVM each.
val coverageEnabled = providers.gradleProperty("uikaCoverage").map(String::toBoolean).getOrElse(false)

// The :runtime classifier is the agent jar itself; the default artifact only embeds it.
val jacocoTestKitAgent: Configuration by configurations.creating
dependencies {
    jacocoTestKitAgent("org.jacoco:org.jacoco.agent:${jacoco.toolVersion}:runtime")
}

val testKitDir = layout.buildDirectory.dir("test-kit")
val testKitExec = layout.buildDirectory.file("jacoco/testKit.exec")

tasks.test {
    useJUnitPlatform()
    // The jacoco plugin instruments every Test task by default, so `make gradle-check` would
    // pay for coverage it never reports.
    extensions.getByType<JacocoTaskExtension>().isEnabled = coverageEnabled

    if (coverageEnabled) {
        // TestKit builds run in a daemon the test task's own agent never reaches, and the five
        // task classes are tested only that way. The testkit dir doubles as that daemon's Gradle
        // user home, so one gradle.properties here instruments every build, no test edits.
        val agentJar = jacocoTestKitAgent.elements.map { it.single().asFile.absolutePath }
        val kitDir = testKitDir.get().asFile
        val exec = testKitExec.get().asFile
        systemProperty("org.gradle.testkit.dir", kitDir.absolutePath)
        doFirst {
            // daemon=false is load-bearing: JaCoCo flushes at JVM exit, and a reusable daemon
            // outlives the report task. A single-use daemon exits with each build.
            kitDir.mkdirs()
            exec.parentFile.mkdirs()
            exec.delete()
            kitDir.resolve("gradle.properties").writeText(
                """
                org.gradle.daemon=false
                org.gradle.jvmargs=-javaagent:${agentJar.get()}=destfile=${exec.path},append=true,includes=net.exoego.uika.*
                """.trimIndent()
            )
        }
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    // filter, not a plain add: JacocoReport fails on an execution-data path that does not exist.
    executionData(files(testKitExec).filter { it.exists() })
    reports {
        xml.required = true
        html.required = false
        csv.required = false
    }
}

gradlePlugin {
    plugins {
        create("uika") {
            id = "net.exoego.uika"
            implementationClass = "net.exoego.uika.gradle.UikaPlugin"
            displayName = "uika Gradle plugin"
            description = "Gradle plugin for writing uika resolved classpath dumps and running upgrade checks"
        }
    }
}

publishing {
    // Applies to the main publication and the plugin-marker publication; Maven
    // Central validates the full metadata set on both POMs.
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("uika-gradle-plugin")
            description.set("Gradle plugin for writing uika resolved classpath dumps and running upgrade checks")
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
    repositories {
        // Local staging directory; JReleaser signs and uploads it to Maven Central.
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}
