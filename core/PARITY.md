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
| `toml_parser`   | `1.1.3+spec-1.1.0`   | `com.fromwau.kotoml.parser`     |
| `toml_writer`   | `1.1.2+spec-1.1.0`   | `com.fromwau.kotoml.writer`     |

Features ported: the defaults, `parse` and `display`. Not ported: `serde`, `debug`, `unbounded`, `unsafe`.
`toml_edit` re-exports `toml_datetime::*`, so the date-time types live in the root package next to the
document model.

## 2. Decisions a reader needs first

### Bytes, not characters

The lexer, the event parser and the decoders work on the UTF-8 bytes of the input, never on the
`String`'s UTF-16 units. Every `Span`, every error position and every `RawString` span is a byte
offset, exactly as upstream's are, so a span read off this port indexes the same text a Rust caller would
index. Only the slices that become values (decoded strings, keys, raw text kept for formatting) are
decoded back to `String`. The reason is that upstream's error columns, its `Span`s and the text an edit
reproduces are all defined over bytes; converting at the edges keeps every one of them identical
instead of translating each rule. The cost is one `encodeToByteArray()` per parse.

Where upstream counts characters it means Unicode scalar values (`str::chars`), so the port counts code
points there, never UTF-16 units: the error column, the key ordering of `sort_values` (Rust compares
`str` byte-wise, which orders by code point) and the escaping of control characters.

### Naming

- Types and functions keep upstream's names in Kotlin casing, accessors included: `span.start()`,
  `token.kind()`, `decor_mut` becomes `decorMut()`. A Rust `new` constructor is the Kotlin constructor.
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
  parent at the first write, so `doc["a"]["b"] = value(...)` works as in Rust, and reading never adds a key.
- `Value` is a sealed type whose parts (`Formatted`, `TomlArray`, `InlineTable`, `Decor`) are mutable in place.
  A whole value is replaced through its container (`TomlArray.replace`, `InlineTable.insert`, `Item.assign`).
  `VisitMut.visitValueMut` returns the value to keep in place, which is how a visitor replaces one.
- `Decor.assign(other)` is `*decor = other`.
- Storing an item stores a copy of its content (`assign`, `table[key] = item`, `insert`, `extend`, an entry's
  `insert`), so two places never share a slot, a table or a value. Rust moves the value into its place; Kotlin
  cannot empty the caller's variable, and moving out of an array element would leave a hole in the array.
- A `Value` or `Table` handed to a container directly (`TomlArray.push`, `InlineTable.insert`,
  `ArrayOfTables.push`) is stored as is, as Rust moves it: keep using it only through the document, or hand over
  its `clone()`.
- An array element is always a value and an array-of-tables element always a table. Writing anything else
  into one fails, so upstream's placeholder elements cannot occur and `len()` counts what iteration yields.
- `into_*` conversions move the content into the result and leave the source empty, as Rust's move does.

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
indexing into a scalar, assigning a missing inline-table key), the Kotlin function returns a
`TomlEditError` instead. A Kotlin `operator set` may return a value, so `item["k"] = v` stays index syntax
while `item.set("k", v)` hands the error back; in the `[]=` form it is discarded.

## 3. Public items

Status: `done`, `serde-only` (behind the `serde` feature, out of scope), or `n/a` with the reason.

### `toml_datetime`

