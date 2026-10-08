# Comment scenarios: syntax corners

Written by an independent QA agent from the owner's rules and the public README only (no kotoml source,
tests or runs). Emphasis: places where comments hide in TOML syntax.

Conventions for headers that the fenced blocks cannot show:
- `line endings: CRLF` means every line of input and expected ends in `\r\n`.
- `BOM` means input and expected start with U+FEFF.
- `input has no final newline` means the input's last line has no line ending; the expected block shows the
  output exactly, and it ends with a line ending unless the header says otherwise.
- `doc` is `TomlDocument.parse(input).assertSuccess()` (TOML 1.1 by default); `TomlVersion.V1_0` is passed
  where the header says `version: 1.0` and the input needs it.

## `#` inside strings is not a comment

### C-001: a `#` inside a basic string survives an edit of another key
rule: 1   version: 1.0
```toml input
a = "x # not a comment" # real
b = 1
```
```kotlin edit
doc["b"] = 2L
```
```toml expected
a = "x # not a comment" # real
b = 2
```

### C-002: replacing a basic string holding `#` keeps only the real same-line comment
rule: 3   version: 1.0
```toml input
a = "x # y" # real
```
```kotlin edit
doc["a"] = "z"
```
```toml expected
a = "z" # real
```

### C-003: replacing a literal string holding `#` keeps the real comment
rule: 3   version: 1.0
```toml input
p = 'C:\#dir' # path
```
```kotlin edit
doc["p"] = "D"
```
```toml expected
p = "D" # path
```

### C-004: removing a multi-line basic string whose lines start with `#` takes the comment above
rule: 2   version: 1.0
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
# above
s = """
# not a comment
"""
t = 1
```
```kotlin edit
doc.remove("s")
```
```toml expected
t = 1
```

### C-005: replacing a multi-line literal string keeps the comment after its closing quotes
rule: 3   version: 1.0
```toml input
s = '''
line # x
''' # trailing
```
```kotlin edit
doc["s"] = "y"
```
```toml expected
s = "y" # trailing
```

### C-006: escaped quotes and `#` in a string, value replaced, comment kept
rule: 3   version: 1.0
```toml input
a = "say \"#hi\"" # c
```
```kotlin edit
doc["a"] = "bye"
```
```toml expected
a = "bye" # c
```

### C-007: a string ending in `#` with a comment glued on, value replaced
rule: 3   version: 1.0
```toml input
a = 'x#'#c
```
```kotlin edit
doc["a"] = "z"
```
```toml expected
a = "z"#c
```

### C-008: an array element that is a multi-line string with `#` lines is removed with its comment
rule: 6   version: 1.0
```toml input
a = [
  """
# not a comment
""", # real
  2,
]
```
```kotlin edit
doc["a"].assertSuccess().arrayOrNull!!.removeAt(0)
```
```toml expected
a = [
  2,
]
```

### C-009: a comment holding brackets and quotes survives an array append
rule: 1   version: 1.0
```toml input
a = [1] # ] [ " '
```
```kotlin edit
doc["a"].assertSuccess().arrayOrNull!!.add(Value.Integer(2L))
```
```toml expected
a = [1, 2] # ] [ " '
```

## Comments with no space, tabs and indentation

### C-010: a comment with no space before it keeps its spacing on value replace
rule: 3   version: 1.0
```toml input
a = 1#c
b = 2
```
```kotlin edit
doc["a"] = 10L
```
```toml expected
a = 10#c
b = 2
```

### C-011: tabs before a comment are kept on value replace
rule: 3   version: 1.0
```toml input
	a = 1	# tabbed
```
```kotlin edit
doc["a"] = 2L
```
```toml expected
	a = 2	# tabbed
```

### C-012: an indented comment above a removed key goes with it
rule: 2   version: 1.0
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
[t]
	# about a
	a = 1
	b = 2
```
```kotlin edit
doc["t"].assertSuccess().standardTableOrNull!!.remove("a")
```
```toml expected
[t]
	b = 2
```

### C-013: a comment indented deeper than its key goes with the key below it
rule: 2   version: 1.0
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
a = 1
        # deep note
b = 2
c = 3
```
```kotlin edit
doc.remove("b")
```
```toml expected
a = 1
c = 3
```

### C-014: a removed key's no-space same-line comment goes with it
rule: 2   version: 1.0
```toml input
a = 1#gone
b = 2#stays
```
```kotlin edit
doc.remove("a")
```
```toml expected
b = 2#stays
```

## Comments after headers

### C-015: a no-space header comment survives adding a key
rule: 1   version: 1.0
```toml input
[t]# c
a = 1
```
```kotlin edit
doc["t"].assertSuccess().standardTableOrNull!!.set("b", Value.Integer(2L))
```
```toml expected
[t]# c
a = 1
b = 2
```

### C-016: a header comment survives removing the table's only key
rule: 1, 2   version: 1.0
```toml input
[t] # header note
a = 1 # one
```
```kotlin edit
doc["t"].assertSuccess().standardTableOrNull!!.remove("a")
```
```toml expected
[t] # header note
```

