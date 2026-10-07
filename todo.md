# Open work

Numbers are never reused. A closed item keeps its place in **Done** with a verdict: what was decided or done, how
it was proved, and the date. Items come from the three-reviewer QA pass of 2026-10-05 against `GOALS.md`, each
reproduced on `dev` at `2781090` before it was filed.

Every defect, ruling and docs item from that pass is closed; see **Done**.

Items 30 and up come from the black-box audit of 2026-10-05: six agents built apps on kotoml `0.1.0` at `4cbd719`
from the docs alone (config editor, serialization, CLI, parser, builder, restructuring). A ✓ marks an item the main
session reproduced itself; the others rest on one agent's run. Repros are in each agent's `REPORT.md`.

## Candidate work

### Bugs against a goal

- [ ] **116. Storing a dotted inline table holding kept lines into a standard table swallows the next line.**
  `s = {\n  b.c = 1,\n  # about d\n  b.d = 2,\n}\n[t]\nx = 0\n`, remove `d` from `s.b`, then `t["b"] = s.b`
  prints `[t]\nb.c = 1\n\n  # about dx = 0\n`: the kept brace-style text prints raw on the table's lines, so
  `x = 0` reads back as part of the comment. Found while fixing 114; already on dev before it.

### Cleanup pass rulings (2026-10-07)


### Layout no person would write


### Error messages


### API gaps and ergonomics

- [ ] **70. CLI has no set, delete or in-place edit.** The only path, `decode | jq | encode`, drops every comment
  and writes all tables inline. `encode` reads only tagged JSON, not what `get --json` prints.
  **Ruling 2026-10-05:** `-i` writes back into the file. Low priority: do when nothing else is open. Plain JSON to
  TOML not in scope yet.

### Docs


### Found while fixing


### Owner calls on wave 1 (2026-10-05)



- [ ] **84. Remove `mavenLocal()` from `settings.gradle.kts`.** Added so kotoml builds against kern 0.4.3 and klap
  0.5.2 before they are published; take it out once they are, before kotoml ships.

### Owner calls on wave 2 (2026-10-05)


Settled with no change (wave 2): a caller's comment on a replaced value replaces the comment on that line; a
removed table keeps the comment lines above its header in place; a copy of a document's root keeps its version.

### Owner calls on wave 3 (2026-10-06)


Settled with no change (wave 3): a longer word starting with a keyword (`info`, `nann`) must be quoted, even when it
is one edit away; a swap of two neighbouring letters counts as one edit (`ture`, `fasle`); a copied value brings
its same-line comment into an array as into a table (C5 stands).