| upstream | Kotlin | status | tests |
|---|---|---|---|
| `Datetime` (`date`, `time`, `offset` fields) | `Datetime` data class | done | `DatetimeDisplayTest`, `DatetimeParseTest` |
| `Date` (`year`, `month`, `day`) | `Date` (`UShort`, `UByte`, `UByte`) | done | `DatetimeDisplayTest.datetimeWithoutSeconds`, `DatetimeParseTest.datetimeWithoutSeconds` |
| `Time` (`hour`, `minute`, `second`, `nanosecond`) | `Time` (`UByte`, `UByte`, `UByte?`, `UInt?`) | done | `DatetimeDisplayTest`, `DatetimeParseTest.timeWithoutSeconds` |
| `Offset::Z`, `Offset::Custom { minutes }` | `Offset.Z`, `Offset.Custom(minutes: Short)` | done | `DatetimeDisplayTest.datetimeWithoutSeconds` |
| `DatetimeParseError` | `DatetimeParseError(part, expected)`, typed: `DatetimePart`, `DatetimeExpected`, `DatetimeField` | done | `DatetimeParseTest` |
| `impl Display for Datetime / Date / Time / Offset / DatetimeParseError` | `toString()`; the error's text is rendered as part of `TomlParseError.render` | done | `DatetimeDisplayTest`, `DatetimeParseTest` |
| `impl FromStr for Datetime` | `Datetime.parse(String): Result<Datetime, DatetimeParseError>` | done | `DatetimeParseTest` |
| `impl From<Date> for Datetime`, `impl From<Time> for Datetime` | `Datetime.from(Date)`, `Datetime.from(Time)` | done | |
| `impl Ord / PartialOrd / Eq / Hash` (derived) | `Comparable`, data-class `equals` / `hashCode` | done | |
| `impl Error for DatetimeParseError` | `IError` | done | |
| `de::*` (`DatetimeDeserializer`, `VisitMap`, `is_datetime`) | | serde-only | |
| `ser::*` (`DatetimeSerializer`, `SerializerError`, `is_datetime`) | | serde-only | |
| `impl Serialize / Deserialize for Datetime / Date / Time` | | serde-only | |

### `toml_parser`

