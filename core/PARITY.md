# Parity with upstream `toml_edit`

This file is the item-by-item proof that `:core` ports `toml_edit` and the three crates under it. A row
that is not `done` says why; a behaviour difference that is not listed under
[Intended differences](#intended-differences) is a bug.

## 1. Upstream pin

Repository <https://github.com/toml-rs/toml>, commit `342362803e41051e522127ca933a19d12a2711ac`.

| crate           | version              | Kotlin package                        |
|-----------------|----------------------|---------------------------------------|
| `toml_edit`     | `0.25.15+spec-1.1.0` | `com.fromwau.kotoml`            |
| `toml_datetime` | `1.1.1+spec-1.1.0`   | `com.fromwau.kotoml`            |
| `toml_parser`   | `1.1.3+spec-1.1.0`   | `com.fromwau.kotoml.parser`, internal |
| `toml_writer`   | `1.1.2+spec-1.1.0`   | `com.fromwau.kotoml.writer`, internal |

Features ported: the defaults, `parse` and `display`. Not ported: `serde`, `debug`, `unbounded`, `unsafe`.
`toml_edit` re-exports `toml_datetime::*`, so the date-time types live in the root package next to the
document model. The `parser` and `writer` packages are internal: a caller reads and prints TOML through the
document model. The parser types a `TomlParseError` carries are public and live in the root package:
`Span`, `ParseErrorReason`, `ScalarKind`, `IntegerRadix`, `Encoding`, `FloatPart`, `Expected` and
`ExpectedToken`.

## 2. Decisions a reader needs first

### Bytes, not characters

The lexer, the event parser and the decoders work on the UTF-8 bytes of the input, never on the
`String`'s UTF-16 units. Every `Span`, every error position and every span kept for formatting is a byte
offset, exactly as upstream's are, so a span read off this port indexes the same text a Rust caller would
index. Only the slices that become values (decoded strings, keys, raw text kept for formatting) are
decoded back to `String`. The reason is that upstream's error columns, its `Span`s and the text an edit
reproduces are all defined over bytes; converting at the edges keeps every one of them identical
instead of translating each rule. The cost is one `encodeToByteArray()` per parse.

Where upstream counts characters it means Unicode scalar values (`str::chars`), so the port counts code
points there, never UTF-16 units: the error column, the key ordering of `sort_values` (Rust compares
`str` byte-wise, which orders by code point) and the escaping of control characters.

### Naming

- Types and functions keep upstream's names in Kotlin casing, accessors included: `token.kind()`. A Rust `new`
  constructor is the Kotlin constructor. The document model is the exception: it reads like Kotlin's own
  collections. Values have constructors, `OrNull` properties and a `var decor`; tables and arrays have `size`,
  `put`, `getOrPut`, `add`, `removeAt` and `var` properties for each setter/getter pair; an `Item` has
  constructors and `OrNull` properties. Section 5 lists each rename.
- A Rust trait implemented on a standard type (a closure, `()`, `Vec<T>`, `Option<T>`) has no Kotlin home, so
  the port names the implementation instead; each one is listed in its crate's table.
- Display is `toString()`. `FromStr` is a `parse` function on the companion that returns a kern `Result`.
- Rust's derived `Ord` on an enum orders by discriminant. Kotlin enums order by declaration, so `TokenKind`
  and `Encoding`, whose discriminants are byte values, are declared in discriminant order.

### Nothing throws, nothing recurses unbounded

The event parser keeps open arrays and inline tables on a heap stack instead of recursing, so
`parseDocument` takes any nesting depth on every target, as upstream does only when a `RecursionGuard` is in
place. The bound and its error are upstream's: `RecursionGuard` reports
`cannot recurse further; max recursion depth met`, and `toml_edit`'s parser applies it with the same limit.

### Items are slots, values are not

Rust hands out `&mut Item` and `&mut Value` and lets a caller overwrite the place: `*item = other`. Kotlin has
references, not places, so the port makes the difference explicit:

- `Item` is a mutable slot. Tables and arrays store items, indexing hands out the stored item, and
  `item.assign(other)` is `*item = other.clone()`. Indexing a missing key returns an empty item that joins its
  parent at the first write, so `doc["a"]["b"] = "x"` works as in Rust, and reading never adds a key.
- `Value` is a sealed type. A scalar's spelling is fixed once made, apart from `resetFormatting()`; its
  `decor` and the containers `TomlArray` and `InlineTable` change in place. A whole value is replaced through
  its container (`array[i] = v`, `inlineTable[key] = v`, `Item.assign`).
- `Decor` is an immutable data class, and every holder exposes it as a `var decor`: `*decor_mut() = other` is
  `holder.decor = other`, and `set_prefix(p)` is `holder.decor = holder.decor.copy(prefix = p)`.
- Storing an item stores a copy of its content (`assign`, `table[key] = item`, `put`, `putAll`, `getOrPut`,
  `putItem`), so two places never share a slot, a table or a value. Rust moves the value into its place; Kotlin
  cannot empty the caller's variable, and moving out of an array element would leave a hole in the array.
- A `Value` or `Table` handed to a container directly (`TomlArray.add`, `InlineTable.put`, `ArrayOfTables.add`,
  the `Item` constructors) is stored as is, as Rust moves it: keep using it only through the document, or hand
  over its `copy()`.
- An array element and an inline-table entry are always values, and an array-of-tables element always a
  table. Writing anything else into one fails with `NotAValue` or `NotATableElement`, so upstream's placeholder
  elements cannot occur, `size` counts what iteration yields, and an inline table never holds a `Table`.
- The conversions between kinds (`Table.toInlineTable()`, `InlineTable.toTable()`, `ArrayOfTables.toTomlArray()`)
  convert a copy and leave the source as it is; Rust's `into_*` consumes the source.

### Beyond upstream: the whole toml-test suite

Upstream does not pass all of toml-test. It accepts nine files `files-toml-1.0.0` lists as invalid (rules
TOML 1.1 relaxed), and twelve valid files per list do not print back byte for byte. The port passes both spec
lists in full, and for that adds:

- `TomlVersion`, a parse option. `V1_1`, the default, is upstream's behaviour; `V1_0` also refuses newlines,
  comments and a trailing comma in an inline table, the `\x` and `\e` escapes, and times without seconds. The
  1.0.0 list is checked in `V1_0`.
- Line endings are kept per line, CRLF included, and a line inserted by hand ends like the line before it.
  Upstream prints every line ending as LF.
- Each dotted-key line and each table header keeps the spelling it was written with, for as long as the keys
  it shares with other lines keep theirs. Upstream prints every occurrence of a key with the first one's.
- Interleaved dotted keys (`a.x`, `b.y`, `a.z`) print in the order they were read; a value added later follows
  its sibling. Sorting replaces the read order with key order. Upstream groups them by table.
- A document whose text had no final newline prints without one while it prints as it was read; once changed it
  ends with a newline, as upstream always does.

Everything else, and every edited document that uses none of the above, prints exactly as upstream prints it.

### Index operators and panics

`get` serves both Rust's `Index` and `IndexMut`. Where upstream panics on misuse (an index out of bounds,
indexing into a scalar), the Kotlin function returns a
`TomlEditError` instead. A Kotlin `operator set` may return a value, so `item["k"] = v` stays index syntax
while `item.set("k", v)` hands the error back; in the `[]=` form it is discarded.

## 3. Public items

Status: `done`, `serde-only` (behind the `serde` feature, out of scope), or `n/a` with the reason. The
`toml_parser` and `toml_writer` items are ported as `internal`, apart from the error payload types named in
section 1.

### `toml_datetime`

| upstream | Kotlin | status | tests |
|---|---|---|---|
| `Datetime` (`date`, `time`, `offset` fields) | `Datetime(date, time, offset)` data class, each part defaulting to `null` | done | `DatetimeDisplayTest`, `DatetimeParseTest` |
| `Date` (`year`, `month`, `day`) | `Date` (`Int`, `Int`, `Int`) | done | `` DatetimeDisplayTest.`an offset date-time without seconds prints as hours and minutes` ``, `` DatetimeParseTest.`an offset date-time without seconds parses with no seconds` `` |
| `Time` (`hour`, `minute`, `second`, `nanosecond`) | `Time` (`Int`, `Int`, `Int?`, `Int?`) | done | `DatetimeDisplayTest`, `` DatetimeParseTest.`a time without seconds parses with no seconds` `` |
| `Offset::Z`, `Offset::Custom { minutes }` | `Offset.Z`, `Offset.Custom(minutes: Int)` | done | `` DatetimeDisplayTest.`an offset date-time without seconds prints as hours and minutes` `` |
| `DatetimeParseError` | `DatetimeParseError(part, expected)`, typed: `DatetimePart`, `DatetimeExpected`, `DatetimeField` | done | `DatetimeParseTest` |
| `impl Display for Datetime / Date / Time / Offset / DatetimeParseError` | `toString()`; the error's text is rendered as part of `TomlParseError.render` | done | `DatetimeDisplayTest`, `DatetimeParseTest` |
| `impl FromStr for Datetime` | `Datetime.parse(String): Result<Datetime, DatetimeParseError>` | done | `DatetimeParseTest` |
| `impl From<Date> for Datetime`, `impl From<Time> for Datetime` | `Datetime(date = …)`, `Datetime(time = …)` | done | `ValueTest` |
| `impl Ord / PartialOrd / Eq / Hash` (derived) | `Comparable`, data-class `equals` / `hashCode` | done | |
| `impl Error for DatetimeParseError` | `IError` | done | |
| `de::*` (`DatetimeDeserializer`, `VisitMap`, `is_datetime`) | | serde-only | |
| `ser::*` (`DatetimeSerializer`, `SerializerError`, `is_datetime`) | | serde-only | |
| `impl Serialize / Deserialize for Datetime / Date / Time` | | serde-only | |

### `toml_parser`

| upstream | Kotlin | status | tests |
|---|---|---|---|
| `Source::new`, `lex`, `input` | `TomlSource(String)`, `lex()`, `input()` | done | `LexerTest`, `ParseDocumentTest` |
| `Source::get(impl SourceIndex)` | `TomlSource.get(Span)`, `get(Token)`, `get(Event)` | done | `ParseDocumentTest` (every event is sliced) |
| `Source::get_unchecked` | | n/a | behind the `unsafe` feature |
| `SourceIndex` (sealed trait over `Span`, `Token`, `Event`) | the three `TomlSource.get` overloads | done | |
| `Raw::new_unchecked` | `Raw.newUnchecked(String, Encoding?, Span)` | done | `DecoderStringTest`, `DecoderScalarTest` |
| `Raw::decode_key / decode_scalar / decode_whitespace / decode_comment / decode_newline` | same names | done | `ParseDocumentTest`, `ParseValueTest`, `ParseSimpleKeyTest` |
| `Raw::as_str / as_bytes / len / is_empty` | `asStr()`, `asBytes()` (a copy), `len()` (bytes), `isEmpty()` | done | |
| `Span::new_unchecked`, `is_empty`, `len`, `start`, `end`, `before`, `after`, `append` | `data class Span(start, end)` with a `length` property; `before`, `after`, `append` internal | done | every snapshot prints spans |
| `impl Add<usize> / AddAssign<usize> for Span`, `impl Add<Span> for usize` | `Span.plus(Int)`, internal | done | |
| `impl Default for Span` | `Span.DEFAULT`, internal | done | |
| `impl Debug for Span` (`start..end`) | `toString()` | done | |
| `ErrorSink` | `fun interface ErrorSink` | done | all parser tests |
| `impl ErrorSink for FnMut(ParseError)` / `Vec<ParseError>` | a lambda: `ErrorSink { errors += it }` | done | all parser tests |
| `impl ErrorSink for ()` | `ErrorSink.IGNORE` | done | |
| `impl ErrorSink for Option<ParseError>` | `FirstErrorSink` | done | |
| `ParseError::new`, `with_context`, `with_expected`, `with_unexpected` | `data class ParseError(reason, unexpected, context, expected)` and `copy` | done | `DecoderStringTest` |
| `ParseError::context / description / expected / unexpected` | properties; `description` is the typed `reason: ParseErrorReason` | done | `DecoderStringTest` |
| `Expected::Literal`, `Expected::Description` | `Expected.Literal`, `Expected.Token(ExpectedToken)` | done | `DecoderStringTest` |
| `lexer::Lexer` (`Iterator<Item = Token>`), `into_vec` | `Lexer : Iterator<Token>`, `intoVec()` | done | `LexerTest` |
| `lexer::Token::kind / span` | same names | done | `LexerTest` |
| `lexer::TokenKind` (16 kinds), `description`, `encoding` | `TokenKind`, `encoding`; `description` n/a, `TomlParseError.render` words the kinds | done | `LexerTest` |
| `parser::parse_document / parse_key / parse_simple_key / parse_value` | `parseDocument`, `parseKey`, `parseSimpleKey`, `parseValue` | done | `ParseDocumentTest`, `ParseValueTest`, `ParseSimpleKeyTest` |
| `parser::EventReceiver` (17 callbacks with defaults) | `interface EventReceiver` | done | all parser tests |
| `impl EventReceiver for FnMut(Event)` / `Vec<Event>` | `eventReceiver { ... }`, e.g. `eventReceiver(events::add)` | done | all parser tests |
| `impl EventReceiver for ()` | `EventReceiver.IGNORE` | done | |
| `parser::ValidateWhitespace::new` | `ValidateWhitespace(EventReceiver, TomlSource)` | done | toml_edit's parser |
| `parser::RecursionGuard::new(_, u32)` | `RecursionGuard(EventReceiver, UInt)` | done | `` ParseDocumentTest.`*recursion limit*` `` |
| `parser::Event::new_unchecked / kind / encoding / span` | `Event(...)`, `Event.newUnchecked`, same names | done | all parser tests |
| `parser::EventKind` (17 kinds), `description` | `EventKind`; `description` n/a, as for `TokenKind` | done | |
| `decoder::Encoding`, `description` | `Encoding`, with `MlLiteralString` / `MlBasicString` spelled `MultilineLiteralString` / `MultilineBasicString` (also in `TokenKind`); `description` n/a, as for `TokenKind` | done | |
| `decoder::StringBuilder` | `DecodedStringBuilder` | done | `DecoderStringTest` |
| `impl StringBuilder for String` / `Cow<str>` | `StringOutput` | done | `DecoderStringTest`, `ParseValueTest` |
| `impl StringBuilder for &str` | `BorrowedOutput` | done | |
| `impl StringBuilder for ()` | `DecodedStringBuilder.DISCARD` | done | `ParseDocumentTest` (keys) |
| `decoder::ScalarKind` (`String`, `Boolean`, `DateTime`, `Float`, `Integer`), `description`, `invalid_description` | `ScalarKind` sealed interface; `description` n/a, as for `TokenKind`; `invalid_description` is `ParseErrorReason.InvalidScalar(kind)` | done | `ParseValueTest` |
| `decoder::IntegerRadix`, `description`, `value`, `invalid_description`, `Default` | `IntegerRadix`, `base`; `description` n/a, as for `TokenKind`; `Default` is `IntegerRadix.Dec`; `invalid_description` as for `ScalarKind` | done | `ParseValueTest` |
| derived `Ord` / `Hash` / `Eq` on `Span`, `Token`, `TokenKind`, `Event`, `EventKind`, `Encoding`, `ScalarKind`, `IntegerRadix`, `ParseError` | `Comparable` / `equals` / `hashCode`; `ScalarKind` is not `Comparable`, as nothing orders it | done | |

