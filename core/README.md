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

doc["a"]["b"].set("c", Value.InlineTable(InlineTable()))
doc["a"]["b"]["c"].set("d", "hello")
// "hello" = 'toml!' # comment
// ['a'.b]
// c = { d = "hello" }
```

An unchanged document prints back byte for byte, and one read without a final newline prints none after an edit
either.

Indexing returns a `Result` on every container: the stored item, `TomlEditError.NotFound` for a key that is not
there, or `TomlEditError.IndexOutOfBounds` for a position past the end; `getOrNull(key)` and `getOrNull(index)` give
`null` instead. Indexing on through an error passes it on, so `doc["a"]["b"]` stops at the first key it misses; read
the end with `getOrNull()` or `getOrElse { }`. Writing adds only the key it names, never a missing parent, so create
each level first, as `c` is created above. Where `toml_edit` panics, for example on an index out of bounds or
on writing a key into a string, the call returns a `TomlEditError` instead. Every call that stores something
refuses to nest deeper than `TomlDocument.NESTING_LIMIT` lets the parser read: it returns `TomlEditError.TooDeep`
and stores nothing, as do the `…Of` builders and the conversions to inline content. Its `keyPath` leads from the
document's root to where the content gets too deep, or from the top of what is built when it is stored nowhere yet,
and passes over array elements without a part. Every store returns a `Result`, as every edit does; see
[Handle errors](#handle-errors). Writing never deletes a key; `remove` does. An item taken before its key is removed,
or replaced by `set` or `put`, keeps what it held but no longer belongs to the document: writing through it changes
nothing there, so index again.

## Make and read values

```kotlin
val doc = TomlDocument()
doc.set("port", Value.Integer(8080L, Decor(prefix = " ", suffix = " # default")))
doc.set("when", Datetime.LocalDate(Date.of(1979, 5, 27).getOrElse { error(it) }))
// port = 8080 # default
// when = 1979-05-27

