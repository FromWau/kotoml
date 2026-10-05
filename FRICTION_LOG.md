# Friction log: porting toml_edit to Kotlin Multiplatform (`:toml`)

Append-only. Entries are never rewritten, even when a later entry proves one wrong; a
correction is its own entry that references the earlier one. Written while the context
is fresh, not at the end of a session.

Target: `toml_edit` 0.25.15 and its crates `toml_parser`, `toml_writer`, `toml_datetime`, at
toml-rs/toml `342362803e41051e522127ca933a19d12a2711ac`, ported into `toml/` as common code.

Scope decided: default features (`parse`, `display`) only; the lexer works on UTF-8 bytes so
every span is a byte offset, as upstream's are.

Each entry: **Wanted / Got / Severity / Doing / For <upstream>.**

Severity scale: `paper cut` (seconds lost) / `detour` (minutes, wrong path walked) /
`blocker` (cannot proceed without a decision or an upstream change).

## Phase 0: reading upstream

### 1. 2026-10-02 20:51 - Upstream itself does not pass goal 2 of the brief on 21 suite files

**Wanted:** confirm upstream is a correct oracle for all four suite checks on both spec lists,
before porting toward it.

**Got:** built upstream `toml_edit` at the pinned commit as a small binary and ran it over every
`.toml` in `files-toml-1.0.0` and `files-toml-1.1.0`:

```
files-toml-1.0.0: invalid accepted=9 rejected=492 valid-failed=0 roundtrip-diff=12
files-toml-1.1.0: invalid accepted=0 rejected=494 valid-failed=0 roundtrip-diff=12
```

The 9 accepted are 1.0-only rules that 1.1 relaxed (`inline-table/linebreak-0[1-4]`,
`inline-table/trailing-comma`, `string/basic-byte-escapes`, `{datetime,local-datetime,local-time}/no-secs`).
The 12 round-trip differences: CRLF printed as LF (`empty-crlf`, `newline-crlf`,
`multiline-escaped-crlf`), a final newline added (`table/no-eol-0[12]`), and table-header and
dotted keys re-quoted (`"name".'last'` printed as `name.'last'`, `["".a]` as `[''.a]`).

**Severity:** blocker for goal 2 as written: parity (goal 1) and "every valid file survives
byte for byte" / "every 1.0.0 invalid file is refused" (goal 2) cannot both hold.

**Doing:** port for parity first (milestones 1-4 do not depend on this), raise it with the owner,
decide at milestone 5. MEASURED, not inferred.

**For the brief:** goal 2 assumes upstream passes all four checks; its own harness only runs the
1.1.0 decoder and encoder checks, never a byte-for-byte round trip.

### 2. 2026-10-02 20:51 - Upstream's bundled toml-test data is byte-identical to the submodule

**Wanted:** know whether upstream's 560 `tests/snapshots/invalid/**.stderr` error snapshots apply
to our suite.

**Got:** `diff -rq toml-test-data-2.14.1/assets/toml-test/tests toml-test/tests` is empty.

**Severity:** none; a green result that closes a question.

**Doing:** port `decoder_compliance` with those snapshots as exact expectations for the error text.

## Phase 2: toml_parser

### 3. 2026-10-02 21:14 - snapbox's `.raw()` still folds CRLF and lone CR into LF

**Wanted:** compare ported snapshots exactly, since upstream marks them `.raw()`.

**Got:** `LexerTest.testLexComment` failed: expected remaining stream `\ntrailing`, actual `\r\ntrailing`.
Upstream passes the same case. snapbox 1.2.2 `Data::raw()` is `FilterSet::empty().newlines()`: it keeps the
newline filter, and `normalize_line_endings` maps both `\r\n` and `\r` to `\n` on actual and expected.

**Severity:** detour, about five minutes.

**Doing:** the Kotlin `assertData` / `assertFileData` helpers apply the same normalization to both sides, and
nothing else. Only plain-text comparisons can differ; Debug output escapes `\r`.

**For snapbox:** the name `raw` reads as "no filters at all"; its doc says "Remove default filters", which
is true but does not say newline folding survives.

### 4. 2026-10-02 21:18 - The parser layer matches upstream on all 768 parseable suite files

**Wanted:** a stronger check than upstream's 182 parser tests before building the document model on top.

**Got:** a small Rust binary over upstream `toml_parser` and a throwaway Kotlin test both dumped, for every
`.toml` under `toml-test/tests` that is valid UTF-8, the testsuite's `EventResults` Debug text (every event,
every lexer, parser and decoder error). After fixing the test-side imitation of Rust's `Debug` escaping, which
had passed U+3000, unassigned code points and noncharacters through unescaped, `diff -rq` reports 0
differences on the JVM. MEASURED.

Early read the brief asked for, the parser alone (no duplicate-key or table semantics): refuses 429 of 501
invalid files in `files-toml-1.0.0` and 431 of 494 in `files-toml-1.1.0`; files that are not UTF-8 count as
refused, as upstream's harness refuses them before parsing.

**Severity:** none; a green result.

**Doing:** the differential dump is not committed (it needs the Rust toolchain); upstream's own tests and the
suite tests are what the gate runs.

## Phase 4: toml_edit

### 5. 2026-10-02 21:44 - Hand-copied snapshots lose trailing spaces upstream relies on

**Wanted:** copy `compliance/invalid.rs` expectations as Kotlin raw strings.

**Got:** `ComplianceInvalidTest.strayCr` failed on `1 |` versus `1 | `: the rendered error line ends with a
space after the gutter when the source line is empty or ends in a CR, and the space did not survive writing the
raw string by hand.

**Severity:** paper cut, but silent: a snapshot that drops whitespace still reads right.

**Doing:** every expectation with significant trailing or control whitespace is now written as an escaped string
generated from the Rust literal by a script, not retyped. Checked the other hand-written test files for the
same loss: `grep -n ' $'` over the upstream sources, line by line.

### 6. 2026-10-02 21:51 - commonTest code may use JVM-only stdlib and still pass jvmTest

**Wanted:** a green `:toml:jvmTest` to mean the common test code is common.

**Got:** `VisitExampleTest` used `sortedSetOf`, a JVM-only stdlib function. `jvmTest` compiled and passed;
every native test compile then failed with `Unresolved reference 'sortedSetOf'`.

**Severity:** paper cut; the gate caught it, a JVM-only loop would not have.

**Doing:** `mutableSetOf` / `setOf` (the test compares sets, so order never mattered). Native test compiles stay
in the gate on every commit, never skipped for speed.

## Phase 5: the suite

### 7. 2026-10-02 21:57 - The port fails goal 2 on exactly upstream's 21 files, and nothing else

**Wanted:** all four suite checks at 100% on both lists.

**Got:** decoding and encoding pass on every valid file of both lists; every invalid file of
`files-toml-1.1.0` is refused. Failing, and identical file for file to what upstream's own binary does
(entry 1): round trip, 12 files per list; refusing, the 9 `files-toml-1.0.0` invalid files that TOML 1.1 made
valid. MEASURED on the JVM.

**Severity:** blocker for goal 2 as written; it needs the owner's call, since meeting it means either leaving
upstream's behaviour (goal 1) or breaking an upstream test (goal 3: `document_display_propagates_write_errors`
asserts CRLF input prints without `\r`).

**Doing:** finishing the unblocked work first, then asking with the options laid out.

### 8. 2026-10-02 22:12 - RESOLVES entries 1 and 7: the owner chose the whole suite; 2 upstream tests change, not 16

**Wanted:** pass every file of both lists, as the owner asked ("i want 501/501", "fix").

**Got:** a parse option `TomlVersion.V1_0` refuses the nine 1.1-only files. Per-line endings, per-occurrence key
spelling and read-order dotted keys make all twelve round-trip files print back. A first attempt that kept a
missing final newline unconditionally broke 14 upstream `edit.rs` tests, all of which edit text that lacks one and
expect a newline added. Keeping it only while the document prints exactly as read (render, then compare with the
source plus one newline) satisfies both, and leaves those 14 untouched. Two upstream expectations do contradict
the suite and change: `test_key_unification` and the CRLF assertion of `document_display_propagates_write_errors`.
MEASURED: 8 of 8 suite checks, 388 tests, JVM and linuxX64.

**Severity:** detour, about an hour.

**Doing:** each change is a row under "Intended differences" in PARITY.md, and pinned by `PreservationTest` or
`TomlVersionTest` as well as by the suite.

**For upstream:** issues 163 (dotted key order) and 1158 (final newline) are what the suite's round trip trips on;
the approach here (order recorded per line, a per-line spelling used while the shared key is unchanged) is one way
to close them without changing the model's shape.

### 9. 2026-10-02 22:20 - The parity sweep found an untested README claim and two KDoc examples that did not compile as written

**Wanted:** every claim in the README and every KDoc example to be backed by code that runs.

**Got:** `Table.setImplicit`'s example called `DocumentMut.parse(...)` and used the `Result` as a document. The new
README said writing a key into a string returns `TomlEditError`, and no test checked that. Upstream's README is
itself a doctest (`#[doc = include_str!("../README.md")]` in `src/lib.rs`), and PARITY had no row for it.

**Severity:** paper cut. KDoc examples are not compiled, so nothing caught them.

**Doing:** fixed the example; `EditDocTest.readmeExample` ports upstream's README, and `moduleReadme*` tests pin
this module's README examples and the `set` claim. Gate green, 392 tests on JVM and linuxX64.

### 10. 2026-10-02 22:21 - Corrects entry 9's title: one KDoc example was wrong, not two

**Got:** only `Table.setImplicit`'s example was broken; the `Visit` example already unwraps with `.getOrNull()!!`.

**Severity:** paper cut, my own miscount. No upstream feedback.

## Phase 6: kotoml

### 11. 2026-10-03 00:37 - The log moves with the code: `:toml` is now its own project, kotoml

**Got:** the port left kontext for `~/Projects/kotoml` (packages `com.fromwau.kotoml`, modules `:core` and `:app`, CLI
`kotoml`). Entries 1 to 10 were written in kontext and name its paths (`toml/`, `:toml`); they stay as written.

**Doing:** `./gradlew check` now runs the official toml-test runner against the native binary, so the suite is judged
by toml-test itself, not only by the Kotlin port of its rules.

## Next steps

1. **Date-times as kotlinx-datetime types.** Drop the own `Datetime` / `Date` / `Time` / `Offset`: a value is one of an
   offset date-time (`LocalDateTime` + `UtcOffset`), a local date-time, a local date or a local time. The written
   spelling stays in the repr, so printing is unchanged. A second of 60 (a leap second) is valid TOML that
   `LocalTime` cannot hold, so it becomes a typed parse error; no toml-test file uses it. `:core` gains
   kotlinx-datetime as an `api` dependency, and PARITY records the difference.
2. **A Kotlin-first API, in slices** (values, then tables and arrays, then the document). Replace the Rust-shaped
   names (`iter()`, `len()`, `asStr()`, `getMut()`, `clone()`, `fromIter()`, the `description()` strings on
   `TokenKind`, `Encoding` and `IntegerRadix`) with Kotlin idioms: properties, operators, `List` and `Map` views.
   Parse from a kotlinx-io `Source` (bytes are already parsed, with a typed `InvalidUtf8`) and write to a `Sink`. The
   upstream tests are rewritten against the new API, and PARITY becomes a map of behaviour and tests instead of
   names.

### 12. 2026-10-03 00:47 - Supersedes next step 1: date-times stay hand-rolled in core

**Wanted:** decide whether core should hold date-times as kotlinx-datetime types.

**Got:** core's own `Datetime` / `Date` / `Time` / `Offset` (about 480 lines with the parser) already cover every
TOML date-time, a leap second and a written offset included, and match upstream, whose `toml_datetime` is hand-rolled
too, with no date library. kotlinx-datetime would refuse `:60`, still need a wrapper for an offset date-time, and put a
pre-1.0 library (0.8.0) into core's public API. Its types are `@Serializable` already, as ISO strings.

**Doing:** keep the hand-rolled types in core. Conversions to kotlinx-datetime (`toLocalDate()` and the rest) go into
the serialization module, which depends on kotlinx-datetime anyway. There, a kotlinx-datetime field reads from a TOML
date through its own string serializer; writing one as a native TOML date, not a quoted string, means recognising its
serial name in the encoder. Next step 2, the Kotlin-first API, is now the next step.

### 13. 2026-10-03 01:02 - The Kotlin-first API: what the audit decided, and two bugs it found

**Wanted:** a call on every public declaration before reshaping the API (next step 2).

**Got:** an audit of the whole surface. The four forks were decided by the owner:
- spans: dropped from the public API; `Document` goes internal, every `span()` member goes, only parse errors carry
  positions;
- collections: `Iterable` plus `size` and nullable `get`, no `List` or `Map` (their `get` would throw);
- writer: the `writer` package goes internal, as does `parser`, once `Span`, the parse-error reasons and
  `Expected` move to the root package;
- `Decor`: an immutable `data class Decor(prefix, suffix)` behind `var decor`.

Also planned:
- the `Mut`, `into` and `clone` duplicates go;
- `RawString`, `Repr` and `Formatted` go internal behind value constructors and properties;
- collection vocabulary (`size`, `add`, `put`, `removeAt`, `getOrPut`, `sortWith`/`sortBy`);
- `set` overloads replace the top-level `value()`, `table()` and `array()`;
- `xOrNull` properties replace `asX()` and `isX()`;
- `DocumentMut` becomes `TomlDocument` with `val root`;
- `visit` and `visitmut` go;
- date-time fields become `Int`.

The order is values, then tables and arrays, then the document, then the lower layers.

**Bugs found, NOT YET VERIFIED by a test:**
- `Table.set` stores the caller's `Item` object, so `doc["b"] = doc["a"]` shares one slot between both keys;
- an array slot accepts a table, and an array-of-tables slot accepts a value.

