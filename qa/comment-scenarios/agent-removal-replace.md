# Comment scenarios: removal and replacement

Each block's text ends with a single newline unless the header says otherwise. Expected outputs were written
from the owner's rules alone, without running kotoml.

## Removing keys from the root and from tables

### R-001: removing a middle key keeps the comment above it, now above the next key
rule: 2   version: 1.0
```toml input
a = 1
# about b
b = 2 # bee
c = 3
```
```kotlin edit
doc.remove("b")
```
```toml expected
a = 1
# about b
c = 3
```

### R-002: removing the first key keeps the file header comment and blank line
rule: 2   version: 1.0
```toml input
# file header

a = 1 # first
b = 2
```
```kotlin edit
doc.remove("a")
```
```toml expected
# file header

b = 2
```

### R-003: removing the last key leaves its comment above at end of file
rule: 2   version: 1.0
```toml input
a = 1
# about b
b = 2 # bee
```
```kotlin edit
doc.remove("b")
```
```toml expected
a = 1
# about b
```

### R-004: the comment below a removed key belongs to the next key and stays
rule: 1, 2   version: 1.0
```toml input
a = 1 # a
# about b
b = 2
```
```kotlin edit
doc.remove("a")
```
```toml expected
# about b
b = 2
```

### R-005: a blank line and floating comment between keys stay when the key above them goes
rule: 2   version: 1.0
```toml input
a = 1 # a

# floating
b = 2
```
```kotlin edit
doc.remove("a")
```
```toml expected

# floating
b = 2
```

### R-006: removing the last root value before a table keeps the blank line above the header
rule: 2   version: 1.0
```toml input
a = 1
b = 2 # bee

[t]
k = 1
```
```kotlin edit
doc.remove("b")
```
```toml expected
a = 1

[t]
k = 1
```

### R-007: removing the last key of a table keeps its comment above, then the next header follows
rule: 2   version: 1.0
```toml input
[a]
x = 1
# about y
y = 2 # why

# about b
[b]
z = 3
```
```kotlin edit
doc["a"].getOrFail().standardTableOrNull!!.remove("y")
```
```toml expected
[a]
x = 1
# about y

# about b
[b]
z = 3
```

### R-008: removing the only key of a table leaves the header and the comment above the key
rule: 2   version: 1.0
```toml input
[a] # hdr
# k
k = 1 # kay
```
```kotlin edit
doc["a"].getOrFail().standardTableOrNull!!.remove("k")
```
```toml expected
[a] # hdr
# k
```

### R-009: removing an indented key takes its indentation with it
rule: 2   version: 1.0
```toml input
[t]
  # about a
  a = 1 # one
  b = 2
```
```kotlin edit
doc["t"].getOrFail().standardTableOrNull!!.remove("a")
```
```toml expected
[t]
  # about a
  b = 2
```

### R-010: a blank line above the next key survives removal of the key before it
rule: 2   version: 1.0
```toml input
[t]
a = 1

b = 2
```
```kotlin edit
doc["t"].getOrFail().standardTableOrNull!!.remove("a")
```
```toml expected
[t]

b = 2
```

### R-011: removing a multi-line array value drops every comment inside it and after its closing bracket
rule: 2   version: 1.0
```toml input
# about list
list = [
  1, # one
  # mid
  2,
] # end
b = 1
```
```kotlin edit
doc.remove("list")
```
```toml expected
# about list
b = 1
```

### R-012: removing a multi-line string whose body looks like a comment takes the whole value
rule: 2   version: 1.0
```toml input
s = """
# not a comment
""" # real
b = 1
```
```kotlin edit
doc.remove("s")
```
```toml expected
b = 1
```

### R-013: removing a key whose value is a multi-line inline table drops comments inside it
rule: 2   version: 1.1
```toml input
a = 0
# about t
t = {
  # inner
  x = 1, # ex
} # tee
z = 9
```
```kotlin edit
doc.remove("t")
```
```toml expected
a = 0
# about t
z = 9
```

### R-014: removing a key in a CRLF file keeps the CRLF line endings of the rest
rule: 1, 2   version: 1.0   line endings: CRLF
```toml input
a = 1
# about b
b = 2 # bee
c = 3
```
```kotlin edit
doc.remove("b")
```
```toml expected
a = 1
# about b
c = 3
```

### R-015: removing the last key of a file without a final newline
rule: 2   version: 1.0   (input has no final newline after `# bee`; expected ends with `a = 1` and no newline)
settled: todo 31, 2026-10-05: a file read without a final newline keeps none after an edit.
```toml input
a = 1
b = 2 # bee
```
```kotlin edit
doc.remove("b")
```
```toml expected
a = 1```