val port: Long? = doc["port"].getOrNull()?.longOrNull // 8080
val spelled = TomlDocument.parse("n = 0x10\n").getOrElse { error(it) }["n"].getOrNull()?.valueOrNull as Value.Integer
// spelled.value == 16, spelled.spelling == "0x10"
```

Each kind of value has a constructor, and an optional `Decor` sets the text before and after it; a side left
`null` prints the default spacing for where the value stands. A parsed value keeps the text it was written as
in `spelling`, which `format()` keeps. `toString()` gives the value's text alone, without its decor, and
`Value.parse` reads such text back, ignoring whitespace and line breaks around it. A float without a spelling of its
own prints in the shortest digits that read back as it, in exponent form such as `1e300` at 1e16 and above or below
1e-5. The `OrNull` properties read a value of one kind, or `null` for any other kind. Every call that stores an
integer takes an `Int` as well as a `Long`. An array's and an inline table's `value` is the container it wraps, as a
scalar's `value` is the scalar. A line break inside a multi-line string reads as `\n`, also where the file's lines end
in `\r\n`, and a multi-line string without a spelling of its own breaks its lines as the document's lines end: the
document it is stored in, or the one it was taken out of (see [Items and their comments](#items-and-their-comments)),
and with `\n` when built in code. A value's `displaySpelling` and `toString()` are the text its document prints.

## Tables and arrays

```kotlin
val doc = TomlDocument.parse("[server]\nhosts = [\"alpha\"]\n").getOrElse { error(it) }
doc["server"]["hosts"].getOrNull()?.arrayOrNull?.add("beta")
val server = doc["server"].getOrNull()?.standardTableOrNull!!
server.getOrPut("port") { Item(Value.Integer(8080L)) }
doc.set("client", standardTableOf("retries" to Item(Value.Integer(3L))).getOrElse { error(it) })
// [server]
// hosts = ["alpha", "beta"]
// port = 8080
//
// [client]
// retries = 3
```

`StandardTable`, `InlineTable`, `TomlArray` and `ArrayOfTables` read like Kotlin collections: `size`, `isNotEmpty`,
`keys`, `put`, `getOrPut`, `remove`, `add`, `removeAt`, `retainAll`, `removeAll`, `clear` and iteration. Removing
takes what belongs to the item: the comment lines right above it with no blank line between, the comment on its line,
every comment inside its value, and for a table everything after its first header, the comments above its
sub-tables' headers included. Comment lines set apart from it by a blank line stay where they print. A removal that
would take a comment out of the document returns `TomlEditError.CommentsWouldBeLost` and changes nothing, unless
called with `force = true`; `retainAll`, `removeAll` and `clear` remove all the items they pick or none. Removing a
key that is not there returns `NotFound`, an index past the end `IndexOutOfBounds`. Where a removal leaves two blank
lines meeting, one stays, and none at the start or end of the file.

```kotlin
val doc = TomlDocument.parse("# free\n\n# about x\nx = 1 # one\ny = 2\n").getOrElse { error(it) }
doc.remove("x") // Err(CommentsWouldBeLost(keyPath = [x], count = 2)), nothing changed
doc.remove("x", force = true) // Ok(item)
// # free
//
// y = 2
```

Replacing an item keeps its spacing and its comments: the comment lines right above it, the one after it, for a table
those of its header, and for an element or an inline table's entry the one after its comma. The comments the new item
brings from where it was read fill only the places where the item it replaces has none; each place counts on its own.
A comment you give in its decor, such as `Decor(suffix = " # new")`, takes the place of the old one there.

```kotlin
val doc = TomlDocument.parse("# about x\nx = 1 # one\nz = 0\nport = 1 # comment\n").getOrElse { error(it) }
val other = TomlDocument.parse("# about y\ny = 5 # five\n").getOrElse { error(it) }
doc.set("x", other["y"].getOrElse { error(it) }) // # about x\nx = 5 # one
doc.set("z", other["y"].getOrElse { error(it) }) // # about y\nz = 5 # five
doc.set("port", Value.Integer(2L, Decor(prefix = " ", suffix = " # new"))) // port = 2 # new
```

An array of tables left with no tables prints
`key = []` below the comment lines that stood above its first header, which stay since the array does. A new key
takes the indentation of the key above it, and an element or key added to an array or inline table on one line takes
the spacing already there. A comment an element or an inline table's value brings, read with it or given in its
decor, such as `Decor(suffix = " # note")`, puts a one-line array or inline table on lines of its own, one entry per
line, whether the entry is added, inserted at an index or replaces another. A comment given in its decor takes the
place of the one that stood after the entry it replaces, one read with it only fills a place without one, and the new
lines end as the document's do. An element or entry read from
brackets or braces across lines brings the comment after its comma, its same-line comment, wherever it is stored,
also when `removeAt` or `remove` hands it back. Comment lines between an element and its comma stay between them
wherever the element goes, and on a line of a table, dotted keys' included, they print above its key, so they still
belong to it. TOML 1.0 has no comments inside an inline table, so there the comments a
value brings have no place: `put`, `putAll`, `getOrPut`, `putItem` and `Item.assign` return
`TomlEditError.CommentsWouldBeLost` and change nothing, unless called with `force = true`, which stores the value
without them.

Sorting, by key name with `sort()` or by your own order with `sortBy` and `sortWith`, on a table, an inline table or an
array, moves each item with its comments: the comment lines directly above it, the comment on its line, after its comma
in braces or brackets, and for a table its header comment and the tables under it. The comment lines directly above
dotted keys' first line, such as `# about b` above `b.d = 1`, stay above their first line once `b.c` sorts before it.
Comment lines a blank line sets apart from the item below them, the blank lines themselves and the comment after `[`
or `{` stay in place. Storing an array of tables in place of the one its tables were read from, such as
`doc.set("a", arr.reversed().toArrayOfTables())`, reorders the tables the same way.

```kotlin
val doc = TomlDocument.parse("# first\n[[a]] # h1\nn = 1\n\n# second\n[[a]] # h2\nn = 2\n").getOrElse { error(it) }
doc.set("a", doc["a"].getOrNull()?.arrayOfTablesOrNull!!.reversed().toArrayOfTables().getOrElse { error(it) })
// # second
// [[a]] # h2
// n = 2
//
// # first
// [[a]] # h1
// n = 1
```

```kotlin
val doc = TomlDocument.parse("v = 1 # one\nt = { a = 1 }\n", TomlVersion.V1_0).getOrElse { error(it) }
val t = doc["t"].getOrNull()?.inlineTableOrNull!!
val v = doc["v"].getOrNull()?.valueOrNull!!
t.put("v", v) // Err(CommentsWouldBeLost(keyPath = [t, v], count = 1)), nothing changed
t.put("v", v, force = true)
// v = 1 # one
// t = { a = 1, v = 1 }
```