### `toml_writer`

| upstream | Kotlin | status | tests |
|---|---|---|---|
| `core::fmt::Write` (the writer every function writes to), `fmt::Error`, `fmt::Result` | `fun interface FmtWrite`, `FmtError`, `EmptyResult<FmtError>`; `StringWriter` for `String` | done | `WriterDocTest`, `WriterStringTest` |
| `TomlWrite::open_table_header`, `close_table_header`, `open_array_of_tables_header`, `close_array_of_tables_header`, `open_inline_table`, `close_inline_table`, `open_array`, `close_array`, `key_sep`, `keyval_sep`, `key`, `value`, `val_sep`, `space`, `open_comment`, `newline` | extension functions on `FmtWrite`, same names | done | `` WriterDocTest.`a table header and a key value pair write through the writer calls` `` |
| `impl TomlWrite for W: fmt::Write` | the extensions apply to every `FmtWrite` | done | |
| `WriteTomlKey`, `ToTomlKey::to_toml_key` | `fun interface WriteTomlKey`, `WriteTomlKey.toTomlKey()` | done | `WriterStringTest` |
| `impl WriteTomlKey for str / String / Cow<str> / &V` | `WriteTomlKey.of(String)` | done | `` WriterDocTest.`a table header and a key value pair write through the writer calls` `` |
| `WriteTomlValue`, `ToTomlValue::to_toml_value` | `fun interface WriteTomlValue`, `WriteTomlValue.toTomlValue()` | done | `WriterFloatTest`, `WriterStringTest` |
| `impl WriteTomlValue for bool, u8, i8, u16, i16, u32, i32, u64, i64` | `WriteTomlValue.of(Boolean / UByte / Byte / UShort / Short / UInt / Int / ULong / Long)` | done | |
| `impl WriteTomlValue for u128, i128` | | n/a | Kotlin has no 128-bit integer |
| `impl WriteTomlValue for f32, f64` | `WriteTomlValue.of(Float / Double)` | done | `WriterFloatTest`, `FloatFormatTest` |
| `impl WriteTomlValue for char` | `WriteTomlValue.of(Char)` | done | |
| `impl WriteTomlValue for str / String / Cow<str> / &V` | `WriteTomlValue.of(String)` | done | `` WriterDocTest.`a table header and a key value pair write through the writer calls` `` |
| `impl WriteTomlValue for [V] / [V; N] / Vec<V>` | `WriteTomlValue.of(Iterable<WriteTomlValue>)` | done | |
| `impl WriteTomlValue for BTreeMap<K, V> / HashMap<K, V>` | `WriteTomlValue.of(Map<out WriteTomlKey, WriteTomlValue>)` | done | |
| `TomlStringBuilder::new`, `as_default`, `as_literal`, `as_ml_literal`, `as_basic_pretty`, `as_ml_basic_pretty`, `as_basic`, `as_ml_basic` | `TomlStringBuilder(String)`, same names | done | `WriterStringTest`, `WriterDocTest` |
| `TomlString` (`WriteTomlValue`, derived `Eq` / `Hash`) | `TomlString` | done | `WriterStringTest` |
| `TomlKeyBuilder::new`, `as_default`, `as_unquoted`, `as_literal`, `as_basic_pretty`, `as_basic` | `TomlKeyBuilder(String)`, same names | done | `WriterStringTest` |
| `TomlKey` (`WriteTomlKey`, derived `Eq` / `Hash`) | `TomlKey` | done | `WriterStringTest` |
| derived `Ord` on `TomlString`, `TomlKey` | | n/a | the order of private fields is not a contract; Kotlin offers no comparison |
| `TomlIntegerFormat::new`, `as_decimal`, `as_hex_upper`, `as_hex_lower`, `as_octal`, `as_binary`, `format`, `Default` | `TomlIntegerFormat()`, same names; `format(Long): TomlInteger?`, `format(ULong): TomlInteger` | done | `WriterIntegerTest`, `WriterDocTest` |
| `TomlInteger::new` | `TomlInteger(Long)`, `TomlInteger.of(ULong)` | done | `` WriterDocTest.`an integer format writes ten as lower-case hex` `` |
| `impl WriteTomlValue for TomlInteger<N>` (every integer width) | `TomlInteger : WriteTomlValue` over `Long` and `ULong` | done | `WriterIntegerTest` |

### `toml_edit`