Settled the same day, no change: a copied table prints where its key stands (item 38 over 29's "after the tables
already there"); a moved table gets the default blank line (spacing is not kept across operations); a bare value's
same-line comment travels with it; `makeValue` on a 1.0 array of tables keeps header comments and comment lines
between elements; `sort()` stays on inline tables; the wave 1 wire changes stay (kotoml is unreleased).

Reported as friction but documented by design, not filed: `doc["typo"]["k"] = v` is a silent no-op; nullable types
are refused; `Map<Int, V>` is refused.

## Not in scope (owner, 2026-10-05)

Deliberate misuse is not a defect: the goals cover what a caller can do by accident in normal use. Not filed:
an `EditAborted` swallowed by the caller's own `catch` inside a builder block; a caller's own exception thrown in
a block (it propagates and the document is left as it was); an array added into itself; `/dev/zero` or more than
2 GiB on stdin; arbitrary text written into `Decor`, `trailing` or a key's decor.

## Done

- [x] **115. `KeyValuePairs.takeAll()` leaves the kept lines behind.** `moveToDottedTable()` and
  `takeReadDottedTable()` move only the entries, so a dotted table's kept comment lines, `emptyTableDecor` and
  `emptyLineKeyDecor` may be lost on that conversion. Not yet reproduced.
  **Verdict 2026-10-07:** no change: no loss reproduced. Kept lines of an inline table are brace-style and a standard
  table's table-style, so moving them unchanged would print them wrongly; the callers that need them read them into
  comments. The one path that holds such lines is 116.

- [x] **114. A comment above a dotted key in braces glues onto the line before once the table is a header.**
  `t = {\n  x = 0,\n  b.c = 1,\n  # about d\n  b.d = 2,\n}` made a header prints `b.c = 1# about d\nb.d = 2`;
  after `format()` the comment ends the line of `c` instead of standing above `d`. Likewise making
  `t = {\n  # about c\n  b.c = 1,\n  d = 2,\n}` a header and then removing `c` from `b` prints `b = {}# about c`.
  `TableLineComments.applyTo` writes table-style prefixes onto keys in dotted inline tables, which
  `takeOutOfBraces` reads as brace-style. Found while fixing 104; already on dev before it.
  **Verdict 2026-10-07:** fixed: keys under a dotted key in braces hold their comment lines brace-style on both sides,
  the text read into comments is cleared from the dotted tables inside, and an emptied dotted table on a table's lines
  prints the lines above its keys above its `{}`; comments stay above their keys, print once, and survive removals.
  Pinned in LayoutChangeTest and FormatTest.

- [x] **112. Split StandardTable.kt.** Move the out-of-braces body lines (`VisualValue`, `takeOutOfBraces`,
  `inPrintOrder`), table placement (`placedParts`, `place*`) and the sort helpers into their own files; no API or
  behaviour change. `FRICTION_LOG.md` stays tracked on `dev` (ruling, D5).
  **Verdict 2026-10-07:** done: BodyLines.kt holds the out-of-braces body lines (`VisualValue`, `takeOutOfBraces`,
  `inPrintOrder`, `emptyTableLine`) and TablePlacement.kt the table placement; moved line for line, StandardTable.kt
  1299 to 1030 lines. The sort code stays: it is private members of the class.

- [x] **104. `format()` prints a kept comment line twice.** Removing the last key of `b.c` in
  `t = {\n  # about c\n  b.c = 1,\n}` and then `setLayout(Header, force = true)` on `t` prints
  `[t]\n\n  # about cb = {}\n`; `format()` on that document prints the line twice:
  `[t]\n\n  # about cb = {}\n\n# about cb = {}\n`. **Ruling 2026-10-07:** that the forced layout change lets the
  emptied key fall into the comment is fine; the doubled line is the bug. **Ruling 2026-10-07, later:** the
  glued line is a printing bug too: a comment kept in a dotted table prints on its own line on a table's lines, so
  the example prints `[t]\n# about c\nb = {}\n` and `format()` prints it once.
  **Verdict 2026-10-07:** fixed: making a dotted inline table's holder a header table turns the brace-style comment
  lines kept in it into comment lines above the next key, or kept lines at the end, as comments above keys already
  convert; the example prints `[t]\n# about c\nb = {}\n` and `format()` leaves it as is. Pinned in LayoutChangeTest and
  FormatTest.

- [x] **113. Reordering an array of tables moves the comment lines above its headers.** Reversing
  `# first\n[[a]] # h1\nn = 1 #one\n\n# second\n[[a]] # h2\nn = 2 #two\n` with
  `doc["a"] = arr.reversed().toArrayOfTables()` prints `# first` twice, drops `# second` and the blank line:
  `# first\n[[a]] # h2\nn = 2 #two\n# first\n[[a]] # h1\nn = 1 #one\n`. **Ruling 2026-10-07:** the lines above a
  header stay where they are, since they need not be about that header (`# x = 1` above `[[a]]`); only the tables
  swap, each with its header comment and values:
  `# first\n[[a]] # h2\nn = 2 #two\n\n# second\n[[a]] # h1\nn = 1 #one\n`.
  **Verdict 2026-10-07:** fixed: when the array is stored over the old one, each table takes the place of the old header
  at its position, with the lines above it; a table under an element moves with it, and the lines above every place
  stay, even where a different kind of header comes to stand (owner: keep that). Pinned in PrintingFixesTest.

- [x] **111. CommentScenariosTest prints only scenarios that differ from their expected output.** It prints a
  block for all 330 on every run.
  **Verdict 2026-10-07:** done: gen.py prints a block only for a scenario whose output differs; the assertions are
  unchanged.

- [x] **110. One "too deep" wording.** Serialization's `nests deeper than kotoml writes` becomes core's
  `nests deeper than 80 levels, so it would not parse back`.
  **Verdict 2026-10-07:** fixed: serialization's TooDeep reads `nests deeper than 80 levels, so it would not parse back`
  (the limit is `TomlDocument.NESTING_LIMIT`, 80). Pinned in DepthTest.

- [x] **109. Document how `get` prints header paths.** `get .a` prints `[[srv]]` and `[c]` relative to `.a`;
  `get .a.srv` prints `[[a.srv]]`. **Ruling:** keep both; app/README.md says so.
  **Verdict 2026-10-07:** done: app/README.md shows both forms from the built binary; GetTest pins the relative one too.

- [x] **108. CLI error text writes a key bare when jq reads it bare.** `get .servers.a` on an array of tables says
  `cannot apply ."a" to array of tables`, while a missing key says `no key "nope" at .nope`. **Ruling:** both bare
  where jq allows it: `cannot apply .a to array of tables`.
  **Verdict 2026-10-07:** fixed: `Step.describe()` spells a key bare where jq reads it bare and quoted otherwise, and
  the evaluator uses it, so `cannot apply .a to array of tables` and `cannot apply ."a b" to ...`. Pinned in GetTest.

- [x] **107. A decode parse error on line 1 puts the rendered block in its message.** `decodeFromString` of
  `a = 1 x\n` gives `line 1, column 7: TOML parse error at line 1, column 7`, then the gutter and caret lines, then
  the reason; on line 2 it gives `line 2, column 7: unexpected key or value, expected newline, ...` alone.
  `describe()` in TomlDecodeError.kt calls `render("")`, which draws the gutter for line 1.
  **Ruling 2026-10-07:** line 1 gives the one-line message, as every other line does.
  **Verdict 2026-10-07:** fixed in core: `render` against empty text gives the message alone on any line, as its KDoc
  says, so a decode error on line 1 reads `line 1, column 7: unexpected key or value, ...`. Pinned in ParseFixesTest and
  ErrorMessageTest.

- [x] **106. `Key.format()` keeps indentation that `TomlDocument.format()` removes.** On
  `x = 0\n# c\n  a   = 1\n   b = 2\n`, `format()` on keys `a` and `b` prints `  a = 1\n  b = 2`, while the
  document's `format()` prints `a = 1\nb = 2`. Its KDoc was reworded to the code's behaviour in the cleanup pass;
  reword it back with the fix.
  **Ruling 2026-10-07:** `Key.format()` removes the indentation as `TomlDocument.format()` does; the KDoc says so again.
  **Verdict 2026-10-07:** fixed: `Key.format()` on a key starting a line of a table leaves that line with no
  indentation, keeping the lines above; keys in braces and headers format as before. Pinned in FormatTest.

- [x] **105. `ArrayOfTables.addAll` keeps the source table's comments and header line; `add` drops them.** Adding
  `s[0]` of `# above\n[[s]] # head\nx = 1\n` to `[[t]]\ny = 0\n`: `add` prints `[[t]]\ny = 0\n\n[[t]]\nx = 1\n`,
  `addAll` prints `[[t]]\ny = 0\n# above\n[[t]] # head\nx = 1\n`. `addAll` skips `copyToStore`.
  **Ruling 2026-10-07:** `addAll` stores each table as `add` does.
  **Verdict 2026-10-07:** fixed: `addAll` stores each table through `copyToStore` as `add` does; `toArrayOfTables` keeps
  comments and places so the reorder idiom `arr.reversed().toArrayOfTables()` prints in place, as three
  PrintingFixesTest tests pin. Pinned in CollectionApiTest.

- [x] **103. An emptied dotted table on a table's lines took the default spacing.** Removing the last key of a dotted
  table under a header or at the top level printed `b.d.e=2` as `b.d = {}`, where inside an inline table (item 100) the
  `{}` line keeps the spacing of the line it replaced.
  **Verdict 2026-10-07:** fixed: the emptied table keeps the removed line's indentation, spacing before `=` and value
  spacing, without its comment, so `b.d.e=2 # e` leaves `b.d={}`; the spacing is held on the table, not on its key, so a
  header table made dotted and emptied prints `[a.b.d]` again when made a header. `format()` still rewrites the spacing.
  Pinned in RemovalKeepsCommentsTest.

- [x] **73. Document `isImplicit`, `position`, `leafDecor` and `Value.parse` for spellings.** And correct the sorting
  sentence, which suggests tables move.
  **Verdict 2026-10-07:** fixed: `isImplicit`/`setImplicit`, a table's `position` and the sorting sentence were
  already right after the API work; core/README.md gains "Choose spellings and comments": `Value.parse` to store a
  spelling of your choice, a value's `decor`, a key's `leafDecor` and `dottedDecor`, a table's `decor`, and
  `keyOrNull`, with an example mirrored in EditDocTest.

- [x] **100. An empty dotted table last in a multi-line inline table prints `{} ,`.** `b.d = {} ,`: a space before
  the comma, because the empty table's value is built anew on each read and loses its suffix. Valid TOML, no comment
  lost.
  **Verdict 2026-10-07:** fixed: two causes: an empty dotted table's `{}` value was built anew on each read, so the
  spacing a layout gave it was lost (`{} ,`); and removing a dotted table's last key took its line's spacing with the
  leaf key (`a = 1, b.d = {} ,` on one line). The `{}` spacing now lives on the table and survives `format()`, and the
  emptied table takes the removed line's spacing without its comment. Five MultilineInlineTableTest cases;
  CommentPropertyTest, EditPropertyTest and FormatTest clean at 10 times the seeds.

- [x] **102. `displaySpelling` follows the document for strings but not date-times; a detached string forgets its
  file.**
  **Ruling 2026-10-07:** `Value.Datetime.displaySpelling` follows its document's version, as a string's follows the line
  ending; a string taken out of a document (removed, or a detached handle) keeps the line ending of the document it came
  from; one built in code and never stored uses `\n`.
  **Verdict 2026-10-07:** fixed: `Value.Datetime.displaySpelling` follows its document's version (`07:32:00` in TOML
  1.0); a table, array or value taken out of a document (remove, removeAt, a replace that detaches it, or `copy()`)
  keeps that document's line ending and TOML version, for printing and edits, until stored again (owner confirmed);
  one built in code uses `\n` and TOML 1.1; a table's `toString()` uses its document's line ending;
  `TomlDocument(table)` takes the source's line ending and version. PreservationTest, VersionTest, ValueTextTest.

- [x] **101. Two indents for arrays, and LF lines in a CRLF file.** The spreading a caller's comment triggers indents
  two spaces from column 0, while `setMultiline(true)` indents from the array's line; and a multi-line string written in
  its default spelling (by `respell()` or built in code) breaks its lines with LF in a CRLF document.
  **Ruling 2026-10-07:** one rule: every spread array indents from its own line; and every line kotoml writes takes the
  document's line ending (item 79), multi-line strings included.
  **Progress 2026-10-07:** the indent rule is done (d87e784). A CRLF inside a multi-line string decodes as `\r\n`
  today, so writing those lines in CRLF would change the value. **Ruling 2026-10-07:** A: the parser reads a line
  break inside a multi-line string as `\n` whatever the file's line ending (the spec allows it), so kotoml can write
  those lines in the document's line ending; a file still prints back byte for byte.
  **Verdict 2026-10-07:** fixed: a spread array indents from its own line by one path, nested arrays included
  (CommentPlacementTest); a CRLF inside a multi-line string decodes to `\n`, and a multi-line string in its default
  spelling (respelled, or built in code and stored) breaks its lines with the document's line ending and reads back
  the same; unchanged files print byte for byte. DecoderStringTest, PreservationTest, RespellTest, serialization
  DecodeTest and EncodeIntoTest.

