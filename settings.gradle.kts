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
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // A kern or klap built here and published locally, ahead of the release repository, so a fix there can
        // be used from this build before it is released. Limited to com.fromwau, so nothing else can come from a
        // developer's machine.
        mavenLocal {
            mavenContent { includeGroupAndSubgroups("com.fromwau") }
        }
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
include(":app")