| upstream | Kotlin | status | tests |
|---|---|---|---|
| `DocumentMut::new`, `Default` | `TomlDocument()` | done | `` EditUnitTest.`an empty document prints as text that parses back` `` |
| `DocumentMut::as_item`, `as_item_mut`, `into_item`, `as_table`, `as_table_mut`, `into_table` | `root: Table` | done | `EditTest`, `VisitExampleTest` |
| `DocumentMut::iter` | `TomlDocument` is an `Iterable<Pair<String, Item>>` over the root table | done | `TomlDocumentTest` |
| `DocumentMut::set_trailing`, `trailing` | `var trailing: String` | done | `` EditTest.`a document's decor and trailing text print around its tables` ``, `TomlDocumentTest` |
| `impl FromStr for DocumentMut` | `TomlDocument.parse(String)` and `parse(ByteArray)`, both `Result<TomlDocument, TomlParseError>` | done | `EditTest`, `ComplianceParseTest` |
| `impl Display for DocumentMut` | `toString()`, keeping line endings, spellings and dotted-key order (see section 2) | done | `EditTest`, `PreservationTest`, `` SuiteTest.`every valid V1_0 file prints back byte for byte` ``, `` SuiteTest.`every valid V1_1 file prints back byte for byte` `` |
| `impl Index<&str> / IndexMut<&str> for DocumentMut` | `operator get`, `operator set` | done | `EditTest` |
| `impl Deref / DerefMut<Target = Table> for DocumentMut` | `root`, plus forwards for `getOrNull`, `containsKey`, `operator contains`, `put(String / Key, Item)`, `putAll`, `remove`, `retainAll`, `removeAll`, `clear`, `getOrPut(String / Key, …)`, `keys`, `size`, `isEmpty`, `isNotEmpty`, the `operator set` overloads, and `var decor` for `decor` / `decor_mut` | done | `` EditTest.`a document's decor and trailing text print around its tables` ``, `SlotSemanticsTest`, `CollectionApiTest` |
| `impl From<Table> for DocumentMut` | `TomlDocument(Table)` | done | |
| `impl Clone for DocumentMut` | `copy()` | done | `TomlDocumentTest` |
| `Document::new`, `Default`, `parse`, `as_item`, `into_item`, `as_table`, `into_table`, `iter`, `trailing`, `raw`, `into_mut`, `FromStr`, `Deref<Target = Table>` | | n/a | `TomlDocument.parse` gives the editable document directly; see section 5 |
| `ImDocument` (deprecated alias of `Document`) | | n/a | deprecated upstream since 0.23 |
| `Item::None / Value / Table / ArrayOfTables`, `Default` | the constructors `Item()`, `Item(Value)`, `Item(Table)`, `Item(ArrayOfTables)` | done | `EditTest` |
| `Item::or_insert` | | n/a | `Table.getOrPut`, or `if (item.isMissing) item.assign(other)` |
| `Item::type_name` (as `type: TomlType`), `as_value(_mut)`, `as_table(_mut)`, `as_array_of_tables(_mut)`, `as_table_like(_mut)`, `is_none` | `type`, `valueOrNull`, `tableOrNull`, `arrayOfTablesOrNull`, `tableLikeOrNull`, `isMissing` | done | `EditTest`, `ComplianceParseTest` |
| `Item::is_value`, `is_table`, `is_array_of_tables`, `is_table_like` | | n/a | `type`, or a check that the `OrNull` property is not null |
| `Item::as_str`, `as_integer`, `as_float`, `as_bool`, `as_datetime`, `as_array(_mut)`, `as_inline_table(_mut)` | `stringOrNull`, `longOrNull`, `doubleOrNull`, `booleanOrNull`, `datetimeOrNull`, `arrayOrNull`, `inlineTableOrNull` | done | `EditTest`, `ComplianceParseTest` |
| `Item::is_str` … `is_inline_table` | | n/a | `type`, or a check that the `OrNull` property is not null |
| `Item::span` | | n/a | an editable item never holds spans; see section 2 |
| `Item::get` / `get_mut` (`Option<&Item>`) | `getOrNull(String / Int)` | done | `EditTest` |
| `impl Index / IndexMut<usize / &str / String> for Item` | `operator get(String / Int)`, `operator set(String / Int, Item)`, and `operator set(String, …)` for a `Value`, `Table`, `ArrayOfTables`, `String`, `Long`, `Double`, `Boolean` or `Datetime` | done | `EditTest`, `` ComplianceParseTest.`nested arrays of tables and sub-tables read back by index` `` |
| `Item::into_value / into_table / into_array_of_tables` (`Result<_, Item>`) | internal; `Table.toInlineTable()`, `InlineTable.toTable()` and `ArrayOfTables.toTomlArray()` convert a copy | done | `` EditTest.`an inline table converted to a table prints as a table with a header` ``, `SlotSemanticsTest` |
| `Item::make_value` | `makeValue()` | done | `` EditTest.`makeValue turns a table and its sub-tables into one inline table` `` |
| `impl Display`, `impl Clone`, `From<Value / Table / ArrayOfTables>` | `toString()`, `copy()`, the constructors | done | `` EditUnitTest.`an item prints as text that parses back` `` |
| `impl FromStr for Item`, `From<&Item>`, `From<&str, i64, …>` | | n/a | `Item(Value.parse(…))`, `copy()`, `Item(Value.String(…))` and its siblings |
| `value()`, `table()`, `array()` | | n/a | the `operator set` overloads store a scalar, a `Value` or a table directly; otherwise `Item(…)` |
| `Value::String / Integer / Float / Boolean / Datetime / Array / InlineTable` | `Value.String(value, decor)` … `Value.Datetime(value, decor)`, `Value.Array(array, decor)`, `Value.InlineTable(table, decor)`; each scalar has `value`, `spelling` and `displaySpelling` | done | `EditTest`, `ValueTest` |
| `Value::type_name` (as `type: TomlType`), `as_str`, `as_integer`, `as_float`, `as_bool`, `as_datetime`, `as_array(_mut)`, `as_inline_table(_mut)` | `type`, `stringOrNull`, `longOrNull`, `doubleOrNull`, `booleanOrNull`, `datetimeOrNull`, `arrayOrNull`, `inlineTableOrNull` | done | `ValueTest`, `ComplianceParseTest` |
| `Value::is_str` … `is_inline_table` | | n/a | `is Value.String` and its siblings, or `type` |
| `Value::decor_mut`, `decor`, `decorated` | `var decor`, or the constructor's `decor` | done | `` EditDocTest.`a new value's decor has no suffix` ``, `` EditDocTest.`a new value's decor reads as default` ``, `` EditDocTest.`a value's decor prints around its text` `` |
| `Value::fmt` | `resetFormatting()` | done | `ValueTest` |
| `Value::span` | | n/a | an editable value never holds spans; see section 2 |
| `impl FromStr for Value`, `impl Display`, `impl Clone` | `Value.parse`, `toString()`, `copy()` | done | `ComplianceParseTest`, `ValueTest` |
| `impl From<&str, String, i64, f64, bool, Datetime, Date, Time, Array, InlineTable, &Value> for Value` | the constructors: `Value.String(…)`, `Value.Datetime(Datetime(date = …))`, `Value.Array(…)`, `copy()` for `&Value` | done | `ComplianceParseTest` |
| `impl FromIterator<V> / FromIterator<(K, V)> for Value` | `Value.Array(values.toTomlArray())`, `Value.InlineTable(pairs.toInlineTable())` | done | `` EditUnitTest.`an array built from strings prints with default spacing` ``, `` EditTest.`putAll copies another inline table's pairs and leaves that table as it was` `` |
| `Array::new`, `fmt`, `trailing_comma` / `set_trailing_comma`, `trailing` / `set_trailing`, `decor(_mut)`, `len`, `is_empty`, `clear`, `get`, `get_mut` | `TomlArray()`, `resetFormatting()`, `var trailingComma`, `var trailing: String`, `var decor`, `size`, `isEmpty()` and `isNotEmpty()`, `clear()`, `operator get`; no `span` | done | `EditTest`, `EditDocTest`, `CollectionApiTest` |
| `Array::iter`, `iter_mut`, `IntoIterator`, `ArrayIter`, `ArrayIterMut`, `ArrayIntoIter` | `Iterable<Value>`; aliases n/a | done | `` EditDocTest.`walking a document replaces each float with a string and keeps its decor` `` |
| `Array::push`, `push_formatted` | `add(Value)`, plus `add(String / Long / Double / Boolean / Datetime)` | done | `` EditDocTest.`an array takes elements of different types` ``, `` EditDocTest.`an array takes a parsed value with its spelling` `` |
| `Array::insert`, `insert_formatted` | `add(index, value): EmptyResult<TomlEditError>` | done | `` EditTest.`inserting and setting array elements keeps their decor and replacing resets it` ``, `` EditDocTest.`an array takes a value inserted at its start` ``, `` EditDocTest.`an array takes a parsed value inserted at its start` `` |
| `Array::replace`, `replace_formatted` | `operator set(index, value)` and `replace(index, value, keepDecor = true)`; `keepDecor = false` is `replace_formatted` | done | `` EditTest.`inserting and setting array elements keeps their decor and replacing resets it` ``, `` EditDocTest.`an array element can be replaced` ``, `` EditDocTest.`an array element can be replaced by a parsed value without keeping its decor` `` |
| `Array::remove`, `retain`, `sort_by`, `sort_by_key` | `removeAt`, `retainAll` returning whether it removed a value, `sortWith(Comparator<Value>)`, `sortBy(selector)` | done | `` EditTest.`removing array elements returns them and leaves an empty array as brackets` ``, `` EditTest.`an array of strings sorts with a comparator over its values` ``, `RemovalKeepsCommentsTest`, `` CollectionApiTest.`retainAll on an array returns whether it removed a value` `` |
| `impl Display / Extend / FromIterator for Array` | `toString()`, `addAll`, `tomlArrayOf(…)` and `Iterable<Value>.toTomlArray()` | done | `` EditUnitTest.`an array built from strings prints with default spacing` `` |
| `ArrayOfTables::new`, `len`, `is_empty`, `clear`, `get`, `get_mut`, `push`, `retain`, `iter`, `iter_mut`, `IntoIterator`, iterator aliases | `ArrayOfTables()`, `size`, `isEmpty()` and `isNotEmpty()`, `clear()`, `operator get`, `add`, `retainAll` returning whether it removed a table, `Iterable<Table>`; no `span`; aliases n/a | done | `EditTest`, `` CollectionApiTest.`retainAll on an array of tables returns whether it removed a table` `` |
| `ArrayOfTables::insert`, `replace`, `remove` | `add(index, table)`, `operator set(index, table)` and `replace(index, table, keepDecor = true)`, `removeAt`, returning `TomlEditError` where upstream panics; `set` keeps the old table's header decor (section 5) | done | `` CollectionApiTest.`setting an array of tables element keeps the comments around the old header` ``, `` CollectionApiTest.`replacing an array of tables element without its decor prints the new table's own` ``, `` EditTest.`makeValue turns an array of tables into an array of inline tables` ``, `` EditTest.`a table added at the start of an array of tables prints first` ``, `` EditTest.`a table added in the middle of an array of tables prints between its neighbours` ``, `` EditTest.`a table added at the end of an array of tables prints last` ``, `` EditTest.`adding a table past the end of an array of tables fails with IndexOutOfBounds` ``, `` EditTest.`setting an array of tables element returns the old table and prints the new one` ``, `` EditTest.`setting a table past the end of an array of tables fails with IndexOutOfBounds` ``, `RemovalKeepsCommentsTest` |
| `ArrayOfTables::into_array` | `toTomlArray()`, converting a copy | done | `` EditTest.`makeValue turns an array of tables into an array of inline tables` ``, `SlotSemanticsTest`, `` CollectionApiTest.`toTomlArray converts a copy of an array of tables and leaves it as it was` `` |
| `impl Display / Extend / FromIterator for ArrayOfTables` | `toString()`, `addAll`, `arrayOfTablesOf(…)` and `Iterable<Table>.toArrayOfTables()` | done | |
| `Table::new`, `decor(_mut)`, `iter`, `iter_mut`, `IntoIterator`, `Iter`, `IterMut`, `IntoIter`, `len`, `is_empty`, `clear`, `contains_key` | `Table()`, `var decor`, `Iterable<Pair<String, Item>>`, `keys`, `size`, `isEmpty()` and `isNotEmpty()`, `clear()`, `containsKey` and `operator contains`; no `span`; aliases n/a | done | `EditTest`, `CollectionApiTest` |
| `Table::set_implicit` / `is_implicit`, `set_dotted` / `is_dotted`, `set_position` / `position` | `var isImplicit`, `var isDotted`, `var position: Long?` (`isize` is `Long`) | done | `` EditTest.`a table's position sets the order of its header in the document` ``, `` EditTest.`a table with a negative position prints before the other tables` ``, `` EditDocTest.`an implicit table with a sub-table prints only the sub-table's header` `` |
| `Table::fmt`, `sort_values`, `sort_values_by`, `get_values` | `resetFormatting()`, `sortBy { (key, _) -> key }` or any `sortBy(selector)`, `sortWith(Comparator<Pair<Key, Item>>)`, `valuesWithPaths()` | done | `` EditTest.`sorting a table's keys moves each key with its comments` ``, `` EditTest.`sorting a table's keys by a comparator orders them by their spelling` ``, `TableSortTest` |
| `Table::get`, `get_mut` | `getOrNull` | done | `EditTest` |
| `Table::insert`, `insert_formatted` | `put(String, Item)`, keeping a present key's formatting (section 5), and `put(Key, Item)`, returning the item replaced | done | `EditTest`, `SlotSemanticsTest`, `` CollectionApiTest.`put with a string keeps the spelling and comment of a quoted key` ``, `` CollectionApiTest.`put with a key replaces the spelling and comment of a quoted key` `` |
| `Table::entry`, `entry_format`, `Entry`, `OccupiedEntry`, `VacantEntry`, `or_insert`, `or_insert_with` | `getOrPut(String / Key, () -> Item): Item` | done | `` EditTest.`a table with a negative position prints before the other tables` ``, `` EditDocTest.`getOrPut on an empty table stores the key under its name` ``, `SlotSemanticsTest` |
| `Table::key`, `key_mut` | `keyOrNull(name): Key?`, whose formatting is `var` | done | `PreservationTest` |
| `Table::remove`, `retain`, `into_inline_table` | `remove`, `retainAll((Pair<String, Item>) -> Boolean)` returning whether it removed a pair, `toInlineTable()` converting a copy | done | `EditTest`, `RemovalCommentScopeTest`, `RemovalKeepsCommentsTest`, `RemovalPropertyTest`, `CollectionApiTest` |
| `Table::get_key_value(_mut)`, `contains_table`, `contains_value`, `contains_array_of_tables`, `remove_entry` | | n/a | `keyOrNull(name)` with `getOrNull(name)`; `getOrNull(name)?.tableOrNull != null` and its siblings; `keyOrNull(name)` before `remove(name)` |
| `impl Index / IndexMut<&str> for Table` | `operator get` (a slot), `operator set` for an `Item`, `Value`, `Table`, `ArrayOfTables` or scalar | done | `EditTest`, `` EditDocTest.`string values that need escaping print as literal strings` `` |
| `impl Display / Extend / FromIterator for Table` | `toString()`, `putAll`, `tableOf(…)` and `Iterable<Pair<String, Item>>.toTable()` | done | `` EditDocTest.`string values that need escaping print as literal strings` ``, `SlotSemanticsTest` |
| `TableLike` (`iter`, `iter_mut`, `len`, `is_empty`, `clear`, `get`, `get_mut`, `contains_key`, `insert`, `remove`, `get_values`, `fmt`, `sort_values`, `set_dotted`, `is_dotted`, `key`, `key_mut`) | sealed `TableLike`: `entries`, `keys`, `size`, `isEmpty()`, `isNotEmpty()`, `clear()`, `getItem(key)`, `containsKey` and `operator contains`, `putItem`, `removeItem`, `valuesWithPaths()`, `resetFormatting()`, `var isDotted`, `keyOrNull(name)`; sorting is on `Table` and `InlineTable` (section 5) | done | `` EditTest.`tables and inline tables read as TableLike and an array of tables does not` ``, `VisitExampleTest`, `` CollectionApiTest.`getItem reads the item at a key of a table like` ``, `` CollectionApiTest.`a key is in a table like when it holds an item` `` |
| `TableLike::entry`, `entry_format`, `get_key_value(_mut)` | | n/a | `getOrPut` on `Table` or `InlineTable`; `keyOrNull(name)` with `getItem(name)` |
| `InlineTable::new`, `fmt`, `trailing_comma` / `set_trailing_comma`, `trailing` / `set_trailing`, `set_dotted` / `is_dotted`, `decor(_mut)`, `iter`, `iter_mut`, `IntoIterator`, `len`, `is_empty`, `clear`, `contains_key` | `InlineTable()`, `resetFormatting()`, `var trailingComma`, `var trailing: String`, `var isDotted`, `var decor`, `Iterable<Pair<String, Value>>`, `keys`, `size`, `isEmpty()` and `isNotEmpty()`, `clear()`, `containsKey` and `operator contains`; no `span` | done | `EditTest`, `CollectionApiTest` |
| `InlineTable::get`, `get_mut`, `insert`, `insert_formatted`, `remove`, `retain`, `key`, `key_mut` | `operator get`, `put(String / Key, Value)`, `put(String, …)` keeping a present key's formatting (section 5), `remove`, `retainAll((Pair<String, Value>) -> Boolean)` returning whether it removed a pair, `keyOrNull(name)` | done | `CollectionApiTest`, `` EditTest.`getOrPut adds a key to an inline table and resetting prints default spacing` ``, `` EditTest.`removing inline table keys keeps the spacing of the ones left` ``, `RemovalKeepsCommentsTest` |
| `InlineTable::entry`, `entry_format`, `get_or_insert`, `InlineEntry`, `InlineOccupiedEntry`, `InlineVacantEntry` | `getOrPut(String / Key, () -> Value): Value` | done | `` EditTest.`getOrPut adds a key to an inline table and resetting prints default spacing` ``, `` EditDocTest.`getOrPut on an inline table stores the key under its name` `` |
| `InlineTable::sort_values`, `sort_values_by`, `get_values`, `into_table` | `sortBy { (key, _) -> key }` or any `sortBy(selector)`, `sortWith(Comparator<Pair<Key, Value>>)`, `valuesWithPaths()`, `toTable()` converting a copy | done | `` EditTest.`an inline table converted to a table prints as a table with a header` ``, `TableSortTest` |
| `InlineTable::get_key_value(_mut)`, `remove_entry` | | n/a | `keyOrNull(name)` with `operator get`; `keyOrNull(name)` before `remove(name)` |
| `impl Index<&str> for InlineTable` | `operator get`, returning null where upstream panics | done | |
| `impl IndexMut<&str> for InlineTable` | `operator set` for a `Value` or scalar, adding a missing key | done | `SlotSemanticsTest` |
| `impl Display / Extend / FromIterator for InlineTable` | `toString()`, `putAll`, `inlineTableOf(…)` and `Iterable<Pair<String, Value>>.toInlineTable()` | done | `` EditTest.`putAll copies another inline table's pairs and leaves that table as it was` `` |
| `Key::new`, `fmt`, `parse` (dotted), `with_leaf_decor`, `with_dotted_decor`, `leaf_decor(_mut)`, `dotted_decor(_mut)` | `Key(name, leafDecor, dottedDecor)`, `resetFormatting()`, `Key.parsePath`, `var leafDecor`, `var dottedDecor` | done | `` EditTest.`Key parse reads bare quoted and literal keys to their names` ``, `ValueTest` |
| `Key::get`, `as_mut`, `KeyMut` | `val name`; a key's formatting is `var` on `Key` itself, so there is no `KeyMut` | done | `VisitExampleTest`, `PreservationTest` |
| `Key::as_repr`, `default_repr`, `display_repr` | `spelling`, `displaySpelling`; the default spelling is internal | done | `` EditTest.`sorting a table's keys by a comparator orders them by their spelling` `` |
| `Key::span` | | n/a | an editable key never holds spans; see section 2 |
| `impl FromStr for Key` | `Key.parse` | done | `` EditTest.`a key built from a name prints with the simplest quoting that parses back` `` |
| `impl Display / Clone / Hash / Ord / Eq for Key`, `From<&str / String>` | `toString()`, `copy()`, `hashCode` / `compareTo` / `equals` by text, the constructor | done | |
| `impl Deref<Target = str> / Borrow<str> for Key`, `PartialEq<str / &str / String>`, `From<Key> for String` | `name` | n/a | Kotlin has no deref; compare `key.name` |
| `RawString` (`as_str`, `span`, `Default`, `From<&str / String / &String / Box<str>>`) | internal; decor and trailing text are `String`s | done | `` EditTest.`a parsed document keeps the whitespace and comments around every key and value` `` |
| `Repr` (`as_raw`, `span`) | internal; a value or key shows its `spelling` | done | `` EditTest.`a parsed document keeps the whitespace and comments around every key and value` `` |
| `Decor` (`new`, `clear`, `prefix`, `set_prefix`, `suffix`, `set_suffix`, `Default`) | immutable `data class Decor(prefix: String?, suffix: String?)`; `Decor()` for `default` and `clear`, `copy(prefix = …)` for `set_prefix` | done | `` EditTest.`a document's decor and trailing text print around its tables` ``, `ValueTest` |
| `Formatted` (`new`, `value`, `into_value`, `as_repr`, `default_repr`, `display_repr`, `span`, `decor_mut`, `decor`, `fmt`), `Display`, `Eq` | internal; each scalar `Value` has `value`, `spelling`, `displaySpelling`, `decor` and `resetFormatting()` | done | `EditDocTest`, `ValueTest` |
| `ValueRepr` (sealed trait) | | n/a | each scalar `Value` has its own constructor |
| `TomlError` (`message`, `span`), `Display`, `Error`, `Eq`, `Hash` | `data class TomlParseError(reason, span, position, context, expected)`, an `IError`; `Display` is `render(source)` | done | `ComplianceInvalidTest`, `` ComplianceParseTest.`garbage inside an inline table reports an extra assignment at column 5` ``, `` ComplianceParseTest.`garbage inside a closed inline table reports an extra assignment at column 5` ``, `` ComplianceParseTest.`a line of two equals signs reports an extra equals sign at column 2` `` |
| `visit::Visit` and `visit_document` … `visit_value` | | n/a | a recursive function with a `when` over the sealed `Value` walks the tree; `VisitExampleTest`, `` EditDocTest.`walking a document collects every string in tables and inline tables` `` show how |
| `visit_mut::VisitMut` and `visit_document_mut` … `visit_value_mut` | | n/a | as for `Visit`; `VisitExampleTest`, `` EditDocTest.`walking a document replaces each float with a string and keeps its decor` `` show how |
| `de::*`, `ser::*`, `TomlError::custom`, `Serialize / Deserialize` impls | | serde-only | |
| `toml_datetime::*` re-export | the date-time types in the same package | done | |
| (none: an addition) | `TomlVersion`, and a `version` parameter on `TomlDocument.parse`, `TomlDocument.read`, `Value.parse`, `Key.parse`, `Key.parsePath` | added | `TomlVersionTest`, `` SuiteTest.`* V1_0 *` `` |
| (none: an addition) | `removeAll(predicate)` on `Table`, `InlineTable`, `TomlArray`, `ArrayOfTables` and `TomlDocument`, returning whether it removed anything; `keys` on `TableLike` and `TomlDocument`; `isNotEmpty()` on every container | added | `CollectionApiTest` |
| (none: an addition) | `TomlDocument.read(Source)` and `TomlDocument.writeTo(Sink)` over kotlinx-io, failing with `TomlReadError` (`TomlIoError` or `Parse`) and `TomlIoError` | added | `TomlDocumentTest`, `` EditDocTest.`the module README example reads a document from a source and writes it to a sink` `` |

