# Comment scenarios: restructuring and adding

Each `toml` block is the full file: every line between the fences, each ending in `\n` (or `\r\n` where the
header says `line endings: CRLF`). `doc` is the parsed input; `getOrFail()` unwraps a `Result`.

## Adding keys

### S-001: a key appended to the root goes below the last key and its comments
rule: 1, 9   version: 1.0
```toml input
# about a
a = 1 # one
```
```kotlin edit
doc["b"] = 2L
```
```toml expected
# about a
a = 1 # one
b = 2
```

### S-002: a root key appended before the first table leaves the comment above the header
rule: 1, 9   version: 1.0
```toml input
name = "x" # the name

# server settings
[server]
port = 80
```
```kotlin edit
doc["version"] = "1.0"
```
```toml expected
name = "x" # the name
version = "1.0"

# server settings
[server]
port = 80
```

### S-003: a key appended to a table goes above the comment that sits directly on the next header
rule: 1, 9   version: 1.0
```toml input
[t]
k = 1 # k
# about u
[u]
x = 1
```
```kotlin edit
val t = doc["t"].getOrFail().standardTableOrNull!!
t.set("n", Value.Integer(2L))
```
```toml expected
[t]
k = 1 # k
n = 2
# about u
[u]
x = 1
```

### S-004: a key appended to a table whose trailing comment is followed by a blank line
rule: 1, 9   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
[t]
k = 1
# still about t

[u]
x = 1
```
```kotlin edit
val t = doc["t"].getOrFail().standardTableOrNull!!
t.set("n", Value.Integer(2L))
```
```toml expected
[t]
k = 1
n = 2
# still about t

[u]
x = 1
```

### S-005: a root key appended to a file that ends in a comment block goes above the comment
rule: 1, 9   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a = 1

# end of file
```
```kotlin edit
doc["b"] = 2L
```
```toml expected
a = 1
b = 2

# end of file
```

### S-006: a key appended to the last table goes above a comment directly under its last key
rule: 1, 9   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
[t]
k = 1
# end
```
```kotlin edit
val t = doc["t"].getOrFail().standardTableOrNull!!
t.set("n", Value.Integer(2L))
```
```toml expected
[t]
k = 1
n = 2
# end
```

### S-007: the first key of an empty table goes right under its commented header
rule: 1, 9   version: 1.0
```toml input
[t] # empty for now

[u]
x = 1
```
```kotlin edit
val t = doc["t"].getOrFail().standardTableOrNull!!
t.set("k", Value.Integer(1L))
```
```toml expected
[t] # empty for now
k = 1

[u]
x = 1
```

### S-008: the first key of a table whose body is only a comment
rule: 1, 9   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
[t]
# nothing yet

[u]
x = 1
```
```kotlin edit
val t = doc["t"].getOrFail().standardTableOrNull!!
t.set("k", Value.Integer(1L))
```
```toml expected
[t]
k = 1
# nothing yet

[u]
x = 1
```

### S-009: a key appended to a table keeps every other key's comments above and beside it
rule: 1, 9   version: 1.0
```toml input
[t]
# first
a = 1 # a

# second
b = 2 # b
```
```kotlin edit
doc["t"]["c"] = 3L
```
```toml expected
[t]
# first
a = 1 # a

# second
b = 2 # b
c = 3
```

### S-010: getOrPut on a key that exists changes nothing
rule: 1   version: 1.0
```toml input
[server] # srv
# port
port = 80 # http
```
```kotlin edit
val server = doc["server"].getOrFail().standardTableOrNull!!
server.getOrPut("port") { Item(Value.Integer(8080L)) }
```
```toml expected
[server] # srv
# port
port = 80 # http
```

### S-011: getOrPut on a missing key appends it below the commented keys
rule: 1, 9   version: 1.0
```toml input
[server] # srv
port = 80 # http

[db]
url = "x"
```
```kotlin edit
val server = doc["server"].getOrFail().standardTableOrNull!!
server.getOrPut("host") { Item(Value.String("localhost")) }
```
```toml expected
[server] # srv
port = 80 # http
host = "localhost"

[db]
url = "x"
```

### S-012: getOrPut at the root adds the key among the root values, above the tables
rule: 1, 9   version: 1.0
```toml input
# top
title = "t" # title

[a]
x = 1
```
```kotlin edit
doc.getOrPut("owner") { Item(Value.String("me")) }
```
```toml expected
# top
title = "t" # title
owner = "me"

[a]
x = 1
```

### S-013: a new value with its own decor brings its own comment
rule: 1, 9   version: 1.0
```toml input
# settings
host = "h" # host
```
```kotlin edit
doc["port"] = Value.Integer(8080L, Decor(prefix = " ", suffix = " # default"))
```
```toml expected
# settings
host = "h" # host
port = 8080 # default
```

### S-014: a root key added above a header that was the first line of the file
rule: 1, 9   version: 1.0
settled: owner ruling 2026-10-05: the header that opened the file gets a blank line once key/value lines print above it.
```toml input
[t] # t
a = 1
```
```kotlin edit
doc["v"] = 1L
```
```toml expected
v = 1

[t] # t
a = 1
```

### S-015: a key added to a file that is only a comment goes below it
rule: 1, 9   version: 1.0
settled: owner ruling 2026-10-05 (Q3): in a document that is only comments, new content goes below them.
```toml input
# just a comment
```
```kotlin edit
doc["a"] = 1L
```
```toml expected
# just a comment
a = 1
```

## Adding tables