### C-017: a header with spaces around dots keeps its spelling and comment when a key is added
rule: 1   version: 1.0
```toml input
[ a . b ] # hdr
x = 1
```
```kotlin edit
doc["a"]["b"]["y"] = 2L
```
```toml expected
[ a . b ] # hdr
x = 1
y = 2
```

### C-018: a quoted header containing `#` and a real comment survives a value replace
rule: 1, 3   version: 1.0
```toml input
["x#y"] # real
k = 1 # one
```
```kotlin edit
doc["x#y"]["k"] = 2L
```
```toml expected
["x#y"] # real
k = 2 # one
```

### C-019: removing an array-of-tables element with a glued header comment takes the comment above its header
rule: 5, 2   version: 1.0
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
# top
[[x]]# first
n = 1
# between
[[x]]# second
n = 2
```
```kotlin edit
doc["x"].assertSuccess().arrayOfTablesOrNull!!.removeAt(0)
```
```toml expected
# between
[[x]]# second
n = 2
```

### C-020: removing the last array-of-tables element takes the comment above its header
rule: 5, 2   version: 1.0
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
# top
[[x]]# first
n = 1
# between
[[x]]# second
n = 2
```
```kotlin edit
doc["x"].assertSuccess().arrayOfTablesOrNull!!.removeAt(1)
```
```toml expected
# top
[[x]]# first
n = 1
```

### C-021: a spaced `[[ x ]]` header keeps its spelling and comment when a middle element goes
rule: 5   version: 1.0
```toml input
[[ x ]] # a
n = 1
[[ x ]] # b
n = 2
[[ x ]] # c
n = 3
```
```kotlin edit
doc["x"].assertSuccess().arrayOfTablesOrNull!!.removeAt(1)
```
```toml expected
[[ x ]] # a
n = 1
[[ x ]] # c
n = 3
```

## Arrays of tables emptied and extended

### C-022: an array of tables emptied one table at a time keeps the comment above its last first header
rule: 5, 7   version: 1.0
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
# top
[[x]]
n = 1
# mid
[[x]]
n = 2
# tail
```
```kotlin edit
val aot = doc["x"].assertSuccess().arrayOfTablesOrNull!!
aot.removeAt(0)
aot.removeAt(0)
```
```toml expected
# mid
x = []
# tail
```

### C-023: an emptied array of tables after another table must not land inside that table
rule: 7, 10, 2   version: 1.0
settled: owner ruling 2026-10-05 (Q1): the comment above an emptied or replaced array of tables moves up with its new line.
```toml input
[t]
b = 2

# top
[[x]]
n = 1
```
```kotlin edit
doc["x"].assertSuccess().arrayOfTablesOrNull!!.removeAt(0)
```
```toml expected

# top
x = []

[t]
b = 2
```

### C-024: an emptied array of tables after root keys
rule: 7, 2   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a = 1

# top
[[x]]
n = 1

[t]
b = 2
```
```kotlin edit
doc["x"].assertSuccess().arrayOfTablesOrNull!!.removeAt(0)
```
```toml expected
a = 1

# top
x = []

[t]
b = 2
```

### C-025: appending to an array of tables keeps the last element's same-line comment and the trailing comment
rule: 1, 5   version: 1.0
settled: owner ruling 2026-10-05 (Q10): a comment at the end of the file stays last when a table is appended.
```toml input
[[x]]
n = 1 # one
# tail
```
```kotlin edit
doc["x"].assertSuccess().arrayOfTablesOrNull!!.add(standardTableOf("n" to Item(Value.Integer(2L))).assertSuccess())
```
```toml expected
[[x]]
n = 1 # one

[[x]]
n = 2
# tail
```

