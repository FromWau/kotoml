plugins {
    id("kotoml-module")
    alias(libs.plugins.kotlinSerialization)
}

description = "Maps @Serializable Kotlin types to TOML and back, and updates a TOML document in place."

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
        // A format implements Encoder and Decoder, parts of which kotlinx-serialization still marks experimental.
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(libs.kotlinx.serialization.core)
            // Read and written natively, but no kotlinx-datetime type appears in this module's signatures.
            implementation(libs.kotlinx.datetime)
        }

        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
