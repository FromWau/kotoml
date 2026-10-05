package com.fromwau.kotoml.serialization

import com.fromwau.kotoml.Item
import com.fromwau.kotoml.StandardTable
import com.fromwau.kotoml.Table as KeyedTable
import com.fromwau.kotoml.TextPosition
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

    /** A table, an inline table, or a dotted key's parts; [keys] tells where each key was written. */
    class Table(
        val entries: Map<String, Item>,
        override val type: TomlType,
        private val keys: KeyedTable? = null,
    ) : TomlInput {
        fun positionOf(key: String): TextPosition? = keys?.keyOrNull(key)?.position
    }

    /** An array or an array of tables. */
    class Sequence(
        val elements: List<TomlInput>,
        override val type: TomlType,
    ) : TomlInput
}

internal fun Item.toInput(): TomlInput? {
    standardTableOrNull?.let { return it.toInput() }
    arrayOfTablesOrNull?.let { tables ->
        return TomlInput.Sequence(tables.map { it.toInput() }, TomlType.ArrayOfTables)
    }
    return valueOrNull?.toInput()
}

internal fun StandardTable.toInput(): TomlInput.Table = TomlInput.Table(entries.toMap(), TomlType.Table, this)

internal fun Value.toInput(): TomlInput = when (this) {
    is Value.InlineTable -> TomlInput.Table(table.entries.toMap(), TomlType.InlineTable, table)
    is Value.Array -> TomlInput.Sequence(array.map { it.toInput() }, TomlType.Array)
    else -> TomlInput.Scalar(this)
}