### R-016: removing a missing key changes nothing
rule: 1   version: 1.0
```toml input
# c
a = 1 # a
```
```kotlin edit
doc.remove("zz")
```
```toml expected
# c
a = 1 # a
```

### R-017: removing one dotted key keeps the comments above both dotted lines
rule: 2   version: 1.0
```toml input
# about b
a.b = 1 # bee
# about c
a.c = 2 # cee
```
```kotlin edit
doc["a"].getOrFail().tableOrNull!!.remove("b")
```
```toml expected
# about b
# about c
a.c = 2 # cee
```

### R-018: removing a dotted table removes all its dotted lines but keeps the comment above the first
rule: 2   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
x = 0
# about a
a.b = 1 # bee
# about c
a.c = 2 # cee
y = 3
```
```kotlin edit
doc.remove("a")
```
```toml expected
x = 0
# about a
y = 3
```

### R-019: removing a dotted sub-key inside a table body
rule: 2   version: 1.0
```toml input
[t]
# about x
x.y = 1 # c
x.z = 2
w = 3
```
```kotlin edit
doc["t"].getOrFail().standardTableOrNull!!.remove("x")
```
```toml expected
[t]
# about x
w = 3
```

### R-020: removing a key deep inside nested tables
rule: 2   version: 1.0
```toml input
[a.b.c]
# k
k = 1 # kay
m = 2
```
```kotlin edit
doc["a"]["b"]["c"].getOrFail().standardTableOrNull!!.remove("k")
```
```toml expected
[a.b.c]
# k
m = 2
```

### R-021: removing a key then setting it again appends it without the old comments
rule: 2   version: 1.0
```toml input
a = 1 # one
b = 2
```
```kotlin edit
doc.remove("a")
doc["a"] = 3L
```
```toml expected
b = 2
a = 3
```

## Removing tables

### R-022: removing a table keeps the lines above its header and the next table's comment
rule: 2   version: 1.0
```toml input
# top
a = 1

# about server
[server] # srv
port = 80 # p

# about client
[client]
x = 1
```
```kotlin edit
doc.remove("server")
```
```toml expected
# top
a = 1

# about server

# about client
[client]
x = 1
```

### R-023: removing a table drops its sub-tables and the comments above their headers
rule: 2   version: 1.0
```toml input
[a]
x = 1

# about a.b
[a.b] # bee
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

### R-024: removing a sub-table keeps its parent and the comment above the sub-table header
rule: 2   version: 1.0
```toml input
[a]
x = 1

# about a.b
[a.b] # bee
y = 2

# about a.c
[a.c]
z = 3
```
```kotlin edit
doc["a"].getOrFail().standardTableOrNull!!.remove("b")
```
```toml expected
[a]
x = 1

# about a.b

# about a.c
[a.c]
z = 3
```

### R-025: removing the last table keeps a comment written after its body at end of file
rule: 1, 2, 5   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a = 1

[t]
k = 1
# trailing at end
```
```kotlin edit
doc.remove("t")
```
```toml expected
a = 1

# trailing at end
```

### R-026: removing a table whose header is implicit keeps the comment above its first printed header
rule: 2   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
x = 1

# about a.b
[a.b]
y = 2

# about c
[c]
```
```kotlin edit
doc.remove("a")
```
```toml expected
x = 1

# about a.b

# about c
[c]
```

### R-027: removing a table whose sub-table header comes first in the file
rule: 2   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
# about a.b
[a.b]
y = 2

# about a
[a]
x = 1

# about c
[c]
z = 3
```
```kotlin edit
doc.remove("a")
```
```toml expected
# about a.b

# about c
[c]
z = 3
```

### R-028: removing a table whose body is split by another table keeps the other table intact
rule: 2   version: 1.0
```toml input
[a]
x = 1

# about b
[b]
m = 1

# about a.c
[a.c]
z = 3
```
```kotlin edit
doc.remove("a")
```
```toml expected

# about b
[b]
m = 1
```

### R-029: removing a sub-table under an array-of-tables element
rule: 2   version: 1.0
```toml input
[[p]]
n = 1
# sub
[p.q] # cue
m = 1
```
```kotlin edit
doc["p"].getOrFail().arrayOfTablesOrNull!![0].remove("q")
```
```toml expected
[[p]]
n = 1
# sub
```

### R-030: removing a table in a CRLF file
rule: 2   version: 1.0   line endings: CRLF
```toml input
# top
[a]
x = 1

# about b
[b]
y = 2
```
```kotlin edit
doc.remove("a")
```
```toml expected
# top

# about b
[b]
y = 2
```

## Arrays of tables

### R-031: removing a whole array of tables drops the comments above its later elements
rule: 2   version: 1.0
```toml input
x = 0

# fruits
[[fruit]] # first
name = "apple"

# second fruit
[[fruit]]
name = "banana"