## 4. Upstream tests

### `toml_datetime`

| upstream | Kotlin |
|---|---|
| `tests/display.rs::time_without_seconds` | `` DatetimeDisplayTest.`a time without seconds prints as hours and minutes` `` |
| `tests/display.rs::time_without_seconds_with_nanoseconds` | `` DatetimeDisplayTest.`a time with nanoseconds but no seconds prints zero seconds before the fraction` `` |
| `tests/display.rs::time_with_zero_seconds_and_nanoseconds` | `` DatetimeDisplayTest.`a time with zero seconds and zero nanoseconds prints a one digit fraction` `` |
| `tests/display.rs::datetime_without_seconds` | `` DatetimeDisplayTest.`an offset date-time without seconds prints as hours and minutes` `` |
| `tests/parse.rs::only_t` | `` DatetimeParseTest.`a lone T is refused as missing a year or hour` `` |
| `tests/parse.rs::only_tz` | `` DatetimeParseTest.`a lone TZ is refused as missing a year or hour` `` |
| `tests/parse.rs::only_tz_dot` | `` DatetimeParseTest.`a lone TZ followed by a dot is refused as missing a year or hour` `` |
| `tests/parse.rs::time_without_seconds` | `` DatetimeParseTest.`a time without seconds parses with no seconds` `` |
| `tests/parse.rs::time_without_seconds_with_nanoseconds` | `` DatetimeParseTest.`a fraction after minutes without seconds is refused` `` |
| `tests/parse.rs::datetime_without_seconds` | `` DatetimeParseTest.`an offset date-time without seconds parses with no seconds` `` |

Upstream's `{:#?}` snapshots are compared byte for byte: the tests render values through a test-only
copy of Rust's derived `Debug` (`RustDebug.kt`), so the expected text is upstream's, unedited.

### `toml_parser`

Snapshot files (`tests/snapshots/*.txt`) are copied to `src/commonTest/snapshots/toml_parser/`; section 5 lists the texts kotoml changed.

