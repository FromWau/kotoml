# Open work

Numbers are never reused. A closed item keeps its place in **Done** with a verdict: what was decided or done, how
it was proved, and the date. Items come from the three-reviewer QA pass of 2026-10-05 against `GOALS.md`, each
reproduced on `dev` at `2781090` before it was filed.

Every defect, ruling and docs item from that pass is closed; see **Done**.

## Candidate work

- [ ] **22. `TomlType.Missing` is a public kind nothing has.** No `Item` or `Value` has it since `Item::None` went;
  only serialization reports it for an absent field. Goal 10.
- [ ] **23. `encodeInto`'s list merge is quadratic.** 2000 tables: 877 ms; 10 000 integers: 1.4 s. Should 5.
- [ ] **24. Exit codes are barely tested.** Only `FilterTest` asserts one; nothing pins `check --json` output or exits
  0 and 1. Goal 15.
- [ ] **25. Printing and copying recurse once per nesting level.** `Encode.kt`, `Value.copy`,
  `StandardTable.copy`. A document built through the API thousands of levels deep overflows the stack in `toString()`,
  and, since arrays store copies (item 11), already while it is built. That is the hand-built case the owner ruled
  deliberate misuse; `writeTo` refuses anything past the limit with `TooDeep`. Making both iterative would remove the
  overflow for good. Goal 5.

## Not in scope (owner, 2026-10-05)

Deliberate misuse is not a defect: the goals cover what a caller can do by accident in normal use. Not filed:
an `EditAborted` swallowed by the caller's own `catch` inside a builder block; a caller's own exception thrown in
a block (it propagates and the document is left as it was); an array added into itself; `/dev/zero` or more than
2 GiB on stdin; arbitrary text written into `Decor`, `trailing` or a key's decor.

## Done

- [x] **21. `InvalidUtf8` speaks Rust and bytes.** `validUpTo` / `errorLength` are byte offsets from Rust's
  `Utf8Error`; the message "invalid utf-8 sequence of 1 bytes from index 4" is Rust's. Goal 10, Should 1.
  **Verdict 2026-10-05:** fixed (owner). `InvalidUtf8(byteOffset)`, message "bytes that are not UTF-8"; the position
  says where in characters. The compliance harness no longer strips a newline after Rust's `Utf8Error`, and the nine
  UTF-8 snapshot copies were regenerated from the parser. Wire change; `WireSchemaTest` updated.

- [x] **18. `PARITY.md` test references.** `:309` cites a nanosecond-without-second test that no longer exists (the
  case cannot be built since `Time.of`); `:570` cites a drifted name. 79 of 85 intended-difference rows name no
  test.
  **Progress 2026-10-05:** both dangling references fixed (`:309` is now n/a with its reason, `:570` the current name);
  a script confirms all 455 test citations in PARITY.md resolve. Still open: the intended-difference rows that name no
  test.
  **Verdict 2026-10-05:** not wanted. The owner deleted `PARITY.md`: kotoml has passed upstream and changes its
  behaviour on purpose, so a row per difference only held it back. Goal 11 is retired.

- [x] **20. KDoc for the `ParseErrorReason` objects.** About 25 public objects have none. Should 4.
  **Verdict 2026-10-05:** fixed: one-line KDoc on all 27 reasons, each example checked against what the parser reports
  (three that did not hold were dropped).

