package com.fromwau.kotoml

import com.fromwau.kern.result.Result
import com.fromwau.kotoml.parser.ErrorSink
import com.fromwau.kotoml.parser.Event
import com.fromwau.kotoml.parser.EventKind
import com.fromwau.kotoml.parser.TomlSource
import com.fromwau.kotoml.parser.eventReceiver
import com.fromwau.kotoml.parser.parseDocument
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Edits keep every comment they do not own. Adding, replacing a value with a value, sorting and changing how a table
 * prints keep them all; removing a value takes only the comment on its own line, and giving a table a new layout with
 * `isDotted` may take its comments, all but the lines above its key; putting an inline table on one line with
 * `isMultiline` may take the comments inside its braces. Removing or replacing whole tables
 * is covered by `RemovalPropertyTest` and the comment scope tests, which state what those take.
 */
class CommentPropertyTest {

    private val steps = 12

    private val handWritten = listOf(
        "# top\na = 1 # one\n# about b\nb = 2 # two\n\n# the end\n",
        "# about a.x\na.x = 1 # x\n# about b\nb = 2\n# about a.y\na.y = 3 # y\n",
        "# server\n[server] # header\n# host\nhost = 'h' # h\n\n# client\n[client]\nport = 1 # p\n# after\n",
        "# first\n[[x]] # one\nn = 1 # n\n\n# between\n[y]\n\n# second\n[[x]]\nn = 2\n# trailing\n",
        "a = [\n  # above one\n  1, # one\n  [2, 3], # pair\n  # above last\n  { x = 1 },\n  # end\n] # array\n",
        "# t\nt = { a = 1, b = { c = [] } } # inline\n# fruit\n[fruit]\n# apple\napple.color = 'red' # red\n",
        "a = 1 # one\r\n# about b\r\nb = 2\r\n\r\n# about t\r\n[t] # t\r\nc = 3 # c",
        "s = [ # s\n  { x = 1 }, # one\n  # above\n  { # two\n    y = 2, # y\n  },\n] # end\n" +
            "t = { # t\n  z = 3 # z\n} # u\n",
    )

    @Test
    fun `edits to hand-written documents keep every comment they do not own`() {
        val failures = handWritten.withIndex().flatMap { (index, text) ->
            (0 until 60).mapNotNull { seed -> failureOf("hand-written $index", text, seed) }
        }
        assertEquals(emptyList(), failures.take(10), "${failures.size} failures")
    }

    @Test
    fun `edits to every commented suite file keep every comment they do not own`() {
        val root = "../toml-test/tests/valid"
        val failures = walkFiles(root)
            .filter { it.endsWith(".toml") }
            .flatMap { file ->
                val text = readBytes("$root/$file").decodeToString()
                if ('#' !in text) return@flatMap emptyList()
                (0 until 5).mapNotNull { seed -> failureOf(file, text, seed) }
            }
        assertEquals(emptyList(), failures.take(10), "${failures.size} failures")
    }

    private fun failureOf(
        name: String,
        text: String,
        seed: Int,
    ): String? {
        val version = TomlVersion.entries.lastOrNull { TomlDocument.parse(text, it) is Result.Success } ?: return null
        val doc = TomlDocument.parse(text, version).getOrFail()
        val random = Random(seed)
        val log = mutableListOf<String>()
        repeat(steps) {
            val before = doc.toString()
            val edit = commentKeepingEdit(doc, Edits(doc, random), random)
            log += edit.name
            val owned = edit.apply()
            val after = doc.toString()
            val lost = (comments(before) - owned).minusAll(comments(after))
            if (lost.isNotEmpty()) {
                val where = "$name seed $seed after ${log.joinToString(" ; ")}"
                return "$where: lost $lost\n--- before:\n$before--- after:\n$after"
            }
        }
        return null
    }

    /** An edit that keeps every comment but those it returns when applied. */
    private class Edit(val name: String, val apply: () -> List<String>)

