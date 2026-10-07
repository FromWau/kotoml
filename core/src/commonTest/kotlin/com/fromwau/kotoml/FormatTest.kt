package com.fromwau.kotoml

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** `format()` fixes spacing, key quoting, blank lines, indentation and comments, keeping layouts and spellings. */
class FormatTest {

    private fun parse(
        text: String,
        version: TomlVersion = TomlVersion.V1_1,
    ): TomlDocument = TomlDocument.parse(text, version).assertSuccess()

    private fun formatted(
        text: String,
        version: TomlVersion = TomlVersion.V1_1,
    ): String = parse(text, version).apply { format() }.toString()

    @Test
    fun `the owner's example formats as ruled`() {
        val text = "#top\n  name   =   'kotoml'    #the name\nport=0x1F90\nratio = 1e0\nlist = [ 1 ,2,  3 , ]\n\n\n" +
            "[ server ]\n  host=\"h\"\n"
        val expected = "# top\nname = 'kotoml' # the name\nport = 0x1F90\nratio = 1e0\nlist = [1, 2, 3]\n\n" +
            "[server]\nhost = \"h\"\n"
        assertEquals(expected, formatted(text))
    }

    @Test
    fun `a key that can be bare prints bare and others stay quoted`() {
        val text = "'q' = 1\n\"a b\" = 2\n\"c\" = 3\n\"\" = 4\n[ 'x' . \"y z\" ]\n'k' = 5\n"
        assertEquals("q = 1\n\"a b\" = 2\nc = 3\n\"\" = 4\n\n[x.\"y z\"]\nk = 5\n", formatted(text))
    }

    @Test
    fun `spacing around equals signs and commas and inside braces is one space`() {
        val text = "a=1\nb  =  2\nt = {a=1,b=2}\ne = { }\nf = [ ]\ng = [1 , 2,3]\nh = [ { x=1 } , [ 2 ] ]\n"
        val expected = "a = 1\nb = 2\nt = { a = 1, b = 2 }\ne = {}\nf = []\ng = [1, 2, 3]\nh = [{ x = 1 }, [2]]\n"
        assertEquals(expected, formatted(text))
    }

    @Test
    fun `runs of blank lines become one and none open or end the file`() {
        assertEquals("a = 1\n\nb = 2\nc = 3\n", formatted("\n\na = 1\n\n\n\nb = 2\nc = 3\n\n\n"))
    }

    @Test
    fun `a single blank line between key lines stays`() {
        assertEquals("a = 1\n\nb = 2\n", formatted("a = 1\n\nb = 2\n"))
    }

    @Test
    fun `each header has one blank line above it unless it opens the file`() {
        assertEquals("[a]\nx = 1\n\n[b]\n\n[c]\n", formatted("[a]\nx = 1\n\n\n[b]\n[c]\n"))
    }

    @Test
    fun `the blank line above a header goes above the comment lines right above it`() {
        val text = "a = 1\n# about b\n# more\n[b]\nc = 2\n# not about d\n\n[d]\n"
        assertEquals("a = 1\n\n# about b\n# more\n[b]\nc = 2\n# not about d\n\n[d]\n", formatted(text))
        assertEquals("# about a\n[a]\n", formatted("\n# about a\n[a]\n"))
    }

    @Test
    fun `indentation goes`() {
        val text = "  a = 1\n[t]\n    # about b\n    b = 2\n\t[[u]]\n\t  c = 3\n"
        assertEquals("a = 1\n\n[t]\n# about b\nb = 2\n\n[[u]]\nc = 3\n", formatted(text))
    }

    @Test
    fun `a comment takes one space after its hash and one before it on a line`() {
        val text = "#c\na = 1   #d\n#\n##e\n#  f\nb = 2#g\n"
        assertEquals("# c\na = 1 # d\n#\n## e\n#  f\nb = 2 # g\n", formatted(text))
    }

    @Test
    fun `an array or inline table on one line loses its trailing comma`() {
        assertEquals("a = [1, 2]\nt = { a = 1 }\n", formatted("a = [1, 2,]\nt = { a = 1, }\n"))
    }

