package com.fromwau.kotoml

// Display writes text as it would appear in a document: a span into the original input is never resolved
// here, so a decor still held as a span prints its default and a spelling held as a span prints the default
// spelling, as upstream's Display does.

internal fun encodeKey(
    buf: StringBuilder,
    key: Key,
) {
    buf.append(key.displaySpelling)
}

internal fun encodeKeyPath(
    buf: StringBuilder,
    path: List<Key>,
    defaultPrefix: String,
    defaultSuffix: String,
    leafDecor: RawDecor,
) {
    for ((index, key) in path.withIndex()) {
        val dottedDecor = key.rawDottedDecor
        val first = index == 0
        val last = index + 1 == path.size

        if (first) {
            leafDecor.prefixEncode(buf, defaultPrefix)
        } else {
            buf.append('.')
            dottedDecor.prefixEncode(buf, DEFAULT_KEY_PATH_DECOR.first)
        }

        encodeKey(buf, key)

        if (last) {
            leafDecor.suffixEncode(buf, defaultSuffix)
        } else {
            dottedDecor.suffixEncode(buf, DEFAULT_KEY_PATH_DECOR.second)
        }
    }
}

internal fun encodeFormatted(
    buf: StringBuilder,
    formatted: Formatted<*>,
    defaultPrefix: String,
    defaultSuffix: String,
    decor: RawDecor = formatted.decor,
    spelling: String = formatted.displaySpelling,
) {
    decor.prefixEncode(buf, defaultPrefix)
    buf.append(spelling)
    decor.suffixEncode(buf, defaultSuffix)
}

internal fun encodeArray(
    buf: StringBuilder,
    array: TomlArray,
    defaultPrefix: String,
    defaultSuffix: String,
    decor: RawDecor = array.rawDecor,
    version: TomlVersion = TomlVersion.V1_1,
) {
    decor.prefixEncode(buf, defaultPrefix)
    buf.append('[')

    var index = 0
    for (item in array.values) {
        val value = item.valueOrNull ?: continue
        if (index == 0) {
            val (prefix, suffix) = DEFAULT_LEADING_VALUE_DECOR
            encodeValue(buf, value, prefix, suffix, version = version)
        } else {
            buf.append(',')
            encodeValue(buf, value, DEFAULT_VALUE_DECOR.first, DEFAULT_VALUE_DECOR.second, version = version)
        }
        index++
    }
    if (array.trailingComma && array.isNotEmpty()) buf.append(',')

    encodeRaw(buf, array.rawTrailing.textOr(""))
    buf.append(']')
    decor.suffixEncode(buf, defaultSuffix)
}

internal fun encodeInlineTable(
    buf: StringBuilder,
    table: InlineTable,
    defaultPrefix: String,
    defaultSuffix: String,
    decor: RawDecor = table.rawDecor,
    version: TomlVersion = TomlVersion.V1_1,
) {
    decor.prefixEncode(buf, defaultPrefix)
    buf.append('{')

    val lines = table.bodyLines()
    val lastValue = lines.indexOfLast { it is VisualValue }
    // Kept lines print after the comma that ends the entry before them.
    val kept = StringBuilder()
    var first = true
    for ((index, line) in lines.withIndex()) {
        val child = when (line) {
            is KeptLines -> {
                kept.append(line.text)
                continue
            }
            is VisualValue -> line
        }
        val keyPath = child.spelledPath()
        val value = child.value
        if (!first) buf.append(',')
        first = false
        buf.append(kept)
        kept.clear()
        val inner = if (index == lastValue) DEFAULT_TRAILING_VALUE_DECOR else DEFAULT_VALUE_DECOR
        val keyDecor = keyPath.last().rawLeafDecor
        val leafDecor = child.lineStart?.let { RawDecor(it, keyDecor.suffix) } ?: keyDecor
        val (keyPrefix, keySuffix) = DEFAULT_INLINE_KEY_DECOR
        encodeKeyPath(buf, keyPath, keyPrefix, keySuffix, leafDecor)
        buf.append('=')
        encodeValue(buf, value, inner.first, inner.second, version = version)
    }
    if (table.trailingComma && table.isNotEmpty()) buf.append(',')
    buf.append(kept)

    encodeRaw(buf, table.rawTrailing.textOr(""))
    buf.append('}')
    decor.suffixEncode(buf, defaultSuffix)
}

