plugins {
    `maven-publish`
    java
    application
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

// Maven Central requires sources and javadoc jars alongside every jar artifact.
java {
    withSourcesJar()
    withJavadocJar()
}

// The javadoc jar ships empty, like the Gradle plugin's. Nothing here is public API, and
// the Maven Central quota is shared across the whole net.exoego namespace. See PUBLISHING.md.
tasks.withType<Javadoc>().configureEach {
    setSource(files())
}

tasks.named<Jar>("javadocJar") {
    exclude("**")
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "net.exoego.uika.cli.Main",
            "Implementation-Version" to version,
        )
    }
}

tasks.test {
    useJUnitPlatform()
    // Fixtures, goldens and scenarios.tsv are shared with the Rust crate. Tests address them
    // as tests/fixtures/x.jar: that string is interned as a violation's source, and the
    // goldens pin it byte for byte.
    workingDir = rootDir.resolve("../cli")
    maxHeapSize = "1g"
}

val nativeClassifiers = listOf(
    "linux-x86_64",
    "macos-aarch64",
    "macos-x86_64",
    "windows-x86_64"
)
val nativeDist = layout.projectDirectory.dir("../dist/native")
val nativeZips = nativeClassifiers.associateWith { classifier ->
    nativeDist.file("$classifier/uika-$version-$classifier.zip").asFile
}
val stagedNativeZips = nativeZips.filterValues { it.isFile }
val missingNativeZips = if (stagedNativeZips.isEmpty()) emptyList() else (nativeZips.keys - stagedNativeZips.keys).toList()
val nativeDistPath = nativeDist.asFile.path

// All four or none. A partial set would publish a release that one platform cannot resolve,
// and a Central deployment cannot be amended afterwards.
tasks.withType<AbstractPublishToMaven>().configureEach {
    doFirst {
        if (missingNativeZips.isNotEmpty()) {
            throw GradleException(
                "native CLI ZIPs are incomplete under $nativeDistPath: missing " + missingNativeZips.joinToString(", ")
            )
        }
    }
}

// The jar has no dependencies, so module metadata would say nothing the POM does not, and
// it would cost four more files per release against the Central Portal limits.
tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = false
}

publishing {
    publications {
        create<MavenPublication>("uikaCli") {
            artifactId = "uika-cli"
            from(components["java"])
            stagedNativeZips.forEach { (classifier, file) ->
                artifact(file) {
                    extension = "zip"
                    this.classifier = classifier
                }
            }
            pom {
                name.set("uika-cli")
                description.set("uika command-line interface: a runnable jar, plus native binaries as classified ZIPs")
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