### C-026: removing an array of tables whole takes the comments above its headers
rule: 2, 5   version: 1.0
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
a = 1
# about x
[[x]] # first
n = 1
# second x
[[x]] # second
n = 2
```
```kotlin edit
doc.remove("x")
```
```toml expected
a = 1
```

## Multi-line arrays

### C-027: a comment after `[` stays when the first element is removed
rule: 6   version: 1.0
```toml input
a = [ # first line
  1,
  2,
]
```
```kotlin edit
doc["a"].assertSuccess().arrayOrNull!!.removeAt(0)
```
```toml expected
a = [ # first line
  2,
]
```

### C-028: removing a middle element takes its comma and same-line comment
rule: 6   version: 1.0
```toml input
a = [
  1, # one
  2, # two
  3, # three
]
```
```kotlin edit
doc["a"].assertSuccess().arrayOrNull!!.removeAt(1)
```
```toml expected
a = [
  1, # one
  3, # three
]
```

### C-029: removing the last element (trailing comma) takes its same-line comment
rule: 6   version: 1.0
```toml input
a = [
  1, # one
  2, # two
  3, # three
]
```
```kotlin edit
doc["a"].assertSuccess().arrayOrNull!!.removeAt(2)
```
```toml expected
a = [
  1, # one
  2, # two
]
```

### C-030: a comment above a removed element goes with it
rule: 6   version: 1.0
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
a = [
  1,
  # about two
  2,
  3,
]
```
```kotlin edit
doc["a"].assertSuccess().arrayOrNull!!.removeAt(1)
```
```toml expected
a = [
  1,
  3,
]
```

### C-031: a comment above the removed last element goes with it
rule: 6   version: 1.0
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
a = [
  1,
  # about two
  2,
]
```
```kotlin edit
doc["a"].assertSuccess().arrayOrNull!!.removeAt(1)
```
```toml expected
a = [
  1,
]
```

### C-032: removing the last element that has no trailing comma but a comment
rule: 6, 10   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a = [
  1,
  2 # two
]
```
```kotlin edit
doc["a"].assertSuccess().arrayOrNull!!.removeAt(1)
```
```toml expected
a = [
  1,
]
```

### C-033: appending after a last element with a comment and no trailing comma inserts the comma before the comment
rule: 6, 10   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a = [
  1,
  2 # two
]
```
```kotlin edit
doc["a"].assertSuccess().arrayOrNull!!.add(Value.Integer(3L))
```
```toml expected
a = [
  1,
  2, # two
  3
]
```

### C-034: appending to a multi-line array with trailing commas goes on its own line below the last comment
rule: 6   version: 1.0
```toml input
a = [
  1, # one
  2, # two
]
```
```kotlin edit
doc["a"].assertSuccess().arrayOrNull!!.add(Value.Integer(3L))
```
```toml expected
a = [
  1, # one
  2, # two
  3,
]
```

### C-035: appending when a comment sits before `]`
rule: 6   version: 1.0
settled: owner ruling 2026-10-05 (Q8): a new element goes below the comment lines before `]`, indented like the elements.
```toml input
a = [
  1,
  # end of list
]
```
```kotlin edit
doc["a"].assertSuccess().arrayOrNull!!.add(Value.Integer(2L))
```
```toml expected
a = [
  1,
  # end of list
  2,
]
```

### C-036: inserting at index 0 below a comment after `[`
rule: 6   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a = [ # list
  1,
]
```
```kotlin edit
doc["a"].assertSuccess().arrayOrNull!!.add(0, Value.Integer(0L))
```
```toml expected
a = [ # list
  0,
  1,
]
```

### C-037: replacing an element keeps its same-line comment
rule: 3, 6   version: 1.0
```toml input
a = [
  1, # one
  2, # two
]
```
```kotlin edit
doc["a"].assertSuccess().arrayOrNull!!.replace(0, Value.Integer(9L))
```
```toml expected
a = [
  9, # one
  2, # two
]
```

### C-038: replacing the last element with no trailing comma keeps its comment and the `]`
rule: 3, 10   version: 1.0
```toml input
a = [
  1,
  2 # two
]
```
```kotlin edit
doc["a"].assertSuccess().arrayOrNull!!.replace(1, Value.String("b"))
```
```toml expected
a = [
  1,
  "b" # two
]
```

### C-039: clearing an array keeps the comment after `[` and drops the elements' own comments
rule: 6   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a = [ # c
  1, # one
  2,
]
```
```kotlin edit
doc["a"].assertSuccess().arrayOrNull!!.clear()
```
```toml expected
a = [ # c
]
```

### C-040: appending to an empty array that only holds a comment
rule: 6   version: 1.0
settled: owner ruling 2026-10-05 (Q8): a new element goes below the comment lines before `]`, indented like the elements.
```toml input
a = [
  # nothing yet
]
```
```kotlin edit
doc["a"].assertSuccess().arrayOrNull!!.add(Value.Integer(1L))
```
```toml expected
a = [
  # nothing yet
  1
]
```

### C-041: a single-line array keeps its comment through append, remove and clear
rule: 1   version: 1.0
```toml input
a = [1, 2] # c
```
```kotlin edit
val arr = doc["a"].assertSuccess().arrayOrNull!!
arr.add(Value.Integer(3L))
arr.removeAt(0)
```
```toml expected
a = [2, 3] # c
```

### C-042: removing a whole multi-line array key takes every comment inside it and the one above
rule: 2   version: 1.0
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
# list of things
a = [ # open
  1, # one
  # above two
  2,
] # close
b = 1
```
```kotlin edit
doc.remove("a")
```
```toml expected
b = 1
```

### C-043: replacing a multi-line array with a scalar keeps the comments above and after it
rule: 3   version: 1.0
```toml input
# above
a = [
  1, # one
] # after
```
```kotlin edit
doc["a"] = 5L
```
```toml expected
# above
a = 5 # after
```

### C-044: a nested array element with its comment is removed from the outer array
rule: 6   version: 1.0
```toml input
a = [
  [1, 2], # pair
  [3], # single
]
```
```kotlin edit
doc["a"].assertSuccess().arrayOrNull!!.removeAt(0)
```
```toml expected
a = [
  [3], # single
]
```

### C-045: editing an inner array keeps the outer array's comments
rule: 1   version: 1.0
```toml input
a = [
  [1, 2], # pair
  [3], # single
]
```
```kotlin edit
doc["a"].assertSuccess().arrayOrNull!![1].assertSuccess().arrayOrNull!!.add(Value.Integer(4L))
```
```toml expected
a = [
  [1, 2], # pair
  [3, 4], # single
]
```