```kotlin
val doc = TomlDocument.parse("# db\n[old] # main\nurl = 1\n\n[cache]\n[old.pool]\nsize = 2\n").getOrElse { error(it) }
doc.rename("old", "db")
// # db
// [db] # main
// url = 1
//
// [cache]
// [db.pool]
// size = 2
```

`rename(old, new)`, on a `StandardTable`, an `InlineTable`, the document and an `Item` holding a table of either
kind, gives a key a new name and keeps everything else: its place, the comment lines above it, its same-line comment,
its spacing and its item, so an `Item` taken from it before stays in the document. The new name is spelled as a new
key is, bare when it can be and quoted when it must be. A table renames the headers of the tables under it where they
print, `[old.pool]` becoming `[db.pool]` with its comments; an array of tables renames every `[[old]]` header, and
dotted keys every line they start, `old.a = 1` becoming `new.a = 1`. A name that is not there is
`TomlEditError.NotFound`, and one another key already holds is `TomlEditError.KeyTaken`; either changes nothing, as
renaming a key to its own name does.

A table prints in one of four layouts, the `TableLayout` objects: `Header` (`[t]`), `Dotted` (`t.a = 1`), `Inline`
(`t = { a = 1 }`) and `MultilineInline`, one entry per line between `{` and `}`, the last with a comma (which only
TOML 1.1 reads). `layout` reads it, on a `StandardTable`, an `InlineTable` or the `Item` holding either, where it is
null when the slot holds no table. `Item.setLayout(layout, force = false)` changes it. A change that would drop a
comment changes nothing and returns `TomlEditError.CommentsWouldBeLost` with the key path and the number of comments;
pass `force = true` to make it and drop them. Blank lines between the entries stay wherever the new layout has lines
for them, under a header, as dotted keys or in braces across lines, and a free comment keeps its place among the
lines around it; on one line blank lines go, and none is left at the end of the file. Asking for the layout a table
already has changes nothing.

```kotlin
val doc = TomlDocument.parse("[t] # t\na = 1 # one\n").getOrElse { error(it) }
val slot = doc["t"].getOrElse { error(it) }
slot.setLayout(TableLayout.Inline) // Err(CommentsWouldBeLost(keyPath = [t], count = 1)), nothing changed
slot.setLayout(TableLayout.MultilineInline) // keeps every comment
// t = { # t
//   a = 1, # one
// }
slot.setLayout(TableLayout.Inline, force = true)
// t = { a = 1 } # t
```

`Header` and `MultilineInline` reach the tables inside: with `Header` every inline table inside becomes a table under
its own header (`[t.sub]`) and every array of only inline tables an array of tables (`[[t.list]]`); with
`MultilineInline` every table inside goes in braces across lines, indented from the line holding `{` and ending as the
document's lines do, and every comment stays. `Inline` puts the table and the tables inside on one line, so the
comments inside the braces go, the header comments of the tables inside too, while the table's header comment, the
one after `[t]` or after `{`, prints after `}`, or after the table's comma in an array or an inline table across lines.
A comment already there stays, and the header comment goes. `Dotted` keeps the lines above the table's key and each
value's same-line comment, and drops its header comment, the comment after its braces and the comment lines inside
it; the tables under it keep their own layouts. Dotted keys made a `Header` put the comment lines above their first
line above the header and keep the comment lines between their lines. A table stays the same `StandardTable` while
it changes between `Header` and `Dotted`, and the same `InlineTable` between the inline layouts. On an array of
tables, or on an array of only inline tables, `Header` and the inline layouts change every table in it. A slot in an
array or an inline table holds only values, so it takes no `Header`, and a slot in an array takes no `Dotted` either.
`StandardTable.isImplicit` reads whether a header is hidden while the table holds no values of its own, and
`setImplicit` changes it; it cannot fail. An `Item` is a slot that holds a value, a table or an array of tables;
`valueOrNull`, `standardTableOrNull` and their siblings read what it holds, and `tableOrNull` reads a table of either
kind; how items carry comments when stored, copied or removed is under
[Items and their comments](#items-and-their-comments). A table stored under a new key prints right after its new
parent's lines and the tables stored before it there; one stored in place of a table prints where that table did. A
table made in code that holds only tables prints no header of its own, as `[tool]` does not above `[tool.ruff]`. A
table's `position` sets where its header prints among the document's tables.

A table put under a header prints where a table stored at that key would, keeping every comment;
`toInlineTable` and `toStandardTable` build the converted table without touching the document. The full set of rules
is goal 4 in [GOALS.md](../GOALS.md).

An array prints on one line or across lines. `TomlArray.isMultiline` reads whether a line break stands between its
brackets outside its elements, so `[[1,\n2]]` is on one line while the array inside it is not, and
`setMultiline(value, force = false)` changes it for the array itself, the arrays and inline tables inside keeping
theirs. Across lines each element takes a line of its own, indented two spaces more than the line holding `[`, with a
comma after each and `]` on a line of its own, the new lines ending as the document's do. On one line, `[1, 2]`, the
comments between the brackets have no place, so the change returns `TomlEditError.CommentsWouldBeLost` and changes
nothing unless forced; forced, they go and the comment after `]` stays. Asking for the layout an array has changes
nothing.

```kotlin
val doc = TomlDocument.parse("a = [\n  1, # one\n  2,\n] # a\n").getOrElse { error(it) }
val array = doc["a"].getOrNull()?.arrayOrNull!!
array.setMultiline(false) // Err(CommentsWouldBeLost(keyPath = [a], count = 1)), nothing changed
array.setMultiline(false, force = true)
// a = [1, 2] # a
array.setMultiline(true)
// a = [
//   1,
//   2,
// ] # a
```

A document's top-level keys live in its `root` table. The document itself offers the map calls (`get`, `set`,
`getOrNull`, `contains`, `keys`, `put`, `putAll`, `getOrPut`, `remove`, `rename`, `retainAll`, `removeAll`,
`clear`, `size`) and iterates over the root's pairs; everything else a `StandardTable` does, such as
`sortBy { (key, _) -> key }` or `keyOrNull(name)`, goes through `doc.root`. A key read from text knows its
`position`, the line and column it was written at, so a program checking a parsed document can point at the line it
complains about.

