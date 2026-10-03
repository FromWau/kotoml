# Parity with upstream `toml_edit`

This file is the item-by-item proof that `:toml` ports `toml_edit` and the three crates under it. A row
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
- The conversions between kinds (`Table.toInlineTable()`, `InlineTable.toTable()`, `ArrayOfTables.toArray()`)
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
| `Date` (`year`, `month`, `day`) | `Date` (`Int`, `Int`, `Int`) | done | `DatetimeDisplayTest.datetimeWithoutSeconds`, `DatetimeParseTest.datetimeWithoutSeconds` |
| `Time` (`hour`, `minute`, `second`, `nanosecond`) | `Time` (`Int`, `Int`, `Int?`, `Int?`) | done | `DatetimeDisplayTest`, `DatetimeParseTest.timeWithoutSeconds` |
| `Offset::Z`, `Offset::Custom { minutes }` | `Offset.Z`, `Offset.Custom(minutes: Short)` | done | `DatetimeDisplayTest.datetimeWithoutSeconds` |
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
| `parser::RecursionGuard::new(_, u32)` | `RecursionGuard(EventReceiver, UInt)` | done | `ParseDocumentTest.*RecursionLimit` |
| `parser::Event::new_unchecked / kind / encoding / span` | `Event(...)`, `Event.newUnchecked`, same names | done | all parser tests |
| `parser::EventKind` (17 kinds), `description` | `EventKind`; `description` n/a, as for `TokenKind` | done | |
| `decoder::Encoding`, `description` | `Encoding`; `description` n/a, as for `TokenKind` | done | |
| `decoder::StringBuilder` | `DecodedStringBuilder` | done | `DecoderStringTest` |
| `impl StringBuilder for String` / `Cow<str>` | `StringOutput` | done | `DecoderStringTest`, `ParseValueTest` |
| `impl StringBuilder for &str` | `BorrowedOutput` | done | |
| `impl StringBuilder for ()` | `DecodedStringBuilder.DISCARD` | done | `ParseDocumentTest` (keys) |
| `decoder::ScalarKind` (`String`, `Boolean`, `DateTime`, `Float`, `Integer`), `description`, `invalid_description` | `ScalarKind` sealed interface; `description` n/a, as for `TokenKind`; `invalid_description` is `ParseErrorReason.InvalidScalar(kind)` | done | `ParseValueTest` |
| `decoder::IntegerRadix`, `description`, `value`, `invalid_description`, `Default` | `IntegerRadix`, `value`; `description` n/a, as for `TokenKind`; `Default` is `IntegerRadix.Dec`; `invalid_description` as for `ScalarKind` | done | `ParseValueTest` |
| derived `Ord` / `Hash` / `Eq` on `Span`, `Token`, `TokenKind`, `Event`, `EventKind`, `Encoding`, `ScalarKind`, `IntegerRadix`, `ParseError` | `Comparable` / `equals` / `hashCode` | done | |

### `toml_writer`

| upstream | Kotlin | status | tests |
|---|---|---|---|
| `core::fmt::Write` (the writer every function writes to), `fmt::Error`, `fmt::Result` | `fun interface FmtWrite`, `FmtError`, `EmptyResult<FmtError>`; `StringWriter` for `String` | done | `WriterDocTest`, `WriterStringTest` |
| `TomlWrite::open_table_header`, `close_table_header`, `open_array_of_tables_header`, `close_array_of_tables_header`, `open_inline_table`, `close_inline_table`, `open_array`, `close_array`, `key_sep`, `keyval_sep`, `key`, `value`, `val_sep`, `space`, `open_comment`, `newline` | extension functions on `FmtWrite`, same names | done | `WriterDocTest.crateExample` |
| `impl TomlWrite for W: fmt::Write` | the extensions apply to every `FmtWrite` | done | |
| `WriteTomlKey`, `ToTomlKey::to_toml_key` | `fun interface WriteTomlKey`, `WriteTomlKey.toTomlKey()` | done | `WriterStringTest` |
| `impl WriteTomlKey for str / String / Cow<str> / &V` | `WriteTomlKey.of(String)` | done | `WriterDocTest.crateExample` |
| `WriteTomlValue`, `ToTomlValue::to_toml_value` | `fun interface WriteTomlValue`, `WriteTomlValue.toTomlValue()` | done | `WriterFloatTest`, `WriterStringTest` |
| `impl WriteTomlValue for bool, u8, i8, u16, i16, u32, i32, u64, i64` | `WriteTomlValue.of(Boolean / UByte / Byte / UShort / Short / UInt / Int / ULong / Long)` | done | |
| `impl WriteTomlValue for u128, i128` | | n/a | Kotlin has no 128-bit integer |
| `impl WriteTomlValue for f32, f64` | `WriteTomlValue.of(Float / Double)` | done | `WriterFloatTest`, `FloatFormatTest` |
| `impl WriteTomlValue for char` | `WriteTomlValue.of(Char)` | done | |
| `impl WriteTomlValue for str / String / Cow<str> / &V` | `WriteTomlValue.of(String)` | done | `WriterDocTest.crateExample` |
| `impl WriteTomlValue for [V] / [V; N] / Vec<V>` | `WriteTomlValue.of(Iterable<WriteTomlValue>)` | done | |
| `impl WriteTomlValue for BTreeMap<K, V> / HashMap<K, V>` | `WriteTomlValue.of(Map<out WriteTomlKey, WriteTomlValue>)` | done | |
| `TomlStringBuilder::new`, `as_default`, `as_literal`, `as_ml_literal`, `as_basic_pretty`, `as_ml_basic_pretty`, `as_basic`, `as_ml_basic` | `TomlStringBuilder(String)`, same names | done | `WriterStringTest`, `WriterDocTest` |
| `TomlString` (`WriteTomlValue`, derived `Eq` / `Hash`) | `TomlString` | done | `WriterStringTest` |
| `TomlKeyBuilder::new`, `as_default`, `as_unquoted`, `as_literal`, `as_basic_pretty`, `as_basic` | `TomlKeyBuilder(String)`, same names | done | `WriterStringTest` |
| `TomlKey` (`WriteTomlKey`, derived `Eq` / `Hash`) | `TomlKey` | done | `WriterStringTest` |
| derived `Ord` on `TomlString`, `TomlKey` | | n/a | the order of private fields is not a contract; Kotlin offers no comparison |
| `TomlIntegerFormat::new`, `as_decimal`, `as_hex_upper`, `as_hex_lower`, `as_octal`, `as_binary`, `format`, `Default` | `TomlIntegerFormat()`, same names; `format(Long): TomlInteger?`, `format(ULong): TomlInteger` | done | `WriterIntegerTest`, `WriterDocTest` |
| `TomlInteger::new` | `TomlInteger(Long)`, `TomlInteger.of(ULong)` | done | `WriterDocTest.tomlIntegerFormatExample` |
| `impl WriteTomlValue for TomlInteger<N>` (every integer width) | `TomlInteger : WriteTomlValue` over `Long` and `ULong` | done | `WriterIntegerTest` |

### `toml_edit`

