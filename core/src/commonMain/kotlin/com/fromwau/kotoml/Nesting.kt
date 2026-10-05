package com.fromwau.kotoml

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result

/**
 * Where content stands in the text it prints, in the terms of the parser's rule: a header's path may have as many
 * parts as [TomlDocument.NESTING_LIMIT], while a line's dotted key and the brackets of its value count toward the limit
 * together, and a key inside an inline table stays below it. A dotted inline table counts as one more bracket, which
 * gives the same count as its dots.
 */
internal sealed interface Nesting {
    /** In a standard table [parts] keys below the root, whose keys print after [dots] dotted keys on their lines. */
    data class InTable(val parts: Int, val dots: Int) : Nesting

    /** In an array of tables whose elements' headers have [parts] parts. */
    data class InTables(val parts: Int) : Nesting

    /** Inside an array or inline table [level] brackets and dots deep on its line. */
    data class InLine(val level: Int) : Nesting
}

/**
 * Where [container], a table or an array of either kind, stands: below the slots holding it up to the document's
 * root, or up to the outermost one stored nowhere, which stands as close to a root as it can: a table as the root
 * itself, anything else under one of its keys.
 */
internal fun nestingOf(container: Any): Nesting {
    val chain = mutableListOf(container)
    while (true) chain.add(parentOf(chain.last()) ?: break)
    var nesting = topNestingOf(chain.last())
    for (index in chain.lastIndex - 1 downTo 0) nesting = nesting.inside(chain[index])
    return nesting
}

/**
 * Whether storing [item] copies it from a place in a document, this one or another: then the copy leaves the
 * comments of its source's own lines behind. Storing a table or array that is stored nowhere, removed or built in
 * code, or a bare value, keeps them.
 */
internal fun copiesFromDocument(item: Item): Boolean = sourceOf(item) != null

/**
 * Whether storing [item] in [receiver] copies it to another place of the document it is read from: then the copy
 * prints after the tables already there.
 */
internal fun copiesWithinDocument(
    item: Item,
    receiver: Any,
): Boolean {
    val sourceRoot = outermostTableOfSource(item)
        ?: item.copiedWithin
        ?: item.arrayOfTablesOrNull?.copiedWithin
        ?: return false
    return sourceRoot === outermostTableOf(receiver)
}

/** The outermost table of the document [item] is read from, or null when it is stored nowhere. */
internal fun outermostTableOfSource(item: Item): StandardTable? = sourceOf(item)?.let(::outermostTableOf)

/** Whether [container] is stored in a table or array, so a copy of it leaves the comments of that place behind. */
internal fun isStored(container: Any): Boolean = parentOf(container) != null

private fun sourceOf(item: Item): Any? = item.owner ?: item.container?.let(::parentOf)

/** Whether storing [table] in [array] copies it from another place, in any document, not reorders [array]. */
internal fun copiesFromElsewhere(
    table: StandardTable,
    array: ArrayOfTables,
): Boolean {
    val source = parentOf(table) ?: return false
    return source !== array
}

/** The table whose body prints [table]'s lines while it is dotted: the nearest one above it with a header. */
internal fun headerTableOf(table: StandardTable): StandardTable? {
    var current: Any = table
    while (true) {
        val parent = parentOf(current) ?: return null
        if (parent is StandardTable && !parent.isDotted) return parent
        current = parent
    }
}

/** The outermost table [container] is stored under: a document's root, or the table at the top of a detached tree. */
internal fun outermostTableOf(container: Any): StandardTable? {
    var current = container
    while (true) current = parentOf(current) ?: break
    return current as? StandardTable
}

/** The line ending of the document [container] is stored in, which the lines it adds end in; `\n` outside one. */
internal fun lineEndingOf(container: Any): String = outermostTableOf(container)?.lineEnding ?: LINE_BREAK

