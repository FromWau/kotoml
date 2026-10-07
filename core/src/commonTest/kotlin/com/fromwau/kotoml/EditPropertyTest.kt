package com.fromwau.kotoml

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.Buffer
import kotlinx.io.readString

/**
 * Any sequence of public edits leaves a document that writes TOML of its version reading back as the data the document
 * holds, or refuses to write it with a typed error.
 */
class EditPropertyTest {

    private val steps = 12

    private val handWritten = listOf(
        "",
        "a = 1\n",
        "# the port to listen on\nport = 8080\n# bind address\nhost = \"0.0.0.0\"\n",
        "# about a.x\na.x = 1\n# about b\nb = 2\n# about a.y\na.y = 3\nc = 4\n",
        "title = 't'\n\n# about server\n[server]\n# inside\nhost = 'h'\n\n# about client\n[client]\nport = 1\n",
        "# first\n[[x]]\nn = 1\n\n# between\n[y]\n\n# second\n[[x]]\nn = 2\n# trailing\n",
        "[fruit]\napple.color = 'red'\n[fruit.apple.texture]\nsmooth = true\n",
        "a = [\n  1, # one\n  [2, 3],\n  { x = 1, y.z = 2 },\n]\nt = { a = 1, b = { c = [] } }\n",
        "a = 1\r\n# about b\r\nb = 2\r\n\r\n[t]\r\nc = 3",
        "[a.b.c]\nd = 1\n[a]\ne = 2\n[[a.f]]\ng = 3\n[[a.f]]\n[a.f.h]\ni = 4\n",
    )

    @Test
    fun `random edits to hand-written documents write TOML that reads back as edited`() {
        val failures = handWritten.withIndex().flatMap { (index, text) ->
            (0 until 40).mapNotNull { seed -> failureOf("hand-written $index", text, seed) }
        }
        assertNoFailures(failures)
    }

    @Test
    fun `random edits to every valid suite file write TOML that reads back as edited`() {
        val root = "../toml-test/tests/valid"
        val failures = walkFiles(root)
            .filter { it.endsWith(".toml") }
            .flatMap { file ->
                val text = readBytes("$root/$file").decodeToString()
                (0 until 3).mapNotNull { seed -> failureOf(file, text, seed) }
            }
        assertNoFailures(failures)
    }

    /**
     * Sequences that once wrote text that does not read back, replayed as found: a document, a seed and the step that
     * failed. A change to [Edits] changes what a seed picks, so after one, find such sequences again with more seeds.
     */
    @Test
    fun `edit sequences that once failed write TOML that reads back as edited`() {
        val suite = "../toml-test/tests/valid"
        val newlineComment = readBytes("$suite/inline-table/newline-comment.toml").decodeToString()
        val failures = listOfNotNull(
            failureOf("hand-written 5", handWritten[5], seed = 90, steps = 25),
            failureOf("hand-written 8", handWritten[8], seed = 191, steps = 12),
            failureOf("hand-written 8", handWritten[8], seed = 1375, steps = 8),
            failureOf("hand-written 9", handWritten[9], seed = 339, steps = 5),
            failureOf("newline-comment", newlineComment, seed = 20, steps = 19),
        )
        assertNoFailures(failures)
    }

    private fun assertNoFailures(failures: List<String>) {
        assertEquals(emptyList(), failures.take(10), "${failures.size} failures")
    }

    /** What went wrong editing [text] with the edits [seed] picks, or null when every step wrote back as edited. */
    private fun failureOf(
        name: String,
        text: String,
        seed: Int,
        steps: Int = this.steps,
    ): String? {
        val version = TomlVersion.entries.lastOrNull { TomlDocument.parse(text, it) is Result.Success } ?: return null
        val doc = TomlDocument.parse(text, version).assertSuccess()
        val random = Random(seed)
        val log = mutableListOf<String>()
        var before = doc.toString()
        repeat(steps) {
            log += Edits(doc, random).applyOne()
            val written = Buffer()
            if (doc.writeTo(written) is Result.Error) return@repeat
            val printed = written.readString()
            val where = "$name seed $seed after ${log.joinToString(" ; ")}"
            val problem = when (val reparsed = TomlDocument.parse(printed, doc.version)) {
                is Result.Error -> "does not parse: ${reparsed.error}"
                is Result.Success -> {
                    val same = reparsed.value.toTagged().sameAs(doc.toTagged())
                    "reads back other data".takeUnless { same }
                }
            }
            if (problem != null) return "$where: $problem\n--- before the last edit:\n$before--- after:\n$printed"
            before = printed
        }
        return null
    }
}