**Doing:** keep the internal storage and the print path; expose the new API as views over them. Keep the field names
and `@SerialName`s of the error types, which are the `--json` wire format. Run the round-trip tests after slice 1.

### 14. 2026-10-03 02:22 - Next step 2 is done: the Kotlin-first API is on master

**Got:** four waves of subagents, each on its own branch and worktree, each verified with
`./gradlew clean build --rerun-tasks` (all tests on JVM and linuxX64, the official toml-test runner for 1.0 and 1.1):
- `parser` and `writer` internal; `Span` and the parse-error reasons public in the root package; visitors deleted;
- slot aliasing fixed (storing an item stores a copy), and array, array-of-tables and inline-table slots accept only
  their kind;
- values with constructors, `xOrNull` properties, an immutable `Decor`, no public spans, `Int` date-time fields;
- containers with Kotlin collection vocabulary, `getOrPut`, `Item()` constructors, `doc["a"]["b"] = "x"`;
- `TomlDocument` with `val root: Table`, and `read(Source)` / `writeTo(Sink)` with typed, serializable errors.

The expected outputs of every upstream and round-trip test, and the `--json` wire format, are unchanged. The one
upstream test whose input changed is `EditTest.tableUnderInline`: inline tables hold only values.

**Left, undecided:** `Table.sortValuesWith` versus `sortWith`, and `Item.isNone`.

**Next:** the kotlinx.serialization module (`@Serializable` models to and from TOML), including date-time fields as
native TOML dates; then `:config` in kontext moves onto kotoml.

### 15. 2026-10-03 02:32 - The serialization module's rules, decided before building it

**Wanted:** settle how `@Serializable` models map to TOML before an agent builds `com.fromwau.kotoml:serialization`.

**Decided by the owner:**
- **Update in place:** besides `encodeToString` / `decodeFromString`, an `encodeInto(document, value)` writes a value
  into an existing `TomlDocument`: existing keys keep their comments, order and spelling, new keys are appended.
- **Null:** a null field is left out when writing. A missing key reads as the field's default, as null for a
  nullable field without one, and is a typed error naming the key path otherwise.
- **Lists of objects:** configurable per format instance, `[[tables]]` by default. Lists of scalars stay inline
  arrays. Reading accepts both forms.
- **Unknown keys:** a typed error by default, `ignoreUnknownKeys = true` to allow them. `encodeInto` never deletes
  keys the model does not know.
- **Enums:** the name as a string, honouring `@SerialName`, read case-sensitively.
- **Sealed classes:** a table with a `type` key holding the serial name; the key name is configurable.
- **Pair and Triple:** as their own serializers say (`first`, `second`), with no special case.
- **Duration:** written in Kotlin's short form (`"15s"`); reading also accepts ISO-8601 (`"PT15S"`).
- **Date-times:** kotlinx-datetime fields are written as native TOML dates, recognised by their serial names. The
  module depends on kotlinx-datetime; core does not.
- **Errors:** decoding returns a `Result` with a typed, serializable error. kotlinx's `SerializationException` is
  caught at the module's edge.

### 16. 2026-10-03 02:59 - The serialization module is on master, with three known gaps

**Got:** `com.fromwau.kotoml:serialization` (`Toml` format, `decodeFromString`, `encodeToString`, `encodeInto`),
121 tests, core untouched. Every public entry point returns a `Result`.

**Known gaps, open:**
1. `encodeInto` removing a key (a field set to null) also removes the comment lines above it, because in core
   they are that key's decor. This matters most for `:config`.
2. A changed list is replaced whole: comments and unknown keys inside its elements are lost; only each `[[...]]`
   header's own decor survives.
3. With `encodeDefaults` off, `encodeInto` runs the serializer twice.

**Next:** `:config` in kontext moves onto kotoml (and off tomlkt), after klap 0.5.0 is released.

### 17. 2026-10-03 03:08 - How upstream handles the bugs and gaps we found, and the open tasks

**Wanted:** know whether Rust's `toml_edit` (pinned commit) has the problems kotoml found, before fixing the rest.

**Got:** checked in upstream's code:
- **Slot aliasing:** impossible upstream. Assigning moves the value, so `doc["b"] = doc["a"].clone()` is required.
  kotoml's bug came from porting moves to shared Kotlin references; copy-on-store is the Kotlin equivalent.
- **Wrong kind in array slots:** upstream has the same bug.
  - `Array` stores `Vec<Item>` (`array.rs:19`), and `IndexMut<usize>` returns `&mut Item` from it (`index.rs:25`),
    so a table can be assigned into an array.
  - Printing skips non-values (`iter_values`, `array.rs:113`), so the table silently vanishes.
  - Upstream documents it instead (`Array::len`, `array.rs:123`: "placeholder elements will exist").
  - Arrays of tables take a value the same way (`index.rs:27`).
  - kotoml refuses both with a typed error.
- **Inline table holding a Table:** upstream allows it, printing a table as dotted keys and dropping an array of
  tables. kotoml refuses it, or converts it in `putItem`.
- **Serialization gaps:** upstream's serde support only builds or reads whole documents; it has no `encodeInto`.
  - Gaps 2 and 3 exist only because kotoml offers update-in-place.
  - Gap 1 (removing a key removes the comment above it) happens upstream too whenever a key is removed by hand: the
    comment lines are the key's `leaf_decor` (`key.rs:36`).

**Open tasks:**
1. **Removing a key drops the comments above it** (gap 1). It matters for `:config`: setting a field to null deletes
   its explanatory comment. Decide whether `encodeInto`, or core's removal, should keep the comment lines, for
   example by moving them to the next key.
2. **A changed list is replaced whole** (gap 2): comments and unknown keys inside its elements are lost; only each
   `[[...]]` header's own decor survives. Consider an element-wise merge for arrays of tables.
3. **`encodeInto` runs the serializer twice** when `encodeDefaults` is off (gap 3). It only matters for serializers
   with side effects; document it or avoid the second pass.
4. **Undecided names:** `Table.sortValuesWith` versus `sortWith`, and `Item.isNone`.
5. **Release klap 0.5.0** (branch `feat/json-error-structure` in `~/Projects/klap-json-error-structure`, uncommitted)
   before pushing kotoml; until then kotoml builds only where klap 0.5.0 is in mavenLocal.
6. **Move `:config` in kontext onto kotoml** (off tomlkt), after 1 is settled.

### 18. 2026-10-03 03:34 - RESOLVES open task 1 of entry 17: removing a key keeps the comments above it

**Got:** on master as `6204f1b`. Every removal path in core keeps the comment and blank lines above the removed key or
header in place (`KeptLines.kt`); `encodeInto` gets it through core. A property test removes every top-level key of
every commented toml-test valid file and checks that only the key's own bytes disappear. Five ported upstream tests
changed their expectations to the new rule, as PARITY records. Replacing (`put(String, …)`, replacing a table,
the `to*` conversions) still drops a key's lines.

**Decided:** test functions are named as backtick sentences everywhere, ported upstream tests included; PARITY's test
map moves to the sentences.

### 19. 2026-10-03 03:42 - Which comments a removal keeps: what is above stays, what is inside goes

**Decided by the owner:** a comment above a removed key may not be about that key (for example a commented-out
alternative, `#host = "127.0.0.1"` above `host = "localhost"`), so it stays. What belongs to the removed thing
goes with it:
- comments above the removed key, header or element: kept (as `6204f1b` does);
- the comment on the removed key's own line: removed with it;
- comments inside a removed table's body, inside a removed multi-line array or inline table value: removed;
- comments above a nested header inside a removed table (`[a.b]` when removing `[a]`): removed;
- removing a whole array of tables: the comment above its first `[[x]]` stays, those above later elements go;
- `removeAt` on one `[[x]]` element: the comment above it stays.

**Open task:** `6204f1b` keeps the comments above nested headers and above every element of a removed array of
tables; change those two cases to the rule above. Do it after the test-name pass is merged.

### 20. 2026-10-03 03:49 - The owner confirmed every case of entry 19's comment rule

**Got:** the test-name pass is on master (`71e0203`): 543 tests renamed to sentences, counts unchanged.
The owner answered eleven concrete cases:
1. keep the comment above a removed key;
2. remove the comment on its own line;
3. keep the comment above a removed table's header;
4. remove comments inside the removed table's body;
5. remove the comment above a nested header inside it;
6. for a whole array of tables, keep the comment above the first `[[x]]` and remove those above later elements;
7. keep the comment above a `removeAt` element of an array of tables;
8. remove comments inside a removed multi-line array;
9. remove comments inside a removed inline table;
10. on `removeAt` in a multi-line array, keep the comment above the element and remove its same-line comment;
11. keep the comment above the last key of a table, before the next header.

**Doing:** each case becomes a test with the exact example shown to the owner, and the code is changed where it
differs.

### 21. 2026-10-03 04:09 - The comment rule is pinned in thirteen cases and on master

**Got:** on master: the eleven cases of entry 20, plus two the owner confirmed afterwards:
- removing a table whose sub-table prints before it keeps only the comment above the first header;
- `clear()` on an array of tables keeps the comment above every element, as `removeAt` does, while removing the
  whole key keeps only the first.

All thirteen are in `RemovalCommentScopeTest`. The fix also repaired invalid output from `6204f1b`: removing the
last element of a multi-line array or inline table printed its closing bracket inside a comment.

**Left alone:** removing through a dotted inline table still uses the older kept-lines path, which can keep the removed
entry's same-line comment.

### 22. 2026-10-03 04:38 - RESOLVES open task 2 of entry 17: encodeInto merges lists element by element

**Got:** on master as `b82144b`. A changed list is no longer replaced whole:
- equal elements match first, compared as TOML values, wherever they moved;
- the rest pair with the old element that shares the most values (scalars by position);
- what is left is inserted, or removed with the owner's removal rule.

Ten spec tests (`ListMergeTest`) were written first and approved by the owner case by case; the build did not touch
them. Three older expectations changed and were approved: a commented multi-line list keeps its layout, a one-line
list keeps its inner spacing, and a table that takes another's place gets none of its comments, header line included.

**Known limit, open:** if one update both reorders tables and removes others, the comment kept from a removed table
prints at that table's old position, which a moved table may now occupy.

**Open from entry 17:** `encodeInto` running the serializer twice, the two undecided names, the klap 0.5.0 release,
and moving `:config` onto kotoml.

### 23. 2026-10-03 05:41 - RESOLVES open task 3 of entry 17, and every nullable type is refused

**Got:** on master:
- `encodeInto` runs the serializer a second time only when a field at its default has a key in the document
  (`8eebf66`).
- Every nullable type is refused by type, writing and reading, with `NullableType(path)` (`b5555a1`). This replaces
  entry 15's null rules: a missing key means the field's default, never null.