### S-016: a new table after a commented key gets a blank line before its header
rule: 1, 9   version: 1.0
```toml input
a = 1 # one
```
```kotlin edit
doc["t"] = standardTableOf("k" to Item(Value.Integer(1L))).getOrFail()
```
```toml expected
a = 1 # one

[t]
k = 1
```

### S-017: a new table goes after the last table, leaving every header comment in place
rule: 1, 9   version: 1.0
```toml input
# first
[a] # a
x = 1 # x

# second
[b] # b
y = 2 # y
```
```kotlin edit
doc["c"] = standardTableOf("k" to Item(Value.Integer(1L))).getOrFail()
```
```toml expected
# first
[a] # a
x = 1 # x

# second
[b] # b
y = 2 # y

[c]
k = 1
```

### S-018: a new table in a file that ends in a comment block goes above the comment
rule: 1, 9   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
[s]
x = 1

# end
```
```kotlin edit
doc["t"] = standardTableOf("k" to Item(Value.Integer(1L))).getOrFail()
```
```toml expected
[s]
x = 1

[t]
k = 1

# end
```

### S-019: a new sub-table prints right after its parent, before the next table and its comment
rule: 1, 9   version: 1.0
```toml input
[t]
a = 1 # a

# about u
[u]
b = 2
```
```kotlin edit
doc["t"]["sub"] = standardTableOf("k" to Item(Value.Integer(1L))).getOrFail()
```
```toml expected
[t]
a = 1 # a

[t.sub]
k = 1

# about u
[u]
b = 2
```

### S-020: a new sub-table goes above a comment that sits directly on the next header
rule: 1, 9   version: 1.0
```toml input
[t]
a = 1
# about u
[u]
```
```kotlin edit
doc["t"]["sub"] = standardTableOf("k" to Item(Value.Integer(1L))).getOrFail()
```
```toml expected
[t]
a = 1

[t.sub]
k = 1
# about u
[u]
```

### S-021: a new sub-table goes after the parent's existing commented sub-tables
rule: 1, 9   version: 1.0
```toml input
[t]
a = 1

# first sub
[t.x] # x
y = 1
```
```kotlin edit
doc["t"]["z"] = standardTableOf("k" to Item(Value.Integer(1L))).getOrFail()
```
```toml expected
[t]
a = 1

# first sub
[t.x] # x
y = 1

[t.z]
k = 1
```

### S-022: a new table under an implicit parent prints after the parent's other sub-tables
rule: 1, 9   version: 1.0
```toml input
# about b
[a.b] # b
x = 1
```
```kotlin edit
doc["a"]["c"] = standardTableOf("k" to Item(Value.Integer(1L))).getOrFail()
```
```toml expected
# about b
[a.b] # b
x = 1

[a.c]
k = 1
```

### S-023: a value written into an implicit table makes its header print above the sub-table's comment
rule: 1, 9   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
# about b
[a.b]
x = 1
```
```kotlin edit
doc["a"]["k"] = 1L
```
```toml expected
[a]
k = 1
# about b
[a.b]
x = 1
```

### S-024: an empty sub-table from getOrPut prints a bare header after its parent
rule: 1, 9   version: 1.0
```toml input
[t]
a = 1 # a

# the end
[z]
```
```kotlin edit
val t = doc["t"].getOrFail().standardTableOrNull!!
t.getOrPut("sub") { Item(StandardTable()) }
```
```toml expected
[t]
a = 1 # a

[t.sub]

# the end
[z]
```

### S-025: a table header added to a dotted-key table prints as a sub-table header
rule: 1, 9   version: 1.0
```toml input
# about a
a.b = 1 # ab
```
```kotlin edit
doc["a"]["c"] = standardTableOf("k" to Item(Value.Integer(1L))).getOrFail()
```
```toml expected
# about a
a.b = 1 # ab

[a.c]
k = 1
```

### S-026: an array of tables added through the builder to a file that is only a comment
rule: 1, 9   version: 1.0
settled: owner ruling 2026-10-05 (Q3): in a document that is only comments, new content goes below them.
```toml input
# just a comment
```
```kotlin edit
doc.edit {
    arrayOfTables("bin") {
        table { "name" to "a" }
    }
}
```
```toml expected
# just a comment

[[bin]]
name = "a"
```

## Adding array elements

### S-027: an element added to a one-line array keeps the line's comment
rule: 1, 9   version: 1.0
```toml input
# nums
a = [1, 2] # two nums
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.add(Value.Integer(3L))
```
```toml expected
# nums
a = [1, 2, 3] # two nums
```

### S-028: an element added to a multi-line array gets its own line below the last element's comment
rule: 1, 9   version: 1.0
```toml input
a = [
  1, # one
  2, # two
]
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.add(Value.Integer(3L))
```
```toml expected
a = [
  1, # one
  2, # two
  3,
]
```

### S-029: an element added after a last element that has a comment and no comma puts the comma before the comment
rule: 1, 9, 10   version: 1.0
```toml input
a = [
  1,
  2 # two
]
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.add(Value.Integer(3L))
```
```toml expected
a = [
  1,
  2, # two
  3
]
```

### S-030: an element added to a multi-line array that ends in a comment line
rule: 1, 9   version: 1.0
settled: owner ruling 2026-10-05 (Q8): a new element goes below the comment lines before `]`, indented like the elements.
```toml input
a = [
  1,
  # more later
]
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.add(Value.Integer(2L))
```
```toml expected
a = [
  1,
  # more later
  2,
]
```

### S-031: an element inserted at index 0 of a one-line array
rule: 1, 9   version: 1.0
```toml input
a = [1, 2] # nums
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.add(0, Value.Integer(0L))
```
```toml expected
a = [0, 1, 2] # nums
```

