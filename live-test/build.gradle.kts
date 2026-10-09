// Tests of the kotoml executable itself, as a user's shell runs it; `check` runs them like any other tests.

plugins {
    id("kotoml-kotlin")
}

description = "Runs the kotoml executable built for this machine in each installed shell."

val kotomlBinary = configurations.dependencyScope("kotomlBinary")
val kotomlBinaryFile = configurations.resolvable("kotomlBinaryFile") {
    extendsFrom(kotomlBinary.get())
    // The category :app gives its executable, which picks that variant out of all :app publishes.
    attributes { attribute(Category.CATEGORY_ATTRIBUTE, objects.named("kotoml-executable")) }
}

dependencies {
    kotomlBinary(project(":app"))
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()

    sourceSets {
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

tasks.named<Test>("jvmTest") {
    // An input, so the tests rerun whenever the executable changes and not only when they do.
    val binary: FileCollection = kotomlBinaryFile.get()
    inputs.files(binary).withPropertyName("kotomlBinary").withPathSensitivity(PathSensitivity.NONE)
    jvmArgumentProviders += CommandLineArgumentProvider { listOf("-Dkotoml.binary=${binary.singleFile.absolutePath}") }
}
