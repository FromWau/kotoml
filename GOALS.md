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
4. **An edit touches only what it changes.** Comments and formatting are kept, except:
   - a comment inside a structure whose key is removed or replaced goes with it, the comments between the lines of a
     removed dotted-key table included; a value replaced by a table takes the comment above it to its header;
   - a removed key's same-line comment goes with it, while a replaced value keeps its spacing and same-line comment;
   - making a table or an inline table dotted, or dotted back into a header or braces, replaces its layout: its
     comments may go, but for the lines above its key;
   - in an array of tables, removing elements drops the comments between them and keeps the one above the first
     and the one after the last; one left empty prints `key = []` under the comment that was above its first header;
   - dotted keys turned into a header put the comment above their first line above the header;
   - a copy stored elsewhere takes only the comments inside it, not its source's same-line comment, header comment
     or the comment lines above its header; a decor a caller gives a value is kept;
   - in a document that is only comments, new content goes below them; a new header gets its blank line above the
     comment that introduces it.

   Check: `CommentPropertyTest` applies random comment-keeping edits (adding, replacing a value with a value, removing a
   value, sorting, `isDotted` and `isImplicit`) and checks that every comment survives but those the rules above let
   go; `CommentScenariosTest` runs the 330 scenarios three QA agents wrote from these rules (`qa/comment-scenarios`),
   the firm ones byte for byte; `RemovalCommentScopeTest`, `RemovalKeepsCommentsTest`, `ReplacementCommentScopeTest`,
   `HeaderCommentsTest`, `RemovalPropertyTest`. Gap: one case in heavy runs, an inline table put back into braces (todo 28).

### Robustness

5. **Nothing throws.** Every public call returns its failure as a typed value, whatever the input: parsing, editing,
   decoding, encoding and the CLI. Only internal checks of conditions that cannot happen may throw.
   Check: review every `throw`, `!!`, `require`, `check` and `error` in main code. Gap: no fuzzing.
6. **Bounded depth on every target.** Arrays, inline tables and the dots of a line's keys count toward one nesting
   limit, `TomlDocument.NESTING_LIMIT`; a table header's path is bounded by the same limit on its own. So no input
   overflows the stack, and deeper input is a typed parse error. What kotoml builds stays within the same limit:
   every call that stores refuses to nest deeper with `TomlEditError.TooDeep` and stores nothing, `writeTo` refuses
   a document that `isDotted` changes made deeper with `TomlWriteError.TooDeep`, and serialization refuses a model
   nested that deep with `TomlEncodeError.TooDeep`.
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
   `kotlinx-io-core`; anything new in a public signature needs a reason.
   Check: `core/build.gradle.kts`.
4. **Docs are written for consumers.** Every public declaration has KDoc a caller can act on, and the README is
   organised by task.
   Check: review.
5. **No performance regressions.** Gap: there is no benchmark yet, so no number to hold.