/** The TOML version of the document [container] is stored in, or TOML 1.1 when it is stored nowhere. */
internal fun documentVersionOf(container: Any): TomlVersion = outermostTableOf(container)?.version ?: TomlVersion.V1_1

private fun topNestingOf(container: Any): Nesting = when (container) {
    is StandardTable -> Nesting.InTable(parts = 0, dots = 0)
    is ArrayOfTables -> Nesting.InTables(parts = 1)
    else -> Nesting.InLine(level = 1)
}

private fun parentOf(container: Any): Any? = slotOf(container)?.owner

/** The slot [container] is stored in, or null when it is stored nowhere. */
private fun slotOf(container: Any): Item? {
    val slot = when (container) {
        is StandardTable -> container.holder
        is InlineTable -> container.holder
        is TomlArray -> container.holder
        is ArrayOfTables -> container.holder
        is Value.String -> container.holder
        is Value.Datetime -> container.holder
        else -> null
    }
    return slot?.takeIf { it.holdsContainer(container) && it.owner != null }
}

/**
 * The keys from the document's root down to [container], or from the outermost table or array it is stored under
 * when that is stored nowhere; an element of an array adds none.
 */
private fun keyPathOf(container: Any): List<String> {
    val keys = ArrayDeque<String>()
    var current = container
    while (true) {
        val slot = slotOf(current) ?: break
        slot.entry?.takeIf { it.isStored }?.key?.name?.let(keys::addFirst)
        current = slot.owner ?: break
    }
    return keys
}

/** Where [child], stored in a container standing here, stands. */
private fun Nesting.inside(child: Any): Nesting = when (this) {
    is Nesting.InTable -> when (child) {
        is StandardTable -> Nesting.InTable(parts + 1, if (child.printsDotted) dots + 1 else 0)
        is ArrayOfTables -> Nesting.InTables(parts + 1)
        else -> Nesting.InLine(dots + 1)
    }
    is Nesting.InTables -> Nesting.InTable(parts, dots = 0)
    is Nesting.InLine -> Nesting.InLine(level + 1)
}

/**
 * [TomlEditError.TooDeep], with the keys to the first place [item] would print deeper than the parser reads if stored
 * in [container] under [key] (null for an array's element), from where [keyPathOf] starts; or null when it would not.
 */
internal fun tooDeepError(
    container: Any,
    key: String?,
    item: Item,
): TomlEditError.TooDeep? = DepthWalk()
    .apply { place(nestingOf(container), key, item, keyPathOf(container)) }
    .run()
    ?.let { TomlEditError.TooDeep(it) }

/** [TomlEditError.TooDeep] when the table or array [item] holds nests too deep standing at the top, else null. */
internal fun tooDeepAtTop(item: Item): TomlEditError.TooDeep? =
    item.container?.let(::tooDeepPathAtTop)?.let { TomlEditError.TooDeep(it) }

/** The keys from [container] to the first place its content prints deeper than the parser reads at the top. */
internal fun tooDeepPathAtTop(container: Any): List<String>? =
    DepthWalk().apply { visit(container, topNestingOf(container), emptyList()) }.run()

/** This new table or array, or [TomlEditError.TooDeep] when its content nests too deep standing at the top. */
internal fun <T : Any> T.refusedWhenTooDeep(): Result<T, TomlEditError> =
    tooDeepPathAtTop(this)?.let { Err(TomlEditError.TooDeep(it)) } ?: Ok(this)

/**
 * Decides which dotted tables under [top] print under a header of their own: those whose lines, printed as dotted
 * keys, would nest deeper than the parser reads. A header resets the count of dots, so the text reads back.
 */