- An intermediate design (null allowed only as a field's default) was built and dropped: the owner found it confusing,
  and it could not keep both one run and the rule "a field at its default updates its key".

The README's "Nullable types" section shows how to model an optional value or section without null.

**Known limit:** a type known only from a value (a default-polymorphic subclass, a generic contextual serializer) is
refused only when a value of it is written or read, so a nullable field it leaves out at its default is not refused.

**Open from entry 17:** the two undecided names (`sortValuesWith`, `Item.isNone`), the klap 0.5.0 release, and
moving `:config` onto kotoml, whose optional sections (`calibration: Calibration? = null` and similar) must then be
modelled without null.

### 24. 2026-10-03 05:51 - Tables sort like collections; an inline-table sort leaves the spacing behind

**Got:** `chore/table-sort` (`ac2c09e`): `sortBy { }` and `sortWith(comparator)` on `Table` and `InlineTable`
replace `sortValues()` and `sortValuesWith`; `TableLike` has no sort member. Standing rule from the owner: the
container API follows Kotlin's collection vocabulary wherever it fits.

**Open tasks:**
1. **Inline-table sort spacing.** Each entry's spacing moves with it, so sorting `{ c = 3, a = 1, b = 2 }` prints
   `{ a = 1, b = 2 , c = 3}`. The pre-existing behaviour, older than the rename. Positional spacing (after `{`,
   between entries, before `}`) should stay where it was while the entries move, as `TomlArray`'s one-line sort
   already does.
2. **Collection-API audit:** compare `Table`, `InlineTable`, `TableLike`, `TomlArray`, `ArrayOfTables` and
   `TomlDocument`'s forwards with Kotlin's `MutableMap` / `MutableList` vocabulary; the owner approves a table of
   renames and additions before anything is built.

### 25. 2026-10-03 11:22 - The collection-API audit is built; assigning a table through a bare slot keeps the key's spacing

**Got:** `feat/collection-api` (`cd1382d`, `e32a858`, `8c43407`): all thirteen approved audit proposals, plus two
fixes the owner approved:
- a key whose value changes kind (value to or from a table) leaves its spacing behind, so `[[bookmark]]` stays
  clean;
- a header's default blank line goes above the comments it inherits.

**Open task:** `doc["k"].assign(Item(table))`, calling `assign` directly on a slot, cannot reset the key's spacing,
because a slot does not know which key holds it. `put`, `set` and `doc["s"]["k"] = table` are covered. Fixing it
needs a link from a slot back to its entry.

**Still open:** entry 24's inline-table sort spacing; the klap 0.5.0 release; moving `:config` onto kotoml.

### 26. 2026-10-03 11:37 - RESOLVES entry 24's open task 1, and corrects its claim about arrays

**Got:** `fix/inline-sort-spacing` (`25131f7`). Entry 24 said `TomlArray`'s one-line sort already kept positional
spacing; it did not: `[ 3, 1, 2 ]` sorted to `[ 1, 2 , 3]`, the same bug. Both now keep the spacing at its position
when the container prints on one line; a multi-line one still moves each element with the comment above it. Four
tests in `TableSortTest`; clean build green, core 554, serialization 168, app 22, JVM and linuxX64.

**Still open:** entry 25's `assign` leftover; the klap 0.5.0 release; moving `:config` onto kotoml; `Item.isNone`.

### 27. 2026-10-03 11:46 - RESOLVES entry 25's open task: assign through a slot resets the key's spacing

**Got:** `fix/assign-key-spacing` (`60c1c81`). `KeyValue` links its slot back to
itself (`Item.entry`), and `KeyValue.prepareFor` holds the kind-change spacing rule that `put` and `assign` both
call. Four tests in `CollectionApiTest`, one nested. My first expectation for the commented case predated `8c43407`
(spacer above the comments); corrected to match `put`'s. Clean build green: core 558, serialization 168, app 22.

**Still open:** `Item.isNone`; the klap 0.5.0 release; moving `:config` onto kotoml.

### 28. 2026-10-03 11:53 - RESOLVES entry 17's open task 4: `isNone` is now `isMissing`

**Got:** `chore/is-missing` (`b57946b`). The owner picked `isMissing` with `TomlType.Missing` over keeping `isNone`,
dropping it for `type == TomlType.None`, or `isEmpty` (which reads as a container with no elements). The human
type name is now "missing"; `--json` errors carry `"Missing"`. No test pins either text. The Rust debug snapshots in
the ported tests keep upstream's `None`. Clean build green: core 558, serialization 168, app 22.

**Still open:** the klap 0.5.0 release; moving `:config` onto kotoml.

### 29. 2026-10-03 11:57 - Pins entry 28's two unpinned texts

**Got:** `4b26658`, on master with `b57946b`. `TypeNameTest` pins the human name "missing" and `isMissing` on an
absent key; `ErrorJsonTest` pins `"found":"Missing"` on the wire. Neither text has a public path that reaches it
today: `TomlType.describe` names only a value a dotted key tried to extend, and a decode with nothing there reports
`MissingKey`. So both tests build the value directly. Clean build green: core 560, serialization 169, app 22.

### 30. 2026-10-03 12:02 - Corrects entry 29: a decode can report `found = Missing`

**Wanted:** verify entry 29's unmarked claim that a decode with nothing there always reports `MissingKey`.

**Got:** half right. Measured in `MissingValueTest` (`test/missing-value`, `7637e9e`):
- a generated serializer reports a field with no key and no default as `MissingKey`, at its full path inside a list
  element (`servers[1].port`);
- a hand-written serializer that calls `decodeIntElement(descriptor, 0)` without asking `decodeElementIndex` gets
  `TypeMismatch("port", Integer, Missing)` from an empty document. So `found = Missing` is reachable through the
  public API, not only by building the error by hand.

**Severity:** paper cut; my own error: I stated something I had only read in the code.

Clean build green: core 560, serialization 172, app 22.

## Phase: the installed app

### 31. 2026-10-03 12:21 - `kotoml check <TAB>` offered no files: the file argument lacked `.file()`

**Wanted:** path completion on `get` and `check`'s file argument after installing with `makepkg -si` (owner report).

**Got:** `kotoml __complete -- check ""` printed nothing. klap completes paths only for an argument marked
`.file()` (completion metadata only; it does not check the path), and `fileArgument()` never marked it. With it,
`__complete` prints klap's files directive for both commands. Fixed in `fix/file-completion` (`5bccca1`).

**Severity:** paper cut for users; found only by using the installed binary.

**Not pinned:** no app test drives the klap command tree (`main` builds it inline), so completion has no test.

**Also seen:** `makepkg` prints `libfakeroot internal error: payload not recognized!`. A known, harmless fakeroot
warning; `!debug` is already set here. The package's files are `root:root` with the right modes, and
`pacman -Qkk kotoml` finds nothing altered.

### 32. 2026-10-03 12:40 - Three agents played with the installed 0.1.0 binary: what they found

**Wanted:** bugs and surprises from real use of `kotoml` (owner's request), on copies of `lib.versions.toml`.

**Bugs, reproduced by me as well:**
1. `get` of a table made only of dotted keys prints nothing (`printf 'a.b = 2\n' | kotoml get .a`); `--json` is right.
2. A quoted filter key decodes `\t` and `\n` to the letters `t` and `n` (`get '."t\tk"'` reads `ttk`).
3. `get` on a CRLF file prints LF line endings.
4. `get '.srv[]'` over an array of tables runs the tables together, with no separator.
5. `check --toml 1.0` on `"\q"` lists the 1.1-only escapes `e` and `x` as expected.
6. `a = 0x` (and `0b`, `0o`) reports "integer number overflowed". It may be upstream's text: NOT YET CHECKED.
7. `encode` ignores the date-time tag: `datetime` + `"1979-05-27"` prints a local date, exit 0.
8. `encode` segfaults (exit 139, no message) on JSON nested 50000 deep.
9. `encode` accepts number spellings outside TOML/JSON: `0x1p3`, `1f`, `Infinity`, `+5`, `007`, Arabic-Indic digits.

**Surprising, as reported by the agents, NOT YET VERIFIED by me:**
- `get .srv` prints an array of tables as inline tables and loses its comments.
- `[tbl.sub]` prints as `[sub]`.
- `.nope[]` exits 0 with no output.
- Filter runtime errors exit 1; usage errors exit 2. The README describes 2 only for "filter does not parse".
- `--color always` colours only help.
- `--json` prints human text for help, docs and completion.
- Some `--json` errors put a sentence in `detail`: permission denied, and `encode`'s JSON parser message.
- `encode` error paths always name `.type`.
- `encode` output can be refused by `decode` (depth 80) or by `check --toml 1.0` (`07:32Z`).
- Lone surrogates become U+FFFD silently.
- Errors with no position: invalid UTF-8 and dotted keys over 80 parts. A 100k-bracket line dumps 200 KB to stderr.
- **Nits:**
  - the underline counts bytes;
  - a leading BOM shifts columns;
  - "duplicate key" text has no key name;
  - "expected nothing";
  - "arrayoftables";
  - decode prints `1.0E10` and `-00:00` as `+00:00`;
  - encode output ends with a blank line;
  - mixed-case help text.

**Worked:**
- Round trips of every value type.
- Exit codes for invalid and unreadable input.
- `--json` streams.
- Structural conflicts.
- The 1.0 and 1.1 rules (apart from item 5).
- Large inputs.
- All three completion scripts parse.
- `__complete` gives the files directive.

### 33. 2026-10-03 12:41 - Proposed order for entry 32, and the decisions it needs from the owner

**Order proposed:**
1. The `get` output bugs (32.1 to 32.4), since they break the tool's main promise.
2. `encode` (32.7 to 32.9).
3. 32.5.

**Owner decisions needed:**
- **32.6:** keep upstream's "integer number overflowed" for `0x`, or move away from upstream's wording.
- **32.4:** how `.[]` prints tables (a blank line between them, `[[x]]` headers, or something else).
- **`get .srv`:** whether an array of tables prints as its `[[srv]]` layout instead of inline tables.
- **Exit codes:** whether runtime filter errors and usage errors keep exit 1 and 2, or whether the README changes.

Nothing from entry 32 is started yet.

### 34. 2026-10-03 12:45 - Roadmap after entry 32's fixes: set, a richer selector, then a formatter

**Wanted (owner):** kotoml as an all-round TOML tool (get, edit, fmt), in this order after entry 32 is fixed:
1. **A setter in the CLI.** `get` only reads today; there is no way to change a value from the command line. The
   core edit API (`put`, `assign`, format-preserving printing) already exists, so this is mainly a CLI surface.
2. **A jq-like selector**, maybe before the formatter. `get` understands only paths (`.a.b`, `.[0]`, `.[]`, `|`);
   jq's filters (`select`, `map`, comparisons and so on) are not there.
3. **A `:formatter` module** for `kotoml fmt`.

**Open, for the owner when each one starts:**
- **Setter:** the command shape (`set <path> <value>`, editing in place, or printing to stdout), and how a value's TOML type is
  given.
- **Selector:** how much of jq to support.
- **Formatter:** what rules it applies, and whether it is its own module.

Nothing started.

### 35. 2026-10-03 12:50 - Owner decisions on entry 34's roadmap, and whether a formatter is worth building

**Decided by the owner:**
- **Setter:** OK as described in entry 34.
- **Selector:** jq-like; the scope is still to be decided.
- **Formatter:** to be decided, after checking what already exists.

**Got (formatter research):**
- **tombi** (github.com/tombi-toml/tombi, MIT) is a formatter, linter and language server. Checked in a shallow clone:
  - Its CI runs toml-test for `v1.0.0`, `v1.1.0-preview` and `v1.1.0`, through `cargo xtask toml-test`, and I found
    no skip list.
  - Its last commit is 2026-10-02.
  - Its configuration accepts `toml-version = "v1.0.0" | "v1.1.0"`.
- **taplo**, the older toolkit, is TOML 1.0 only. Search results call it effectively abandoned and say users are
  moving to tombi. NOT VERIFIED beyond those search results.
- **The "tomlfmt" tools** (cargo-tomlfmt, minrk/tomlfmt, a Go package and others) are small single-purpose
  formatters.
- **A caveat on test coverage:** toml-test tests parsing and encoding, not formatting. So passing it says nothing
  about a formatter's output.

**Recommendation:**
- **No `kotoml fmt` CLI.** tombi already covers it for TOML 1.0 and 1.1, it is maintained, and it ships with an
  editor integration that a CLI command would not match.
- **kotoml's own niche** is the Kotlin library (format-preserving edits, kotlinx.serialization) plus a jq-like CLI.
- **A formatter API inside the library** would only be worth it if a Kotlin user needs to normalise generated TOML;
  core's `resetFormatting` already covers part of that.
- Owner to decide.

### 36. 2026-10-03 12:54 - The formatter is a "maybe", for the library and the CLI; no Kotlin TOML formatter exists that I found

**Decided by the owner:** the formatter is a "maybe, if bored" item. If it is built, `fmt` would exist in the library
as well as in the CLI.

**Got:** I found no Kotlin TOML formatter:
- **ktoml, tomlkt, 4koma:** parse and serialize; tomlkt can write comments through annotations; none reformats an
  existing document.
- **`KotlinMania/toml-kotlin`:** also a KMP port of toml-rs, but line by line.
  - It is published as `io.github.kotlinmania:toml-kotlin:0.1.0`, but it is in progress: 45 Kotlin files, the port
    report says "To Do", and it is part of a mass port of Codex.
  - It has no formatter and does not mention toml-test.
  - Checked in a shallow clone.
- Not exhaustive: one web search plus that clone.

### 37. 2026-10-03 12:56 - Corrects entry 35: tombi's `v1.1.0-preview` is not a separate toml-test version

**Got:** tombi's `xtask/src/command/toml_test.rs` maps both `V1_1_0_Preview` (deprecated) and `V1_1_0` to toml-test's
`1.1`. Its three CI jobs therefore run two test lists, the same two kotoml's `check` runs (`files-toml-1.0.0` and
`files-toml-1.1.0` in the submodule at `ff49d10`). Nothing to add to kotoml's suite.

### 38. 2026-10-03 13:02 - Owner decisions on entry 33: `.[]` gets blank lines, `get .srv` prints as written, `0x` gets its own typed error

**Decided by the owner, after seeing examples:**
- **Entry 32.4, `get '.srv[]'`:** one table after another, separated by a blank line. Each prints like `get .package`:
  its lines, with no header.
- **`get .srv`:** an array of tables prints as written in the file: `[[srv]]` headers, its comments and its layout. It no
  longer prints as one inline array.
- **Entry 32.6, `a = 0x`:** a clear message backed by its own typed reason, moving away from upstream. This changes
  three upstream snapshots, which pin "integer number overflowed" today: `core/src/commonTest/snapshots/toml_edit/invalid/integer/incomplete-{hex,oct,bin}.stderr`.
  - Cause: `EditParser.kt:547` reports `Reason.IntegerOverflow` for any failed integer parse, as upstream does, so a
    prefix with no digits is reported as an overflow.

### 39. 2026-10-03 13:13 - RESOLVES entry 32.1 to 32.4 and 32.6: `get` prints what the file holds; `0x` is MissingDigits

**Got:** `fix/get-output`, docs first (`333cc0d`), then the fixes (`147447c`, `83298d7`).
- **The evaluator carries each result's key path.** An array of tables prints as written under its full
  `[[a.srv]]` header, comments included.
- **`.[]` over tables** sets each table apart with a blank line.
- **A table of dotted keys** prints its lines.
- **Quoted filter keys** take jq's escapes. An unknown escape is `FilterExpected.Escape` and exits 2.
- **A CRLF document prints CRLF.** klap ends output with `\n`, so `get` adds the `\r`.
- **`0x`, `0o` and `0b`** report `Reason.MissingDigits(radix)`. The JSON radix is `"Hex"` from the existing
  `IntegerRadix` enum, not the `"Hexadecimal"` shown to the owner. Our three snapshot copies changed; PARITY
  records it.
- **Checked on the native binary** with the agents' reproductions.
- **Clean build green:** core 1126, serialization 344, app 58 (JVM and linuxX64 counted together).

**Left alone:** `get .tbl` prints a sub-table's header relative to the selection (`[sub]` for `[tbl.sub]`), as entry
32 reported. Not part of the owner's decisions.

**Still open from entry 32:** 32.5 (1.0 escape list); `encode` 32.7 to 32.9.

### 40. 2026-10-03 13:16 - Rust terms left in the code: none public, two groups internal (owner report)

**Wanted:** no Rust vocabulary in kotoml's code. The owner noticed `unwrap`.

**Got:** surveyed on master. No public declaration in core, serialization or app uses one. They remain in:
1. **Test helpers `unwrap()` and `unwrapErr()`.** Defined twice: `core/src/commonTest/.../ResultAsserts.kt` and
   `serialization/src/commonTest/.../Models.kt`. 320 uses, tests only. Proposed names: `getOrFail()` and
   `errorOrFail()` (they call kotlin.test's `fail`).
2. **Internal names from the port:**
   - `RawString` (69), `Repr` (30), `despan` (50);
   - `asStr` (24), `toStrWithDefault` (11), `asRaw` (6), `newUnchecked` (15);
   - `shiftRemove` (4), `deepClone` (3), `IndexMap` (1, in a doc);
   - `parseRustI64`, `parseRustF64`, `compareRustStr` (2 each).

**Proposed:** pure renames, no behaviour change. Doc comments that say "the way Rust's X does" stay where they
explain copied behaviour; PARITY keeps mapping upstream names.

**Open for the owner:**
- Both groups, or one?
- A table of new internal names to approve before the rename.

### 41. 2026-10-03 13:23 - RESOLVES entry 32.5; the version option already exists; upstream's escape list lacks `t`

**Got:**
- **Entry 32.5:** fixed in `fix/escape-list-1-0` (`1a55599`). `ParseContext.decodeErrors` swaps in 1.0's escape list
  for `MissingEscapedValue` when reading 1.0; it is used for strings and quoted keys. Two tests in
  `TomlVersionTest`. Clean build green.
- **The version option:** the owner asked for `--tomlVersion` with completion and 1.1 as the default. It already
  exists as `--toml <1.0|1.1>` on `get`, `check` and `decode`, with completion and `(default: 1.1)`, checked on the
  installed binary. The only open point is the name. I proposed `--toml-version`, since CLI options are kebab-case
  and every other kotoml option is too.
- **Found while fixing:** neither escape list has `t`, though `\t` is valid in both versions. It is upstream's
  list: `bad-escape-01.stderr` pins `b, e, f, n, r, \, ", x, u, U`. Adding `t` changes our copies of upstream
  snapshots, which needs the owner's approval as with `0x`.

**Owner decision (2026-10-03 13:25):** keep \`--toml\`; no rename.

### 42. 2026-10-03 13:35 - The bad-escape hint lists `t` (owner approved)

**Got:** `896f051` on `fix/escape-list-1-0`. Both escape lists now have `t` after `r`.
- **Copies changed to match:**
  - 10 `.stderr` snapshots;
  - 1 Rust-debug snapshot (`toml_parser/...string_confusing_quotes_4.txt`);
  - 2 expectations in `DecoderStringTest`;
  - the 1.0 tests in `TomlVersionTest`.
- **PARITY records the difference.**
- **My miss:** my first text search found only the `.stderr` form. The clean build caught the Rust-debug form. Clean
  build green: core 1130, serialization 344, app 58.

### 43. 2026-10-03 13:39 - RESOLVES entry 32.7: `encode` checks a date-time's kind against its tag

**Got:** `fix/encode-datetime-tag` (`8bc50c7`).
- **One rule for both directions:** `Datetime.tag()` maps each kind to its toml-test tag. `decode` writes with it, and
  `encode` now refuses a value whose kind is not its tag's, as `NotTaggedJson("$.a")`.
- **Tests:** two in `TaggedJsonTest` (5 mismatches refused, 4 matches round-trip).
- **Clean build green, toml-test 1.0 and 1.1 included:** core 1130, serialization 344, app 62.

**Not changed:** `NotTaggedJson` still says only where, not what was wrong (entry 32, "encode's error paths").

### 44. 2026-10-03 13:46 - RESOLVES entry 32.8: `encode` refuses deep JSON instead of crashing

**Got:** `e5af8de` on `fix/encode-datetime-tag`.
- **The crash is kotlinx.serialization's:** `JsonTreeReader.readArray` and `read` recurse once per level. Measured on
  the JVM as a `StackOverflowError`; on Kotlin/Native it is a segfault.
- **The fix:**
  - `parseTaggedJson` measures bracket nesting (ignoring brackets in strings) before kotlinx sees the text.
  - Past `TomlDocument.NESTING_LIMIT + 2 = 82` it refuses with `AppError.JsonTooDeep(limit)`, exit 1.
  - The `+ 2` is the root object and a scalar's tag object. `decode` accepts 80 nested arrays and refuses 81
    (measured), and the 80-deep JSON still encodes.
- **The limit is shared:** core's internal `LIMIT` is now the public `TomlDocument.NESTING_LIMIT`, approved by the
  owner as "reuse LIMIT".
- **Verified on native:** the 50000-deep input prints `error: JSON nests deeper than 82 levels`, and `--json` prints
  `{"error":{"type":"JsonTooDeep","limit":82},"code":1}`.
- **Clean build green:** core 1130, serialization 344, app 70.

**Known gap:** the count matches arrays exactly, not tables. JSON with 80 nested table objects is accepted (82
levels), but TOML refuses an 80-part key path, so `encode` can still print a table path that `decode` refuses.
That is not a crash.

### 45. 2026-10-03 13:48 - Open task (owner approved): `encode`'s errors say what is wrong, not only where

**Wanted:** typed `encode` errors that carry the reason, as `JsonTooDeep(limit)` does.

**Got:** today:
- `NotTaggedJson(path)` names only where, for every wrong tag, bad value, missing `value` or extra key.
- `JsonTooDeep(limit)` has no path.

**Doing (not started):**
- Give `NotTaggedJson` a typed reason, for example:
  - `DatetimeKindMismatch(tag, found)` (from entry 32.7);
  - `BadValue(type, value)`;
  - `UnknownType(type)`;
  - `MissingValue`;
  - `ExtraKey(key)`.
- Check entry 32's report that the path wrongly ends in `.type`.
- Consider a path for `JsonTooDeep`.
- The owner approves the exact reasons before building.

### 46. 2026-10-03 13:52 - RESOLVES entry 32.9: `encode` reads numbers in JSON's grammar

**Got:** `5c8bd95` on `fix/encode-datetime-tag`.
- **Evidence:** toml-test's README defines no number grammar, so I measured its valid fixtures instead. They contain
  44 integers, all shaped `-?digits`, and 40 floats, shaped `-?digits(.digits)?(e[+-]?digits)?` or `inf`, `-inf`, `nan`.
  That is JSON's grammar.
- **The rule:**
  - an integer matches `-?(0|[1-9][0-9]*)` and fits a `Long`;
  - a float matches JSON's number grammar (`e` or `E`, so decode's `1.0E10` reads back) and stays finite, or is
    `inf` or `nan` with an optional sign.
- **Now refused:** every spelling entry 32 reported. Four tests in `TaggedJsonTest`.
- **Clean build green, toml-test encoders included:** core 1130, serialization 344, app 78.

**Chosen without asking the owner:** the grammar, based on the fixtures above. The errors are still the where-only
`NotTaggedJson`, which entry 45 covers.

### 47. 2026-10-03 13:57 - Corrects entry 46: `encode` reads TOML's number spellings (owner decision)

**Decided by the owner:** "accept TOML's own spellings, such as +5 or 1_000; this is a toml lib". This replaces entry
46's JSON grammar. The commit was amended to `b264533`, so master never carries the JSON-only rule.

**Got:**
- **The rule:** `tomlNumber` reads the text with core's `Value.parse`, refusing surrounding whitespace.
  - An integer tag needs a TOML integer; every radix and `_` and `+` are accepted.
  - A float tag takes a TOML float, or an integer spelling converted.
  - `1e400`, `007`, `1f`, `0x1p3` and `Infinity` are refused, because TOML refuses them.
  - The value keeps its spelling, so `1_000` prints as written.
- **Tests:** four in `TaggedJsonTest`.
- **Clean build green, toml-test encoders included:** core 1130, serialization 344, app 78.

### 48. 2026-10-03 14:30 - RESOLVES entry 45: typed `encode` reasons, and suggestions on unknown names

**Decided by the owner:** positions on errors stay as they are. Suggestions: yes.

**Got:** `feat/typed-encode-errors`.
- **`f3a55d2`:** `NotTaggedJson(path, reason: TaggedJsonProblem)` with `RootNotObject`, `Untagged`, `UnknownType`,
  `MissingValue`, `ValueNotString`, `ExtraKey` and `BadValue` (which covers a date-time of the wrong kind).
  - An object whose `"type"` is a string is a tagged value even when malformed. So the three cases that reported
    `$.a.type` (entry 32) now report `$.a`. A table's own `type` key holds an object, so tables still read
    (tested).
  - Human text: `not toml-test's tagged JSON at $.a: "x" is not a valid integer`.
- **`a15788c`:** `UnknownEnumValue` and `UnknownSubtype` gain `suggestion`.
  - It is the name equal ignoring case, else the nearest within max(1, length / 3) edits, else null.
  - Serialization errors are data, so this is the type hint's place too: `TypeMismatch` already carries
    `expected` and `found`, and nothing was added there.
- **My misses:** two of my test expectations had wrong edit distances (`cirkel`, `sctp`); the build caught both.
- **Clean build green:** core 1130, serialization 354, app 92.

### 49. 2026-10-03 14:38 - Owner decision: kotoml keeps its own closest-name function; no shared dependency

**Got:** klap has a public `suggest(token, candidates, ignoreCase)`, also Levenshtein. It differs from kotoml's
`closestName` in four ways:
- a unique prefix wins at any distance;
- its bound is `1..max(2, candidate.length / 3)`;
- it never suggests a complete rewrite;
- it never suggests a case-only match.

So `sctp` gives `TCP` in klap and nothing in kotoml, and `tcp` gives nothing in klap and `TCP` in kotoml.

**Decided by the owner:** a duplicate is fine for something this small. It is not worth moving into kern or making
serialization depend on klap.

### 50. 2026-10-03 14:41 - `closestName` merges klap's rules with kotoml's case-only match (owner: "best of both worlds")

**Got:** amended into `130c3d8`. The rule, ignoring case:
1. a name that differs only in case;
2. else the only name the value begins with;
3. else the nearest within `max(2, name.length / 3)` edits that is not a complete rewrite.

The written word itself is never suggested.

`sctp` now gives `TCP`, the same as klap; `DecodeTest` was updated. There are 4 new tests in `SuggestionTest`. Clean
build green: core 1130, serialization 362, app 92.

### 51. 2026-10-03 14:44 - The suggestions are pinned end to end

**Got:** `b4809fd`: `DecodeSuggestionTest` has 9 tests.
- Each rule (case-only, unique prefix, nearest, none) goes through a real decode for `UnknownEnumValue` (`Mode`) and
  `UnknownSubtype` (`Shape`).
- The suggestion is pinned in both errors' JSON, set and null.
- Clean build green: serialization 380.

### 52. 2026-10-03 14:59 - Entry 32's remaining reports, verified on the installed binary, and the owner's rulings

**Got:** reproduced on the system `kotoml` built from `b4809fd` (`makepkg -sif`). Owner's rulings:
- **A, colour:** kotoml defines no colour for its output, so only klap's help is coloured. That is correct as is.
- **B:** `completion` and `docs` print their own output under `--json`. `kotoml --json` with no command becomes a JSON
  usage error.
- **C:** permission denied becomes a typed I/O failure, not a sentence.
- **D:** `encode`'s invalid JSON becomes a typed error with an offset, without kotlinx's dump of the input.
- **E:** the README's exit codes change to match the code: 1 for errors about data, 2 for usage.
- **F to I:** core's texts become clearer. Invalid UTF-8 and over-deep dotted keys get a line and column; "duplicate
  key" names the key; "expected nothing" goes.
- **J:** `[tbl.sub]` printing as `[sub]` under `get .tbl` stays.
- **K:** a missing key is an error, not empty or `null`; "more user friendly". Scope to confirm.
- **L:** "arrayoftables" becomes "array of tables".
- **M:** a lone surrogate becoming U+FFFD stays; kotlinx does it.
- **N:** "full TOML support: if it is valid TOML it stays, if not it is a bug". So a `-00:00` offset must survive
  (today `decode` writes `+00:00`), and `1.0E10` is valid TOML and stays.
- **O, P:** fine as is.

### 53. 2026-10-03 15:17 - Entry 52's rulings built, except B, which needs klap

**Got:** `fix/playtest-rulings`:
- **K, L and E** (`f0f1bd6`):
  - `NoSuchKey(path, key)` and `NoSuchIndex(path, index, size)`, exit 1. Each result carries the jq path that
    reached it.
  - Type names are printed in words ("array of tables").
  - The README's exit codes now match the code.
- **C and D** (`ea29835`, `7983ae1`):
  - `ReadFailure.PermissionDenied`, from `Files.isReadable` on the JVM and `access(R_OK)` on native.
  - `InvalidJson(position)` takes kotlinx's offset (parsed from its message) and counts from 1, as core's
    `TextPosition` does.
- **F to I** (`6a52f68`):
  - The key name is added to "duplicate key", and ", expected nothing" goes.
  - Invalid UTF-8 and over-deep dotted keys get positions.
  - 111 snapshot copies were regenerated from the parser through a temporary harness step, and each diff was checked
    to be one of the three agreed kinds.
  - Comments that still called the snapshots "copied unedited" were fixed.
- **N** (`c1248f7`): `Offset.Unknown` prints `-00:00`.
- **Clean build green:** core 1142, serialization 380, app 107.

**Found on the way:**
- The JVM and Kotlin/Native decode invalid UTF-8 differently: `ED A0 80` becomes 1 U+FFFD on the JVM and 3 on
  native. So the UTF-8 error shows its line only up to the bad bytes.
- The XML test report replaces a vertical tab with `?`. That is why snapshot rewrites came from the parser itself,
  not the report.

**Open:** B, `kotoml --json` with no command. klap prints help and exits 0 even under `--json`, so the fix belongs
in klap's unreleased 0.5.0 branch.

### 54. 2026-10-03 15:32 - RESOLVES entry 52's B in klap: a group without a subcommand under `--json` is a usage error

**Decided by the owner:** it is a klap bug; fix it, and leave a TODO that `--help --json` should print real JSON.

**Got:** in `~/Projects/klap-json-error-structure` (branch `feat/json-error-structure`). Left uncommitted, alongside the
owner's own uncommitted 0.5.0 work there.
- **The case:** `CliError.MissingSubcommand(parent)`.
  - JSON `{"type":"MissingSubcommand","parent":"..."}`, exit 2.
  - Human text "missing subcommand for '...'".
- **The fix:** `parse` turns the help a group falls back to into that error only under `--json`. An explicit `--help`
  is untouched, with a TODO in `Parser.kt` above `helpRequested`.
- **Tests and docs:** three tests in `JsonErrorTest` (root, nested group, help without `--json`); a sentence in
  `docs/guide.md`'s Structured `--json` section.
- **Verified:** klap 1116 tests green (JVM, linuxX64). Republished to mavenLocal. kotoml's native binary now prints
  `{"error":{"type":"MissingSubcommand","parent":"kotoml"},"code":2}` for `kotoml --json`, and still shows help
  without `--json`. kotoml clean build green.

### 55. 2026-10-03 15:44 - klap: `--help --json` prints the help as data (owner-approved shape)

**Got:** in `~/Projects/klap-json-error-structure`, uncommitted with the rest of klap 0.5.0.
- **The model:** `internal/render/HelpJson.kt` has `@Serializable` `HelpJson`, `ArgumentHelp`, `OptionHelp`,
  `CommandHelp` and `ExampleHelp`, encoded with `Json.encodeToString`. Fields that do not apply are left out by
  default-value omission. `--help-all --json` is an array of `HelpJson`.
- **One list of built-ins:** `builtinOptions()` in `Help.kt` now builds the built-in global options (help,
  help-all, json, color, version, completion, docs) for both forms. Text help renders rows from it. All existing
  text-help tests pass unchanged.
- **The wiring:** `Invocation.ShowHelp` gains `json`, set by `--help` and `--help-all`, and the runner prints
  `helpJsonText`. The TODO from entry 54 is gone.
- **Tests and docs:** four tests in `HelpJsonTest`; `docs/guide.md`'s Structured `--json` section updated.
- **Verified:** klap 1120 tests green on JVM and linuxX64, and `./gradlew build` green. Republished to mavenLocal.
  `kotoml get --help --json` prints the structured help.

**Owner ruling (2026-10-03 15:49):** `--toml 1.2` suggesting "Did you mean 1.0?" is fine. No klap fixes are left beyond releasing 0.5.0.

### 56. 2026-10-03 16:27 - One closest-match rule: kern:fuzzy, used by klap and kotoml (supersedes entry 49)

**Decided by the owner:**
- A shared module, generic enough for a fuzzy finder later: `kern:fuzzy`.
- kern goes to 0.4.0.
- klap's public `suggest` is removed; klap exports `kern:fuzzy` instead.

**Got:**
- **kern** (`~/Projects/kern-wt/fuzzy`, branch `feat/fuzzy`, from a clean master):
  - `ce0e448` adds `:fuzzy` with `editDistance` and `closestMatch` (the merged rule), 12 tests from both projects,
    a README and a row in the module table;
  - `4e0979f` bumps to 0.4.0.
  - Published to mavenLocal.
- **klap** (uncommitted, with the rest of 0.5.0):
  - kern 0.2.0 becomes 0.4.0, and `kern:fuzzy` is an `api` dependency;
  - `Suggest.kt` (public and internal) is deleted, and 10 call sites use `closestMatch`;
  - 5 rule tests moved to kern; the parser-agreement test now uses `closestMatch`;
  - the guide's did-you-mean section is updated.
  - klap's `settings.gradle.kts` gains a `com.fromwau`-scoped `mavenLocal`, as kotoml's has. Without it, kern 0.4.0
    could not resolve.
  - 1115 tests green on JVM and linuxX64; build green; republished.
- **kotoml** (`chore/kern-fuzzy`, `8fb253d`):
  - kern 0.4.0, and serialization uses `closestMatch`;
  - `Suggestion.kt` and `SuggestionTest` are removed; `DecodeSuggestionTest` passes unchanged.
  - Clean build green: core 1142, serialization 362, app 107.

**My miss:** a `compileKotlinJvm` I reported as clean had failed on resolution. I was grepping only for `e: `. I
now look for `BUILD` and `What went wrong`.

**Behaviour change in klap:** a case-only miss such as `--Verbose` now suggests `--verbose`.

**Release order:** kern 0.4.0 first, then klap 0.5.0, then kotoml can push.

### 57. 2026-10-03 16:38 - kern:fuzzy after one QA agent: `didYouMean`, swaps cost one edit, and the doc fixes

**Got:** one QA and review agent found no crashes or wrong distances. Its findings, all applied with the owner's
approval:
- **Docs:** they described the "complete rewrite" check wrongly ("share a character"). The code checks fewer edits
  than the longer word has characters.
- **Duplicates:** they were compared with case, so `"sta"` with `[status, STATUS]` gave no suggestion.
- **Case folding:** rule 1 compared characters one by one, while rules 2 and 3 lowercased whole strings. Case is now
  folded once.
- **Undocumented behaviour:** `editDistance` counts UTF-16 code units, and ties go to the first candidate. Both
  are now documented.
- **Swaps:** they cost 2, so `ls`→`sl` gave no suggestion. `editDistance` is now the optimal string alignment
  distance: a swap of neighbours counts as one edit, and no character is edited twice. `ca`→`abc` is 3, pinned.
- **Speed:** a candidate whose length alone puts it past the limit is now skipped before measuring. Not re-measured.
- **Naming:** `closestMatch` is now `didYouMean(written, candidates: Collection<String>)`, leaving "match" for a
  future fuzzy finder.

**Where:**
- kern `5c37706` on `feat/fuzzy`: fuzzy has 20 tests on JVM and linuxX64; kern builds; republished.
- klap: the rename at its call sites, the test and the guide (uncommitted). 1115 tests and the build are green;
  republished.
- kotoml: the switch-over amended to `f8e8e75`. Clean build green: core 1142, serialization 362, app 107.

### 58. 2026-10-03 16:43 - kern 0.4.0 released; klap no longer needs mavenLocal

**Got:** the owner squashed the fuzzy work into `f08db46`, tagged `v0.4.0`, pushed it and published it. I checked:
- The squash has the same tree as the reviewed `5c37706`.
- `fuzzy`, `result` and `terminal` list 0.4.0 as latest on maven.frommhund.xyz, and `fuzzy-jvm-0.4.0.jar` returns 200.

The `mavenLocal` I had added to klap's `settings.gradle.kts` is removed, so that file is back to the owner's version. klap builds with `--refresh-dependencies` from the release repository.

**Release order now:** klap 0.5.0, then kotoml.

**Open (owner, 2026-10-03 16:44):** remove kotoml's `mavenLocal` from `settings.gradle.kts` once klap 0.5.0 is released. Until then, `app` resolves klap 0.5.0 only from mavenLocal.

### 59. 2026-10-03 16:50 - The cleanup pass over my klap changes

**Changed:**
- **One tree walk instead of three:** `--help-all`, `--help-all --json` and the man/markdown docs each walked the
  visible subcommands themselves. `Docs.kt`'s private `docNodes` became `Command.visibleTree(qualifiedName)` in
  `Help.kt`, and all three use it. They must list the same commands, so this is one rule.
- **Import order:** `Parser.kt`'s `kern.fuzzy` import sat among the `klap` imports; it is now sorted.
- **The guide:** the `--json` paragraph is split in two, a line that ran past the wrap is fixed, and
  "`--version` does honour it" (whose "it" now pointed at the `MissingSubcommand` sentence) became
  "`--version` honours `--json` too".

**Left alone:**
- **`words()` and `spellings()` both order spellings.** One renders `--[no-]x` for text and suppresses generated
  negations; the other lists real tokens. They answer different questions.
- **`val node = this@builtinOptions`:** inside `buildList`, `this` is the list.
- **The owner's own 0.5.0 work** in the same files, such as the "Errors follow suit" paragraph and `ErrorJson.kt`.

**Open, a schema question for the owner:** an argument with `absentWhen` or `relaxedWhen` reports
`required: true` in JSON help, though text help says "absent with" or "optional with". It needs a field, such as
`requiredUnless`, or `required: false`.

**Verified:** klap 1115 tests green on JVM and linuxX64; `./gradlew build` green; republished.

### 60. 2026-10-03 16:57 - klap JSON help names an argument's condition: `absentWith`, `optionalWith` (owner chose B)

**Got:** `ArgumentHelp` gains `absentWith` and `optionalWith`, from `absentWhen` and `relaxedWhen` via the same
`constraintToken()` text help uses. So `tool cp` reports `DEST` with `"absentWith":"--target-directory"`, and
`tool rm` reports `file` with `"optionalWith":"--force"`. `required` stays true, as the base rule. One test in
`HelpJsonTest`; the guide's field list is updated. klap 1116 tests and the build are green; republished.

### 61. 2026-10-03 17:06 - kotoml resolves kern and klap from the release repository only

**Got:** klap 0.5.0 is released (metadata release 0.5.0; `klap-jvm-0.5.0.jar` returns 200). `chore/no-maven-local` (`6ac31c1`) removes the `com.fromwau` `mavenLocal` from `settings.gradle.kts`. The clean build with `--refresh-dependencies` is green: core 1142, serialization 362, app 107, toml-test included. No docs mentioned mavenLocal. RESOLVES the open item after entry 58.

### 62. 2026-10-03 23:45 - A sweep for Rust left in the code, by three read-only agents; supersedes entry 40's survey

**Got:** core main, core tests, and everything else (serialization, app, build-logic, docs) were each read in full.
I checked these claims myself: the broken `[TomlArray.fmt]` link (`Value.kt:275`), that `BorrowedOutput` and
`FirstErrorSink` are unused, the `Cargo.toml` examples, and 330 `unwrap`/`unwrapErr` uses.
- **User-facing:** `Cargo.toml` / `.package.version` in `--help`, the READMEs and the `TomlDocument` KDoc; `Ok(...)` in
  serialization KDoc; public `Encoding.Ml*`, `IntegerRadix.value()`, `Offset.Custom(Short)`, `ScalarKind : Comparable`,
  `TableLike.items()`/`key()`; `ReadFailure.Io`/`IsADirectory` on the `--json` wire.
- **Dead because of Rust:** `BorrowedOutput` is the only builder that refuses text, so with it gone the public
  `AllocationFailed` cannot happen and the `Boolean` returns of `pushStr`/`pushChar` mean nothing. The `FmtWrite`
  plumbing has a single writer that never fails.
- **Internal renames:** `Repr`, `RawString` accessors, `despan`, `Raw.asStr`/`len()`, `Token`/`Event` accessor
  functions, `into*`, the IndexMap vocabulary, `keyvalSep`, `parseRust*`, `Span.DEFAULT`, the duplicate
  `StringEncoding`, serialization's `TomlNode.Struct`/`Sequence`.
- **Tests:** `unwrap`/`unwrapErr`/`isOk`, the snapbox-named asserts, 13 `fun t(...)`, `assertTrue(x != null)` with `!!`,
  9 doctest ports that assert nothing, one duplicate test.
- **PARITY.md:** four errors (`:toml` at line 3, two test names that match nothing, a blank line that breaks a table,
  "(goal 2)").
- **Keep:** `RustDebug.kt`, `.dbg()` and `RustFloat.kt`, which exist to compare with upstream's snapshots and the float oracle.

**Owner ruling (2026-10-03 23:45):** `Ok` and `Err` stay; they are kern:result's names, not kotoml's.

**Open for the owner:** `.getOrNull()!!` in README examples; renaming `ReadFailure` cases, which changes `--json`; the
design questions (public `Span` counts UTF-8 bytes, `Value.String` shadows `kotlin.String`, a domain name for
`TableLike`); the order of the branches.

### 63. 2026-10-03 23:58 - Owner rulings on entry 62: `getOrElse { error(it) }` in examples; `ReadFailure` stays

**Decided by the owner:**
- The 14 README and KDoc examples (and their 2 mirrors in `ReadmeTest`) use `.getOrElse { error(it) }` instead of
  `.getOrNull()!!`. Done on `docs/get-or-else`, uncommitted; `./gradlew check` green.
- `ReadFailure`'s `NotFound`, `IsADirectory`, `PermissionDenied` and `Io` stay: plain English (`IsADirectory` is POSIX's
  `EISDIR` text) that only coincides with Rust's `io::ErrorKind`, and renaming would change `--json`.

### 64. 2026-10-04 05:19 - Entry 62's user-facing fixes and public API changes, on `fix/rust-public-surface`

**Got:** uncommitted, stacked on `docs/get-or-else`. Clean build green: core 1142, serialization 362, app 107.
- **Examples:** `Cargo.toml` / `.package.version` are now `config.toml` with `.servers[N]`, in `--help`, app's
  README, core's README and `TomlDocument`'s KDoc. Each app README line was run on the native binary.
- **KDoc:** `[TomlArray.fmt]` links `resetFormatting`; serialization's `Ok(...)` reads `Result.Success(...)`;
  `Item.assign` no longer cites Rust.
- **Public API:** `Encoding` / `TokenKind` `Ml*` are `Multiline*`; `IntegerRadix.base` replaces `value()`;
  `Offset.Custom(minutes: Int)`; `ScalarKind` is not `Comparable` (nothing ordered it).
- **Snapshots unchanged:** `ParserDebug` maps `Multiline` back to upstream's `Ml`.
- **PARITY.md:** the four errors, plus rows for each API change.

**My miss:** the whole-word rename also rewrote upstream's Debug text inside `LexerTest`'s expected strings; the
JVM tests caught it (3 failures) and those 26 literals went back to `Ml*`.

**Wire change, not raised before building:** `ParseErrorReason.InvalidString` / `MultilineKey` serialise
`encoding`, so `--json` now says `"encoding":"MultilineBasicString"` (measured on the binary).

### 65. 2026-10-04 05:24 - Completes entry 64: `TableLike.entries` and `keyOrNull(name)`

**Got:** entry 64 had left out `TableLike.items()` / `key(name)`, which the owner approved with the rest. Now
`val entries` and `keyOrNull(name)` on `TableLike`, `Table` and `InlineTable`; callers in core, serialization, app
and the tests follow the compiler; PARITY's rows use the new names. Clean build green: core 1142, serialization 362,
app 107. The interface's own name stays `TableLike` (a separate design question).

### 66. 2026-10-04 05:42 - Dead code from the Rust port removed, on `chore/dead-rust-code`

**Got:** uncommitted, stacked on `fix/rust-public-surface`. Clean build green: core 1142, serialization 362, app 107.
- **Text that could be refused:** `BorrowedOutput` was the only builder that refused text, so with it gone the public
  `ParseErrorReason.AllocationFailed` could never be reported. Both are gone, and `DecodedStringBuilder` has
  `append` / `appendCodePoint` returning `Unit` instead of `pushStr` / `pushChar` returning `Boolean`.
- **`FmtWrite`:** its only writer never failed. The writer functions take a `StringBuilder`; `FmtError`,
  `StringWriter` and `orBail` are gone.
- **Unused:** `FirstErrorSink`, `Raw.asBytes`, the no-op `decodeWhitespace`, `Event.newUnchecked`,
  `TomlInteger.of(ULong)`, the writer's `openArrayOfTablesHeader` / `closeArrayOfTablesHeader` / `openComment`,
  and the test helpers `fileExists` and `range`.
- **One test reshaped:** the ported `document_display_propagates_write_errors` fed `toString()` into its own failing
  writer, so it tested only that writer. It keeps its CRLF assertion as `a line inserted into a CRLF document ends
  with CRLF`; the failing-sink half is `TomlDocumentTest`'s existing `writeTo` test. PARITY maps the upstream test
  to both and records every removal.

### 67. 2026-10-04 06:28 - Entry 62's internal renames, on `refactor/internal-names`, judged by Kotlin's conventions

**Got:** four commits, each with a clean build green (core 1142, serialization 362, app 107):
- `e85745b` spelling: `Repr` is `Spelling`, `RawString` is `KeptText` (the approved `SourceText` already named the
  parse input; owner picked `KeptText`), `despan` is `resolveSpans`, a stored spelling is `written`.
- `1fcb5b0` parser: `Token` and `Event` are data classes with properties; `Raw.text` / `length`; `toList`,
  `next` / `peek`, `Span.EMPTY`.
- `1214e87` collections: `put`, `remove`, `retainAll`, `deepCopy`, `entries`; `into*` that empty their source are
  `moveTo*`.
- writer and numbers: `parseTomlFloat` / `parseTomlLong`, `compareByCodePoint`, `toShortestDecimalString`, spelled-out
  separators, and the writer uses `Encoding`.

**Owner correction (2026-10-04):** a name is not wrong because Rust uses it too; Kotlin's conventions (stdlib,
collections, kotlinx) decide. `KeyValuePairs.sortBy(comparator)` went back to `sortBy`. Dropped from the plan:
`TomlNode.Struct` (kotlinx says `beginStructure`), `Sequence`, `TomlImpl` (kotlinx has `JsonImpl`), `escapeDebug`.

