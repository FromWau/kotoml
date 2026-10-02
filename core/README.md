# kotoml core

A format-preserving TOML parser and editor. A document reads into a tree you can change, and prints back
with its comments, spacing and spelling as written. It is a port of the Rust crate
[`toml_edit`](https://github.com/toml-rs/toml/tree/main/crates/toml_edit), with the `toml_parser`,
`toml_writer` and `toml_datetime` crates under it. Failures come back as typed errors rather than
exceptions.

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64.

## Add to your build

```kotlin
repositories {
    mavenCentral()
    maven("https://maven.frommhund.xyz/releases")
}

dependencies {
    implementation("com.fromwau.kotoml:core:$kotomlVersion")
}
```

KMP consumers put it in `commonMain`. kern's `result` and `kotlinx-serialization-core` come along as `api`,
since `Result` and the `@Serializable` error types appear in this module's signatures. The JVM and Android
artifacts need a JDK 25 toolchain, and Android needs `minSdk` 26. Published versions are listed at
[maven.frommhund.xyz](https://maven.frommhund.xyz/#/releases/com/fromwau/kotoml).

## Edit a document

```kotlin
val text = "\n\"hello\" = 'toml!' # comment\n['a'.b]\n"
val doc = DocumentMut.parse(text).getOrNull()!!
check(doc.toString() == text)

doc["a"]["b"]["c"]["d"] = value("hello")
doc["a"]["b"]["c"].asInlineTableMut()?.fmt()
// "hello" = 'toml!' # comment
// ['a'.b]
// c = { d = "hello" }
```

Indexing a key that is not there gives an empty item; writing to it adds the key. Where `toml_edit` panics,
for example on an index out of bounds or on writing a key into a string, the call returns a `TomlEditError`
instead. The `item["key"] = value` form drops that error; call `item.set("key", value)` to see it.

## Handle errors

```kotlin
val text = "a = 1\na = 2\n"
when (val parsed = DocumentMut.parse(text)) {
    is Result.Success -> use(parsed.value)
    is Result.Error -> when (val reason = parsed.error.reason) {
        is TomlParseError.Reason.DuplicateKey -> println("`${reason.key}` is defined twice")
        else -> println(parsed.error.render(text))
    }
}
```

Every error type is `@Serializable`, so a program can pass it on as JSON. A `TomlParseError` says what went
wrong as a typed `reason`, where as a byte `span` and a line and column
`position`, and what would have been accepted there. `render(text)` gives the message as `toml_edit` words it,
with the offending line and a caret under it.

## TOML versions

Parsing follows TOML 1.1 by default. Pass `TomlVersion.V1_0` to `parse` to refuse what 1.1 added: newlines,
comments and a trailing comma in inline tables, the `\x` and `\e` escapes, and times without seconds.

## Differences from `toml_edit`

The API keeps upstream's names in Kotlin casing, with a few renames where a name clashes in Kotlin (`Array`
is `TomlArray`). Unchanged text prints back byte for byte, which goes past upstream: line endings are kept per
line, each dotted key and header keeps its own spelling, and dotted keys keep the order they were read in.
[PARITY.md](PARITY.md) lists every public item, every ported test, and every intended difference.

The command-line tool [kotoml](../app/README.md) queries TOML files with this module, and runs the official
toml-test suite against it.

## Credits

The design, the behaviour and the tests are those of the toml-rs crates, by their
[contributors](https://github.com/toml-rs/toml/graphs/contributors), ported from commit
`342362803e41051e522127ca933a19d12a2711ac`. kotoml is licensed under the [MIT licence](../LICENSE); upstream's
own [MIT](LICENSE-MIT) and [Apache 2.0](LICENSE-APACHE) licences, under which it was ported, are kept here with
their copyright notices.