- [x] **19. Pin every error wire name.** Core's `ErrorJsonTest` pins 10 of 85 `@SerialName`s, serialization's 7 of
  18; a rename fails no test. One table-driven test per sealed hierarchy. Goal 8.
  **Verdict 2026-10-05:** fixed. `WireSchemaTest` in core, serialization and app derives each error hierarchy's wire
  schema from its serializer descriptor (every subtype's serial name and fields, every enum entry) and compares it with
  the expected text; 30 types. A rename of `NotFound` failed it (mutation check, reverted). Goal 8's check updated.

- [x] **17. `TomlEditError`'s header is stale.** `core/.../TomlEditError.kt:7` says "refused by panicking";
  `NotFound`, `TypeMismatch` and `NotAValue` are not panics upstream.
  **Verdict 2026-10-05:** fixed: the header now says why an index, edit or builder block failed, and that upstream
  panics on most of these.

- [x] **16. `Datetime`'s KDoc example does not compile.** `core/.../Datetime.kt:15` calls the private `Date(...)`;
  use `Date.of(...)`.
  **Verdict 2026-10-05:** fixed: the example uses `Date.of(…).getOrElse { error(it) }`, mirrored by a test in
  `DatetimeParseTest`.

- [x] **15. `GOALS.md` wording.** State the deliberate-misuse rule; goal 3 excludes raw formatting text
  (`Decor`, `trailing`, key decor) the caller writes; goal 6 states the real bound (a header path adds its own
  levels); goal 8: `span` and `position` are nullable; goals 13 and 15: `ReadFailureTest` (native) and
  `PermissionTest` (JVM) are platform tests.
  **Verdict 2026-10-05:** done in `1f6d6f4`, before the fixes: the misuse rule, goal 3's raw formatting text, goal 6's
  bound, goal 8's optional span and position, and goals 13 and 15's platform tests. Goals 6 and 9 were updated again
  with items 11 and 12.

- [x] **14. An empty dotted or implicit table still prints.** Removing `a.b` from `a = { a.b = 1 }`, emptying an
  implicit table, or an empty dotted inline table makes the key vanish from the text while the model keeps it.
  Print it as `key = {}` or its header. Goal 3.
  **Verdict 2026-10-05:** fixed. A dotted table left holding nothing prints its key as `{}`, below the comments kept
  from its keys; an implicit table left holding nothing prints its header (hidden only while tables under it print).
  Upstream's `test_remove_last_value_from_implicit` now expects `[a]`; PARITY row, `isImplicit` KDoc, three tests in
  `RemovalKeepsCommentsTest`; core, serialization and app JVM green.

- [x] **13. `parse(String)` refuses a lone surrogate.** Today it becomes `?` on the JVM and U+FFFD on Kotlin/Native,
  so the value changes silently and differs by platform; `PARITY.md:727` and `TomlSource.kt:14` claim U+FFFD.
  Return a typed parse error. Goals 2, 13.
  **Verdict 2026-10-05:** fixed. `TomlDocument.parse`, `Value.parse`, `Key.parse` and `Key.parsePath` refuse a `String`
  holding an unpaired surrogate with `TomlParseError.Reason.NotUnicode` at its line and column. PARITY and the
  `TomlSource` KDoc corrected; four tests in `ParseFixesTest`; core, serialization and app JVM green.

- [x] **12. A typed error past the nesting limit when encoding or storing.** A recursive model 100 deep encodes to
  text kotoml refuses (`KeyTooDeep`); thousands deep throws `StackOverflowError` (`NodeEncoder` catches
  `Exception`). Return a typed `TooDeep` from serialization and from storing through the API. Goals 3, 5, 6, 14.
  **Verdict 2026-10-05:** fixed. Serialization refuses classes, lists and maps nested to `NESTING_LIMIT` with
  `TomlEncodeError.TooDeep` (one level less than the parser reads keeps every written header, array and inline table
  within it), so the encoder never recurses past that. For the API the owner chose a check when written: `writeTo`
  measures depth with the parser's rule, without recursion, and returns `TomlWriteError.TooDeep(keyPath)`. Tests:
  serialization `DepthTest` (4), core `WriteDepthTest` (5). Printing and copying stay recursive: item 25.

- [x] **11. Arrays and arrays of tables store a copy.** `TomlArray.add` / `add(index)` / `set` / `replace`,
  `ArrayOfTables.add` / `replace` store the object as is, so one `Value` added to two arrays changes both. Store a
  copy, as the table setters and the DSL do. Removes the self-containing array as a side effect. Goal 9.
  **Verdict 2026-10-05:** fixed. `add`, `add(index)`, `set`, `replace` and the factories store copies; the parser and
  internal moves use an internal `append` that stores what they own. The builder fills a nested array or table before
  adding it, and the merger works on the array's stored values after placing them. PARITY and goal 9 now say what
  copies and what wraps. Four tests in `SlotSemanticsTest`; core, serialization and app JVM green.

- [x] **10. CLI stdin treats `EINTR` as an I/O error instead of retrying.** `app/.../Stdin.native.kt`. Goal 15.
  **Verdict 2026-10-05:** fixed. A read that returns -1 with `EINTR` is retried. Compiled for linuxX64 and
  mingwX64; not unit-tested, since a test cannot reliably interrupt the read with a signal.

- [x] **9. A parse error after a byte order mark is one column off.** `"﻿a = = 1"` reports column 6; the
  visible column is 5. Should 1.
  **Verdict 2026-10-05:** fixed. The first line's columns start after a leading byte order mark, and `render` shows
  that line without it. Two upstream snapshot copies regenerated from the parser (`bom-not-at-start-02`, `-03`), a
  PARITY row, a test in `ParseFixesTest`; core JVM green.

- [x] **8. After an `encodeInto` reorder of an array of tables the blank lines shift.** The text starts with a
  blank line and the one between two elements is gone. Goal 4.
  **Verdict 2026-10-05:** fixed (owner chose blank lines that stay in place). A moved table keeps its comment lines
  and takes the blank lines above the slot it lands in (`keepBlankLinesInPlace`). Two owner-approved expectations
  changed (`ListMergeTest`, `ListLayoutTest`), two tests added in `EncodeIntoFixesTest`, README updated;
  serialization JVM green.

- [x] **7. A comment kept from a removed table prints below a table added later.** `# about a\n[a]\nx=1\n`, remove
  `a`, add `[c]`: `[c]\nz = 1\n# about a`. Goal 4.
  **Verdict 2026-10-05:** fixed. A table made by hand follows the last table walked or the lines kept from its
  parent's removed tables, whichever stands later (`DocumentParts.add`). A test in `RemovalKeepsCommentsTest`; core
  and serialization JVM green.

- [x] **6. Adding to an inline table puts a space before the comma.** `{ x = 1 }` plus `y` prints
  `{ x = 1 , y = 2 }`. Goal 4.
  **Verdict 2026-10-05:** fixed. On one line a new last entry takes over the space before `}`, as the one-line sort
  already treats that spacing as the position's; every insert path goes through `InlineTable.putEntry`. Two tests in
  `PrintingFixesTest`; core and serialization JVM green.

- [x] **5. Appending to a multi-line array lands on the previous element's line.** `core/.../TomlArray.kt:93`
  `a = [\n  1, # one\n]` plus `add(3)` prints `1, 3, # one`. `encodeInto` already lays inserted elements out
  (`layOutInserted`); core and the DSL do not. Goal 4.
  **Verdict 2026-10-05:** fixed. `TomlArray.add` gives a value without a prefix of its own a line of its own in a
  multi-line array, below the last element and its comment, keeping a trailing comma. The rule moved there from
  `encodeInto`'s merger, which keeps only the middle-insert case. Three tests in `PrintingFixesTest`; core and
  serialization JVM green.

- [x] **4. `encodeInto` rewrites a number that reads the same.** `serialization/.../DocumentMerger.kt:428`
  `d = 1` into a `Double` field becomes `d = 1.0`; `f = 0.1000000001` into a `Float` becomes `f = 0.1`. The
  serialization README promises such values are left as written. Goals 4, 12, 14.
  **Verdict 2026-10-05:** fixed. `Double` and `Float` fields carry a reads-back check built from the decoder's own
  rule (`Value.asDouble`, `Double.asFloatOrNull`), so `d = 1` stays for `1.0` and `0.1000000001` stays for `0.1f`;
  a value the decoder would refuse is rewritten. Three tests in `EncodeIntoFixesTest`; serialization JVM green.

- [x] **3. CLI `encode` prints a lone surrogate.** `app/.../Main.kt` `encode()`
  It prints with `toString()`, bypassing the `NotUnicode` check `writeTo` has, and exits 0. Goals 3, 15.
  **Verdict 2026-10-05:** fixed. `encode` writes through core's `writeTo`, and its `NotUnicode` becomes
  `NotTaggedJson(path, TaggedJsonProblem.NotUnicode)`, exit 1. Two tests in `TaggedJsonTest`; app JVM green.

- [x] **2. CLI `encode` accepts JSON one level deeper than kotoml parses back.** `app/.../TaggedJson.kt:67`, `:115`
  80 nested objects encode, and the output is refused with `KeyTooDeep`: the parser refuses a key inside the 80th
  inline table (`EditParser.kt:508`), `TaggedJson` counts that level as allowed. Goals 3, 15.
  **Verdict 2026-10-05:** fixed. An object as deep as the limit is refused when it holds a key, the parser's own
  rule. Two tests in `TaggedJsonTest`; app JVM green.

- [x] **1. Array-of-tables headers print the first element's spelling.** `core/.../Encode.kt:305`
  `[[x]]` then `[["x"]]` prints `[[x]]` twice; `[[a.b]]` then `[[a . b]]` prints a mix. The leaf key is the one
  `Key` the parent holds for the whole array. Breaks goal 2 and the header-spelling claim in `core/PARITY.md` and
  `core/README.md`; toml-test never varies an array-of-tables header's spelling, so the suite cannot catch it.
  **Verdict 2026-10-05:** fixed. A header's spelling now covers its own key, and what it stands for is settled
  from the keys the finished parse left on its path, so a later `[a]` replacing a key no longer voids an earlier
  `[[a .b]]`. Four tests in `PreservationTest`; clean build green, core 1354, toml-test 1.0 and 1.1.