| upstream | Kotlin | status | tests |
|---|---|---|---|
| `DocumentMut::new`, `Default` | `TomlDocument()` | done | `EditUnitTest.documentDefaultRoundtrip` |
| `DocumentMut::as_item`, `as_item_mut`, `into_item`, `as_table`, `as_table_mut`, `into_table` | `root: Table` | done | `EditTest`, `VisitExampleTest` |
| `DocumentMut::iter` | `TomlDocument` is an `Iterable<Pair<String, Item>>` over the root table | done | `TomlDocumentTest` |
| `DocumentMut::set_trailing`, `trailing` | `var trailing: String` | done | `EditTest.testAddRootDecor`, `TomlDocumentTest` |
| `impl FromStr for DocumentMut` | `TomlDocument.parse(String)` and `parse(ByteArray)`, both `Result<TomlDocument, TomlParseError>` | done | `EditTest`, `ComplianceParseTest` |
| `impl Display for DocumentMut` | `toString()`, keeping line endings, spellings and dotted-key order (see section 2) | done | `EditTest`, `PreservationTest`, `SuiteTest.roundTrips*` |
| `impl Index<&str> / IndexMut<&str> for DocumentMut` | `operator get`, `operator set` | done | `EditTest` |
| `impl Deref / DerefMut<Target = Table> for DocumentMut` | `root`, plus forwards for `getOrNull`, `containsKey`, `operator contains`, `put`, `remove`, `getOrPut`, `size`, `isEmpty`, the `operator set` overloads, and `var decor` for `decor` / `decor_mut` | done | `EditTest.testAddRootDecor`, `SlotSemanticsTest` |
| `impl From<Table> for DocumentMut` | `TomlDocument(Table)` | done | |
| `impl Clone for DocumentMut` | `copy()` | done | `TomlDocumentTest` |
| `Document::new`, `Default`, `parse`, `as_item`, `into_item`, `as_table`, `into_table`, `iter`, `trailing`, `raw`, `into_mut`, `FromStr`, `Deref<Target = Table>` | | n/a | `TomlDocument.parse` gives the editable document directly; see section 5 |
| `ImDocument` (deprecated alias of `Document`) | | n/a | deprecated upstream since 0.23 |
| `Item::None / Value / Table / ArrayOfTables`, `Default` | the constructors `Item()`, `Item(Value)`, `Item(Table)`, `Item(ArrayOfTables)` | done | `EditTest` |
| `Item::or_insert` | | n/a | `Table.getOrPut`, or `if (item.isNone) item.assign(other)` |
| `Item::type_name` (as `type: TomlType`), `as_value(_mut)`, `as_table(_mut)`, `as_array_of_tables(_mut)`, `as_table_like(_mut)`, `is_none` | `type`, `valueOrNull`, `tableOrNull`, `arrayOfTablesOrNull`, `tableLikeOrNull`, `isNone` | done | `EditTest`, `ComplianceParseTest` |
| `Item::is_value`, `is_table`, `is_array_of_tables`, `is_table_like` | | n/a | `type`, or a check that the `OrNull` property is not null |
| `Item::as_str`, `as_integer`, `as_float`, `as_bool`, `as_datetime`, `as_array(_mut)`, `as_inline_table(_mut)` | `stringOrNull`, `longOrNull`, `doubleOrNull`, `booleanOrNull`, `datetimeOrNull`, `arrayOrNull`, `inlineTableOrNull` | done | `EditTest`, `ComplianceParseTest` |
| `Item::is_str` … `is_inline_table` | | n/a | `type`, or a check that the `OrNull` property is not null |
| `Item::span` | | n/a | an editable item never holds spans; see section 2 |
| `Item::get` / `get_mut` (`Option<&Item>`) | `getOrNull(String / Int)` | done | `EditTest` |
| `impl Index / IndexMut<usize / &str / String> for Item` | `operator get(String / Int)`, `operator set(String / Int, Item)`, and `operator set(String, …)` for a `Value`, `Table`, `ArrayOfTables`, `String`, `Long`, `Double`, `Boolean` or `Datetime` | done | `EditTest`, `ComplianceParseTest.fruit` |
| `Item::into_value / into_table / into_array_of_tables` (`Result<_, Item>`) | internal; `Table.toInlineTable()`, `InlineTable.toTable()` and `ArrayOfTables.toArray()` convert a copy | done | `EditTest.inlineTableToTable`, `SlotSemanticsTest` |
| `Item::make_value` | `makeValue()` | done | `EditTest.tableIntoInline` |
| `impl Display`, `impl Clone`, `From<Value / Table / ArrayOfTables>` | `toString()`, `copy()`, the constructors | done | `EditUnitTest.itemStringRoundtrip` |
| `impl FromStr for Item`, `From<&Item>`, `From<&str, i64, …>` | | n/a | `Item(Value.parse(…))`, `copy()`, `Item(Value.String(…))` and its siblings |
| `value()`, `table()`, `array()` | | n/a | the `operator set` overloads store a scalar, a `Value` or a table directly; otherwise `Item(…)` |
| `Value::String / Integer / Float / Boolean / Datetime / Array / InlineTable` | `Value.String(value, decor)` … `Value.Datetime(value, decor)`, `Value.Array(array, decor)`, `Value.InlineTable(table, decor)`; each scalar has `value`, `spelling` and `displaySpelling` | done | `EditTest`, `ValueTest` |
| `Value::type_name` (as `type: TomlType`), `as_str`, `as_integer`, `as_float`, `as_bool`, `as_datetime`, `as_array(_mut)`, `as_inline_table(_mut)` | `type`, `stringOrNull`, `longOrNull`, `doubleOrNull`, `booleanOrNull`, `datetimeOrNull`, `arrayOrNull`, `inlineTableOrNull` | done | `ValueTest`, `ComplianceParseTest` |
| `Value::is_str` … `is_inline_table` | | n/a | `is Value.String` and its siblings, or `type` |
| `Value::decor_mut`, `decor`, `decorated` | `var decor`, or the constructor's `decor` | done | `EditDocTest.valueDecorMut`, `valueDecor`, `valueDecorated` |
| `Value::fmt` | `resetFormatting()` | done | `ValueTest` |
| `Value::span` | | n/a | an editable value never holds spans; see section 2 |
| `impl FromStr for Value`, `impl Display`, `impl Clone` | `Value.parse`, `toString()`, `copy()` | done | `ComplianceParseTest`, `ValueTest` |
| `impl From<&str, String, i64, f64, bool, Datetime, Date, Time, Array, InlineTable, &Value> for Value` | the constructors: `Value.String(…)`, `Value.Datetime(Datetime(date = …))`, `Value.Array(…)`, `copy()` for `&Value` | done | `ComplianceParseTest` |
| `impl FromIterator<V> / FromIterator<(K, V)> for Value` | `Value.Array(values.toTomlArray())`, `Value.InlineTable(pairs.toInlineTable())` | done | `EditUnitTest.valueFromIterFormatting`, `EditTest.testInlineTableAppend` |
| `Array::new`, `fmt`, `trailing_comma` / `set_trailing_comma`, `trailing` / `set_trailing`, `decor(_mut)`, `len`, `is_empty`, `clear`, `get`, `get_mut` | `TomlArray()`, `resetFormatting()`, `var trailingComma`, `var trailing: String`, `var decor`, `size`, `isEmpty()`, `clear()`, `operator get`; no `span` | done | `EditTest`, `EditDocTest` |
| `Array::iter`, `iter_mut`, `IntoIterator`, `ArrayIter`, `ArrayIterMut`, `ArrayIntoIter` | `Iterable<Value>`; aliases n/a | done | `EditDocTest.visitMutFloatToString` |
| `Array::push`, `push_formatted` | `add(Value)`, plus `add(String / Long / Double / Boolean / Datetime)` | done | `EditDocTest.arrayPush`, `arrayPushFormatted` |
| `Array::insert`, `insert_formatted` | `add(index, value): EmptyResult<TomlEditError>` | done | `EditTest.testInsertReplaceIntoArray`, `EditDocTest.arrayInsert*` |
| `Array::replace`, `replace_formatted` | `operator set(index, value)` and `replace(index, value, keepDecor = true)`; `keepDecor = false` is `replace_formatted` | done | `EditTest.testInsertReplaceIntoArray`, `EditDocTest.arrayReplace*` |
| `Array::remove`, `retain`, `sort_by`, `sort_by_key` | `removeAt`, `retainAll`, `sortWith(Comparator<Value>)`, `sortBy(selector)` | done | `EditTest.testRemoveFromArray`, `EditTest.sortingWithReferences` |
| `impl Display / Extend / FromIterator for Array` | `toString()`, `addAll`, `tomlArrayOf(…)` and `Iterable<Value>.toTomlArray()` | done | `EditUnitTest.valueFromIterFormatting` |
| `ArrayOfTables::new`, `len`, `is_empty`, `clear`, `get`, `get_mut`, `push`, `retain`, `iter`, `iter_mut`, `IntoIterator`, iterator aliases | `ArrayOfTables()`, `size`, `isEmpty()`, `clear()`, `operator get`, `add`, `retainAll`, `Iterable<Table>`; no `span`; aliases n/a | done | `EditTest` |
| `ArrayOfTables::insert`, `replace`, `remove` | `add(index, table)`, `operator set(index, table)`, `removeAt`, returning `TomlEditError` where upstream panics | done | `EditTest.arrayOfTables*` |
| `ArrayOfTables::into_array` | `toArray()`, converting a copy | done | `EditTest.arrayOfTablesToArray`, `SlotSemanticsTest` |
| `impl Display / Extend / FromIterator for ArrayOfTables` | `toString()`, `addAll`, `arrayOfTablesOf(…)` and `Iterable<Table>.toArrayOfTables()` | done | |
| `Table::new`, `decor(_mut)`, `iter`, `iter_mut`, `IntoIterator`, `Iter`, `IterMut`, `IntoIter`, `len`, `is_empty`, `clear`, `contains_key` | `Table()`, `var decor`, `Iterable<Pair<String, Item>>`, `size`, `isEmpty()`, `clear()`, `containsKey` and `operator contains`; no `span`; aliases n/a | done | `EditTest` |
| `Table::set_implicit` / `is_implicit`, `set_dotted` / `is_dotted`, `set_position` / `position` | `var isImplicit`, `var isDotted`, `var position: Long?` (`isize` is `Long`) | done | `EditTest.testSetPosition*`, `EditDocTest.tableSetImplicit` |
| `Table::fmt`, `sort_values`, `sort_values_by`, `get_values` | `resetFormatting()`, `sortValues()`, `sortValuesWith(Comparator<Pair<Key, Item>>)`, `valuesWithPaths()` | done | `EditTest.testSortValues*` |
| `Table::get`, `get_mut` | `getOrNull` | done | `EditTest` |
| `Table::insert`, `insert_formatted` | `put(String, Item)`, `put(Key, Item)`, returning the item replaced | done | `EditTest`, `SlotSemanticsTest` |
| `Table::entry`, `entry_format`, `Entry`, `OccupiedEntry`, `VacantEntry`, `or_insert`, `or_insert_with` | `getOrPut(String / Key, () -> Item): Item` | done | `EditTest.testSetPositionNegative`, `EditDocTest.tableEntryKey`, `SlotSemanticsTest` |
| `Table::key`, `key_mut` | `key(name): Key?`, whose formatting is `var` | done | `PreservationTest` |
| `Table::remove`, `retain`, `into_inline_table` | `remove`, `retainAll`, `toInlineTable()` converting a copy | done | `EditTest` |
| `Table::get_key_value(_mut)`, `contains_table`, `contains_value`, `contains_array_of_tables`, `remove_entry` | | n/a | `key(name)` with `getOrNull(name)`; `getOrNull(name)?.tableOrNull != null` and its siblings; `key(name)` before `remove(name)` |
| `impl Index / IndexMut<&str> for Table` | `operator get` (a slot), `operator set` for an `Item`, `Value`, `Table`, `ArrayOfTables` or scalar | done | `EditTest`, `EditDocTest.itemValue` |
| `impl Display / Extend / FromIterator for Table` | `toString()`, `putAll`, `tableOf(…)` and `Iterable<Pair<String, Item>>.toTable()` | done | `EditDocTest.itemValue`, `SlotSemanticsTest` |
| `TableLike` (`iter`, `iter_mut`, `len`, `is_empty`, `clear`, `get`, `get_mut`, `contains_key`, `insert`, `remove`, `get_values`, `fmt`, `sort_values`, `set_dotted`, `is_dotted`, `key`, `key_mut`) | sealed `TableLike`: `items()`, `size`, `isEmpty()`, `clear()`, `item(key)`, `containsKey`, `putItem`, `removeItem`, `valuesWithPaths()`, `resetFormatting()`, `sortValues()`, `var isDotted`, `key(name)` | done | `EditTest.testAsTableLike`, `VisitExampleTest` |
| `TableLike::entry`, `entry_format`, `get_key_value(_mut)` | | n/a | `getOrPut` on `Table` or `InlineTable`; `key(name)` with `item(name)` |
| `InlineTable::new`, `fmt`, `trailing_comma` / `set_trailing_comma`, `trailing` / `set_trailing`, `set_dotted` / `is_dotted`, `decor(_mut)`, `iter`, `iter_mut`, `IntoIterator`, `len`, `is_empty`, `clear`, `contains_key` | `InlineTable()`, `resetFormatting()`, `var trailingComma`, `var trailing: String`, `var isDotted`, `var decor`, `Iterable<Pair<String, Value>>`, `size`, `isEmpty()`, `clear()`, `containsKey` and `operator contains`; no `span` | done | `EditTest` |
| `InlineTable::get`, `get_mut`, `insert`, `insert_formatted`, `remove`, `retain`, `key`, `key_mut` | `operator get`, `put(String / Key, Value)`, `remove`, `retainAll`, `key(name)` | done | `EditTest.testInsertIntoInlineTable`, `EditTest.testRemoveFromInlineTable` |
| `InlineTable::entry`, `entry_format`, `get_or_insert`, `InlineEntry`, `InlineOccupiedEntry`, `InlineVacantEntry` | `getOrPut(String / Key, () -> Value): Value` | done | `EditTest.testInsertIntoInlineTable`, `EditDocTest.inlineEntryKey` |
| `InlineTable::sort_values`, `sort_values_by`, `get_values`, `into_table` | `sortValues()`, `sortValuesWith(Comparator<Pair<Key, Value>>)`, `valuesWithPaths()`, `toTable()` converting a copy | done | `EditTest.inlineTableToTable` |
| `InlineTable::get_key_value(_mut)`, `remove_entry` | | n/a | `key(name)` with `operator get`; `key(name)` before `remove(name)` |
| `impl Index<&str> for InlineTable` | `operator get`, returning null where upstream panics | done | |
| `impl IndexMut<&str> for InlineTable` | `operator set` for a `Value` or scalar, adding a missing key | done | `SlotSemanticsTest` |
| `impl Display / Extend / FromIterator for InlineTable` | `toString()`, `putAll`, `inlineTableOf(…)` and `Iterable<Pair<String, Value>>.toInlineTable()` | done | `EditTest.testInlineTableAppend` |
| `Key::new`, `fmt`, `parse` (dotted), `with_leaf_decor`, `with_dotted_decor`, `leaf_decor(_mut)`, `dotted_decor(_mut)` | `Key(name, leafDecor, dottedDecor)`, `resetFormatting()`, `Key.parsePath`, `var leafDecor`, `var dottedDecor` | done | `EditTest.testKeyFromStr`, `ValueTest` |
| `Key::get`, `as_mut`, `KeyMut` | `val name`; a key's formatting is `var` on `Key` itself, so there is no `KeyMut` | done | `VisitExampleTest`, `PreservationTest` |
| `Key::as_repr`, `default_repr`, `display_repr` | `spelling`, `displaySpelling`; the default spelling is internal | done | `EditTest.testSortValuesBy` |
| `Key::span` | | n/a | an editable key never holds spans; see section 2 |
| `impl FromStr for Key` | `Key.parse` | done | `EditTest.keyValueRoundtrip` |
| `impl Display / Clone / Hash / Ord / Eq for Key`, `From<&str / String>` | `toString()`, `copy()`, `hashCode` / `compareTo` / `equals` by text, the constructor | done | |
| `impl Deref<Target = str> / Borrow<str> for Key`, `PartialEq<str / &str / String>`, `From<Key> for String` | `name` | n/a | Kotlin has no deref; compare `key.name` |
| `RawString` (`as_str`, `span`, `Default`, `From<&str / String / &String / Box<str>>`) | internal; decor and trailing text are `String`s | done | `EditTest.assignWhitespace` |
| `Repr` (`as_raw`, `span`) | internal; a value or key shows its `spelling` | done | `EditTest.assignWhitespace` |
| `Decor` (`new`, `clear`, `prefix`, `set_prefix`, `suffix`, `set_suffix`, `Default`) | immutable `data class Decor(prefix: String?, suffix: String?)`; `Decor()` for `default` and `clear`, `copy(prefix = …)` for `set_prefix` | done | `EditTest.testAddRootDecor`, `ValueTest` |
| `Formatted` (`new`, `value`, `into_value`, `as_repr`, `default_repr`, `display_repr`, `span`, `decor_mut`, `decor`, `fmt`), `Display`, `Eq` | internal; each scalar `Value` has `value`, `spelling`, `displaySpelling`, `decor` and `resetFormatting()` | done | `EditDocTest`, `ValueTest` |
| `ValueRepr` (sealed trait) | | n/a | each scalar `Value` has its own constructor |
| `TomlError` (`message`, `span`), `Display`, `Error`, `Eq`, `Hash` | `data class TomlParseError(reason, span, position, context, expected)`, an `IError`; `Display` is `render(source)` | done | `ComplianceInvalidTest`, `ComplianceParseTest.garbage*` |
| `visit::Visit` and `visit_document` … `visit_value` | | n/a | a recursive function with a `when` over the sealed `Value` walks the tree; `VisitExampleTest`, `EditDocTest.visitStringCollector` show how |
| `visit_mut::VisitMut` and `visit_document_mut` … `visit_value_mut` | | n/a | as for `Visit`; `VisitExampleTest`, `EditDocTest.visitMutFloatToString` show how |
| `de::*`, `ser::*`, `TomlError::custom`, `Serialize / Deserialize` impls | | serde-only | |
| `toml_datetime::*` re-export | the date-time types in the same package | done | |
| (none: an addition) | `TomlVersion`, and a `version` parameter on `TomlDocument.parse`, `TomlDocument.read`, `Value.parse`, `Key.parse`, `Key.parsePath` | added | `TomlVersionTest`, `SuiteTest.*Toml100` |
| (none: an addition) | `TomlDocument.read(Source)` and `TomlDocument.writeTo(Sink)` over kotlinx-io, failing with `TomlReadError` (`TomlIoError` or `Parse`) and `TomlIoError` | added | `TomlDocumentTest`, `EditDocTest.moduleReadmeReadAndWrite` |

