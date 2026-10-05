# Open work

Numbers are never reused. A closed item keeps its place in **Done** with a verdict: what was decided or done, how
it was proved, and the date. Items come from the three-reviewer QA pass of 2026-10-05 against `GOALS.md`, each
reproduced on `dev` at `2781090` before it was filed.

Every defect, ruling and docs item from that pass is closed; see **Done**.

## Candidate work

- [ ] **28. An inline table put back into braces loses the comments on its first key's line.** Owner ruling
  2026-10-05: an `isDotted` change gives a table a new layout, so its comments may go, but for the lines above its
  key. One heavy-run case still breaks that (`CommentPropertyTest` at 25 steps and 2000 seeds, hand-written 0 seed
  1660): an inline table printed on dotted lines, `"new key"."é"…`, holds the comments printed above that line on
  its first entry's key; made an inline table again, it prints `"new key" = { … }` and those comments, visually
  above its key, are gone. Moving them onto the holding key when it goes back into braces would keep them. Goal 4.

## Not in scope (owner, 2026-10-05)

Deliberate misuse is not a defect: the goals cover what a caller can do by accident in normal use. Not filed:
an `EditAborted` swallowed by the caller's own `catch` inside a builder block; a caller's own exception thrown in
a block (it propagates and the document is left as it was); an array added into itself; `/dev/zero` or more than
2 GiB on stdin; arbitrary text written into `Decor`, `trailing` or a key's decor.

## Done

- [x] **27. A multi-line inline table made dotted loses the comments between its entries.** TOML 1.1:
  `t = {\n  # about c\n  c = 3, # three\n  d = 4, # four\n  # end\n}` with `isDotted = true` prints the lines
  above each key but drops `# three`, `# four` (held at the start of the next key's prefix, before its first break)
  and `# end` (the table's trailing text); after `remove("c")` the kept `# about c` prints above `# about t`. Each
  needs comments moved to the end of the line before or after the group. Goal 4.
  **Verdict 2026-10-05:** fixed with goal 4's property test. On a table's lines a dotted inline table now ends the line
  before with a comment that followed a comma, puts one after its opening brace on a line of its own, and ends its last
  line with the comments before and after its closing brace; a comment joining a line that already ends in one goes
  on its own line. Removing a key from it edits the text in its braces' shape, so the key's same-line comment goes and
  the opening-brace comment stays. `HeaderCommentsTest` pins it, failing on the old code.


- [x] **26. Making nested inline tables dotted drops the comment above them.** `x = 1\n# about b\nt = { c = { c = 3 } }`,
  then `isDotted = true` on `t` and on `c`: the next edit to them prints `x = 1\n t.c.c = 3` (the comment line gone,
  a stray space before `t`), and a new key prints with a space before its line ending. Found while cutting down
  `EditPropertyTest` findings; it still reads back, so that test, which checks data, cannot see it. A comment-keeping
  property (goal 4) would. Goal 4.
  **Verdict 2026-10-05:** fixed, and wider than filed: making even one inline table dotted dropped the comment, since
  a line takes its leading text from its last key, and the keys of an inline table hold the spacing inside its braces.
  An inline table made dotted on a table's lines now prints its first line under the text above the key that holds
  it, turns each key's spacing into the lines above it, and keeps only a comment after each value. Removing and
  replacing its keys keep lines by the same rule. Three tests in `PreservationTest`, each failing on the old code;
  `EditPropertyTest` clean at 25 steps and 2000 seeds.


- [x] **25. Printing and copying recurse once per nesting level.** `Encode.kt`, `Value.copy`,
  `StandardTable.copy`. A document built through the API thousands of levels deep overflows the stack in `toString()`,
  and, since arrays store copies (item 11), already while it is built. That is the hand-built case the owner ruled
  deliberate misuse; `writeTo` refuses anything past the limit with `TooDeep`. Making both iterative would remove the
  overflow for good. Goal 5.
  **Verdict 2026-10-05:** fixed by the owner's ruling "add a limit and if we reach that depth we error", enforced
  from the root. Every table and array knows the slot holding it, and every slot its container (`Nesting.kt`), so a
  store measures the incoming content, without recursion, from where it lands, by the parser's rule. Past
  `NESTING_LIMIT` it returns the new `TomlEditError.TooDeep(keyPath)` and stores nothing; that covers `set`, `put`,
  `getOrPut`, `putAll`, `add`, `addAll`, `replace`, `assign`, `makeValue`, the DSL, the `…Of` builders and the
  conversions to inline content. A dotted table counts the header parts above it too, so turning dotted tables into
  headers with the public `isDotted` cannot be used to stack tables without end; no tree gets deep enough to overflow
  printing or copying. `writeTo`'s `TooDeep` stays for documents that `isDotted` changes made deeper. Scalar `add`
  on an array cannot nest and still returns `Unit`. Tests: `NestingTest` (13), `StoreDepthTest` (17),
  `WriteDepthTest` (3), each depth rule
  mutation-checked; the CLI's tagged JSON reader now leans on the stores instead of its own copy of the rule. Clean
  build green.

- [x] **24. Exit codes are barely tested.** Only `FilterTest` asserts one; nothing pins `check --json` output or exits
  0 and 1. Goal 15.
  **Verdict 2026-10-05:** fixed. The CLI tree is `kotomlCli()`, and `ExitCodeTest` runs whole invocations through
  klap's `run` with a recording terminal: exits 0, 1 (unreadable, not TOML, nothing selected) and 2 (bad filter,
  unknown option), and `check --json`'s output both ways, as the README states them. All eight held on the first run.

- [x] **23. `encodeInto`'s list merge is quadratic.** 2000 tables: 877 ms; 10 000 integers: 1.4 s. Should 5.
  **Verdict 2026-10-05:** fixed for every list but a wholly changed one. The cost was not the planner but
  `holdsKeyAtDefault`, which paired every new element with every old one; it now groups by kind and key, linear in the
  elements. Measured on the JVM, `encodeInto` of 10 000 unchanged integers: 769 ms to 8 ms; 2000 unchanged tables:
  139 ms to 26 ms; 100 appended: 27 ms. The planner also skips its used prefix. A list whose every element changed
  stays quadratic (10 000 integers: 2.5 s): a hash would have to agree exactly with "reads the same" across types.

- [x] **22. `TomlType.Missing` is a public kind nothing has.** No `Item` or `Value` has it since `Item::None` went;
  only serialization reports it for an absent field. Goal 10.
  **Verdict 2026-10-05:** fixed (owner). `TomlType.Missing` is gone; a serializer that reads a field the document
  lacks gets `MissingKey(path)`, as generated serializers already did (`mismatchAt`, used by the decoder and
  `KnownTypes`). Wire change: `"found":"Missing"` no longer occurs. Tests updated in `MissingValueTest`,
  `TypeNameTest`, `ErrorJsonTest`, `WireSchemaTest`.

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