**Tooling note:** `.span()` to `.span` could not be a text replace (other types keep `span()`, and test snapshot strings
hold upstream's field names). Renames went through compiler errors instead: change the declaration, rewrite exactly
the reported positions. That also rewrote two `List.first()` calls at cascade positions to `peek()`; caught by the
next compile and put back.

### 68. 2026-10-04 06:47 - Entry 62's test-helper pass, on `test/kotlin-helpers`

**Got:** five commits, each with a clean build green and test counts unchanged (core 1142, serialization 362, app 107):
- `05d07f8`: `getOrFail` / `errorOrFail` replace `unwrap` / `unwrapErr` in both modules; `isOk` is gone (`assertIs`).
- `bb486f3`: `assertSnapshot`, `assertDebugSnapshot`, `assertFileSnapshot` replace the snapbox names.
- `6783bcd`: kotlin.test's `assertNotNull` / `assertNull` / `assertIs` replace `assertTrue` on null and type checks;
  their contracts smart-cast, so a following `!!` became the value `assertNotNull` returns.
- `1673a41`: the 13 ported `t(...)` helpers and `tRecurse` are named for what they assert (`assertLexes`,
  `assertEvents`, `assertRefusedWith`, ...); `Repr` round trips are `Spelling` round trips; `producesDisplay` is
  `prints`.
- `d37d485`: nine ported doctests that asserted nothing now check what they build (all nine expectations held on
  the first run); the duplicate decor test checks `Decor()`.

**Found on the way:** `Result.Success` in a test without kern's import resolves to the stdlib's `kotlin.Result`;
three files needed the import added.

### 69. 2026-10-04 06:54 - Owner decisions on entry 62's design questions

**Decided by the owner:**
- **`TableLike`:** option A. `Table` becomes the sealed interface over `StandardTable` (today's `Table`) and
  `InlineTable`, as the stdlib names a supertype for the concept (`Collection`, `CharSequence`) and TOML calls both
  "tables". `Item.tableOrNull` returns either kind; `standardTableOrNull` is the `[section]` kind.
- **Public `Span`:** `String` indices instead of UTF-8 bytes, converted once when a `TomlParseError` is built;
  internal spans stay bytes. `InvalidUtf8`'s `validUpTo` / `errorLength` stay bytes. `--json` offsets change.
- **`Value.String` and siblings shadowing Kotlin's types:** fine, unchanged.
- **Getter-style functions:** become properties.

### 70. 2026-10-04 07:28 - Entry 69's decisions built, on `refactor/kotlin-api`

**Got:** four commits; a full `./gradlew clean build --rerun-tasks` is green (core 1146, serialization 362, app 107):
- `c9caaf9` getters as properties: `span`, `spelling`, `rawDecor`, `input`, `remaining`, `TokenKind.encoding`,
  `AppError.exitCode` and a few private ones. Functions that render, scan or mutate stay functions; the local
  `isPrefix()` stays one because Kotlin has no local getters.
- `566abcb` a parse error's `span` / `context` are `String` indices (converted once in `TomlSink`); invalid UTF-8
  gets an empty span where the valid text ends. Two new tests pin both. `render()` keeps upstream's caret count
  in UTF-8 bytes: counting characters would have changed copied upstream snapshots (`bom-not-at-start-01`).
  Measured on the native binary: `"é😀"` before the error moves `asdf` from byte 17 to index 14.
- `8846cd3` `Table` is the sealed interface over `StandardTable` and `InlineTable`; `tableOrNull` reads either
  kind, `standardTableOrNull` the `[section]` kind; `standardTableOf` / `toStandardTable`. PARITY was edited per
  column so upstream's own `Table` / `TableLike` names stay.
- `e748749` lines the renames pushed past 120 columns.

**Also fixed:** core's README still said `key(name)`, stale since `keyOrNull`.

**My miss:** one build died (owner: the laptop ran out of memory and the Gradle daemon was killed) and my command
committed anyway because it chained with `;`. The full rerun above is the evidence for that commit. Commit only
behind `&&` after the build.

### 71. 2026-10-04 07:39 - A sweep for sentinel values; the parser's four fixed on `fix/sentinels`

**Got:** 11 sentinels in main code; none reached a caller. `007d5c3`, clean build green (core 1146, serialization 362,
app 107), first errors unchanged across the 560 upstream snapshots and the toml-test invalid suite:
- `SourceText.slice` refuses a span outside its input (`require`) instead of returning `""`.
- `keySpan` returns `Span?`: a key without a spelling reports no position instead of a fake `0..0` (line 1,
  column 1). Not `checkNotNull`, so the parse still ends in its typed error.
- The root table's span stays `null`, as every other table without one, instead of `Span.EMPTY`.
- A value that failed to parse is `null` (`value`, `onScalar`, `parseValueItem`) and is skipped, instead of the
  placeholders `Integer(0)`, `NaN`, `Long.MAX_VALUE` and `Datetime(null, null, null)`. Checked first: every parse
  entry point returns `Err` once the sink holds an error. `Value.parse` treats a null without an error as a bug.

**Left alone, with the Kotlin reference:** `indexOf` returning -1 (as `String.indexOf`), `Long.MIN_VALUE` as a sort
key's lower bound, the null objects `IGNORE` / `DISCARD` / `RawDecor.EMPTY`, `Offset.Unknown` (RFC 3339's
`-00:00`), `Span(0, 0)` in the decoders (a real position), `seek(-1)`, jq's `Index(-1)`.

**Open:**
- Small local ones: `runStart = -1` (`DecodeString.kt`), `-1` as a map key (`ListPlan.kt`), `key = ""` before a map
  key is known (`NodeEncoder.kt`).
- `Datetime` (public) is three nullable fields, so `Datetime()` with no date and no time can be built. A sealed
  interface of the four TOML kinds would rule that out; a public API change, the owner's call.

### 72. 2026-10-04 07:44 - Completes entry 71's small local sentinels

**Got:** a second commit on `fix/sentinels`, clean build green (core 1146, serialization 362, app 107): the invalid run
of an unquoted key is a `Span?`, `ListPlan`'s "nothing kept before" is a `null` map key, and `MapEncoder`'s key is
`null` until known, read through a `checkNotNull`. Its `elementName` is only asked by `shouldEncodeElementDefault`,
which generated class serializers call and map serializers never do, so the old stale key could not surface.

**Still open:** the sealed `Datetime`, the owner's call.


### 73. 2026-10-04 07:53 - `Datetime` is a sealed interface of TOML's four kinds (owner: "sealed")

**Got:** `634cad6` on `refactor/sealed-datetime` (stacked on `fix/sentinels`); build green (core 1148, serialization
362, app 107):
- `Datetime.OffsetDateTime`, `LocalDateTime`, `LocalDate`, `LocalTime` replace the data class of three nullable
  parts. Nested, so they never clash with kotlinx-datetime's `LocalDate`. `date`, `time` and `offset` still read
  on every kind, narrowed to non-null on the kinds that have them; ordering, `toString` and `parse` unchanged.
- Serialization's decoding helper is generic over the subtype (`is D` instead of comparing kinds), so the four
  kotlinx-datetime conversions lost their `!!`. The app's and the test harness's tagged JSON use a `when`.
- One new test pins the kind each of the four forms parses to; core's README example and PARITY follow.

**Found:** a property declared on the sealed interface cannot be smart-cast (`datetime.time != null &&
datetime.time.second`), so the TOML 1.0 seconds check reads it once with `?.let`.

### 74. 2026-10-04 07:59 - Corrects entry 73: a `when` on the kind does smart-cast (owner)

**Got:** entry 73 said a sealed interface's property cannot be smart-cast and used `time?.let`. Only the property
read through the interface cannot; a `when (datetime)` narrows the value itself, and each kind's `time` is then a
non-null `Time`. The TOML 1.0 seconds check is now that `when`, amended into the commit (`5f6b6f3`); build green.

**My miss:** I stated the limit wider than it is.

### 75. 2026-10-04 10:09 - Deslop of the whole repository, module by module (owner: sequential)

**Got:** five commits on `chore/deslop`, each after a green build (core 1148, serialization 362, app 107):
- `295907f` docs: `.env.example` named kern; PARITY's sorting row argued in the old table names; one README wrap.
  A script checked every kotoml-side name the READMEs and PARITY cite against the code; app's README, which no
  test mirrors, was run on the native binary line by line.
- `a90641a` app: `\u` in a filter key read a sign as a hex digit (`\u-041` became U+FFBF; checked with a script);
  the jq bare-key rule lived twice (`isBareKeyChar`, `locate`); `SelectionSerializer.deserialize` and its JSON
  reader had no caller but their own test, klap never decodes; three KDoc slips.
- `e75a083` serialization: `decodeFromDocument` rebuilt `StandardTable.toInput()` and the merger rebuilt
  `Item.tableOrNull`.
- `c061010` core: the writer re-implemented the decoder's unquoted-key check (now `isUnquotedKeyChar`);
  `keySeparator` had no caller.
- core tests, serialization tests: nothing to change.

**Left alone, on purpose:** the ported parsers' size and nesting (`Parser.kt` 1144 lines, depth 8), which mirror
upstream for PARITY; the identical Linux and Apple `readStdinChunk` (a shared POSIX source set costs more than 4
lines); `leavesOutDefaults` (only a custom list serializer can set it, but one can); the long KDoc on grammar,
contracts and hazards.

**Open, the owner's call:** the published jars ship only kotoml's MIT `LICENSE` and the POM declares MIT, while
core is a port of toml-rs (MIT or Apache-2.0) whose notice MIT requires in copies. The Arch package already ships
both upstream licenses.

### 76. 2026-10-04 10:23 - Full review by 7 blind reviewers; every important finding reproduced

**Got:** 7 read-only reviewers (lexer/decoders, document building/errors, editable model, printing, serialization,
encodeInto, CLI/docs/build), about 45 findings. 33 of them reproduced by me: two throwaway JVM probe classes (deleted
after) and the native binary. All behaved exactly as reported.

**Highest:**
- encodeInto writes a wrong value when a field's default instance differs from its class's defaults
  (`server: Server = Server(port = 9090)` over `[server] host = "localhost"` stays port 8080 when read).
- Array-of-tables elements print in their old position order, so a reorder, insert or replace reads back reordered
  (shared with upstream; serialization works around it, core callers are unprotected).
- A dotted table stored as an array-of-tables element prints nothing.
- An empty slot keeps the kind it was handed out with, so an inline table can hold a standard table.
- UIntArray / ULongArray / UByteArray / UShortArray bypass the unsigned handling (4000000000u writes -294967296).
- encodeInto throws IllegalArgumentException on a hand-built invalid Date in the document.
- The native CLI crashes on any stat failure but ENOENT (`kotoml check file/x`).

Full triage in the session; nothing fixed yet.

### 77. 2026-10-04 15:03 - Entry 76's findings fixed on `fix/qa-round`; every reproduced finding re-run green

**Decided by the owner:** Date, Time and Offset.Custom validate with `require`; upstream bugs get fixed too (kotoml is
a superset); what the user provides persists and invalid TOML is a typed error at use (lone surrogates: kept in the
model and `toString()`, refused by `writeTo` with `TomlWriteError.NotUnicode` and by serialization with
`TomlEncodeError.NotUnicode`); syntax errors carry `context`.

**Got:** 6 fixer agents on disjoint files, then one gate. Commits `f38b183` (the round), `b2ce875` (encodeInto and a
default differing from its class's defaults, at any depth: the second run also encodes such a value without
defaults), `eb749f1` (PARITY and README), `e0963aa` (cleanup). Build green: core 1286, serialization 424, app 140
(from 1148 / 362 / 107). All 33 reproduced findings re-run: each fixed.

**Caught at the gate:** test names with a comma compile on the JVM but not on Kotlin/Native; the parser fixer's
stray-character check in underscored integers pre-empted upstream's `_` message for `_0b1` (now runs after the
underscore checks); one hand-traced caret line was off by a space; a serialization test built a Date the new
`require` refuses, and the `IllegalArgumentException` catch it covered became unreachable, so both went.