- [x] **99. No one call writes a value in its default spelling or puts an array on one line.** `resetFormatting()`
  did both; `format()` keeps value spellings and layouts by ruling, so `0x10` cannot become `16` and a multi-line
  array cannot go to one line without decor calls by hand.
  **Ruling 2026-10-07:** two tools. Arrays get the table pattern: `TomlArray.isMultiline` read-only and
  `setMultiline(value, force = false): EmptyResult<TomlEditError>`, one line refused with `CommentsWouldBeLost` unless
  forced, across lines one element per line with a trailing comma. Spellings: `respell(): Unit`, deep like
  `format()`, on the document, tables, `Item`, `Value` and arrays, writes every value in its default spelling
  (`0x1F90` becomes `8080`, `'kotoml'` `"kotoml"`, `1e0` `1.0`, `07:32` `07:32:00`); keys keep theirs.
  **Verdict 2026-10-07:** fixed: `TomlArray.isMultiline`/`setMultiline(value, force)` follow the table pattern (one
  line refused with `CommentsWouldBeLost` unless forced, the comment after `]` kept; across lines one element per
  line, indented from the array's line, with a trailing comma; nested arrays keep their layout), and `respell()` on
  the document, tables, arrays, `Item` and `Value` writes every value in its default spelling, keys and comments
  untouched. ArrayMultilineTest, RespellTest, both property tests.

- [x] **98. A forced layout change drops the comment lines above a table's first sub-header.** At ten times the
  seeds, `CommentPropertyTest` finds `setLayout(force = true)` dropping comments that removing the same table keeps:
  `# [x] you\n[x.y.z.w] # ...\n[x]\nd = 4`, then `setLayout(Inline, force = true)` on `x` drops the line above the
  first header. Reproduced with `format()` a no-op, so it predates item 65.
  **Verdict 2026-10-07:** fixed: three causes: a sub-header printing before the table's own lost the lines above it
  into the braces, also when a table in between had no position yet; and an empty dotted table across lines lost its
  kept lines because line comments matched a value built anew. The lines now go above the key (KeptLines, Item,
  LineComments matched by key). Four LayoutTest cases; CommentPropertyTest clean at 100 times the seeds,
  EditPropertyTest at 10 times.

- [x] **65. `resetFormatting` is shallow and differs per kind.** A table keeps its values' spellings and key quoting,
  an array resets its layout but not its elements, and on the root it deletes comments. No document-wide reset.
  **Ruling 2026-10-05:** rename to `format(): Unit` on `Item`, `Value`, the tables and the document; deep; keeps
  comments. It cannot fail: writing is `writeTo`'s.
  **Ruling 2026-10-05 (2):** `format()` fixes key quoting (`'q'` becomes `q`), blank lines between tables and
  spacing; it leaves the layout as it is (dotted stays dotted, inline stays inline).
  **Ruling 2026-10-07:** value spellings stay (`'lit'`, `0x1F90`, `1e0`); comments get one space after `#` and a
  same-line comment one space before it (`a = 1 # c`); indentation goes; a one-line array or inline table loses its
  trailing comma; each line keeps its own line ending.
  **Verdict 2026-10-07:** fixed: `format()` on the document, both tables, both arrays, Item, Value and Key replaces
  `resetFormatting`; deep, cannot fail, keeps comments, line endings, layouts and value spellings, and fixes key
  quoting, spacing, blank lines (a single one between key lines stays, runs collapse), indentation, comment spacing (a
  missing space after `#` is added) and one-line trailing commas; objects stay the same ones. FormatTest (every valid
  toml-test file reads back the same, keeps every comment, formats stably); format edits in both property tests. Found
  on the way: the lexer refused `[[j ."#"]]` (fixed, ParseFixesTest).

- [x] **61. No rename.** `put("new", remove("old"))` moves the key to the end and leaves its comment above the next
  key; a key built from the old key's decor duplicates the comment.
  **Ruling 2026-10-05:** `rename(old, new)` keeps position, comments and value; a header table renames its sub-tables;
  `NotFound` / key-taken errors.
  **Ruling 2026-10-05 (2):** renaming an array of tables renames every `[[x]]` header to `[[y]]`. Renaming onto a key
  already taken is an error (assumed, not yet confirmed).
  **Verdict 2026-10-07:** fixed: `rename(old, new)` on tables, inline tables, the document and items keeps the entry's
  place, comments, spacing and item (handles stay live), renames sub-table headers, every `[[old]]` header and dotted
  lines, spells the new key as a new key; `NotFound` and the new `KeyTaken` change nothing; renaming to itself is Ok.
  RenameTest; random renames in both property tests.

- [x] **94. A multi-line inline table is a layout no caller can choose.** `inline()` writes one across lines only
  when comments need it, and nested tables always on one line, dropping their comments in 1.1. **Ruling 2026-10-06:**
  B: an explicit layout, `InlineTable.isMultiline`, like `isDotted`: `true` prints one entry per line, `false` one
  line (comments inside go, as on one line today); a parsed multi-line inline table reads `true`;
  `Item.inline(multiline = …)` picks it for the table and its nested tables, and without the argument picks
  multi-line when there are comments to keep, as today; in a 1.0 document `writeTo` refuses it with
  `NotInToml10(MultilineInlineTable)`. Four layouts: header, dotted, one-line inline, multi-line inline.
  **Verdict 2026-10-07:** fixed through 96: `TableLayout.MultilineInline` is a layout a caller chooses with
  `setLayout`; a multi-line inline table's last entry takes a trailing comma in TOML 1.1.

- [x] **97. `setInline(true)` on a dotted table holding lines kept from removed keys loses and duplicates
  comments.** Parse `# about a.x\na.x = 1 # x\n# about b\nb = 2\n# about a.y\na.y = 3 # y\n`, then
  `a.remove("y")`, `a.setDotted(false, true)`, `a.setImplicit(true)`, `a.setDotted(true, true)`, `doc.remove("b")`,
  `doc["a"].setInline(true)`: refused with `CommentsWouldBeLost(["a"], 1)` (forced, it drops the comment above the
  first line and repeats the kept lines). In 1.1 an inline conversion should never fail on comments.
  **Verdict 2026-10-07:** fixed: a dotted table holding lines kept from removed keys goes to `MultilineInline` without
  force and keeps every comment once (two causes in `KeyValue.takeLinesAbove`). LayoutTest.

- [x] **96. A table's layout is three setters with flags, and the version is a print option.** `setInline`,
  `setMultiline` and `setDotted` pick between states of one thing, and a 1.0 document refuses 1.1-only syntax only
  when written. **Ruling 2026-10-07:** a table's layout is a state, a sealed `TableLayout` (`Header`, `Dotted`,
  `Inline`, `MultilineInline`), read as `layout` and changed with `Item.setLayout(layout, force = false)`, which
  reaches the tables inside for `Header` and `MultilineInline`; the tables stay `StandardTable` and `InlineTable`
  objects, so handles stay live. The version belongs to the document: `doc.setVersion(version, force = false)`
  converts it (to 1.0: seconds on times, `\e`/`\x` respelled, multi-line inline tables to one line, trailing
  commas dropped; typed errors, `CommentsWouldBeLost` unless forced); under 1.0 an edit that would add 1.1-only
  syntax is refused at the edit, so `writeTo` no longer refuses a version problem. Replaces item 95's setters
  before release.
  **Verdict 2026-10-07:** fixed: `TableLayout` (`Header`, `Dotted`, `Inline`, `MultilineInline`) read as `layout` and
  changed with `Item.setLayout(layout, force)`, reaching the tables inside for `Header` and `MultilineInline`; a
  change keeps every comment the new layout can hold, refuses with `CommentsWouldBeLost` unless forced, and going to
  one line keeps the header comment after `}`; `doc.setVersion(version, force)` converts to 1.0 after a dry run; under
  1.0 an edit adding 1.1-only syntax returns `TomlEditError.NotInToml10`; a time stored in a 1.0 document gets seconds
  0; `writeTo` no longer refuses a version problem (`TomlWriteError.NotInToml10` removed). LayoutTest, VersionTest,
  both modes in the property tests.