# about other
[other]
k = 1
```
```kotlin edit
doc.remove("fruit")
```
```toml expected
x = 0

# fruits

# about other
[other]
k = 1
```

### R-032: removing the first element keeps the comment above the first header and the second element's comment
rule: 5   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
# fruits
[[fruit]] # first
name = "apple" # a

# second fruit
[[fruit]]
name = "banana"
```
```kotlin edit
doc["fruit"].getOrFail().arrayOfTablesOrNull!!.removeAt(0)
```
```toml expected
# fruits

# second fruit
[[fruit]]
name = "banana"
```

### R-033: removing the last element keeps the comment above it, like a removed key
rule: 2, 5   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
[[fruit]]
name = "apple"

# second fruit
[[fruit]] # two
name = "banana" # b
```
```kotlin edit
doc["fruit"].getOrFail().arrayOfTablesOrNull!!.removeAt(1)
```
```toml expected
[[fruit]]
name = "apple"

# second fruit
```

### R-034: removing a middle element keeps the comments above it and above the next one
rule: 2, 5   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
[[p]]
n = 1

# second
[[p]]
n = 2 # two

# third
[[p]]
n = 3
```
```kotlin edit
doc["p"].getOrFail().arrayOfTablesOrNull!!.removeAt(1)
```
```toml expected
[[p]]
n = 1

# second

# third
[[p]]
n = 3
```

### R-035: removing two adjacent elements at once drops the comment between them
rule: 5   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
[[p]]
n = 1

# second
[[p]]
n = 2

# third
[[p]]
n = 3

# after
[q]
```
```kotlin edit
doc["p"].getOrFail().arrayOfTablesOrNull!!.removeAll { it["n"].getOrNull()?.longOrNull != 1L }
```
```toml expected
[[p]]
n = 1

# second

# after
[q]
```

### R-036: clearing an array of tables prints `key = []` with the comments around it kept
rule: 5, 7   version: 1.0
```toml input
# fruits
[[fruit]]
name = "a"
# between
[[fruit]]
name = "b"
# after
```
```kotlin edit
doc["fruit"].getOrFail().arrayOfTablesOrNull!!.clear()
```
```toml expected
# fruits
fruit = []
# after
```

### R-037: removing the only element of an array of tables prints `key = []`
rule: 7   version: 1.0
```toml input
a = 1
# bins
[[bin]] # only
name = "x"
```
```kotlin edit
doc["bin"].getOrFail().arrayOfTablesOrNull!!.removeAt(0)
```
```toml expected
a = 1
# bins
bin = []
```

### R-038: an emptied array of tables after a table moves to the root values and stays valid
rule: 5, 7, 9   version: 1.0
settled: owner ruling 2026-10-05 (Q1): the comment above an emptied or replaced array of tables moves up with its new line.
```toml input
[a]
x = 1

# bins
[[bin]]
name = "x"
```
```kotlin edit
doc["bin"].getOrFail().arrayOfTablesOrNull!!.clear()
```
```toml expected

# bins
bin = []

[a]
x = 1
```

### R-039: removing a key from inside an array-of-tables element keeps the comment above it
rule: 2   version: 1.0
```toml input
[[p]]
# n
n = 1 # one
m = 2
[[p]]
n = 3
```
```kotlin edit
doc["p"].getOrFail().arrayOfTablesOrNull!![0].remove("n")
```
```toml expected
[[p]]
# n
m = 2
[[p]]
n = 3
```

### R-040: removing a nested array of tables inside a table
rule: 2   version: 1.0
```toml input
[a]
x = 1

# about a.b
[[a.b]] # first
y = 1

# second b
[[a.b]]
y = 2

# about c
[c]
```
```kotlin edit
doc["a"].getOrFail().standardTableOrNull!!.remove("b")
```
```toml expected
[a]
x = 1

# about a.b

# about c
[c]
```

## Arrays: removing elements

### R-041: removing a middle element of a single-line array keeps the comment after the array
rule: 3, 6   version: 1.0
```toml input
a = [1, 2, 3] # nums
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.removeAt(1)
```
```toml expected
a = [1, 3] # nums
```

### R-042: removing the first element of a single-line array
rule: 6   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a = [1, 2, 3] # nums
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.removeAt(0)
```
```toml expected
a = [2, 3] # nums
```

### R-043: removing the last element of a spaced single-line array keeps the space before the bracket
rule: 6   version: 1.0
settled: fixed 2026-10-05: the spacing before the closing bracket and an element inserted into an array of one element per line.
```toml input
a = [ 1, 2, 3 ] # nums
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.removeAt(2)
```
```toml expected
a = [ 1, 2 ] # nums
```