internal fun encodeValue(
    buf: StringBuilder,
    value: Value,
    defaultPrefix: String,
    defaultSuffix: String,
    decor: RawDecor = value.rawDecor,
    version: TomlVersion = TomlVersion.V1_1,
) {
    when (value) {
        is Value.String -> {
            encodeFormatted(buf, value.formatted, defaultPrefix, defaultSuffix, decor, value.displaySpelling)
        }
        is Value.Integer -> encodeFormatted(buf, value.formatted, defaultPrefix, defaultSuffix, decor)
        is Value.Float -> encodeFormatted(buf, value.formatted, defaultPrefix, defaultSuffix, decor)
        is Value.Boolean -> encodeFormatted(buf, value.formatted, defaultPrefix, defaultSuffix, decor)
        is Value.Datetime -> {
            val spelling = value.formatted.datetimeSpellingIn(version)
            encodeFormatted(buf, value.formatted, defaultPrefix, defaultSuffix, decor, spelling)
        }
        is Value.Array -> encodeArray(buf, value.value, defaultPrefix, defaultSuffix, decor, version)
        is Value.InlineTable -> encodeInlineTable(buf, value.value, defaultPrefix, defaultSuffix, decor, version)
    }
}

// TOML 1.0 has no time without seconds, so one prints with `:00` after its minutes.
internal fun Formatted<Datetime>.datetimeSpellingIn(version: TomlVersion): String =
    if (version == TomlVersion.V1_0) spellingWithSeconds(displaySpelling, value) else displaySpelling

/** Writes lines, each ending as it ended in the text it came from; a line made by hand ends like the one before. */
internal class LineWriter(
    val buf: StringBuilder,
    private var lastEnding: String,
    val version: TomlVersion,
) {
    /** How the line before ended, which a line made by hand ends with too. */
    val ending: String
        get() = lastEnding

    fun endLine(ending: String?) {
        if (ending != null) lastEnding = ending
        buf.append(lastEnding)
    }
}

private fun encodeKeyValueLine(
    writer: LineWriter,
    visual: VisualValue,
) {
    val buf = writer.buf
    val keyPath = visual.spelledPath()
    val value = visual.value
    var leafDecor = keyPath.last().rawLeafDecor
    var valueDecor = value.rawDecor
    visual.lineStart?.let { start ->
        leafDecor = RawDecor(start, leafDecor.suffix.takeUnless { visual.defaultKeySpacing })
    }
    if (visual.spacedForBraces) {
        // After a value in braces only a comment carries over; the space before `}` does not.
        val comment = valueDecor.suffix?.textOrNull?.takeIf { '#' in it }?.substringBefore('\n')?.trimEnd('\r')
        valueDecor = RawDecor(valueDecor.prefix, comment?.let(KeptText::from))
    }
    encodeKeyPath(buf, keyPath, DEFAULT_KEY_DECOR.first, DEFAULT_KEY_DECOR.second, leafDecor)
    buf.append('=')
    encodeValue(buf, value, DEFAULT_VALUE_DECOR.first, DEFAULT_VALUE_DECOR.second, valueDecor, writer.version)
    buf.append(visual.lineEnd)
    writer.endLine(visual.entry.lineEnding)
}

private fun encodeBodyLines(
    writer: LineWriter,
    table: StandardTable,
) {
    var indent = ""
    for (line in table.bodyLines()) {
        when (line) {
            is VisualValue -> {
                val start = line.lineStart ?: line.spelledPath().last().rawLeafDecor.prefix
                if (start == null) {
                    // A line made by hand is indented like the line above it.
                    val indented = if (indent.isEmpty()) line else line.startingWith(KeptText.from(indent))
                    encodeKeyValueLine(writer, indented)
                } else {
                    start.textOrNull?.let { indent = it.substringAfterLast('\n') }
                    encodeKeyValueLine(writer, line)
                }
            }
            is KeptLines -> writer.buf.append(line.text)
        }
    }
}

/** A table's own key/value pairs, one per line, in TOML of [version]: upstream's `Display for Table`. */
internal fun encodeTableBody(
    buf: StringBuilder,
    table: StandardTable,
    version: TomlVersion,
) {
    settleHeadersForDepth(table)
    encodeBodyLines(LineWriter(buf, lineEndingOf(table), version), table)
}

