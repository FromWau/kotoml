package com.fromwau.kotoml

import com.fromwau.kern.result.IError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Why an index, an edit or a builder block could not do what it was asked; upstream panics on most of these. */
@Serializable
public sealed interface TomlEditError : IError {
    /**
     * The error as a person reads it, led by the key when it has one, such as
     * `server: expected a table, found an integer`.
     */
    public val message: String
        get() = describe()

    /** Indexing by [key] into a table or inline table that holds no item there. */
    @Serializable
    @SerialName("NotFound")
    public data class NotFound(val key: String) : TomlEditError

    /** A rename's new name [key] already names an item of the table, so nothing changed. */
    @Serializable
    @SerialName("KeyTaken")
    public data class KeyTaken(val key: String) : TomlEditError

    /** A builder opened [key] as a [expected], but the key holds a [found]. */
    @Serializable
    @SerialName("TypeMismatch")
    public data class TypeMismatch(
        val key: String,
        val expected: TomlType,
        val found: TomlType,
    ) : TomlEditError

    /** [index] is past the end of a sequence of [length] elements. */
    @Serializable
    @SerialName("IndexOutOfBounds")
    public data class IndexOutOfBounds(
        val index: Int,
        val length: Int,
    ) : TomlEditError

    /** Indexing by [key] into an item of type [itemType], which is neither a table nor an inline table. */
    @Serializable
    @SerialName("NotATable")
    public data class NotATable(
        val key: String,
        val itemType: TomlType,
    ) : TomlEditError

    /** Indexing by [index] into an item of type [itemType], which is neither an array nor an array of tables. */
    @Serializable
    @SerialName("NotAnArray")
    public data class NotAnArray(
        val index: Int,
        val itemType: TomlType,
    ) : TomlEditError

    /** An inline table or array can only hold values; an item of type [itemType] was given. */
    @Serializable
    @SerialName("NotAValue")
    public data class NotAValue(val itemType: TomlType) : TomlEditError

    /** An array of tables can only hold tables; an item of type [itemType] was given. */
    @Serializable
    @SerialName("NotATableElement")
    public data class NotATableElement(val itemType: TomlType) : TomlEditError

    /**
     * [Item.setLayout] found an item of type [itemType] that cannot take the layout asked for: a slot holding no table,
     * which is any value but an inline table or a non-empty array of only inline tables, and an empty array of tables;
     * or, for [TableLayout.Dotted], an array, an array of tables or an element of either.
     */
    @Serializable
    @SerialName("NotExpandable")
    public data class NotExpandable(val itemType: TomlType) : TomlEditError

    /**
     * Storing would nest deeper than [TomlDocument.NESTING_LIMIT] allows, so the document could not be read back;
     * nothing was stored. [keyPath] leads to where it gets too deep from the document's root, or from the top of the
     * table, array or value being built when that is stored in no document; an element of an array adds no part.
     */
    @Serializable
    @SerialName("TooDeep")
    public data class TooDeep(val keyPath: List<String>) : TomlEditError

    /**
     * A layout change, by [Item.setLayout], [TomlArray.setMultiline] or [TomlDocument.setVersion], would drop [count]
     * comments and was called without `force = true`, so nothing changed. [keyPath] leads to the slot or table from
     * the document's root, or from the top of the table, array or value it is stored under when that is stored in no
     * document; an element of an array adds no part.
     */
    @Serializable
    @SerialName("CommentsWouldBeLost")
    public data class CommentsWouldBeLost(
        val keyPath: List<String>,
        val count: Int,
    ) : TomlEditError

    /**
     * The document's [TomlDocument.version] is TOML 1.0, and the edit would add [feature], which only TOML 1.1 reads,
     * so nothing changed: a key or string spelled with a `\e` or `\x` escape, an inline table across lines or one
     * ending in a comma. [keyPath] leads from the document's root to the key spelled so, or to the key whose value
     * holds it; an element of an array adds no part.
     */
    @Serializable
    @SerialName("NotInToml10")
    public data class NotInToml10(
        val keyPath: List<String>,
        val feature: Toml11Feature,
    ) : TomlEditError
}