/** One random public edit to [doc], at a place [random] picks among all the document holds. */
internal class Edits(
    private val doc: TomlDocument,
    private val random: Random,
) {
    val tables = mutableListOf<StandardTable>()
    val inlines = mutableListOf<InlineTable>()
    val arrays = mutableListOf<TomlArray>()
    val tableArrays = mutableListOf<ArrayOfTables>()
    val slots = mutableListOf<Item>()

    /** A layout to give a table, any of the four. */
    fun layout(): TableLayout =
        listOf(TableLayout.Header, TableLayout.Dotted, TableLayout.Inline, TableLayout.MultilineInline).random(random)

    init {
        collect(doc.root)
    }

    private fun collect(table: StandardTable) {
        tables += table
        for ((_, item) in table) collect(item)
    }

    private fun collect(item: Item) {
        slots += item
        item.standardTableOrNull?.let(::collect)
        item.arrayOfTablesOrNull?.let { tables ->
            tableArrays += tables
            tables.values.forEach(::collect)
        }
        item.inlineTableOrNull?.let { table ->
            inlines += table
            table.items.entries.forEach { collect(it.item) }
        }
        item.arrayOrNull?.let { array ->
            arrays += array
            array.values.forEach(::collect)
        }
    }

    /** Applies one edit and names it; a refused edit counts too, since it must leave the document valid. */
    fun applyOne(): String {
        val edits = buildList<Pair<String, () -> Unit>> {
            val table = tables.random(random)
            val force = random.nextBoolean()
            val key = keyFor(table.keys)
            add("table set $key" to { table.set(key, item()) })
            add("table put $key" to { table.put(Key(key), item()) })
            add("table getOrPut $key" to { table.getOrPut(key) { item() } })
            table.keys.randomOrNull(random)?.let { old -> add("table remove $old" to { table.remove(old) }) }
            table.keys.randomOrNull(random)?.let { old ->
                add("table rename $old to $key" to { table.rename(old, key) })
            }
            add("table sort" to { table.sortWith(compareBy { it.first.name }) })
            add("table retainAll" to { table.retainAll { random.nextBoolean() } })
            add("table clear" to { table.clear() })
            val spelled = toml11Spellings.random(random)
            add("table set $key to $spelled" to { table.set(key, Value.parse(spelled).assertSuccess()) })
            val version = TomlVersion.entries.random(random)
            add("doc setVersion $version force $force" to { doc.setVersion(version, force) })
            add("table format" to { table.format() })
            add("doc format" to { doc.format() })
            add("table respell" to { table.respell() })
            add("doc respell" to { doc.respell() })
            if (table !== doc.root) add("table setImplicit" to { table.setImplicit(!table.isImplicit) })
            inlines.randomOrNull(random)?.let { inline ->
                val inlineKey = keyFor(inline.keys)
                add("inline set $inlineKey" to { inline.set(inlineKey, value()) })
                add("inline putItem $inlineKey" to { inline.putItem(inlineKey, item()) })
                inline.keys.randomOrNull(random)?.let { old -> add("inline remove $old" to { inline.remove(old) }) }
                inline.keys.randomOrNull(random)?.let { old ->
                    add("inline rename $old to $inlineKey" to { inline.rename(old, inlineKey) })
                }
                add("inline format" to { inline.format() })
            }
            arrays.randomOrNull(random)?.let { array ->
                val index = random.nextInt(array.size + 1)
                add("array add" to { array.add(value()) })
                add("array add at $index" to { array.add(index, value()) })
                add("array replace $index" to { array.replace(index, value(), random.nextBoolean()) })
                add("array removeAt $index" to { array.removeAt(index) })
                add("array clear" to { array.clear() })
                add("array format" to { array.format() })
                val multiline = random.nextBoolean()
                add("array setMultiline $multiline force $force" to { array.setMultiline(multiline, force) })
                add("array respell" to { array.respell() })
            }
            tableArrays.randomOrNull(random)?.let { tables ->
                val index = random.nextInt(tables.size + 1)
                add("tables add" to { tables.add(table()) })
                add("tables add at $index" to { tables.add(index, table()) })
                add("tables replace $index" to { tables.replace(index, table(), random.nextBoolean()) })
                add("tables removeAt $index" to { tables.removeAt(index) })
                add("tables clear" to { tables.clear() })
            }
            slots.randomOrNull(random)?.let { slot ->
                add("slot assign" to { slot.assign(item()) })
                add("slot format" to { slot.format() })
                add("slot respell" to { slot.respell() })
                val layout = layout()
                add("slot setLayout $layout force $force" to { slot.setLayout(layout, force) })
                add("slot index set" to { slot.set(keyFor(emptySet()), item()) })
            }
            slots.randomOrNull(random)?.let { source ->
                val target = tables.random(random)
                val copyKey = keyFor(target.keys)
                add("copy a slot to $copyKey" to { target.set(copyKey, source) })
                source.standardTableOrNull?.let { add("table toInlineTable" to { it.toInlineTable() }) }
                source.valueOrNull?.let { copied ->
                    arrays.randomOrNull(random)?.let { array ->
                        add("copy a value into an array" to { array.add(copied) })
                    }
                    inlines.randomOrNull(random)?.let { inline ->
                        add("copy a value into an inline table" to { inline.set(keyFor(inline.keys), copied) })
                    }
                }
            }
        }
        val (name, edit) = edits.random(random)
        edit()
        return name
    }

    private val newKeys = listOf(
        "a", "b", "x", "new key", "", "a.b", "1", "true", "#", "'q'", "\"dq\"", "é", "tab\tkey",
    )

    fun keyFor(existing: Set<String>): String =
        if (existing.isNotEmpty() && random.nextBoolean()) existing.random(random) else newKeys.random(random)

    private val strings = listOf(
        "", "plain", "it's", "say \"hi\"", "back\\slash", "line\nbreak", "cr\r\nlf", "tab\tstop", "\u0001ctl",
        "del\u007f", "'''", "\"\"\"", "ü € 😀", "# not a comment", "a\\nb",
    )

    // Values spelled as only TOML 1.1 reads them, or with a TOML 1.0 form that differs.
    private val toml11Spellings = listOf(
        "\"\\e\\x41\"", "\"\"\"a\\e\"\"\"", "07:32", "1979-05-27T07:32Z", "{ a = 1, }", "{\n  a = 1, # a\n}",
        "[{ \"\\x41\" = { b = 07:32 } }]",
    )

    private val datetimes = listOf(
        "1979-05-27T07:32:00Z", "1979-05-27T00:32:00.999999-07:00", "1979-05-27T07:32:00", "1979-05-27", "07:32:00",
        "00:32:00.5",
    )

    fun value(depth: Int = 0): Value = when (random.nextInt(if (depth < 2) 8 else 6)) {
        0 -> Value.String(strings.random(random))
        1 -> Value.Integer(listOf(0L, -1L, 42L, Long.MAX_VALUE, Long.MIN_VALUE).random(random))
        2 -> Value.Float(
            listOf(0.0, -0.0, 1.5, 1e300, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 5e-324)
                .random(random),
        )
        3 -> Value.Boolean(random.nextBoolean())
        4 -> Value.Datetime(Datetime.parse(datetimes.random(random)).assertSuccess())
        5 -> Value.String(strings.random(random))
        6 -> Value.Array(List(random.nextInt(4)) { value(depth + 1) }.toTomlArray().assertSuccess())
        else -> Value.InlineTable(
            List(random.nextInt(4)) { newKeys.random(random) to value(depth + 1) }.toInlineTable().assertSuccess(),
        )
    }

    fun table(depth: Int = 0): StandardTable {
        val table = StandardTable()
        repeat(random.nextInt(3)) {
            val item = if (depth < 2 && random.nextInt(4) == 0) Item(table(depth + 1)) else Item(value())
            table.set(newKeys.random(random), item)
        }
        return table
    }

    fun item(): Item = when (random.nextInt(6)) {
        0 -> Item(table())
        1 -> Item(List(1 + random.nextInt(2)) { table() }.toArrayOfTables().assertSuccess())
        2 -> slots.randomOrNull(random)?.copy() ?: Item(value())
        else -> Item(value())
    }
}
