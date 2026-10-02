package com.fromwau.kotoml.app

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kotoml.Item

/** Runs [filter] on [input]; every stage maps each output of the stage before it. A missing key gives none. */
internal fun Filter.evaluate(input: Item): Result<List<Item>, AppError.CannotApply> {
    var current = listOf(input)
    for (stage in stages) {
        for (step in stage) {
            val next = mutableListOf<Item>()
            for (item in current) {
                when (val stepped = item.apply(step)) {
                    is Result.Success -> next += stepped.value
                    is Result.Error -> return stepped
                }
            }
            current = next
        }
    }
    return Ok(current)
}

private fun Item.apply(step: Step): Result<List<Item>, AppError.CannotApply> {
    if (isNone()) return Ok(if (step == Step.Iterate) emptyList() else listOf(Item.none()))
    val table = asTableLike()
    val array = arrayOrNull
    val tables = asArrayOfTables()
    return when (step) {
        is Step.Key -> table?.let { Ok(listOf(it.getItem(step.key) ?: Item.none())) }
        is Step.Index -> {
            val size = array?.len() ?: tables?.len()
            size?.let {
                val index = if (step.index < 0) step.index + size else step.index
                val element = array?.get(index)?.let { Item.from(it) } ?: tables?.get(index)?.let { Item.from(it) }
                Ok(listOf(element ?: Item.none()))
            }
        }
        Step.Iterate -> when {
            table != null -> Ok(table.iterItems().asSequence().map { it.second }.toList())
            array != null -> Ok(array.iter().asSequence().map { Item.from(it) }.toList())
            tables != null -> Ok(tables.iter().asSequence().map { Item.from(it) }.toList())
            else -> null
        }
    } ?: Err(AppError.CannotApply(step, type))
}