- [x] **95. A layout change is a property write that may drop comments.** `table.isDotted = true`, `inline.isMultiline`
  set to false, and `Item.inline()` / `expand()` change the document as a side effect of an assignment or rewrite it
  silently, a habit of the Rust port. **Ruling 2026-10-07:** read-only properties ask, and functions returning a
  `Result` change: `Item.isInline` with `setInline(value, force = false)` (`setInline(true)` replaces `inline()`,
  `setInline(false)` replaces `expand()`); `InlineTable.isMultiline` with `setMultiline(value, force = false)`;
  `isDotted` with `setDotted(value, force = false)` on both table kinds; `StandardTable.isImplicit` with
  `setImplicit(value)` returning `Unit`, since it cannot fail. A change that would drop a comment returns a new
  `TomlEditError.CommentsWouldBeLost(keyPath, count)` and changes nothing, unless `force = true`; `setInline(true)`
  goes across lines in TOML 1.1 when that keeps the comments, and in a 1.0 document fails unless forced; one line or
  across lines is chosen separately with `setMultiline` (2026-10-07: no `multiline` parameter on `setInline`). A
  multi-line inline table's last entry takes a trailing comma in TOML 1.1 (a 1.0 document has neither multi-line
  inline tables nor trailing commas).
  **Verdict 2026-10-07:** superseded by 96 before release: its setters (setInline, setDotted, setMultiline,
  setImplicit) and `CommentsWouldBeLost` were built, then folded into `setLayout`; `setImplicit` and
  `CommentsWouldBeLost` stay.

- [x] **93. `expand()` turns only the top level into a table.** Inline tables inside stay inline, so `inline()` then
  `expand()` does not give back a table with sub-tables. **Ruling 2026-10-06:** `expand()` mirrors `inline()` over the
  whole tree: the value and every inline table inside it become header tables, arrays of inline tables become arrays
  of tables.
  **Verdict 2026-10-06:** fixed: `expand()` mirrors `inline()` over the whole tree: nested inline tables become
  headers, arrays of only inline tables become arrays of tables, comments kept at every level, `TooDeep` on header
  depth; dotted keys inside stay dotted; a table holding only tables prints no header unless it carries a comment. The
  1.1 round trip is exact for the comments `inline()` keeps; nested tables' comments are item 94. ExpandTest.

- [x] **82. Rename `makeValue()` to `inline()`.** **Ruling 2026-10-05:** rename to `Item.inline()`, and add
  its mirror `Item.expand()`, which turns `t = { … }` into `[t]` in place; `InlineTable.toStandardTable()` stays
  for building a table without touching the document. Goes with 66 (wave 2, API).
  **Verdict 2026-10-06:** fixed: `makeValue` is renamed `inline()`, and its mirror `expand()` turns `t = { … }` into
  `[t]` and an array of inline tables into an array of tables in place, one level deep, keeping comments and printing
  where a table stored at that key would; already a table: no change; a scalar or plain array: `NotExpandable` (new
  error); inside a value: `NotAValue`. ExpandTest; the QA scenario sources and gen.py follow the renames of 66 and 82.

- [x] **66. Containers answer in different shapes.** `InlineTable[k]` and `TomlArray[i]` are nullable,
  `StandardTable[k]` a `Result`; no `InlineTable.getOrNull`; `TomlArray.add(String)` is `Unit` and `add(Value)` a
  `Result`; store calls refuse `Int`; payloads are `.value`, `.array` and `.table`; `putItem` turns a table into an
  inline table where `Item.set` refuses it.
  **Ruling 2026-10-05:** yes, breaking changes are fine before the first release: `Result` indexing and `getOrNull` on
  every container, `Int` overloads, one payload name, `putItem` of a table into an inline table is `NotAValue`.
  **Ruling 2026-10-05 (2):** scalar `add`s stay `Unit`: a call that cannot fail returns no `Result`. The payloads
  `Value.Array.array` and `Value.InlineTable.table` are renamed to `.value`, like the scalars.
  **Verdict 2026-10-06:** fixed: every container indexes to a `Result` with `getOrNull` beside it, every integer store
  takes `Int` (and `Value.Integer(Int)`), scalar adds stay `Unit`, the payloads are `.value`, and `putItem` of a table
  or array of tables into an inline table is `NotAValue`. Scalar `set`s keep `EmptyResult`: they can fail (`TooDeep`,
  `NotATable`). IndexingTest, CollectionApiTest, SlotSemanticsTest.

- [x] **92. A type mismatch does not suggest the value meant.** A `Boolean` field given `"ture"` reports
  `TypeMismatch` with the value but no hint. **Ruling 2026-10-06:** yes: `TypeMismatch` gains a suggestion from
  kern:fuzzy's `didYouMean` with the expected type's names `preferred` (`true`/`false` for a boolean, `inf`/`nan`
  for a float).
  **Verdict 2026-10-06:** fixed: `TypeMismatch` gains `suggestion` (wire field), from kern:fuzzy's `didYouMean` with
  the expected type's names preferred: a boolean field suggests `true`/`false`, a float `inf`/`nan` (keeping its
  sign), an enum one of its names; only strings and booleans are compared; a suggestion of another type is dropped; a
  quoted exact keyword (`"true"`) suggests itself. DecodeSuggestionTest, ErrorMessageTest, ErrorJsonTest.

- [x] **91. `+inf` followed by a control character is told to quote.** `a = +inf` and a vertical tab reports
  "string values must be quoted" over the whole word. **Ruling 2026-10-06:** B: a keyword followed by a control
  character reports the control character itself, under it.
  **Verdict 2026-10-06:** fixed: a keyword directly followed by a control character reports `InvalidScalar` under the
  control character (`+inf` and a vertical tab: "invalid float" at column 25). ParseMessageTest; one upstream snapshot
  changed.

- [x] **90. A keyword typo in the first letter is told to quote.** `rue` and `yrue` say "string values must be
  quoted"; `nf` is compared only to `nan`. Only words starting with t, f, i or n are compared to the keywords.
  **Ruling 2026-10-06:** B: compare every unquoted word to `true`, `false`, `inf` and `nan`; the nearest keyword
  wins, and a tie goes to the first in that order.
  **Verdict 2026-10-06:** fixed: every unquoted word is compared to `true`, `false`, `inf` and `nan` (a signed word to
  `inf` and `nan`) through kern:fuzzy's `didYouMean`, which kotoml's findings made stricter in kern 0.4.4: a prefix
  needs two characters, one edit up to five characters, no replaced letter in a word of three or fewer. `rue`, `tr`,
  `nf`, `an` are keyword typos; `can`, `imf`, `value` must be quoted. ParseMessageTest.

- [x] **75. `clean build` fails now and then linking the Windows binary.** `./gradlew clean build` once stopped at
  `:app:linkReleaseExecutableMingwX64` with `ld.lld: cannot open output file .../mingwX64/releaseExecutable/kotoml.exe:
  No such file or directory`; a plain `build` right after linked it. Likely `clean` racing the parallel link; not
  yet reproduced.
  **Verdict 2026-10-06:** not a defect: a single `clean build` orders `clean` before every producer (five runs green);
  the exact ld.lld error was reproduced only with a second Gradle invocation running `clean` in the same checkout
  while the first linked. Seen once, in the assistant's own run; the owner never hit it.

- [x] **39. Printing a document built in code is quadratic.** ✓ The DSL, n tables each with two sub-tables:
  2500 print in 378 ms, 5000 in 1.46 s; the same text parsed prints in 31 ms. Built tables have no position.
  `encodeInto` of 8000 reversed `[[servers]]` takes 5.6 s (todo 23 left wholly changed lists quadratic). Should 5.
  **Ruling 2026-10-05:** expected: printing a built document is linear.
  **Verdict 2026-10-06:** fixed: the print order keeps the slotted part each anchor leads to, and an array element
  finds its array's first without copying it. 20000 built tables print in about 109 ms (was 121 s); output unchanged
  byte for byte. `encodeInto`'s reversed-list merge stays quadratic in the matching (todo 23), its print share is
  linear. PrintScalingTest.

