// Compiler settings every kotoml module shares, library or program.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
        // The store types are @MustUseReturnValues: core compiles them only with the checker on, and with it an
        // ignored store result warns in every module.
        freeCompilerArgs.add("-Xreturn-value-checker=check")
    }
}
