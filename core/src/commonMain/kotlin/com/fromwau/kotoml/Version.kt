package com.fromwau.kotoml

import com.fromwau.kern.result.getOrElse

/**
 * Why storing [item] in [container] under [key], spelled as [spelled] when given, is refused: it would nest too deep,
 * or add to a TOML 1.0 document what only TOML 1.1 reads. Null when it may be stored.
 */
internal fun storeError(
    container: Any,
    key: String?,
    item: Item,
    spelled: Key? = null,
): TomlEditError? = tooDeepError(container, key, item) ?: notInToml10Error(container, key, item, spelled)

/**
 * [TomlEditError.NotInToml10] for the first key or value, in the order the tables hold them, that storing [item] in
 * [container] under [key] would add to a TOML 1.0 document although only TOML 1.1 reads it; null when [container]
 * is in no TOML 1.0 document or [item] holds none.
 */
internal fun notInToml10Error(
    container: Any,
    key: String?,
    item: Item,
    spelled: Key? = null,
): TomlEditError.NotInToml10? {
    if (documentVersionOf(container) != TomlVersion.V1_0) return null
    val path = keyPathOf(container) + listOfNotNull(key)
    spelled?.toml11Escape()?.let { return TomlEditError.NotInToml10(path, it) }
    return item.toml11Only(path)
}

private fun Item.toml11Only(path: List<String>): TomlEditError.NotInToml10? {
    standardTableOrNull?.let { return it.items.toml11Only(path) }
    arrayOfTablesOrNull?.let { tables -> return tables.values.firstNotNullOfOrNull { it.toml11Only(path) } }
    return when (val value = valueOrNull) {
        is Value.String -> value.spelling?.toml11Escape()?.let { TomlEditError.NotInToml10(path, it) }
        is Value.Array -> value.value.values.firstNotNullOfOrNull { it.toml11Only(path) }
        is Value.InlineTable -> value.value.toml11Only(path)
        else -> null
    }
}

private fun InlineTable.toml11Only(path: List<String>): TomlEditError.NotInToml10? = when {
    layout == TableLayout.MultilineInline -> TomlEditError.NotInToml10(path, Toml11Feature.MultilineInlineTable)
    !isDotted && trailingComma && isNotEmpty() -> {
        TomlEditError.NotInToml10(path, Toml11Feature.InlineTableTrailingComma)
    }
    else -> items.toml11Only(path)
}

private fun KeyValuePairs.toml11Only(path: List<String>): TomlEditError.NotInToml10? {
    for (entry in entries) {
        val keyPath = path + entry.key.name
        entry.key.toml11Escape()?.let { return TomlEditError.NotInToml10(keyPath, it) }
        entry.item.toml11Only(keyPath)?.let { return it }
    }
    return null
}

private fun Key.toml11Escape(): Toml11Feature? = spelling?.toml11Escape()

/** The first escape only TOML 1.1 reads, `\e` or `\x`, this basic string or quoted key spelling uses, or null. */
private fun String.toml11Escape(): Toml11Feature? {
    if (!startsWith('"')) return null
    var index = 0
    while (index < length) {
        if (this[index] != '\\') {
            index++
            continue
        }
        when (getOrNull(index + 1)) {
            'e' -> return Toml11Feature.EscapeEscape
            'x' -> return Toml11Feature.HexEscape
        }
        index += 2
    }
    return null
}

/** [spelling], a basic string or quoted key, with each `\e` and `\xHH` escape spelled as the `\u` escape for it. */
private fun toml10Spelling(spelling: String): String {
    if (spelling.toml11Escape() == null) return spelling
    return buildString {
        var index = 0
        while (index < spelling.length) {
            val char = spelling[index]
            if (char != '\\' || index + 1 == spelling.length) {
                append(char)
                index++
                continue
            }
            when (val escaped = spelling[index + 1]) {
                'e' -> append("\\u001B")
                // The two hex digits follow as they are.
                'x' -> append("\\u00")
                else -> append('\\').append(escaped)
            }
            index += 2
        }
    }
}