- [x] **89. A container's `copy()` keeps every comment.** `src["db"].getOrFail().standardTableOrNull!!.copy()`
  stored elsewhere brings `# Generated` and `# db hdr`; `src["db"].getOrFail().copy()` does not. **Ruling
  2026-10-05:** rule 3 for every public `copy()` (table, inline table, array, array of tables, value): it leaves
  the comments read from its source, as a store does; internal code uses a private deep copy.
  **Verdict 2026-10-05:** fixed: every public `copy()` (table, inline table, array, array of tables, array or
  inline-table value) is what a store would store and leaves the comments read from its source; `TomlDocument.copy()`
  and a root's copy keep everything; internal code uses an internal `deepCopy()`. HandleRulesTest.

- [x] **88. Any word starting with `t` or `f` is told it is a misspelt boolean.** `fruit = tomato` says
  `invalid boolean, expected true`. **Ruling 2026-10-05:** the item 87 rule for booleans: only a case variant of
  `true`/`false` or a word one edit away (`ture`, `fals`) gets the boolean message.
  **Verdict 2026-10-05:** fixed: the same rule for `true`/`false`, through one shared helper: `True`, `ture`, `fals`
  get the boolean message; `tomato`, `truthy`, `t` must be quoted. ParseMessageTest; 13 upstream snapshots changed.

- [x] **87. `info` and `inn` are told they are a misspelt float.** Item 81's rule takes any word starting with
  `inf`/`nan`. **Ruling 2026-10-05:** B: only a case variant of `inf`/`nan` (signed or not) or a word one edit
  away from one; a longer word starting with them (`info`, `infinity`) is a string missing its quotes.
  **Verdict 2026-10-05:** fixed: only a case variant of `inf`/`nan`, signed or not, or a word one edit away (a swap of
  two neighbouring letters counts as one) gets the float message; a longer word starting with the keyword (`info`,
  `infinity`) is "string values must be quoted". ParseMessageTest.

- [x] **86. A date error does not name the bad value.** `Date.of(2021, 2, 29)` says
  `expected day between 01 and 28`, without the 29. **Ruling 2026-10-05:** `DatetimeParseError` gains the value (wire
  change), filled by `of` and by the parser, so the message reads `invalid date: day 29 is out of range 01 to 28`.
  **Verdict 2026-10-05:** fixed: `DatetimeParseError` carries the value out of range (wire field `value`), filled by
  `of` and the parser; a range error reads `invalid date: day 29 is out of range 01 to 28`. ErrorMessageTest,
  ErrorJsonTest; 35 upstream snapshots changed in their last line.

- [x] **85. Core says "datetime", serialization "date-time".** **Ruling 2026-10-05:** "date-time" everywhere, the
  TOML spec's word; core's messages and type names change.
  **Verdict 2026-10-05:** fixed: "date-time" in every core and CLI message (type names, the part-less
  `DatetimeParseError`); identifiers and wire names unchanged.

- [x] **83. A table's or value's own `toString()` prints the 1.1 forms in a 1.0 document.** Only a whole document
  prints in its version. **Ruling 2026-10-05:** it follows its document's version; TOML 1.1 is the default and 1.0
  is opt-in.
  **Verdict 2026-10-05:** fixed: a table's, array's, inline table's or value's own `toString()` prints in its
  document's version; one stored nowhere prints 1.1. WriteVersionTest.

- [x] **81. A stray word starting with `i` or `n` is told it is a misspelt float.** `a = [I don't belong]` says
  "invalid float, expected `inf`". **Ruling 2026-10-05:** A: only a word that looks like `inf` or `nan` gets that;
  any other word is "string values must be quoted".
  **Verdict 2026-10-05:** fixed: a word gets the float message only when, ignoring case and sign, it starts with
  `inf`/`nan` or is one edit away; any other word is "string values must be quoted". ParseMessageTest; two upstream
  snapshots changed.

- [x] **80. A caller's comment on an element added at an index, or a replaced one, is dropped.** `add(0, 0 # c)` on
  `[1, 2]` prints `[0, 1, 2]`. **Ruling 2026-10-05:** yes, the item 37 rule: `add(index, …)` and `replace` spread a
  one-line array (or inline table) over lines and keep the comment.
  **Verdict 2026-10-05:** fixed: `add(index, value)` and `replace` keep a caller's comment as `add(value)` does; a
  caller's comment on a replacement replaces the comment on that line. CommentPlacementTest.

- [x] **79. Lines a caller's comment adds use `\n` in a CRLF file.** `a = [1, 2]\r\n` plus `3` with `# c` prints
  `a = [\n  1,\n  2,\n  3, # c\n]\r\n`. **Ruling 2026-10-05:** A: the new lines take the file's line ending;
  `makeValue` too.
  **Verdict 2026-10-05:** fixed: the lines a caller's comment spreads an array or inline table over, and those
  `makeValue` writes for a table built in code, end in the document's line ending. CommentPlacementTest.

- [x] **78. A 1.0 document writes `\e` and `\x` in their 1.0 spelling.** `\e[0m \x41` prints `"\u001B[0m A"`.
  **Ruling 2026-10-05:** B: `writeTo` refuses them under 1.0 with `TomlWriteError.NotInToml10`, as it refuses a
  trailing comma. `07:32` keeps printing as `07:32:00`.
  **Ruling 2026-10-05 (2):** `toString()` of such a document prints the 1.0 spelling (`\u001B`), so it reads back
  under 1.0; only `writeTo` refuses.
  **Verdict 2026-10-05:** fixed: under 1.0 `writeTo` refuses a `\e`/`\x` key or string with
  `NotInToml10(keyPath, EscapeEscape/HexEscape)`; `toString()` prints the 1.0 spelling; `07:32` still writes
  `07:32:00`. WriteVersionTest.

- [x] **77. A `.copy()` of an item read from a document keeps that document's comments.** `src["db"].copy()` stored in
  another document brings `# Generated…` and `[db] # db hdr`, glued to the line before; `dst.set("db", src["db"])`
  leaves them. **Ruling 2026-10-05:** handles stay live, with five rules: reading gives a live view; storing stores
  a copy that leaves its source's comments, whatever the source; `.copy()` is exactly what a store would store, so
  `src["db"].copy()` and `src["db"]` land the same; `remove()` hands the item over with its own comments (a move); a
  replaced or removed item is detached. Document the five rules in `core/README.md`.
  **Verdict 2026-10-05:** fixed: `Item.copy()` of an item read from a document leaves that place's same-line comment,
  header comment and comment lines above, and in its own document prints where a direct store would; removed items and
  caller `decor` keep their comments; the five rules are in core/README.md ("Items and their comments") and the
  `Item.copy()` KDoc. HandleRulesTest.

- [x] **57. No readable message for decode, edit, write and datetime errors.** Only `TomlParseError` renders; the
  rest print as data classes. `TypeMismatch` omits the bad value; `UnknownKey` suggests no field name.
  **Ruling 2026-10-05:** every error type gets a readable message
  (`database.pool: expected an integer, found the string "ten"`, `unknown key nmae, did you mean name?`).
  **Progress 2026-10-05:** decode and encode errors have `message` (`TypeMismatch` gains `value`, `UnknownKey`
  `suggestion`). Left: edit, write and datetime errors in core.
  **Verdict 2026-10-05:** fixed: core's edit, write and date-time errors have `message` too, led by the key path,
  reusing the parser's wording for date-time parts and TOML 1.1 features. ErrorMessageTest; serialization's decode and
  encode errors had theirs in wave 1.

- [x] **43. CLI: `--color` does nothing.** Listed on every help page; no escapes with `always`, nor on a terminal.
  **Ruling 2026-10-05:** A: implement colour (`error:` and the caret line coloured, `auto` on a terminal).
  **Ruling 2026-10-05 (2):** no colour. Decline klap's built-in with `builtins { color = false }`, so `--color`
  is no longer offered.
  **Verdict 2026-10-05:** fixed: kotoml declines klap's built-in (`builtins { color = false }`), so `--color` is no
  longer offered and is a usage error. ExitCodeTest.