    private fun commentKeepingEdit(
        doc: TomlDocument,
        edits: Edits,
        random: Random,
    ): Edit {
        val options = buildList {
            val table = edits.tables.random(random)
            val newKey = edits.keyFor(emptySet()).takeUnless { it in table }
            if (newKey != null) {
                add(Edit("table set new $newKey") { table.set(newKey, edits.item()).let { emptyList() } })
                add(Edit("table getOrPut new $newKey") { table.getOrPut(newKey) { edits.item() }.let { emptyList() } })
            }
            val scalarKey = table.keys.filter { table.getOrNull(it)!!.isScalar() }.randomOrNull(random)
            if (scalarKey != null) {
                add(Edit("table set value at $scalarKey") { table.set(scalarKey, edits.value()).let { emptyList() } })
                add(Edit("table remove value $scalarKey") { removedValue(table, scalarKey) })
            }
            add(Edit("table sort") { table.sortWith(compareBy { it.first.name }).let { emptyList() } })
            if (table !== edits.tables.first()) {
                add(Edit("table isDotted") {
                    // A new layout for the table: its comments may go, but for the lines above its key.
                    val owned = commentsOf(doc, table)
                    table.isDotted = !table.isDotted
                    owned
                })
                add(Edit("table isImplicit") { (!table.isImplicit).also { table.isImplicit = it }.let { emptyList() } })
            }
            edits.arrays.randomOrNull(random)?.let { array ->
                val index = random.nextInt(array.size + 1)
                add(Edit("array add") { array.add(edits.value()).let { emptyList() } })
                add(Edit("array add at $index") { array.add(index, edits.value()).let { emptyList() } })
                val scalarIndex = (0 until array.size).filter { array[it].getOrFail().isScalar() }.randomOrNull(random)
                if (scalarIndex != null) {
                    add(Edit("array replace $scalarIndex") {
                        array.replace(scalarIndex, edits.value()).let { emptyList() }
                    })
                    add(Edit("array removeAt $scalarIndex") { removedElement(array, scalarIndex) })
                }
            }
            edits.inlines.randomOrNull(random)?.let { inline ->
                add(Edit("inline isDotted") {
                    // A new layout for the table: its comments may go, but for those above it.
                    val inside = comments(inline.toString()) + comments(inline.decor.suffix.orEmpty())
                    inline.isDotted = !inline.isDotted
                    inside
                })
                add(Edit("inline isMultiline") {
                    // One line holds no comment: going to one, the comments inside the braces may go.
                    val inside = if (inline.isMultiline) comments(inline.toString()) else emptyList()
                    inline.isMultiline = !inline.isMultiline
                    inside
                })
            }
            edits.tableArrays.randomOrNull(random)?.let { tables ->
                add(Edit("tables add") { tables.add(edits.table()).let { emptyList() } })
            }
            edits.slots.randomOrNull(random)?.let { slot ->
                add(Edit("slot expand") { slot.expand().let { emptyList() } })
            }
        }
        return options.random(random)
    }

    private fun Item.isScalar(): Boolean = valueOrNull?.isScalar() ?: false

    private fun Value.isScalar(): Boolean = arrayOrNull == null && inlineTableOrNull == null

    /** The comments [table] carries, but for the lines above its key: those removing it from [doc] takes. */
    private fun commentsOf(
        doc: TomlDocument,
        table: StandardTable,
    ): List<String> {
        val path = pathTo(doc.root, table) ?: return emptyList()
        val copy = doc.copy()
        var parent: Any = copy.root
        for (step in path.dropLast(1)) {
            parent = when (step) {
                is String -> {
                    val item = (parent as StandardTable).getOrNull(step)!!
                    item.standardTableOrNull ?: item.arrayOfTablesOrNull!!
                }
                else -> (parent as ArrayOfTables)[step as Int].getOrFail()
            }
        }
        when (val last = path.last()) {
            is String -> (parent as StandardTable).remove(last)
            else -> (parent as ArrayOfTables).removeAt(last as Int)
        }
        return comments(doc.toString()).minusAll(comments(copy.toString()))
    }

    /** The keys and array indices leading from [from] to [target], or null when it is not under [from]. */
    private fun pathTo(
        from: StandardTable,
        target: StandardTable,
    ): List<Any>? {
        for ((key, item) in from) {
            item.standardTableOrNull?.let { child ->
                if (child === target) return listOf(key)
                pathTo(child, target)?.let { return listOf<Any>(key) + it }
            }
            item.arrayOfTablesOrNull?.forEachIndexed { index, element ->
                if (element === target) return listOf(key, index)
                pathTo(element, target)?.let { return listOf<Any>(key, index) + it }
            }
        }
        return null
    }

    /** Removes the value at [key], returning the comment on its line, which goes with it. */
    private fun removedValue(
        table: StandardTable,
        key: String,
    ): List<String> {
        val owned = comments(table.getOrNull(key)!!.valueOrNull!!.decor.suffix.orEmpty())
        table.remove(key)
        return owned
    }

    /** Removes the element at [index], returning the comment on the rest of its line, which goes with it. */
    private fun removedElement(
        array: TomlArray,
        index: Int,
    ): List<String> {
        val suffix = array[index].getOrFail().decor.suffix.orEmpty()
        val next = array.getOrNull(index + 1)?.decor?.prefix ?: array.trailing
        val owned = comments(suffix) + comments(next.orEmpty().substringBefore('\n'))
        array.removeAt(index)
        return owned
    }

    /** The comments in [text], in order, each from `#` to the end of its line. */
    private fun comments(text: String): List<String> {
        val source = TomlSource(text)
        val events = mutableListOf<Event>()
        parseDocument(source.lex().toList(), eventReceiver { events += it }, ErrorSink.IGNORE)
        return events
            .filter { it.kind == EventKind.Comment }
            .map { source.bytes.decodeToString(it.span.start, it.span.end).trimEnd('\r') }
    }

    /** These elements without one occurrence of each of [others]. */
    private fun List<String>.minusAll(others: List<String>): List<String> {
        val remaining = others.groupingBy { it }.eachCount().toMutableMap()
        return filter { comment ->
            val count = remaining[comment] ?: 0
            if (count > 0) remaining[comment] = count - 1
            count == 0
        }
    }

    private operator fun List<String>.minus(owned: List<String>): List<String> = minusAll(owned)
}