### S-032: an element inserted at index 0 of a multi-line array whose first element has a comment above
rule: 1, 9   version: 1.0
settled: fixed 2026-10-05: the spacing before the closing bracket and an element inserted into an array of one element per line.
```toml input
a = [
  # first
  1,
  2,
]
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.add(0, Value.Integer(0L))
```
```toml expected
a = [
  0,
  # first
  1,
  2,
]
```

### S-033: an element inserted between commented elements goes above the next element's comment
rule: 1, 9   version: 1.0
```toml input
a = [
  1, # one
  # about two
  2, # two
]
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.add(1, Value.Integer(9L))
```
```toml expected
a = [
  1, # one
  9,
  # about two
  2, # two
]
```

### S-034: replacing an array element keeps the comment above it and the one after it
rule: 1, 3   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a = [
  1, # one
  # about two
  2, # two
]
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.replace(1, Value.Integer(20L))
```
```toml expected
a = [
  1, # one
  # about two
  20, # two
]
```

### S-035: an element added to an empty multi-line array that holds only a comment
rule: 1, 9, 10   version: 1.0
settled: owner ruling 2026-10-05 (Q8): a new element goes below the comment lines before `]`, indented like the elements.
```toml input
a = [
  # none yet
]
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.add(Value.Integer(1L))
```
```toml expected
a = [
  # none yet
  1
]
```

### S-036: an inline table added to a multi-line array of inline tables
rule: 1, 9   version: 1.0
```toml input
points = [
  { x = 1, y = 2 }, # origin-ish
]
```
```kotlin edit
val p = inlineTableOf("x" to Value.Integer(3L), "y" to Value.Integer(4L)).getOrFail()
doc["points"].getOrFail().arrayOrNull!!.add(Value.InlineTable(p))
```
```toml expected
points = [
  { x = 1, y = 2 }, # origin-ish
  { x = 3, y = 4 },
]
```

### S-037: a row added to a multi-line array of arrays
rule: 1, 9   version: 1.0
```toml input
m = [
  [1, 2], # row one
  [3, 4], # row two
]
```
```kotlin edit
val row = tomlArrayOf(Value.Integer(5L), Value.Integer(6L)).getOrFail()
doc["m"].getOrFail().arrayOrNull!!.add(Value.Array(row))
```
```toml expected
m = [
  [1, 2], # row one
  [3, 4], # row two
  [5, 6],
]
```

## Array of tables

### S-038: a new element of an array of tables prints right after the last one
rule: 1, 5, 9   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
[[x]] # first
a = 1
# after the last x

[y]
b = 2
```
```kotlin edit
val aot = doc["x"].getOrFail().arrayOfTablesOrNull!!
aot.add(standardTableOf("a" to Item(Value.Integer(2L))).getOrFail())
```
```toml expected
[[x]] # first
a = 1

[[x]]
a = 2
# after the last x

[y]
b = 2
```

### S-039: a new element of an array of tables at the end of a file that ends in a comment
rule: 1, 5, 9   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
[[x]]
a = 1
# end
```
```kotlin edit
val aot = doc["x"].getOrFail().arrayOfTablesOrNull!!
aot.add(standardTableOf("a" to Item(Value.Integer(2L))).getOrFail())
```
```toml expected
[[x]]
a = 1

[[x]]
a = 2
# end
```

### S-040: an element inserted at index 0 of an array of tables with a comment above the first header
rule: 1, 5, 9   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
title = "t"

# the xs
[[x]] # first
a = 1
```
```kotlin edit
val aot = doc["x"].getOrFail().arrayOfTablesOrNull!!
aot.add(0, standardTableOf("a" to Item(Value.Integer(0L))).getOrFail())
```
```toml expected
title = "t"

[[x]]
a = 0

# the xs
[[x]] # first
a = 1
```

### S-041: an element inserted in the middle of an array of tables goes above the next element's comment
rule: 1, 5, 9   version: 1.0
```toml input
[[x]] # one
a = 1

# second x
[[x]] # two
a = 2
```
```kotlin edit
val aot = doc["x"].getOrFail().arrayOfTablesOrNull!!
aot.add(1, standardTableOf("a" to Item(Value.Integer(9L))).getOrFail())
```
```toml expected
[[x]] # one
a = 1

[[x]]
a = 9

# second x
[[x]] # two
a = 2
```

### S-042: emptying an array of tables and adding a new element
rule: 2, 5, 7, 9   version: 1.0
settled: owner ruling 2026-10-05 (Q9): each removal keeps the lines above its own header where they print; a new element follows them.
```toml input
a = 1

# xs
[[x]] # one
k = 1
```
```kotlin edit
val aot = doc["x"].getOrFail().arrayOfTablesOrNull!!
aot.removeAt(0)
aot.add(standardTableOf("k" to Item(Value.Integer(2L))).getOrFail())
```
```toml expected
a = 1

# xs

[[x]]
k = 2
```

### S-043: a key added to one element of an array of tables goes above the next element's comment
rule: 1, 9   version: 1.0
```toml input
[[x]]
a = 1
# next one
[[x]]
a = 2
```
```kotlin edit
val aot = doc["x"].getOrFail().arrayOfTablesOrNull!!
aot.first().set("b", Value.Integer(2L))
```
```toml expected
[[x]]
a = 1
b = 2
# next one
[[x]]
a = 2
```

### S-044: an array of tables emptied entirely prints `key = []` and keeps the comment above
rule: 2, 5, 7   version: 1.0
settled: owner ruling 2026-10-05 (Q9): each removal keeps the lines above its own header where they print, unlike one clear().
```toml input
a = 1

# xs
[[x]] # one
k = 1
# between
[[x]] # two
k = 2
```
```kotlin edit
val aot = doc["x"].getOrFail().arrayOfTablesOrNull!!
aot.removeAt(1)
aot.removeAt(0)
```
```toml expected
a = 1

# xs
# between
x = []
```

