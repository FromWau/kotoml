plugins {
    id("kotoml-module")
    alias(libs.plugins.kotlinSerialization)
}

description = "A format-preserving TOML parser and editor, ported from toml_edit."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kern.result)
            implementation(libs.kern.fuzzy)
            // The error types are @Serializable, so a program can hand them on as data.
            api(libs.kotlinx.serialization.core)
            // Source and Sink appear in TomlDocument's read and write signatures.
            api(libs.kotlinx.io.core)
        }

        commonTest.dependencies {
            implementation(libs.kern.result.test)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