### C-046: tab indentation in a multi-line array is reused for an appended element
rule: 6   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a = [
	1,	# one
]
```
```kotlin edit
doc["a"].assertSuccess().arrayOrNull!!.add(Value.Integer(2L))
```
```toml expected
a = [
	1,	# one
	2,
]
```

### C-047: several elements on one line, the line's comment belongs to the last one
rule: 6   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a = [
  1, 2, # pair
  3,
]
```
```kotlin edit
doc["a"].assertSuccess().arrayOrNull!!.removeAt(0)
```
```toml expected
a = [
  2, # pair
  3,
]
```

### C-048: a value with a same-line comment copied into an array on one line spreads it across lines
rule: 10   version: 1.0
settled: owner ruling 2026-10-08: a stored item brings its own comments (GOALS.md goal 4).
```toml input
v = 1 # one
a = [2]
```
```kotlin edit
val v = doc["v"].assertSuccess().valueOrNull!!
doc["a"].assertSuccess().arrayOrNull!!.add(v)
```
```toml expected
v = 1 # one
a = [
  2,
  1, # one
]
```

### C-049: a value with a same-line comment refused by an inline table of TOML 1.0 leaves it unchanged
rule: 10   version: 1.0
settled: owner ruling 2026-10-08: a store the target cannot hold is refused.
```toml input
v = 1 # one
t = { x = 0 }
```
```kotlin edit
val v = doc["v"].assertSuccess().valueOrNull!!
doc["t"].assertSuccess().inlineTableOrNull!!.set("k", v)
```
```toml expected
v = 1 # one
t = { x = 0 }
```

### C-050: a multi-line array copied to another key keeps working when placed as the last value
rule: 10, 1   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a = [
  1, # one
]
```
```kotlin edit
val v = doc["a"].assertSuccess().valueOrNull!!
doc["b"] = v
```
```toml expected
a = [
  1, # one
]
b = [
  1, # one
]
```

## TOML 1.1 inline tables with newlines and comments

### C-051: removing a key of a multi-line inline table takes its same-line comment
rule: 2   version: 1.1
```toml input
t = {
  a = 1, # one
  b = 2, # two
}
```
```kotlin edit
doc["t"].assertSuccess().inlineTableOrNull!!.remove("a")
```
```toml expected
t = {
  b = 2, # two
}
```

### C-052: a comment above a removed inline-table key goes with it
rule: 2   version: 1.1
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
t = {
  # about a
  a = 1,
  b = 2,
}
```
```kotlin edit
doc["t"].assertSuccess().inlineTableOrNull!!.remove("a")
```
```toml expected
t = {
  b = 2,
}
```

### C-053: replacing a value in a multi-line inline table keeps its comment
rule: 3   version: 1.1
```toml input
t = {
  a = 1, # one
  b = 2, # two
}
```
```kotlin edit
doc["t"].assertSuccess().inlineTableOrNull!!.set("a", Value.Integer(9L))
```
```toml expected
t = {
  a = 9, # one
  b = 2, # two
}
```

### C-054: adding a key to a multi-line inline table puts it on its own line
rule: 1, 6   version: 1.1
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
t = {
  a = 1, # one
  b = 2, # two
}
```
```kotlin edit
doc["t"].assertSuccess().inlineTableOrNull!!.set("c", Value.Integer(3L))
```
```toml expected
t = {
  a = 1, # one
  b = 2, # two
  c = 3,
}
```

### C-055: removing the last key before `}` without a trailing comma keeps the comma rule valid
rule: 2, 10   version: 1.1
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
t = {
  a = 1, # one
  b = 2 # two
}
```
```kotlin edit
doc["t"].assertSuccess().inlineTableOrNull!!.remove("b")
```
```toml expected
t = {
  a = 1, # one
}
```

### C-056: adding after a last inline key with a comment and no trailing comma
rule: 10   version: 1.1
```toml input
t = {
  a = 1 # one
}
```
```kotlin edit
doc["t"].assertSuccess().inlineTableOrNull!!.set("b", Value.Integer(2L))
```
```toml expected
t = {
  a = 1, # one
  b = 2
}
```

### C-057: a comment after `{` stays when the inline table is emptied
rule: 2   version: 1.1
```toml input
t = { # settings
  a = 1,
}
```
```kotlin edit
doc["t"].assertSuccess().inlineTableOrNull!!.remove("a")
```
```toml expected
t = { # settings
}
```

### C-058: a single-line inline table with a trailing comma loses a key
rule: 2   version: 1.1
```toml input
t = { a = 1, b = 2, } # c
```
```kotlin edit
doc["t"].assertSuccess().inlineTableOrNull!!.remove("b")
```
```toml expected
t = { a = 1, } # c
```