- [x] **76. A lost write on standard error reads as standard output.** kern reports stdout's failure before
  stderr's, and a failure on stderr alone also counts, so a run whose stdout landed but whose stderr hit a full disk
  exits 1 with `cannot write to standard output`. Saying which stream failed needs kern to report it (kern, klap).
  **Ruling 2026-10-05:** B: each stream answers for itself. kern's `WriteError` says which stream failed, and
  klap reports a standard error failure under its own name (a kern and a klap release).
  **Verdict 2026-10-05:** fixed upstream: kern 0.4.3's `WriteError` names its `stream`, klap 0.5.2 reports a refused
  write as `cannot write to standard output` or `standard error`, and `"stream"` in the `--json` envelope. A test
  in `ExitCodeTest`; native CLI: `> /dev/full` names standard output, `| head` exits 141 silently; build green.

- [x] **74. Document the CLI's JSON mapping.** Datetimes as strings, `nan`/`inf` as strings, fractions cut to 9
  digits, `--json` span offsets in UTF-16 units.
  **Verdict 2026-10-05:** fixed: app/README.md documents the JSON mapping: big integers, nan/inf as strings, RFC 3339
  times, UTF-16 spans.

- [x] **72. Say that a 1.1 parse lets edits write 1.1-only syntax.** `core/README.md`'s versions section only says
  `V1_0` parses stricter.
  **Verdict 2026-10-05:** fixed: core/README.md's versions section documents `doc.version`, that a 1.1 parse lets
  edits write 1.1-only syntax, and `NotInToml10`.

- [x] **71. CLI exit codes and argument shapes.** Exit 1 for a missing key, an unreadable file and invalid TOML
  alike, with no `.a?` or default; `decode` and `encode` take no file; a bare `kotoml` exits 0.
  **Ruling 2026-10-05:** distinct typed errors and exit codes: 3 when nothing is at the path, with `error: no key …`
  on standard error.
  **Pinned:** `ExitCodeTest`, ignored until fixed. The fix replaces the test that expects exit 1.
  **Verdict 2026-10-05:** fixed: a missing key or index exits 3 with `error: no key …`; bare `kotoml` prints help on
  stderr and exits 2; `decode` and `encode` take a file. ExitCodeTest.

- [x] **69. `Value.parse` refuses surrounding whitespace.** `Value.parse("  1  ")` and `Value.parse("\"x\"\n")`.
  **Ruling 2026-10-05:** expected: surrounding whitespace is trimmed.
  **Pinned:** `ValueTextTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: `Value.parse` ignores whitespace and line breaks around the value. ValueTextTest.

- [x] **68. A value's `toString()` includes its decor.** `put`'s old value prints ` "1"`; `removeAt` returns
  `\n    "macros"`.
  **Ruling 2026-10-05:** `toString()` is the spelling only; `decor` stays as the one place for comments and spacing.
  **Pinned:** `ValueTextTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: `toString` of a value, array or inline table is its text without its own decor.
  ValueTextTest.

- [x] **67. No target version for writing.** A document cannot be written as TOML 1.0 (see 32).
  **Ruling 2026-10-05:** `doc.version` settable, starting as the parse version, 1.1 for a new document; `writeTo`
  prints that version's forms.
  **Verdict 2026-10-05:** fixed: `doc.version` is settable, starting as the parse version and V1_1 for a new document;
  `toString` and `writeTo` print that version's forms. WriteVersionTest.

- [x] **64. `toStandardTable` drops every comment.** A multi-line inline table made a standard table loses all its
  comments, while `makeValue` and `isDotted` keep them; `makeValue` on an array of tables drops all comments in 1.1.
  **Ruling 2026-10-05:** expected: comments kept, as `makeValue` and `isDotted` keep them; in 1.0 the comments inside
  are dropped.
  **Pinned:** `CommentPlacementTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: `toStandardTable` and `makeValue` on an array of tables keep comments; in 1.0 the
  comments inside tables go. CommentPlacementTest.

- [x] **63. A handle taken before `set` replaces its key follows the replacement.** `val old = doc["a"]`, then
  `doc.set("a", newTable)`: `old` reads and edits the new table. After `remove` or `edit {}` it is detached.
  **Ruling 2026-10-05:** A: the old handle is detached, as after `remove`.
  **Pinned:** `ValueTextTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: `set`/`put` on a key detach an item taken for it earlier, as `remove` does.
  ValueTextTest; SlotFixesTest moved to the rule.

- [x] **62. Sorting does not reorder header tables.** `root.sortBy { it.first.name }` keeps `[b]` before `[a]`;
  the public `StandardTable.position` reorders only the tables set and is undocumented.
  **Ruling 2026-10-05:** `sort()` orders by key name in plain string order, `sortBy { }` by a custom key; both move
  header tables with the lines above them. Document `position`.
  **Pinned:** `EditLayoutTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: `sort()` orders by key name (`String.compareTo`), `sortBy`/`sortWith` move header
  tables with the lines above them; `position` documented. EditLayoutTest, TableSortTest.

- [x] **60. A missing contextual serializer is `Serializer`, not `NoSerializer`.** And `NoSerializer.type` reads
  `Color (Kotlin reflection is not available)` on the JVM; `NoSerializer` is not in the serialization README.
  **Ruling 2026-10-05:** expected: `NoSerializer(type="Color", path="accent")`, the type's bare name.
  **Pinned:** `DecodeTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: a missing contextual serializer is `NoSerializer(type = bare name, path, position)`.
  DecodeTest.

- [x] **59. `07:32.5` and `07:32:00Z` give `DatetimeParseError(part=null, expected=null)`.** Every other datetime
  error names the part.
  **Ruling 2026-10-05:** expected: part `Time` and what was expected.
  **Pinned:** `ParseMessageTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: `07:32.5` and `07:32:00Z` name part Time and what was expected. ParseMessageTest,
  DatetimeParseTest.

- [x] **58. `TooDeep.keyPath` means something else per call.** DSL tables give the failing key only, arrays and
  `tomlArrayOf` give `[]`, `inlineTableOf` the path inside the value, `writeTo` the path from the root.
  `DuplicateKey` names only the last segment (`[a.b]` twice gives `key=b`).
  **Ruling 2026-10-05:** `TooDeep.keyPath` is the path from the document root, or from the value's own top when not
  stored; `DuplicateKey` carries the full key path (wire change).
  **Verdict 2026-10-05:** fixed: `TooDeep.keyPath` leads from the root, or from the value's own top when stored
  nowhere; `DuplicateKey` carries `keyPath` (wire change). ParseMessageTest, StoreDepthTest, ErrorJsonTest.

- [x] **56. Decode errors carry a key path only.** `TypeMismatch(path=database.pool, …)` has no line or column, and
  no public call maps a path to a place. Should 1.
  **Ruling 2026-10-05:** every located decode error gains an optional position, filled when decoding from text or a
  document.
  **Verdict 2026-10-05:** fixed: located decode errors carry the key's position through the new public `Key.position`.
  ErrorMessageTest.

- [x] **55. CLI errors never name the file, and `check` takes one file.** `kotoml check bad.toml` prints
  `TOML parse error at line 3, column 5`; a loop over files cannot tell which failed. Should 1.
  **Ruling 2026-10-05:** errors start `error: <file>: …`; `check` takes several files and prints `<file>: OK` for each
  when given more than one, exit 1 if any fails; `--json` errors gain `path`.
  **Pinned:** `ExitCodeTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: errors name the file (`error: <file>: …`), `check` takes several files and prints
  `<file>: OK` for each, `--json` errors carry `path`. ExitCodeTest.

- [x] **54. `render()` echoes the whole line.** A 5 MB line gives 10 MB of message; a lone CR or NUL is printed raw
  by the library (the CLI escapes it); the caret width counts UTF-8 bytes and misaligns after tabs.
  **Ruling 2026-10-05:** show about 80 characters around the column, `…` at cut ends; control characters as `\x0D`;
  one caret per character. Only the message is cut, never the data.
  **Pinned:** `ParseMessageTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: `render()` shows about 80 characters around the error with `…`, control characters as
  `\x0D`, one caret per character; the span is unchanged. ParseMessageTest; 102 upstream snapshots regenerated.

- [x] **53. A surrogate or out-of-range `\u` says "expected unicode hexadecimal value".** `"\uD800"`,
  `"\U00110000"`: the digits are hex, the code point is the problem; the caret sits on the `u`.
  **Ruling 2026-10-05:** expected: a new reason for a code point that is not a Unicode scalar value, the caret under
  the whole escape.
  **Pinned:** `ParseMessageTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: a surrogate or out-of-range escape is `InvalidCodePoint` with the caret under the
  whole escape. ParseMessageTest.

