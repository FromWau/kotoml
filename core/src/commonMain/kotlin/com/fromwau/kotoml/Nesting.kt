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
 * Whether storing [item] in [receiver] copies it to another place of the document it is read from: then the copy
 * leaves the comments of its source's own lines behind. Copying into another document keeps them, as does storing a
 * table or array that is stored nowhere, or a bare value.
 */
internal fun copiesWithinDocument(
    item: Item,
    receiver: Any,
): Boolean {
    val source = item.owner ?: item.container?.let(::parentOf) ?: return false
    return outermostTableOf(source) === outermostTableOf(receiver)
}

/** Whether storing [table] in [array] copies it from another place of their document, not reorders [array]. */
internal fun copiesWithinDocument(
    table: StandardTable,
    array: ArrayOfTables,
): Boolean {
    val source = parentOf(table) ?: return false
    return source !== array && outermostTableOf(source) === outermostTableOf(array)
}

/** The outermost table [container] is stored under: a document's root, or the table at the top of a detached tree. */
internal fun outermostTableOf(container: Any): StandardTable? {
    var current = container
    while (true) current = parentOf(current) ?: break
    return current as? StandardTable
}

private fun topNestingOf(container: Any): Nesting = when (container) {
    is StandardTable -> Nesting.InTable(parts = 0, dots = 0)
    is ArrayOfTables -> Nesting.InTables(parts = 1)
    else -> Nesting.InLine(level = 1)
}

private fun parentOf(container: Any): Any? {
    val slot = when (container) {
        is StandardTable -> container.holder
        is InlineTable -> container.holder
        is TomlArray -> container.holder
        is ArrayOfTables -> container.holder
        else -> null
    }
    return slot?.takeIf { it.holdsContainer(container) }?.owner
}

/** Where [child], stored in a container standing here, stands. */
private fun Nesting.inside(child: Any): Nesting = when (this) {
    is Nesting.InTable -> when (child) {
        is StandardTable -> Nesting.InTable(parts + 1, if (child.isDotted) dots + 1 else 0)
        is ArrayOfTables -> Nesting.InTables(parts + 1)
        else -> Nesting.InLine(dots + 1)
    }
    is Nesting.InTables -> Nesting.InTable(parts, dots = 0)
    is Nesting.InLine -> Nesting.InLine(level + 1)
}

/**
 * [TomlEditError.TooDeep], with the keys from [container] to the first place [item] would print deeper than the parser
 * reads if stored in it under [key] (null for an array's element); or null when it would not.
 */
internal fun tooDeepError(
    container: Any,
    key: String?,
    item: Item,
): TomlEditError.TooDeep? = DepthWalk()
    .apply { place(nestingOf(container), key, item, emptyList()) }
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