### R-044: removing the first element on its own line takes its line comment and keeps the comment above
rule: 6   version: 1.0
```toml input
ports = [
  # first
  80, # http
  443, # https
]
```
```kotlin edit
doc["ports"].getOrFail().arrayOrNull!!.removeAt(0)
```
```toml expected
ports = [
  # first
  443, # https
]
```

### R-045: removing the last element on its own line takes its comment, not the previous element's
rule: 6   version: 1.0
```toml input
ports = [
  80, # http
  443, # https
]
```
```kotlin edit
doc["ports"].getOrFail().arrayOrNull!!.removeAt(1)
```
```toml expected
ports = [
  80, # http
]
```

### R-046: removing a middle element keeps the comment above it, now above the next element
rule: 6   version: 1.0
```toml input
a = [
  1,
  # about two
  2, # two
  3,
]
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.removeAt(1)
```
```toml expected
a = [
  1,
  # about two
  3,
]
```

### R-047: removing the last element without a trailing comma takes the comment on its line
rule: 6, 9   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a = [
  1,
  2 # two
]
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.removeAt(1)
```
```toml expected
a = [
  1,
]
```

### R-048: removing the first element when the last has no trailing comma
rule: 6   version: 1.0
```toml input
a = [
  1, # one
  2 # two
]
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.removeAt(0)
```
```toml expected
a = [
  2 # two
]
```

### R-049: removing the last element keeps a comment written above the closing bracket
rule: 6   version: 1.0
```toml input
a = [
  1,
  2,
  # closing note
]
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.removeAt(1)
```
```toml expected
a = [
  1,
  # closing note
]
```

### R-050: removing an element that shares a line keeps that line's comment
rule: 6   version: 1.0
```toml input
a = [
  1, 2, # pair
  3,
]
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.removeAt(1)
```
```toml expected
a = [
  1, # pair
  3,
]
```

### R-051: removing the first of two elements sharing a line keeps the second on that line
rule: 6   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a = [
  1, 2, # pair
  3,
]
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.removeAt(0)
```
```toml expected
a = [
  2, # pair
  3,
]
```

### R-052: removing the only element written on the bracket's line keeps the comment
rule: 6, 9   version: 1.0
settled: settled by rule 6: a removed element's own same-line comment goes with it.
```toml input
a = [1 # c
]
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.removeAt(0)
```
```toml expected
a = []
```

### R-053: clearing a multi-line array keeps the comments above elements before the bracket
rule: 6   version: 1.0
```toml input
a = [
  # c
  1, # one
  2,
] # tail
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.clear()
```
```toml expected
a = [
  # c
] # tail
```

### R-054: clearing a single-line array keeps the comment after it
rule: 3, 6   version: 1.0
```toml input
a = [1, 2] # c
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.clear()
```
```toml expected
a = [] # c
```

