package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Result
import com.fromwau.kotoml.parser.TokenKind
import com.fromwau.kotoml.parser.TomlSource

/**
 * Gives [target], a table or a slot, a new layout with [change], unless that would drop a comment and [force] is
 * false: then nothing changes and the result is [TomlEditError.CommentsWouldBeLost] at [layoutKeyPathOf].
 * [change] runs first on a copy of the tree holding [target], so a failure it returns changes nothing either.
 */
internal fun <T : Any> changeLayout(
    target: T,
    force: Boolean,
    change: (T) -> EmptyResult<TomlEditError>,
): EmptyResult<TomlEditError> {
    if (!force) {
        val trial = Trial.of(target)
        val before = trial.comments()
        val tried = change(trial.target)
        if (tried is Result.Error) return tried
        val lost = missing(before, trial.comments())
        if (lost > 0) return Err(TomlEditError.CommentsWouldBeLost(layoutKeyPathOf(target), lost))
    }
    return change(target)
}

/** The keys to [target], a table or a slot, as [TomlEditError.CommentsWouldBeLost] names them. */
internal fun layoutKeyPathOf(target: Any): List<String> {
    if (target !is Item) return keyPathOf(target)
    val owner = target.owner ?: return emptyList()
    val key = target.entry?.takeIf { it.isStored }?.key?.name
    return if (key == null) keyPathOf(owner) else keyPathOf(owner) + key
}

/** A copy of the whole tree holding a table or slot, with the copy of that table or slot as [target]. */
private class Trial<T : Any>(val top: Any, val target: T) {
    /** The comments the copied tree prints. */
    fun comments(): List<String> = commentsPrinted(top)

    companion object {
        fun <T : Any> of(target: T): Trial<T> {
            // From the top down, the key or the index of each slot on the way to the target.
            val steps = ArrayDeque<Any>()
            var current: Any = target
            if (target is Item) {
                val owner = target.owner
                if (owner == null) {
                    val copy = target.copyWithComments()
                    @Suppress("UNCHECKED_CAST")
                    return Trial(copy, copy as T)
                }
                steps.addFirst(stepTo(target))
                current = owner
            }
            while (true) {
                val slot = slotOf(current) ?: break
                steps.addFirst(stepTo(slot))
                current = slot.owner ?: break
            }
            val top = copyOfTop(current)
            var node: Any = top
            for ((index, step) in steps.withIndex()) {
                val slot = slotAt(node, step) ?: error("a copy holds what its source holds")
                node = if (index == steps.lastIndex && target is Item) slot else checkNotNull(slot.container)
            }
            @Suppress("UNCHECKED_CAST")
            return Trial(top, node as T)
        }

        private fun copyOfTop(top: Any): Any = when (top) {
            is StandardTable -> top.deepCopy().also { copy ->
                copy.isDocumentRoot = top.isDocumentRoot
                copy.lineEnding = top.lineEnding
            }
            is InlineTable -> top.deepCopy()
            is TomlArray -> top.deepCopy()
            is ArrayOfTables -> top.deepCopy()
            else -> error("only a table or an array holds slots")
        }
    }
}

/** The key or index [slot] is stored at in its owner. */
internal fun stepTo(slot: Item): Any {
    slot.entry?.takeIf { it.isStored }?.let { return it.key.name }
    return when (val owner = slot.owner) {
        is TomlArray -> owner.values.indexOfFirst { it === slot }
        is ArrayOfTables -> owner.values.indexOfFirst { it === slot }
        else -> error("a slot stored in a table has a key")
    }
}

/** The slot [stepTo] names in [container], or null when it holds none there. */
internal fun slotAt(
    container: Any,
    step: Any,
): Item? = when (container) {
    is StandardTable -> container.items.getEntry(step as String)?.item
    is InlineTable -> container.items.getEntry(step as String)?.item
    is TomlArray -> container.values.getOrNull(step as Int)
    is ArrayOfTables -> container.values.getOrNull(step as Int)
    else -> null
}

/**
 * The comments [node] prints with its decor, each from `#` to the end of its line: a table as a document of its own,
 * so the tables under it print under their headers, without changing it.
 */
internal fun commentsPrinted(node: Any): List<String> = when (node) {
    is StandardTable -> commentsIn(TomlDocument(node.deepCopy(), KeptText(), node.lineEnding, null).toString())
    is ArrayOfTables -> node.values.flatMap { slot -> slot.standardTableOrNull?.let(::commentsPrinted).orEmpty() }
    is Item -> node.standardTableOrNull?.let(::commentsPrinted)
        ?: node.arrayOfTablesOrNull?.let(::commentsPrinted)
        ?: node.valueOrNull?.let { commentsIn(it.toString()) + commentsIn(it.rawDecor) }
        ?: emptyList()
    is InlineTable -> commentsIn(node.toString()) + commentsIn(node.rawDecor)
    is TomlArray -> commentsIn(node.toString()) + commentsIn(node.rawDecor)
    else -> emptyList()
}

private fun commentsIn(decor: RawDecor): List<String> =
    commentsIn(decor.prefix?.textOrNull.orEmpty()) + commentsIn(decor.suffix?.textOrNull.orEmpty())

/** The comments in [text], read as TOML tokens so a `#` inside a string is no comment. */
private fun commentsIn(text: String): List<String> {
    if ('#' !in text) return emptyList()
    val source = TomlSource(text)
    return source
        .lex()
        .asSequence()
        .filter { it.kind == TokenKind.Comment }
        .map { source.bytes.decodeToString(it.span.start, it.span.end).trimEnd() }
        .toList()
}

/** How many of [before] are missing from [after], each comment matched once. */
internal fun missing(
    before: List<String>,
    after: List<String>,
): Int {
    val left = after.groupingBy { it }.eachCount().toMutableMap()
    return before.count { comment ->
        val count = left[comment] ?: 0
        if (count > 0) left[comment] = count - 1
        count == 0
    }
}