## Sorting

### S-045: sorting the root moves each key's comments with it
rule: 1   version: 1.0
```toml input
# zeta
z = 1 # z
# alpha
a = 2 # a
```
```kotlin edit
doc.root.sortWith(compareBy { it.first.name })
```
```toml expected
# alpha
a = 2 # a
# zeta
z = 1 # z
```

### S-046: sorting moves a key's blank line together with its comment
rule: 1   version: 1.0
settled: owner ruling 2026-10-05: sorting keeps blank lines, and the comment block heading a file, in place.
```toml input
# zeta
z = 1 # z

# alpha
a = 2 # a
```
```kotlin edit
doc.root.sortWith(compareBy { it.first.name })
```
```toml expected
# alpha
a = 2 # a

# zeta
z = 1 # z
```

### S-047: sorting a table keeps the comment above the next header at the end
rule: 1   version: 1.0
```toml input
[t] # header
# about z
z = 1 # z
a = 2 # a
# about u
[u]
```
```kotlin edit
doc["t"].getOrFail().standardTableOrNull!!.sortWith(compareBy { it.first.name })
```
```toml expected
[t] # header
a = 2 # a
# about z
z = 1 # z
# about u
[u]
```

### S-048: sorting a table leaves its sub-table headers and their comments in place
rule: 1   version: 1.0
```toml input
[t]
b = 1 # b
a = 2 # a

# the sub
[t.sub]
c = 3
```
```kotlin edit
doc["t"].getOrFail().standardTableOrNull!!.sortWith(compareBy { it.first.name })
```
```toml expected
[t]
a = 2 # a
b = 1 # b

# the sub
[t.sub]
c = 3
```

### S-049: sorting the root reorders values and dotted keys but not headers
rule: 1   version: 1.0
```toml input
z = 1 # z
m.x = 2 # mx
a = 3 # a

[t]
k = 1
```
```kotlin edit
doc.root.sortWith(compareBy { it.first.name })
```
```toml expected
a = 3 # a
m.x = 2 # mx
z = 1 # z

[t]
k = 1
```

### S-050: sorting moves the file-head comment with the key it sits on
rule: 1   version: 1.0
settled: owner ruling 2026-10-05: sorting keeps blank lines, and the comment block heading a file, in place.
```toml input
# config file

# b key
b = 1
a = 2
```
```kotlin edit
doc.root.sortWith(compareBy { it.first.name })
```
```toml expected
# config file

a = 2
# b key
b = 1
```

### S-051: sorting a dotted-key table keeps each line's comment
rule: 1   version: 1.0
```toml input
a.z = 1 # z
a.b = 2 # b
```
```kotlin edit
doc["a"].getOrFail().standardTableOrNull!!.sortWith(compareBy { it.first.name })
```
```toml expected
a.b = 2 # b
a.z = 1 # z
```

## Copying

### S-052: copying a value over another key keeps the target's comments, not the source's
rule: 3   version: 1.0
settled: owner ruling 2026-10-05 (Q4): a copy takes only the comments inside it.
```toml input
# about a
a = 1 # one
# about b
b = 2 # two
```
```kotlin edit
doc.set("b", doc["a"].getOrFail())
```
```toml expected
# about a
a = 1 # one
# about b
b = 1 # two
```

### S-053: copying a value to a new key gives it default formatting
rule: 9   version: 1.0
settled: owner ruling 2026-10-05 (Q4): a copy takes only the comments inside it.
```toml input
a = 1 # one
```
```kotlin edit
doc.set("b", doc["a"].getOrFail())
```
```toml expected
a = 1 # one
b = 1
```

### S-054: copying a multi-line array keeps the comments inside it
rule: 1, 9   version: 1.0
settled: owner ruling 2026-10-05 (Q4): a copy takes only the comments inside it.
```toml input
a = [
  1, # one
  2,
] # the list
```
```kotlin edit
doc.set("b", doc["a"].getOrFail())
```
```toml expected
a = [
  1, # one
  2,
] # the list
b = [
  1, # one
  2,
]
```

### S-055: copying a table to a new key
rule: 1, 9   version: 1.0
settled: owner ruling 2026-10-05 (Q4): a copy takes only the comments inside it.
```toml input
# about a
[a] # a header
x = 1 # x
```
```kotlin edit
doc.set("b", doc["a"].getOrFail())
```
```toml expected
# about a
[a] # a header
x = 1 # x

[b]
x = 1 # x
```

### S-056: copying a table over a value key leaves the value's comment line in place
rule: 2, 4   version: 1.0
settled: fixed 2026-10-05 (todo 38): a copied table prints with its new parent's tables, where its key stands.
```toml input
# about b
b = 1 # b

[a]
x = 1 # x
```
```kotlin edit
doc.set("b", doc["a"].getOrFail())
```
```toml expected
# about b
[b]
x = 1 # x

[a]
x = 1 # x
```

### S-057: copying an array of tables to a new key
rule: 1, 9   version: 1.0
settled: fixed 2026-10-05 (todo 29): a copy stored elsewhere in its document prints after the tables already there.
```toml input
[[x]] # first
a = 1
# between
[[x]] # second
a = 2
```
```kotlin edit
doc.set("y", doc["x"].getOrFail())
```
```toml expected
[[x]] # first
a = 1
# between
[[x]] # second
a = 2

[[y]]
a = 1
# between
[[y]] # second
a = 2
```