### R-055: removing several elements with a filter keeps every comment above them
rule: 6   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a = [
  # one
  1,
  # two
  2, # two!
  # three
  3,
]
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.removeAll { (it as? Value.Integer)?.value != 3L }
```
```toml expected
a = [
  # one
  # two
  # three
  3,
]
```

### R-056: removing an inline table element from an array of inline tables
rule: 6   version: 1.0
```toml input
pts = [ { x = 1 }, { x = 2 } ] # p
```
```kotlin edit
doc["pts"].getOrFail().arrayOrNull!!.removeAt(0)
```
```toml expected
pts = [ { x = 2 } ] # p
```

### R-057: removing an element of a nested array keeps the outer array's comments
rule: 6   version: 1.0
```toml input
m = [
  [1, 2], # row 1
  [3, 4], # row 2
]
```
```kotlin edit
doc["m"].getOrFail().arrayOrNull!![0]!!.arrayOrNull!!.removeAt(1)
```
```toml expected
m = [
  [1], # row 1
  [3, 4], # row 2
]
```

## Inline tables: removing entries

### R-058: removing the middle entry of a single-line inline table keeps the comment after it
rule: 2, 3   version: 1.0
```toml input
t = { a = 1, b = 2, c = 3 } # t
```
```kotlin edit
doc["t"].getOrFail().inlineTableOrNull!!.remove("b")
```
```toml expected
t = { a = 1, c = 3 } # t
```

### R-059: removing the first entry of a single-line inline table
rule: 2   version: 1.0
```toml input
t = { a = 1, b = 2, c = 3 } # t
```
```kotlin edit
doc["t"].getOrFail().inlineTableOrNull!!.remove("a")
```
```toml expected
t = { b = 2, c = 3 } # t
```

### R-060: removing the last entry of a single-line inline table keeps the space before the brace
rule: 2   version: 1.0
settled: fixed 2026-10-05: the spacing before the closing bracket and an element inserted into an array of one element per line.
```toml input
t = { a = 1, b = 2, c = 3 } # t
```
```kotlin edit
doc["t"].getOrFail().inlineTableOrNull!!.remove("c")
```
```toml expected
t = { a = 1, b = 2 } # t
```

### R-061: removing the first entry of a multi-line inline table keeps the comment above it
rule: 2   version: 1.1
```toml input
t = {
  # about a
  a = 1, # one
  b = 2, # two
}
```
```kotlin edit
doc["t"].getOrFail().inlineTableOrNull!!.remove("a")
```
```toml expected
t = {
  # about a
  b = 2, # two
}
```

### R-062: removing the last entry of a multi-line inline table takes its comment, not the previous one
rule: 2   version: 1.1
```toml input
t = {
  a = 1, # one
  b = 2, # two
}
```
```kotlin edit
doc["t"].getOrFail().inlineTableOrNull!!.remove("b")
```
```toml expected
t = {
  a = 1, # one
}
```

### R-063: removing the last entry without a trailing comma takes the comment on its line
rule: 2, 9   version: 1.1
```toml input
t = {
  a = 1,
  b = 2 # two
}
```
```kotlin edit
doc["t"].getOrFail().inlineTableOrNull!!.remove("b")
```
```toml expected
t = {
  a = 1,
}
```

### R-064: removing an entry of a nested inline table keeps the outer comment
rule: 2, 3   version: 1.0
```toml input
t = { a = { x = 1, y = 2 }, b = 3 } # tee
```
```kotlin edit
doc["t"]["a"].getOrFail().inlineTableOrNull!!.remove("x")
```
```toml expected
t = { a = { y = 2 }, b = 3 } # tee
```

### R-065: clearing a multi-line inline table keeps comments above entries
rule: 2   version: 1.1
```toml input
t = {
  # about a
  a = 1, # one
  b = 2,
} # tee
```
```kotlin edit
doc["t"].getOrFail().inlineTableOrNull!!.clear()
```
```toml expected
t = {
  # about a
} # tee
```

### R-066: retainAll on a table keeps the comments above every removed key
rule: 2   version: 1.0
```toml input
[t]
# a
a = 1 # one
# b
b = 2 # two
# c
c = 3 # three
```
```kotlin edit
doc["t"].getOrFail().standardTableOrNull!!.retainAll { (k, _) -> k.name == "b" }
```
```toml expected
[t]
# a
# b
b = 2 # two
# c
```

### R-067: clearing a table keeps its header and all comments above its keys
rule: 2   version: 1.0
```toml input
# about t
[t] # tee
# a
a = 1 # one

# b
b = 2
```
```kotlin edit
doc["t"].getOrFail().standardTableOrNull!!.clear()
```
```toml expected
# about t
[t] # tee
# a

# b
```

### R-068: removeAll on the document removes root values and keeps their comments
rule: 2   version: 1.0
```toml input
# a
a = 1 # one
# b
b = "x" # two
# c
c = 3

[t]
k = 1
```
```kotlin edit
doc.removeAll { (_, item) -> item.valueOrNull is Value.Integer }
```
```toml expected
# a
# b
b = "x" # two
# c

[t]
k = 1
```

### R-069: clearing the document keeps the lines above every removed header and key
rule: 2   version: 1.0
```toml input
# head
a = 1 # one

# about t
[t] # tee
k = 1

# about u
[u]
m = 2
```
```kotlin edit
doc.clear()
```
```toml expected
# head

# about t

# about u
```

### R-070: retainAll keeping only a table drops root values but keeps their comments
rule: 2   version: 1.0
```toml input
# a
a = 1 # one

# about t
[t]
k = 1
```
```kotlin edit
doc.retainAll { (k, _) -> k.name == "t" }
```
```toml expected
# a

# about t
[t]
k = 1
```

## Replacing values with values

### R-071: replacing a value keeps its same-line comment and the comment above
rule: 3   version: 1.0
```toml input
# the port
port = 1 # p
```
```kotlin edit
doc["port"] = 2L
```
```toml expected
# the port
port = 2 # p
```

### R-072: replacing a value keeps unusual spacing around `=` and before the comment
rule: 3   version: 1.0
```toml input
  port   =   1    # p
```
```kotlin edit
doc["port"] = 2L
```
```toml expected
  port   =   2    # p
