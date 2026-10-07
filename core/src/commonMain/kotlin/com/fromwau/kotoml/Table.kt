package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Result

/**
 * A TOML table of either kind: a [StandardTable] (`[section]`) or an [InlineTable] (`{ a = 1 }`), seen as key/item
 * pairs.
 *
 * ```kotlin
 * fun names(table: Table): List<String> = table.entries.map { it.first }
 * ```
 */
public sealed interface Table {
    /** The key/item pairs, in order. */
    public val entries: List<Pair<String, Item>>

    /** The item at [key], or null when there is none. */
    public fun getItem(key: String): Item?

    /**
     * Sets [key] to a copy of [item].
     *
     * @return the item [key] held before, or null when it held none; [TomlEditError.NotAValue] when this is an
     *   inline table and [item] holds no value; or [TomlEditError.TooDeep], storing nothing, when [item] would nest
     *   deeper than the parser reads.
     */
    public fun putItem(
        key: String,
        item: Item,
    ): Result<Item?, TomlEditError>

    /**
     * Removes [key] and returns its item, or null when it held none. Only the key's own text goes; the comment and
     * blank lines above it stay where they print, as [StandardTable.remove] and [InlineTable.remove] keep them.
     */
    public fun removeItem(key: String): Item?

    /** The number of keys. */
    public val size: Int

    /** The keys, in order, as a copy that later changes to the table leave as it is. */
    public val keys: Set<String>

    /** Whether the table has no keys. */
    public fun isEmpty(): Boolean

    /** Whether the table has a key. */
    public fun isNotEmpty(): Boolean

    /** Removes every entry, keeping the comment and blank lines above each. */
    public fun clear()

    /** Whether the table has [key]. */
    public fun containsKey(key: String): Boolean

    /** Whether the table has [key], for `key in table`. */
    public operator fun contains(key: String): Boolean

    /** The values visually under this table, with the key path that reaches each. */
    public fun valuesWithPaths(): List<Pair<List<Key>, Value>>

    /** Drops the formatting of the keys and values in this table. */
    public fun resetFormatting()

    /** Whether this table stands for the parts of a dotted key; [setDotted] changes it. */
    public val isDotted: Boolean

    /**
     * Makes this table stand for the parts of a dotted key when [value] is true, or prints it under a header or in
     * braces when false, a new layout: the lines above its key stay and each value keeps the comment on its line,
     * while its header comment, the comment after its braces and the comment lines inside it go. A table already laid
     * out as asked stays as it is.
     *
     * ```kotlin
     * val doc = TomlDocument.parse("[a] # a\nb = 1\n").getOrElse { error(it) }
     * val table = doc["a"].getOrNull()?.standardTableOrNull
     * table?.setDotted(true) // fails with CommentsWouldBeLost(["a"], 1): the header comment would go
     * table?.setDotted(true, force = true)
     * doc.toString() // "a.b = 1\n"
     * ```
     *
     * @return [TomlEditError.CommentsWouldBeLost], changing nothing, when the new layout would drop a comment and
     *   [force] is false; with [force] true the comments go.
     */
    public fun setDotted(
        value: Boolean,
        force: Boolean = false,
    ): EmptyResult<TomlEditError>

    /** The key [name] as stored, whose formatting can be changed in place; null when there is none. */
    public fun keyOrNull(name: String): Key?
}