### C-059: removing a whole multi-line inline table takes the comment above it and every comment inside it
rule: 2   version: 1.1
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
# cfg
t = { # open
  a = 1, # one
  # loose
} # close
x = 1
```
```kotlin edit
doc.remove("t")
```
```toml expected
x = 1
```

### C-060: replacing a multi-line inline table with a scalar keeps the comment after `}`
rule: 3   version: 1.1
```toml input
# cfg
t = {
  a = 1, # one
} # close
```
```kotlin edit
doc["t"] = 0L
```
```toml expected
# cfg
t = 0 # close
```

### C-061: a nested inline table edit keeps the outer comments
rule: 1   version: 1.1
```toml input
t = {
  inner = { x = 1 }, # inner
  y = 2, # why
}
```
```kotlin edit
doc["t"]["inner"]["x"] = 5L
```
```toml expected
t = {
  inner = { x = 5 }, # inner
  y = 2, # why
}
```

### C-062: a multi-line array of inline tables loses one entry with its comment
rule: 6   version: 1.1
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
deps = [
  { name = "a" }, # first
  # about b
  { name = "b" }, # second
]
```
```kotlin edit
doc["deps"].assertSuccess().arrayOrNull!!.removeAt(1)
```
```toml expected
deps = [
  { name = "a" }, # first
]
```

## Inline tables made dotted and back

### C-063: an inline table made dotted keeps the comment above its key
rule: 8   version: 1.0
settled: owner ruling 2026-10-05 (Q5): a table or inline table made dotted, or a header again, keeps the lines above its key and each value's same-line comment; its header comment, the comment after its braces and the comment lines inside go.
```toml input
x = 0
# above
t = { a = 1, b = 2 } # c
```
```kotlin edit
doc["t"].assertSuccess().setLayout(TableLayout.Dotted, force = true)
```
```toml expected
x = 0
# above
t.a = 1
t.b = 2
```

### C-064: a multi-line 1.1 inline table made dotted keeps the line above, drops inner comments
rule: 8   version: 1.1
settled: owner ruling 2026-10-05 (Q5): a table or inline table made dotted, or a header again, keeps the lines above its key and each value's same-line comment; its header comment, the comment after its braces and the comment lines inside go.
```toml input
# above
t = {
  a = 1, # one
  b = 2, # two
}
y = 3
```
```kotlin edit
doc["t"].assertSuccess().setLayout(TableLayout.Dotted)
```
```toml expected
# above
t.a = 1 # one
t.b = 2 # two
y = 3
```

## Dotted keys interleaved with comments

### C-065: removing a dotted table removes the comments between its lines and the one above its first line
rule: 2   version: 1.0
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
# a-block
a.b = 1 # ab
# between
a.c = 2 # ac
d = 3
```
```kotlin edit
doc.remove("a")
```
```toml expected
d = 3
```

### C-066: removing one dotted key takes the comment above it
rule: 2   version: 1.0
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
# a-block
a.b = 1 # ab
# between
a.c = 2 # ac
d = 3
```
```kotlin edit
doc["a"].assertSuccess().standardTableOrNull!!.remove("b")
```
```toml expected
# between
a.c = 2 # ac
d = 3
```

### C-067: replacing a dotted key's value keeps its spelling and comment
rule: 3   version: 1.0
```toml input
a . b = 1 # ab
a.c = 2 # ac
```
```kotlin edit
doc["a"]["b"] = 7L
```
```toml expected
a . b = 7 # ab
a.c = 2 # ac
```

### C-068: adding to a dotted table goes after its last dotted line
rule: 1   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a.b = 1 # ab
# between
a.c = 2 # ac
d = 3
```
```kotlin edit
doc["a"].assertSuccess().standardTableOrNull!!.set("e", Value.Integer(5L))
```
```toml expected
a.b = 1 # ab
# between
a.c = 2 # ac
a.e = 5
d = 3
```

### C-069: dotted keys split by another key, one removed
rule: 2   version: 1.0
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
a.x = 1 # ax
b = 2 # bee
# about a.y
a.y = 3
```
```kotlin edit
doc["a"].assertSuccess().standardTableOrNull!!.remove("y")
```
```toml expected
a.x = 1 # ax
b = 2 # bee
```

### C-070: a dotted table made a standard table keeps the lines above its first key
rule: 8   version: 1.0
settled: owner ruling 2026-10-05 (Q2): a new header gets its blank line above the comment that introduces it.
```toml input
x = 0
# above
t.a = 1 # one
```
```kotlin edit
doc["t"].assertSuccess().setLayout(TableLayout.Header)
```
```toml expected
x = 0

# above
[t]
a = 1 # one
```

### C-071: a standard table made dotted keeps the lines above its header and drops the header comment
rule: 8   version: 1.0
settled: owner ruling 2026-10-05 (Q5): a table or inline table made dotted, or a header again, keeps the lines above its key and each value's same-line comment; its header comment, the comment after its braces and the comment lines inside go.
```toml input
x = 0
# above
[t] # hdr
a = 1 # one
```
```kotlin edit
doc["t"].assertSuccess().setLayout(TableLayout.Dotted, force = true)
```
```toml expected
x = 0
# above
t.a = 1 # one
```

## Quoted keys containing `#`

### C-072: a quoted key containing `#` keeps its spelling on replace
rule: 3   version: 1.0
```toml input
"a#b" = 1 # c
```
```kotlin edit
doc["a#b"] = 2L
```
```toml expected
"a#b" = 2 # c
```