    @Test
    fun `an array across lines gains a trailing comma and the standard indent`() {
        val text = "a = [\n1,\n      2\n]\nb = [1,\n  2]\n"
        assertEquals("a = [\n  1,\n  2,\n]\nb = [\n  1,\n  2,\n]\n", formatted(text))
    }

    @Test
    fun `an inline table across lines gains a trailing comma in TOML 1_1`() {
        assertEquals("t = {\n  a = 1,\n  b = 2,\n}\n", formatted("t = {\na=1,\n    b=2\n}\n"))
    }

    @Test
    fun `value spellings stay`() {
        val text = "s = 'lit'\nn = 0x1F90\nf = 1e0\nt = 07:32\nm = '''\n  two\n    lines'''\nb = 1_000\n"
        assertEquals(text, formatted(text))
    }

    @Test
    fun `each line keeps its own line ending`() {
        assertEquals("a = 1\r\n\r\n[t]\r\nb = 2\r\n", formatted("a=1\r\n\r\n\r\n[ t ]\r\nb=2\r\n"))
        assertEquals("a = 1\nb = 2\r\nc = [\r\n  1,\r\n]\n", formatted("a=1\nb=2\r\nc=[\r\n1\r\n]\n"))
    }

    @Test
    fun `a TOML 1_0 document formats as TOML 1_0`() {
        val text = "t = 07:32:00\nx = {a=1}\na = [\n1\n]\n"
        val doc = parse(text, TomlVersion.V1_0)
        doc.format()
        assertEquals("t = 07:32:00\nx = { a = 1 }\na = [\n  1,\n]\n", doc.toString())
        assertEquals(TomlVersion.V1_0, doc.version)
    }

    @Test
    fun `dotted keys stay dotted`() {
        val text = "a . b = 1\n'a'.c=2\n[t]\n  x . \"y\" . z = 3\n"
        val doc = parse(text)
        doc.format()
        assertEquals("a.b = 1\na.c = 2\n\n[t]\nx.y.z = 3\n", doc.toString())
        assertEquals(TableLayout.Dotted, doc["a"].assertSuccess().layout)
    }

    @Test
    fun `arrays and inline tables across lines keep their comments`() {
        val text = "a = [ # open\n    1,   # one\n  # above two\n      2\n\n\n  # end\n]\n" +
            "t = { # t\n     x = 1,  #x\n  y = [\n3, 4\n  ] }\n"
        val expected = "a = [ # open\n  1, # one\n  # above two\n  2,\n\n  # end\n]\n" +
            "t = { # t\n  x = 1, # x\n  y = [\n    3, 4,\n  ],\n}\n"
        assertEquals(expected, formatted(text))
    }

    @Test
    fun `an inline table across lines inside a one-line array indents from the array's line`() {
        assertEquals("a = [{\n  x = 1,\n}]\n", formatted("a = [{\n    x = 1\n}]\n"))
    }

    @Test
    fun `arrays of tables keep their headers`() {
        val text = "[[ x ]]\nn=1\n[[ x ]]\nn=2\n  [ x . y ]\n  m = 3\n"
        assertEquals("[[x]]\nn = 1\n\n[[x]]\nn = 2\n\n[x.y]\nm = 3\n", formatted(text))
    }

    @Test
    fun `formatting one table leaves the rest of the document as it is`() {
        val doc = parse("a=1\n[ t ]\n  b=2\n  [ t . s ]\n  c  =  3\n[ u ]\n  d=4\n")
        doc["t"].assertSuccess().standardTableOrNull!!.format()
        assertEquals("a=1\n\n[t]\nb = 2\n\n[t.s]\nc = 3\n[ u ]\n  d=4\n", doc.toString())
    }

    @Test
    fun `formatting one slot formats its line and leaves the others`() {
        val doc = parse("a   =   [ 1 ,2 ]   #c\nb=2\n")
        doc["a"].assertSuccess().format()
        assertEquals("a = [1, 2] # c\nb=2\n", doc.toString())
    }