## Format

```kotlin
val doc = TomlDocument.parse("#top\n  name   =   'kotoml'    #the name\nlist = [ 1 ,2, ]\n\n\n[ server ]\n").getOrElse {
    error(it)
}
doc.format()
// # top
// name = 'kotoml' # the name
// list = [1, 2]
//
// [server]
```

`format()` tidies a document in place, or the part of it under a `StandardTable`, an `InlineTable`, a `TomlArray`,
an `ArrayOfTables`, an `Item` or an array or inline table `Value`, leaving the rest of the document as it is. A key
prints bare when it can, `'q'` as `q`; `=` takes one space each side, a comma one space after it and none before,
a header no space inside its brackets, and an inline table on one line one space inside its braces. A comment takes
one space after `#` and one before it on a line with a value. Each header has one blank line above it and the comment
lines right above it, unless it opens the file; a run of blank lines becomes one, and none open or end the file.
Indentation goes, but inside an array or inline table across lines, whose lines are indented two spaces more than
the line holding `[` or `{`, with the closing bracket on a line of its own. An array or inline table on one line drops
the comma after its last element, and one across lines ends in one: an array always, an inline table in TOML 1.1.

It keeps every comment, how each line ends, and the spelling of every value, such as `'lit'`, `0x1F90` or `1e0`.
Layouts stay: a header stays a header, dotted keys stay dotted, and an array or inline table stays on one line or
across lines. Every table and value stays the same object, but for a slot whose text reads back as another kind,
such as an empty dotted table printing `key = {}` or a dotted inline table printing on a table's lines, which then
holds what reading its text gives. `format()` cannot fail. On a `Key` it makes the key bare when it can be and
drops the spacing around its dots and before `=`.

```kotlin
val doc = TomlDocument.parse("port = 0x1F90 # http\nname = 'kotoml'\nat = [07:32, 1e0]\n").getOrElse { error(it) }
doc.respell()
// port = 8080 # http
// name = "kotoml"
// at = [07:32:00, 1.0]
```