**Wire changes:** `JsonTooDeep` reports 81 (was 82); new `NotUnicode` (both), `TomlDecodeError.DiscriminatorConflict`,
`FilterExpected.IndexInRange`; `writeTo` returns `TomlWriteError`.

**Left open:**
- A float too small for `Float` reads as 0 without an error (overflow is refused); plausibly by design.
- Dotted keys inside nested inline tables can nest about 6,300 levels; a deep-stack risk on small threads. Not run.
- `{ a = [1, 2] , b = [1] }` spacing when a key follows the last inline entry; upstream's too.
- From the fixers: `Item.set(index, …)` stores an array-of-tables element without clearing `isDotted` (prints
  correctly); a `StandardTable.get` handle on a table later made inline writes into the old table; comment lines
  above a replaced dotted-key line, or above a table replaced by an empty item, are not carried; a comment above the
  last removed dotted key of an emptied map is lost; the exponent fix assumes native `toDoubleOrNull` handles a
  1M-digit mantissa (JVM checked).

### 78. 2026-10-04 15:31 - Corrects entry 77's validation: `of()` with a typed error, nothing throws (owner)

**Decided by the owner:** "private constructor and use `.of()`", replacing entry 77's `require`; and `getOrElse { … }`
over `getOrNull() ?: …`.

