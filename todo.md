# Open work

Numbers are never reused. A closed item keeps its place in **Done** with a verdict: what was decided or done, how
it was proved, and the date. Items come from the three-reviewer QA pass of 2026-10-05 against `GOALS.md`, each
reproduced on `dev` at `2781090` before it was filed.

## Defects

- [ ] **1. Array-of-tables headers print the first element's spelling.** `core/.../Encode.kt:305`
  `[[x]]` then `[["x"]]` prints `[[x]]` twice; `[[a.b]]` then `[[a . b]]` prints a mix. The leaf key is the one
  `Key` the parent holds for the whole array. Breaks goal 2 and the header-spelling claim in `core/PARITY.md` and
  `core/README.md`; toml-test never varies an array-of-tables header's spelling, so the suite cannot catch it.
- [ ] **2. CLI `encode` accepts JSON one level deeper than kotoml parses back.** `app/.../TaggedJson.kt:67`, `:115`
  80 nested objects encode, and the output is refused with `KeyTooDeep`: the parser refuses a key inside the 80th
  inline table (`EditParser.kt:508`), `TaggedJson` counts that level as allowed. Goals 3, 15.
- [ ] **3. CLI `encode` prints a lone surrogate.** `app/.../Main.kt` `encode()`
  It prints with `toString()`, bypassing the `NotUnicode` check `writeTo` has, and exits 0. Goals 3, 15.
- [ ] **4. `encodeInto` rewrites a number that reads the same.** `serialization/.../DocumentMerger.kt:428`
  `d = 1` into a `Double` field becomes `d = 1.0`; `f = 0.1000000001` into a `Float` becomes `f = 0.1`. The
  serialization README promises such values are left as written. Goals 4, 12, 14.
- [ ] **5. Appending to a multi-line array lands on the previous element's line.** `core/.../TomlArray.kt:93`
  `a = [\n  1, # one\n]` plus `add(3)` prints `1, 3, # one`. `encodeInto` already lays inserted elements out
  (`layOutInserted`); core and the DSL do not. Goal 4.
- [ ] **6. Adding to an inline table puts a space before the comma.** `{ x = 1 }` plus `y` prints
  `{ x = 1 , y = 2 }`. Goal 4.
- [ ] **7. A comment kept from a removed table prints below a table added later.** `# about a\n[a]\nx=1\n`, remove
  `a`, add `[c]`: `[c]\nz = 1\n# about a`. Goal 4.
- [ ] **8. After an `encodeInto` reorder of an array of tables the blank lines shift.** The text starts with a
  blank line and the one between two elements is gone. Goal 4.
- [ ] **9. A parse error after a byte order mark is one column off.** `"﻿a = = 1"` reports column 6; the
  visible column is 5. Should 1.
- [ ] **10. CLI stdin treats `EINTR` as an I/O error instead of retrying.** `app/.../Stdin.native.kt`. Goal 15.

## Decided: enforce (owner, 2026-10-05)

- [ ] **11. Arrays and arrays of tables store a copy.** `TomlArray.add` / `add(index)` / `set` / `replace`,
  `ArrayOfTables.add` / `replace` store the object as is, so one `Value` added to two arrays changes both. Store a
  copy, as the table setters and the DSL do. Removes the self-containing array as a side effect. Goal 9.
- [ ] **12. A typed error past the nesting limit when encoding or storing.** A recursive model 100 deep encodes to
  text kotoml refuses (`KeyTooDeep`); thousands deep throws `StackOverflowError` (`NodeEncoder` catches
  `Exception`). Return a typed `TooDeep` from serialization and from storing through the API. Goals 3, 5, 6, 14.
- [ ] **13. `parse(String)` refuses a lone surrogate.** Today it becomes `?` on the JVM and U+FFFD on Kotlin/Native,
  so the value changes silently and differs by platform; `PARITY.md:727` and `TomlSource.kt:14` claim U+FFFD.
  Return a typed parse error. Goals 2, 13.
- [ ] **14. An empty dotted or implicit table still prints.** Removing `a.b` from `a = { a.b = 1 }`, emptying an
  implicit table, or an empty dotted inline table makes the key vanish from the text while the model keeps it.
  Print it as `key = {}` or its header. Goal 3.

## Docs and goals

- [ ] **15. `GOALS.md` wording.** State the deliberate-misuse rule; goal 3 excludes raw formatting text
  (`Decor`, `trailing`, key decor) the caller writes; goal 6 states the real bound (a header path adds its own
  levels); goal 8: `span` and `position` are nullable; goals 13 and 15: `ReadFailureTest` (native) and
  `PermissionTest` (JVM) are platform tests.
- [ ] **16. `Datetime`'s KDoc example does not compile.** `core/.../Datetime.kt:15` calls the private `Date(...)`;
  use `Date.of(...)`.
- [ ] **17. `TomlEditError`'s header is stale.** `core/.../TomlEditError.kt:7` says "refused by panicking";
  `NotFound`, `TypeMismatch` and `NotAValue` are not panics upstream.
- [ ] **18. `PARITY.md` test references.** `:309` cites a nanosecond-without-second test that no longer exists (the
  case cannot be built since `Time.of`); `:570` cites a drifted name. 79 of 85 intended-difference rows name no
  test.
- [ ] **19. Pin every error wire name.** Core's `ErrorJsonTest` pins 10 of 85 `@SerialName`s, serialization's 7 of
  18; a rename fails no test. One table-driven test per sealed hierarchy. Goal 8.
- [ ] **20. KDoc for the `ParseErrorReason` objects.** About 25 public objects have none. Should 4.

## Candidate work

- [ ] **21. `InvalidUtf8` speaks Rust and bytes.** `validUpTo` / `errorLength` are byte offsets from Rust's
  `Utf8Error`; the message "invalid utf-8 sequence of 1 bytes from index 4" is Rust's. Goal 10, Should 1.
- [ ] **22. `TomlType.Missing` is a public kind nothing has.** No `Item` or `Value` has it since `Item::None` went;
  only serialization reports it for an absent field. Goal 10.
- [ ] **23. `encodeInto`'s list merge is quadratic.** 2000 tables: 877 ms; 10 000 integers: 1.4 s. Should 5.
- [ ] **24. Exit codes are barely tested.** Only `FilterTest` asserts one; nothing pins `check --json` output or exits
  0 and 1. Goal 15.

## Not in scope (owner, 2026-10-05)

Deliberate misuse is not a defect: the goals cover what a caller can do by accident in normal use. Not filed:
an `EditAborted` swallowed by the caller's own `catch` inside a builder block; a caller's own exception thrown in
a block (it propagates and the document is left as it was); an array added into itself; `/dev/zero` or more than
2 GiB on stdin; arbitrary text written into `Decor`, `trailing` or a key's decor.

## Done