### C-073: removing the key above a quoted `#` key leaves it untouched
rule: 2   version: 1.0
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
# top
x = 0 # zero
'a # b' = 1 # c
```
```kotlin edit
doc.remove("x")
```
```toml expected
'a # b' = 1 # c
```

### C-074: a new key whose name contains `#` is quoted so it is not a comment
rule: 10   version: 1.0
```toml input
a = 1 # c
```
```kotlin edit
doc["x#y"] = 2L
```
```toml expected
a = 1 # c
"x#y" = 2
```

## Line endings, BOM and the final newline

### C-075: CRLF: an added key ends in CRLF and the comments keep theirs
rule: 9, 1   version: 1.0   line endings: CRLF
```toml input
# top
a = 1 # c
```
```kotlin edit
doc["b"] = 2L
```
```toml expected
# top
a = 1 # c
b = 2
```

### C-076: CRLF: removing a key takes the CRLF comment above it
rule: 9, 2   version: 1.0   line endings: CRLF
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
# about a
a = 1 # c
b = 2
```
```kotlin edit
doc.remove("a")
```
```toml expected
b = 2
```

### C-077: CRLF: appending to a multi-line array with comments
rule: 9, 6   version: 1.0   line endings: CRLF
```toml input
a = [
  1, # one
]
```
```kotlin edit
doc["a"].assertSuccess().arrayOrNull!!.add(Value.Integer(2L))
```
```toml expected
a = [
  1, # one
  2,
]
```

### C-078: CRLF: removing an array element takes its comment and its CRLF, not the next line's
rule: 9, 6   version: 1.0   line endings: CRLF
```toml input
a = [
  1, # one
  2, # two
]
```
```kotlin edit
doc["a"].assertSuccess().arrayOrNull!!.removeAt(0)
```
```toml expected
a = [
  2, # two
]
```

### C-079: mixed endings: a new line ends like the line before it
rule: 9   version: 1.0
note: input is `a = 1 # crlf\r\nb = 2 # lf\n`; expected is `a = 1 # crlf\r\nb = 2 # lf\nc = 3\n`
```toml input
a = 1 # crlf
b = 2 # lf
```
```kotlin edit
doc["c"] = 3L
```
```toml expected
a = 1 # crlf
b = 2 # lf
c = 3
```

### C-080: mixed endings, other way round: the new line copies the CRLF before it
rule: 9   version: 1.0
note: input is `a = 1 # lf\nb = 2 # crlf\r\n`; expected is `a = 1 # lf\nb = 2 # crlf\r\nc = 3\r\n`
```toml input
a = 1 # lf
b = 2 # crlf
```
```kotlin edit
doc["c"] = 3L
```
```toml expected
a = 1 # lf
b = 2 # crlf
c = 3
```

### C-081: CRLF without a final newline keeps none once changed
rule: 9, 3   version: 1.0   line endings: CRLF   input has no final newline
settled: todo 31, 2026-10-05: a file read without a final newline keeps none after an edit.
```toml input
# top
a = 1 # c
```
```kotlin edit
doc["a"] = 2L
```
```toml expected
# top
a = 2 # c```

### C-082: no final newline after a same-line comment: the value changes and no newline is gained
rule: 9, 3   version: 1.0   input has no final newline
settled: todo 31, 2026-10-05: a file read without a final newline keeps none after an edit.
```toml input
a = 1 # c
```
```kotlin edit
doc["a"] = 2L
```
```toml expected
a = 2 # c```

### C-083: a final comment without a newline stays last when a key is added
rule: 9, 1   version: 1.0   input has no final newline
settled: settled like C-084: a file read without a final newline keeps none after an edit.
```toml input
a = 1
# end
```
```kotlin edit
doc["b"] = 2L
```
```toml expected
a = 1
b = 2
# end```

### C-084: a final comment without a newline stays when the key above it is removed
rule: 9, 2   version: 1.0   input has no final newline
```toml input
a = 1
# end
```
```kotlin edit
doc.remove("a")
```
note: corrected: a file read without a final newline keeps none after an edit; an added one is allowed, not required.
```toml expected
# end```

### C-085: a byte order mark stays through an edit next to a comment
rule: 9, 3   version: 1.0   BOM
```toml input
# first line after BOM
a = 1 # c
```
```kotlin edit
doc["a"] = 2L
```
```toml expected
# first line after BOM
a = 2 # c
```

### C-086: a byte order mark stays when the first key is removed
rule: 9, 2   version: 1.0   BOM
```toml input
a = 1 # c
b = 2
```
```kotlin edit
doc.remove("a")
```
```toml expected
b = 2
```

### C-087: a byte order mark stays when a key is added to a comment-only document
rule: 9   version: 1.0   BOM
settled: owner ruling 2026-10-05 (Q3): in a document that is only comments, new content goes below them.
```toml input
# only a comment
```
```kotlin edit
doc["a"] = 1L
```
```toml expected
# only a comment
a = 1
```

## Blank-line runs

### C-088: removing the first key keeps the comment that follows a blank line, without the blank lines above it
rule: 2   version: 1.0
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
a = 1