### S-058: a converted copy of a table goes among the root values; the original table is untouched
rule: 1, 9   version: 1.0
```toml input
x = 0

[t] # t
a = 1 # one
```
```kotlin edit
val inline = doc["t"].getOrFail().standardTableOrNull!!.toInlineTable()
doc["u"] = Value.InlineTable(inline)
```
```toml expected
x = 0
u = { a = 1 }

[t] # t
a = 1 # one
```

## Table to inline table

### S-059: inline() keeps the comment above the header and moves the header comment to the line
rule: 4, 8   version: 1.0
settled: owner ruling 2026-10-05 (Q6): makeValue keeps a table with value comments across lines in TOML 1.1, the header comment after `{`; otherwise, or in a document read as 1.0, one line with only the header comment after `}`.
```toml input
# about t
[t] # t header
a = 1 # one
b = 2
```
```kotlin edit
doc["t"].getOrFail().inline()
```
```toml expected
# about t
t = { a = 1, b = 2 } # t header
```

### S-060: inline() keeps the blank line and comment above the header above the new key
rule: 4, 8   version: 1.0
```toml input
x = 0

# about t
[t]
a = 1 # one
```
```kotlin edit
doc["t"].getOrFail().inline()
```
```toml expected
x = 0

# about t
t = { a = 1 }
```

### S-061: inline() leaves the comment above the next table where it is
rule: 4, 8   version: 1.0
```toml input
[t]
a = 1
# about u
[u]
b = 2
```
```kotlin edit
doc["t"].getOrFail().inline()
```
```toml expected
t = { a = 1 }
# about u
[u]
b = 2
```

### S-062: inline() folds sub-tables in and drops their comments
rule: 4, 8   version: 1.0
```toml input
[t]
a = 1

# about u
[t.u] # u
b = 2

[v]
c = 3
```
```kotlin edit
doc["t"].getOrFail().inline()
```
```toml expected
t = { a = 1, u = { b = 2 } }

[v]
c = 3
```

### S-063: inline() on a dotted-key table keeps the comment above
rule: 4, 8   version: 1.0
```toml input
# about t
t.a = 1
t.b = 2
```
```kotlin edit
doc["t"].getOrFail().inline()
```
```toml expected
# about t
t = { a = 1, b = 2 }
```

### S-064: inline() on a dotted-key table with a comment on its first line
rule: 4, 8, 10   version: 1.0
settled: owner ruling 2026-10-05 (Q6): makeValue keeps a table with value comments across lines in TOML 1.1, the header comment after `{`; otherwise, or in a document read as 1.0, one line with only the header comment after `}`.
```toml input
t.a = 1 # a
t.b = 2
```
```kotlin edit
doc["t"].getOrFail().inline()
```
```toml expected
t = { a = 1, b = 2 }
```

### S-065: inline() in TOML 1.1 still writes a one-line inline table
rule: 4, 8, 9   version: 1.1
settled: owner ruling 2026-10-05 (Q6): makeValue keeps a table with value comments across lines in TOML 1.1, the header comment after `{`; otherwise, or in a document read as 1.0, one line with only the header comment after `}`.
```toml input
[t] # t
a = 1 # one
```
```kotlin edit
doc["t"].getOrFail().inline()
```
```toml expected
t = { # t
  a = 1 # one
}
```

## Dotted and back

### S-066: a table made dotted takes its header comment onto its first line (owner example)
rule: 8   version: 1.0
settled: owner ruling 2026-10-05 (Q5): a table or inline table made dotted, or a header again, keeps the lines above its key and each value's same-line comment; its header comment, the comment after its braces and the comment lines inside go.
```toml input
# about x
[x] # x header
a = 1
```
```kotlin edit
doc["x"].getOrFail().standardTableOrNull!!.isDotted = true
```
```toml expected
# about x
x.a = 1
```

### S-067: a table made dotted after a root key keeps the blank line and comment above
rule: 8   version: 1.0
settled: owner ruling 2026-10-05 (Q5): a table or inline table made dotted, or a header again, keeps the lines above its key and each value's same-line comment; its header comment, the comment after its braces and the comment lines inside go.
```toml input
title = "t"

# about x
[x] # x header
a = 1
b = 2
```
```kotlin edit
doc["x"].getOrFail().standardTableOrNull!!.isDotted = true
```
```toml expected
title = "t"

# about x
x.a = 1
x.b = 2
```

### S-068: a table made dotted keeps its keys' own comments
rule: 8   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
[x]
a = 1 # one
b = 2 # two
```
```kotlin edit
doc["x"].getOrFail().standardTableOrNull!!.isDotted = true
```
```toml expected
x.a = 1 # one
x.b = 2 # two
```

### S-069: a table made dotted where both the header and the first key have a comment
rule: 8   version: 1.0
settled: owner ruling 2026-10-05 (Q5): a table or inline table made dotted, or a header again, keeps the lines above its key and each value's same-line comment; its header comment, the comment after its braces and the comment lines inside go.
```toml input
[x] # hx
a = 1 # one
```
```kotlin edit
doc["x"].getOrFail().standardTableOrNull!!.isDotted = true
```
```toml expected
x.a = 1 # one
```

### S-070: a table made dotted keeps the comment above its first key
rule: 8   version: 1.0
settled: owner ruling 2026-10-05 (Q5): a table or inline table made dotted, or a header again, keeps the lines above its key and each value's same-line comment; its header comment, the comment after its braces and the comment lines inside go.
```toml input
[x]
# about a
a = 1
```
```kotlin edit
doc["x"].getOrFail().standardTableOrNull!!.isDotted = true
```
```toml expected
x.a = 1
```

### S-071: a table made dotted leaves the next table and its blank line alone
rule: 8   version: 1.0
settled: owner ruling 2026-10-05 (Q5): a table or inline table made dotted, or a header again, keeps the lines above its key and each value's same-line comment; its header comment, the comment after its braces and the comment lines inside go.
```toml input
[x] # hx
a = 1

