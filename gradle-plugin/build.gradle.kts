plugins {
    `java-gradle-plugin`
    `maven-publish`
}

dependencies {
    // Use Gradle-bundled Groovy (JsonSlurper) to read JSON.
    implementation(localGroovy())
    testImplementation(gradleTestKit())
    testImplementation(localGroovy())
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

repositories {
    mavenCentral()
}

group = "net.exoego.uika"
version = providers.gradleProperty("uikaVersion")
    .orElse(providers.environmentVariable("UIKA_VERSION"))
    .getOrElse("0.1.0")

sourceSets {
    main {
        java.srcDir("../jvm-plugin-core/src/main/java")
    }
}

// Do not pin a toolchain; emit Java 17-compatible bytecode with the JVM running Gradle.
// Gradle 9 requires at least JVM 17, so this works in every target environment.
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

// uikaUpgradeCheck defaults the CLI version to the plugin's own version, read from here.
tasks.jar {
    manifest {
        attributes("Implementation-Version" to version)
    }
}

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        create("uika") {
            id = "net.exoego.uika"
            implementationClass = "net.exoego.uika.gradle.UikaPlugin"
        }
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/exoego/uika")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }
}
