// The documented PR-gate shape with the real published plugin: mavenLocal holds the plugin
// and the uika-cli jar it resolves by its own version, and the fixture repository holds
// the jars the check compares, laid out as Maven without POMs.
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
    plugins {
        id("net.exoego.uika") version providers.gradleProperty("uikaVersion").get()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        maven {
            url = uri(providers.gradleProperty("fixtureRepo").get())
            metadataSources { artifact() }
        }
    }
}

rootProject.name = "uika-gradle-e2e"
include("app")