[y]
b = 2
```
```kotlin edit
doc["x"].getOrFail().standardTableOrNull!!.isDotted = true
```
```toml expected
x.a = 1

[y]
b = 2
```

### S-072: dotted keys made a table get a header; the comment above stays above it
rule: 8, 9   version: 1.0
```toml input
# about x
x.a = 1 # one
x.b = 2
```
```kotlin edit
doc["x"].getOrFail().standardTableOrNull!!.isDotted = false
```
```toml expected
# about x
[x]
a = 1 # one
b = 2
```

### S-073: dotted keys after a root key made a table
rule: 8, 9   version: 1.0
settled: owner ruling 2026-10-05 (Q2): a new header after the root values gets its blank line above the comment that introduces it.
```toml input
title = 1
# about x
x.a = 1
```
```kotlin edit
doc["x"].getOrFail().standardTableOrNull!!.isDotted = false
```
```toml expected
title = 1

# about x
[x]
a = 1
```

### S-074: an inline table made dotted keeps its same-line comment on the first line
rule: 8   version: 1.0
settled: owner ruling 2026-10-05 (Q5): a table or inline table made dotted, or a header again, keeps the lines above its key and each value's same-line comment; its header comment, the comment after its braces and the comment lines inside go.
```toml input
t = { a = 1, b = 2 } # tt
```
```kotlin edit
doc["t"].getOrFail().inlineTableOrNull!!.isDotted = true
```
```toml expected
t.a = 1
t.b = 2
```

### S-075: nested multi-line inline tables made dotted twice (owner example)
rule: 8   version: 1.1
settled: owner ruling 2026-10-05 (Q5): a table or inline table made dotted, or a header again, keeps the lines above its key and each value's same-line comment; its header comment, the comment after its braces and the comment lines inside go.
```toml input
t = { u = { # open u
k = 1, # one
} # after u
}
```
```kotlin edit
doc["t"].getOrFail().inlineTableOrNull!!.isDotted = true
doc["t"]["u"].getOrFail().inlineTableOrNull!!.isDotted = true
```
```toml expected
t.u.k = 1 # one
```

### S-076: a table made dotted and back keeps the header comment on the first key
rule: 8   version: 1.0
settled: owner ruling 2026-10-05 (Q5): a table or inline table made dotted, or a header again, keeps the lines above its key and each value's same-line comment; its header comment, the comment after its braces and the comment lines inside go.
```toml input
# about x
[x] # x header
a = 1
```
```kotlin edit
val x = doc["x"].getOrFail().standardTableOrNull!!
x.isDotted = true
x.isDotted = false
```
```toml expected
# about x
[x]
a = 1
```

### S-077: a sub-table made dotted moves into its parent's body with its comments
rule: 8   version: 1.0
settled: owner ruling 2026-10-05 (Q5): a table made dotted drops its header comment.
```toml input
[a] # a
x = 1

# about b
[a.b] # b
y = 2
```
```kotlin edit
doc["a"]["b"].getOrFail().standardTableOrNull!!.isDotted = true
```
```toml expected
[a] # a
x = 1

# about b
b.y = 2
```

### S-078: an inline table inside a table made dotted keeps the next key's comment
rule: 8   version: 1.0
settled: owner ruling 2026-10-05 (Q5): a table or inline table made dotted, or a header again, keeps the lines above its key and each value's same-line comment; its header comment, the comment after its braces and the comment lines inside go.
```toml input
[s]
t = { a = 1 } # tt
# about z
z = 0
```
```kotlin edit
doc["s"]["t"].getOrFail().inlineTableOrNull!!.isDotted = true
```
```toml expected
[s]
t.a = 1
# about z
z = 0
```

## Implicit tables

### S-079: a table made implicit leaves its header comments where the header stood
rule: 8   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
# about a
[a] # a header

# about b
[a.b]
x = 1
```
```kotlin edit
doc["a"].getOrFail().standardTableOrNull!!.isImplicit = true
```
```toml expected
# about a
# a header

# about b
[a.b]
x = 1
```

### S-080: isImplicit on a table with its own values changes nothing
rule: 1, 8   version: 1.0
```toml input
# about a
[a] # a
k = 1

[a.b]
x = 1
```
```kotlin edit
doc["a"].getOrFail().standardTableOrNull!!.isImplicit = true
```
```toml expected
# about a
[a] # a
k = 1

[a.b]
x = 1
```

### S-081: isImplicit on an empty table with no sub-tables keeps its header
rule: 1, 8, 10   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
x = 1

# about a
[a] # empty
```
```kotlin edit
doc["a"].getOrFail().standardTableOrNull!!.isImplicit = true
```
```toml expected
x = 1

# about a
[a] # empty
```

## Dotted keys and headers mixed

### S-082: a key added to a dotted-key table at the root prints as another dotted line
rule: 1, 9   version: 1.0
```toml input
# about a
a.b = 1 # ab

[c]
d = 1
```
```kotlin edit
doc["a"]["e"] = 2L
```
```toml expected
# about a
a.b = 1 # ab
a.e = 2