| upstream | Kotlin |
|---|---|
| `src/lexer/test.rs::test_lex_ascii_char` | `` LexerTest.`lexing one ASCII character emits its token and leaves the rest` `` |
| `src/lexer/test.rs::test_lex_whitespace` | `` LexerTest.`whitespace lexes as one token up to the first non-blank character` `` |
| `src/lexer/test.rs::test_lex_comment` | `` LexerTest.`a comment lexes up to the line ending and keeps control characters in it` `` |
| `src/lexer/test.rs::test_lex_crlf` | `` LexerTest.`CRLF and a lone carriage return each lex as one newline` `` |
| `src/lexer/test.rs::test_lex_literal_string` | `` LexerTest.`a literal string lexes to its closing quote or the end of input` `` |
| `src/lexer/test.rs::test_lex_ml_literal_string` | `` LexerTest.`a multi-line literal string takes up to two extra quotes before its closing delimiter` `` |
| `src/lexer/test.rs::test_lex_basic_string` | `` LexerTest.`a basic string lexes past escaped quotes to its closing quote` `` |
| `src/lexer/test.rs::test_lex_atom` | `` LexerTest.`an atom lexes up to whitespace or a dot` `` |
| `src/lexer/test.rs::literal_strings` | `` LexerTest.`literal strings of every quote count lex into string tokens` `` |
| `src/lexer/test.rs::basic_strings` | `` LexerTest.`basic strings of every quote count and escape lex into string tokens` `` |
| `src/lexer/test.rs::keylike` | `` LexerTest.`keylike text of letters digits dashes and underscores lexes as one atom` `` |
| `src/lexer/test.rs::all` | `` LexerTest.`a line using every token kind lexes into each kind with contiguous spans` `` |
| `src/lexer/test.rs::bare_cr_bad` | `` LexerTest.`a bare carriage return or an unterminated string lexes into tokens covering every byte` `` |
| `src/lexer/test.rs::bad_comment` | `` LexerTest.`a NUL inside a comment stays part of the comment token` `` |
| `src/decoder/string.rs::literal_string` | `` DecoderStringTest.`a literal string decodes to its content with backslashes kept` `` |
| `src/decoder/string.rs::ml_literal_string` | `` DecoderStringTest.`a multi-line literal string drops its first newline and keeps inner quotes` `` |
| `src/decoder/string.rs::basic_string` | `` DecoderStringTest.`a basic string decodes its escapes and reports a bad escape or a raw newline` `` |
| `src/decoder/string.rs::ml_basic_string` | `` DecoderStringTest.`a multi-line basic string joins lines ending in a backslash and reports bad escapes` `` |
| `src/decoder/string.rs::unquoted_keys` | `` DecoderStringTest.`an unquoted key decodes to its own text and an empty one is reported` `` |
| `src/decoder/scalar.rs::many_redundant_signs` | `` DecoderScalarTest.`thousands of leading signs on a number report an error without overflowing` `` |
| `tests/testsuite/parse_document.rs::document_empty` | `` ParseDocumentTest.`an empty document emits no events` `` |
| `tests/testsuite/parse_document.rs::document_ws` | `` ParseDocumentTest.`a document of only spaces emits one whitespace event` `` |
| `tests/testsuite/parse_document.rs::document_key_string` | `` ParseDocumentTest.`a dotted key with a basic string value parses without errors` `` |
| `tests/testsuite/parse_document.rs::document_key_string_comment` | `` ParseDocumentTest.`a key value pair followed by a comment parses without errors` `` |
| `tests/testsuite/parse_document.rs::document_key_ws` | `` ParseDocumentTest.`whitespace around the dots of a dotted key parses without errors` `` |
| `tests/testsuite/parse_document.rs::document_table` | `` ParseDocumentTest.`a table header with a spaced dotted key parses without errors` `` |
| `tests/testsuite/parse_document.rs::document_table_missing_key` | `` ParseDocumentTest.`an empty table header reports an empty key` `` |
| `tests/testsuite/parse_document.rs::document_table_dot` | `` ParseDocumentTest.`a table header of a lone dot reports two empty keys` `` |
| `tests/testsuite/parse_document.rs::document_table_dot_dot` | `` ParseDocumentTest.`a table header of two dots reports three empty keys` `` |
| `tests/testsuite/parse_document.rs::document_table_dot_key` | `` ParseDocumentTest.`a table header starting with a dot reports one empty key` `` |
| `tests/testsuite/parse_document.rs::document_table_dot_dot_key` | `` ParseDocumentTest.`a table header starting with a dot reports one empty key in the dot dot key snapshot` `` |
| `tests/testsuite/parse_document.rs::document_table_key_dot` | `` ParseDocumentTest.`a table header ending in a dot reports one empty key` `` |
| `tests/testsuite/parse_document.rs::document_table_key_dot_dot` | `` ParseDocumentTest.`a table header ending in two dots reports two empty keys` `` |
| `tests/testsuite/parse_document.rs::document_table_key_dot_dot_key` | `` ParseDocumentTest.`a table header with two dots between keys reports one empty key` `` |
| `tests/testsuite/parse_document.rs::document_missing_key` | `` ParseDocumentTest.`a value with no key reports an empty key` `` |
| `tests/testsuite/parse_document.rs::document_dot` | `` ParseDocumentTest.`a key of a lone dot reports two empty keys` `` |
| `tests/testsuite/parse_document.rs::document_dot_dot` | `` ParseDocumentTest.`a key of two dots reports three empty keys` `` |
| `tests/testsuite/parse_document.rs::document_dot_key` | `` ParseDocumentTest.`a key starting with a dot reports one empty key` `` |
| `tests/testsuite/parse_document.rs::document_dot_dot_key` | `` ParseDocumentTest.`a key starting with two dots reports two empty keys` `` |
| `tests/testsuite/parse_document.rs::document_key_dot` | `` ParseDocumentTest.`a key ending in a dot reports one empty key` `` |
| `tests/testsuite/parse_document.rs::document_key_dot_dot` | `` ParseDocumentTest.`a key ending in two dots reports two empty keys` `` |
| `tests/testsuite/parse_document.rs::document_key_dot_dot_key` | `` ParseDocumentTest.`a key with two dots between its parts reports one empty key` `` |
| `tests/testsuite/parse_document.rs::document_inline_table_missing_key` | `` ParseDocumentTest.`an inline table entry with no key reports an empty key` `` |
| `tests/testsuite/parse_document.rs::document_inline_table_dot` | `` ParseDocumentTest.`an inline table key of a lone dot reports two empty keys` `` |
| `tests/testsuite/parse_document.rs::document_inline_table_dot_dot` | `` ParseDocumentTest.`an inline table key of two dots reports three empty keys` `` |
| `tests/testsuite/parse_document.rs::document_inline_table_dot_key` | `` ParseDocumentTest.`an inline table key starting with a dot reports one empty key` `` |
| `tests/testsuite/parse_document.rs::document_inline_table_dot_dot_key` | `` ParseDocumentTest.`an inline table key starting with two dots reports two empty keys` `` |
| `tests/testsuite/parse_document.rs::document_inline_table_key_dot` | `` ParseDocumentTest.`an inline table key ending in a dot reports one empty key` `` |
| `tests/testsuite/parse_document.rs::document_inline_table_key_dot_dot` | `` ParseDocumentTest.`an inline table key ending in two dots reports two empty keys` `` |
| `tests/testsuite/parse_document.rs::document_inline_table_key_dot_dot_key` | `` ParseDocumentTest.`an inline table key with two dots between its parts reports one empty key` `` |
| `tests/testsuite/parse_document.rs::document_key_datetime` | `` ParseDocumentTest.`a local date value followed by a comment parses without errors` `` |
| `tests/testsuite/parse_document.rs::document_bom` | `` ParseDocumentTest.`a leading byte order mark is skipped` `` |
| `tests/testsuite/parse_document.rs::document_complex` | `` ParseDocumentTest.`the TOML example document with tables arrays and multi-line strings parses without errors` `` |
| `tests/testsuite/parse_document.rs::document_invalid` | `` ParseDocumentTest.`a stray dollar sign line reports an invalid key with no value` `` |
| `tests/testsuite/parse_document.rs::document_invalid_comment` | `` ParseDocumentTest.`a NUL inside a comment reports an invalid comment character` `` |
| `tests/testsuite/parse_document.rs::document_invalid_cr` | `` ParseDocumentTest.`a carriage return without a newline reports the bare carriage return` `` |
| `tests/testsuite/parse_document.rs::inline_table_within_recursion_limit` | `` ParseDocumentTest.`inline tables nested to the recursion limit parse without errors` `` |
| `tests/testsuite/parse_document.rs::inline_table_outside_recursion_limit` | `` ParseDocumentTest.`inline tables past the recursion limit report it and parsing resumes at the next key` `` |
| `tests/testsuite/parse_document.rs::array_within_recursion_limit` | `` ParseDocumentTest.`arrays nested to the recursion limit parse without errors` `` |
| `tests/testsuite/parse_document.rs::array_outside_recursion_limit` | `` ParseDocumentTest.`arrays nested past the recursion limit report it and parsing resumes at the next key` `` |
| `tests/testsuite/parse_document.rs::array_and_inline_table_within_recursion_limit` | `` ParseDocumentTest.`an inline table in an array within the recursion limit parses without errors` `` |
| `tests/testsuite/parse_document.rs::array_and_inline_table_outside_recursion_limit` | `` ParseDocumentTest.`inline tables and arrays nested past the recursion limit report it and parsing resumes` `` |
| `tests/testsuite/parse_document.rs::hex_with_spaces` | `` ParseDocumentTest.`a hexadecimal prefix followed by a space reports an unquoted string value` `` |
| `tests/testsuite/parse_document.rs::hex_with_bad_chars` | `` ParseDocumentTest.`a hexadecimal number with a non-hex digit reports an invalid hexadecimal number` `` |
| `tests/testsuite/parse_document.rs::float_with_bad_underscore` | `` ParseDocumentTest.`an underscore before a float's dot reports it may only go between digits` `` |
| `tests/testsuite/parse_document.rs::many_extra_equals` | `` ParseDocumentTest.`thousands of equals signs after a key report an error without overflowing` `` |
| `tests/testsuite/parse_value.rs::value_empty` | `` ParseValueTest.`an empty value reports that string values must be quoted` `` |
| `tests/testsuite/parse_value.rs::value_datetime` | `` ParseValueTest.`a local date-time with a fraction parses as one scalar` `` |
| `tests/testsuite/parse_value.rs::value_negative_integer` | `` ParseValueTest.`a negative integer parses as one scalar` `` |
| `tests/testsuite/parse_value.rs::value_float` | `` ParseValueTest.`a float with only an exponent parses as one scalar` `` |
| `tests/testsuite/parse_value.rs::value_integer_with_seps` | `` ParseValueTest.`a float with underscores in every part parses as one scalar` `` |
| `tests/testsuite/parse_value.rs::value_ml_string_literal_single_line` | `` ParseValueTest.`a multi-line literal string on one line keeps its backslashes` `` |
| `tests/testsuite/parse_value.rs::value_ml_string_literal_multiline` | `` ParseValueTest.`a multi-line literal string over several lines parses as one scalar` `` |
| `tests/testsuite/parse_value.rs::value_string_escaped_unicode` | `` ParseValueTest.`a basic string with a unicode escape parses as one scalar` `` |
| `tests/testsuite/parse_value.rs::value_string_escaped` | `` ParseValueTest.`a basic string with every short escape parses as one scalar` `` |
| `tests/testsuite/parse_value.rs::value_inline_table` | `` ParseValueTest.`an inline table value emits its keys and values` `` |
| `tests/testsuite/parse_value.rs::value_array` | `` ParseValueTest.`an array value of inline tables emits each table's events` `` |
| `tests/testsuite/parse_value.rs::array_empty` | `` ParseValueTest.`an empty array parses as open and close` `` |
| `tests/testsuite/parse_value.rs::array_whitespace` | `` ParseValueTest.`an array holding only whitespace parses as empty` `` |
| `tests/testsuite/parse_value.rs::array_integers_newlines` | `` ParseValueTest.`an array split over lines keeps its newlines as events` `` |
| `tests/testsuite/parse_value.rs::array_integers_trailing_comment` | `` ParseValueTest.`an array keeps a comment after its trailing comma` `` |
| `tests/testsuite/parse_value.rs::array_comments_only` | `` ParseValueTest.`an array holding only comments parses without values` `` |
| `tests/testsuite/parse_value.rs::array_integers_surrounded_comment` | `` ParseValueTest.`an array keeps comments around its value and its comma` `` |
| `tests/testsuite/parse_value.rs::array_integer` | `` ParseValueTest.`an array of one integer parses` `` |
| `tests/testsuite/parse_value.rs::array_trailing_comma` | `` ParseValueTest.`an array of one value accepts a trailing comma` `` |
| `tests/testsuite/parse_value.rs::array_string_types` | `` ParseValueTest.`an array mixes all four string kinds` `` |
| `tests/testsuite/parse_value.rs::array_integers_trailing_comma` | `` ParseValueTest.`an array of integers accepts a trailing comma` `` |
| `tests/testsuite/parse_value.rs::array_integers` | `` ParseValueTest.`an array of integers parses` `` |
| `tests/testsuite/parse_value.rs::array_floats` | `` ParseValueTest.`an array of floats parses` `` |
| `tests/testsuite/parse_value.rs::array_strings` | `` ParseValueTest.`an array of basic strings parses` `` |
| `tests/testsuite/parse_value.rs::array_nested_same_type` | `` ParseValueTest.`nested arrays of integers parse` `` |
| `tests/testsuite/parse_value.rs::array_nested_multiple_types` | `` ParseValueTest.`nested arrays of different types parse` `` |
| `tests/testsuite/parse_value.rs::array_inline_table` | `` ParseValueTest.`an array of inline tables parses` `` |
| `tests/testsuite/parse_value.rs::array_no_close` | `` ParseValueTest.`an array without a closing bracket reports an unclosed array` `` |
| `tests/testsuite/parse_value.rs::array_only_comma` | `` ParseValueTest.`an array holding only a comma reports an extra comma` `` |
| `tests/testsuite/parse_value.rs::array_leading_comma` | `` ParseValueTest.`an array starting with a comma reports an extra comma` `` |
| `tests/testsuite/parse_value.rs::array_extra_trailing_comma` | `` ParseValueTest.`an array ending in two commas reports an extra comma` `` |
| `tests/testsuite/parse_value.rs::datetime_offset_lower_t` | `` ParseValueTest.`an offset date-time accepts a lower case t` `` |
| `tests/testsuite/parse_value.rs::datetime_offset_space` | `` ParseValueTest.`an offset date-time accepts a space for the T` `` |
| `tests/testsuite/parse_value.rs::datetime_offset_z` | `` ParseValueTest.`an offset date-time in UTC parses` `` |
| `tests/testsuite/parse_value.rs::datetime_offset_lower_z` | `` ParseValueTest.`an offset date-time accepts a lower case z` `` |
| `tests/testsuite/parse_value.rs::datetime_offset_hour` | `` ParseValueTest.`an offset date-time with an hour offset parses` `` |
| `tests/testsuite/parse_value.rs::datetime_offset_minutes` | `` ParseValueTest.`an offset date-time with a minutes offset parses` `` |
| `tests/testsuite/parse_value.rs::datetime_local` | `` ParseValueTest.`a local date-time parses` `` |
| `tests/testsuite/parse_value.rs::datetime_local_fractional_seconds` | `` ParseValueTest.`a local date-time with fractional seconds parses` `` |
| `tests/testsuite/parse_value.rs::datetime_local_fractional_seconds_truncated` | `` ParseValueTest.`an offset date-time with more than nine fraction digits parses` `` |
| `tests/testsuite/parse_value.rs::datetime_date` | `` ParseValueTest.`a local date parses` `` |
| `tests/testsuite/parse_value.rs::datetime_time` | `` ParseValueTest.`a local time parses` `` |
| `tests/testsuite/parse_value.rs::datetime_time_fractional_seconds` | `` ParseValueTest.`a local time with fractional seconds parses` `` |
| `tests/testsuite/parse_value.rs::inline_table_empty` | `` ParseValueTest.`an empty inline table parses as open and close` `` |
| `tests/testsuite/parse_value.rs::inline_table_ws` | `` ParseValueTest.`an inline table holding only whitespace parses as empty` `` |
| `tests/testsuite/parse_value.rs::inline_table_key_value` | `` ParseValueTest.`an inline table with one pair parses` `` |
| `tests/testsuite/parse_value.rs::inline_table_multiple_key_values` | `` ParseValueTest.`an inline table with two pairs parses` `` |
| `tests/testsuite/parse_value.rs::inline_table_dotted_key` | `` ParseValueTest.`an inline table with a dotted key parses` `` |
| `tests/testsuite/parse_value.rs::inline_table_only_comma` | `` ParseValueTest.`an inline table holding only a comma reports an extra comma` `` |
| `tests/testsuite/parse_value.rs::inline_table_leading_comma` | `` ParseValueTest.`an inline table starting with a comma reports an extra comma` `` |
| `tests/testsuite/parse_value.rs::inline_table_trailing_comma` | `` ParseValueTest.`an inline table accepts a trailing comma` `` |
| `tests/testsuite/parse_value.rs::inline_table_no_comma` | `` ParseValueTest.`an inline table without a comma between pairs reports the missing comma` `` |
| `tests/testsuite/parse_value.rs::inline_table_no_eq` | `` ParseValueTest.`an inline table pair without an equals sign reports the missing assignment` `` |
| `tests/testsuite/parse_value.rs::inline_table_no_close` | `` ParseValueTest.`an inline table without a closing brace reports an unclosed inline table` `` |
| `tests/testsuite/parse_value.rs::inline_table_repeated_key` | `` ParseValueTest.`an inline table with a repeated key parses without an error` `` |
| `tests/testsuite/parse_value.rs::integer` | `` ParseValueTest.`a decimal integer parses` `` |
| `tests/testsuite/parse_value.rs::integer_positive` | `` ParseValueTest.`an integer with a plus sign parses` `` |
| `tests/testsuite/parse_value.rs::integer_zero` | `` ParseValueTest.`zero parses as an integer` `` |
| `tests/testsuite/parse_value.rs::integer_negative` | `` ParseValueTest.`an integer with a minus sign parses` `` |
| `tests/testsuite/parse_value.rs::integer_one_sep` | `` ParseValueTest.`an integer with one underscore parses` `` |
| `tests/testsuite/parse_value.rs::integer_multiple_seps` | `` ParseValueTest.`an integer with several underscores parses` `` |
| `tests/testsuite/parse_value.rs::integer_every_other_sep` | `` ParseValueTest.`an integer with an underscore between every digit parses` `` |
| `tests/testsuite/parse_value.rs::integer_neg_sep` | `` ParseValueTest.`an underscore after a minus sign reports a misplaced underscore` `` |
| `tests/testsuite/parse_value.rs::integer_pos_sep` | `` ParseValueTest.`an underscore after a plus sign reports a misplaced underscore` `` |
| `tests/testsuite/parse_value.rs::integer_leading_sep` | `` ParseValueTest.`a leading underscore reports a misplaced underscore` `` |
| `tests/testsuite/parse_value.rs::integer_trailing_sep` | `` ParseValueTest.`a trailing underscore reports a misplaced underscore` `` |
| `tests/testsuite/parse_value.rs::integer_hex` | `` ParseValueTest.`a hexadecimal integer parses` `` |
| `tests/testsuite/parse_value.rs::integer_hex_neg` | `` ParseValueTest.`a sign after the hex prefix reports an unexpected sign` `` |
| `tests/testsuite/parse_value.rs::integer_neg_hex` | `` ParseValueTest.`a signed hexadecimal integer reports that a radix cannot be signed` `` |
| `tests/testsuite/parse_value.rs::integer_oct_sep` | `` ParseValueTest.`an octal integer with underscores parses` `` |
| `tests/testsuite/parse_value.rs::integer_bin_sep` | `` ParseValueTest.`a binary integer with underscores parses` `` |
| `tests/testsuite/parse_value.rs::integer_i64_min` | `` ParseValueTest.`the smallest Long parses` `` |
| `tests/testsuite/parse_value.rs::integer_i64_max` | `` ParseValueTest.`the largest Long parses` `` |
| `tests/testsuite/parse_value.rs::integer_exceed_i64` | `` ParseValueTest.`an integer beyond Long reports that it failed to parse` `` |
| `tests/testsuite/parse_value.rs::float` | `` ParseValueTest.`a float with a fraction parses` `` |
| `tests/testsuite/parse_value.rs::float_positive` | `` ParseValueTest.`a float with a plus sign parses` `` |
| `tests/testsuite/parse_value.rs::float_negative` | `` ParseValueTest.`a float with a minus sign parses` `` |
| `tests/testsuite/parse_value.rs::float_pos` | `` ParseValueTest.`a float with a positive exponent and no sign parses` `` |
| `tests/testsuite/parse_value.rs::float_exp_positive` | `` ParseValueTest.`a float with a plus signed exponent parses` `` |
| `tests/testsuite/parse_value.rs::float_exp_negative` | `` ParseValueTest.`a float with a minus signed exponent parses` `` |
| `tests/testsuite/parse_value.rs::float_negative_exp_negative` | `` ParseValueTest.`a negative float with a negative upper case exponent parses` `` |
| `tests/testsuite/parse_value.rs::float_sep` | `` ParseValueTest.`a float with underscores in the fraction and exponent parses` `` |
| `tests/testsuite/parse_value.rs::float_sep_bad` | `` ParseValueTest.`a float with misplaced underscores reports an invalid float and exponent` `` |
| `tests/testsuite/parse_value.rs::float_f64_min` | `` ParseValueTest.`the most negative Double parses` `` |
| `tests/testsuite/parse_value.rs::float_f64_max` | `` ParseValueTest.`the largest Double parses` `` |
| `tests/testsuite/parse_value.rs::float_nan` | `` ParseValueTest.`nan parses as a float` `` |
| `tests/testsuite/parse_value.rs::float_nan_positive` | `` ParseValueTest.`plus nan parses as a float` `` |
| `tests/testsuite/parse_value.rs::float_nan_negative` | `` ParseValueTest.`minus nan parses as a float` `` |
| `tests/testsuite/parse_value.rs::float_inf` | `` ParseValueTest.`inf parses as a float` `` |
| `tests/testsuite/parse_value.rs::float_inf_positive` | `` ParseValueTest.`plus inf parses as a float` `` |
| `tests/testsuite/parse_value.rs::float_inf_negative` | `` ParseValueTest.`minus inf parses as a float` `` |
| `tests/testsuite/parse_value.rs::float_exceed_f64` | `` ParseValueTest.`a float beyond Double parses without an error` `` |
| `tests/testsuite/parse_value.rs::string_escaped` | `` ParseValueTest.`a basic string with quotes tabs and long unicode escapes parses` `` |
| `tests/testsuite/parse_value.rs::string_escaped_escape` | `` ParseValueTest.`a basic string accepts the escape character escape` `` |
| `tests/testsuite/parse_value.rs::hex_escape` | `` ParseValueTest.`a basic string accepts hex escapes` `` |
| `tests/testsuite/parse_value.rs::string_ml_string_multiple_lines` | `` ParseValueTest.`a multi-line basic string over several lines parses` `` |
| `tests/testsuite/parse_value.rs::string_confusing_quotes_1` | `` ParseValueTest.`a multi-line basic string holding an escaped quote and two quotes parses` `` |
| `tests/testsuite/parse_value.rs::string_confusing_quotes_2` | `` ParseValueTest.`a multi-line basic string ending in an escaped backslash parses` `` |
| `tests/testsuite/parse_value.rs::string_confusing_quotes_3` | `` ParseValueTest.`a multi-line basic string closed by only two quotes reports it as invalid` `` |
| `tests/testsuite/parse_value.rs::string_confusing_quotes_4` | `` ParseValueTest.`a multi-line basic string left open by an escaped quote reports a missing escape` `` |
| `tests/testsuite/parse_value.rs::string` | `` ParseValueTest.`a line-ending backslash in a multi-line basic string trims the next lines` `` |
| `tests/testsuite/parse_value.rs::string_trailing_slash_1` | `` ParseValueTest.`a multi-line basic string holding only a line-ending backslash is empty` `` |
| `tests/testsuite/parse_value.rs::string_trailing_slash_2` | `` ParseValueTest.`consecutive line-ending backslashes leave a multi-line basic string empty` `` |
| `tests/testsuite/parse_value.rs::string_trailing_slash_3` | `` ParseValueTest.`a line-ending backslash before the closing quotes trims the indentation` `` |
| `tests/testsuite/parse_value.rs::string_literal_path_1` | `` ParseValueTest.`a literal string keeps a Windows path's backslashes` `` |
| `tests/testsuite/parse_value.rs::string_literal_path_2` | `` ParseValueTest.`a literal string keeps a UNC path's backslashes and dollar sign` `` |
| `tests/testsuite/parse_value.rs::string_literal_quotes` | `` ParseValueTest.`a literal string keeps double quotes` `` |
| `tests/testsuite/parse_value.rs::string_literal_escaped_1` | `` ParseValueTest.`a literal string keeps a regex's backslashes` `` |
| `tests/testsuite/parse_value.rs::string_literal_escaped_2` | `` ParseValueTest.`a multi-line literal string keeps a regex's backslashes` `` |
| `tests/testsuite/parse_value.rs::string_literal_confusing_quotes` | `` ParseValueTest.`a multi-line literal string keeps one quote at each end` `` |
| `tests/testsuite/parse_value.rs::string_ml_string_literal_multiple_lines` | `` ParseValueTest.`a multi-line literal string trims its first newline only` `` |
| `tests/testsuite/parse_value.rs::string_missing_opening` | `` ParseValueTest.`text ending in a quote reports a missing opening quote` `` |
| `tests/testsuite/parse_simple_key.rs::simple_key_empty` | `` ParseSimpleKeyTest.`an empty input reports an invalid key` `` |
| `tests/testsuite/parse_simple_key.rs::simple_key_unquoted_ascii` | `` ParseSimpleKeyTest.`an unquoted ASCII key is one simple key event` `` |
| `tests/testsuite/parse_simple_key.rs::simple_key_string` | `` ParseSimpleKeyTest.`a basic string key is one simple key event with its escapes undecoded` `` |
| `tests/testsuite/parse_simple_key.rs::simple_key_string_literal` | `` ParseSimpleKeyTest.`a literal string key is one simple key event` `` |