```

### R-073: replacing a value keeps a quoted key's spelling
rule: 3   version: 1.0
```toml input
"my key" = 1 # c
```
```kotlin edit
doc.set("my key", Value.Integer(2L))
```
```toml expected
"my key" = 2 # c
```

### R-074: replacing a hex value prints the new value plainly but keeps the comment
rule: 3   version: 1.0
```toml input
n = 0x10 # hex
```
```kotlin edit
doc["n"] = 17L
```
```toml expected
n = 17 # hex
```

### R-075: replacing a literal string with a different kind of value keeps the comment
rule: 3   version: 1.0
```toml input
s = 'a' # c
```
```kotlin edit
doc["s"] = Value.Integer(5L)
```
```toml expected
s = 5 # c
```

### R-076: replacing a dotted key's value keeps the dotted spelling and comment
rule: 3   version: 1.0
```toml input
# about a.b
a.b = 1 # c
a.c = 2
```
```kotlin edit
doc["a"]["b"] = 2L
```
```toml expected
# about a.b
a.b = 2 # c
a.c = 2
```

### R-077: replacing a value inside an inline table keeps the table's comment
rule: 3   version: 1.0
```toml input
t = { a = 1, b = 2 } # t
```
```kotlin edit
doc["t"]["a"] = 9L
```
```toml expected
t = { a = 9, b = 2 } # t
```

### R-078: replacing a multi-line array with a scalar drops its inner comments and keeps the one after `]`
rule: 1, 3   version: 1.0
```toml input
# above
a = [
  1, # one
  # mid
  2,
] # arr
b = 1
```
```kotlin edit
doc["a"] = 5L
```
```toml expected
# above
a = 5 # arr
b = 1
```

### R-079: replacing a scalar with an inline table is value-to-value and keeps the comment
rule: 3   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
x = 1 # one
```
```kotlin edit
doc["x"] = Value.InlineTable(inlineTableOf("k" to Value.Integer(1L)).getOrFail())
```
```toml expected
x = { k = 1 } # one
```

### R-080: replacing a scalar with an array keeps the comment
rule: 3   version: 1.0
```toml input
x = 1 # one
```
```kotlin edit
doc["x"] = Value.Array(tomlArrayOf(Value.Integer(1L), Value.Integer(2L)).getOrFail())
```
```toml expected
x = [1, 2] # one
```

### R-081: replacing a multi-line inline table with a scalar drops its inner comments
rule: 1, 3   version: 1.1
```toml input
t = {
  # inner
  a = 1, # one
} # tee
```
```kotlin edit
doc["t"] = 5L
```
```toml expected
t = 5 # tee
```

### R-082: replacing a value in a table through edit keeps the comment
rule: 3   version: 1.0
```toml input
# server
[server] # srv
# port
port = 80 # p
host = "a"
```
```kotlin edit
doc.edit { table("server") { "port" to 9090 } }
```
```toml expected
# server
[server] # srv
# port
port = 9090 # p
host = "a"
```

### R-083: put replaces a value and keeps its comment
rule: 3   version: 1.0
```toml input
[t]
k = 1 # kay
```
```kotlin edit
doc["t"].getOrFail().standardTableOrNull!!.put("k", Item(Value.Integer(2L)))
```
```toml expected
[t]
k = 2 # kay
```

### R-084: assign on an item replaces its value and keeps the comment
rule: 3   version: 1.0
```toml input
k = 1 # kay
```
```kotlin edit
doc["k"].getOrFail().assign(Item(Value.Integer(7L)))
```
```toml expected
k = 7 # kay
```

### R-085: replacing a value in a CRLF file keeps the comment and CRLF
rule: 3   version: 1.0   line endings: CRLF
```toml input
a = 1 # one
b = 2
```
```kotlin edit
doc["a"] = 3L
```
```toml expected
a = 3 # one
b = 2
```

## Replacing array elements

### R-086: replacing an element of a single-line array
rule: 3, 6   version: 1.0
```toml input
a = [1, 2, 3] # c
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.replace(1, Value.Integer(9L))
```
```toml expected
a = [1, 9, 3] # c
```

### R-087: replacing an element on its own line keeps its comment
rule: 3, 6   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a = [
  # first
  1, # one
  2, # two
]
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.replace(0, Value.Integer(10L))
```
```toml expected
a = [
  # first
  10, # one
  2, # two
]
```

### R-088: replacing the last element without a trailing comma keeps the comment on its line
rule: 3, 6   version: 1.0
```toml input
a = [
  1,
  2 # two
]
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.replace(1, Value.String("x"))
```
```toml expected
a = [
  1,
  "x" # two
]
```

### R-089: replacing a nested array element with a scalar drops the comments inside the old element
rule: 1, 3   version: 1.0
```toml input
a = [
  [
    1, # inner
  ], # outer
  2,
]
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.replace(0, Value.Integer(0L))
```
```toml expected
a = [
  0, # outer
  2,
]
```

## Replacing with a different structure

### R-090: the owner's example: an array of tables replaced by a value drops its header comment
rule: 4   version: 1.0
```toml input
[[x]] # here is x
a = 1
```
```kotlin edit
doc["x"] = 0L
```
```toml expected
x = 0
```

### R-091: an array of tables replaced by a value keeps the lines above its first header
rule: 4   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a = 1
# about x
[[x]] # here is x
b = 1
# second
[[x]]
b = 2
```
```kotlin edit
doc["x"] = 0L
```
```toml expected
a = 1
# about x
x = 0
```

