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

doc["a"]["b"]["c"]["d"] = "hello"
doc["a"]["b"]["c"].inlineTableOrNull?.resetFormatting()
// "hello" = 'toml!' # comment
// ['a'.b]
// c = { d = "hello" }
```

Indexing a key that is not there gives an empty item; writing to it adds the key. Where `toml_edit` panics,
for example on an index out of bounds or on writing a key into a string, the call returns a `TomlEditError`
instead. The `item["key"] = value` form drops that error; call `item.set("key", value)` to see it.

## Make and read values

```kotlin
val doc = DocumentMut()
doc["port"] = Value.Integer(8080L, Decor(prefix = " ", suffix = " # default"))
doc["when"] = Datetime(date = Date(year = 1979, month = 5, day = 27))
// port = 8080 # default
// when = 1979-05-27

val port: Long? = doc["port"].longOrNull // 8080
val spelled = DocumentMut.parse("n = 0x10\n").getOrNull()!!["n"].valueOrNull as Value.Integer
// spelled.value == 16, spelled.spelling == "0x10"
```

Each kind of value has a constructor, and an optional `Decor` sets the text before and after it; a side left
`null` prints the default spacing for where the value stands. A parsed value keeps the text it was written as
in `spelling`, and `resetFormatting()` drops it. The `OrNull` properties read a value of one kind, or `null`
for any other kind.

## Tables and arrays

```kotlin
val doc = DocumentMut.parse("[server]\nhosts = [\"alpha\"]\n").getOrNull()!!
doc["server"]["hosts"].arrayOrNull?.add("beta")
val server = doc["server"].tableOrNull!!
server.getOrPut("port") { Item(Value.Integer(8080L)) }
doc["client"] = tableOf("retries" to Item(Value.Integer(3L)))
// [server]
// hosts = ["alpha", "beta"]
// port = 8080
//
// [client]
// retries = 3
```

`Table`, `InlineTable`, `TomlArray` and `ArrayOfTables` read like Kotlin collections: `size`, `put`, `getOrPut`,
`remove`, `add`, `removeAt`, `retainAll` and iteration. An `Item` is a slot that holds a value, a table or an
array of tables; `valueOrNull`, `tableOrNull` and their siblings read what it holds. Storing an item stores a
copy, so editing the original afterwards does not change the document.

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

The types keep upstream's names (`Array` is `TomlArray`), and their members read like Kotlin's collections:
`size` for `len`, `put` and `getOrPut` for `insert` and the entry API, `add` for `push`. Unchanged text prints back byte for byte, which goes past upstream: line endings are kept per
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
