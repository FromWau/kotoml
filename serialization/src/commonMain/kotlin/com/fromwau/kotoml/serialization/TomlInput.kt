package com.fromwau.kotoml.serialization

import com.fromwau.kotoml.Item
import com.fromwau.kotoml.TomlType
import com.fromwau.kotoml.Value

/** A document's item seen as the decoder reads it: a scalar, a table of any form, or a list of any form. */
internal sealed interface TomlInput {
    /** The kind of item as the document holds it, for errors. */
    val type: TomlType

    class Scalar(val value: Value) : TomlInput {
        override val type: TomlType
            get() = value.type
    }

    /** A table, an inline table, or a dotted key's parts. */
    class Table(
        val entries: Map<String, Item>,
        override val type: TomlType,
    ) : TomlInput

    /** An array or an array of tables. */
    class Sequence(
        val elements: List<TomlInput>,
        override val type: TomlType,
    ) : TomlInput
}

internal fun Item.toInput(): TomlInput? {
    tableOrNull?.let { return TomlInput.Table(it.items().toMap(), TomlType.Table) }
    arrayOfTablesOrNull?.let { tables ->
        val elements = tables.map { TomlInput.Table(it.items().toMap(), TomlType.Table) }
        return TomlInput.Sequence(elements, TomlType.ArrayOfTables)
    }
    return valueOrNull?.toInput()
}

internal fun Value.toInput(): TomlInput = when (this) {
    is Value.InlineTable -> TomlInput.Table(table.items().toMap(), TomlType.InlineTable)
    is Value.Array -> TomlInput.Sequence(array.map { it.toInput() }, TomlType.Array)
    else -> TomlInput.Scalar(this)
}
