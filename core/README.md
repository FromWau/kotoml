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

KMP consumers put it in `commonMain`. kern's `result`, `kotlinx-serialization-core` and `kotlinx-io-core` come
along as `api`, since `Result`, the `@Serializable` error types and kotlinx-io's `Source` and `Sink` appear in this
module's signatures. The JVM and Android artifacts need a JDK 25 toolchain, and Android needs `minSdk` 26.
Published versions are listed at [maven.frommhund.xyz](https://maven.frommhund.xyz/#/releases/com/fromwau/kotoml).

## Edit a document

```kotlin
val text = "\n\"hello\" = 'toml!' # comment\n['a'.b]\n"
val doc = TomlDocument.parse(text).getOrElse { error(it) }
check(doc.toString() == text)

doc["a"]["b"]["c"] = Value.InlineTable(InlineTable())
doc["a"]["b"]["c"]["d"] = "hello"
// "hello" = 'toml!' # comment
// ['a'.b]
// c = { d = "hello" }
```

An unchanged document prints back byte for byte, and one read without a final newline prints none after an edit
either.

Indexing returns a `Result`: the stored item, or `TomlEditError.NotFound` for a key that is not there.
Indexing on through an error passes it on, so `doc["a"]["b"]` stops at the first key it misses; read the end
with `getOrNull()` or `getOrElse { }`. Writing adds only the key it names, never a missing parent, so create
each level first, as `c` is created above. Where `toml_edit` panics, for example on an index out of bounds or
on writing a key into a string, the call returns a `TomlEditError` instead. Every call that stores something
refuses to nest deeper than `TomlDocument.NESTING_LIMIT` lets the parser read: it returns `TomlEditError.TooDeep`
and stores nothing, as do the `…Of` builders and the conversions to inline content. Its `keyPath` leads from the
document's root to where the content gets too deep, or from the top of what is built when it is stored nowhere yet,
and passes over array elements without a part. The `item["key"] = value` form
drops these errors; call `item.set("key", value)` to see them. Writing never deletes a key; `remove` does.
An item taken before its key is removed, or replaced by `set` or `put`, keeps what it held but no longer belongs
to the document: writing through it changes nothing there, so index again.

## Make and read values

```kotlin
val doc = TomlDocument()
doc["port"] = Value.Integer(8080L, Decor(prefix = " ", suffix = " # default"))
doc["when"] = Datetime.LocalDate(Date.of(1979, 5, 27).getOrElse { error(it) })
// port = 8080 # default
// when = 1979-05-27

val port: Long? = doc["port"].getOrNull()?.longOrNull // 8080
val spelled = TomlDocument.parse("n = 0x10\n").getOrElse { error(it) }["n"].getOrNull()?.valueOrNull as Value.Integer
// spelled.value == 16, spelled.spelling == "0x10"
```

Each kind of value has a constructor, and an optional `Decor` sets the text before and after it; a side left
`null` prints the default spacing for where the value stands. A parsed value keeps the text it was written as
in `spelling`, and `resetFormatting()` drops it. `toString()` gives the value's text alone, without its decor, and
`Value.parse` reads such text back, ignoring whitespace and line breaks around it. A float without a spelling of its
own prints in the shortest digits that read back as it, in exponent form such as `1e300` at 1e16 and above or below
1e-5. The `OrNull` properties read a value of one kind, or `null` for any other kind.

## Tables and arrays

```kotlin
val doc = TomlDocument.parse("[server]\nhosts = [\"alpha\"]\n").getOrElse { error(it) }
doc["server"]["hosts"].getOrNull()?.arrayOrNull?.add("beta")
val server = doc["server"].getOrNull()?.standardTableOrNull!!
server.getOrPut("port") { Item(Value.Integer(8080L)) }
doc["client"] = standardTableOf("retries" to Item(Value.Integer(3L))).getOrElse { error(it) }
// [server]
// hosts = ["alpha", "beta"]
// port = 8080
//
// [client]
// retries = 3
```

`StandardTable`, `InlineTable`, `TomlArray` and `ArrayOfTables` read like Kotlin collections: `size`, `isNotEmpty`,
`keys`, `put`, `getOrPut`, `remove`, `add`, `removeAt`, `retainAll`, `removeAll` and iteration. Removing keeps the
comment and blank lines above what it removes, and takes what belongs to it: the comment on its line, every comment
inside its value, and for a table everything after its first header, the comments above its sub-tables' headers
included. Where a removed table leaves two blank lines meeting, one stays, and none at the start or end of the file.
Replacing a value keeps its spacing and the comment after it. An array of tables left with no tables prints
`key = []`. A new key takes the indentation of the key above it, and an element or key added to an array or
inline table on one line takes the spacing already there. A comment a caller gives an element or an inline table's
value in its decor, such as `Decor(suffix = " # note")`, puts a one-line array or inline table on lines of its own,
one entry per line; in a document that writes TOML 1.0, which has no comments inside an inline table, an inline table's
value drops it. Sorting, by key name with `sort()` or by your own order with `sortBy` and `sortWith`, moves each key
and each table header with the comment lines right above it and leaves blank lines in place.
Making a table dotted, or a header again, keeps the lines above its key and each value's same-line comment and drops
its other comments. An `Item` is a slot that holds a value, a table or an array of tables; `valueOrNull`,
`standardTableOrNull` and their siblings read what it holds, and `tableOrNull` reads a table of either kind. Storing
an item stores a copy, so editing the original afterwards does not change the document; a copy from a place in a
document, this one or another, takes only the comments inside it. A removed item does not carry the lines above it,
which stay in the document, so storing it again prints them once. A table stored under a new key prints right after
its new parent's lines and the tables stored before it there; one stored in place of a table prints where that table
did. A table made in code that holds only tables prints no header of its own, as `[tool]` does not above
`[tool.ruff]`. A table's `position` sets where its header prints among the document's tables. `toStandardTable` and
`makeValue` keep the comments of what they convert. The full set of rules is goal 4 in [GOALS.md](../GOALS.md).

A document's top-level keys live in its `root` table. The document itself offers the map calls (`get`, `set`,
`getOrNull`, `contains`, `keys`, `put`, `putAll`, `getOrPut`, `remove`, `retainAll`, `removeAll`, `clear`,
`size`) and iterates over the root's pairs; everything else a `StandardTable` does, such as `sortBy { (key, _) -> key }`
or `keyOrNull(name)`, goes through `doc.root`. A key read from text knows its `position`, the line and column it
was written at, so a program checking a parsed document can point at the line it complains about.