/** What prints among a document's tables, in the order of [position]. */
private sealed class DocumentPart(
    // Where the part stands among the document's tables, or null for one made by hand.
    var slot: Long?,
) {
    // The part a part made by hand prints next to, before it when walked before it and after it otherwise.
    var anchor: DocumentPart? = null

    // The slotted part found by following the anchors, kept so a long run of parts made by hand prints in linear time.
    private var slotted: DocumentPart? = null
    private var isResolved = false

    val position: Long
        get() = slottedPart()?.slot ?: 0L

    /** Forgets the slotted part found for this part, for when an anchor on the way to it changes. */
    fun forgetPosition() {
        slotted = null
        isResolved = false
    }

    // Anchors point to parts walked earlier or to slotted parts, so following them ends.
    private fun slottedPart(): DocumentPart? {
        if (slot != null) return this
        if (isResolved) return slotted
        val walked = mutableListOf<DocumentPart>()
        var part: DocumentPart? = this
        while (part != null && part.slot == null && !part.isResolved) {
            walked += part
            part = part.anchor
        }
        val found = if (part == null || part.slot != null) part else part.slotted
        for (each in walked) {
            each.slotted = found
            each.isResolved = true
        }
        return found
    }
}

private class TableToVisit(
    slot: Long?,
    val table: StandardTable,
    val path: List<Key>,
    val isArray: Boolean,
) : DocumentPart(slot)

private class KeptHeaderLines(
    slot: Long?,
    val text: String,
) : DocumentPart(slot)

/** A whole document, tables in their original order: upstream's `Display for DocumentMut`. */
internal fun encodeDocument(buf: StringBuilder, document: TomlDocument) {
    val root = document.root
    settleHeadersForDepth(root)
    val decor = root.rawDecor
    val headStart = buf.length
    decor.prefixEncode(buf, DEFAULT_ROOT_DECOR.first)
    val headEnd = buf.length
    val writer = LineWriter(buf, document.lineEnding, document.version)

    val collected = DocumentParts()
    collected.visitTable(root, mutableListOf(), isArrayOfTables = false)
    val ordered = collected.parts.sortedWith(
        compareBy({ it !is TableToVisit || it.path.isNotEmpty() }, { it.position }),
    )
    // A document's head is content the first table is set apart from, like keys.
    var firstTable = decor.prefix?.textOrNull.isNullOrEmpty()
    var headerPrinted = false
    val rootPrintsLines = root.visualValues().isNotEmpty()
    val keptRanges = mutableListOf<IntRange>()
    var rootPrinted = false
    for (part in ordered) {
        when (part) {
            is TableToVisit -> {
                val firstHeader = !headerPrinted && part.path.isNotEmpty() && rootPrintsLines
                firstTable = visitTable(writer, part.table, part.path, part.isArray, firstTable, firstHeader)
                if (part.path.isNotEmpty()) headerPrinted = true else rootPrinted = buf.length > headEnd
            }
            is KeptHeaderLines -> {
                if (part.text.isNotEmpty()) keptRanges += buf.length until buf.length + part.text.length
                buf.append(part.text)
            }
        }
    }
    val printedBelowHead = buf.length > headEnd
    decor.suffixEncode(buf, DEFAULT_ROOT_DECOR.second)
    encodeRaw(buf, document.rawTrailing.textOr(""))
    for (range in keptRanges.asReversed()) dropBlankLinesMeeting(buf, range)
    // Blank lines above the first table open the file only where it was read with them.
    val head = buf.substring(0, headEnd).removePrefix("\uFEFF")
    if (head.isEmpty() && !rootPrinted && !document.readStartingBlank) {
        buf.deleteRange(headEnd, headEnd + blankLinesAt(buf, headEnd))
    }
    // A head read without a final line break needs one once anything follows it.
    if (buf.length > headEnd && head.isNotEmpty() && !head.endsWith('\n')) buf.insert(headEnd, writer.ending)
    // A head ending in the lines of the last removed table ends the file while nothing prints below it.
    if (root.headEndsInRemovedTable && !printedBelowHead) dropBlankLinesMeeting(buf, headStart until headEnd)
}

/**
 * Drops the blank lines ending the [kept] lines of a removed table when blank lines follow them or nothing does, so
 * removing a table leaves one blank line between its neighbours and none at the end of the file.
 */
private fun dropBlankLinesMeeting(buf: StringBuilder, kept: IntRange) {
    val after = kept.last + 1
    if (after < buf.length && blankLinesAt(buf, after) == 0) return
    var blankStart = after
    while (blankStart > kept.first) {
        val lineStart = buf.lastIndexOf("\n", blankStart - 2).let { if (it < kept.first) kept.first else it + 1 }
        if (buf.substring(lineStart, blankStart).isNotBlank()) break
        blankStart = lineStart
    }
    buf.deleteRange(blankStart, after)
}