### R-092: an array of tables after a table, replaced by a value, prints among the root values
rule: 4, 9   version: 1.0
settled: owner ruling 2026-10-05 (Q1): the comment above an emptied or replaced array of tables moves up with its new line.
```toml input
[a]
x = 1

# fruits
[[fruit]] # here
name = "a"
```
```kotlin edit
doc["fruit"] = 0L
```
```toml expected

# fruits
fruit = 0

[a]
x = 1
```

### R-093: a table replaced by a value drops its header comment and body comments
rule: 4   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a = 1
# about t
[t] # tee
# k
k = 1 # kay
```
```kotlin edit
doc["t"] = 5L
```
```toml expected
a = 1
# about t
t = 5
```

### R-094: a table replaced by a value keeps the next table's blank line and comment
rule: 4   version: 1.0
```toml input
[t] # tee
k = 1

# about u
[u]
m = 2
```
```kotlin edit
doc["t"] = 5L
```
```toml expected
t = 5

# about u
[u]
m = 2
```

### R-095: a table with sub-tables replaced by a value drops the sub-tables' comments
rule: 4   version: 1.0
```toml input
[t]
k = 1

# about t.s
[t.s] # ess
m = 2

# about u
[u]
```
```kotlin edit
doc["t"] = 5L
```
```toml expected
t = 5

# about u
[u]
```

### R-096: a value replaced by a table prints the table after the root values
rule: 4   version: 1.0
settled: owner ruling 2026-10-05 (Q2): a new header gets its blank line above the comment that introduces it.
```toml input
# about x
x = 1 # one
y = 2
```
```kotlin edit
doc["x"] = standardTableOf("k" to Item(Value.Integer(1L))).getOrFail()
```
```toml expected
y = 2

# about x
[x]
k = 1
```

### R-097: the last root value replaced by a table keeps the comment above, then the new header
rule: 4   version: 1.0
settled: owner ruling 2026-10-05 (Q2): a new header after the root values gets its blank line above the comment that introduces it.
```toml input
a = 1
# about x
x = 1 # one
```
```kotlin edit
doc["x"] = standardTableOf("k" to Item(Value.Integer(1L))).getOrFail()
```
```toml expected
a = 1

# about x
[x]
k = 1
```

### R-098: an inline table replaced by a standard table drops its comments
rule: 4   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
z = 0
t = { a = 1 } # tee
```
```kotlin edit
doc["t"] = standardTableOf("a" to Item(Value.Integer(1L))).getOrFail()
```
```toml expected
z = 0

[t]
a = 1
```

### R-099: a table replaced by an inline table value keeps the lines above its header
rule: 4   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
x = 1

# about t
[t] # tee
k = 1 # kay
```
```kotlin edit
doc["t"] = Value.InlineTable(inlineTableOf("m" to Value.Integer(2L)).getOrFail())
```
```toml expected
x = 1

# about t
t = { m = 2 }
```

### R-100: makeValue turns a table into an inline table and drops the comments inside it
rule: 4, 8   version: 1.0
settled: owner ruling 2026-10-05 (Q6): makeValue on one line keeps the header comment after `}`.
```toml input
x = 1

# about t
[t] # tee
# a
a = 1 # one
```
```kotlin edit
doc["t"].getOrFail().makeValue()
```
```toml expected
x = 1

# about t
t = { a = 1 } # tee
```

### R-101: a table replaced by another table keeps the comments above its header
rule: 4   version: 1.0
settled: owner ruling 2026-10-05 (Q7): a table replaced by a table keeps its header comment.
```toml input
# about t
[t] # tee
k = 1 # kay

[u]
```
```kotlin edit
doc["t"] = standardTableOf("m" to Item(Value.Integer(2L))).getOrFail()
```
```toml expected
# about t
[t] # tee
m = 2