## 4. Upstream tests

### `toml_datetime`

| upstream | Kotlin |
|---|---|
| `tests/display.rs::time_without_seconds` | `DatetimeDisplayTest.timeWithoutSeconds` |
| `tests/display.rs::time_without_seconds_with_nanoseconds` | `DatetimeDisplayTest.timeWithoutSecondsWithNanoseconds` |
| `tests/display.rs::time_with_zero_seconds_and_nanoseconds` | `DatetimeDisplayTest.timeWithZeroSecondsAndNanoseconds` |
| `tests/display.rs::datetime_without_seconds` | `DatetimeDisplayTest.datetimeWithoutSeconds` |
| `tests/parse.rs::only_t` | `DatetimeParseTest.onlyT` |
| `tests/parse.rs::only_tz` | `DatetimeParseTest.onlyTz` |
| `tests/parse.rs::only_tz_dot` | `DatetimeParseTest.onlyTzDot` |
| `tests/parse.rs::time_without_seconds` | `DatetimeParseTest.timeWithoutSeconds` |
| `tests/parse.rs::time_without_seconds_with_nanoseconds` | `DatetimeParseTest.timeWithoutSecondsWithNanoseconds` |
| `tests/parse.rs::datetime_without_seconds` | `DatetimeParseTest.datetimeWithoutSeconds` |

Upstream's `{:#?}` snapshots are compared byte for byte: the tests render values through a test-only
copy of Rust's derived `Debug` (`RustDebug.kt`), so the expected text is upstream's, unedited.

### `toml_parser`

Snapshot files (`tests/snapshots/*.txt`) are copied unedited to `src/commonTest/snapshots/toml_parser/`.