- [x] **52. `\x` and `\e` under TOML 1.0 are a syntax error, not `NotInToml10`.** `Toml11Feature` has no escape
  entries though `core/README.md` lists the escapes among what 1.0 refuses.
  **Ruling 2026-10-05:** expected: `NotInToml10` with new `Toml11Feature` entries for `\x` and `\e`.
  **Pinned:** `ParseMessageTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: `\x` and `\e` under 1.0 are `NotInToml10(HexEscape/EscapeEscape)` over the escape.
  ParseMessageTest.

- [x] **51. Numbers apart by a space are told to be quoted.** ✓ `port = 80 80`, `[1, 2 3]`, `[1 2]`, `x = ` say
  "string values must be quoted"; `MissingArrayComma` exists for `["x" "y"]`. Should 1.
  **Ruling 2026-10-05:** expected: `port = 80 80` and `a = true false` give `UnexpectedKeyOrValue` at the second
  token; `[1 2]` `MissingArrayComma`; `x = ` `MissingValue`.
  **Pinned:** `ParseMessageTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: `80 80` and `true false` give `UnexpectedKeyOrValue` at the second token, `[1 2]`
  `MissingArrayComma`, `x = ` `MissingValue`. ParseMessageTest.

- [x] **50. A line break inside a string is reported on the next line.** ✓ `desc = "hello\nworld"` reports line 2,
  "key with no value" or "string values must be quoted". Should 1.
  **Ruling 2026-10-05:** expected: line 1, `InvalidString(Basic)`, as an unclosed string.
  **Pinned:** `ParseMessageTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: a line break in a basic or literal string is `InvalidString` at the end of its line.
  ParseMessageTest.

- [x] **49. Extreme floats print without an exponent.** `1e300` prints 301 digits, `Double.MIN_VALUE` 324,
  `1e21` as `1000000000000000000000.0`. Core and serialization.
  **Ruling 2026-10-05:** exponent form at 1e16 and above or below 1e-5, with the shortest digits that read back
  (`1e300`, `5e-324`); valid in 1.0 and 1.1.
  **Pinned:** `ValueTextTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: floats print the shortest digits that read back, in exponent form at 1e16 and above
  or below 1e-5, in core and serialization. ValueTextTest, ScalarTest.

- [x] **48. Empty parent headers outside the DSL.** `standardTableOf("ruff" to …)` under `tool` and serialization
  of a map of classes print `[tool]\n\n[tool.ruff]`; the DSL omits the empty parent. `encodeInto` adds one to a
  user's file.
  **Ruling 2026-10-05:** a hand-made table holding only tables prints no header of its own (as the DSL); a parsed
  header stays.
  **Pinned:** `EditLayoutTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: a table made in code holding only tables prints no header (core, encode, encodeInto);
  a parsed header stays. EditLayoutTest, serialization EncodeTest.

- [x] **47. A new key in an indented table starts at column 0.** Keys indented by two spaces; a new key is not.
  Multi-line arrays copy their indentation.
  **Ruling 2026-10-05:** expected: the new key takes the indentation of the keys above it.
  **Pinned:** `EditLayoutTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: a key added by hand takes the indentation of the line above it. EditLayoutTest.

- [x] **46. Appending to a one-line array ignores its spacing.** ✓ `[ 1, 2 ]` plus 3 prints `[ 1, 2 , 3]`; `[1,2]`
  prints `[1,2, 3]`; `{a=1}` gets `b = 2`; `[\n  1,\n  2]` gets `2, 3]`.
  **Ruling 2026-10-05:** expected: `[ 1, 2, 3 ]`, `[1,2,3]`, `{a=1, b=2}`, `[\n  1,\n  2,\n  3]`.
  **Pinned:** `EditLayoutTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: an added element or key takes the spacing the array or inline table already has.
  EditLayoutTest.

- [x] **45. Removing a table between blank lines leaves two.** ✓ `[a]…\n\n[b]…\n\n[c]…` minus `b` leaves
  `\n\n\n[c]`; removing the first or last table leaves a blank line at the start or end. Follows the README rule
  (the blank lines above are kept); collapse to one when nothing else is kept between them.
  **Ruling 2026-10-05:** expected: one blank line where two meet with nothing kept between them; none at the start or
  end of the file. A kept comment between keeps both.
  **Pinned:** `EditLayoutTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: removing a table leaves one blank line where two meet and none at the start or end of
  the file; a kept comment keeps both. EditLayoutTest, RemovalPropertyTest model.

- [x] **44. CLI: `get --json` keeps times without seconds.** `07:32` prints `"07:32"`, `1979-05-27T07:32Z` as
  written, not RFC 3339; `decode` adds `:00`, and `get --json` normalises other spellings.
  **Ruling 2026-10-05:** expected: `"07:32:00"`, `"1979-05-27T07:32:00Z"`.
  **Pinned:** `ExitCodeTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: `get --json` prints RFC 3339 times with seconds. ExitCodeTest.

- [x] **42. CLI: `encode` ends with a blank line.** ✓ `{"a":{"type":"integer","value":"1"}}` gives `a = 1\n\n`.
  **Ruling 2026-10-05:** expected: `a = 1\n`.
  **Pinned:** `ExitCodeTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: `encode` prints `a = 1\n` and reads a file argument. ExitCodeTest.

- [x] **41. CLI: a value from a CRLF file prints with `\r\n`.** ✓ `kotoml get -r .t.x` on a CRLF file gives `y\r`
  in `$(…)`. In a mixed file the first line ending is used for every line printed. Goals 2, 15.
  **Ruling 2026-10-05:** expected: a single value ends in `\n`; a table keeps each line's own ending.
  **Pinned:** `ExitCodeTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: a value ends in `\n`, a table keeps each line's own ending. ExitCodeTest, GetTest.

- [x] **38. Moved and copied tables print away from their parent.** ✓ Remove `[a]` and store it under `[b]`:
  `[b.a]` prints above `[b]`. A copy stored in a new table prints under its source and the new parent last as an
  empty header; a copy stored into an existing table goes to the end of the file while a fresh table stays with its
  parent; replacing a table with a copy from the same document moves it to the end and leaves a blank first line.
  Goal 4 (readability).
  **Ruling 2026-10-05:** expected: a moved or copied table prints right after its parent's body; a copy replacing a
  table prints where the replaced one did, with no blank first line.
  **Pinned:** `EditLayoutTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: a moved or copied table prints after its parent's lines and the tables before its
  key; a copy replacing a table takes its position and spacing. EditLayoutTest. Supersedes todo 29's "a copy prints
  after the tables already there" for standard tables (QA S-056).