[c]
d = 1
```

### S-083: a key added to a dotted-key table inside a section
rule: 1, 9   version: 1.0
```toml input
[a]
b.c = 1 # bc
```
```kotlin edit
doc["a"]["b"]["d"] = 2L
```
```toml expected
[a]
b.c = 1 # bc
b.d = 2
```

### S-084: a key added to a dotted-key table whose lines are interleaved with other keys
rule: 1, 9   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a.x = 1 # ax
z = 0 # z
a.y = 2 # ay
```
```kotlin edit
doc["a"]["w"] = 3L
```
```toml expected
a.x = 1 # ax
z = 0 # z
a.y = 2 # ay
a.w = 3
```

### S-085: a key added to a section whose body has dotted keys and a closing comment
rule: 1, 9   version: 1.0
```toml input
[a]
b.c = 1 # bc
z = 0 # z
# about q
[q]
```
```kotlin edit
doc["a"]["n"] = 5L
```
```toml expected
[a]
b.c = 1 # bc
z = 0 # z
n = 5
# about q
[q]
```

## Builder

### S-086: edit replaces a value and keeps its comments
rule: 3   version: 1.0
```toml input
[server] # srv
# the port
port = 80 # http
```
```kotlin edit
doc.edit {
    table("server") { "port" to 9090 }
}
```
```toml expected
[server] # srv
# the port
port = 9090 # http
```

### S-087: edit adds an array to a table above the next table's comment
rule: 1, 9   version: 1.0
```toml input
[server]
port = 80 # http

# database
[db]
url = "x"
```
```kotlin edit
doc.edit {
    table("server") {
        array("tags") { add("x") }
    }
}
```
```toml expected
[server]
port = 80 # http
tags = ["x"]

# database
[db]
url = "x"
```

### S-088: edit removes one key and adds another
rule: 2, 9   version: 1.0
```toml input
# debug flag
debug = true # remove me
name = "x" # n
```
```kotlin edit
doc.edit {
    remove("debug")
    "level" to 2
}
```
```toml expected
# debug flag
name = "x" # n
level = 2
```

### S-089: edit creates a missing table after the commented tables
rule: 1, 9   version: 1.0
```toml input
title = "t" # title

# server
[server]
port = 80
```
```kotlin edit
doc.edit {
    table("database") { "url" to "postgres://" }
}
```
```toml expected
title = "t" # title

# server
[server]
port = 80

[database]
url = "postgres://"
```

### S-090: edit adds to a multi-line array with comments
rule: 1, 9   version: 1.0
```toml input
[server]
tags = [
  "a", # first
  "b", # second
]
```
```kotlin edit
doc.edit {
    table("server") {
        array("tags") { add("c") }
    }
}
```
```toml expected
[server]
tags = [
  "a", # first
  "b", # second
  "c",
]
```

### S-091: a failed edit leaves every comment as it was
rule: 1   version: 1.0
```toml input
name = "x" # n
# about t
[t]
k = 1
```
```kotlin edit
val result = doc.edit {
    "extra" to 1
    table("name") { "a" to 1 }
}
// result is TomlEditError.TypeMismatch
```
```toml expected
name = "x" # n
# about t
[t]
k = 1
```

### S-092: edit adds an array-of-tables element before the next table's comment
rule: 1, 5, 9   version: 1.0
```toml input
[[bin]] # first
name = "a"

# libs
[lib]
path = "x"
```
```kotlin edit
doc.edit {
    arrayOfTables("bin") {
        table { "name" to "b" }
    }
}
```
```toml expected
[[bin]] # first
name = "a"

[[bin]]
name = "b"

# libs
[lib]
path = "x"
```

### S-093: edit adds a key to an inline table and keeps its line comment
rule: 1, 9   version: 1.0
```toml input
dep = { version = "1" } # pinned
```
```kotlin edit
doc.edit {
    inlineTable("dep") { "optional" to true }
}
```
```toml expected
dep = { version = "1", optional = true } # pinned
```

## Inline tables

### S-094: a key added to a one-line inline table keeps the line comment
rule: 1, 9   version: 1.0
```toml input
t = { a = 1 } # tt
```
```kotlin edit
doc["t"].getOrFail().inlineTableOrNull!!.set("b", Value.Integer(2L))
```
```toml expected
t = { a = 1, b = 2 } # tt
```

### S-095: a key added to a multi-line inline table gets its own line
rule: 1, 9   version: 1.1
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
t = {
  a = 1, # one
}
```
```kotlin edit
doc["t"].getOrFail().inlineTableOrNull!!.set("b", Value.Integer(2L))
```
```toml expected
t = {
  a = 1, # one
  b = 2,
}
```

### S-096: a key added after a commented last key with no comma puts the comma before the comment
rule: 1, 9, 10   version: 1.1
```toml input
t = {
  a = 1 # one
}
```
```kotlin edit
doc["t"].getOrFail().inlineTableOrNull!!.set("b", Value.Integer(2L))
```
```toml expected
t = {
  a = 1, # one
  b = 2
}
```

### S-097: a key added to an empty inline table
rule: 1, 9   version: 1.0
```toml input
t = {} # empty
```
```kotlin edit
doc["t"].getOrFail().inlineTableOrNull!!.set("b", Value.Integer(2L))
```
```toml expected
t = { b = 2 } # empty
```

### S-098: replacing a value in a multi-line inline table keeps its comments
rule: 3   version: 1.1
```toml input
t = {
  # about a
  a = 1, # one
  b = 2, # two
}
```
```kotlin edit
doc["t"].getOrFail().inlineTableOrNull!!.set("a", Value.Integer(10L))
```
```toml expected
t = {
  # about a
  a = 10, # one
  b = 2, # two
}
```

## Replacing with a different structure

### S-099: a value replaced by a table leaves its comment line where it printed
rule: 2, 4   version: 1.0
```toml input
# about x
x = 1 # one
y = 2 # y
```
```kotlin edit
doc["x"] = standardTableOf("k" to Item(Value.Integer(1L))).getOrFail()
```
note: corrected: owner ruling 2026-10-05 (Q3): a value replaced by a table takes the comment above it to its new header.
```toml expected
y = 2 # y

