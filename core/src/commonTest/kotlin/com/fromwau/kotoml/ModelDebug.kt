package com.fromwau.kotoml

import com.fromwau.kotoml.writer.rustDebug

// Upstream's derived and hand-written `Debug` for the document model, field for field.

internal fun KeptText.dbg(): Dbg {
    val span = span
    val text = textOrNull
    return when {
        span != null -> lit("${span.start}..${span.end}")
        text.isNullOrEmpty() -> lit("empty")
        else -> str(text)
    }
}

internal fun Spelling.dbg(): Dbg = raw.dbg()

private fun Span?.dbgOption(): Dbg = option(this) { lit("${it.start}..${it.end}") }

internal fun RawDecor.dbg(): Dbg = struct(
    "Decor",
    "prefix" to (prefix?.dbg() ?: str("default")),
    "suffix" to (suffix?.dbg() ?: str("default")),
)

internal fun Key.dbg(): Dbg = struct(
    "Key",
    "key" to str(name),
    "repr" to option(written) { it.dbg() },
    "leaf_decor" to rawLeafDecor.dbg(),
    "dotted_decor" to rawDottedDecor.dbg(),
)

internal fun <T : Any> Formatted<T>.dbg(value: (T) -> Dbg): Dbg = struct(
    "Formatted",
    "value" to value(this.value),
    "repr" to (written?.dbg() ?: str("default")),
    "decor" to decor.dbg(),
)

internal fun Value.dbg(): Dbg = when (this) {
    is Value.String -> tuple("String", formatted.dbg { str(it) })
    is Value.Integer -> tuple("Integer", formatted.dbg { lit(it) })
    is Value.Float -> tuple("Float", formatted.dbg { lit(rustDebug(it)) })
    is Value.Boolean -> tuple("Boolean", formatted.dbg { lit(it) })
    is Value.Datetime -> tuple("Datetime", formatted.dbg { it.dbg() })
    is Value.Array -> tuple("Array", array.dbg())
    is Value.InlineTable -> tuple("InlineTable", table.dbg())
}

internal fun TomlArray.dbg(): Dbg = struct(
    "Array",
    "trailing" to rawTrailing.dbg(),
    "trailing_comma" to lit(trailingComma),
    "decor" to rawDecor.dbg(),
    "span" to span.dbgOption(),
    "values" to seq(values.map { it.dbg() }),
)

private fun KeyValuePairs.dbg(): Dbg = Dbg.MapOf(entries.map { it.key.dbg() to it.item.dbg() })

internal fun InlineTable.dbg(): Dbg = struct(
    "InlineTable",
    "trailing" to rawTrailing.dbg(),
    "trailing_comma" to lit(trailingComma),
    "implicit" to lit(isImplicit),
    "decor" to rawDecor.dbg(),
    "span" to span.dbgOption(),
    "dotted" to lit(isDotted),
    "items" to items.dbg(),
)

internal fun Table.dbg(): Dbg = struct(
    "Table",
    "decor" to rawDecor.dbg(),
    "implicit" to lit(isImplicit),
    "dotted" to lit(isDotted),
    "doc_position" to option(position) { lit(it) },
    "span" to span.dbgOption(),
    "items" to items.dbg(),
)

internal fun ArrayOfTables.dbg(): Dbg = struct(
    "ArrayOfTables",
    "span" to span.dbgOption(),
    "values" to seq(values.map { it.dbg() }),
)

internal fun Item.dbg(): Dbg {
    valueOrNull?.let { return tuple("Value", it.dbg()) }
    tableOrNull?.let { return tuple("Table", it.dbg()) }
    arrayOfTablesOrNull?.let { return tuple("ArrayOfTables", it.dbg()) }
    return lit("None")
}

internal fun TomlDocument.dbg(): Dbg = struct(
    "DocumentMut",
    "root" to Item(root).dbg(),
    "trailing" to rawTrailing.dbg(),
)