## Build and edit with blocks

```kotlin
val doc = buildTomlDocument {
    "title" to "kotoml"
    table("server") {
        "port" to 8080
        array("tags") { add("fast") }
    }
    arrayOfTables("bin") {
        table { "name" to "kotoml" }
    }
}.getOrElse { error(it) }

doc.edit {
    table("server") { "port" to 9090 }
    table("database") { "url" to "postgres://" }
}
// title = "kotoml"
//
// [server]
// port = 9090
// tags = ["fast"]
//
// [[bin]]
// name = "kotoml"
//
// [database]
// url = "postgres://"
```

Inside a block a key is written `"key" to value`, and `table`, `inlineTable`, `array` and `arrayOfTables` open the
key's container, creating it when it is missing. Opening a key that holds another kind is
`TomlEditError.TypeMismatch`. `edit` is all or nothing: when it fails the document is left as it was, and when it
succeeds an `Item` taken from the document earlier no longer belongs to it, so index again. Inside a block `to` is
the builder's, so write a `Pair` there as `Pair("a", 1)`.

## Read and write files

```kotlin
val path = Path("config.toml")
val read = SystemFileSystem.source(path).buffered().use { TomlDocument.read(it) }
val doc = when (read) {
    is Result.Success -> read.value
    is Result.Error -> return println(
        when (val error = read.error) {
            is TomlIoError -> "cannot read $path: ${error.detail}"
            is TomlReadError.Parse -> error.error.render(error.text)
        },
    )
}
doc["package"]["version"] = "0.2.0"
SystemFileSystem.sink(path).buffered().use { doc.writeTo(it) }
```

