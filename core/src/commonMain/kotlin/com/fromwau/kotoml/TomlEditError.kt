package com.fromwau.kotoml

import com.fromwau.kern.result.IError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An edit that upstream refuses by panicking, returned here as a value instead.
 */
@Serializable
public sealed interface TomlEditError : IError {
    /** Indexing by [key] into a table or inline table that holds no item there. */
    @Serializable
    @SerialName("NotFound")
    public data class NotFound(val key: String) : TomlEditError

    /** [index] is past the end of a sequence of [length] elements. */
    @Serializable
    @SerialName("IndexOutOfBounds")
    public data class IndexOutOfBounds(val index: Int, val length: Int) : TomlEditError

    /** Indexing by [key] into an item of type [itemType], which is neither a table nor an inline table. */
    @Serializable
    @SerialName("NotATable")
    public data class NotATable(val key: String, val itemType: TomlType) : TomlEditError

    /** Indexing by [index] into an item of type [itemType], which is neither an array nor an array of tables. */
    @Serializable
    @SerialName("NotAnArray")
    public data class NotAnArray(val index: Int, val itemType: TomlType) : TomlEditError

    /** An inline table or array can only hold values; an item of type [itemType] was given. */
    @Serializable
    @SerialName("NotAValue")
    public data class NotAValue(val itemType: TomlType) : TomlEditError

    /** An array of tables can only hold tables; an item of type [itemType] was given. */
    @Serializable
    @SerialName("NotATableElement")
    public data class NotATableElement(val itemType: TomlType) : TomlEditError
}