### `toml_writer`

| upstream | Kotlin |
|---|---|
| `tests/float.rs::zero` | `` WriterFloatTest.`zero writes with a zero fraction` `` |
| `tests/float.rs::neg_zero` | `` WriterFloatTest.`negative zero writes its sign and a zero fraction` `` |
| `tests/float.rs::inf` | `` WriterFloatTest.`positive infinity writes as inf` `` |
| `tests/float.rs::neg_inf` | `` WriterFloatTest.`negative infinity writes as minus inf` `` |
| `tests/float.rs::nan` | `` WriterFloatTest.`a positive NaN writes as nan` `` |
| `tests/float.rs::neg_nan` | `` WriterFloatTest.`a negative NaN writes as minus nan` `` |
| `tests/integer.rs::positive` | `` WriterIntegerTest.`a positive integer writes in every radix` `` |
| `tests/integer.rs::negative` | `` WriterIntegerTest.`a negative integer writes only in decimal` `` |
| `tests/string.rs::empty` | `` WriterStringTest.`an empty string is quoted as a key and as a value` `` |
| `tests/string.rs::alpha` | `` WriterStringTest.`letters write as a bare key and a basic string` `` |
| `tests/string.rs::ident` | `` WriterStringTest.`letters dashes and underscores write as a bare key` `` |
| `tests/string.rs::one_single_quote` | `` WriterStringTest.`a string holding single quotes defaults to basic quotes` `` |
| `tests/string.rs::two_single_quote` | `` WriterStringTest.`a string holding doubled single quotes defaults to basic quotes` `` |
| `tests/string.rs::three_single_quote` | `` WriterStringTest.`a string holding tripled single quotes rules out both literal forms` `` |
| `tests/string.rs::one_double_quote` | `` WriterStringTest.`a string holding double quotes defaults to literal quotes` `` |
| `tests/string.rs::two_double_quote` | `` WriterStringTest.`a string holding doubled double quotes defaults to literal quotes` `` |
| `tests/string.rs::three_double_quote` | `` WriterStringTest.`a string holding tripled double quotes defaults to literal quotes` `` |
| `tests/string.rs::mixed_quote_1` | `` WriterStringTest.`a double quote then a single quote defaults to a multi-line basic string` `` |
| `tests/string.rs::mixed_quote_2` | `` WriterStringTest.`escaped double quotes beside doubled single quotes default to a multi-line literal` `` |
| `tests/string.rs::escape` | `` WriterStringTest.`a string holding backslashes defaults to literal quotes` `` |
| `tests/string.rs::cr` | `` WriterStringTest.`lone carriage returns are escaped and rule out both literal forms` `` |
| `tests/string.rs::lf` | `` WriterStringTest.`a string with newlines defaults to a multi-line basic string` `` |
| `tests/string.rs::crlf` | `` WriterStringTest.`CRLF breaks write as escaped carriage returns in a multi-line basic string` `` |
| `tests/string.rs::tab` | `` WriterStringTest.`tabs are escaped in the default basic string` `` |
| `tests/string.rs::many_quotes` | `` WriterStringTest.`a run of 256 double quotes defaults to literal quotes` `` |
| `tests/string.rs::parseable` (proptest over `\PC*`) | `` WriterStringTest.`the default key and string encodings of random text parse back` `` (512 seeded random strings) |
| `src/lib.rs` doctest | `` WriterDocTest.`a table header and a key value pair write through the writer calls` `` |
| `src/string.rs` doctest on `TomlStringBuilder` | `` WriterDocTest.`a string with newlines writes as a multi-line basic string by default` `` |
| `src/integer.rs` doctest on `TomlIntegerFormat` | `` WriterDocTest.`an integer format writes ten as lower-case hex` `` |

`tests/string.rs` also parses every default encoding with an older `toml` release to keep new Cargo readable by
old Cargo. That crate has no Kotlin form; the port parses the same `key = value` text with its own parser and
checks the decoded key and value, which holds the encodings to the same grammar.

Beyond upstream's tests, `FloatFormatTest` compares the shortest digits, `Display`, `Debug` and TOML text of
2,400 floats with Rust's own output, recorded by `src/commonTest/snapshots/oracle/floats.rs`.

### `toml_edit`