    @Test
    fun `formatting an array formats only what is between its brackets`() {
        val doc = parse("a   =   [ 1 ,2, ]   #c\nb=2\n")
        doc["a"].assertSuccess().arrayOrNull!!.format()
        assertEquals("a   =   [1, 2]   #c\nb=2\n", doc.toString())
    }

    @Test
    fun `formatting an inline table value formats only what is between its braces`() {
        val doc = parse("t =  {a=1,b={c=2}}\n")
        doc["t"].assertSuccess().valueOrNull!!.format()
        assertEquals("t =  { a = 1, b = { c = 2 } }\n", doc.toString())
    }

    @Test
    fun `formatting a dotted table formats its lines`() {
        val doc = parse("a . b=1\nc=2\n'a' . d=3\n")
        doc["a"].assertSuccess().standardTableOrNull!!.format()
        assertEquals("a.b = 1\nc=2\na.d = 3\n", doc.toString())
    }

    @Test
    fun `formatting an array of tables formats every element`() {
        val doc = parse("a=1\n[[ x ]]\nn=1\n[[ x ]]\nn=2\n")
        doc["x"].assertSuccess().arrayOfTablesOrNull!!.format()
        assertEquals("a=1\n\n[[x]]\nn = 1\n\n[[x]]\nn = 2\n", doc.toString())
    }

    @Test
    fun `formatting the root formats the whole document`() {
        val doc = parse("\n\na=1\n[ t ]\nb=2\n\n\n")
        doc.root.format()
        assertEquals("a = 1\n\n[t]\nb = 2\n", doc.toString())
    }

    @Test
    fun `formatting a key makes it bare when it can be and drops its own spacing`() {
        val doc = parse("'a' . b = 1\n[[ 'x' ]]\n[[x]]\n")
        doc.root.keyOrNull("a")!!.format()
        doc.root.keyOrNull("x")!!.format()
        // The space before `b` is the line's own key's, which stays.
        assertEquals("a. b = 1\n[[x]]\n[[x]]\n", doc.toString())
    }

    @Test
    fun `formatting a key removes the indentation of its line`() {
        val doc = parse("x = 0\n# c\n  a   = 1\n   b = 2\n")
        doc.root.keyOrNull("a")!!.format()
        doc.root.keyOrNull("b")!!.format()
        assertEquals("x = 0\n# c\na = 1\nb = 2\n", doc.toString())
    }

    @Test
    fun `a scalar value holds nothing to format`() {
        val value = Value.parse("'lit'").assertSuccess()
        value.format()
        assertEquals("'lit'", value.toString())
    }

    @Test
    fun `values stored nowhere format on their own`() {
        val array = Value.parse("[ 1 ,\n2 ]").assertSuccess().arrayOrNull!!
        array.format()
        assertEquals("[\n  1,\n  2,\n]", array.toString())
        val table = Value.parse("{a=1,b=2,}").assertSuccess().inlineTableOrNull!!
        table.format()
        assertEquals("{ a = 1, b = 2 }", table.toString())
    }

    @Test
    fun `a table stored nowhere formats on its own`() {
        val table = parse("[t]\n  a=1\n  'b'  =  [ 2 ]\n")["t"].assertSuccess().copy().standardTableOrNull!!
        table.format()
        assertEquals("a = 1\nb = [2]\n", table.toString())
    }

    @Test
    fun `an edited document formats`() {
        val doc = parse("# top\na=1\n# about b\nb=2\n\n[ t ]\n  c=3\n")
        doc.remove("b", force = true)
        doc["t"]["d"] = 4L
        doc["u"] = standardTableOf("e" to Item(Value.Integer(5L))).assertSuccess()
        doc.format()
        assertEquals("# top\na = 1\n\n[t]\nc = 3\nd = 4\n\n[u]\ne = 5\n", doc.toString())
    }

    @Test
    fun `formatting keeps every object`() {
        val doc = parse("[ t ]\na = [ 1 ]\n")
        val table = doc["t"].assertSuccess().standardTableOrNull!!
        val array = doc["t"]["a"].assertSuccess().arrayOrNull!!
        doc.format()
        assertSame(table, doc["t"].assertSuccess().standardTableOrNull)
        assertSame(array, doc["t"]["a"].assertSuccess().arrayOrNull)
        array.add(2L)
        assertEquals("[t]\na = [1, 2]\n", doc.toString())
    }