| upstream | Kotlin | status | tests |
|---|---|---|---|
| `Source::new`, `lex`, `input` | `Source(String)`, `lex()`, `input()` | done | `LexerTest`, `ParseDocumentTest` |
| `Source::get(impl SourceIndex)` | `Source.get(Span)`, `get(Token)`, `get(Event)` | done | `ParseDocumentTest` (every event is sliced) |
| `Source::get_unchecked` | | n/a | behind the `unsafe` feature |
| `SourceIndex` (sealed trait over `Span`, `Token`, `Event`) | the three `Source.get` overloads | done | |
| `Raw::new_unchecked` | `Raw.newUnchecked(String, Encoding?, Span)` | done | `DecoderStringTest`, `DecoderScalarTest` |
| `Raw::decode_key / decode_scalar / decode_whitespace / decode_comment / decode_newline` | same names | done | `ParseDocumentTest`, `ParseValueTest`, `ParseSimpleKeyTest` |
| `Raw::as_str / as_bytes / len / is_empty` | `asStr()`, `asBytes()` (a copy), `len()` (bytes), `isEmpty()` | done | |
| `Span::new_unchecked`, `is_empty`, `len`, `start`, `end`, `before`, `after`, `append` | `Span(start, end)` and same names | done | every snapshot prints spans |
| `impl Add<usize> / AddAssign<usize> for Span`, `impl Add<Span> for usize` | `Span.plus(Int)` | done | |
| `impl Default for Span` | `Span.DEFAULT` | done | |
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
| `lexer::TokenKind` (16 kinds), `description`, `encoding` | `TokenKind`, same names | done | `LexerTest` |
| `parser::parse_document / parse_key / parse_simple_key / parse_value` | `parseDocument`, `parseKey`, `parseSimpleKey`, `parseValue` | done | `ParseDocumentTest`, `ParseValueTest`, `ParseSimpleKeyTest` |
| `parser::EventReceiver` (17 callbacks with defaults) | `interface EventReceiver` | done | all parser tests |
| `impl EventReceiver for FnMut(Event)` / `Vec<Event>` | `eventReceiver { ... }`, e.g. `eventReceiver(events::add)` | done | all parser tests |
| `impl EventReceiver for ()` | `EventReceiver.IGNORE` | done | |
| `parser::ValidateWhitespace::new` | `ValidateWhitespace(EventReceiver, Source)` | done | toml_edit's parser |
| `parser::RecursionGuard::new(_, u32)` | `RecursionGuard(EventReceiver, UInt)` | done | `ParseDocumentTest.*RecursionLimit` |
| `parser::Event::new_unchecked / kind / encoding / span` | `Event(...)`, `Event.newUnchecked`, same names | done | all parser tests |
| `parser::EventKind` (17 kinds), `description` | `EventKind`, same names | done | |
| `decoder::Encoding`, `description` | `Encoding`, same name | done | |
| `decoder::StringBuilder` | `DecodedStringBuilder` | done | `DecoderStringTest` |
| `impl StringBuilder for String` / `Cow<str>` | `StringOutput` | done | `DecoderStringTest`, `ParseValueTest` |
| `impl StringBuilder for &str` | `BorrowedOutput` | done | |
| `impl StringBuilder for ()` | `DecodedStringBuilder.DISCARD` | done | `ParseDocumentTest` (keys) |
| `decoder::ScalarKind` (`String`, `Boolean`, `DateTime`, `Float`, `Integer`), `description`, `invalid_description` | `ScalarKind` sealed interface, `description`; `invalid_description` is `ParseErrorReason.InvalidScalar(kind)` | done | `ParseValueTest` |
| `decoder::IntegerRadix`, `description`, `value`, `invalid_description`, `Default` | `IntegerRadix`, `description`, `value`, `IntegerRadix.DEFAULT`; `invalid_description` as for `ScalarKind` | done | `ParseValueTest` |
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
| `DocumentMut::new`, `Default` | `DocumentMut()` | done | `EditUnitTest.documentDefaultRoundtrip` |
| `DocumentMut::as_item`, `as_item_mut`, `into_item`, `as_table`, `as_table_mut`, `into_table`, `iter`, `set_trailing`, `trailing` | same names | done | `EditTest` |
| `impl FromStr for DocumentMut` | `DocumentMut.parse(String): Result<DocumentMut, TomlParseError>` | done | `EditTest`, `ComplianceParseTest` |
| `impl Display for DocumentMut` | `toString()`, keeping line endings, spellings and dotted-key order (see section 2) | done | `EditTest`, `PreservationTest`, `SuiteTest.roundTrips*` |
| `impl Index<&str> / IndexMut<&str> for DocumentMut` | `operator get`, `operator set` | done | `EditTest` |
| `impl Deref / DerefMut<Target = Table> for DocumentMut` | `asTable()`, plus forwards for `getOrNull`, `containsKey`, `insert`, `remove`, `entry`, `len`, `isEmpty`, `decor`, `decorMut` | done | `EditTest.testAddRootDecor` |
| `impl From<Table> for DocumentMut` | `DocumentMut(Table)` | done | |
| `Document::new`, `Default` | `Document()` | done | |
| `Document::parse`, `as_item`, `into_item`, `as_table`, `into_table`, `iter`, `trailing`, `raw`, `into_mut` | same names | done | every `DocumentMut.parse` goes through them |
| `impl FromStr for Document<String>` | `Document.parse` | done | |
| `impl Deref<Target = Table> for Document` | `asTable()`, `operator get` | done | |
| `ImDocument` (deprecated alias of `Document`) | | n/a | deprecated upstream since 0.23 |
| `Item::None / Value / Table / ArrayOfTables`, `Default` | `Item.none()`, `Item.from(Value / Table / ArrayOfTables)` | done | `EditTest` |
| `Item::or_insert` | `orInsert(Item): Result<Item, TomlEditError>` | done | |
| `Item::type_name` (as `type: TomlType`), `as_*`, `as_*_mut`, `is_*`, `as_integer` … `is_inline_table`, `as_table_like(_mut)`, `is_table_like`, `span` | same names | done | `EditTest`, `ComplianceParseTest` |
| `Item::get` / `get_mut` (`Option<&Item>`) | `getOrNull(String / Int)`, `getMut(String / Int)` | done | `EditTest` |
| `impl Index / IndexMut<usize / &str / String> for Item` | `operator get(String / Int)`, `operator set(String / Int, Item)` | done | `EditTest`, `ComplianceParseTest.fruit` |
| `Item::into_value / into_table / into_array_of_tables` (`Result<_, Item>`) | nullable results | done | `EditTest.inlineTableToTable` |
| `Item::make_value` | `makeValue()` | done | `EditTest.tableIntoInline` |
| `impl FromStr for Item`, `impl Display`, `impl Clone`, `From<…>` | `Item.parse`, `toString()`, `clone()`, `Item.from(…)` | done | `EditUnitTest.itemStringRoundtrip` |
| `value()`, `table()`, `array()` | `value(…)` overloads, `table()`, `array()` | done | `EditDocTest.itemValue` |
| `Value::String / Integer / Float / Boolean / Datetime / Array / InlineTable` | `Value.String(formatted)` … `Value.Array(array)`, `Value.InlineTable(table)` | done | `EditTest` |
| `Value::type_name` (as `type: TomlType`), `as_str` … `is_inline_table`, `as_array_mut`, `as_inline_table_mut`, `decor_mut`, `decor`, `decorated`, `span` | same names | done | `EditDocTest` |
| `impl FromStr for Value`, `impl Display`, `From<&str, String, i64, f64, bool, Datetime, Date, Time, Array, InlineTable, &Value>` | `Value.parse`, `toString()`, `Value.from(…)` | done | `ComplianceParseTest` |
| `impl FromIterator<V> / FromIterator<(K, V)> for Value` | `Value.fromIter(Iterable<Value>)`, `Value.fromPairs(Iterable<Pair<String, Value>>)` | done | `EditUnitTest.valueFromIterFormatting` |
| `Array` (`new`, `fmt`, `set_trailing_comma`, `trailing_comma`, `set_trailing`, `trailing`, `decor_mut`, `decor`, `span`, `iter`, `iter_mut`, `len`, `is_empty`, `clear`, `get`, `get_mut`, `push`, `push_formatted`, `retain`, `sort_by`, `sort_by_key`) | `TomlArray`, same names | done | `EditTest`, `EditDocTest` |
| `Array::insert`, `insert_formatted`, `replace`, `replace_formatted`, `remove` | same names, returning `TomlEditError` where upstream panics | done | `EditTest.testInsertReplaceIntoArray`, `EditDocTest` |
| `impl Display / Extend / FromIterator / IntoIterator for Array`, `ArrayIter`, `ArrayIterMut`, `ArrayIntoIter` | `toString()`, `extend`, `TomlArray.fromIter`, `Iterable<Value>`; iterator aliases n/a | done | |
| `ArrayOfTables` (`new`, `into_array`, `span`, `iter`, `iter_mut`, `len`, `is_empty`, `clear`, `get`, `get_mut`, `push`, `retain`) | same names | done | `EditTest` |
| `ArrayOfTables::insert`, `replace`, `remove` | same names, returning `TomlEditError` where upstream panics | done | `EditTest.arrayOfTables*` |
| `impl Display / Extend / FromIterator / IntoIterator for ArrayOfTables`, iterator aliases | `toString()`, `extend`, `ArrayOfTables.fromIter`, `Iterable<Table>`; aliases n/a | done | |
| `Table` (`new`, `into_inline_table`, `get_values`, `fmt`, `sort_values`, `sort_values_by`, `set_implicit`, `is_implicit`, `set_dotted`, `is_dotted`, `set_position`, `position`, `decor_mut`, `decor`, `key`, `key_mut`, `span`, `iter`, `iter_mut`, `len`, `is_empty`, `clear`, `entry`, `entry_format`, `get_mut`, `get_key_value`, `get_key_value_mut`, `contains_key`, `contains_table`, `contains_value`, `contains_array_of_tables`, `insert`, `insert_formatted`, `remove`, `remove_entry`, `retain`) | same names; `isize` positions are `Long` | done | `EditTest` |
| `Table::get` | `getOrNull` | done | `EditTest` |
| `impl Index / IndexMut<&str> for Table` | `operator get` (a slot), `operator set` | done | `EditTest` |
| `impl Display / Extend / FromIterator / IntoIterator for Table`, `Iter`, `IterMut`, `IntoIter` | `toString()`, `extend`, `Table.fromIter`, `Iterable<Pair<String, Item>>`; aliases n/a | done | `EditDocTest.itemValue` |
| `TableLike` (`iter`, `iter_mut`, `len`, `is_empty`, `clear`, `entry`, `entry_format`, `get`, `get_mut`, `get_key_value`, `get_key_value_mut`, `contains_key`, `insert`, `remove`, `get_values`, `fmt`, `sort_values`, `set_dotted`, `is_dotted`, `key`, `key_mut`) | `TableLike`; renamed `iterItems`, `iterItemsMut`, `itemEntry`, `itemEntryFormat`, `getItem`, `insertItem`, `removeItem` | done | `EditTest.testAsTableLike` |
| `Entry`, `OccupiedEntry` (`key`, `key_mut`, `get`, `get_mut`, `into_mut`, `insert`, `remove`), `VacantEntry` (`key`, `insert`) | same names; the variants implement `Entry` directly | done | `EditTest.testSetPositionNegative`, `EditDocTest` |
| `InlineTable` (`new`, `into_table`, `get_values`, `fmt`, `set_trailing_comma`, `trailing_comma`, `set_trailing`, `trailing`, `sort_values`, `sort_values_by`, `set_dotted`, `is_dotted`, `decor_mut`, `decor`, `key`, `key_mut`, `span`, `iter`, `iter_mut`, `len`, `is_empty`, `clear`, `entry`, `entry_format`, `get`, `get_mut`, `get_key_value`, `get_key_value_mut`, `contains_key`, `get_or_insert`, `insert`, `insert_formatted`, `remove`, `remove_entry`, `retain`) | same names | done | `EditTest` |
| `impl Index<&str> for InlineTable` | `operator get`, returning null where upstream panics | done | |
| `impl IndexMut<&str> for InlineTable` | `operator set`, returning `TomlEditError` where upstream panics | done | |
| `impl Display / Extend / FromIterator / IntoIterator for InlineTable`, iterator aliases | `toString()`, `extend`, `InlineTable.fromIter`, `Iterable<Pair<String, Value>>`; aliases n/a | done | `EditTest.testInlineTableAppend` |
| `InlineEntry`, `InlineOccupiedEntry`, `InlineVacantEntry` | same names | done | |
| `Key` (`new`, `parse`, `with_leaf_decor`, `with_dotted_decor`, `as_mut`, `get`, `as_repr`, `default_repr`, `display_repr`, `leaf_decor_mut`, `dotted_decor_mut`, `leaf_decor`, `dotted_decor`, `span`, `fmt`) | `Key(String)`, same names | done | `EditTest.testKeyFromStr`, `EditTest.keyReprRoundtrip` |
| `impl FromStr for Key` | `Key.parseSimple` | done | `EditTest.keyValueRoundtrip` |
| `impl Display / Clone / Hash / Ord / Eq for Key`, `From<&str / String>` | `toString()`, `clone()`, `hashCode` / `compareTo` / `equals` by text, the constructor | done | |
| `impl Deref<Target = str> / Borrow<str> for Key`, `PartialEq<str / &str / String>`, `From<Key> for String` | `get()` | n/a | Kotlin has no deref; compare `key.get()` |
| `KeyMut` (`get`, `as_repr`, `default_repr`, `display_repr`, `leaf_decor_mut`, `dotted_decor_mut`, `leaf_decor`, `dotted_decor`, `fmt`), `Display` | same names | done | `VisitExampleTest` |
| `RawString` (`as_str`, `span`, `Default`, `From<&str / String / &String / Box<str>>`) | same names, `RawString()`, `RawString.from(String)` | done | `EditTest.assignWhitespace` |
| `Repr` (`as_raw`, `span`) | same names | done | `EditTest.assignWhitespace` |
| `Decor` (`new`, `clear`, `prefix`, `set_prefix`, `suffix`, `set_suffix`, `Default`) | `Decor(prefix, suffix)`, same names, `Decor()` | done | `EditTest.testAddRootDecor` |
| `Formatted` (`new`, `value`, `into_value`, `as_repr`, `default_repr`, `display_repr`, `span`, `decor_mut`, `decor`, `fmt`), `Display`, `Eq` | `Formatted.of(…)`, same names | done | `EditTest.despanKeys`, `EditDocTest` |
| `ValueRepr` (sealed trait) | | n/a | `Formatted.of` is overloaded for exactly the five types it covers |
| `TomlError` (`message`, `span`), `Display`, `Error`, `Eq`, `Hash` | `data class TomlParseError(reason, span, position, context, expected)`, an `IError`; `Display` is `render(source)` | done | `ComplianceInvalidTest`, `ComplianceParseTest.garbage*` |
| `visit::Visit` and `visit_document` … `visit_value` | `com.fromwau.kotoml.visit` | done | `VisitExampleTest`, `EditDocTest.visitStringCollector` |
| `visit_mut::VisitMut` and `visit_document_mut` … `visit_value_mut` | `com.fromwau.kotoml.visitmut` | done | `VisitExampleTest`, `EditDocTest.visitMutFloatToString` |
| `de::*`, `ser::*`, `TomlError::custom`, `Serialize / Deserialize` impls | | serde-only | |
| `toml_datetime::*` re-export | the date-time types in the same package | done | |
| (none: an addition) | `TomlVersion`, and a `version` parameter on `DocumentMut.parse`, `Document.parse`, `Value.parse`, `Item.parse`, `Key.parse`, `Key.parseSimple` | added | `TomlVersionTest`, `SuiteTest.*Toml100` |

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
| `Source` over a `String` with an unpaired surrogate | not representable: `&str` is always valid UTF-8 | lexed as if U+FFFD stood there, which is what `encodeToByteArray` writes | Kotlin strings can hold what Rust strings cannot |
| `ValidateWhitespace` given a span that does not slice its source | panics (`expect("token spans are valid")`) | skips validating that span | spans come from the lexer, so only a misused receiver reaches this |
| `Lexer::next` after the end | returns `None` | returns another `Eof` token | Kotlin's `Iterator.next` past `hasNext() == false` would have to throw |
| `parseDocument` with no `RecursionGuard` | recurses once per nesting level | iterates with a heap stack | a stack overflow on Kotlin/Native is a crash, not a value |
| `WriteTomlValue.of(Map)` | `BTreeMap` writes sorted, `HashMap` in hash order | the map's own iteration order | Kotlin maps carry their order; pass a sorted map for `BTreeMap`'s |
| `WriteTomlValue.of(Char)` | a `char` is a Unicode scalar value | a `Char` is a UTF-16 unit, so a lone surrogate is written as is | Kotlin has no scalar-value type |
| `Array` | named `Array` | `TomlArray` | a star import of `Array` would shadow `kotlin.Array` |
| `Table::get`, `Item::get` | `get` | `getOrNull` | `get` is the index operator |
| `TableLike` item functions | `iter`, `iter_mut`, `entry`, `entry_format`, `get`, `get_mut`, `insert`, `remove` | `iterItems`, `iterItemsMut`, `itemEntry`, `itemEntryFormat`, `getItem`, `insertItem`, `removeItem` | an `InlineTable` already uses those names for values, and Kotlin cannot overload on the return type |
| `impl FromStr for Key` | `"a".parse::<Key>()` | `Key.parseSimple` | `Key.parse` is upstream's dotted-path parser |
| `Formatted::new` | generic over the sealed `ValueRepr` | `Formatted.of` overloads | a Kotlin type parameter cannot be limited to five types |
| `Item::into_value` and friends | `Result<_, Item>` returning the item | nullable | the Kotlin item still exists after a failed conversion |
| `*place = value` through `&mut` | assignment | `Item.assign`, `Decor.assign`, container `replace` / `insert`, `VisitMut.visitValueMut` returning the value | see "Items are slots, values are not" |
| Errors | text: `ParseError::description`, `TomlError::message`, `DatetimeParseError`'s `what` / `expected` | typed reasons (`ParseErrorReason`, `TomlParseError.Reason`, `DatetimeExpected`); upstream's text comes from `TomlParseError.render(source)` | a caller branches on what went wrong without matching text, and the wording is decided where it is shown |
| `TomlError` | holds a copy of the input to render from | `render` takes the source; the error keeps a byte `span` and a `TextPosition` | the error stays small, and the caller already holds the text |
| Duplicate key and dotted key over a value | no key named | `DuplicateKey(key)`, `DottedKeyExtendsValue(key, type)` | the reason says which key |
| `Index` on a missing key | panics (`index not found`) | returns an empty item, or null for `InlineTable` | reading through a missing key never fails, and the empty item joins the table when written to |
| `IndexMut` before a write | inserts an empty slot (or turns an empty item into an inline table) at access time | joins the parent at the first write | reading in Kotlin cannot be told from writing; the document only changes when written |
| Storing an `Item` | moves it | stores a copy | see "Items are slots, values are not" |
| Writing a non-value into an `Array` element or a non-table into an `ArrayOfTables` element (`*item = other`, `Item::make_value`) | stores it as a placeholder that `len` counts and printing skips | `TomlEditError.NotAValue` / `NotATableElement`, nothing written; `makeValue()` returns the result | an element of the wrong kind would vanish from the printed document |
| `Array::insert / replace / remove`, `ArrayOfTables::insert / replace / remove`, `IndexMut` on a scalar or a missing inline-table key, `TableLike::insert` of a non-value into an inline table | panic | `TomlEditError` | nothing throws |
| `DocumentMut::as_table` when the root item was made a non-table | panics | resets the root to an empty table | nothing throws |
| `Document` | immutable by borrow rules | editable through its items | Kotlin cannot hand out read-only views of mutable classes; documented as read-only |
| `Display` into a writer that refuses text | stops part-way, propagating `fmt::Error` | `toString()` cannot fail; writing that string to an `FmtWrite` fails before anything is written | Kotlin's `toString` has no error channel |
| Line endings | every line printed with LF | each line keeps its own ending; an inserted line ends like the line before | the suite's round trip (`valid/newline-crlf`); upstream's `document_display_propagates_write_errors` therefore expects CRLF kept |
| A key spelled differently on several lines | every occurrence printed with the first spelling | each line keeps its own while the shared keys are unchanged | the suite's round trip (`valid/key/dotted-01`, `valid/table/with-literal-string`); upstream's `test_key_unification` therefore expects each header as written |
| Interleaved dotted keys | grouped by table (upstream issue 163) | printed in read order until sorted | the suite's round trip (`valid/spec-1.1.0/common-9`) |
| Text without a final newline | a newline is always added (upstream issue 1158) | kept while the document prints as it was read | the suite's round trip (`valid/table/no-eol-01`), without changing upstream's tests that edit such text |
| TOML 1.0 | not offered | `TomlVersion.V1_0` | the suite's 1.0.0 invalid files (`invalid/inline-table/trailing-comma` and eight more) |