| upstream | Kotlin |
|---|---|
| `src/lexer/test.rs::test_lex_ascii_char` | `LexerTest.testLexAsciiChar` |
| `src/lexer/test.rs::test_lex_whitespace` | `LexerTest.testLexWhitespace` |
| `src/lexer/test.rs::test_lex_comment` | `LexerTest.testLexComment` |
| `src/lexer/test.rs::test_lex_crlf` | `LexerTest.testLexCrlf` |
| `src/lexer/test.rs::test_lex_literal_string` | `LexerTest.testLexLiteralString` |
| `src/lexer/test.rs::test_lex_ml_literal_string` | `LexerTest.testLexMlLiteralString` |
| `src/lexer/test.rs::test_lex_basic_string` | `LexerTest.testLexBasicString` |
| `src/lexer/test.rs::test_lex_atom` | `LexerTest.testLexAtom` |
| `src/lexer/test.rs::literal_strings` | `LexerTest.literalStrings` |
| `src/lexer/test.rs::basic_strings` | `LexerTest.basicStrings` |
| `src/lexer/test.rs::keylike` | `LexerTest.keylike` |
| `src/lexer/test.rs::all` | `LexerTest.all` |
| `src/lexer/test.rs::bare_cr_bad` | `LexerTest.bareCrBad` |
| `src/lexer/test.rs::bad_comment` | `LexerTest.badComment` |
| `src/decoder/string.rs::literal_string` | `DecoderStringTest.literalString` |
| `src/decoder/string.rs::ml_literal_string` | `DecoderStringTest.mlLiteralString` |
| `src/decoder/string.rs::basic_string` | `DecoderStringTest.basicString` |
| `src/decoder/string.rs::ml_basic_string` | `DecoderStringTest.mlBasicString` |
| `src/decoder/string.rs::unquoted_keys` | `DecoderStringTest.unquotedKeys` |
| `src/decoder/scalar.rs::many_redundant_signs` | `DecoderScalarTest.manyRedundantSigns` |
| `tests/testsuite/parse_document.rs::document_empty` | `ParseDocumentTest.documentEmpty` |
| `tests/testsuite/parse_document.rs::document_ws` | `ParseDocumentTest.documentWs` |
| `tests/testsuite/parse_document.rs::document_key_string` | `ParseDocumentTest.documentKeyString` |
| `tests/testsuite/parse_document.rs::document_key_string_comment` | `ParseDocumentTest.documentKeyStringComment` |
| `tests/testsuite/parse_document.rs::document_key_ws` | `ParseDocumentTest.documentKeyWs` |
| `tests/testsuite/parse_document.rs::document_table` | `ParseDocumentTest.documentTable` |
| `tests/testsuite/parse_document.rs::document_table_missing_key` | `ParseDocumentTest.documentTableMissingKey` |
| `tests/testsuite/parse_document.rs::document_table_dot` | `ParseDocumentTest.documentTableDot` |
| `tests/testsuite/parse_document.rs::document_table_dot_dot` | `ParseDocumentTest.documentTableDotDot` |
| `tests/testsuite/parse_document.rs::document_table_dot_key` | `ParseDocumentTest.documentTableDotKey` |
| `tests/testsuite/parse_document.rs::document_table_dot_dot_key` | `ParseDocumentTest.documentTableDotDotKey` |
| `tests/testsuite/parse_document.rs::document_table_key_dot` | `ParseDocumentTest.documentTableKeyDot` |
| `tests/testsuite/parse_document.rs::document_table_key_dot_dot` | `ParseDocumentTest.documentTableKeyDotDot` |
| `tests/testsuite/parse_document.rs::document_table_key_dot_dot_key` | `ParseDocumentTest.documentTableKeyDotDotKey` |
| `tests/testsuite/parse_document.rs::document_missing_key` | `ParseDocumentTest.documentMissingKey` |
| `tests/testsuite/parse_document.rs::document_dot` | `ParseDocumentTest.documentDot` |
| `tests/testsuite/parse_document.rs::document_dot_dot` | `ParseDocumentTest.documentDotDot` |
| `tests/testsuite/parse_document.rs::document_dot_key` | `ParseDocumentTest.documentDotKey` |
| `tests/testsuite/parse_document.rs::document_dot_dot_key` | `ParseDocumentTest.documentDotDotKey` |
| `tests/testsuite/parse_document.rs::document_key_dot` | `ParseDocumentTest.documentKeyDot` |
| `tests/testsuite/parse_document.rs::document_key_dot_dot` | `ParseDocumentTest.documentKeyDotDot` |
| `tests/testsuite/parse_document.rs::document_key_dot_dot_key` | `ParseDocumentTest.documentKeyDotDotKey` |
| `tests/testsuite/parse_document.rs::document_inline_table_missing_key` | `ParseDocumentTest.documentInlineTableMissingKey` |
| `tests/testsuite/parse_document.rs::document_inline_table_dot` | `ParseDocumentTest.documentInlineTableDot` |
| `tests/testsuite/parse_document.rs::document_inline_table_dot_dot` | `ParseDocumentTest.documentInlineTableDotDot` |
| `tests/testsuite/parse_document.rs::document_inline_table_dot_key` | `ParseDocumentTest.documentInlineTableDotKey` |
| `tests/testsuite/parse_document.rs::document_inline_table_dot_dot_key` | `ParseDocumentTest.documentInlineTableDotDotKey` |
| `tests/testsuite/parse_document.rs::document_inline_table_key_dot` | `ParseDocumentTest.documentInlineTableKeyDot` |
| `tests/testsuite/parse_document.rs::document_inline_table_key_dot_dot` | `ParseDocumentTest.documentInlineTableKeyDotDot` |
| `tests/testsuite/parse_document.rs::document_inline_table_key_dot_dot_key` | `ParseDocumentTest.documentInlineTableKeyDotDotKey` |
| `tests/testsuite/parse_document.rs::document_key_datetime` | `ParseDocumentTest.documentKeyDatetime` |
| `tests/testsuite/parse_document.rs::document_bom` | `ParseDocumentTest.documentBom` |
| `tests/testsuite/parse_document.rs::document_complex` | `ParseDocumentTest.documentComplex` |
| `tests/testsuite/parse_document.rs::document_invalid` | `ParseDocumentTest.documentInvalid` |
| `tests/testsuite/parse_document.rs::document_invalid_comment` | `ParseDocumentTest.documentInvalidComment` |
| `tests/testsuite/parse_document.rs::document_invalid_cr` | `ParseDocumentTest.documentInvalidCr` |
| `tests/testsuite/parse_document.rs::inline_table_within_recursion_limit` | `ParseDocumentTest.inlineTableWithinRecursionLimit` |
| `tests/testsuite/parse_document.rs::inline_table_outside_recursion_limit` | `ParseDocumentTest.inlineTableOutsideRecursionLimit` |
| `tests/testsuite/parse_document.rs::array_within_recursion_limit` | `ParseDocumentTest.arrayWithinRecursionLimit` |
| `tests/testsuite/parse_document.rs::array_outside_recursion_limit` | `ParseDocumentTest.arrayOutsideRecursionLimit` |
| `tests/testsuite/parse_document.rs::array_and_inline_table_within_recursion_limit` | `ParseDocumentTest.arrayAndInlineTableWithinRecursionLimit` |
| `tests/testsuite/parse_document.rs::array_and_inline_table_outside_recursion_limit` | `ParseDocumentTest.arrayAndInlineTableOutsideRecursionLimit` |
| `tests/testsuite/parse_document.rs::hex_with_spaces` | `ParseDocumentTest.hexWithSpaces` |
| `tests/testsuite/parse_document.rs::hex_with_bad_chars` | `ParseDocumentTest.hexWithBadChars` |
| `tests/testsuite/parse_document.rs::float_with_bad_underscore` | `ParseDocumentTest.floatWithBadUnderscore` |
| `tests/testsuite/parse_document.rs::many_extra_equals` | `ParseDocumentTest.manyExtraEquals` |
| `tests/testsuite/parse_value.rs::value_empty` | `ParseValueTest.valueEmpty` |
| `tests/testsuite/parse_value.rs::value_datetime` | `ParseValueTest.valueDatetime` |
| `tests/testsuite/parse_value.rs::value_negative_integer` | `ParseValueTest.valueNegativeInteger` |
| `tests/testsuite/parse_value.rs::value_float` | `ParseValueTest.valueFloat` |
| `tests/testsuite/parse_value.rs::value_integer_with_seps` | `ParseValueTest.valueIntegerWithSeps` |
| `tests/testsuite/parse_value.rs::value_ml_string_literal_single_line` | `ParseValueTest.valueMlStringLiteralSingleLine` |
| `tests/testsuite/parse_value.rs::value_ml_string_literal_multiline` | `ParseValueTest.valueMlStringLiteralMultiline` |
| `tests/testsuite/parse_value.rs::value_string_escaped_unicode` | `ParseValueTest.valueStringEscapedUnicode` |
| `tests/testsuite/parse_value.rs::value_string_escaped` | `ParseValueTest.valueStringEscaped` |
| `tests/testsuite/parse_value.rs::value_inline_table` | `ParseValueTest.valueInlineTable` |
| `tests/testsuite/parse_value.rs::value_array` | `ParseValueTest.valueArray` |
| `tests/testsuite/parse_value.rs::array_empty` | `ParseValueTest.arrayEmpty` |
| `tests/testsuite/parse_value.rs::array_whitespace` | `ParseValueTest.arrayWhitespace` |
| `tests/testsuite/parse_value.rs::array_integers_newlines` | `ParseValueTest.arrayIntegersNewlines` |
| `tests/testsuite/parse_value.rs::array_integers_trailing_comment` | `ParseValueTest.arrayIntegersTrailingComment` |
| `tests/testsuite/parse_value.rs::array_comments_only` | `ParseValueTest.arrayCommentsOnly` |
| `tests/testsuite/parse_value.rs::array_integers_surrounded_comment` | `ParseValueTest.arrayIntegersSurroundedComment` |
| `tests/testsuite/parse_value.rs::array_integer` | `ParseValueTest.arrayInteger` |
| `tests/testsuite/parse_value.rs::array_trailing_comma` | `ParseValueTest.arrayTrailingComma` |
| `tests/testsuite/parse_value.rs::array_string_types` | `ParseValueTest.arrayStringTypes` |
| `tests/testsuite/parse_value.rs::array_integers_trailing_comma` | `ParseValueTest.arrayIntegersTrailingComma` |
| `tests/testsuite/parse_value.rs::array_integers` | `ParseValueTest.arrayIntegers` |
| `tests/testsuite/parse_value.rs::array_floats` | `ParseValueTest.arrayFloats` |
| `tests/testsuite/parse_value.rs::array_strings` | `ParseValueTest.arrayStrings` |
| `tests/testsuite/parse_value.rs::array_nested_same_type` | `ParseValueTest.arrayNestedSameType` |
| `tests/testsuite/parse_value.rs::array_nested_multiple_types` | `ParseValueTest.arrayNestedMultipleTypes` |
| `tests/testsuite/parse_value.rs::array_inline_table` | `ParseValueTest.arrayInlineTable` |
| `tests/testsuite/parse_value.rs::array_no_close` | `ParseValueTest.arrayNoClose` |
| `tests/testsuite/parse_value.rs::array_only_comma` | `ParseValueTest.arrayOnlyComma` |
| `tests/testsuite/parse_value.rs::array_leading_comma` | `ParseValueTest.arrayLeadingComma` |
| `tests/testsuite/parse_value.rs::array_extra_trailing_comma` | `ParseValueTest.arrayExtraTrailingComma` |
| `tests/testsuite/parse_value.rs::datetime_offset_lower_t` | `ParseValueTest.datetimeOffsetLowerT` |
| `tests/testsuite/parse_value.rs::datetime_offset_space` | `ParseValueTest.datetimeOffsetSpace` |
| `tests/testsuite/parse_value.rs::datetime_offset_z` | `ParseValueTest.datetimeOffsetZ` |
| `tests/testsuite/parse_value.rs::datetime_offset_lower_z` | `ParseValueTest.datetimeOffsetLowerZ` |
| `tests/testsuite/parse_value.rs::datetime_offset_hour` | `ParseValueTest.datetimeOffsetHour` |
| `tests/testsuite/parse_value.rs::datetime_offset_minutes` | `ParseValueTest.datetimeOffsetMinutes` |
| `tests/testsuite/parse_value.rs::datetime_local` | `ParseValueTest.datetimeLocal` |
| `tests/testsuite/parse_value.rs::datetime_local_fractional_seconds` | `ParseValueTest.datetimeLocalFractionalSeconds` |
| `tests/testsuite/parse_value.rs::datetime_local_fractional_seconds_truncated` | `ParseValueTest.datetimeLocalFractionalSecondsTruncated` |
| `tests/testsuite/parse_value.rs::datetime_date` | `ParseValueTest.datetimeDate` |
| `tests/testsuite/parse_value.rs::datetime_time` | `ParseValueTest.datetimeTime` |
| `tests/testsuite/parse_value.rs::datetime_time_fractional_seconds` | `ParseValueTest.datetimeTimeFractionalSeconds` |
| `tests/testsuite/parse_value.rs::inline_table_empty` | `ParseValueTest.inlineTableEmpty` |
| `tests/testsuite/parse_value.rs::inline_table_ws` | `ParseValueTest.inlineTableWs` |
| `tests/testsuite/parse_value.rs::inline_table_key_value` | `ParseValueTest.inlineTableKeyValue` |
| `tests/testsuite/parse_value.rs::inline_table_multiple_key_values` | `ParseValueTest.inlineTableMultipleKeyValues` |
| `tests/testsuite/parse_value.rs::inline_table_dotted_key` | `ParseValueTest.inlineTableDottedKey` |
| `tests/testsuite/parse_value.rs::inline_table_only_comma` | `ParseValueTest.inlineTableOnlyComma` |
| `tests/testsuite/parse_value.rs::inline_table_leading_comma` | `ParseValueTest.inlineTableLeadingComma` |
| `tests/testsuite/parse_value.rs::inline_table_trailing_comma` | `ParseValueTest.inlineTableTrailingComma` |
| `tests/testsuite/parse_value.rs::inline_table_no_comma` | `ParseValueTest.inlineTableNoComma` |
| `tests/testsuite/parse_value.rs::inline_table_no_eq` | `ParseValueTest.inlineTableNoEq` |
| `tests/testsuite/parse_value.rs::inline_table_no_close` | `ParseValueTest.inlineTableNoClose` |
| `tests/testsuite/parse_value.rs::inline_table_repeated_key` | `ParseValueTest.inlineTableRepeatedKey` |
| `tests/testsuite/parse_value.rs::integer` | `ParseValueTest.integer` |
| `tests/testsuite/parse_value.rs::integer_positive` | `ParseValueTest.integerPositive` |
| `tests/testsuite/parse_value.rs::integer_zero` | `ParseValueTest.integerZero` |
| `tests/testsuite/parse_value.rs::integer_negative` | `ParseValueTest.integerNegative` |
| `tests/testsuite/parse_value.rs::integer_one_sep` | `ParseValueTest.integerOneSep` |
| `tests/testsuite/parse_value.rs::integer_multiple_seps` | `ParseValueTest.integerMultipleSeps` |
| `tests/testsuite/parse_value.rs::integer_every_other_sep` | `ParseValueTest.integerEveryOtherSep` |
| `tests/testsuite/parse_value.rs::integer_neg_sep` | `ParseValueTest.integerNegSep` |
| `tests/testsuite/parse_value.rs::integer_pos_sep` | `ParseValueTest.integerPosSep` |
| `tests/testsuite/parse_value.rs::integer_leading_sep` | `ParseValueTest.integerLeadingSep` |
| `tests/testsuite/parse_value.rs::integer_trailing_sep` | `ParseValueTest.integerTrailingSep` |
| `tests/testsuite/parse_value.rs::integer_hex` | `ParseValueTest.integerHex` |
| `tests/testsuite/parse_value.rs::integer_hex_neg` | `ParseValueTest.integerHexNeg` |
| `tests/testsuite/parse_value.rs::integer_neg_hex` | `ParseValueTest.integerNegHex` |
| `tests/testsuite/parse_value.rs::integer_oct_sep` | `ParseValueTest.integerOctSep` |
| `tests/testsuite/parse_value.rs::integer_bin_sep` | `ParseValueTest.integerBinSep` |
| `tests/testsuite/parse_value.rs::integer_i64_min` | `ParseValueTest.integerI64Min` |
| `tests/testsuite/parse_value.rs::integer_i64_max` | `ParseValueTest.integerI64Max` |
| `tests/testsuite/parse_value.rs::integer_exceed_i64` | `ParseValueTest.integerExceedI64` |
| `tests/testsuite/parse_value.rs::float` | `ParseValueTest.float` |
| `tests/testsuite/parse_value.rs::float_positive` | `ParseValueTest.floatPositive` |
| `tests/testsuite/parse_value.rs::float_negative` | `ParseValueTest.floatNegative` |
| `tests/testsuite/parse_value.rs::float_pos` | `ParseValueTest.floatPos` |
| `tests/testsuite/parse_value.rs::float_exp_positive` | `ParseValueTest.floatExpPositive` |
| `tests/testsuite/parse_value.rs::float_exp_negative` | `ParseValueTest.floatExpNegative` |
| `tests/testsuite/parse_value.rs::float_negative_exp_negative` | `ParseValueTest.floatNegativeExpNegative` |
| `tests/testsuite/parse_value.rs::float_sep` | `ParseValueTest.floatSep` |
| `tests/testsuite/parse_value.rs::float_sep_bad` | `ParseValueTest.floatSepBad` |
| `tests/testsuite/parse_value.rs::float_f64_min` | `ParseValueTest.floatF64Min` |
| `tests/testsuite/parse_value.rs::float_f64_max` | `ParseValueTest.floatF64Max` |
| `tests/testsuite/parse_value.rs::float_nan` | `ParseValueTest.floatNan` |
| `tests/testsuite/parse_value.rs::float_nan_positive` | `ParseValueTest.floatNanPositive` |
| `tests/testsuite/parse_value.rs::float_nan_negative` | `ParseValueTest.floatNanNegative` |
| `tests/testsuite/parse_value.rs::float_inf` | `ParseValueTest.floatInf` |
| `tests/testsuite/parse_value.rs::float_inf_positive` | `ParseValueTest.floatInfPositive` |
| `tests/testsuite/parse_value.rs::float_inf_negative` | `ParseValueTest.floatInfNegative` |
| `tests/testsuite/parse_value.rs::float_exceed_f64` | `ParseValueTest.floatExceedF64` |
| `tests/testsuite/parse_value.rs::string_escaped` | `ParseValueTest.stringEscaped` |
| `tests/testsuite/parse_value.rs::string_escaped_escape` | `ParseValueTest.stringEscapedEscape` |
| `tests/testsuite/parse_value.rs::hex_escape` | `ParseValueTest.hexEscape` |
| `tests/testsuite/parse_value.rs::string_ml_string_multiple_lines` | `ParseValueTest.stringMlStringMultipleLines` |
| `tests/testsuite/parse_value.rs::string_confusing_quotes_1` | `ParseValueTest.stringConfusingQuotes1` |
| `tests/testsuite/parse_value.rs::string_confusing_quotes_2` | `ParseValueTest.stringConfusingQuotes2` |
| `tests/testsuite/parse_value.rs::string_confusing_quotes_3` | `ParseValueTest.stringConfusingQuotes3` |
| `tests/testsuite/parse_value.rs::string_confusing_quotes_4` | `ParseValueTest.stringConfusingQuotes4` |
| `tests/testsuite/parse_value.rs::string` | `ParseValueTest.string` |
| `tests/testsuite/parse_value.rs::string_trailing_slash_1` | `ParseValueTest.stringTrailingSlash1` |
| `tests/testsuite/parse_value.rs::string_trailing_slash_2` | `ParseValueTest.stringTrailingSlash2` |
| `tests/testsuite/parse_value.rs::string_trailing_slash_3` | `ParseValueTest.stringTrailingSlash3` |
| `tests/testsuite/parse_value.rs::string_literal_path_1` | `ParseValueTest.stringLiteralPath1` |
| `tests/testsuite/parse_value.rs::string_literal_path_2` | `ParseValueTest.stringLiteralPath2` |
| `tests/testsuite/parse_value.rs::string_literal_quotes` | `ParseValueTest.stringLiteralQuotes` |
| `tests/testsuite/parse_value.rs::string_literal_escaped_1` | `ParseValueTest.stringLiteralEscaped1` |
| `tests/testsuite/parse_value.rs::string_literal_escaped_2` | `ParseValueTest.stringLiteralEscaped2` |
| `tests/testsuite/parse_value.rs::string_literal_confusing_quotes` | `ParseValueTest.stringLiteralConfusingQuotes` |
| `tests/testsuite/parse_value.rs::string_ml_string_literal_multiple_lines` | `ParseValueTest.stringMlStringLiteralMultipleLines` |
| `tests/testsuite/parse_value.rs::string_missing_opening` | `ParseValueTest.stringMissingOpening` |
| `tests/testsuite/parse_simple_key.rs::simple_key_empty` | `ParseSimpleKeyTest.simpleKeyEmpty` |
| `tests/testsuite/parse_simple_key.rs::simple_key_unquoted_ascii` | `ParseSimpleKeyTest.simpleKeyUnquotedAscii` |
| `tests/testsuite/parse_simple_key.rs::simple_key_string` | `ParseSimpleKeyTest.simpleKeyString` |
| `tests/testsuite/parse_simple_key.rs::simple_key_string_literal` | `ParseSimpleKeyTest.simpleKeyStringLiteral` |