/** [spelling], the spelling of [datetime], with `:00` after the minutes of a time without seconds. */
internal fun spellingWithSeconds(
    spelling: String,
    datetime: Datetime,
): String {
    val time = datetime.time
    if (time == null || time.second != null) return spelling
    // `HH:MM` follows a date's 10 characters and the separator, or opens a time alone.
    val minutesEnd = if (datetime.date == null) 5 else 16
    return spelling.substring(0, minutesEnd) + ":00" + spelling.substring(minutesEnd)
}

/** Changes what only TOML 1.1 reads under [root] into what TOML 1.0 reads; see [TomlDocument.setVersion]. */
internal fun toml10Conversion(root: StandardTable): Toml10Conversion = Toml10Conversion().also { it.convert(root) }

/** A walk changing a tree into TOML 1.0, which counts the comments putting its inline tables on one line drops. */
internal class Toml10Conversion {
    private var firstLoss: List<String>? = null
    private var lost = 0

    /** [TomlEditError.CommentsWouldBeLost] for the comments the walk dropped, or null when it dropped none. */
    fun lostComments(): TomlEditError.CommentsWouldBeLost? =
        firstLoss?.let { TomlEditError.CommentsWouldBeLost(it, lost) }

    fun convert(table: StandardTable) {
        table.headerSpelling?.respellKeys(::toml10Spelling)
        convert(table.items)
    }

    private fun convert(pairs: KeyValuePairs) {
        for (entry in pairs.entries) {
            entry.key.respell()
            entry.pathSpelling?.respellKeys(::toml10Spelling)
            convert(entry.item)
        }
    }

    private fun convert(slot: Item) {
        slot.standardTableOrNull?.let { return convert(it) }
        slot.arrayOfTablesOrNull?.let { tables ->
            tables.values.forEach(::convert)
            return
        }
        when (val value = slot.valueOrNull) {
            is Value.String -> value.formatted.written = value.spelling?.let { Spelling(toml10Spelling(it)) }
            is Value.Datetime -> value.giveSeconds()
            is Value.Array -> value.value.values.forEach(::convert)
            is Value.InlineTable -> {
                if (value.value.layout == TableLayout.MultilineInline) putOnOneLine(slot)
                value.value.trailingComma = false
                convert(value.value.items)
            }
            else -> Unit
        }
    }

    private fun putOnOneLine(slot: Item) {
        // The header comment may move past the comma, into the text of the array or inline table holding the slot.
        val scope = slot.owner?.takeIf { it is TomlArray || it is InlineTable } ?: slot
        val before = commentsPrinted(scope)
        slot.setLayout(TableLayout.Inline, force = true)
        val dropped = missing(before, commentsPrinted(scope))
        if (dropped > 0 && firstLoss == null) firstLoss = layoutKeyPathOf(slot)
        lost += dropped
    }

    private fun Key.respell() {
        spelling?.let { written = Spelling(toml10Spelling(it)) }
    }
}

/**
 * Gives each time without seconds in [stored], just stored in [container], seconds of 0 when [container] is in a TOML
 * 1.0 document, which prints them; see [TomlDocument.setVersion].
 */
internal fun giveSecondsInToml10(
    container: Any,
    stored: Item,
) {
    if (documentVersionOf(container) == TomlVersion.V1_0) giveSecondsWithin(stored)
}

private fun giveSecondsWithin(slot: Item) {
    slot.standardTableOrNull?.let { table -> return table.items.entries.forEach { giveSecondsWithin(it.item) } }
    slot.arrayOfTablesOrNull?.let { tables -> return tables.values.forEach(::giveSecondsWithin) }
    when (val value = slot.valueOrNull) {
        is Value.Datetime -> value.giveSeconds()
        is Value.Array -> value.value.values.forEach(::giveSecondsWithin)
        is Value.InlineTable -> value.value.items.entries.forEach { giveSecondsWithin(it.item) }
        else -> Unit
    }
}

private fun Value.Datetime.giveSeconds() {
    val time = value.time?.takeIf { it.second == null } ?: return
    val full = Time.of(time.hour, time.minute, 0).getOrElse { error("a time read or built is in range") }
    val datetime = when (val old = value) {
        is Datetime.OffsetDateTime -> old.copy(time = full)
        is Datetime.LocalDateTime -> old.copy(time = full)
        is Datetime.LocalTime -> old.copy(time = full)
        is Datetime.LocalDate -> old
    }
    formatted = formatted.withValue(datetime, spelling?.let { Spelling(spellingWithSeconds(it, value)) })
}