**Got:** `83125d7` (a failed document parse returns its error without resolving spans) and `07700b8`:
- `Date`, `Time`, `Offset.Custom` have private constructors (`@ConsistentCopyVisibility`, so `copy` is private too)
  and `of(…)` returning `Result<…, DatetimeParseError>`, the error a date-time in a file gets (`Range(field, min, max)`
  with its part; new `SecondBeforeFraction` for a fraction without a second).
- The parser builds through `of()`, so each range rule lives once; serialization's own year check folds into it.
- Build green: core 1288, serialization 424, app 140.

**Throws left in main code:** none on any input. Only internal can't-happen checks remain (two `checkNotNull`, the
`SourceText.slice` `require`, now unreachable from a failed parse too), and the app's never-called
`SelectionSerializer.deserialize`.

### 79. 2026-10-04 20:44 - Entry 77's leftovers reproduced on `dev` (`8b456ed`)

**Got:** throwaway probe tests in core and serialization (JVM; linuxX64 for the mantissa), and the native release
binary for deep nesting. Probes deleted after.
- **Reproduced:**
  - Deep dotted keys in nested inline tables: `StackOverflowError` thrown on the JVM test thread from
    `resolveSpans` (`Value.kt:361` / `InlineTable.kt:182` / `Item.kt:373` recursion). 20 inline levels of 78-part
    keys (1560 levels, 3.2 KB) parse; 40 levels (3120, 6.5 KB) throw. The native binary's `check` and `get` pass at
    78 x 78 (12.6 KB) on its 8 MB main stack. So "nothing throws on any input" (entry 78) is false on the JVM.
  - Comments lost when replacing: `# about a` above `a.b = 1` goes when `doc["a"] = 5` (or `put`); both comments of
    a two-line dotted key go. `doc["t"].assign(Item())` and `put("t", Item())` remove `[t]` and its `# about t`,
    while removing keeps it (entry 20, case 3); replacing `[t]` with a value keeps it.
  - Emptied map: `encodeInto` with an empty `Map` over `# about b\nm.b = 1` prints `m = {}` and drops the comment.
    Core's own `remove` of the last dotted key keeps it, as does the `[m]` header form.
  - `Item.set(0, dottedTable)` on an array of tables: `isDotted` stays true; prints `[[a]]\ny = 2` correctly.
  - Stale handle: after `doc["t"].makeValue()`, writes through the old `StandardTable` land in a detached table.
  - A float below `Float`'s range: `1e-50` and `1e-46` decode to `0.0`, `-1e-50` to `-0.0`; `1e50` is `OutOfRange`.