/** The length of the blank lines starting at [start], up to the line holding anything else. */
private fun blankLinesAt(buf: CharSequence, start: Int): Int {
    var end = start
    var index = start
    while (index < buf.length) {
        when (buf[index]) {
            ' ', '\t', '\r' -> index++
            '\n' -> end = ++index
            else -> break
        }
    }
    return end - start
}

/**
 * The parts of a document in the order its tables nest. A table made by hand follows the last table walked, or the
 * lines kept from its parent's removed tables when they stand later, and the elements of an array of tables print in
 * array order.
 */
private class DocumentParts {
    val parts = mutableListOf<DocumentPart>()
    private var lastTable: DocumentPart? = null

    // The kept lines of each table being walked, innermost last.
    private val keptOfParents = ArrayDeque<List<DocumentPart>>()

    fun visitTable(
        table: StandardTable,
        path: MutableList<Key>,
        isArrayOfTables: Boolean,
    ) {
        // A root or an array-of-tables element has nowhere to print its values but under its own header. A dotted
        // table with no lines of its own prints its header's comments where its header would stand.
        val ownPart = path.isEmpty() || isArrayOfTables || !table.printsDotted
        if (ownPart || table.keepsHeaderCommentsAmongTables(path.last())) {
            val part = TableToVisit(table.position, table, path.toList(), isArrayOfTables)
            add(part)
            lastTable = part
        }
        val kept = table.keptHeaderLines.map { KeptHeaderLines(it.order, it.text) }
        kept.forEach(::add)

        keptOfParents.addLast(kept)
        for (entry in table.items.entries) {
            val child = entry.item.standardTableOrNull
            val array = entry.item.arrayOfTablesOrNull
            path.add(entry.key)
            if (child != null) visitTable(child, path, isArrayOfTables = false)
            if (array != null) visitArray(array, path)
            path.removeAt(path.size - 1)
        }
        keptOfParents.removeLast()
    }

    private fun visitArray(
        array: ArrayOfTables,
        path: MutableList<Key>,
    ) {
        // An empty array prints `key = []` among its parent's lines, under these lines.
        val kept = array.keptHeaderLines
            .takeIf { array.isNotEmpty() }
            .orEmpty()
            .map { KeptHeaderLines(it.order, it.text) }
        kept.forEach(::add)
        // The lines kept from removed elements stay where they printed, so an element made by hand comes after them.
        keptOfParents.addLast(kept)
        val blocks = array.map { element ->
            val start = parts.size
            visitTable(element, path, isArrayOfTables = true)
            parts.subList(start, parts.size).toList()
        }
        keptOfParents.removeLast()
        placeInArrayOrder(blocks)
    }

    private fun add(part: DocumentPart) {
        if (part.slot == null) {
            val candidates = listOfNotNull(lastTable) + keptOfParents.lastOrNull().orEmpty()
            part.anchor = candidates.maxByOrNull { it.position }
        }
        parts += part
    }
}

/**
 * Hands the slots the [blocks] of one array of tables hold out again in array order, each element's header first and
 * the parts under it right after it in their order, whatever slots a copied table brought along. Parts made by hand
 * ahead of every slotted one print just before the first element that has a slot, so a new first element prints
 * first. An element made by hand prints just before the earliest slotted part under it, or after the elements
 * before it when nothing under it has a slot.
 */
private fun placeInArrayOrder(blocks: List<List<DocumentPart>>) {
    val slotted = blocks.flatMap { block ->
        val header = block.first()
        val under = block
            .drop(1)
            .filter { it.slot != null }
            .sortedBy { it.slot }
        listOfNotNull(header.takeIf { it.slot != null }) + under
    }
    val first = slotted.firstOrNull() ?: return
    // Only these parts can have found their slotted part through what changes here: later parts are not walked yet.
    for (block in blocks) for (part in block) part.forgetPosition()
    val slots = slotted
        .mapNotNull { it.slot }
        .sorted()
        .iterator()
    for (part in slotted) part.slot = slots.next()
    for (part in blocks.flatten().takeWhile { it.slot == null }) part.anchor = first
    var latest: DocumentPart? = null
    for (block in blocks) {
        val header = block.first()
        val earliestUnder = block.filter { it.slot != null }.minByOrNull { it.position }
        if (header.slot == null) (earliestUnder ?: latest)?.let { header.anchor = it }
        // The last of equal parts, since an outer array's pass keeps their walk order when it hands slots out again.
        for (part in block) if (part.position >= (latest?.position ?: Long.MIN_VALUE)) latest = part
    }
}

