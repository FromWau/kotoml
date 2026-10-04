package com.fromwau.kotoml.app

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import com.fromwau.kotoml.Item
import com.fromwau.kotoml.TomlDocument

/**
 * An item a filter selected, with the keys that lead to it from the root (an index adds no key) and [trail], the
 * jq path that reached it, for errors.
 */
internal data class Selected(
    val item: Item,
    val path: List<String>,
    val trail: String = "",
)

/** Runs this filter on [document] and collects what it selects. */
internal fun Filter.select(document: TomlDocument): Result<Selection, AppError> =
    evaluate(Item(document.root)).map { Selection(it, streams, document.lineEnding()) }

// The first line ending in the document; a document with none prints with `\n`.
private fun TomlDocument.lineEnding(): String {
    val text = toString()
    val newline = text.indexOf('\n')
    return if (newline > 0 && text[newline - 1] == '\r') "\r\n" else "\n"
}

/** Runs [filter] on [input]; every stage maps each output of the stage before it. */
internal fun Filter.evaluate(input: Item): Result<List<Selected>, AppError> {
    var current = listOf(Selected(input, emptyList()))
    for (stage in stages) {
        for (step in stage) {
            val next = mutableListOf<Selected>()
            for (selected in current) {
                when (val stepped = selected.apply(step)) {
                    is Result.Success -> next += stepped.value
                    is Result.Error -> return stepped
                }
            }
            current = next
        }
    }
    return Ok(current)
}

private fun Selected.apply(step: Step): Result<List<Selected>, AppError> {
    val table = item.tableLikeOrNull
    val array = item.arrayOrNull
    val tables = item.arrayOfTablesOrNull
    return when (step) {
        is Step.Key -> table?.let {
            val at = trail + step.locate()
            val child = it.getItem(step.key) ?: return Err(AppError.NoSuchKey(at, step.key))
            Ok(listOf(Selected(child, path + step.key, at)))
        }
        is Step.Index -> {
            val size = array?.size ?: tables?.size
            size?.let {
                val at = "$trail[${step.index}]"
                val index = if (step.index < 0) step.index + size else step.index
                val element = array?.get(index)?.let { Item(it) }
                    ?: tables?.get(index)?.let { Item(it) }
                    ?: return Err(AppError.NoSuchIndex(at, step.index, size))
                Ok(listOf(Selected(element, path, at)))
            }
        }
        Step.Iterate -> when {
            table != null -> Ok(
                table.entries.map { (key, child) -> Selected(child, path + key, trail + Step.Key(key).locate()) },
            )
            array != null -> Ok(array.mapIndexed { index, value -> Selected(Item(value), path, "$trail[$index]") })
            tables != null -> Ok(
                tables.mapIndexed { index, element -> Selected(Item(element), path, "$trail[$index]") },
            )
            else -> null
        }
    } ?: Err(AppError.CannotApply(step, item.type))
}

/** This key as a jq path step: bare when jq would read it bare, quoted otherwise. */
private fun Step.Key.locate(): String {
    val bare = key.isNotEmpty() &&
        key.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }
    return if (bare) ".$key" else ".\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
