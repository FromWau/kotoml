plugins {
    id("kotoml-app")
    alias(libs.plugins.kotlinSerialization)
}

description = "kotoml: query TOML files like jq, and the toml-test decoder and encoder for kotoml."

val entryPackage = "com.fromwau.kotoml.app"

// Generated so `--version` and the package version both come from the catalogue's one entry.
val versionSource = tasks.register("versionSource") {
    description = "Generates the VERSION constant from kotomlVersion in the version catalogue."
    group = LifecycleBasePlugin.BUILD_GROUP

    val version = libs.versions.kotomlVersion.get()
    val outputDir = layout.buildDirectory.dir("generated/version/kotlin")
    inputs.property("version", version)
    outputs.dir(outputDir)

    doLast {
        val target = outputDir.get().asFile.resolve("com/fromwau/kotoml/app/Version.kt")
        target.parentFile.mkdirs()
        target.writeText(
            """
            package com.fromwau.kotoml.app

            /** Generated from `kotomlVersion` in `gradle/libs.versions.toml`. */
            internal const val VERSION: String = "$version"

            """.trimIndent(),
        )
    }
}

kotlin {
    jvm {
        mainRun { mainClass.set("$entryPackage.MainKt") }
    }

    listOf(linuxX64(), mingwX64(), macosArm64()).forEach { target ->
        target.binaries.executable {
            entryPoint = "$entryPackage.main"
            baseName = "kotoml"
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(versionSource)
        }

        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.klap)
            implementation(libs.kern.dirs)
            implementation(libs.kern.fs)
            implementation(libs.kotlinx.io.core)
        }

        commonTest.dependencies {
            implementation(libs.kern.result.test)
            implementation(libs.kern.terminal.test)
        }
    }
}

// --- The official toml-test suite, judged by its own runner against this machine's native binary ---

val hostOs: String = System.getProperty("os.name").lowercase()
val hostTarget = when {
    hostOs.startsWith("linux") -> "linuxX64"
    hostOs.startsWith("mac") -> "macosArm64"
    hostOs.startsWith("windows") -> "mingwX64"
    else -> error("there is no kotoml binary for $hostOs")
}
val onWindows = hostTarget == "mingwX64"
val hostTask = hostTarget.replaceFirstChar(Char::uppercase)
val hostExecutable = "kotoml.${if (onWindows) "exe" else "kexe"}"
val linkHostBinary = "linkReleaseExecutable$hostTask"
val hostBinary = layout.buildDirectory.file("bin/$hostTarget/releaseExecutable/$hostExecutable")

// The runner and the suite it embeds come from the submodule, so the build judges against the pinned commit.
val tomlTestSources = rootProject.layout.projectDirectory.dir("toml-test")
val tomlTestRunner = layout.buildDirectory.file("toml-test/toml-test${if (onWindows) ".exe" else ""}")

val buildTomlTestRunner = tasks.register<Exec>("buildTomlTestRunner") {
    description = "Builds toml-test's official runner from the toml-test submodule; needs Go."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    inputs.dir(tomlTestSources).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(tomlTestRunner)
    workingDir(tomlTestSources)
    commandLine("go", "build", "-o", tomlTestRunner.get().asFile.absolutePath, "./cmd/toml-test")
}

fun registerTomlTest(spec: String) = tasks.register<Exec>("tomlTest${spec.replace(".", "")}") {
    description = "Runs the official toml-test suite for TOML $spec against the native kotoml binary."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(buildTomlTestRunner, linkHostBinary)
    // toml-test splits -decoder and -encoder on whitespace, so they name the binary from its own directory.
    val binary = hostBinary.get().asFile
    workingDir(binary.parentFile)
    commandLine(
        tomlTestRunner.get().asFile.absolutePath,
        "test",
        "-toml",
        spec,
        "-decoder=./${binary.name} decode --toml $spec",
        "-encoder=./${binary.name} encode",
    )
}

val tomlTestRuns = listOf(registerTomlTest("1.0"), registerTomlTest("1.1"))

val tomlTest = tasks.register("tomlTest") {
    description = "Runs the official toml-test suite for TOML 1.0 and 1.1 against the native kotoml binary."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(tomlTestRuns)
}

tasks.named("check") { dependsOn(tomlTest) }

// --- The debug executable for this machine, which :live-test runs the way a user's shell does ---

// Its own category, so a project that depends on :app for its classes never selects the executable, and :live-test
// selects nothing else.
val hostDebugBinary = configurations.consumable("hostDebugBinary") {
    attributes { attribute(Category.CATEGORY_ATTRIBUTE, objects.named("kotoml-executable")) }
}
artifacts {
    add(hostDebugBinary.name, layout.buildDirectory.file("bin/$hostTarget/debugExecutable/$hostExecutable")) {
        builtBy("linkDebugExecutable$hostTask")
    }
}