internal fun settleHeadersForDepth(top: StandardTable) {
    val tables = ArrayDeque<Pair<StandardTable, Nesting.InTable>>()
    tables.addLast(top to Nesting.InTable(parts = 0, dots = 0))
    while (true) {
        val (table, at) = tables.removeLastOrNull() ?: break
        for (entry in table.items.entries) {
            entry.item.arrayOfTablesOrNull?.forEach { tables.addLast(it to Nesting.InTable(at.parts + 1, dots = 0)) }
            val child = entry.item.standardTableOrNull ?: continue
            val dotted = Nesting.InTable(at.parts + 1, at.dots + 1)
            child.headerForDepth = child.isDotted && !child.fitsDotted(at, dotted)
            tables.addLast(child to if (child.printsDotted) dotted else Nesting.InTable(at.parts + 1, dots = 0))
        }
    }
}

// Whether this table's own lines fit as dotted keys: its values, `key = []` for no tables, and `key = {}` when empty.
private fun StandardTable.fitsDotted(
    parent: Nesting.InTable,
    dotted: Nesting.InTable,
): Boolean {
    val limit = TomlDocument.NESTING_LIMIT
    if (items.size == 0) return parent.dots < limit
    return items.entries.all { entry ->
        val tables = entry.item.arrayOfTablesOrNull
        when {
            entry.item.standardTableOrNull != null -> true
            tables != null -> tables.isNotEmpty() || dotted.dots < limit
            else -> DepthWalk().apply { place(dotted, entry.key.name, entry.item, emptyList()) }.run() == null
        }
    }
}

// Iterative, so that measuring content of any depth takes no more stack than measuring a flat table.
private class DepthWalk {
    private data class Frame(val container: Any, val nesting: Nesting, val path: List<String>)

    private val limit = TomlDocument.NESTING_LIMIT
    private val frames = ArrayDeque<Frame>()
    private var found: List<String>? = null

    fun visit(container: Any, nesting: Nesting, path: List<String>) {
        frames.addLast(Frame(container, nesting, path))
    }

    fun run(): List<String>? {
        while (found == null) {
            val (container, nesting, path) = frames.removeLastOrNull() ?: break
            when (container) {
                is StandardTable -> placeEntries(nesting, container.items, path)
                is InlineTable -> placeEntries(nesting, container.items, path)
                is TomlArray -> for (slot in container.values) place(nesting, null, slot, path)
                is ArrayOfTables -> for (slot in container.values) place(nesting, null, slot, path)
            }
        }
        return found
    }

    private fun placeEntries(
        at: Nesting,
        pairs: KeyValuePairs,
        path: List<String>,
    ) {
        for (entry in pairs.entries) place(at, entry.key.name, entry.item, path)
    }

    /** Measures [item] stored under [key] in a container standing at [at], whose keys are [path]. */
    fun place(
        at: Nesting,
        key: String?,
        item: Item,
        path: List<String>,
    ) {
        if (found != null) return
        val itemPath = if (key == null) path else path + key
        val keyTooDeep = when (at) {
            is Nesting.InTable -> at.dots >= limit
            is Nesting.InTables -> false
            is Nesting.InLine -> key != null && at.level >= limit
        }
        val container = item.container
        if (container == null) {
            if (keyTooDeep) found = itemPath
            return
        }
        val inside = at.inside(container)
        val tooDeep = when (inside) {
            // A table prints under the header [Nesting.InTable.dots] levels up; an empty dotted one prints its key
            // with `{}` on a line of the table above.
            is Nesting.InTable -> {
                val headerParts = inside.parts - inside.dots
                headerParts > limit || (inside.dots > 0 && item.isEmptyTable() && keyTooDeep)
            }
            is Nesting.InTables -> false
            is Nesting.InLine -> keyTooDeep || inside.level > limit
        }
        if (tooDeep) found = itemPath else visit(container, inside, itemPath)
    }
}

private fun Item.isEmptyTable(): Boolean = standardTableOrNull?.items?.size == 0

/** The table or array of either kind this item holds, or null for a scalar. */
private val Item.container: Any?
    get() = standardTableOrNull ?: arrayOfTablesOrNull ?: arrayOrNull ?: inlineTableOrNull