### `toml_writer`

| upstream | Kotlin |
|---|---|
| `tests/float.rs::zero` | `WriterFloatTest.zero` |
| `tests/float.rs::neg_zero` | `WriterFloatTest.negZero` |
| `tests/float.rs::inf` | `WriterFloatTest.inf` |
| `tests/float.rs::neg_inf` | `WriterFloatTest.negInf` |
| `tests/float.rs::nan` | `WriterFloatTest.nan` |
| `tests/float.rs::neg_nan` | `WriterFloatTest.negNan` |
| `tests/integer.rs::positive` | `WriterIntegerTest.positive` |
| `tests/integer.rs::negative` | `WriterIntegerTest.negative` |
| `tests/string.rs::empty` | `WriterStringTest.empty` |
| `tests/string.rs::alpha` | `WriterStringTest.alpha` |
| `tests/string.rs::ident` | `WriterStringTest.ident` |
| `tests/string.rs::one_single_quote` | `WriterStringTest.oneSingleQuote` |
| `tests/string.rs::two_single_quote` | `WriterStringTest.twoSingleQuote` |
| `tests/string.rs::three_single_quote` | `WriterStringTest.threeSingleQuote` |
| `tests/string.rs::one_double_quote` | `WriterStringTest.oneDoubleQuote` |
| `tests/string.rs::two_double_quote` | `WriterStringTest.twoDoubleQuote` |
| `tests/string.rs::three_double_quote` | `WriterStringTest.threeDoubleQuote` |
| `tests/string.rs::mixed_quote_1` | `WriterStringTest.mixedQuote1` |
| `tests/string.rs::mixed_quote_2` | `WriterStringTest.mixedQuote2` |
| `tests/string.rs::escape` | `WriterStringTest.escape` |
| `tests/string.rs::cr` | `WriterStringTest.cr` |
| `tests/string.rs::lf` | `WriterStringTest.lf` |
| `tests/string.rs::crlf` | `WriterStringTest.crlf` |
| `tests/string.rs::tab` | `WriterStringTest.tab` |
| `tests/string.rs::many_quotes` | `WriterStringTest.manyQuotes` |
| `tests/string.rs::parseable` (proptest over `\PC*`) | `WriterStringTest.parseable` (512 seeded random strings) |
| `src/lib.rs` doctest | `WriterDocTest.crateExample` |
| `src/string.rs` doctest on `TomlStringBuilder` | `WriterDocTest.tomlStringBuilderExample` |
| `src/integer.rs` doctest on `TomlIntegerFormat` | `WriterDocTest.tomlIntegerFormatExample` |

