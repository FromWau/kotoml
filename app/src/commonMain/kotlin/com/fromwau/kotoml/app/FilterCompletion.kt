package com.fromwau.kotoml.app

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap
import com.fromwau.kotoml.Item
import com.fromwau.kotoml.TomlDocument

/** A whole filter Tab can put in place of the one typed, and the type of what it selects. */
internal data class FilterCompletion(
    val filter: String,
    val type: String,
)

/**
 * The filters one step longer than what [typed] selects in [document]: a key of each table it reaches, or `[]` and
 * each index of each array. The shell keeps those that start with [typed], so a half-typed last step narrows them.
 */
internal fun filterCompletions(
    document: TomlDocument,
    typed: String,
): List<FilterCompletion> {
    val parent = typed.take(typed.lastStepStart())
    // A trailing `|` starts a new stage, which reads every item the stages before it select.
    val path = parent.trimEnd().removeSuffix("|")
    val reached = if (path.isBlank()) {
        listOf(Item(document.root))
    } else {
        val selected = parseFilter(path).flatMap { it.evaluate(Item(document.root)) }
        (selected as? Result.Success)?.value?.map { it.item } ?: return emptyList()
    }
    // The same step can reach items of different types, an element of each table of an array for one.
    return reached
        .flatMap { it.stepsFrom(parent) }
        .groupBy { it.filter }
        .map { (filter, steps) ->
            val types = steps.flatMap { it.types }.distinct().ifEmpty { listOf(steps.first().whenEmpty) }
            FilterCompletion(filter, types.joinToString(" or "))
        }
}

/** A step from one item: the [types] of what it selects, and what to call it when it selects nothing. */
private data class Reachable(
    val filter: String,
    val types: List<String>,
    val whenEmpty: String,
)

private fun Item.stepsFrom(parent: String): List<Reachable> {
    tableOrNull?.let { table ->
        return table.entries.map { (key, child) ->
            val type = child.type.words()
            Reachable(parent + Step.Key(key).describe(), listOf(type), type)
        }
    }
    val elements = arrayOrNull?.map { Item(it) } ?: arrayOfTablesOrNull?.map { Item(it) } ?: return emptyList()
    val each = elements.map { it.type.words() }
    // `[]` selects every element in turn, so it reads as they do; an empty array selects none.
    return listOf(Reachable("$parent[]", each.distinct(), "empty array")) +
        elements.indices.map { index -> Reachable("$parent[$index]", listOf(each[index]), each[index]) }
}

/** Where the step being typed starts: the last `.` or `[` outside a quoted key, or 0 for none. */
private fun String.lastStepStart(): Int {
    var start = 0
    var quoted = false
    var index = 0
    while (index < length) {
        when (this[index]) {
            '\\' -> if (quoted) index++
            '"' -> quoted = !quoted
            '.', '[' -> if (!quoted) start = index
            else -> Unit
        }
        index++
    }
    return start
}