    @Test
    fun `formatting twice gives the same text`() {
        val text = "#top\n  name   =   'kotoml'    #the name\na = [ # open\n    1,   # one\n  2 ]\n\n\n" +
            "# about s\n[ s ]\n  t = {\n  x = 1 }\n[[ u ]]\n"
        val doc = parse(text)
        doc.format()
        val once = doc.toString()
        doc.format()
        assertEquals(once, doc.toString())
        assertEquals(once, formatted(once))
    }

    @Test
    fun `every valid suite file formats to TOML holding the same data and formats the same again`() {
        val root = "../toml-test/tests/valid"
        val failures = walkFiles(root)
            .filter { it.endsWith(".toml") }
            .mapNotNull { file -> failureOf(file, readBytes("$root/$file").decodeToString()) }
        assertEquals(emptyList(), failures.take(10), "${failures.size} failures")
    }

    private fun failureOf(
        name: String,
        text: String,
    ): String? {
        val version = TomlVersion.entries.lastOrNull { TomlDocument.parse(text, it) is Result.Success } ?: return null
        val doc = parse(text, version)
        doc.format()
        val once = doc.toString()
        val read = when (val parsed = TomlDocument.parse(once, version)) {
            is Result.Success -> parsed.value
            is Result.Error -> return "$name: formatted text does not read back: ${parsed.error}\n$once"
        }
        if (!read.toTagged().sameAs(parse(text, version).toTagged())) return "$name: data changed\n$once"
        val lost = commentsIn(text) - commentsIn(once).toSet()
        if (lost.isNotEmpty()) return "$name: lost $lost\n$once"
        val twice = read.apply { format() }.toString()
        if (twice != once) return "$name: formatting again changed\n--- once:\n$once--- twice:\n$twice"
        return null
    }

    private val edited = listOf(
        "# top\na = 1 # one\n# about b\nb = 2 # two\n\n# the end\n",
        "# about a.x\na.x = 1 # x\n# about b\nb = 2\n# about a.y\na.y = 3 # y\n",
        "# server\n[server] # header\n# host\nhost = 'h' # h\n\n# client\n[client]\nport = 1 # p\n# after\n",
        "# first\n[[x]] # one\nn = 1 # n\n\n# between\n[y]\n\n# second\n[[x]]\nn = 2\n# trailing\n",
        "a = [\n  # above one\n  1, # one\n  [2, 3], # pair\n  # above last\n  { x = 1 },\n  # end\n] # array\n",
        "s = [ # s\n  { x = 1 }, # one\n  { # two\n    y = 2, # y\n  },\n] # end\nt = { # t\n  z = 3 # z\n} # u\n",
        "a = 1 # one\r\n# about b\r\nb = 2\r\n\r\n# about t\r\n[t] # t\r\nc = 3 # c",
    )

    @Test
    fun `formatting part of an edited document keeps its data and comments and formatting it whole is stable`() {
        val failures = edited.withIndex().flatMap { (index, text) ->
            (0 until 40).mapNotNull { seed -> editedFailureOf("hand-written $index", text, seed) }
        }
        assertEquals(emptyList(), failures.take(10), "${failures.size} failures")
    }

    private fun editedFailureOf(
        name: String,
        text: String,
        seed: Int,
    ): String? {
        val doc = parse(text)
        val random = Random(seed)
        val edits = List(8) { Edits(doc, random).applyOne() }
        val before = doc.toString()
        if (TomlDocument.parse(before, doc.version) is Result.Error) return null
        val data = doc.toTagged()
        val places = Edits(doc, random)
        val target = when (random.nextInt(4)) {
            0 -> doc.also { it.format() }
            1 -> places.tables.random(random).also { it.format() }
            2 -> places.slots.randomOrNull(random)?.also { it.format() }
            else -> places.arrays.randomOrNull(random)?.also { it.format() }
        }
        val once = doc.toString()
        val where = "$name seed $seed after ${edits.joinToString(" ; ")} then formatting ${target ?: "nothing"}"
        val read = when (val parsed = TomlDocument.parse(once, doc.version)) {
            is Result.Success -> parsed.value
            is Result.Error -> return "$where: does not parse ${parsed.error}\n--- before:\n$before--- after:\n$once"
        }
        val lost = commentsIn(before) - commentsIn(once).toSet()
        val problem = when {
            !read.toTagged().sameAs(data) || !doc.toTagged().sameAs(data) -> "data changed"
            lost.isNotEmpty() -> "lost $lost"
            else -> null
        }
        if (problem != null) return "$where: $problem\n--- before:\n$before--- after:\n$once"
        doc.format()
        val whole = doc.toString()
        doc.format()
        if (doc.toString() != whole) return "$where: formatting again changed\n--- once:\n$whole--- twice:\n$doc"
        return null
    }