# about x
[x]
k = 1
```

### S-100: an array of tables replaced by a value (owner example)
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

### S-101: a table replaced by a value after a root key
rule: 4   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a = 0

# about x
[x] # here is x
k = 1
```
```kotlin edit
doc["x"] = 0L
```
```toml expected
a = 0

# about x
x = 0
```

### S-102: an array of tables replaced by an array value drops the comments between elements
rule: 4, 5   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
name = "n"

# the xs
[[x]] # one
a = 1

# between
[[x]] # two
a = 2
```
```kotlin edit
doc["x"] = Value.Array(tomlArrayOf(Value.Integer(1L)).getOrFail())
```
```toml expected
name = "n"

# the xs
x = [1]
```

### S-103: an inline table replaced by an inline table keeps the key's comments
rule: 3   version: 1.0
```toml input
# about t
t = { a = 1 } # tt
```
```kotlin edit
doc["t"] = Value.InlineTable(inlineTableOf("b" to Value.Integer(2L)).getOrFail())
```
```toml expected
# about t
t = { b = 2 } # tt
```

### S-104: a multi-line array replaced by an array drops the comments inside the old one
rule: 1, 3   version: 1.0
```toml input
# xs
xs = [
  1, # one
] # end
```
```kotlin edit
doc["xs"] = Value.Array(tomlArrayOf(Value.Integer(5L)).getOrFail())
```
```toml expected
# xs
xs = [5] # end
```

### S-105: a table replaced by another table
rule: 1, 4   version: 1.0
settled: owner ruling 2026-10-05 (Q7): a table replaced by a table keeps its header comment.
```toml input
# about t
[t] # t header
a = 1 # a
```
```kotlin edit
doc["t"] = standardTableOf("b" to Item(Value.Integer(2L))).getOrFail()
```
```toml expected
# about t
[t] # t header
b = 2
```

## Removing and adding again

### S-106: a key removed and added again goes to the end; the comment above stays put
rule: 2, 9   version: 1.0
```toml input
# about a
a = 1 # one
b = 2
```
```kotlin edit
doc.remove("a")
doc["a"] = 3L
```
```toml expected
# about a
b = 2
a = 3
```

### S-107: a table removed and added again goes below the comment that stayed
rule: 2, 9   version: 1.0
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
x = 1

# about t
[t] # t
k = 1
```
```kotlin edit
doc.remove("t")
doc["t"] = standardTableOf("k" to Item(Value.Integer(2L))).getOrFail()
```
```toml expected
x = 1

# about t

[t]
k = 2
```

## CRLF

### S-108: a key added to a CRLF file uses CRLF
rule: 1, 9   version: 1.0   line endings: CRLF
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
# about a
a = 1 # one
```
```kotlin edit
doc["b"] = 2L
```
```toml expected
# about a
a = 1 # one
b = 2
```

### S-109: a table added to a CRLF file uses CRLF for its blank line and lines
rule: 1, 9   version: 1.0   line endings: CRLF
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a = 1 # one
```
```kotlin edit
doc["t"] = standardTableOf("k" to Item(Value.Integer(1L))).getOrFail()
```
```toml expected
a = 1 # one

[t]
k = 1
```

### S-110: an element added to a CRLF multi-line array
rule: 1, 9   version: 1.0   line endings: CRLF
settled: owner confirmed 2026-10-05: as kotoml prints it.
```toml input
a = [
  1, # one
]
```
```kotlin edit
doc["a"].getOrFail().arrayOrNull!!.add(Value.Integer(2L))
```
```toml expected
a = [
  1, # one
  2,
]
```

## Open questions

1. S-004, S-006, S-008, S-038, S-039: comment lines after a table's last key belong to whatever follows. Does new
   content in that table go above them, even when they read as the end of the table or a note on the last key?
2. S-005, S-015, S-018, S-026, S-107: where does new content go relative to comment lines at the end of the file,
   and does a lone file-head comment stay on top?
3. S-014: does a header that was first in the file get a blank line when something now prints above it?
4. S-023: where does the header of a formerly implicit table print, and with which spacing?
5. S-030, S-032, S-035: placement, indentation and trailing comma for new elements next to comment-only lines in a
   multi-line array.
6. S-034: does rule 3 hold for array elements?
7. S-040: does a comment above the first `[[x]]` stay with that element or with the array's first slot?
8. S-042, S-107: after a removal leaves a comment behind, where does content re-added under the same key print?
9. S-046, S-050: do blank lines and a file-head comment travel with a key when sorting?
10. S-053 to S-057: which comments travel with a copied value, table or array of tables?
11. S-059, S-064, S-065, S-069, S-074, S-076: which comments a new layout keeps (header and first-line comments,
    1.1 multi-line inline tables).
12. S-068: is keeping key comments on a table made dotted required?
13. S-073, S-077: where the lines above a key go when it changes between a header and a dotted line.
14. S-079, S-081: format of a hidden header's same-line comment; whether an empty, sub-table-less table can hide its
    header.
15. S-084: placement of a new dotted line when the table's lines are interleaved.
16. S-095: is rule 9's multi-line array formatting also used for multi-line inline tables?
17. S-101, S-102: when a table becomes a value, do the lines above its header stay above the new key line?
18. S-105: is table-for-table a structure change under rule 4?
19. S-108 to S-110: line ending of new content in a CRLF file.