`TomlDocument.read` takes any kotlinx-io `Source` and reads it to the end; `writeTo` takes any `Sink` and flushes
it. Neither closes what it is given. A failure while reading or writing comes back as a `TomlIoError`, and text
that is not TOML as a `TomlReadError.Parse` carrying the text it read, to render the error from. A document holding
half of a surrogate pair, which UTF-8 cannot write, is not written: `writeTo` returns `TomlWriteError.NotUnicode`
naming the key, and the document keeps the text as you gave it. A document that `isDotted` changes made deeper
than `TomlDocument.NESTING_LIMIT` is not written either: `writeTo` returns `TomlWriteError.TooDeep`. Opening the file
is kotlinx-io's own call and throws its `IOException` when the file cannot be opened.

## Handle errors

```kotlin
val text = "a = 1\na = 2\n"
when (val parsed = TomlDocument.parse(text)) {
    is Result.Success -> use(parsed.value)
    is Result.Error -> when (val reason = parsed.error.reason) {
        is TomlParseError.Reason.DuplicateKey -> println("`${reason.keyPath.joinToString(".")}` is defined twice")
        else -> println(parsed.error.render(text))
    }
}
```

Every error type is `@Serializable`, so a program can pass it on as JSON. A `TomlParseError` says what went
wrong as a typed `reason`, where as a `span` of indices into the parsed text and a line and column
`position`, and what would have been accepted there. `render(text)` gives the message as `toml_edit` words it,
with the offending line and a caret under each character of the offending text. A long line is shown only around
the error, about 80 characters with `…` where it is cut, and a control character as an escape such as `\x0D`; the
error itself keeps the full span. A duplicate key names its whole path from the document's root, `a.b.c`.

A `TomlEditError`, a `TomlWriteError` and a `DatetimeParseError` have a `message` that words them for a person, led
by the key path when the error has one: `server: expected a table, found an integer`,
`point: inline tables cannot end with a comma in TOML 1.0`, and for `Date.of(2021, 2, 29)`
`invalid date, expected day between 01 and 28`.

## TOML versions

Parsing follows TOML 1.1 by default. Pass `TomlVersion.V1_0` to `parse` to refuse what 1.1 added: newlines,
comments and a trailing comma in inline tables, the `\x` and `\e` escapes, and times without seconds.

A document is printed and written as the version it was parsed as, `doc.version`, and a new document as TOML 1.1.
So a document parsed as 1.1 lets edits write 1.1-only syntax, such as a time without seconds or an inline table
with a trailing comma, even when the file held none. Set `doc.version` to change what it writes:

```kotlin
val doc = TomlDocument.parse("t = 07:32\n").getOrElse { error(it) }
doc.version = TomlVersion.V1_0
doc.toString() // "t = 07:32:00\n"
```

Under TOML 1.0 a time prints with its seconds and a string or key spelled with `\e` or `\x` prints in its default
spelling. What has no 1.0 form, an inline table across lines, with a comment or with a trailing comma, stays in
the document and prints in `toString()`, but `writeTo` refuses it with `TomlWriteError.NotInToml10`, naming the key
and the feature.

## Differences from `toml_edit`

The types keep upstream's names (`Array` is `TomlArray`), and their members read like Kotlin's collections:
`size` for `len`, `put` and `getOrPut` for `insert` and the entry API, `add` for `push`. A document is a
`TomlDocument` whose `root` is its top-level table. Unchanged text prints back byte for byte, which goes past upstream: line endings are kept per
line, each dotted key and header keeps its own spelling, and dotted keys keep the order they were read in.

The command-line tool [kotoml](../app/README.md) queries TOML files with this module, and runs the official
toml-test suite against it.

## Credits

kotoml began as a port of the toml-rs crates, by their
[contributors](https://github.com/toml-rs/toml/graphs/contributors), from commit
`342362803e41051e522127ca933a19d12a2711ac`; their design and tests are its starting point, and it has since changed
behaviour where it could do better. kotoml is licensed under the [MIT licence](../LICENSE); upstream's
own [MIT](LICENSE-MIT) and [Apache 2.0](LICENSE-APACHE) licences, under which it was ported, are kept here with
their copyright notices.
