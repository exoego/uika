plugins {
    `maven-publish`
}

group = "net.exoego.uika"
version = providers.gradleProperty("uikaVersion")
    .orElse(providers.environmentVariable("UIKA_VERSION"))
    .get()

val distDir = layout.projectDirectory.dir("../dist/native")
val binaryVersion = project.version.toString()
val classifiers = listOf(
    "linux-x86_64",
    "macos-aarch64",
    "macos-x86_64",
    "windows-x86_64"
)

publishing {
    publications {
        create<MavenPublication>("uikaCli") {
            artifactId = "uika-cli"
            pom {
                // No main artifact, only classified ZIPs; "pom" packaging keeps
                // Maven Central from demanding sources/javadoc jars.
                packaging = "pom"
                name.set("uika-cli")
                description.set("Native uika command-line binaries")
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

            classifiers.forEach { classifier ->
                val file = distDir.file("$classifier/uika-$binaryVersion-$classifier.zip").asFile
                artifact(file) {
                    extension = "zip"
                    this.classifier = classifier
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
