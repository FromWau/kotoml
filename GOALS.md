# Goals

What kotoml promises, written for reviewers and QA. A **must** goal is a release blocker: a failing one is a bug.
A **should** goal is a quality target that may be traded off, with the trade written down.

Each goal names how it is checked. A **gap** marks a goal that no automated check covers yet; test it by hand and
treat closing the gap as open work. Open items live in [todo.md](todo.md).

The goals cover what a caller can do by accident in normal use. Deliberate misuse is not a defect: a caller's own
exception thrown inside a builder block, catching the library's exceptions inside one, an array added into itself,
or an endless stream piped into the CLI.

Run every automated check with `./gradlew clean build --rerun-tasks`: all modules on the JVM and linuxX64, and the
official toml-test runner for TOML 1.0 and 1.1.

## Must

### Correctness

1. **Full TOML compliance.** Every valid toml-test file of TOML 1.0 and 1.1 decodes and encodes to the same values,
   and every invalid file is refused.
   Check: `SuiteTest`, and toml-test's own runner against the native `kotoml` binary in `./gradlew check`.
2. **Lossless round trip.** A document that is not changed prints back byte for byte: comments, blank lines,
   spacing, key spelling, the order dotted keys were read in, each line's ending, a byte order mark, a missing final
   newline.
   Check: `SuiteTest` over every valid file, `PreservationTest`.
3. **Never invalid TOML.** No sequence of public calls produces text that does not parse back. Text that UTF-8 cannot
   write, such as half of a surrogate pair, is a typed error when written, never broken output. Raw formatting text
   a caller writes itself (`Decor`, `trailing`, a key's decor) is the caller's to keep valid.
   Check: `EditPropertyTest` applies random sequences of public edits to every valid toml-test file and to
   hand-written documents, and reads each step's output back as the data the document holds; `EditOutputTest` pins
   what it found. `RemovalPropertyTest` checks removal byte for byte, `PrintingFixesTest` covers `NotUnicode`.
4. **An edit touches only what it changes.** A comment belongs to the item directly below it, with no blank line
   between, or to the item on its line; comment lines set apart by a blank line belong to no item and stay where they
   are. An item is a key/value, a `[t]` or `[[a]]` header, an array element or an inline-table entry, and inside a
   table or array the same rule applies again. Where that leaves a doubt:
   - a comment after an opening bracket (`arr = [ # open`) belongs to the item on that line, `arr`;
   - of several items on one line, the last owns the line's comment (`1, 2, # pair` is `2`'s);
   - a comment between a value and its comma, or on a line holding only the comma, belongs to the value before it;
   - a comment directly above a header belongs to that header, also when it ends the table before;
   - a comment with no item below it (before `]` or `}`, at the end of the file) belongs to no item, and one inside a
     container goes only when the container goes;
   - a comment above a dotted group's first line (`# about a` above `a.b = 1`) belongs to the group and to that line.

   Comments and formatting are kept, except:
   - an item's comments move, copy and are removed with it; removing an item that holds comments, its own or those
     inside it, returns `CommentsWouldBeLost` and changes nothing unless forced, and removing what is not there
     returns `NotFound`; `clear` follows the same rule. A removal leaves no blank line dangling at the start or end of
     the file;
   - an array of tables left empty prints `key = []` under the comments that were above its first header, attached
     and free, since the array is still there;
   - a store or copy carries the item's comments, a table's header comments included; a bare `Value` carries only its
     same-line comment, also one stored after its comma in braces, the lines above belonging to its key. Comment
     lines below an array element that comes to stand on a key/value line print above the key, so they still belong
     to it when read back;
   - replacing keeps the slot's comments; comments the new item or value brings from a document fill only the places
     where the slot has none (its lines above, its same-line comment), while a comment the caller sets in the `decor` of
     a value not yet stored takes its place;
   - a store where the target cannot hold the item's comments (an inline table of TOML 1.0) returns
     `CommentsWouldBeLost` and changes nothing unless forced; braces on one line that can spread go across lines.
     Every store returns its `Result` and has no `[]=` form, so no refusal goes unseen; `doc["t"].set("v", value)`
     stores under a parent read by indexing, a missing parent giving `NotFound`;
   - a layout change (`setLayout`: a header, dotted keys, an inline table on one line or across lines) keeps every
     comment and blank line the new layout can hold, and when it cannot hold a comment returns `CommentsWouldBeLost`
     and changes nothing, unless forced. Forced, it drops only what the new layout cannot hold: dotted keys keep the
     lines above the key and each value's same-line comment; across lines every comment stays; on one line the header
     comment stands after `}` and the comments inside the braces go; a header keeps every comment, the one after `{`,
     or else after `}`, on the header. A header layout and a multi-line one reach the tables inside. Blank lines are
     spacing, not content: one line drops them without an error, and blank lines between `[[a]]` headers are spacing
     the braces lay out themselves. No blank line is left dangling at the end of the file;
   - `setVersion` converts what TOML 1.0 cannot spell under the same comment rule, and under 1.0 an edit that would add
     1.1-only syntax is refused with `NotInToml10`;
   - dotted keys turned into a header put the comment above their first line above the header and keep the comment
     lines between their lines;
   - in a document that is only comments, new content goes below them; a new header gets its blank line above the
     comment that introduces it, and the header that opened the file gets one once key/value lines print above it;
   - sorting and reordering move each item with its comments, while free comments and blank lines stay in place; a
     dotted group's comment stays above the group's first line.

   Check: `CommentPropertyTest` applies random comment-keeping edits (adding, replacing a value with a value, removing a
   value, sorting, `setLayout`, `setImplicit` and `setVersion`) and checks that every comment survives but those the
   rules above let go; `CommentScenariosTest` runs the 330 scenarios three QA agents wrote from these rules
   (`qa/comment-scenarios`), the firm ones byte for byte; `RemovalCommentScopeTest`, `RemovalKeepsCommentsTest`,
   `ReplacementCommentScopeTest`, `HeaderCommentsTest`, `RemovalPropertyTest`.

