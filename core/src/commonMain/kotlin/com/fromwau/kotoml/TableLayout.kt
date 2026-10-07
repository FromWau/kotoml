package com.fromwau.kotoml

/**
 * How a table prints: under a header, as dotted keys, or in braces on one line or across lines. Read it with
 * [Table.layout] or [Item.layout], and change it with [Item.setLayout].
 *
 * ```kotlin
 * val doc = TomlDocument.parse("[t]\na = 1\n").getOrElse { error(it) }
 * doc["t"].getOrNull()?.layout // Header
 * doc["t"].getOrNull()?.setLayout(TableLayout.Inline)
 * doc.toString() // "t = { a = 1 }\n"
 * ```
 */
public sealed interface TableLayout {
    /** Under a header of its own, `[t]`, its values on the lines below. */
    public data object Header : TableLayout

    /** As the first parts of dotted keys on the lines of the table holding it, `t.a = 1`. */
    public data object Dotted : TableLayout

    /** In braces on one line, `t = { a = 1 }`. */
    public data object Inline : TableLayout

    /**
     * In braces across lines, `{` then each entry on a line of its own, then `}`; only TOML 1.1 reads it. The
     * entries are indented two spaces more than the line holding `{`, and in TOML 1.1 the last one ends in a comma.
     */
    public data object MultilineInline : TableLayout
}