- **Not reproduced:**
  - 1M-digit mantissa on native: `1` + 1M zeros `e-1000000` and `0.` + 999999 zeros + `1e1000000` read `1.0` on
    linuxX64 as on the JVM.
  - `{ a = [1, 2] , b = [1] }` plus `c = 3` prints `{ a = [1, 2] , b = [1] , c = 3 }`, the written ` ,` style
    carried on. NOT YET CLEAR what entry 77 meant as wrong here.
- **New:** `makeValue()` on `[t]\nx = 1` prints `t= { x = 1 }`, no space before `=`.

### 80. 2026-10-04 20:52 - RESOLVES entry 79's deep nesting: one limit for brackets and dots (owner)

**Got:** `8ffb1cd` on `dev`. `ParseContext.nesting` counts each enclosing array or inline table and each dot of
the keys leading to a value. A key reaching the limit is `KeyTooDeep` at the key, and a bracket past it is
`RecursionLimit` at the bracket. Each kind alone keeps upstream's boundary (80 brackets read, 80 dots refused).
78 x 78 is now refused, not a `StackOverflowError`. Five tests in `NestingLimitTest`; a PARITY row. Clean build
green: core 1298, serialization 424, app 140; toml-test 1.0 and 1.1 all pass.

**Not checked:** whether `encode` can now print an inline table that `decode` refuses (the same class as entry 44's
gap); the encoder suite passes.

### 81. 2026-10-04 21:09 - RESOLVES entry 79's lost comments on replace and the emptied map (owner rule)

**Rule (owner):** a comment is dropped only when it is inside the structure whose key is removed or changed.