### Robustness

5. **Nothing throws.** Every public call returns its failure as a typed value, whatever the input: parsing, editing,
   decoding, encoding and the CLI. Only internal checks of conditions that cannot happen may throw.
   Check: review every `throw`, `!!`, `require`, `check` and `error` in main code. Gap: no fuzzing.
6. **Bounded depth on every target.** Arrays, inline tables and the dots of a line's keys count toward one nesting
   limit, `TomlDocument.NESTING_LIMIT`; a table header's path is bounded by the same limit on its own. So no input
   overflows the stack, and deeper input is a typed parse error. What kotoml builds stays within the same limit: every
   call that stores refuses to nest deeper with `TomlEditError.TooDeep` and stores nothing, a dotted layout that would
   nest too deep prints under headers instead, `writeTo` keeps `TomlWriteError.TooDeep` as a backstop, and serialization
   refuses a model nested that deep with `TomlEncodeError.TooDeep`.
   Check: `NestingLimitTest`, `NestingTest`, `StoreDepthTest`, `WriteDepthTest`, serialization's `DepthTest`, and
   `TaggedJsonTest` for the CLI's `encode`.
7. **Edits are all or nothing.** `doc.edit { }` either applies completely or leaves the document byte for byte as it
   was.
   Check: `TomlDslTest`.

### API contract

8. **Typed, stable errors.** Every error is a `@Serializable` type whose wire name and fields are stable; renaming
   one is a breaking change. A parse error's `span` and `position`, when it has them, are `String` indices and a line
   and column.
   Check: `WireSchemaTest` in core, serialization and app pins every error type's wire names and fields;
   `ErrorJsonTest` checks whole encodings.
9. **One way to do each thing.** An `Item` always holds a value, a table or an array of tables. Writing never deletes a
   key. Storing into a table or an array stores a copy, so two places never share a slot; only a wrapper made from an
   object (`Item(…)`, `Value.Array(…)`, `TomlDocument(root)`) holds that object itself. Indexing a table or an item
   returns a `Result` that stops at the first missing key.
   Check: `IndexingTest`, `SlotSemanticsTest`, `SlotFixesTest`.
10. **Kotlin-first.** The public API uses Kotlin's vocabulary (collections, `xOrNull`, operators), not upstream's Rust
    names.
    Check: review.

### Docs

11. *Retired 2026-10-05.* Upstream parity is no longer a goal: kotoml's own tests, the ported ones included, pin its
    behaviour. The number stays so that references to the goals below keep their meaning.
12. **Docs are true.** Every README and KDoc example is mirrored by a test, and every behaviour the docs state is
    pinned by one.
    Check: `EditDocTest` and serialization's `ReadmeTest`; review new prose against the tests.

### Platforms and modules

13. **The same behaviour on every target.** Tests live in `commonTest`, except those of platform code such as the
    CLI's file access (`ReadFailureTest` on native, `PermissionTest` on the JVM).
    Check: every common test runs on the JVM and linuxX64. Gap: Android, Windows and Apple targets are compiled, not
    tested.
14. **Serialization.** A model round-trips through TOML. `encodeInto` keeps the document's formatting. A nullable
    type is refused. Every failure is a typed `Result`.
    Check: `EncodeIntoFixesTest`, `ListMergeTest`, `NullabilityTest`, `ReadmeTest`.
15. **CLI.** Exit 0 on success, 1 for errors about the data, 2 for usage errors. `--json` prints errors as JSON. No
    input crashes it, including invalid UTF-8 and deep nesting.
    Check: `ExitCodeTest` runs whole invocations for every exit code and `check --json`'s output; `CheckTest`,
    `InputTest`, `TaggedJsonTest`, `ReadFailureTest` (native only).

## Should

1. **Errors say what and where.** A message names the problem, the key and the place (line, column and a caret), and
   an unknown enum value or subtype suggests the closest name.
   Check: the ported error snapshots, `DecodeSuggestionTest`.
2. **Common tasks are short.** Reading a nested value is one chain; building or editing nested content is one
   builder block.
   Check: review new API against the README's examples.
3. **A small dependency surface.** Core depends only on kern's `result`, `kotlinx-serialization-core` and
   `kotlinx-io-core`, plus kern's `fuzzy` internally for its suggestions; anything new in a public signature needs a
   reason.
   Check: `core/build.gradle.kts`.
4. **Docs are written for consumers.** Every public declaration has KDoc a caller can act on, and the README is
   organised by task.
   Check: review.
5. **No performance regressions.** Gap: there is no benchmark yet, so no number to hold.