# c


b = 2
```
```kotlin edit
doc.remove("a")
```
```toml expected
# c


b = 2
```

### C-089: removing the last key keeps the free comment above it, without blank lines after it
rule: 2   version: 1.0
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
a = 1


# c


b = 2
```
```kotlin edit
doc.remove("b")
```
```toml expected
a = 1


# c
```

### C-090: blank lines between keys survive a value replace
rule: 1, 3   version: 1.0
```toml input
a = 1

# about b

b = 2 # bee
```
```kotlin edit
doc["b"] = 3L
```
```toml expected
a = 1

# about b

b = 3 # bee
```

## Comment-only documents and empty tables

### C-091: a key added to a comment-only document
rule: 1   version: 1.0
settled: owner ruling 2026-10-05 (Q3): in a document that is only comments, new content goes below them.
```toml input
# header comment

# second comment
```
```kotlin edit
doc["a"] = 1L
```
```toml expected
# header comment

# second comment
a = 1
```

### C-092: a table added to a comment-only document keeps the comments
rule: 1   version: 1.0
settled: owner ruling 2026-10-05 (Q3): in a document that is only comments, new content goes below them.
```toml input
# just comments
```
```kotlin edit
doc["t"] = standardTableOf("k" to Item(Value.Integer(1L))).assertSuccess()
```
```toml expected
# just comments

[t]
k = 1
```

### C-093: a key added to an empty table with a header comment goes right after the header
rule: 1   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
[t] # empty
# nothing here

[u]
x = 1
```
```kotlin edit
doc["t"].assertSuccess().standardTableOrNull!!.set("k", Value.Integer(1L))
```
```toml expected
[t] # empty
k = 1
# nothing here

[u]
x = 1
```

### C-094: removing an empty table takes its comment above and keeps the free comment after it
rule: 2   version: 1.0
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
# about t
[t] # empty
# nothing here

[u]
x = 1
```
```kotlin edit
doc.remove("t")
```
```toml expected
# nothing here

[u]
x = 1
```

### C-095: removing a table takes its comment above and keeps the one directly above the next header
rule: 2   version: 1.0
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
# about t
[t]
a = 1
# about u
[u]
b = 2
```
```kotlin edit
doc.remove("t")
```
```toml expected
# about u
[u]
b = 2
```

### C-096: removing a table takes the comments above its headers and its sub-tables' headers
rule: 2   version: 1.0
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
# keep
[a] # a
x = 1
# about a.b
[a.b] # ab
y = 2
# about c
[c]
z = 3
```
```kotlin edit
doc.remove("a")
```
```toml expected
# about c
[c]
z = 3
```

### C-097: adding a key to a table whose last line is followed by comments before the next header
rule: 1   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
[t]
b = 2
# about u
[u]
```
```kotlin edit
doc["t"].assertSuccess().standardTableOrNull!!.set("c", Value.Integer(3L))
```
```toml expected
[t]
b = 2
c = 3
# about u
[u]
```

### C-098: removing a key between two comment blocks
rule: 2   version: 1.0
settled: owner ruling 2026-10-07: a comment belongs to the item below it or on its line (GOALS.md goal 4).
```toml input
[t]
a = 1
# about b
b = 2 # bee
# about c
c = 3
```
```kotlin edit
doc["t"].assertSuccess().standardTableOrNull!!.remove("b")
```
```toml expected
[t]
a = 1
# about c
c = 3
```

## Implicit tables

### C-099: a header hidden as implicit leaves its comments where it stood
rule: 8   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
# above a
[a] # a hdr
[a.b]
x = 1
```
```kotlin edit
doc["a"].assertSuccess().standardTableOrNull!!.setImplicit(true)
```
```toml expected
# above a
# a hdr
[a.b]
x = 1
```

### C-100: a header hidden as implicit with no header comment
rule: 8   version: 1.0
```toml input
# above a
[a]
# sub
[a.b]
x = 1
```
```kotlin edit
doc["a"].assertSuccess().standardTableOrNull!!.setImplicit(true)
```
```toml expected
# above a
# sub
[a.b]
x = 1
```

## Sorting

### C-101: sorting a table moves each key with the comment above it and on its line
rule: 1   version: 1.0
```toml input
[t]
# zebra
z = 1 # last
# apple
a = 2 # first
```
```kotlin edit
doc["t"].assertSuccess().standardTableOrNull!!.sortWith(compareBy { it.first.name })
```
```toml expected
[t]
# apple
a = 2 # first
# zebra
z = 1 # last
```

### C-102: sorting a table with a no-space header comment keeps the header untouched
rule: 1   version: 1.0
```toml input
[t]#hdr
b = 1#bee
a = 2#ay
```
```kotlin edit
doc["t"].assertSuccess().standardTableOrNull!!.sortWith(compareBy { it.first.name })
```
```toml expected
[t]#hdr
a = 2#ay
b = 1#bee
```