- [x] **37. A caller's comment after an array element is dropped.** ✓ `[1, 2]` plus `Value.Integer(3)` with
  `Decor(suffix = " # c")` prints `[1, 2, 3]`; the same for an inline table's value. Goal 4 ("a decor a caller
  gives a value is kept").
  **Ruling 2026-10-05:** A: a comment given to an element or an inline table's value makes the structure multi-line,
  one entry per line; in TOML 1.0 an inline table cannot hold it.
  **Pinned:** `CommentPlacementTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: a comment given in an element's or inline value's decor puts a one-line array or
  inline table on lines of its own, after its comma; a 1.0 inline table drops it. CommentPlacementTest.

- [x] **36. A new key prints above a comment-only head.** ✓ `# banner\n\n[db]\nurl = 1\n`, remove `db`, set `x`:
  `x = 1\n# banner\n\n`. Goal 4 ("new content goes below them").
  **Ruling 2026-10-05:** expected: `# banner\n\nx = 1\n`.
  **Pinned:** `CommentPlacementTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: a document emptied to comments keeps them as its head; new content goes below.
  CommentPlacementTest.

- [x] **35. An inline array of tables made `[[bin]]` repeats its key's comment above every element.** ✓
  `# bins\nbin = [{ name = "a" }, { name = "b" }]`, set to the same tables as an `ArrayOfTables`: `# bins` above
  each `[[bin]]`. Goal 4.
  **Ruling 2026-10-05:** expected: `# bins` once, above the first `[[bin]]`.
  **Pinned:** `CommentPlacementTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: the key's comment prints once, above the first `[[bin]]`. CommentPlacementTest.

- [x] **34. A copy from another document takes the comments above its header and its header comment.** ✓
  Source `# Generated, do not edit\n\n[db] # db hdr\nurl = 1\n`, `dst.set("db", src["db"])`: the banner and
  `# db hdr` arrive, glued to the line before. Replacing `[app] # ah` with another document's `[app] # bh` prints
  `# bh` (a table replaced by a table keeps its header comment). `GOALS.md` states the copy rule for any copy,
  `core/README.md` for the same document only. Goal 4.
  **Ruling 2026-10-05:** expected: the copy prints `[db]\nurl = 1` after a blank line, without the banner
  or `# db hdr`; a replaced `[app] # ah` keeps `# ah`. The rule covers copies from any document.
  **Pinned:** `CommentPlacementTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: the copy rule holds for copies from any document: a copy leaves its source's banner,
  header comment and same-line comment; a replaced table keeps its own header comment. CommentPlacementTest.

- [x] **33. `toString()` prints text `writeTo` refuses.** ✓ 80 nested tables, each made dotted: `writeTo` is
  `TooDeep`, `toString()` returns `t80.t79….t1.leaf = 1`, which does not parse. Goal 3.
  **Ruling 2026-10-05:** B: the flip applies and `toString()` prints headers where a dotted line would be too deep.
  **Pinned:** `WriteDepthTest`, ignored until fixed. The fix replaces the test that expects `TooDeep`.
  **Verdict 2026-10-05:** fixed: dotted tables whose lines would nest past the limit print under a header, so
  `toString` equals `writeTo` and reads back. WriteDepthTest.

- [x] **32. A document read as TOML 1.0 writes TOML 1.1.** ✓ `parse("a = 1\n", V1_0)`, set a `LocalTime` of 7:32:
  `t = 07:32`, which a 1.0 parse refuses; `writeTo` succeeds. Same for `trailingComma = true` (`{ x = 1 ,}`).
  The document remembers its version already (`makeValue`). Goal 3.
  **Ruling 2026-10-05:** a 1.0 document prints a time as `07:32:00`; what has no 1.0 form (`trailingComma`) makes
  `writeTo` refuse with a typed error.
  **Pinned:** `ValueTextTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: a 1.0 document prints a time as `07:32:00` and a 1.1 escape in its default spelling;
  a trailing comma, newline or comment in an inline table makes `writeTo` return
  `TomlWriteError.NotInToml10(keyPath, feature)`. ValueTextTest, WriteVersionTest.

- [x] **31. An edit adds a final newline to a file without one.** ✓ `a = 1\nb = 2` with `doc["a"] = 5L` prints
  `a = 5\nb = 2\n` (CRLF files gain `\r\n`); any edit when the last line is a key/value line. Goals 2, 4.
  **Ruling 2026-10-05:** expected: the missing final newline stays, also when a key is added (`a = 1\nb = 2\nc = 3`,
  no newline).
  **Pinned:** `EditLayoutTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: a document read without a final newline prints none after any edit; setting
  `trailing` to text ending in a newline gives it one. EditLayoutTest; about 24 EditTest snapshots and four QA
  scenarios moved to the rule.

- [x] **30. Storing a removed table again prints the comment above its header twice.** ✓
  `x = 1\n\n# about t\n[t]\nk = 1\n`, `val t = doc.remove("t")!!; doc.set("t", t)`: `# about t` prints twice,
  once more per round. Removal keeps the lines above in place and the removed item still carries them. Hits every
  rename, move and split. Goal 4.
  **Ruling 2026-10-05:** expected: `# about t` once, above `[t]`, however many rounds.
  **Pinned:** `CommentPlacementTest`, ignored until fixed.
  **Verdict 2026-10-05:** fixed: a removed item no longer carries the lines kept above it, so stored again they print
  once (also CRLF). CommentPlacementTest.

- [x] **40. CLI: a failed write to standard output exits 0.** ✓ `kotoml get . f.toml > /dev/full` exits 0 with
  nothing on standard error; jq exits 2. Goal 15.
  **Ruling 2026-10-05:** the cause is kern's native terminal, which has no `writeFailed` (it assumes SIGPIPE kills
  first); klap already exits non-zero when `writeErrored()`. Fix in kern (`fflush` + `ferror`); a readable message is
  klap's.
  **Pinned:** `ExitCodeTest` checks a failed write exits non-zero, which holds through klap; the gap is kern's
  native terminal.
  **Progress 2026-10-05:** kern 0.4.1 reports the failed write; the native CLI now exits 141 on `/dev/full` and a
  closed stdout. Left: klap's message, `error: cannot write to standard output`.
  **Verdict 2026-10-05:** fixed upstream. kern 0.4.2's terminal says why a write failed (`writeResult()`: a closed
  pipe, a refused write, or on the JVM an unknown one); klap 0.5.1 turns a refused write into exit 1 with
  `error: cannot write to standard output: <reason>` and a closed pipe into a silent 141, as `head` users expect.
  Native CLI checked by hand: `> /dev/full` and a closed stdout exit 1 with the message, `| head -1` exits 141
  with nothing on stderr. Two tests in `ExitCodeTest`; `app/README.md` exit codes; build green.

- [x] **28. An inline table put back into braces loses the comments on its first key's line.** Owner ruling
  2026-10-05: an `isDotted` change gives a table a new layout, so its comments may go, but for the lines above its
  key. One heavy-run case still breaks that (`CommentPropertyTest` at 25 steps and 2000 seeds, hand-written 0 seed
  1660): an inline table printed on dotted lines, `"new key"."é"…`, holds the comments printed above that line on
  its first entry's key; made an inline table again, it prints `"new key" = { … }` and those comments, visually
  above its key, are gone. Moving them onto the holding key when it goes back into braces would keep them. Goal 4.
  **Verdict 2026-10-05:** fixed with todo 29's follow-up: a dotted key printed inside braces now starts its first
  line with the holding key's own spacing, the same rule the table-lines case uses, which also kept the comment
  ending the line before a nested inline table made dotted (32 heavy-run cases). `CommentPropertyTest` and
  `EditPropertyTest` are clean at 25 steps, 2000 seeds per hand-written document and 100 per suite file.


- [x] **29. A copied table prints where its source printed.** A table or array of tables copied to another key keeps
  the source's print positions, so `[b]` copied from `[a]` over a value key above it prints before `[a]`, and a copied
  `[[y]]` interleaves with the `[[x]]` it came from (scenarios S-056, S-057). Valid and the same data, but a copy
  should print after the tables already there. Needs the document's highest position when storing, without making
  repeated stores quadratic. Goal 4.
  **Verdict 2026-10-05:** fixed. A table or array of tables copied to a key elsewhere in its own document now gets
  print positions after every table already there, its own tables keeping their order, and takes the default
  spacing of a new header; a table copied into an array of tables drops its positions and prints after the element
  before it. Copies into another document or reorders inside an array keep everything. S-056 and S-057 pass, which
  makes all 330 QA scenarios firm.


- [x] **27. A multi-line inline table made dotted loses the comments between its entries.** TOML 1.1:
  `t = {\n  # about c\n  c = 3, # three\n  d = 4, # four\n  # end\n}` with `isDotted = true` prints the lines
  above each key but drops `# three`, `# four` (held at the start of the next key's prefix, before its first break)
  and `# end` (the table's trailing text); after `remove("c")` the kept `# about c` prints above `# about t`. Each
  needs comments moved to the end of the line before or after the group. Goal 4.
  **Verdict 2026-10-05:** fixed with goal 4's property test. On a table's lines a dotted inline table now ends the
  line
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
  **Verdict 2026-10-05:** fixed. `TomlDocument.parse`, `Value.parse`, `Key.parse` and `Key.parsePath` refuse a
  `String`
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