`tests/string.rs` also parses every default encoding with an older `toml` release to keep new Cargo readable by
old Cargo. That crate has no Kotlin form; the port parses the same `key = value` text with its own parser and
checks the decoded key and value, which holds the encodings to the same grammar.

Beyond upstream's tests, `FloatFormatTest` compares the shortest digits, `Display`, `Debug` and TOML text of
2,400 floats with Rust's own output, recorded by `src/commonTest/snapshots/oracle/floats.rs`.

### `toml_edit`

| upstream | Kotlin |
|---|---|
| `tests/testsuite/edit.rs::parsed_document_outlives_input` | `EditTest.parsedDocumentOutlivesInput` |
| `tests/testsuite/edit.rs::parse_error_outlives_input` | `EditTest.parseErrorOutlivesInput` |
| `tests/testsuite/edit.rs::document_display_propagates_write_errors` | `EditTest.documentDisplayPropagatesWriteErrors` |
| `tests/testsuite/edit.rs::assign_whitespace` | `EditTest.assignWhitespace` |
| `tests/testsuite/edit.rs::test_add_root_decor` | `EditTest.testAddRootDecor` |
| `tests/testsuite/edit.rs::test_no_root_decor` | `EditTest.testNoRootDecor` |
| `tests/testsuite/edit.rs::test_insert_leaf_table` | `EditTest.testInsertLeafTable` |
| `tests/testsuite/edit.rs::test_inserted_leaf_table_goes_after_last_sibling` | `EditTest.testInsertedLeafTableGoesAfterLastSibling` |
| `tests/testsuite/edit.rs::test_inserting_tables_from_different_parsed_docs` | `EditTest.testInsertingTablesFromDifferentParsedDocs` |
| `tests/testsuite/edit.rs::test_insert_nonleaf_table` | `EditTest.testInsertNonleafTable` |
| `tests/testsuite/edit.rs::test_insert_array` | `EditTest.testInsertArray` |
| `tests/testsuite/edit.rs::test_insert_values` | `EditTest.testInsertValues` |
| `tests/testsuite/edit.rs::test_insert_key_with_quotes` | `EditTest.testInsertKeyWithQuotes` |
| `tests/testsuite/edit.rs::test_remove_leaf_table` | `EditTest.testRemoveLeafTable` |
| `tests/testsuite/edit.rs::test_remove_nonleaf_table` | `EditTest.testRemoveNonleafTable` |
| `tests/testsuite/edit.rs::test_remove_array_entry` | `EditTest.testRemoveArrayEntry` |
| `tests/testsuite/edit.rs::test_remove_array` | `EditTest.testRemoveArray` |
| `tests/testsuite/edit.rs::test_remove_value` | `EditTest.testRemoveValue` |
| `tests/testsuite/edit.rs::test_remove_last_value_from_implicit` | `EditTest.testRemoveLastValueFromImplicit` |
| `tests/testsuite/edit.rs::test_sort_values` | `EditTest.testSortValues` |
| `tests/testsuite/edit.rs::test_sort_values_by` | `EditTest.testSortValuesBy` |
| `tests/testsuite/edit.rs::test_set_position` | `EditTest.testSetPosition` |
| `tests/testsuite/edit.rs::test_set_position_negative` | `EditTest.testSetPositionNegative` |
| `tests/testsuite/edit.rs::test_multiple_zero_positions` | `EditTest.testMultipleZeroPositions` |
| `tests/testsuite/edit.rs::test_multiple_max_usize_positions` | `EditTest.testMultipleMaxUsizePositions` |
| `tests/testsuite/edit.rs::test_insert_replace_into_array` | `EditTest.testInsertReplaceIntoArray` |
| `tests/testsuite/edit.rs::test_remove_from_array` | `EditTest.testRemoveFromArray` |
| `tests/testsuite/edit.rs::test_format_array` | `EditTest.testFormatArray` |
| `tests/testsuite/edit.rs::test_insert_into_inline_table` | `EditTest.testInsertIntoInlineTable` |
| `tests/testsuite/edit.rs::test_remove_from_inline_table` | `EditTest.testRemoveFromInlineTable` |
| `tests/testsuite/edit.rs::test_as_table_like` | `EditTest.testAsTableLike` |
| `tests/testsuite/edit.rs::test_inline_table_append` | `EditTest.testInlineTableAppend` |
| `tests/testsuite/edit.rs::test_insert_dotted_into_std_table` | `EditTest.testInsertDottedIntoStdTable` |
| `tests/testsuite/edit.rs::test_insert_dotted_into_implicit_table` | `EditTest.testInsertDottedIntoImplicitTable` |
| `tests/testsuite/edit.rs::sorting_with_references` | `EditTest.sortingWithReferences` |
| `tests/testsuite/edit.rs::table_str_key_whitespace` | `EditTest.tableStrKeyWhitespace` |
| `tests/testsuite/edit.rs::table_key_decor_whitespace` | `EditTest.tableKeyDecorWhitespace` |
| `tests/testsuite/edit.rs::table_into_inline` | `EditTest.tableIntoInline` |
| `tests/testsuite/edit.rs::inline_table_to_table` | `EditTest.inlineTableToTable` |
| `tests/testsuite/edit.rs::array_of_tables_to_array` | `EditTest.arrayOfTablesToArray` |
| `tests/testsuite/edit.rs::test_key_from_str` | `EditTest.testKeyFromStr` |
| `tests/testsuite/edit.rs::despan_keys` | `EditTest.despanKeys` |
| `tests/testsuite/edit.rs::key_repr_roundtrip` | `EditTest.keyReprRoundtrip` |
| `tests/testsuite/edit.rs::key_value_roundtrip` | `EditTest.keyValueRoundtrip` |
| `tests/testsuite/edit.rs::table_under_inline` | `EditTest.tableUnderInline` |
| `tests/testsuite/edit.rs::array_of_tables_insert_at_beginning` | `EditTest.arrayOfTablesInsertAtBeginning` |
| `tests/testsuite/edit.rs::array_of_tables_insert_in_middle` | `EditTest.arrayOfTablesInsertInMiddle` |
| `tests/testsuite/edit.rs::array_of_tables_insert_at_end` | `EditTest.arrayOfTablesInsertAtEnd` |
| `tests/testsuite/edit.rs::array_of_tables_insert_out_of_bounds` | `EditTest.arrayOfTablesInsertOutOfBounds` |
| `tests/testsuite/edit.rs::inline_table_to_table_with_comment` | `EditTest.inlineTableToTableWithComment` |
| `tests/testsuite/edit.rs::inline_table_to_array_of_tables_with_comment` | `EditTest.inlineTableToArrayOfTablesWithComment` |
| `tests/testsuite/edit.rs::array_of_tables_replace` | `EditTest.arrayOfTablesReplace` |
| `tests/testsuite/edit.rs::array_of_tables_replace_out_of_bounds` | `EditTest.arrayOfTablesReplaceOutOfBounds` |
| `tests/compliance/parse.rs::test_value_from_str` | `ComplianceParseTest.testValueFromStr` |
| `tests/compliance/parse.rs::test_key_unification` | `ComplianceParseTest.testKeyUnification` |
| `tests/compliance/parse.rs::crlf` | `ComplianceParseTest.crlf` |
| `tests/compliance/parse.rs::fun_with_strings` | `ComplianceParseTest.funWithStrings` |
| `tests/compliance/parse.rs::tables_in_arrays` | `ComplianceParseTest.tablesInArrays` |
| `tests/compliance/parse.rs::empty_table` | `ComplianceParseTest.emptyTable` |
| `tests/compliance/parse.rs::mixed_table_issue_527` | `ComplianceParseTest.mixedTableIssue527` |
| `tests/compliance/parse.rs::fruit` | `ComplianceParseTest.fruit` |
| `tests/compliance/parse.rs::blank_literal_string` | `ComplianceParseTest.blankLiteralString` |
| `tests/compliance/parse.rs::many_blank` | `ComplianceParseTest.manyBlank` |
| `tests/compliance/parse.rs::literal_eats_crlf` | `ComplianceParseTest.literalEatsCrlf` |
| `tests/compliance/parse.rs::floats` | `ComplianceParseTest.floats` |
| `tests/compliance/parse.rs::bare_key_names` | `ComplianceParseTest.bareKeyNames` |
| `tests/compliance/parse.rs::table_names` | `ComplianceParseTest.tableNames` |
| `tests/compliance/parse.rs::inline_tables` | `ComplianceParseTest.inlineTables` |
| `tests/compliance/parse.rs::number_underscores` | `ComplianceParseTest.numberUnderscores` |
| `tests/compliance/parse.rs::empty_string` | `ComplianceParseTest.emptyString` |
| `tests/compliance/parse.rs::datetimes` | `ComplianceParseTest.datetimes` |
| `tests/compliance/parse.rs::dont_use_dotted_key_prefix_on_table_fuzz_57049` | `ComplianceParseTest.dontUseDottedKeyPrefixOnTableFuzz57049` |
| `tests/compliance/parse.rs::dotted_key_comment_roundtrip` | `ComplianceParseTest.dottedKeyCommentRoundtrip` |
| `tests/compliance/parse.rs::string_repr_roundtrip` | `ComplianceParseTest.stringReprRoundtrip` |
| `tests/compliance/parse.rs::string_value_roundtrip` | `ComplianceParseTest.stringValueRoundtrip` |
| `tests/compliance/parse.rs::array_recursion_limit` | `ComplianceParseTest.arrayRecursionLimit` |
| `tests/compliance/parse.rs::inline_table_recursion_limit` | `ComplianceParseTest.inlineTableRecursionLimit` |
| `tests/compliance/parse.rs::table_key_recursion_limit` | `ComplianceParseTest.tableKeyRecursionLimit` |
| `tests/compliance/parse.rs::dotted_key_recursion_limit` | `ComplianceParseTest.dottedKeyRecursionLimit` |
| `tests/compliance/parse.rs::inline_dotted_key_recursion_limit` | `ComplianceParseTest.inlineDottedKeyRecursionLimit` |
| `tests/compliance/parse.rs::garbage1` | `ComplianceParseTest.garbage1` |
| `tests/compliance/parse.rs::garbage2` | `ComplianceParseTest.garbage2` |
| `tests/compliance/parse.rs::garbage3` | `ComplianceParseTest.garbage3` |
| `tests/compliance/invalid.rs::basic_string_escape` | `ComplianceInvalidTest.basicStringEscape` |
| `tests/compliance/invalid.rs::literal_escape` | `ComplianceInvalidTest.literalEscape` |
| `tests/compliance/invalid.rs::stray_cr` | `ComplianceInvalidTest.strayCr` |
| `tests/compliance/invalid.rs::duplicate_key_with_crlf` | `ComplianceInvalidTest.duplicateKeyWithCrlf` |
| `tests/compliance/invalid.rs::inline_table_missing_key` | `ComplianceInvalidTest.inlineTableMissingKey` |
| `tests/compliance/invalid.rs::inline_table_missing_key_in_array` | `ComplianceInvalidTest.inlineTableMissingKeyInArray` |
| `tests/compliance/invalid.rs::emoji_error_span` | `ComplianceInvalidTest.emojiErrorSpan` |
| `tests/compliance/invalid.rs::text_error_span` | `ComplianceInvalidTest.textErrorSpan` |
| `tests/compliance/invalid.rs::fuzzed_68144_error_span` | `ComplianceInvalidTest.fuzzed68144ErrorSpan` |
| `examples/visit.rs::visit_correct` | `VisitExampleTest.visitCorrect` |
| `examples/visit.rs::visit_mut_correct` | `VisitExampleTest.visitMutCorrect` |
| `src/value.rs::tests::from_iter_formatting` | `EditUnitTest.valueFromIterFormatting` |
| `src/value.rs::string_roundtrip` | `EditUnitTest.valueStringRoundtrip` |
| `src/key.rs::string_roundtrip` | `EditUnitTest.keyStringRoundtrip` |
| `src/item.rs::string_roundtrip` | `EditUnitTest.itemStringRoundtrip` |
| `src/document.rs::default_roundtrip` | `EditUnitTest.documentDefaultRoundtrip` |
| `src/encode.rs::test::parseable_string` (proptest over `\PC*`) | `EditUnitTest.encodeParseableString` (512 seeded random strings) |
| `src/encode.rs::test::parseable_key` (proptest over `\PC*`) | `EditUnitTest.encodeParseableKey` (512 seeded random strings) |
| `src/error.rs::test_translate_position::empty` | `EditUnitTest.translatePositionEmpty` |
| `src/error.rs::test_translate_position::start` | `EditUnitTest.translatePositionStart` |
| `src/error.rs::test_translate_position::end` | `EditUnitTest.translatePositionEnd` |
| `src/error.rs::test_translate_position::after` | `EditUnitTest.translatePositionAfter` |
| `src/error.rs::test_translate_position::first_line` | `EditUnitTest.translatePositionFirstLine` |
| `src/error.rs::test_translate_position::end_of_line` | `EditUnitTest.translatePositionEndOfLine` |
| `src/error.rs::test_translate_position::start_of_second_line` | `EditUnitTest.translatePositionStartOfSecondLine` |
| `src/error.rs::test_translate_position::second_line` | `EditUnitTest.translatePositionSecondLine` |
| `src/lib.rs` doctests (3) | `EditDocTest.crateExample`, `crateDefaultFormatting`, `crateCustomRepresentation` |
| `README.md` doctest (`#[doc = include_str!("../README.md")]` in `src/lib.rs`) | `EditDocTest.readmeExample` |
| `src/array.rs` doctests (10) | `EditDocTest.array*` |
| `src/array_of_tables.rs` doctest on `replace` | `EditDocTest.arrayOfTablesReplace` |
| `src/item.rs` doctest on `value` | `EditDocTest.itemValue` |
| `src/table.rs` doctests (`set_implicit`, `Entry::key`, `OccupiedEntry::key`, `VacantEntry::key`) | `EditDocTest.tableSetImplicit`, `tableEntryKey`, `tableOccupiedEntryKey`, `tableVacantEntryKey` |
| `src/inline_table.rs` doctest on `InlineEntry::key` | `EditDocTest.inlineEntryKey` |
| `src/value.rs` doctests (`decor_mut`, `decor`, `decorated`) | `EditDocTest.valueDecorMut`, `valueDecor`, `valueDecorated` |
| `src/visit.rs` doctest | `EditDocTest.visitStringCollector` |
| `src/visit_mut.rs` doctest | `EditDocTest.visitMutFloatToString` |