| upstream | Kotlin |
|---|---|
| `tests/testsuite/edit.rs::parsed_document_outlives_input` | `` EditTest.`a parsed document prints its input unchanged and keeps a value's decor across an edit` `` |
| `tests/testsuite/edit.rs::parse_error_outlives_input` | `` EditTest.`a parse error renders against the input it came from` `` |
| `tests/testsuite/edit.rs::document_display_propagates_write_errors` | `` EditTest.`writing a document fails when the writer refuses any part of its text` `` |
| `tests/testsuite/edit.rs::assign_whitespace` | `` EditTest.`a parsed document keeps the whitespace and comments around every key and value` `` |
| `tests/testsuite/edit.rs::test_add_root_decor` | `` EditTest.`a document's decor and trailing text print around its tables` `` |
| `tests/testsuite/edit.rs::test_no_root_decor` | `` EditTest.`a parsed document has no decor and prints its trailing text at the end` `` |
| `tests/testsuite/edit.rs::test_insert_leaf_table` | `` EditTest.`a new sub-table prints after its parent's existing sub-tables` `` |
| `tests/testsuite/edit.rs::test_inserted_leaf_table_goes_after_last_sibling` | `` EditTest.`a new sub-table prints after the last sibling table even past other tables` `` |
| `tests/testsuite/edit.rs::test_inserting_tables_from_different_parsed_docs` | `` EditTest.`a table copied from another parsed document prints in its new document` `` |
| `tests/testsuite/edit.rs::test_insert_nonleaf_table` | `` EditTest.`a new table with a sub-table prints both headers after the existing tables` `` |
| `tests/testsuite/edit.rs::test_insert_array` | `` EditTest.`a new array of tables prints one header per table after the existing tables` `` |
| `tests/testsuite/edit.rs::test_insert_values` | `` EditTest.`keys set under an implicit table print in a new header above its sub-table` `` |
| `tests/testsuite/edit.rs::test_insert_key_with_quotes` | `` EditTest.`a new table key that needs quoting prints as a literal string in its header` `` |
| `tests/testsuite/edit.rs::test_remove_leaf_table` | `` EditTest.`removing a sub-table keeps the comment above its header` `` |
| `tests/testsuite/edit.rs::test_remove_nonleaf_table` | `` EditTest.`removing a table removes its sub-tables and keeps the comments above their headers` `` |
| `tests/testsuite/edit.rs::test_remove_array_entry` | `` EditTest.`removing an array of tables element removes only its header and body` `` |
| `tests/testsuite/edit.rs::test_remove_array` | `` EditTest.`removing an array of tables removes every header and body it printed` `` |
| `tests/testsuite/edit.rs::test_remove_value` | `` EditTest.`removing a key returns its value and keeps the comment above it` `` |
| `tests/testsuite/edit.rs::test_remove_last_value_from_implicit` | `` EditTest.`an implicit table left empty prints nothing` `` |
| `tests/testsuite/edit.rs::test_sort_values` | `` EditTest.`sorting a table's keys moves each key with its comments` `` |
| `tests/testsuite/edit.rs::test_sort_values_by` | `` EditTest.`sorting a table's keys by a comparator orders them by their spelling` `` |
| `tests/testsuite/edit.rs::test_set_position` | `` EditTest.`a table's position sets the order of its header in the document` `` |
| `tests/testsuite/edit.rs::test_set_position_negative` | `` EditTest.`a table with a negative position prints before the other tables` `` |
| `tests/testsuite/edit.rs::test_multiple_zero_positions` | `` EditTest.`tables with the same position keep their document order` `` |
| `tests/testsuite/edit.rs::test_multiple_max_usize_positions` | `` EditTest.`tables at the largest position print after the others in document order` `` |
| `tests/testsuite/edit.rs::test_insert_replace_into_array` | `` EditTest.`inserting and setting array elements keeps their decor and replacing resets it` `` |
| `tests/testsuite/edit.rs::test_remove_from_array` | `` EditTest.`removing array elements returns them and leaves an empty array as brackets` `` |
| `tests/testsuite/edit.rs::test_format_array` | `` EditTest.`resetting an array's formatting prints it on one line with default spacing` `` |
| `tests/testsuite/edit.rs::test_insert_into_inline_table` | `` EditTest.`getOrPut adds a key to an inline table and resetting prints default spacing` `` |
| `tests/testsuite/edit.rs::test_remove_from_inline_table` | `` EditTest.`removing inline table keys keeps the spacing of the ones left` `` |
| `tests/testsuite/edit.rs::test_as_table_like` | `` EditTest.`tables and inline tables read as TableLike and an array of tables does not` `` |
| `tests/testsuite/edit.rs::test_inline_table_append` | `` EditTest.`putAll copies another inline table's pairs and leaves that table as it was` `` |
| `tests/testsuite/edit.rs::test_insert_dotted_into_std_table` | `` EditTest.`a dotted sub-table set before its keys prints as dotted keys under the table header` `` |
| `tests/testsuite/edit.rs::test_insert_dotted_into_implicit_table` | `` EditTest.`a sub-table made dotted after its keys prints as dotted keys under the table header` `` |
| `tests/testsuite/edit.rs::sorting_with_references` | `` EditTest.`an array of strings sorts with a comparator over its values` `` |
| `tests/testsuite/edit.rs::table_str_key_whitespace` | `` EditTest.`an array of tables put at a string key replaces the value there` `` |
| `tests/testsuite/edit.rs::table_key_decor_whitespace` | `` EditTest.`an array of tables put at a parsed key prints the key's whitespace in its header` `` |
| `tests/testsuite/edit.rs::table_into_inline` | `` EditTest.`makeValue turns a table and its sub-tables into one inline table` `` |
| `tests/testsuite/edit.rs::inline_table_to_table` | `` EditTest.`an inline table converted to a table prints as a table with a header` `` |
| `tests/testsuite/edit.rs::array_of_tables_to_array` | `` EditTest.`makeValue turns an array of tables into an array of inline tables` `` |
| `tests/testsuite/edit.rs::test_key_from_str` | `` EditTest.`Key parse reads bare quoted and literal keys to their names` `` |
| `tests/testsuite/edit.rs::despan_keys` | `` EditTest.`a parsed key put into a document prints after the existing keys` `` |
| `tests/testsuite/edit.rs::key_repr_roundtrip` | `` EditTest.`a parsed key prints back with its own quoting` `` |
| `tests/testsuite/edit.rs::key_value_roundtrip` | `` EditTest.`a key built from a name prints with the simplest quoting that parses back` `` |
| `tests/testsuite/edit.rs::table_under_inline` | `` EditTest.`a key written under a dotted inline table prints as a dotted key inside it` `` |
| `tests/testsuite/edit.rs::array_of_tables_insert_at_beginning` | `` EditTest.`a table added at the start of an array of tables prints first` `` |
| `tests/testsuite/edit.rs::array_of_tables_insert_in_middle` | `` EditTest.`a table added in the middle of an array of tables prints between its neighbours` `` |
| `tests/testsuite/edit.rs::array_of_tables_insert_at_end` | `` EditTest.`a table added at the end of an array of tables prints last` `` |
| `tests/testsuite/edit.rs::array_of_tables_insert_out_of_bounds` | `` EditTest.`adding a table past the end of an array of tables fails with IndexOutOfBounds` `` |
| `tests/testsuite/edit.rs::inline_table_to_table_with_comment` | `` EditTest.`an inline table replaced by a table keeps the comment above its key` `` |
| `tests/testsuite/edit.rs::inline_table_to_array_of_tables_with_comment` | `` EditTest.`an array of inline tables replaced by an array of tables keeps the comment above its key` `` |
| `tests/testsuite/edit.rs::array_of_tables_replace` | `` EditTest.`setting an array of tables element returns the old table and prints the new one` `` |
| `tests/testsuite/edit.rs::array_of_tables_replace_out_of_bounds` | `` EditTest.`setting a table past the end of an array of tables fails with IndexOutOfBounds` `` |
| `tests/compliance/parse.rs::test_value_from_str` | `` ComplianceParseTest.`each kind of value text parses as its own value kind` `` |
| `tests/compliance/parse.rs::test_key_unification` | `` ComplianceParseTest.`headers spelling the same key differently each print as written` `` |
| `tests/compliance/parse.rs::crlf` | `` ComplianceParseTest.`a document with CRLF line endings parses` `` |
| `tests/compliance/parse.rs::fun_with_strings` | `` ComplianceParseTest.`basic literal and multi-line strings decode their escapes and line continuations` `` |
| `tests/compliance/parse.rs::tables_in_arrays` | `` ComplianceParseTest.`a sub-table under each array of tables element belongs to that element` `` |
| `tests/compliance/parse.rs::empty_table` | `` ComplianceParseTest.`a header with no keys makes an empty table` `` |
| `tests/compliance/parse.rs::mixed_table_issue_527` | `` ComplianceParseTest.`a header under a table holding a dotted key prints as written` `` |
| `tests/compliance/parse.rs::fruit` | `` ComplianceParseTest.`nested arrays of tables and sub-tables read back by index` `` |
| `tests/compliance/parse.rs::blank_literal_string` | `` ComplianceParseTest.`an empty literal string reads as empty` `` |
| `tests/compliance/parse.rs::many_blank` | `` ComplianceParseTest.`a multi-line string of blank lines keeps all but the trimmed first newline` `` |
| `tests/compliance/parse.rs::literal_eats_crlf` | `` ComplianceParseTest.`a line-ending backslash trims a CRLF and the whitespace after it` `` |
| `tests/compliance/parse.rs::floats` | `` ComplianceParseTest.`floats with exponents and underscores decode to their values` `` |
| `tests/compliance/parse.rs::bare_key_names` | `` ComplianceParseTest.`bare and quoted keys of any allowed spelling are read` `` |
| `tests/compliance/parse.rs::table_names` | `` ComplianceParseTest.`quoted table names may hold spaces dots and quotes` `` |
| `tests/compliance/parse.rs::inline_tables` | `` ComplianceParseTest.`empty nested and multi-line inline tables parse` `` |
| `tests/compliance/parse.rs::number_underscores` | `` ComplianceParseTest.`integers with underscores and signs decode to their values` `` |
| `tests/compliance/parse.rs::empty_string` | `` ComplianceParseTest.`an empty basic string reads as empty` `` |
| `tests/compliance/parse.rs::datetimes` | `` ComplianceParseTest.`offset date-times print back as written` `` |
| `tests/compliance/parse.rs::dont_use_dotted_key_prefix_on_table_fuzz_57049` | `` ComplianceParseTest.`a header sharing a dotted key's prefix prints after it as written` `` |
| `tests/compliance/parse.rs::dotted_key_comment_roundtrip` | `` ComplianceParseTest.`dotted keys with comments and blank lines between them print as written` `` |
| `tests/compliance/parse.rs::string_repr_roundtrip` | `` ComplianceParseTest.`a parsed string prints with its own spelling` `` |
| `tests/compliance/parse.rs::string_value_roundtrip` | `` ComplianceParseTest.`a string built from text picks a spelling that parses back` `` |
| `tests/compliance/parse.rs::array_recursion_limit` | `` ComplianceParseTest.`arrays nested 300 deep are refused and 20 deep are read` `` |
| `tests/compliance/parse.rs::inline_table_recursion_limit` | `` ComplianceParseTest.`inline tables nested 300 deep are refused and 20 deep are read` `` |
| `tests/compliance/parse.rs::table_key_recursion_limit` | `` ComplianceParseTest.`a header key of 300 parts is refused and one of 20 is read` `` |
| `tests/compliance/parse.rs::dotted_key_recursion_limit` | `` ComplianceParseTest.`a dotted key of 300 parts is refused and one of 20 is read` `` |
| `tests/compliance/parse.rs::inline_dotted_key_recursion_limit` | `` ComplianceParseTest.`a dotted key of 300 parts in an inline table is refused and one of 20 is read` `` |
| `tests/compliance/parse.rs::garbage1` | `` ComplianceParseTest.`garbage inside an inline table reports an extra assignment at column 5` `` |
| `tests/compliance/parse.rs::garbage2` | `` ComplianceParseTest.`garbage inside a closed inline table reports an extra assignment at column 5` `` |
| `tests/compliance/parse.rs::garbage3` | `` ComplianceParseTest.`a line of two equals signs reports an extra equals sign at column 2` `` |
| `tests/compliance/invalid.rs::basic_string_escape` | `` ComplianceInvalidTest.`a control character in a basic string is refused at its column` `` |
| `tests/compliance/invalid.rs::literal_escape` | `` ComplianceInvalidTest.`a control character in a literal string is refused at its column` `` |
| `tests/compliance/invalid.rs::stray_cr` | `` ComplianceInvalidTest.`a carriage return without a newline is refused in every kind of string and array` `` |
| `tests/compliance/invalid.rs::duplicate_key_with_crlf` | `` ComplianceInvalidTest.`a duplicate key under CRLF line endings is reported on its own line` `` |
| `tests/compliance/invalid.rs::inline_table_missing_key` | `` ComplianceInvalidTest.`an inline table element without a key is refused` `` |
| `tests/compliance/invalid.rs::inline_table_missing_key_in_array` | `` ComplianceInvalidTest.`an inline table element without a key inside an array is refused` `` |
| `tests/compliance/invalid.rs::emoji_error_span` | `` ComplianceInvalidTest.`an error span covers a whole multi-byte character` `` |
| `tests/compliance/invalid.rs::text_error_span` | `` ComplianceInvalidTest.`an error span covers the whole unquoted word` `` |
| `tests/compliance/invalid.rs::fuzzed_68144_error_span` | `` ComplianceInvalidTest.`a bad escape value reports an empty span where the escape value is missing` `` |
| `examples/visit.rs::visit_correct` | `` VisitExampleTest.`a recursive walk collects every dependency name of a Cargo manifest` `` |
| `examples/visit.rs::visit_mut_correct` | `` VisitExampleTest.`a recursive edit makes dependency tables regular and each dependency inline` `` |
| `src/value.rs::tests::from_iter_formatting` | `` EditUnitTest.`an array built from strings prints with default spacing` `` |
| `src/value.rs::string_roundtrip` | `` EditUnitTest.`a string value prints as text that parses back` `` |
| `src/key.rs::string_roundtrip` | `` EditUnitTest.`a key prints as text that parses back` `` |
| `src/item.rs::string_roundtrip` | `` EditUnitTest.`an item prints as text that parses back` `` |
| `src/document.rs::default_roundtrip` | `` EditUnitTest.`an empty document prints as text that parses back` `` |
| `src/encode.rs::test::parseable_string` (proptest over `\PC*`) | `` EditUnitTest.`random printable strings print as values that parse back` `` (512 seeded random strings) |
| `src/encode.rs::test::parseable_key` (proptest over `\PC*`) | `` EditUnitTest.`random printable strings print as keys that parse back` `` (512 seeded random strings) |
| `src/error.rs::test_translate_position::empty` | `` EditUnitTest.`an offset in empty input is line 0 column 0` `` |
| `src/error.rs::test_translate_position::start` | `` EditUnitTest.`the first offset is line 0 column 0` `` |
| `src/error.rs::test_translate_position::end` | `` EditUnitTest.`the last byte's offset is its column on the first line` `` |
| `src/error.rs::test_translate_position::after` | `` EditUnitTest.`the offset past the end is the column after the last byte` `` |
| `src/error.rs::test_translate_position::first_line` | `` EditUnitTest.`an offset in the first line is its column` `` |
| `src/error.rs::test_translate_position::end_of_line` | `` EditUnitTest.`the offset of a newline is the column at the end of its line` `` |
| `src/error.rs::test_translate_position::start_of_second_line` | `` EditUnitTest.`the offset after a newline is column 0 of the next line` `` |
| `src/error.rs::test_translate_position::second_line` | `` EditUnitTest.`an offset in the second line counts columns from its start` `` |
| `src/lib.rs` doctests (3) | `` EditDocTest.`the module example adds an inline table under a dotted header and formats it` ``, `` EditDocTest.`a string set on a new document prints in double quotes` ``, `` EditDocTest.`a parsed value set on a new document prints with its own spelling` `` |
| `README.md` doctest (`#[doc = include_str!("../README.md")]` in `src/lib.rs`) | `` EditDocTest.`the README example keeps the text after the last header` `` |
| `src/array.rs` doctests (10) | `` EditDocTest.`an empty array can be created` ``, `` EditDocTest.`an array's size counts its elements` ``, `` EditDocTest.`an array is empty until an element is added` ``, `` EditDocTest.`an array takes elements of different types` ``, `` EditDocTest.`an array takes a parsed value with its spelling` ``, `` EditDocTest.`an array takes a value inserted at its start` ``, `` EditDocTest.`an array takes a parsed value inserted at its start` ``, `` EditDocTest.`an array element can be replaced` ``, `` EditDocTest.`an array element can be replaced by a parsed value without keeping its decor` ``, `` EditDocTest.`removing an array element shrinks the array` `` |
| `src/array_of_tables.rs` doctest on `replace` | `` EditDocTest.`an array of tables element can be set to another table` `` |
| `src/item.rs` doctest on `value` | `` EditDocTest.`string values that need escaping print as literal strings` `` |
| `src/table.rs` doctests (`set_implicit`, `Entry::key`, `OccupiedEntry::key`, `VacantEntry::key`) | `` EditDocTest.`an implicit table with a sub-table prints only the sub-table's header` ``, `` EditDocTest.`getOrPut on an empty table stores the key under its name` ``, `` EditDocTest.`getOrPut on a present key keeps the key under its name` ``, `` EditDocTest.`getOrPut on a missing key stores the key under its name` `` |
| `src/inline_table.rs` doctest on `InlineEntry::key` | `` EditDocTest.`getOrPut on an inline table stores the key under its name` `` |
| `src/value.rs` doctests (`decor_mut`, `decor`, `decorated`) | `` EditDocTest.`a new value's decor has no suffix` ``, `` EditDocTest.`a new value's decor reads as default` ``, `` EditDocTest.`a value's decor prints around its text` `` |
| `src/visit.rs` doctest | `` EditDocTest.`walking a document collects every string in tables and inline tables` `` |
| `src/visit_mut.rs` doctest | `` EditDocTest.`walking a document replaces each float with a string and keeps its decor` `` |
| `tests/decoder_compliance.rs` + `tests/decoder.rs` (toml-test harness at 1.1.0 and `tests/fixtures`, valid) | `` ComplianceHarnessTest.`every valid suite and upstream fixture decodes to its JSON` `` |
| `tests/decoder_compliance.rs` (invalid, each error checked against `tests/snapshots/invalid/**.stderr`) | `` ComplianceHarnessTest.`every invalid suite and upstream fixture is refused with its snapshot's error text` `` (560 snapshots, copied; section 5 lists the texts kotoml changed) |
| `tests/encoder_compliance.rs` + `tests/encoder.rs` | `` ComplianceHarnessTest.`every valid fixture's JSON encodes to TOML that decodes to the same values` `` |

The harness compares tagged JSON by toml-test's own rules (`TaggedJson.kt`): floats as numbers with `nan` equal
to `nan` and `inf` to `+inf`, offset date-times as instants, local ones by value whatever the fraction's
precision. A fixture that is not UTF-8 is refused with Rust's `Utf8Error` text, as upstream's harness reports it.

The `tests/serde` suite is `serde`-only and not ported.

### The official suite

| check | `files-toml-1.0.0` (parsed as `V1_0`) | `files-toml-1.1.0` |
|---|---|---|
| every valid file decodes to its `.json` | `` SuiteTest.`every valid V1_0 file decodes to its JSON` `` | `` SuiteTest.`every valid V1_1 file decodes to its JSON` `` |
| every valid file prints back byte for byte | `` SuiteTest.`every valid V1_0 file prints back byte for byte` `` | `` SuiteTest.`every valid V1_1 file prints back byte for byte` `` |
| every valid file's JSON, encoded and decoded, gives the same values | `` SuiteTest.`every valid V1_0 file's JSON encodes and decodes to the same values` `` | `` SuiteTest.`every valid V1_1 file's JSON encodes and decodes to the same values` `` |
| every invalid file is refused | `` SuiteTest.`every invalid V1_0 file is refused` `` | `` SuiteTest.`every invalid V1_1 file is refused` `` |

The additions of section 2 are pinned by `PreservationTest` and `TomlVersionTest`.

The same lists also pass under toml-test's own runner, which drives the `kotoml decode` and `kotoml encode`
commands of [`:app`](../app/README.md) and compares their output by its own rules.

## 5. Intended differences

