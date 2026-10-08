package com.fromwau.kotoml

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
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
 * Edits keep every comment they do not own, and an item's comments go where it goes. Adding, replacing a value with a
 * value, renaming, formatting, respelling and changing how a table prints keep them all. Removing a value with force
 * takes exactly the comments that belong to it, the comment lines right above it and the comment on its line, and the
 * removed item stores with them; without force such a removal is refused and changes nothing. Storing a copy of an
 * item brings exactly the comments removing it would take. Sorting a table, an inline table or an array keeps with
 * each item the comments removing it would take. Giving a table a new layout with `setLayout(force = true)` may take
 * its comments, all but the lines above its key, as `setVersion(force = true)` may take those of inline tables across
 * lines and `setMultiline(false, force = true)` those between an array's brackets. Without force a layout or version
 * change keeps every comment or is refused and changes nothing. Removing or replacing whole tables is covered by
 * `RemovalPropertyTest` and the comment scope tests, which state what those take. Comments compare as `format()`
 * spells them.
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
        "[t]\nz = 3 # z\n# about b\nb.d = 1 # d\n# about c\nb.c = 2 # c\n\n# free\n\na = [\n  3, # three\n  1,\n]\n",
        "t = {\n  z = 3, # z\n\n  # free\n\n  # about b\n  b.y = 1, # y\n  b.x = 2,\n  # about a\n  a = 0,\n}\n",
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
        val doc = TomlDocument.parse(text, version).assertSuccess()
        val random = Random(seed)
        val log = mutableListOf<String>()
        repeat(steps) {
            val before = doc.toString()
            val edit = commentKeepingEdit(doc, Edits(doc, random), random)
            log += edit.name
            val outcome = edit.apply()
            val after = doc.toString()
            val lost = comments(before).minusAll(comments(after))
            val gained = comments(after).minusAll(comments(before))
            val problem = when {
                outcome.problem != null -> outcome.problem
                outcome.exact && !sameComments(lost, outcome.owned) -> "lost $lost, not exactly ${outcome.owned}"
                lost.minusAll(outcome.owned).isNotEmpty() -> "lost ${lost.minusAll(outcome.owned)}"
                outcome.brought != null && !sameComments(gained, outcome.brought) -> {
                    "gained $gained, not exactly ${outcome.brought}"
                }
                else -> null
            }
            if (problem != null) {
                val where = "$name seed $seed after ${log.joinToString(" ; ")}"
                return "$where: $problem\n--- before:\n$before--- after:\n$after"
            }
        }
        return null
    }

    /** An edit to the document, which reports what it may do to the document's comments. */
    private class Edit(val name: String, val apply: () -> Outcome)

    /**
     * What an applied edit may do to the document's comments: take those [owned], all of them when [exact], and add
     * exactly those [brought] when known; [problem] says what else went wrong.
     */
    private class Outcome(
        val owned: List<String> = emptyList(),
        val exact: Boolean = false,
        val brought: List<String>? = null,
        val problem: String? = null,
    )

    private val keepsAll = Outcome()

    private fun commentKeepingEdit(
        doc: TomlDocument,
        edits: Edits,
        random: Random,
    ): Edit {
        val options = buildList {
            val table = edits.tables.random(random)
            val newKey = edits.keyFor(emptySet()).takeUnless { it in table }
            if (newKey != null) {
                add(Edit("table set new $newKey") { table.set(newKey, edits.item()).let { keepsAll } })
                add(Edit("table getOrPut new $newKey") { table.getOrPut(newKey) { edits.item() }.let { keepsAll } })
                val source = edits.tables.random(random)
                source.keys.randomOrNull(random)?.let { key ->
                    add(Edit("table set new $newKey to a copy of $key") { copied(source, key, table, newKey) })
                }
            }
            val scalarKey = table.keys.filter { table.getOrNull(it)!!.isScalar() }.randomOrNull(random)
            if (scalarKey != null) {
                add(Edit("table set value at $scalarKey") { table.set(scalarKey, edits.value()).let { keepsAll } })
                add(Edit("table remove value $scalarKey") { removedValue(table, scalarKey) })
                add(Edit("table remove value $scalarKey without force") { removedWithoutForce(doc, table, scalarKey) })
            }
            val renamed = table.keys.randomOrNull(random)
            if (renamed != null) {
                val to = edits.keyFor(table.keys)
                add(Edit("table rename $renamed to $to") { table.rename(renamed, to).let { keepsAll } })
            }
            edits.inlines.randomOrNull(random)?.let { inline ->
                add(Edit("inline sort") { sorted(inline) })
                val old = inline.keys.randomOrNull(random) ?: return@let
                val to = edits.keyFor(inline.keys)
                add(Edit("inline rename $old to $to") { inline.rename(old, to).let { keepsAll } })
            }
            add(Edit("table sort") { sorted(table) })
            add(Edit("table format") { table.format().let { keepsAll } })
            edits.slots.randomOrNull(random)?.let { slot ->
                add(Edit("slot format") { slot.format().let { keepsAll } })
            }
            add(Edit("doc format") { doc.format().let { keepsAll } })
            add(Edit("doc respell") { doc.respell().let { keepsAll } })
            add(Edit("table respell") { table.respell().let { keepsAll } })
            edits.slots.randomOrNull(random)?.let { slot ->
                add(Edit("slot respell") { slot.respell().let { keepsAll } })
            }
            if (table !== edits.tables.first()) {
                add(Edit("table setImplicit") { table.setImplicit(!table.isImplicit).let { keepsAll } })
            }
            edits.arrays.randomOrNull(random)?.let { array ->
                val index = random.nextInt(array.size + 1)
                add(Edit("array add") { array.add(edits.value()).let { keepsAll } })
                add(Edit("array add at $index") { array.add(index, edits.value()).let { keepsAll } })
                add(Edit("array sort") { sorted(array) })
                val scalarIndex = (0 until array.size)
                    .filter { array[it].assertSuccess().isScalar() }
                    .randomOrNull(random)
                if (scalarIndex != null) {
                    add(Edit("array replace $scalarIndex") {
                        array.replace(scalarIndex, edits.value()).let { keepsAll }
                    })
                    add(Edit("array removeAt $scalarIndex") { removedElement(array, scalarIndex) })
                }
                val multiline = random.nextBoolean()
                val force = random.nextBoolean()
                add(Edit("array setMultiline $multiline force $force") {
                    if (force) {
                        // On one line the comments in the array's own text go; those inside its elements stay.
                        val owned = if (multiline) emptyList() else commentsBetweenBrackets(array)
                        array.setMultiline(multiline, force = true).assertSuccess()
                        Outcome(owned)
                    } else {
                        keptOrRefused(doc) { array.setMultiline(multiline) }
                    }
                })
                add(Edit("array respell") { array.respell().let { keepsAll } })
            }
            edits.tableArrays.randomOrNull(random)?.let { tables ->
                add(Edit("tables add") { tables.add(edits.table()).let { keepsAll } })
            }
            val tableSlot = edits.slots.filter { it.layout != null }.randomOrNull(random)
            for (slot in listOfNotNull(tableSlot, edits.slots.randomOrNull(random))) {
                val layout = edits.layout()
                val force = random.nextBoolean()
                add(Edit("slot setLayout $layout force $force") {
                    if (force) {
                        // A new layout for the table: its comments may go, but for the lines above its key.
                        val owned = commentsOf(doc, slot)
                        val _ = slot.setLayout(layout, force = true)
                        Outcome(owned)
                    } else {
                        keptOrRefused(doc) { slot.setLayout(layout) }
                    }
                })
            }
            val version = TomlVersion.entries.random(random)
            val force = random.nextBoolean()
            add(Edit("doc setVersion $version force $force") {
                if (force) {
                    // Inline tables across lines go on one line in TOML 1.0, and their comments may go with them.
                    val owned = edits.slots
                        .filter { it.inlineTableOrNull?.layout == TableLayout.MultilineInline }
                        .flatMap { commentsOf(doc, it) }
                    doc.setVersion(version, force = true).assertSuccess()
                    Outcome(owned)
                } else {
                    keptOrRefused(doc) { doc.setVersion(version) }
                }
            })
        }
        return options.random(random)
    }

    /**
     * Applies a layout change without force, which drops no comment: it either keeps them all or, refused with
     * [TomlEditError.CommentsWouldBeLost], leaves [doc] as it was.
     */
    private fun keptOrRefused(
        doc: TomlDocument,
        change: () -> Result<Unit, TomlEditError>,
    ): Outcome {
        val before = doc.toString()
        val result = change()
        val refused = result is Result.Error && result.error is TomlEditError.CommentsWouldBeLost
        return Outcome(problem = "a refused layout change changed the document".takeIf { refused && before != "$doc" })
    }

    private fun Item.isScalar(): Boolean = valueOrNull?.isScalar() ?: false

    private fun Value.isScalar(): Boolean = arrayOrNull == null && inlineTableOrNull == null

    /** The comments the table or value in [slot] carries, but for the lines above its key. */
    private fun commentsOf(
        doc: TomlDocument,
        slot: Item,
    ): List<String> {
        slot.standardTableOrNull?.let { return commentsOf(doc, it) }
        slot.arrayOfTablesOrNull?.let { tables -> return tables.flatMap { commentsOf(doc, it) } }
        val value = slot.valueOrNull ?: return emptyList()
        return comments(value.toString()) + comments(value.decor.suffix.orEmpty())
    }

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
                else -> (parent as ArrayOfTables)[step as Int].assertSuccess()
            }
        }
        when (val last = path.last()) {
            is String -> (parent as StandardTable).remove(last, force = true).assertSuccess()
            else -> (parent as ArrayOfTables).removeAt(last as Int, force = true).assertSuccess()
        }
        val aboveHeader = comments(table.headerComments().attachedAbove)
        return comments(doc.toString()).minusAll(comments(copy.toString())).minusAll(aboveHeader)
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

    /** The comments removing [key] from a copy of [table] takes: the comments that belong to it. */
    private fun ownedBy(
        table: StandardTable,
        key: String,
    ): List<String> {
        val copy = TomlDocument(table.copy())
        val all = comments(copy.toString())
        copy.remove(key, force = true).assertSuccess()
        return all.minusAll(comments(copy.toString()))
    }

    private fun ownedBy(
        table: InlineTable,
        key: String,
    ): List<String> {
        val copy = table.copy()
        val all = comments(copy.toString())
        copy.remove(key, force = true).assertSuccess()
        return all.minusAll(comments(copy.toString()))
    }

    private fun ownedBy(
        array: TomlArray,
        index: Int,
    ): List<String> {
        val copy = array.copy()
        val all = comments(copy.toString())
        copy.removeAt(index, force = true).assertSuccess()
        return all.minusAll(comments(copy.toString()))
    }

    /**
     * Stores a copy of [key] of [source] at [newKey] of [target], which brings exactly the comments [key] owns; of a
     * dotted table's key that includes the group's comment, which a copy of the table alone cannot tell.
     */
    private fun copied(
        source: StandardTable,
        key: String,
        target: StandardTable,
        newKey: String,
    ): Outcome {
        val brought = if (source.isDotted) null else ownedBy(source, key)
        val stored = target.set(newKey, source.getOrNull(key)!!)
        return Outcome(brought = if (stored is Result.Success) brought else emptyList())
    }

    /** Sorts [table], whose keys keep the comments they own; a dotted table's first line owns the group's comment. */
    private fun sorted(table: StandardTable): Outcome {
        if (table.isDotted) return table.sortWith(compareBy { it.first.name }).let { keepsAll }
        val before = table.keys.associateWith { ownedBy(table, it) }
        table.sortWith(compareBy { it.first.name })
        val moved = before.filter { (key, owned) -> !sameComments(owned, ownedBy(table, key)) }.keys
        return Outcome(problem = "sorting changed the comments of $moved".takeIf { moved.isNotEmpty() })
    }

    private fun sorted(table: InlineTable): Outcome {
        if (table.isDotted) return table.sortWith(compareBy { it.first.name }).let { keepsAll }
        val before = table.keys.associateWith { ownedBy(table, it) }
        table.sortWith(compareBy { it.first.name })
        val moved = before.filter { (key, owned) -> !sameComments(owned, ownedBy(table, key)) }.keys
        return Outcome(problem = "sorting changed the comments of $moved".takeIf { moved.isNotEmpty() })
    }

    private fun sorted(array: TomlArray): Outcome {
        val elements = array.values.map { it.valueOrNull }
        val before = elements.indices.map { ownedBy(array, it) }
        array.sortWith(compareBy { it.toString() })
        val moved = elements.indices.filter { index ->
            val now = array.values.indexOfFirst { it.valueOrNull === elements[index] }
            now < 0 || !sameComments(before[index], ownedBy(array, now))
        }
        return Outcome(problem = "sorting changed the comments of elements $moved".takeIf { moved.isNotEmpty() })
    }

    /**
     * Removes the value at [key] with force, which takes exactly the comments that belong to it; the removed item
     * stores with them.
     */
    private fun removedValue(
        table: StandardTable,
        key: String,
    ): Outcome {
        val own = table.items.getEntry(key)!!.itemComments()
        val owned = comments(own.attachedAbove + own.sameLine)
        val item = table.remove(key, force = true).assertSuccess()
        val carried = comments(TomlDocument().also { it.set("k", item).assertSuccess() }.toString())
        val problem = "the removed item stores with $carried".takeUnless { sameComments(carried, owned) }
        return Outcome(owned, exact = true, problem = problem)
    }

    /** Removes the value at [key] without force, which is refused, changing nothing, when comments would go. */
    private fun removedWithoutForce(
        doc: TomlDocument,
        table: StandardTable,
        key: String,
    ): Outcome {
        val own = table.items.getEntry(key)!!.itemComments()
        val owned = comments(own.attachedAbove + own.sameLine)
        val before = doc.toString()
        val result = table.remove(key)
        val problem = when {
            owned.isEmpty() -> (result as? Result.Error)?.let { "refused a removal taking no comment: ${it.error}" }
            result !is Result.Error -> "removed $owned without force"
            (result.error as? TomlEditError.CommentsWouldBeLost)?.count != owned.size -> "refused with ${result.error}"
            before != doc.toString() -> "a refused removal changed the document"
            else -> null
        }
        return Outcome(exact = true, problem = problem)
    }

    /** Removes the element at [index] with force, which takes exactly the comments that belong to it. */
    private fun removedElement(
        array: TomlArray,
        index: Int,
    ): Outcome {
        val own = array.elementComments(index)
        val owned = comments(own.attachedAbove + own.sameLine + own.attachedBelow + own.commaLine)
        array.removeAt(index, force = true).assertSuccess()
        return Outcome(owned, exact = true)
    }

    /** The comments before and after each of [array]'s elements and before its `]`, not those inside an element. */
    private fun commentsBetweenBrackets(array: TomlArray): List<String> =
        array.flatMap { comments(it.decor.prefix.orEmpty()) + comments(it.decor.suffix.orEmpty()) } +
            comments(array.trailing)

    /** The comments in [text], in order, each as [commentsIn] gives it. */
    private fun comments(text: String): List<String> = commentsIn(text)

    private fun sameComments(
        left: List<String>,
        right: List<String>,
    ): Boolean = left.sorted() == right.sorted()

    /** These elements without one occurrence of each of [others]. */
    private fun List<String>.minusAll(others: List<String>): List<String> {
        val remaining = others.groupingBy { it }.eachCount().toMutableMap()
        return filter { comment ->
            val count = remaining[comment] ?: 0
            if (count > 0) remaining[comment] = count - 1
            count == 0
        }
    }
}

/**
 * The comments in [text], in order, each from `#` to the end of its line as `format()` spells it: one space after its
 * `#`s unless a space or tab follows them, and none at its end.
 */
internal fun commentsIn(text: String): List<String> {
    val source = TomlSource(text)
    val events = mutableListOf<Event>()
    parseDocument(source.lex().toList(), eventReceiver { events += it }, ErrorSink.IGNORE)
    return events
        .filter { it.kind == EventKind.Comment }
        .map { source.bytes.decodeToString(it.span.start, it.span.end).trimEnd('\r', ' ', '\t') }
        .map { comment ->
            val marks = comment.takeWhile { it == '#' }.length
            val first = comment.getOrNull(marks)
            val spaced = first == null || first == ' ' || first == '\t'
            if (spaced) comment else "${comment.take(marks)} ${comment.drop(marks)}"
        }
}