/**
 * Splits the leaf decor of a header's last key into the part written before `[` and the part inside it:
 * the spec allows only spaces and tabs inside the brackets, so a prefix holding a newline goes before them.
 */
private fun leafDecorBeforeBracket(path: List<Key>): Pair<RawDecor?, RawDecor> {
    val lastKey = path.lastOrNull() ?: return null to RawDecor.EMPTY
    val leafDecor = lastKey.rawLeafDecor
    val needsExtraction = leafDecor.prefix?.textOr(DEFAULT_KEY_PATH_DECOR.first)?.contains('\n') ?: false
    return if (needsExtraction) leafDecor to RawDecor.EMPTY else null to leafDecor
}

private fun StandardTable.isFirstElement(): Boolean {
    val array = holder?.arrayOwner as? ArrayOfTables ?: return true
    // Iterating an array of tables copies every element, which per element would print in quadratic time.
    val firstElement = array.values.firstNotNullOfOrNull { it.standardTableOrNull } ?: return true
    return firstElement === this
}

/** Writes one table's header and body; returns whether no table has been written yet. */
private fun visitTable(
    writer: LineWriter,
    table: StandardTable,
    path: List<Key>,
    isArrayOfTables: Boolean,
    firstTable: Boolean,
    // Whether this is the first header printed, below the root's own key/value lines.
    firstHeader: Boolean,
): Boolean {
    val buf = writer.buf
    var first = firstTable
    // A root or an array-of-tables element prints under its own header, whatever it says about being dotted.
    val dotted = table.printsDotted && path.isNotEmpty() && !isArrayOfTables
    if (path.isEmpty()) {
        if (table.visualValues().isNotEmpty()) first = false
    } else if (!dotted && table.printsHeader(isArrayOfTables)) {
        val defaultDecor = if (first) {
            first = false
            "" to DEFAULT_TABLE_DECOR.second
        } else {
            // The blank line above a new header is a line made by hand, so it ends like the line before.
            writer.ending to DEFAULT_TABLE_DECOR.second
        }
        val spelledPath = table.headerSpelling?.resolve(path) ?: path
        val (keyLinesDecor, keyDecor) = leafDecorBeforeBracket(spelledPath)
        // The lines above a key print once, above the first element of the array of tables it names.
        val beforeBracket = keyLinesDecor.takeUnless { isArrayOfTables && !table.isFirstElement() }
        val keyLines = beforeBracket?.prefix?.textOrNull.orEmpty()
        if (table.rawDecor.prefix == null && '#' in keyLines) {
            // The default spacer goes above the key's comments, so they stay right above the header.
            if (keyLines.substringBefore('\n').isNotBlank()) buf.append(defaultDecor.first)
            beforeBracket?.prefixEncode(buf, DEFAULT_KEY_PATH_DECOR.first)
        } else {
            // The header that opened the file is set apart once key/value lines print above it.
            val opened = firstHeader && table.opensFile && table.rawDecor.prefix?.textOrNull == ""
            if (opened) buf.append(writer.ending)
            beforeBracket?.prefixEncode(buf, DEFAULT_KEY_PATH_DECOR.first)
            table.rawDecor.prefixEncode(buf, defaultDecor.first)
        }
        buf.append(if (isArrayOfTables) "[[" else "[")
        val (keyPrefix, keySuffix) = DEFAULT_KEY_PATH_DECOR
        encodeKeyPath(buf, spelledPath, keyPrefix, keySuffix, keyDecor)
        buf.append(if (isArrayOfTables) "]]" else "]")
        table.rawDecor.suffixEncode(buf, defaultDecor.second)
        writer.endLine(table.headerLineEnding)
    } else {
        // A header that does not print leaves its comments where it stood.
        buf.append(table.headerLinesAbove(path.last()))
        val comment = table.headerComment.trimStart()
        if (comment.isNotEmpty()) {
            buf.append(comment)
            writer.endLine(table.headerLineEnding)
        }
    }
    // A dotted table's lines print among its parent's.
    if (!dotted) encodeBodyLines(writer, table)
    return first
}
