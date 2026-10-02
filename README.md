# kotoml

A format-preserving TOML parser and editor for Kotlin Multiplatform. A document reads into a tree you can change,
and prints back with its comments, spacing and spelling as written. Failures are typed, serializable errors, never
exceptions. It is a port of the Rust crates behind [`toml_edit`](https://github.com/toml-rs/toml), and passes the
official [toml-test](https://github.com/toml-lang/toml-test) suite in full for TOML 1.0 and 1.1.

## Modules

| module                         | what it is                                                                    |
|--------------------------------|-------------------------------------------------------------------------------|
| [`core`](core/README.md)       | the parser, the editable document model, and TOML writing                     |
| [`app`](app/README.md)         | the `kotoml` command line: query TOML like jq, and toml-test's decoder/encoder |

```kotlin
implementation("com.fromwau.kotoml:core:$kotomlVersion")
```

Published versions are listed at [maven.frommhund.xyz](https://maven.frommhund.xyz/#/releases/com/fromwau/kotoml),
which reads without credentials.

```kotlin
val doc = DocumentMut.parse("[package]\nname = \"kotoml\" # the name\n").getOrNull()!!
doc["package"]["version"] = value("0.1.0")
doc.toString() // "[package]\nname = \"kotoml\" # the name\nversion = \"0.1.0\"\n"
```

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64.

`./gradlew check` validates everything, the official toml-test suite included, judged by toml-test's own
runner; that runner is built with [Go](https://go.dev/dl/), which `check` needs on the `PATH`. The suite is a git
submodule: clone with `--recurse-submodules`, or run `git submodule update --init` after cloning.

Licensed under [MIT](LICENSE). The design and the tests come from the toml-rs crates; see
[core's credits](core/README.md#credits).