    @Test
    fun `the formatted owner example reads back as the same data`() {
        val text = "#top\n  name   =   'kotoml'    #the name\nlist = [ 1 ,2,  3 , ]\n[ server ]\n  host=\"h\"\n"
        val doc = parse(text)
        doc.format()
        assertTrue(parse(doc.toString()).toTagged().sameAs(parse(text).toTagged()))
    }

    // Formats the document made by removing [removed] from the dotted table [dottedKey] of `t` and making `t` a header.
    private fun assertRemovedThenMadeHeaderFormatsOnce(
        text: String,
        dottedKey: String,
        removed: String,
    ) {
        val doc = parse(text)
        doc["t"][dottedKey].assertSuccess().valueOrNull!!.inlineTableOrNull!!.remove(removed, force = true)
        doc["t"].assertSuccess().setLayout(TableLayout.Header, force = true).assertSuccess()
        val printed = doc.toString()
        doc.format()
        assertEquals(formatted(printed), doc.toString())
        doc.format()
        assertEquals(formatted(printed), doc.toString())
    }

    @Test
    fun `a comment line kept above an emptied key prints once after formatting`() {
        assertRemovedThenMadeHeaderFormatsOnce("t = {\n  # about c\n  b.c = 1,\n}\n", "b", "c")
    }

    @Test
    fun `a comment line kept above an emptied key with a key after it prints once after formatting`() {
        assertRemovedThenMadeHeaderFormatsOnce("t = {\n  # about c\n  b.c = 1,\n  d = 2,\n}\n", "b", "c")
    }

    @Test
    fun `a comment line kept below the last key of a dotted table prints once after formatting`() {
        assertRemovedThenMadeHeaderFormatsOnce("t = {\n  b.c = 1,\n  # about d\n  b.d = 2,\n}\n", "b", "d")
    }

    @Test
    fun `a CRLF comment line kept above an emptied key prints once after formatting`() {
        assertRemovedThenMadeHeaderFormatsOnce("t = {\r\n  # about c\r\n  b.c = 1,\r\n}\r\n", "b", "c")
    }

    @Test
    fun `a comment above a later dotted key in braces stays above that key after a header and formatting`() {
        val doc = parse("t = {\n  x = 0,\n  b.c = 1,\n  # about d\n  b.d = 2,\n}\n")
        doc["t"].assertSuccess().setLayout(TableLayout.Header).assertSuccess()
        doc.format()
        assertEquals("[t]\nx = 0\nb.c = 1\n# about d\nb.d = 2\n", doc.toString())
    }

    @Test
    fun `a comment kept in a dotted table in braces stored into a table stays on its own line after formatting`() {
        val doc = parse("s = {\n  b.c = 1,\n  # about d\n\n  b.d = 2,\n}\n[t]\nx = 0\n")
        doc["s"]["b"].assertSuccess().valueOrNull!!.inlineTableOrNull!!.remove("d").assertSuccess()
        doc["t"].assertSuccess().standardTableOrNull!!["b"] = doc["s"]["b"].assertSuccess()
        doc.format()
        val expected = "s = {\n  b.c = 1,\n  # about d\n}\n\n[t]\nb.c = 1\n# about d\nx = 0\n"
        assertEquals(expected, doc.toString())
        doc.format()
        assertEquals(expected, doc.toString())
    }
}
