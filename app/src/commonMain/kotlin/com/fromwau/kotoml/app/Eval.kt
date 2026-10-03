package com.fromwau.kotoml.app

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import com.fromwau.kotoml.Item
import com.fromwau.kotoml.TomlDocument

/** An item a filter selected, with the keys that lead to it from the root; an index adds no key. */
internal data class Selected(val item: Item, val path: List<String>)

/** Runs this filter on [document] and collects what it selects. */
internal fun Filter.select(document: TomlDocument): Result<Selection, AppError.CannotApply> =
    evaluate(Item(document.root)).map { Selection(it, streams, document.lineEnding()) }

// The first line ending in the document; a document with none prints with `\n`.
private fun TomlDocument.lineEnding(): String {
    val text = toString()
    val newline = text.indexOf('\n')
    return if (newline > 0 && text[newline - 1] == '\r') "\r\n" else "\n"
}

/** Runs [filter] on [input]; every stage maps each output of the stage before it. A missing key gives none. */
internal fun Filter.evaluate(input: Item): Result<List<Selected>, AppError.CannotApply> {
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

private fun Selected.apply(step: Step): Result<List<Selected>, AppError.CannotApply> {
    if (item.isMissing) return Ok(if (step == Step.Iterate) emptyList() else listOf(Selected(Item(), path)))
    val table = item.tableLikeOrNull
    val array = item.arrayOrNull
    val tables = item.arrayOfTablesOrNull
    return when (step) {
        is Step.Key -> table?.let { Ok(listOf(Selected(it.getItem(step.key) ?: Item(), path + step.key))) }
        is Step.Index -> {
            val size = array?.size ?: tables?.size
            size?.let {
                val index = if (step.index < 0) step.index + size else step.index
                val element = array?.get(index)?.let { Item(it) } ?: tables?.get(index)?.let { Item(it) }
                Ok(listOf(Selected(element ?: Item(), path)))
            }
        }
        Step.Iterate -> when {
            table != null -> Ok(table.items().map { (key, child) -> Selected(child, path + key) })
            array != null -> Ok(array.map { Selected(Item(it), path) })
            tables != null -> Ok(tables.map { Selected(Item(it), path) })
            else -> null
        }
    } ?: Err(AppError.CannotApply(step, item.type))
}
