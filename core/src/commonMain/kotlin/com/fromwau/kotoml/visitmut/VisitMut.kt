package com.fromwau.kotoml.visitmut

import com.fromwau.kotoml.ArrayOfTables
import com.fromwau.kotoml.Datetime
import com.fromwau.kotoml.DocumentMut
import com.fromwau.kotoml.Formatted
import com.fromwau.kotoml.InlineTable
import com.fromwau.kotoml.Item
import com.fromwau.kotoml.KeyMut
import com.fromwau.kotoml.Table
import com.fromwau.kotoml.TableLike
import com.fromwau.kotoml.TomlArray
import com.fromwau.kotoml.Value

/**
 * Walks a document and changes it in place. Each function is a hook for one kind of node; by default it
 * visits the node's children through the matching top-level function, so an override calls that function to
 * keep descending.
 *
 * A [Value] cannot replace itself, so [visitValueMut] returns the value to keep in its place: the node
 * itself, or a replacement. Items are slots and are changed with [Item.assign].
 *
 * ```kotlin
 * class FloatToString : VisitMut {
 *     override fun visitValueMut(node: Value): Value {
 *         val replaced = if (node is Value.Float) {
 *             Value.from(node.formatted.value().toString()).also { it.decorMut().assign(node.decor()) }
 *         } else {
 *             node
 *         }
 *         return visitValueMut(this, replaced)
 *     }
 * }
 * ```
 */
public interface VisitMut {
    /** Visits the document's root table. */
    public fun visitDocumentMut(node: DocumentMut): Unit = visitDocumentMut(this, node)

    /** Visits whatever the item holds. */
    public fun visitItemMut(node: Item): Unit = visitItemMut(this, node)

    /** Visits a table's entries. */
    public fun visitTableMut(node: Table): Unit = visitTableMut(this, node)

    /** Visits an inline table's entries. */
    public fun visitInlineTableMut(node: InlineTable): Unit = visitInlineTableMut(this, node)

    /** Visits the entries of a table or inline table. */
    public fun visitTableLikeMut(node: TableLike): Unit = visitTableLikeMut(this, node)

    /** Visits one entry of a table or inline table. */
    public fun visitTableLikeKvMut(key: KeyMut, node: Item): Unit = visitTableLikeKvMut(this, key, node)

    /** Visits an array's values. */
    public fun visitArrayMut(node: TomlArray): Unit = visitArrayMut(this, node)

    /** Visits the tables of an array of tables. */
    public fun visitArrayOfTablesMut(node: ArrayOfTables): Unit = visitArrayOfTablesMut(this, node)

    /** Visits whatever the value holds and returns the value to keep in its place. */
    public fun visitValueMut(node: Value): Value = visitValueMut(this, node)

    /** Visits a boolean; does nothing by default. */
    public fun visitBooleanMut(node: Formatted<Boolean>) {}

    /** Visits a date-time; does nothing by default. */
    public fun visitDatetimeMut(node: Formatted<Datetime>) {}

    /** Visits a float; does nothing by default. */
    public fun visitFloatMut(node: Formatted<Double>) {}

    /** Visits an integer; does nothing by default. */
    public fun visitIntegerMut(node: Formatted<Long>) {}

    /** Visits a string; does nothing by default. */
    public fun visitStringMut(node: Formatted<String>) {}
}

/** Visits [node]'s root table. */
public fun visitDocumentMut(visitor: VisitMut, node: DocumentMut) {
    visitor.visitTableMut(node.asTableMut())
}

/** Visits what [node] holds, putting a replaced value back into it. */
public fun visitItemMut(visitor: VisitMut, node: Item) {
    val value = node.asValueMut()
    if (value != null) {
        val kept = visitor.visitValueMut(value)
        if (kept !== value) node.assign(Item.from(kept))
    }
    node.asTableMut()?.let { visitor.visitTableMut(it) }
    node.asArrayOfTablesMut()?.let { visitor.visitArrayOfTablesMut(it) }
}

/** Visits [node] as a table-like. */
public fun visitTableMut(visitor: VisitMut, node: Table) {
    visitor.visitTableLikeMut(node)
}

/** Visits [node] as a table-like. */
public fun visitInlineTableMut(visitor: VisitMut, node: InlineTable) {
    visitor.visitTableLikeMut(node)
}

/** Visits every entry of [node]. */
public fun visitTableLikeMut(visitor: VisitMut, node: TableLike) {
    for ((key, item) in node.iterItemsMut()) visitor.visitTableLikeKvMut(key, item)
}

/** Visits the item of one entry. */
public fun visitTableLikeKvMut(visitor: VisitMut, key: KeyMut, node: Item) {
    visitor.visitItemMut(node)
}

/** Visits every value of [node], putting replaced values back in place. */
public fun visitArrayMut(visitor: VisitMut, node: TomlArray) {
    for (index in 0 until node.len()) {
        val value = node.getMut(index) ?: continue
        val kept = visitor.visitValueMut(value)
        if (kept !== value) node.replaceFormatted(index, kept)
    }
}

/** Visits every table of [node]. */
public fun visitArrayOfTablesMut(visitor: VisitMut, node: ArrayOfTables) {
    for (table in node.iterMut()) visitor.visitTableMut(table)
}

/** Visits what [node] holds and returns it, the value to keep. */
public fun visitValueMut(visitor: VisitMut, node: Value): Value {
    when (node) {
        is Value.String -> visitor.visitStringMut(node.formatted)
        is Value.Integer -> visitor.visitIntegerMut(node.formatted)
        is Value.Float -> visitor.visitFloatMut(node.formatted)
        is Value.Boolean -> visitor.visitBooleanMut(node.formatted)
        is Value.Datetime -> visitor.visitDatetimeMut(node.formatted)
        is Value.Array -> visitor.visitArrayMut(node.array)
        is Value.InlineTable -> visitor.visitInlineTableMut(node.table)
    }
    return node
}
