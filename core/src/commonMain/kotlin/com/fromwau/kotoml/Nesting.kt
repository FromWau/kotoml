package com.fromwau.kotoml

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
 * root, or up to the outermost one stored nowhere, which stands as a document's root or a value of its first line.
 */
internal fun nestingOf(container: Any): Nesting {
    val chain = mutableListOf(container)
    while (true) chain.add(parentOf(chain.last()) ?: break)
    var nesting = when (chain.last()) {
        is StandardTable -> Nesting.InTable(parts = 0, dots = 0)
        is ArrayOfTables -> Nesting.InTables(parts = 1)
        else -> Nesting.InLine(level = 1)
    }
    for (index in chain.lastIndex - 1 downTo 0) nesting = nesting.inside(chain[index])
    return nesting
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
 * The keys from [container] to the first place [item] would print deeper than the parser reads if stored in it under
 * [key] (null for an array's element), or null when it would not.
 */
internal fun tooDeepPath(
    container: Any,
    key: String?,
    item: Item,
): List<String>? = DepthWalk().apply { place(nestingOf(container), key, item, emptyList()) }.run()

/** The keys to the first place this table, standing as a document's root, prints deeper than the parser reads. */
internal fun StandardTable.tooDeepPath(): List<String>? =
    DepthWalk().apply { visit(this@tooDeepPath, Nesting.InTable(parts = 0, dots = 0), emptyList()) }.run()

// Iterative, so that measuring content of any depth takes no more stack than measuring a flat table.
private class DepthWalk {
    private class Frame(val container: Any, val nesting: Nesting, val path: List<String>)

    private val limit = TomlDocument.NESTING_LIMIT
    private val frames = ArrayDeque<Frame>()
    private var found: List<String>? = null

    fun visit(container: Any, nesting: Nesting, path: List<String>) {
        frames.addLast(Frame(container, nesting, path))
    }

    fun run(): List<String>? {
        while (found == null) {
            val frame = frames.removeLastOrNull() ?: break
            val (nesting, path) = frame.nesting to frame.path
            when (val container = frame.container) {
                is StandardTable -> for (entry in container.items.entries) place(nesting, entry.key.name, entry.item, path)
                is InlineTable -> for (entry in container.items.entries) place(nesting, entry.key.name, entry.item, path)
                is TomlArray -> for (slot in container.values) place(nesting, null, slot, path)
                is ArrayOfTables -> for (slot in container.values) place(nesting, null, slot, path)
            }
        }
        return found
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
        val container: Any = item.standardTableOrNull
            ?: item.arrayOfTablesOrNull
            ?: item.arrayOrNull
            ?: item.inlineTableOrNull
            ?: run {
                if (keyTooDeep) found = itemPath
                return
            }
        val inside = at.inside(container)
        val tooDeep = when (inside) {
            is Nesting.InTable -> when {
                at is Nesting.InTables || !(container as StandardTable).isDotted -> inside.parts > limit
                // An empty dotted table prints its key with `{}` on a line of the table above.
                else -> container.items.size == 0 && keyTooDeep
            }
            is Nesting.InTables -> false
            is Nesting.InLine -> keyTooDeep || inside.level > limit
        }
        if (tooDeep) found = itemPath else visit(container, inside, itemPath)
    }
}