[u]
```

### R-102: replacing one array-of-tables element keeps the comments above other elements
rule: 4, 5   version: 1.0
settled: owner ruling 2026-10-05 (Q7): a table replaced by a table keeps its header comment.
```toml input
# first
[[p]] # one
n = 1 # en
# second
[[p]]
n = 2
```
```kotlin edit
doc["p"].getOrFail().arrayOfTablesOrNull!!.replace(0, standardTableOf("n" to Item(Value.Integer(9L))).getOrFail())
```
```toml expected
# first
[[p]] # one
n = 9
# second
[[p]]
n = 2
```

### R-103: a value replaced by an array of tables
rule: 4   version: 1.0
settled: owner ruling 2026-10-05 (Q2): a new header after the root values gets its blank line above the comment that introduces it.
```toml input
# about x
x = 1 # one
```
```kotlin edit
doc["x"] = Item(ArrayOfTables().apply { add(standardTableOf("n" to Item(Value.Integer(1L))).getOrFail()) })
```
```toml expected
# about x
[[x]]
n = 1
```

### R-104: an array value replaced by an array of tables drops the comments inside the array
rule: 4   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a = 1
x = [
  1, # one
] # arr
```
```kotlin edit
doc["x"] = Item(ArrayOfTables().apply { add(standardTableOf("n" to Item(Value.Integer(1L))).getOrFail()) })
```
```toml expected
a = 1

[[x]]
n = 1
```

## Layout changes

### R-105: making a table dotted keeps the lines above its header
rule: 8   version: 1.0
settled: owner ruling 2026-10-05 (Q5): a table or inline table made dotted, or a header again, keeps the lines above its key and each value's same-line comment; its header comment, the comment after its braces and the comment lines inside go.
```toml input
x = 1

# about t
[t] # tee
# k
k = 1 # kay
```
```kotlin edit
doc["t"].getOrFail().standardTableOrNull!!.isDotted = true
```
```toml expected
x = 1

# about t
t.k = 1 # kay
```

### R-106: making a dotted table a header table keeps the lines above its first dotted key
rule: 8   version: 1.0
settled: owner ruling 2026-10-05 (Q5): a table or inline table made dotted, or a header again, keeps the lines above its key and each value's same-line comment; its header comment, the comment after its braces and the comment lines inside go.
```toml input
x = 1
# about t
t.a = 1 # one
t.b = 2
```
```kotlin edit
doc["t"].getOrFail().standardTableOrNull!!.isDotted = false
```
```toml expected
x = 1

# about t
[t]
a = 1 # one
b = 2
```

### R-107: making an inline table dotted keeps the comment above its key
rule: 8   version: 1.0
settled: owner ruling 2026-10-05 (Q5): a table or inline table made dotted, or a header again, keeps the lines above its key and each value's same-line comment; its header comment, the comment after its braces and the comment lines inside go.
```toml input
x = 1
# about t
t = { a = 1, b = 2 } # tee
```
```kotlin edit
doc["t"].getOrFail().inlineTableOrNull!!.isDotted = true
```
note: corrected: kotoml keeps the comment after the closing brace on the last dotted line; the new-layout rule lets it go but does not require it.
```toml expected
x = 1
# about t
t.a = 1
t.b = 2
```

### R-108: hiding an implicit table's header leaves its comments where the header stood
rule: 8   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
# about a
[a] # hdr
# about a.b
[a.b]
x = 1
```
```kotlin edit
doc["a"].getOrFail().standardTableOrNull!!.isImplicit = true
```
```toml expected
# about a
# hdr
# about a.b
[a.b]
x = 1
```

## Open questions

1. R-015: removing the last key of a file without a final newline: does the output keep the newline after the previous line?
2. R-018: a comment between two dotted lines of a removed dotted table: inside the structure (dropped) or above a line (kept)?
3. R-025: a comment after a table's last key at end of file: inside the removed table or after it?
4. R-026, R-027: which header is the "first header" of a table whose own header is implicit or printed after a sub-table's?
5. R-032 to R-034: removing one array-of-tables element: does the comment above the removed element stay, as for a removed key?
6. R-035, R-055: the filter calls on `ArrayOfTables` and `TomlArray` are assumed to be `removeAll { ... }`.
7. R-038, R-092: an emptied or value-replaced array of tables that stood after a table must print among the root values; where do the comments above its old header go?
8. R-042, R-043, R-060: is the spacing next to `[`/`]`/`}` normalised when the first or last element or entry goes?
9. R-047: the comma left by the previous element when the last one (without trailing comma) is removed: kept as a trailing comma?
10. R-051, R-052: removing an element that shares a line with another element or with the bracket: what happens to the spacing and the line's comment?
11. R-079: a scalar replaced by an inline table: value-to-value (rule 3, comment kept) or a structure change (rule 4)?
12. R-087: does rule 3 (keep the same-line comment) apply to replacing an array element?
13. R-091, R-093, R-099, R-100: after a structure change to a value, do the lines above the old header land directly above the new `key = value` line?
14. R-096 to R-098, R-103, R-104, R-106: a value replaced by a table: do the lines above the old key stay where they printed, and is the new header preceded by a blank line?
15. R-100, R-105: does a header's same-line comment survive `makeValue` or `isDotted = true`?
16. R-101, R-102: replacing a table with a table (or an array-of-tables element with a table) is not a change of structure: is the header's same-line comment kept?
17. R-108: where does a hidden implicit header's same-line comment go?
