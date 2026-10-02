// A command-line program built from this repository: native executables and a JVM entry point, never
// published. The module names its own entry point and binary.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(libs.findVersion("jdk").get().requiredVersion.toInt())

    jvm()
    linuxX64()
    mingwX64()
    macosArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(libs.findLibrary("kotlin-test").get())
        }
    }
}
