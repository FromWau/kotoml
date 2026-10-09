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
    return reached
        .flatMap { it.stepsFrom(parent) }
        .distinctBy { it.filter }
}

private fun Item.stepsFrom(parent: String): List<FilterCompletion> {
    tableOrNull?.let { table ->
        return table.entries.map { (key, child) ->
            FilterCompletion(parent + Step.Key(key).describe(), child.type.words())
        }
    }
    val elements = arrayOrNull?.map { Item(it) } ?: arrayOfTablesOrNull?.map { Item(it) } ?: return emptyList()
    return listOf(FilterCompletion("$parent[]", type.words())) +
        elements.mapIndexed { index, element -> FilterCompletion("$parent[$index]", element.type.words()) }
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