| where | upstream | Kotlin | why |
|---|---|---|---|
| `Offset::Custom` display | `minutes *= -1` overflows (panics in debug builds) for `i16::MIN` | prints the magnitude computed in `Int` | an `i16::MIN` offset is not a TOML value; Kotlin has no panic to copy |
| `TomlSource` over a `String` with an unpaired surrogate | not representable: `&str` is always valid UTF-8 | lexed as if U+FFFD stood there, which is what `encodeToByteArray` writes | Kotlin strings can hold what Rust strings cannot |
| `ValidateWhitespace` given a span that does not slice its source | panics (`expect("token spans are valid")`) | skips validating that span | spans come from the lexer, so only a misused receiver reaches this |
| `Lexer::next` after the end | returns `None` | returns another `Eof` token | Kotlin's `Iterator.next` past `hasNext() == false` would have to throw |
| `parseDocument` with no `RecursionGuard` | recurses once per nesting level | iterates with a heap stack | a stack overflow on Kotlin/Native is a crash, not a value |
| `WriteTomlValue.of(Map)` | `BTreeMap` writes sorted, `HashMap` in hash order | the map's own iteration order | Kotlin maps carry their order; pass a sorted map for `BTreeMap`'s |
| `WriteTomlValue.of(Char)` | a `char` is a Unicode scalar value | a `Char` is a UTF-16 unit, so a lone surrogate is written as is | Kotlin has no scalar-value type |
| `Array` | named `Array` | `TomlArray` | a star import of `Array` would shadow `kotlin.Array` |
| `Table::get`, `Item::get` | `get` | `getOrNull` | `get` is the index operator |
| `TableLike` | an open trait whose item functions are `iter`, `get`, `insert`, `remove`, with entries | a sealed interface over `Table` and `InlineTable` with `entries`, `getItem`, `putItem`, `removeItem` and no entries | an `InlineTable` already uses the plain names for values, and Kotlin cannot overload on the return type |
| `Key::parse` | parses a dotted path | `Key.parsePath`; `Key.parse` is `FromStr`, one key | `parse` reads one of the type, as `Value.parse` and `Datetime.parse` do |
| `Formatted<T>`, `Repr`, `RawString` | public, `Value::String(Formatted<String>)` | internal; a scalar `Value` has its own constructor and `value`, `spelling`, `displaySpelling` and `decor` properties | a caller reads and builds values without unwrapping a formatting container |
| `Value::from(…)`, `FromIterator for Value` | one conversion per source type | the constructors `Value.String(…)` … `Value.InlineTable(…)` | a constructor names the kind; `Value.from(Value)`, a clone, is `copy()` |
| `Value::as_*`, `Item::as_*` scalar and container getters, `is_*` | functions, with `_mut` twins | `OrNull` properties, no `Mut` twins, no predicates | Kotlin's `xOrNull` convention; the `Mut` twin returns the same object, and `type` or `is Value.X` answers the predicate |
| `Decor` | mutable through `decor_mut()`, `set_prefix`, `set_suffix`, `clear` | an immutable data class behind a `var decor` on each holder | a decor read out of one item can be stored on another without the two sharing a change |
| `span()` on `Value`, `Key`, `Table`, `InlineTable`, `Array`, `ArrayOfTables`, `Item`, `Formatted`, `Repr`, `RawString`, `Decor` | public, `None` once a document is editable | not public | an editable document never holds spans; a parse error still carries its `span` |
| `Date`, `Time` fields | `u16`, `u8`, `u32` | `Int` | Kotlin's unsigned types need a `u` suffix and conversions at every use; parsing still checks every range |
| `Datetime` from a date or a time | `From<Date>`, `From<Time>` | `Datetime(date = …)`, `Datetime(time = …)` with the other parts defaulting to `null` | one constructor covers all four kinds |
| `Value::fmt` | `fmt` | `resetFormatting()` | says what it does to the value |
| The escapes a bad escape's error lists | `b`, `e`, `f`, `n`, `r`, `\`, `"`, `x`, `u`, `U` | adds `t`; under TOML 1.0 also leaves out `e` and `x` | `\t` is a valid escape; 1.0 has no `\e` or `\x` |
| A `-00:00` offset | `Offset::Custom { minutes: 0 }`, printed `+00:00` | `Offset.Unknown`, printed `-00:00` | valid TOML stays as written; RFC 3339 gives `-00:00` its own meaning, an unknown local offset |
| A duplicate key's error | "duplicate key" | names the key as TOML spells it: "duplicate key `a`", "duplicate key `\"\"`" | the reader should not have to find which key |
| An error that expects nothing more | "…, expected nothing" | the message alone, such as "invalid float" | "expected nothing" says nothing |
| Bytes that are not UTF-8 | the message alone, with no position | the message under its line, column and caret; the line shows only what comes before the bad bytes | every other parse error points at its place; platforms decode bad bytes differently |
| A dotted key deeper than the nesting limit | "recursion limit", with no position | the same message, pointing at the key | as above |
| An integer prefix with no digits (`0x`, `0o`, `0b`) | "integer number overflowed" | `TomlParseError.Reason.MissingDigits(radix)`: "expected hexadecimal digits after `0x`", and `0o`, `0b` alike | nothing overflowed; the digits are missing |
| `Item::into_value`, `into_table`, `into_array_of_tables`, `Table::into_inline_table`, `InlineTable::into_table`, `ArrayOfTables::into_array` | consume the source | `Item`'s are internal; `toInlineTable()`, `toTable()` and `toTomlArray()` convert a copy and leave the source as it is | Kotlin cannot consume a caller's variable, and a moved-out source left empty in the document would vanish from its printout |
| `*place = value` through `&mut` | assignment | `Item.assign`, `holder.decor = …`, `array[i] = v`, `table[key] = v`, `put` | see "Items are slots, values are not" |
| Errors | text: `ParseError::description`, `TomlError::message`, `DatetimeParseError`'s `what` / `expected` | typed reasons (`ParseErrorReason`, `TomlParseError.Reason`, `DatetimeExpected`); upstream's text comes from `TomlParseError.render(source)` | a caller branches on what went wrong without matching text, and the wording is decided where it is shown |
| `description` on `Encoding`, `ScalarKind`, `IntegerRadix`, `TokenKind`, `EventKind` | public, English text | not ported | the public API carries typed values; `TomlParseError.render(source)` is the one place that words them |
| `toml_parser`, `toml_writer` | public crates | internal packages, except the payload types of `TomlParseError` | a caller reads, edits and prints TOML through the document model, so the lexer, event parser and writer stay free to change |
| `ParseError`, `ErrorSink` | public | internal; a parse failure reaches a caller as `TomlParseError`, whose `Syntax` reason carries the `ParseErrorReason` | the document parser is the only reporter |
| `Span` accessors | `start()`, `end()`, `len()`, `is_empty()` | `start`, `end` and `length` properties of a data class | Kotlin reads a field as a property; an empty span is one whose `length` is zero or less |
| `Source` | named `Source` | `TomlSource` | `kotlinx.io.Source` is the name a caller holding a stream already uses |
| `Visit`, `VisitMut` | traits with one overridable hook per node | not ported | an exhaustive `when` over the sealed `Value` walks the tree in plain recursion, and the compiler names every case it misses |
| `TomlError` | holds a copy of the input to render from | `render` takes the source; the error keeps a byte `span` and a `TextPosition` | the error stays small, and the caller already holds the text |
| Duplicate key and dotted key over a value | no key named | `DuplicateKey(key)`, `DottedKeyExtendsValue(key, type)` | the reason says which key |
| `Index` on a missing key | panics (`index not found`) | returns an empty item, or null for `InlineTable` | reading through a missing key never fails, and the empty item joins the table when written to |
| `IndexMut` before a write | inserts an empty slot (or turns an empty item into an inline table) at access time | joins the parent at the first write | reading in Kotlin cannot be told from writing; the document only changes when written |
| Storing an `Item` | moves it | stores a copy | see "Items are slots, values are not" |
| Collection vocabulary of `Table`, `InlineTable`, `Array`, `ArrayOfTables` | `len`, `push`, `insert`, `replace`, `remove`, `retain`, `extend`, `sort_by`, `sort_by_key`, `sort_values`, `sort_values_by`, `FromIterator`, `fmt`, `clone`, `iter`, `get_mut`, `set_x` / `x` pairs | `size`, `add`, `add(index, …)` or `put`, `operator set`, `removeAt` or `remove`, `retainAll` and `removeAll` returning whether they removed anything, `addAll` or `putAll`, `sortWith`, `sortBy`, `tomlArrayOf(…)` and `toTomlArray()` and their siblings, `resetFormatting()`, `copy()`, `Iterable`, `getOrNull` or `get`, one `var` per pair | a Kotlin caller reaches for the names Kotlin's collections use; `get_mut` and `iter_mut` hand out the same object as `get` and `iter` |
| Sorting a table | `sort_values()` sorts by key; `TableLike` has `sort_values` | no argument-free sort: `sortBy { (key, _) -> key }` sorts by key; `TableLike` has no sort | a table sorts the way a Kotlin collection does. On `TableLike` the pair type would differ between `Table` (`Pair<Key, Item>`) and `InlineTable` (`Pair<Key, Value>`), so `InlineTable` would carry two `sortWith` overloads that a lambda cannot choose between |
| `insert(&str, item)` on a present key (`Table`, `InlineTable`) | resets the key's spelling and decor (`key_mut().fmt()`) | `put(String, …)` keeps the key's spelling and the comments above it, as `table[key] = item` does, and keeps its spacing unless the item moves it between a `key = value` line and a `[header]` or `[[header]]`, where the spacing goes back to the default; `put(Key, …)` replaces all of it | `put` is `MutableMap.put`, which only returns what `set` discards; spacing belongs to the line a key stood on, so upstream's `table_str_key_whitespace` still prints `[[bookmark]]` |
| `ArrayOfTables::replace` | the new table keeps its own decor | `operator set(index, table)` gives the new table the old one's header decor, the lines above its `[[header]]` and the comment after it; `replace(index, table, keepDecor = false)` keeps the new table's own | `TomlArray`'s `set` keeps the old element's decor, and an array of tables reads the same way |
| `Entry`, `OccupiedEntry`, `VacantEntry`, `InlineEntry` and friends | an entry API | `getOrPut(key) { default }` | `MutableMap.getOrPut` covers what the entries are used for; an inline table's `getOrPut` returns the `Value` and a table's the stored `Item` |
| `KeyMut` | a view that changes a key's formatting but not its text | none: a `Key`'s text is a `val` and its formatting `var`s | the text cannot change through a `Key`, so a separate view protects nothing |
| `Item` construction | `Item::Value(…)`, `Item::Table(…)`, and the `value()`, `table()`, `array()` helpers | the constructors `Item(…)`, and `operator set` overloads that take a `Value`, `Table`, `ArrayOfTables` or a scalar | a constructor names the kind, and `doc["a"]["b"] = "x"` needs no wrapper |
| Writing a non-value into an `Array` element or a non-table into an `ArrayOfTables` element (`*item = other`, `Item::make_value`) | stores it as a placeholder that `len` counts and printing skips | `TomlEditError.NotAValue` / `NotATableElement`, nothing written; `makeValue()` returns the result | an element of the wrong kind would vanish from the printed document |
| Writing a `Table` or `ArrayOfTables` into an `InlineTable` slot (`*item = other`, `TableLike::entry(…).or_insert`, an index on an empty item) | stores it; a table prints as dotted keys, an array of tables not at all | `TomlEditError.NotAValue`, nothing written; `putItem` converts the item to an inline table or array, as `TableLike::insert` does | an inline table holds values; a dotted `InlineTable` prints the same dotted keys, as `` EditTest.`a key written under a dotted inline table prints as a dotted key inside it` `` shows |
| `Array::insert / replace / remove`, `ArrayOfTables::insert / replace / remove`, `IndexMut` on a scalar, `TableLike::insert` of an empty item into an inline table | panic | `TomlEditError` | nothing throws |
| `IndexMut<&str> for InlineTable` on a missing key | panics | `inlineTable[key] = value` adds the key | Kotlin's `[]=` is a write, as `MutableMap.set` is |
| `DocumentMut` | named `DocumentMut`, beside the read-only `Document` | `TomlDocument`, the one document type | there is no second document type to tell it apart from |
| `Document` | a read-only parse result that borrows its input, turned editable with `into_mut` | not public | Kotlin cannot hand out a read-only view of mutable classes, so a second type would only add a step |
| The root of a `DocumentMut` | an `Item`, reached as an item or a table, that can be made to hold a non-table | `root: Table` | a document's root is always a table, and one property says so |
| Reading and writing a document | `&str` in through `FromStr`, text out through `Display` | also `TomlDocument.read(Source)` and `writeTo(Sink)`, failing with typed `TomlReadError` and `TomlIoError` | a Kotlin caller holding a file or stream uses kotlinx-io, and an I/O failure arrives as a value like a parse failure |
| `Display` into a writer that refuses text | stops part-way, propagating `fmt::Error` | `toString()` cannot fail; writing that string to an `FmtWrite` fails before anything is written | Kotlin's `toString` has no error channel |
| Line endings | every line printed with LF | each line keeps its own ending; an inserted line ends like the line before | the suite's round trip (`valid/newline-crlf`); upstream's `document_display_propagates_write_errors` therefore expects CRLF kept |
| A key spelled differently on several lines | every occurrence printed with the first spelling | each line keeps its own while the shared keys are unchanged | the suite's round trip (`valid/key/dotted-01`, `valid/table/with-literal-string`); upstream's `test_key_unification` therefore expects each header as written |
| Removing a key, a table or an element (`remove`, `removeItem`, `removeAt`, `retainAll`, `removeAll`, `clear`) | the comment and blank lines above it go with it | the comment and blank lines above it stay where they printed, above whatever follows; what belongs to it goes: the comment on its own line, every comment inside its value or body, and for a table or an array of tables the lines above every header after its first, those of its sub-tables and later elements | a comment above a key may be about something else, such as a commented-out alternative; upstream's `test_remove_leaf_table`, `test_remove_nonleaf_table`, `test_remove_array_entry`, `test_remove_array` and `test_remove_value` therefore expect the lines above kept, and `RemovalCommentScopeTest`, `RemovalKeepsCommentsTest` and `RemovalPropertyTest` pin the rule |
| Interleaved dotted keys | grouped by table (upstream issue 163) | printed in read order until sorted | the suite's round trip (`valid/spec-1.1.0/common-9`) |
| Text without a final newline | a newline is always added (upstream issue 1158) | kept while the document prints as it was read | the suite's round trip (`valid/table/no-eol-01`), without changing upstream's tests that edit such text |
| TOML 1.0 | not offered | `TomlVersion.V1_0` | the suite's 1.0.0 invalid files (`invalid/inline-table/trailing-comma` and eight more) |