**Got:** `1e2931d` on `dev`. A `KeyValue` now knows its `KeyValuePairs` (`pairs`), and the pairs know their table's
removal routine (`keepLinesOfRemoved`). `KeyValue.takeLinesAbove(next)`, used by `put` and `Item.assign`:
- an empty `next` keeps what `remove` keeps;
- a header keeps its lines (as before);
- dotted-key lines: those above the first line go above the new item (through the key's prefix when it is a header,
  so the header's blank line stays above them), those above later lines stay in place as kept lines.
`encodeInto` emptying a dotted map follows through core. 8 tests in `ReplacementCommentScopeTest`, 1 in
`EncodeIntoFixesTest`, a PARITY row. Clean build green: core 1314, serialization 426, app 140; toml-test passes.

**Known quirk:** a key emptied and written again prints above the comment it had, since emptying hands that comment to
the next line, as `remove` does, while the slot keeps its place.

**My miss:** two test names with a comma again; the native compile caught them (entry 77's gotcha).

### 82. 2026-10-04 21:31 - Comments are kept on set and remove; supersedes entry 20's case 2 (owner)

**Decided by the owner:** comments and formatting are kept almost always. They are dropped only between the items of
a list (array of tables): the comment above the first `[[x]]` and the trailing comment after the last stay,
those between elements go. Measured: `remove`, replacing with a value and replacing with a new array of tables
already do that. A key's same-line comment stays on `set` and on `remove`.

**Got:** `2236040` on `dev`.
- `set` / `put` / `assign` of a value over a value: the new value takes the old one's decor on each side it leaves
  at the default (`RawDecor.fillDefaultsFrom`), as `encodeInto` and `TomlArray.replace` already did. A value with
  its own `Decor` keeps it. Side effect: the value's spacing survives too (`=   2`); three tests changed.
- `remove` of a `key = value` line keeps its comment as a line of its own at the line's indentation
  (`VisualValue.commentOnLine`). Reverses entry 20 case 2; `RemovalPropertyTest`'s oracle now keeps the comment;
  upstream's `test_remove_value` keeps `# please` (PARITY row).
- Clean build green before the last two tests: core 1314, serialization 426, app 140, toml-test all pass; with them
  core 1318 on JVM and linuxX64.

**Left as is, NOT YET ASKED:** a same-line comment on a removed array element or inline-table entry still goes
(entry 20 case 10), as does a removed header's `[t] # comment`. Array elements are list items under the owner's
rule; a header line's comment is not covered by it.

### 83. 2026-10-04 21:37 - Corrects entry 82: removing a key drops the comment on its line (owner)

**Decided by the owner:** removing a key with a same-line comment drops the comment too; entry 20 case 2 stands.
Only `set` / `put` / `assign` keep it.

**Got:** the removal half of `2236040` is reverted (`StandardTable`, `KeptLines`, the removal tests and the
property oracle restored from `1e2931d`), and the commit amended to `41514af`, so `dev` never
carries the reversed rule. Clean build green: core 1318, serialization 426, app 140; toml-test passes.

**Still open:** the empty-then-rewrite order (entry 81), and header-line comments on replace (`[t] # about t` set
to a value drops it).

### 84. 2026-10-04 22:52 - An Item is never empty; indexing returns a typed Result (owner)

**Decided by the owner:** no `Item::None`. Three ways to delete a key (`remove`, writing `Item()`, `assign(Item())`)
and a hidden entry that kept the key's place were the cost. `get`/`set` stay operators; `doc["a"]["b"]` is
`Result<Item, TomlEditError>` and stops at the first missing key with `NotFound(key)`; writing below a missing key
creates nothing (write each level); reads end with `getOrNull()` / `getOrElse`; tests use `getOrFail()`.

**Got:** `2aaf242` and `d16ba39` on `dev`.
- `Item()`, `Content.None`, `isMissing`, the detached placeholders and every hidden-entry filter are gone.
  `ItemResult.kt` holds `get`/`set` on the `Result`. `TomlEditError.NotFound(key)` is new on the wire.
- `Table.putItem` returns `Item?`: with no empty item it cannot fail.
- Five ported tests that wrote through missing keys now write each level, same output; PARITY records it.
  11 empty-item tests deleted; `IndexingTest` (9) added.
- About 200 test reads got `.getOrFail()` at the compiler-reported positions by a script; it misplaced 4 on
  nullable collection getters, fixed by hand.
- Cleanup pass: unused imports, a false "remove is the only way to delete" (retainAll/removeAll/clear also do).
- Clean build green: core 1314, serialization 426, app 140; toml-test 1.0 and 1.1 pass. serialization and app
  needed no change.

**Left alone:** `KeyValue.pairs` stays nullable, since `EditParser.kt:322` builds a throwaway `KeyValue`.
`InlineTable`, `TomlArray` and `ArrayOfTables` keep nullable collection-style `get`.

### 85. 2026-10-04 23:13 - A builder DSL: buildTomlDocument and doc.edit (owner)

**Decided by the owner:** one DSL for building and editing; `"key" to value`; `table(name)` creates a missing table;
`edit` all or nothing, accepting that an `Item` taken before a successful edit is detached afterwards.

**Got:** `68ec473` on `dev`. `TomlDsl.kt`: `@TomlDsl`, `KeyedScope` (the `to` overloads once),
`TableScope`, `InlineTableScope`, `ArrayScope`, `ArrayOfTablesScope`; `TomlEditError.TypeMismatch(key, expected,
found)`; `StandardTable.takeStateOf` moves the edited copy's state into the live root. A failing block stops through a
private `EditAborted` caught only at the two entry points. A created table that holds only tables is implicit, so
`[tool.poetry]` prints without an empty `[tool]`; an empty one prints its header. 13 tests in `TomlDslTest` and a
README mirror; all passed on the first run against expectations written before running. Clean build green: core
1342, serialization 426, app 140; toml-test passes.

**Measured:** `"k" to listOf(1)` is a compile error with the deprecation message; `mapOf("a" to 1)` inside a block is
also a compile error (the builder's `to` returns Unit), never a silent wrong value.

**Out of scope:** editing an existing `[[x]]` element by index from the DSL (`table {}` only appends);
standalone `buildStandardTable` / `buildTomlArray`.

### 86. 2026-10-04 23:31 - RESOLVES entry 79's open bugs and the tiny-float ruling (owner: refuse)

**Got:** `0f863ac` on `dev`. Clean build green: core 1346, serialization 430, app 140; toml-test passes.
- `makeValue` calls `KeyValue.prepareFor`, so `[t]` becomes `t = { x = 1 }`. Two ported upstream tests
  (`table_into_inline`, `array_of_tables_to_array`) and one kotoml test pinned `table= {`; changed, PARITY row.
- `makeValue` converts a copy: a `StandardTable` taken earlier keeps its content and is detached, as after `remove`.
  Writes through it reaching the inline table would have let a table into an inline table. The test that pinned a
  slot taken before `makeValue` staying attached is deleted.
- `Item.assign` into an array-of-tables slot clears `isDotted`. The rule moved from `ArrayOfTables.slotOf`
  (deleted) into `Item.Content.placedIn`, used by `inArrayOfTables` and `assign`.
- serialization `decodeFloat`: a non-zero value that rounds to `0f` is `OutOfRange`; `0.0`, `-0.0` and
  `1.4e-45` (`Float.MIN_VALUE`) still read.

### 87. 2026-10-04 23:32 - Corrects entry 86's commit: amended to `8740463`

**Got:** wrapping a 121-column line exposed two comments in the ported `makeValue` tests explaining `table=` as
upstream's unformatted key, which described the fixed bug; both deleted and the commit amended. `EditTest` green.

### 88. 2026-10-05 01:15 - Three-reviewer QA pass against GOALS.md, and the round that followed

**Got:** three read-only reviewers (correctness, robustness, API and docs) audited the whole codebase against
GOALS.md, each in its own worktree. Their worktrees started at "Initial commit", not at `dev`; each reset itself
after one noticed (lesson: check a worktree's HEAD before trusting a subagent's review). 24 items went to `todo.md`;
the owner ruled deliberate misuse out of scope (GOALS.md, memory `no-deliberate-misuse`) and enforced D2, D3, D4, D6.

**Fixed, each with tests, gate green** (core 1404, serialization 450, app 150, toml-test 1.0 and 1.1):
`b720877` array-of-tables header spelling; `9785a53` CLI encode depth and lone surrogates; `f9b8f09` encodeInto
numbers; `f499638` multi-line append layout; `43555b1` inline closing space; `4d2de15` kept comment vs new table;
`e8fbb13` reorder blank lines (owner flipped two approved expectations); `752c476` BOM column (two upstream
snapshots regenerated); `528309c` EINTR; `ac82508` arrays store copies; `030ed99`/`988cf20` TooDeep in
serialization and `writeTo`; `1d43124` lone surrogate parse error; `27a3ec7` empty tables keep their key;
`4877b66`, `79698fc`, `f2a0996` docs and wire-schema pinning.

**My misses:** two test expectations miscounted (CLI depth, trailing comma), a KDoc example set that three probes
contradicted, an unmeasured U+FFFD claim written into PARITY, and `serializer<Enum>()` that only the native build
showed does not work there.

**Open:** todo 18 (PARITY rows without a test), candidates 21-25.

### 89. 2026-10-05 02:19 - Todo 25: a depth limit enforced when storing

**Wanted:** the owner's ruling "add a limit and if we reach that depth we error", enforced from the root: a store
that would nest past `NESTING_LIMIT` fails with a typed error, so no built tree can overflow printing or copying.

**Got:** parent links (content learns its slot, slots their container, stale links detected by identity) and one
iterative walker (`Nesting.kt`) that `writeTo` and every store share. `TomlEditError.TooDeep(keyPath)`; every
storing call, the `…Of` builders and the inline conversions return a `Result`. Three things the compiler did not
show:
- statement-form stores compile silently and drop the new result. Grepping for them found the CLI's tagged JSON
  reader and `Output` would have silently lost content, and `Item.set` discarded its table's error;
- the reader's own copy of the depth rule became redundant once stores enforce it, so it went;
- my first verdict claimed no tree could grow without end. Public `isDotted` broke that: dotted tables added under
  a full header and then turned into headers stack forever. A dotted table now counts the header parts above it,
  which is the parser's rule anyway. Caught while writing the verdict, with a test that fails under the old rule.

**Severity:** detour, about an hour of compiler-driven call-site updates (≈80 test sites, scripted, then by hand
where the script unwrapped at the wrong place).

**Doing:** scalar `TomlArray.add` keeps returning `Unit`, since it cannot nest (a deviation from the plan, which
listed every `add`). The serialization writer unwraps with an internal check: its encoder refuses models one level
short of the limit, so a store there cannot fail.

### 90. 2026-10-05 03:12 - Goal 3's property test over all edits found six bugs at once

**Wanted:** close goal 3's gap: random sequences of public edits over every valid toml-test file and hand-written
documents, each step's output read back and compared as data (`toTagged`).

**Got:** 210 failures on the first run, from six causes:
- an emptied array of tables printed nothing, so its key vanished (owner: print `key = []`, checked live with
  `tomllib`; `x = []` followed by `[[x]]` is invalid, so the empty form must only print while it is empty);
- a value copied out of a multi-line array kept its newline prefix (`key =\n value`), and a value with a same-line
  comment kept it inside brackets;
- three print-order bugs in `Encode.kt`: an element's copied descendants sorted ahead of its `[[header]]`, a new
  table with nothing to anchor fell to position 0, and anchors were copied at walk time while a later pass reshuffled
  slots (ties included);
- a dotted inline table on table lines (public `isDotted`) kept the lines above a removed key with the brace rule,
  leaving a lone `\r` under CRLF.

**Severity:** detour; the cut-down needed a part dump of the print order twice.

**Doing:** fixed all six; `EditOutputTest` pins five from minimal reproductions (each fails on the old code), and the
property test replays the seeds behind the other two. Two hand-built "reproductions" passed on the old code and were
dropped rather than kept as tests that pin nothing. Filed todo 26: nested inline tables made dotted drop the comment
above them, which reads back fine, so a data check cannot see it (goal 4).

### 91. 2026-10-05 03:40 - Todo 26 was wider than filed: one inline table made dotted already lost its comment

**Wanted:** fix todo 26 (nested inline tables made dotted drop the comment above them).

**Got:** a probe without any edit after the flip showed the single-level case fails too: a `key = value` line takes
its leading text from its last key, and an inline table's keys hold the spacing inside its braces (`" "`), so the
comment on the holding key never printed and a stray space did. Fixed at print time (`VisualValue.lineStart`), so
it holds however the table became dotted; removal and replacement use the same conversion
(`linesAboveOnTableLines`).

**Severity:** paper cut once probed; the todo's framing ("nested", "next edit") came from the cut-down in entry 90
and was narrower than the bug.

**Doing:** the TOML 1.1 multi-line case with comments between entries is filed as todo 27 rather than widened into
this fix.

### 92. 2026-10-05 10:40 - Goal 4's comment property test: header comments had nowhere to go

**Wanted:** a property for goal 4: comment-keeping edits (adds, value-for-value replacements, value removals, sorts,
`isDotted`/`isImplicit`) lose no comment but a removed value's own.

**Got:** 184 failures at first, from two causes: an inline table made dotted dropped the comment after its `}`, and a
header that stopped printing (made dotted, or hidden as implicit) dropped the comments above it and on its line.
Owner chose "follow the content": above the first dotted line (the header comment ends it, or takes its own line when
the line has a comment), and where the header stood when hidden or when the dotted table has only sub-tables.

**My misses on the way:** my "a dotted table's lines print with its parent" rule also caught array-of-tables elements
and the root, which `EditPropertyTest` caught as data loss; an empty dotted table's comments printed twice; blank
lines above a never-real header printed (`VisitExampleTest`). TOML 1.1 multi-line inline tables (todo 27) also
needed removal to stay in the braces' shape rather than use the dotted-key rule.

**Doing:** fixed and pinned (`HeaderCommentsTest`, failing on the old code); todo 27 closed. Heavy runs still find a
long tail after repeated flips and nested dotted inline tables: todo 28, not chased now.

### 93. 2026-10-05 - Corrects entry 92 and my explanation of the doubled "# about b"

**Wanted:** explain to the owner why a second `# about b` appeared in a heavy-run case.

**Got:** I first explained it as a comment living inside the dotted table `t`. Replaying the seed step by step showed
otherwise: removing `b` moved its comment onto `t`'s key, correctly, and my printer change from `87a0439` printed it
twice, once as `t`'s header comments and once as the line's own key's lines, since an emptied table's line sits under
its own key. The same double count I had fixed for inline tables, missed in its twin.

**Severity:** detour; a confident wrong explanation reached the owner before the replay.

**Doing:** fixed and pinned in `HeaderCommentsTest`; also `t= {}` now takes the default spacing. Owner ruled that an
`isDotted` change gives a table a new layout (comments may go, but for the lines above its key), for inline and
standard tables; the test encodes it, nested keys' lines above are now carried, and a heavy run is down to one case
(todo 28). Lesson: replay before explaining a property-test failure.

### 94. 2026-10-05 - Three QA agents wrote 330 comment scenarios; 17 firm ones failed

**Wanted:** an independent spec of comment behaviour: three agents, each with the rules and the API but not the code
or tests, wrote input, edit and expected output; a generator (`qa/tools/gen.py`) turns them into one test.

**Got:** 262 of 330 matched at once, none threw or printed invalid TOML. Of 17 firm differences, 7 were kotoml bugs
(array insert/append/remove layout, a CRLF closing line, an emptied `[[x]]`'s comment, `makeValue` on a dotted
table), 4 were rulings the owner then made (Q1 to Q4, two overturning pins from earlier sessions), and the rest were
expectations to correct or open questions. Two agents found the emptied-array placement independently.

**Severity:** detour; my report viewer read CRLF files in text mode and hid the very difference one case was about.

**Doing:** fixed all seven; the property tests caught one of my fixes (first-element removal deleting a comment). 45
open scenarios still differ and wait for owner rulings, grouped.

### 95. 2026-10-05 - Owner rulings Q1 to Q4 on the open scenarios; my habit of running only core bit again

**Got:** owner chose: an emptied array of tables' comment moves with its new line (Q1), a new header's blank line
goes above its comment (Q2), content added to a comment-only file goes below the comments (Q3), a copy takes only
the comments inside it (Q4). Q3: the parser now keeps such a file's text as the document's head. Q4 needed a way to
tell text read from a file from text a caller gave (`KeptText` read state) and then a narrower scope: only copies
within the same document, not reorders inside an array (the `encodeInto` merge) or copies into another document
(the CLI's `get`).

**My miss:** I iterated on core's JVM tests only; the full build showed 8 serialization and app failures from Q4.
The property tests also caught a head without a final newline swallowing the next key, and the BOM sharing the
head.

**Doing:** fixed; scenarios settled with their rulings; todo 29 filed for copied tables printing at their source's
position. Lesson: run every module's tests before calling a behaviour change done.

### 96. 2026-10-05 - Owner rulings Q5 to Q8; layout changes drop comments in the model, not at print time

**Got:** Q5 (a new layout keeps the lines above the key and each value's same-line comment, drops the rest), Q6
(makeValue keeps value comments in a multi-line inline table in TOML 1.1, else one line with the header comment
after `}`; documents now remember the version they were read as), Q7 (a table replacing a table keeps its header
comment), Q8 (new elements below a closing comment line). Q5 overturned the earlier "follow the content" choice, so
seven `HeaderCommentsTest` pins and seven firm scenarios changed with it.

**Doing:** the `isDotted` setters now drop the comments for the new layout in the model; reading and copying set the
flag through internal setters with no side effects. The property tests caught two over-reaches on the way: dropping
lines kept from removed keys, and dropping the comment above an interleaved dotted line (the Q2 run rule applies).
S-075, the owner's own earlier example, now prints `t.u.k = 1 # one`: Q5 keeps `# one` and drops `# open u`, unlike
the output the owner called correct before; flagged in the report.

### 97. 2026-10-05 - Owner rulings Q9 to Q11 kept today's behaviour; one bug surfaced while settling

**Got:** owner chose today's behaviour for removing elements one by one (each keeps the lines above its header),
appending before an end-of-file comment, and a comment after an empty table belonging to what follows. Settling
S-042 showed a new element of an emptied array printed above the comment kept from the removed one; the printer now
anchors elements made by hand after the lines kept from the array's removed elements.

**Doing:** 263 scenarios firm and passing, 65 open ones pass (kotoml matches the agent's guess), 2 open differ (todo
29, copied table order). S-075's question to the owner (Q5 against their earlier example) is unanswered; Q5 stands.

### 98. 2026-10-05 - The owner stepped through the last 65 open scenarios; two rule changes

**Got:** the owner confirmed 63 as kotoml prints them and changed two things: sorting keeps blank lines and the
comment block heading a file in place (S-046, S-050), and the header that opened a file gets a blank line once
key/value lines print above it (S-014). The owner asked to see before and after TOML for every case, not one
example per theme; showing all of them made each batch quick to confirm.

**My miss:** my first S-014 fix gave a file's first header default spacing in the parser, which also added blank
lines when such a header moved (reversed arrays of tables, copies into other documents) and doubled a blank line
under a caller-set document head. Narrowed to a parse-time mark and a print-time rule for the first header below
root key/value lines.

**Doing:** 328 of 330 scenarios firm and passing; S-056 and S-057 stay open for todo 29.

### 99. 2026-10-05 - Todo 29 fixed; its heavy run found an older bug that also closed todo 28

**Got:** copies stored elsewhere in their document now print after the existing tables with default header
spacing (S-056, S-057; all 330 scenarios firm). A rule for "a header that now prints first drops its read blank
line" broke `RemovalPropertyTest`'s byte-exact removal and pinned EditTest ordering, so it was dropped. The heavy
comment sweep then showed 32 losses: a nested inline table made dotted inside braces lost the comment ending the line
before it, stored on its key, because inline-table lines start with the last key's spacing. Masked until Q5 removed
the comment that used to print in that spot. Fixed with the line-start rule the table-lines case already had; it also
fixed todo 28's last case.

**Lesson:** rerun the heavy property sweeps after every rule change, not only the default sizes.