`respell()` changes the spellings `format()` keeps: on the same receivers and on a `Value`, it writes every value
under it in its default spelling, the one a value made in code prints in, such as `0x1F90` as `8080`, `'kotoml'` as
`"kotoml"`, `1e0` as `1.0` and `07:32` as `07:32:00`, the time then holding seconds of 0. Keys keep their spelling,
and comments, layouts, spacing and line endings stay. A default spelling is one TOML 1.0 reads, so a document of
TOML 1.0 stays one. `respell()` cannot fail. To put an array on one line or across lines, see `setMultiline` under
[Tables and arrays](#tables-and-arrays).

## Choose spellings and comments

```kotlin
val doc = TomlDocument()
doc.set("license", Value.parse("'MIT'").getOrElse { error(it) })
doc.set("mode", Value.parse("0o755").getOrElse { error(it) })
doc.root.keyOrNull("mode")?.leafDecor = Decor(prefix = "# file mode\n")
doc.set("port", Value.Integer(8080, Decor(suffix = " # default")))
// license = 'MIT'
// # file mode
// mode = 0o755
// port = 8080 # default
```

A value made by `Value.parse` keeps the text it was parsed from, so `Value.parse("'MIT'")` stores a literal string
and `Value.parse("0o755")` an octal integer; `format()` keeps such spellings and `respell()` writes the default ones.
A value's `decor` is the text around it, its `suffix` the spacing and comment after it on its line. A key's
`leafDecor` is the text around it where it ends a line or a header: its `prefix` holds the comment lines above the
key and the spacing before it, its `suffix` the spacing before `=`. `dottedDecor` is the text around one part of a
dotted key. A table's `decor` is the text around its header: the comment and blank lines above `[t]`, and what
follows the header on its line. `keyOrNull(name)` reads a key of a table.

## Items and their comments

```kotlin
val text = "# Generated\n\n# about db\n[db] # primary\nurl = \"x\" # local\n"
val src = TomlDocument.parse(text).getOrElse { error(it) }
val dst = TomlDocument()
dst.set("db", src["db"].getOrElse { error(it) }) // prints as storing its copy() would
// # about db
// [db] # primary
// url = "x" # local
```

An item handed out by indexing, and the comments that go with it, follow five rules:

1. **Reading gives a live view.** `doc["a"]` is the slot stored in the document: writing through it, its value's
   `decor` included, changes the document.
2. **Storing stores a copy that takes the item's comments.** An item read from a document, this one or another, is
   stored with the comments that belong to it: the comment lines right above it, with no blank line between, and its
   same-line comment, for a table those of its header, and every comment inside it, blank lines included. Comment
   lines set apart from it by a blank line belong to no item and stay in its source. Stored in place of another item,
   it keeps the comments of that slot, and its own fill only the places where the slot has none. Where the place
   cannot hold its comments, the store returns `CommentsWouldBeLost` and changes nothing, unless forced. Editing the
   original afterwards does not change the copy.
3. **`copy()` is exactly what a store would store.** With `val db = src["db"].getOrElse { error(it) }`,
   `dst.set("db", db.copy())` prints the same as `dst.set("db", db)`, in another document or the same one. The same
   holds for the `copy()` of what an item holds: `db.standardTableOrNull!!.copy()` stores as `db.copy()` does, and
   so do the copies of an `InlineTable`, a `TomlArray`, an `ArrayOfTables` and a `Value`, though the comment lines
   above a value's key belong to its key's line, which only the item brings. A `TomlDocument.copy()` is a whole
   document and keeps every comment and its version.
4. **Removing is a move.** `remove` and `removeAt` take the item's comments with it: the comment lines right above
   it, its same-line and header comment, and every comment inside it. When that would take a comment out of the
   document they return `CommentsWouldBeLost` and change nothing, unless called with `force = true`. The item
   `remove` returns from a document or a table keeps the comments it took and stores with them. Comment lines set
   apart from it by a blank line stay in the document it left. A comment a caller set
   through `decor` is the item's own too, so it travels with every store and copy.
5. **A replaced or removed item is detached.** It keeps what it held, but writing through it no longer changes the
   document; index again. It prints, and takes edits, as in the document it left, with that document's line ending
   and TOML version, and so does a `copy()`; stored again, it takes those of the document it is stored in.

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
doc["package"].set("version", "0.2.0")
SystemFileSystem.sink(path).buffered().use { doc.writeTo(it) }
```

`TomlDocument.read` takes any kotlinx-io `Source` and reads it to the end; `writeTo` takes any `Sink` and flushes
it. Neither closes what it is given. A failure while reading or writing comes back as a `TomlIoError`, and text
that is not TOML as a `TomlReadError.Parse` carrying the text it read, to render the error from. A document holding
half of a surrogate pair, which UTF-8 cannot write, is not written: `writeTo` returns `TomlWriteError.NotUnicode`
naming the key, and the document keeps the text as you gave it. A document that dotted keys made deeper
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

Every edit, such as `set`, `put`, `assign` or `remove`, returns a `Result` holding a `TomlEditError` when it fails,
and a failed edit changes nothing. The edit types are marked `@MustUseReturnValues`, so with Kotlin's checker on
(`-Xreturn-value-checker=check`) the compiler warns when such a `Result` is ignored. `doc["t"].set("v", value)`
stores under the item indexing reached and passes on the indexing's error, such as `NotFound` for a missing parent.

```kotlin
val doc = TomlDocument.parse("[server]\nport = 8080\n").getOrElse { error(it) }
doc["server"].set("port", 9090L) // Ok
doc["database"].set("url", "postgres://") // Err(NotFound("database")), nothing changed
```

A `TomlEditError`, a `TomlWriteError` and a `DatetimeParseError` have a `message` that words them for a person, led
by the key path when the error has one: `server: expected a table, found an integer`,
`point: inline tables cannot end with a comma in TOML 1.0`, and for `Date.of(2021, 2, 29)`
`invalid date: day 29 is out of range 01 to 28`.

## TOML versions

Parsing follows TOML 1.1 by default. Pass `TomlVersion.V1_0` to `parse` to refuse what 1.1 added: newlines,
comments and a trailing comma in inline tables, the `\x` and `\e` escapes, and times without seconds.

A document holds TOML of a version, `doc.version`: the one it was parsed as, and TOML 1.1 for a new document. So a
document parsed as 1.1 lets edits add 1.1-only syntax, such as a string spelled with `\e` or an inline table with a
trailing comma, even when the file held none. In a document of TOML 1.0 such an edit fails with
`TomlEditError.NotInToml10`, naming the key and what only 1.1 reads, and changes nothing: storing a key or string
spelled with `\e` or `\x` or an inline table across lines or with a trailing comma, `setLayout(MultilineInline)` and
`setTrailingComma(true)`. A time without seconds is stored with seconds of 0, so it reads back `second == 0` and
prints with `:00`, which TOML 1.0 reads. So a 1.0 document's `toString()` and `writeTo` always read back as TOML 1.0.

`doc.setVersion(version)` changes the version. To TOML 1.0 it converts the document: a time without seconds takes
`:00`, a `\e` or `\x` escape in a key or string becomes the `\u` escape for it, an inline table across lines goes on
one line and an inline table's trailing comma goes. Tables and values stay the same objects. Putting an inline table
on one line keeps its header comment after `}` as `Inline` does and drops the other comments inside it, so then
`setVersion` fails with `TomlEditError.CommentsWouldBeLost` and changes nothing, unless called with `force = true`.
To TOML 1.1 nothing changes but the version.

```kotlin
val doc = TomlDocument.parse("t = 07:32\ns = \"\\e\"\n").getOrElse { error(it) }
doc.setVersion(TomlVersion.V1_0)
doc.toString() // "t = 07:32:00\ns = \"\\u001B\"\n"
```

A table, array, inline table or value prints in its own `toString()` as its document prints it, in the document's
version. One stored nowhere keeps the version of the document it was taken out of, by `remove`, a replace or `copy()`,
for printing and for edits: a table removed from a TOML 1.0 document refuses `setTrailingComma(true)` and gives a time
stored in it seconds of 0, until it is stored in a TOML 1.1 document. `TomlDocument(table)` holds that version too. A
table or value built in code, or read by `Value.parse`, is TOML 1.1.

## Differences from `toml_edit`

The types keep upstream's names (`Array` is `TomlArray`), and their members read like Kotlin's collections:
`size` for `len`, `put` and `getOrPut` for `insert` and the entry API, `add` for `push`. A document is a
`TomlDocument` whose `root` is its top-level table. Unchanged text prints back byte for byte, which goes past
upstream: line endings are kept per line, each dotted key and header keeps its own spelling, and dotted keys keep the
order they were read in.
`format()` replaces upstream's `fmt()`: it reaches everything under what it is called on, keeps comments, layouts and
spellings, and fixes blank lines, indentation and key quoting as well as spacing.

The command-line tool [kotoml](../app/README.md) queries TOML files with this module, and runs the official
toml-test suite against it.

## Credits

kotoml began as a port of the toml-rs crates, by their
[contributors](https://github.com/toml-rs/toml/graphs/contributors), from commit
`342362803e41051e522127ca933a19d12a2711ac`; their design and tests are its starting point, and it has since changed
behaviour where it could do better. kotoml is licensed under the [MIT licence](../LICENSE); upstream's
own [MIT](LICENSE-MIT) and [Apache 2.0](LICENSE-APACHE) licences, under which it was ported, are kept here with
their copyright notices.