### C-103: sorting CRLF keys keeps every line's ending
rule: 1, 9   version: 1.0   line endings: CRLF
```toml input
[t]
b = 1 # bee
a = 2 # ay
```
```kotlin edit
doc["t"].assertSuccess().standardTableOrNull!!.sortWith(compareBy { it.first.name })
```
```toml expected
[t]
a = 2 # ay
b = 1 # bee
```

### C-104: sorting where the last key had no final newline
rule: 1, 9   version: 1.0   input has no final newline
settled: todo 31, 2026-10-05: a file read without a final newline keeps none after an edit.
```toml input
[t]
b = 1 # bee
a = 2 # ay
```
```kotlin edit
doc["t"].assertSuccess().standardTableOrNull!!.sortWith(compareBy { it.first.name })
```
```toml expected
[t]
a = 2 # ay
b = 1 # bee```

## Restructuring

### C-105: a scalar replaced by an array value keeps the same-line comment
rule: 3   version: 1.0
```toml input
# above
a = 1 # c
```
```kotlin edit
doc["a"] = Value.Array(tomlArrayOf(Value.Integer(1L), Value.Integer(2L)).assertSuccess())
```
```toml expected
# above
a = [1, 2] # c
```

### C-106: a scalar replaced by an inline table value keeps the same-line comment
rule: 3   version: 1.0
```toml input
a = 1 # c
```
```kotlin edit
doc["a"] = Value.InlineTable(inlineTableOf("k" to Value.Integer(1L)).assertSuccess())
```
```toml expected
a = { k = 1 } # c
```

### C-107: a scalar replaced by a table keeps the line above, may drop the same-line comment
rule: 4   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
# above
a = 1 # c
```
```kotlin edit
doc["a"] = standardTableOf("k" to Item(Value.Integer(1L))).assertSuccess()
```
```toml expected
# above
[a]
k = 1
```

### C-108: a table replaced by a scalar keeps the lines above its header
rule: 4   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
x = 1
# above t
[t] # hdr
# inner
a = 1 # one
```
```kotlin edit
doc["t"] = 5L
```
```toml expected
x = 1
# above t
t = 5
```

### C-109: a table turned into an inline value keeps the line above its header
rule: 4   version: 1.0
settled: owner ruling 2026-10-05 (Q6): makeValue on one line keeps the header comment after `}`.
```toml input
# above t
[t] # hdr
# inner
a = 1 # one
```
```kotlin edit
doc["t"].assertSuccess().setLayout(TableLayout.Inline, force = true)
```
```toml expected
# above t
t = { a = 1 } # hdr
```

### C-110: an array of tables replaced by a scalar keeps the lines above the first header
rule: 4, 5   version: 1.0
```toml input
x = 1
# about bins
[[bin]] # one
n = 1
# two
[[bin]]
n = 2
```
```kotlin edit
doc["bin"] = 0L
```
```toml expected
x = 1
# about bins
bin = 0
```

### C-111: getOrPut on an existing key leaves its comments alone
rule: 1   version: 1.0
```toml input
[t] # hdr
# above k
k = 1 # one
```
```kotlin edit
doc["t"].assertSuccess().standardTableOrNull!!.getOrPut("k") { Item(Value.Integer(9L)) }
```
```toml expected
[t] # hdr
# above k
k = 1 # one
```

### C-112: getOrPut adds a missing key after a key with a no-space comment
rule: 1   version: 1.0
```toml input
[t]#hdr
k = 1#one
```
```kotlin edit
doc["t"].assertSuccess().standardTableOrNull!!.getOrPut("m") { Item(Value.Integer(2L)) }
```
```toml expected
[t]#hdr
k = 1#one
m = 2
```

## Open questions

1. C-022, C-023, C-024: where does `x = []` print for an emptied array of tables, especially when the array of
   tables came after a standard table (it has to move to the root body to stay `x`), and where do the lines that
   were above its first header end up?
2. C-025, C-083, C-087, C-091: does a new key or table go above or below the document's trailing comment block,
   and does a comment-only document count as trailing?
3. C-032, C-033, C-055, C-056: when the last element or key has no trailing comma, which comma style does the
   container keep after a removal or an append?
4. C-035, C-040: does a new array element go above or below comments sitting before `]`?
5. C-036, C-046, C-054: what layout (own line, copied indentation, comma) does an inserted element or inline key
   get in a multi-line container; does rule 6 apply to TOML 1.1 multi-line inline tables?
6. C-039, C-047: is a comment directly after `[` "above" the first element; does removing the last of several
   elements sharing a line take that line's comment?
7. C-050: does a copied multi-line value keep its inner comments?
8. C-063, C-064, C-070, C-071: which comments survive a change to or from `TableLayout.Dotted`: the key's same-line comment, the
   values' own comments, the header comment?
9. C-081: does the final newline a changed document gains copy the CRLF of the line before it?
10. C-093, C-094, C-095, C-096: does a comment glued to the next (non-sub-table) header belong to the table
    above it or to that header, for removal and for where a new key goes?
11. C-099: does a hidden header's same-line comment stay, and on its own line?
12. C-104: how does sorting handle the line that had no final newline?
13. C-107, C-108, C-109: for a structure change, which comments go and where the replacement prints relative to
    the lines that were above the old key.
