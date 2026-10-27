@file:Suppress("UnstableApiUsage")

rootProject.name = "kotoml"

pluginManagement {
    includeBuild("build-logic")

    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.frommhund.xyz/releases") {
            mavenContent { includeGroupAndSubgroups("com.fromwau") }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenLocal {
            mavenContent {
                includeModuleByRegex("com\\.fromwau", "klap.*")
                // Temporary until kern 0.6.0 is on the release repository.
                includeGroup("com.fromwau.kern")
            }
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven("https://maven.frommhund.xyz/releases") {
            mavenContent { includeGroupAndSubgroups("com.fromwau") }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

gradle.lifecycle.beforeProject {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

include(":core")
include(":serialization")
include(":app")
include(":live-test")