| `tests/decoder_compliance.rs` + `tests/decoder.rs` (toml-test harness at 1.1.0 and `tests/fixtures`, valid) | `ComplianceHarnessTest.decoderComplianceValid` |
| `tests/decoder_compliance.rs` (invalid, each error checked against `tests/snapshots/invalid/**.stderr`) | `ComplianceHarnessTest.decoderComplianceInvalid` (560 snapshots, copied unedited) |
| `tests/encoder_compliance.rs` + `tests/encoder.rs` | `ComplianceHarnessTest.encoderCompliance` |

The harness compares tagged JSON by toml-test's own rules (`TaggedJson.kt`): floats as numbers with `nan` equal
to `nan` and `inf` to `+inf`, offset date-times as instants, local ones by value whatever the fraction's
precision. A fixture that is not UTF-8 is refused with Rust's `Utf8Error` text, as upstream's harness reports it.

The `tests/serde` suite is `serde`-only and not ported.

### The official suite (goal 2)

| check | `files-toml-1.0.0` (parsed as `V1_0`) | `files-toml-1.1.0` |
|---|---|---|
| every valid file decodes to its `.json` | `SuiteTest.decodesValidToml100` | `SuiteTest.decodesValidToml110` |
| every valid file prints back byte for byte | `SuiteTest.roundTripsValidToml100` | `SuiteTest.roundTripsValidToml110` |
| every valid file's JSON, encoded and decoded, gives the same values | `SuiteTest.encodesValidToml100` | `SuiteTest.encodesValidToml110` |
| every invalid file is refused | `SuiteTest.refusesInvalidToml100` | `SuiteTest.refusesInvalidToml110` |

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
| `TableLike` | an open trait whose item functions are `iter`, `get`, `insert`, `remove`, with entries | a sealed interface over `Table` and `InlineTable` with `items()`, `item`, `putItem`, `removeItem` and no entries | an `InlineTable` already uses the plain names for values, and Kotlin cannot overload on the return type |
| `Key::parse` | parses a dotted path | `Key.parsePath`; `Key.parse` is `FromStr`, one key | `parse` reads one of the type, as `Value.parse` and `Datetime.parse` do |
| `Formatted<T>`, `Repr`, `RawString` | public, `Value::String(Formatted<String>)` | internal; a scalar `Value` has its own constructor and `value`, `spelling`, `displaySpelling` and `decor` properties | a caller reads and builds values without unwrapping a formatting container |
| `Value::from(…)`, `FromIterator for Value` | one conversion per source type | the constructors `Value.String(…)` … `Value.InlineTable(…)` | a constructor names the kind; `Value.from(Value)`, a clone, is `copy()` |
| `Value::as_*`, `Item::as_*` scalar and container getters, `is_*` | functions, with `_mut` twins | `OrNull` properties, no `Mut` twins, no predicates | Kotlin's `xOrNull` convention; the `Mut` twin returns the same object, and `type` or `is Value.X` answers the predicate |
| `Decor` | mutable through `decor_mut()`, `set_prefix`, `set_suffix`, `clear` | an immutable data class behind a `var decor` on each holder | a decor read out of one item can be stored on another without the two sharing a change |
| `span()` on `Value`, `Key`, `Table`, `InlineTable`, `Array`, `ArrayOfTables`, `Item`, `Formatted`, `Repr`, `RawString`, `Decor` | public, `None` once a document is editable | not public | an editable document never holds spans; a parse error still carries its `span` |
| `Date`, `Time` fields | `u16`, `u8`, `u32` | `Int` | Kotlin's unsigned types need a `u` suffix and conversions at every use; parsing still checks every range |
| `Datetime` from a date or a time | `From<Date>`, `From<Time>` | `Datetime(date = …)`, `Datetime(time = …)` with the other parts defaulting to `null` | one constructor covers all four kinds |
| `Value::fmt` | `fmt` | `resetFormatting()` | says what it does to the value |
| `Item::into_value`, `into_table`, `into_array_of_tables`, `Table::into_inline_table`, `InlineTable::into_table`, `ArrayOfTables::into_array` | consume the source | `Item`'s are internal; `toInlineTable()`, `toTable()` and `toArray()` convert a copy and leave the source as it is | Kotlin cannot consume a caller's variable, and a moved-out source left empty in the document would vanish from its printout |
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
| Collection vocabulary of `Table`, `InlineTable`, `Array`, `ArrayOfTables` | `len`, `push`, `insert`, `replace`, `remove`, `retain`, `extend`, `sort_by`, `sort_by_key`, `FromIterator`, `fmt`, `clone`, `iter`, `get_mut`, `set_x` / `x` pairs | `size`, `add`, `add(index, …)` or `put`, `operator set`, `removeAt` or `remove`, `retainAll`, `addAll` or `putAll`, `sortWith`, `sortBy`, `tomlArrayOf(…)` and `toTomlArray()` and their siblings, `resetFormatting()`, `copy()`, `Iterable`, `getOrNull` or `get`, one `var` per pair | a Kotlin caller reaches for the names Kotlin's collections use; `get_mut` and `iter_mut` hand out the same object as `get` and `iter` |
| `Entry`, `OccupiedEntry`, `VacantEntry`, `InlineEntry` and friends | an entry API | `getOrPut(key) { default }` | `MutableMap.getOrPut` covers what the entries are used for; an inline table's `getOrPut` returns the `Value` and a table's the stored `Item` |
| `KeyMut` | a view that changes a key's formatting but not its text | none: a `Key`'s text is a `val` and its formatting `var`s | the text cannot change through a `Key`, so a separate view protects nothing |
| `Item` construction | `Item::Value(…)`, `Item::Table(…)`, and the `value()`, `table()`, `array()` helpers | the constructors `Item(…)`, and `operator set` overloads that take a `Value`, `Table`, `ArrayOfTables` or a scalar | a constructor names the kind, and `doc["a"]["b"] = "x"` needs no wrapper |
| Writing a non-value into an `Array` element or a non-table into an `ArrayOfTables` element (`*item = other`, `Item::make_value`) | stores it as a placeholder that `len` counts and printing skips | `TomlEditError.NotAValue` / `NotATableElement`, nothing written; `makeValue()` returns the result | an element of the wrong kind would vanish from the printed document |
| Writing a `Table` or `ArrayOfTables` into an `InlineTable` slot (`*item = other`, `TableLike::entry(…).or_insert`, an index on an empty item) | stores it; a table prints as dotted keys, an array of tables not at all | `TomlEditError.NotAValue`, nothing written; `putItem` converts the item to an inline table or array, as `TableLike::insert` does | an inline table holds values; a dotted `InlineTable` prints the same dotted keys, as `EditTest.tableUnderInline` shows |
| `Array::insert / replace / remove`, `ArrayOfTables::insert / replace / remove`, `IndexMut` on a scalar, `TableLike::insert` of an empty item into an inline table | panic | `TomlEditError` | nothing throws |
| `IndexMut<&str> for InlineTable` on a missing key | panics | `inlineTable[key] = value` adds the key | Kotlin's `[]=` is a write, as `MutableMap.set` is |
| `DocumentMut` | named `DocumentMut`, beside the read-only `Document` | `TomlDocument`, the one document type | there is no second document type to tell it apart from |
| `Document` | a read-only parse result that borrows its input, turned editable with `into_mut` | not public | Kotlin cannot hand out a read-only view of mutable classes, so a second type would only add a step |
| The root of a `DocumentMut` | an `Item`, reached as an item or a table, that can be made to hold a non-table | `root: Table` | a document's root is always a table, and one property says so |
| Reading and writing a document | `&str` in through `FromStr`, text out through `Display` | also `TomlDocument.read(Source)` and `writeTo(Sink)`, failing with typed `TomlReadError` and `TomlIoError` | a Kotlin caller holding a file or stream uses kotlinx-io, and an I/O failure arrives as a value like a parse failure |
| `Display` into a writer that refuses text | stops part-way, propagating `fmt::Error` | `toString()` cannot fail; writing that string to an `FmtWrite` fails before anything is written | Kotlin's `toString` has no error channel |
| Line endings | every line printed with LF | each line keeps its own ending; an inserted line ends like the line before | the suite's round trip (`valid/newline-crlf`); upstream's `document_display_propagates_write_errors` therefore expects CRLF kept |
| A key spelled differently on several lines | every occurrence printed with the first spelling | each line keeps its own while the shared keys are unchanged | the suite's round trip (`valid/key/dotted-01`, `valid/table/with-literal-string`); upstream's `test_key_unification` therefore expects each header as written |
| Interleaved dotted keys | grouped by table (upstream issue 163) | printed in read order until sorted | the suite's round trip (`valid/spec-1.1.0/common-9`) |
| Text without a final newline | a newline is always added (upstream issue 1158) | kept while the document prints as it was read | the suite's round trip (`valid/table/no-eol-01`), without changing upstream's tests that edit such text |
| TOML 1.0 | not offered | `TomlVersion.V1_0` | the suite's 1.0.0 invalid files (`invalid/inline-table/trailing-comma` and eight more) |
