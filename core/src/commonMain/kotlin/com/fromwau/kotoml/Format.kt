package com.fromwau.kotoml

import com.fromwau.kern.result.Result
import com.fromwau.kotoml.parser.Event
import com.fromwau.kotoml.parser.EventKind
import com.fromwau.kotoml.parser.StringOutput
import com.fromwau.kotoml.parser.TomlSource
import com.fromwau.kotoml.writer.TomlKeyBuilder

/**
 * Formats what [target] holds in place: a [TomlDocument], a table or array of any kind, or an [Item]. The text of the
 * tree holding it is formatted as a whole, read back, and the formatting under [target] taken over from what was read,
 * so every object stays the one it was.
 */
internal fun formatInPlace(target: Any) {
    val receiver = when (target) {
        is TomlDocument -> target.root
        is Item -> if (target.owner == null) target.container ?: return else target
        else -> target
    }
    val steps = ArrayDeque<Any>()
    var top: Any = receiver
    if (receiver is Item) {
        steps.addFirst(stepTo(receiver))
        top = checkNotNull(receiver.owner)
    }
    while (true) {
        val slot = slotOf(top) ?: break
        steps.addFirst(stepTo(slot))
        top = checkNotNull(slot.owner)
    }
    val tree = PrintedTree.of(top)
    // Formatting only part of the tree takes over the rest as read back unchanged, so both sides number alike.
    if (receiver !== top) tree.graftAll(top, tree.read(tree.text))
    val formatted = tree.read(formatText(tree.text, tree.version))
    if (receiver === top) {
        tree.graftAll(top, formatted)
        return
    }
    val formattedTop = tree.topIn(formatted)
    var node: Any = formattedTop
    for ((index, step) in steps.withIndex()) {
        val slot = slotAt(node, step) ?: return
        node = if (index == steps.lastIndex && receiver is Item) slot else slot.container ?: return
    }
    graftReceiver(receiver, node)
    reindentInside(receiver, node)
}

/**
 * Indents the lines inside the array or inline table [receiver] is or holds from the line holding its opening bracket
 * as that line prints: a line starting outside [receiver] keeps its indentation, while [read], the same place in the
 * formatted tree, has its lines indented from that line as formatted.
 */
private fun reindentInside(
    receiver: Any,
    read: Any,
) {
    val inside = bracketsOf(receiver) ?: return
    val from = lineIndentOf(bracketsOf(read) ?: return)
    val to = lineIndentOf(inside)
    if (from == to) return
    val reindent: (String) -> String = { text ->
        text
            .split('\n')
            .mapIndexed { index, line -> if (index == 0) line else to + line.removePrefix(from) }
            .joinToString("\n")
    }
    reindentContainer(inside, reindent)
}

/** The array or inline table [node] is or holds as its value, or null for anything else. */
private fun bracketsOf(node: Any): Any? = when (node) {
    is Item -> node.valueOrNull?.let { it.arrayOrNull ?: it.inlineTableOrNull }
    is TomlArray, is InlineTable -> node
    else -> null
}

private fun reindentValue(
    value: Value,
    reindent: (String) -> String,
) {
    value.rawDecor.mapSides(reindent)
    value.arrayOrNull?.let { reindentContainer(it, reindent) }
    value.inlineTableOrNull?.let { reindentContainer(it, reindent) }
}

private fun reindentContainer(
    container: Any,
    reindent: (String) -> String,
) {
    when (container) {
        is TomlArray -> {
            container.rawTrailing = container.rawTrailing.mapText(reindent)
            for (slot in container.values) slot.valueOrNull?.let { reindentValue(it, reindent) }
        }
        is InlineTable -> {
            container.rawTrailing = container.rawTrailing.mapText(reindent)
            for (lines in container.items.keptLines) lines.text = reindent(lines.text)
            for (entry in container.items.entries) {
                entry.key.rawLeafDecor.mapSides(reindent)
                entry.item.valueOrNull?.let { reindentValue(it, reindent) }
            }
        }
    }
}

private fun RawDecor.mapSides(transform: (String) -> String) {
    prefix = prefix?.mapText(transform)
    suffix = suffix?.mapText(transform)
}

/** The text of the tree whose top is a document's root, a table stored nowhere, or an array or table under `x`. */
private class PrintedTree(
    val text: String,
    val version: TomlVersion,
    private val underKey: Boolean,
) {
    fun read(text: String): TomlDocument = when (val parsed = TomlDocument.parse(text, version)) {
        is Result.Success -> parsed.value
        is Result.Error -> error("printed TOML reads back: ${parsed.error}")
    }

    /** Takes over the formatting of the whole tree under [top] from [read], its text read back. */
    fun graftAll(
        top: Any,
        read: TomlDocument,
    ) {
        graftTop(top, topIn(read))
        if (top is StandardTable) top.document?.takeIf { it.root === top }?.takeEndsOf(read)
    }

    fun topIn(document: TomlDocument): Any =
        if (underKey) checkNotNull(document.root.items.getEntry(WRAPPING_KEY)?.item?.container) else document.root

    companion object {
        fun of(top: Any): PrintedTree {
            if (top is StandardTable) {
                val document = top.document?.takeIf { it.root === top }
                    ?: TomlDocument(top.deepCopy(), KeptText(), top.lineEnding, null)
                return PrintedTree(document.toString(), top.version, underKey = false)
            }
            val copy = when (top) {
                is TomlArray -> Item(Value.Array(top.deepCopy().also { it.rawDecor.clear() }))
                is InlineTable -> Item(Value.InlineTable(top.deepCopy().also { it.rawDecor.clear() }))
                is ArrayOfTables -> Item(top.deepCopy())
                else -> error("only a table or an array holds slots")
            }
            val document = TomlDocument()
            document.root.items.put(Key(WRAPPING_KEY), copy)
            return PrintedTree(document.toString(), TomlVersion.V1_1, underKey = true)
        }
    }
}

private const val WRAPPING_KEY = "x"

/** Takes over the formatting of everything [top] holds, the top of a tree, from [read], the same tree read back. */
private fun graftTop(
    top: Any,
    read: Any,
) {
    when {
        top is StandardTable && read is StandardTable -> graftTable(top, read, emptyList(), emptyList())
        top is TomlArray && read is TomlArray -> graftArray(top, read)
        top is InlineTable && read is InlineTable -> graftInlineTable(top, read, emptyList())
        top is ArrayOfTables && read is ArrayOfTables -> graftTables(top, read, emptyList())
    }
}

/** Takes over the formatting of what [receiver] holds from [read], the same place in the tree read back. */
private fun graftReceiver(
    receiver: Any,
    read: Any,
) {
    when {
        receiver is Item && read is Item -> {
            val owner = checkNotNull(receiver.owner)
            val entry = receiver.entry?.takeIf { it.isStored }
            val readEntry = read.entry
            when {
                owner is TomlArray || owner is InlineTable -> graftElement(receiver, read)
                entry != null && readEntry != null ->
                    graftEntry(entry, readEntry, keysFrom(owner), linePathOf(owner))
                else -> graftItem(receiver, read, keysFrom(owner), emptyList())
            }
        }
        receiver is StandardTable && read is StandardTable ->
            graftTable(receiver, read, keysFrom(receiver), linePathOf(receiver))
        receiver is TomlArray && read is TomlArray -> graftArray(receiver, read)
        receiver is InlineTable && read is InlineTable -> graftInlineTable(receiver, read, linePathOf(receiver))
        receiver is ArrayOfTables && read is ArrayOfTables -> graftTables(receiver, read, keysFrom(receiver))
    }
}

/**
 * Takes over the formatting of [element], a slot in an array or inline table, from [read]: its key and the spacing
 * around `=`, and what is inside its value. The text before its key or value and after its value goes with the
 * layout of what holds it, whose comma after the last element formatting can move a comment over.
 */
private fun graftElement(
    element: Item,
    read: Item,
) {
    val entry = element.entry?.takeIf { it.isStored }
    val readEntry = read.entry
    if (entry != null && readEntry != null) {
        val key = entry.key
        val readKey = readEntry.key
        graftSpelling(key, readKey)
        graftDecor(key.rawDottedDecor, readKey.rawDottedDecor)
        key.rawLeafDecor.suffix = graftText(key.rawLeafDecor.suffix, readKey.rawLeafDecor.suffix)
        val linePath = linePathOf(checkNotNull(element.owner))
        entry.pathSpelling = readEntry.pathSpelling?.copy()?.also { it.settle(linePath) }
    }
    val value = element.valueOrNull ?: return
    val readValue = read.valueOrNull ?: return
    if (entry != null) value.rawDecor.prefix = graftText(value.rawDecor.prefix, readValue.rawDecor.prefix)
    when {
        value is Value.Array && readValue is Value.Array -> graftArray(value.value, readValue.value)
        value is Value.InlineTable && readValue is Value.InlineTable ->
            graftInlineTable(value.value, readValue.value, linePathOf(value.value))
        else -> Unit
    }
}

/** The keys from the root down to [container], its own key included. */
private fun keysFrom(container: Any): List<Key> {
    val keys = ArrayDeque<Key>()
    var current = container
    while (true) {
        val slot = slotOf(current) ?: break
        slot.entry?.takeIf { it.isStored }?.let { keys.addFirst(it.key) }
        current = slot.owner ?: break
    }
    return keys
}

/** The keys of the dotted tables from the table printing [container]'s lines down to it, its own included. */
private fun linePathOf(container: Any): List<Key> {
    val keys = ArrayDeque<Key>()
    var current = container
    while (current is StandardTable && current.isDotted || current is InlineTable && current.isDotted) {
        val slot = slotOf(current) ?: break
        val entry = slot.entry?.takeIf { it.isStored } ?: break
        keys.addFirst(entry.key)
        current = slot.owner ?: break
    }
    return keys
}

private fun graftTable(
    table: StandardTable,
    read: StandardTable,
    path: List<Key>,
    linePath: List<Key>,
) {
    graftDecor(table.rawDecor, read.rawDecor)
    table.headerLineEnding = read.headerLineEnding
    table.headerSpelling = read.headerSpelling?.copy()?.also { it.settle(path) }
    table.opensFile = read.opensFile
    if (table.isDotted == read.isDotted) table.isImplicit = read.isImplicit
    table.placeAt(read.position, read.positionFromText)
    table.headEndsInRemovedTable = read.headEndsInRemovedTable
    table.keptHeaderLines.clear()
    read.keptHeaderLines.mapTo(table.keptHeaderLines) { it.copy() }
    graftPairs(table.items, read.items, path, if (table.isDotted) linePath else emptyList())
}

private fun graftTables(
    tables: ArrayOfTables,
    read: ArrayOfTables,
    path: List<Key>,
) {
    tables.keptHeaderLines.clear()
    read.keptHeaderLines.mapTo(tables.keptHeaderLines) { it.copy() }
    if (tables.values.size != read.values.size) return
    for ((slot, readSlot) in tables.values.zip(read.values)) graftItem(slot, readSlot, path, emptyList())
}

private fun graftPairs(
    pairs: KeyValuePairs,
    read: KeyValuePairs,
    path: List<Key>,
    linePath: List<Key>,
) {
    for (readEntry in read.entries) {
        val entry = pairs.getEntry(readEntry.key.name) ?: continue
        graftEntry(entry, readEntry, path, linePath)
    }
    pairs.keptLines.clear()
    read.keptLines.mapTo(pairs.keptLines) { it.copy() }
}

/** Takes over an entry's formatting; [path] leads to the table holding it, [linePath] to it from its lines' table. */
private fun graftEntry(
    entry: KeyValue,
    read: KeyValue,
    path: List<Key>,
    linePath: List<Key>,
) {
    graftKey(entry.key, read.key)
    entry.lineEnding = read.lineEnding
    entry.order = read.order
    entry.pathSpelling = read.pathSpelling?.copy()?.also { it.settle(linePath) }
    graftItem(entry.item, read.item, path + entry.key, linePath + entry.key)
}

private fun graftItem(
    slot: Item,
    read: Item,
    path: List<Key>,
    linePath: List<Key>,
) {
    val readTable = read.standardTableOrNull
    // Reading its lines gives a dotted inline table on a table's lines as a dotted table.
    val table = slot.standardTableOrNull ?: readTable?.let { slot.takeReadDottedTable() }
    val tables = slot.arrayOfTablesOrNull
    val value = slot.valueOrNull
    val readTables = read.arrayOfTablesOrNull
    val readValue = read.valueOrNull
    when {
        table != null && readTable != null -> graftTable(table, readTable, path, linePath)
        tables != null && readTables != null -> graftTables(tables, readTables, path)
        value != null && readValue != null -> graftValue(value, readValue, linePath)
        // An empty dotted table prints `{}` and an empty array of tables `[]`, which read back as values.
        value == null && readValue != null -> slot.takeReadValue(readValue.deepCopy())
    }
}

private fun graftValue(
    value: Value,
    read: Value,
    linePath: List<Key>,
) {
    graftDecor(value.rawDecor, read.rawDecor)
    when {
        value is Value.Array && read is Value.Array -> graftArray(value.value, read.value)
        value is Value.InlineTable && read is Value.InlineTable ->
            graftInlineTable(value.value, read.value, if (value.value.isDotted) linePath else emptyList())
        else -> Unit
    }
}

private fun graftArray(
    array: TomlArray,
    read: TomlArray,
) {
    array.rawTrailing = graftText(array.rawTrailing, read.rawTrailing) ?: KeptText()
    array.trailingComma = read.trailingComma
    if (array.values.size != read.values.size) return
    for ((slot, readSlot) in array.values.zip(read.values)) {
        val value = slot.valueOrNull ?: continue
        val readValue = readSlot.valueOrNull ?: continue
        graftValue(value, readValue, emptyList())
    }
}

private fun graftInlineTable(
    table: InlineTable,
    read: InlineTable,
    linePath: List<Key>,
) {
    table.rawTrailing = graftText(table.rawTrailing, read.rawTrailing) ?: KeptText()
    table.trailingComma = read.trailingComma
    graftPairs(table.items, read.items, emptyList(), linePath)
}

private fun graftKey(
    key: Key,
    read: Key,
) {
    graftSpelling(key, read)
    graftDecor(key.rawLeafDecor, read.rawLeafDecor)
    graftDecor(key.rawDottedDecor, read.rawDottedDecor)
}

/** Takes [read]'s spelling, unless [key] has none of its own and [read]'s is the default one it prints already. */
private fun graftSpelling(
    key: Key,
    read: Key,
) {
    val printsAlready = key.written == null && read.spelling == key.defaultSpelling().raw.textOrNull
    if (!printsAlready) key.written = read.written
}

private fun graftDecor(
    decor: RawDecor,
    read: RawDecor,
) {
    decor.prefix = graftText(decor.prefix, read.prefix)
    decor.suffix = graftText(decor.suffix, read.suffix)
}

/** The text [read] holds, given by a caller where [text] was: a comment a caller set travels with copies. */
private fun graftText(
    text: KeptText?,
    read: KeptText?,
): KeptText? {
    if (read == null) return null
    val given = text != null && !text.isRead && !text.textOrNull.isNullOrEmpty()
    return if (given) KeptText.from(read.textOrNull.orEmpty()) else read
}

/**
 * [text], a whole document that reads without errors, formatted: keys bare when they can be, one space around `=`
 * and after a comma or `#`, one blank line at most and one before each header, no indentation but the elements of an
 * array or inline table across lines, and the comma after the last of those that kotoml prints. Spellings of values,
 * comments, line endings and layouts stay.
 */
internal fun formatText(
    text: String,
    version: TomlVersion,
): String = TextFormatter(text, version).format()

private class TextFormatter(
    private val text: String,
    private val version: TomlVersion,
) {
    private val source = TomlSource(text)
    private val events = documentEvents(source).filter {
        it.kind != EventKind.Whitespace && it.kind != EventKind.Error
    }
    private val multiline = multilineBrackets(events)
    private val out = StringBuilder()
    private var lineBreak = events.firstOrNull { it.kind == EventKind.Newline }?.let(::textOf) ?: LINE_BREAK

    // The last event printed on the line being written, or null at the start of a line.
    private var previous: EventKind? = null

    // The indentation of the line being written.
    private var lineIndent = ""
    private var printedAny = false
    private var lineStartsWithComment = false
    private var lastLineWasComment = false
    private var blankLines = 0
    private var blankLineBreak: String? = null
    private val brackets = ArrayDeque<Bracket>()

    private class Bracket(
        val isArray: Boolean,
        val multiline: Boolean,
        // The indentation of the line holding the opening bracket.
        val indent: String,
    ) {
        var onOpeningLine = true
        var hasLine = false
        var lastValueEnd = -1
        var commaAfterLast = false
        var commaAt = -1
    }

    fun format(): String {
        if (text.startsWith(BYTE_ORDER_MARK)) out.append(BYTE_ORDER_MARK)
        for ((index, event) in events.withIndex()) {
            when (event.kind) {
                EventKind.Newline -> newline(textOf(event))
                EventKind.ArrayClose, EventKind.InlineTableClose -> close(event, index)
                else -> {
                    start(event.kind, index)
                    out.append(textToPrint(event))
                    previous = event.kind
                    afterPrinting(event, index)
                }
            }
        }
        return out.toString()
    }

    private fun textOf(event: Event): String = source.bytes.decodeToString(event.span.start, event.span.end)

    private fun textToPrint(event: Event): String {
        val raw = textOf(event)
        return when (event.kind) {
            EventKind.Comment -> formatComment(raw)
            EventKind.SimpleKey -> if (event.encoding == null) raw else bareKey(event) ?: raw
            else -> raw
        }
    }

    private fun bareKey(event: Event): String? {
        val decoded = StringOutput()
        source.get(event)?.decodeKey(decoded) { }
        val name = decoded.toString()
        return name.takeIf { TomlKeyBuilder(it).asUnquoted() != null }
    }

    private fun afterPrinting(
        event: Event,
        index: Int,
    ) {
        val bracket = brackets.lastOrNull()
        when (event.kind) {
            EventKind.ArrayOpen, EventKind.InlineTableOpen -> {
                brackets.addLast(Bracket(event.kind == EventKind.ArrayOpen, multiline.getValue(index), lineIndent))
            }
            EventKind.Scalar -> bracket?.valueEnded()
            EventKind.ValueSep -> if (bracket != null) {
                bracket.commaAfterLast = true
                bracket.commaAt = out.length - 1
            }
            else -> Unit
        }
    }

    private fun Bracket.valueEnded() {
        lastValueEnd = out.length
        commaAfterLast = false
    }

    private fun newline(ending: String) {
        if (previous == null) {
            if (blankLines++ == 0) blankLineBreak = ending
            return
        }
        out.append(ending)
        lineBreak = ending
        endLine()
    }

    private fun breakLine() {
        out.append(lineBreak)
        endLine()
    }

    private fun endLine() {
        previous = null
        lastLineWasComment = lineStartsWithComment && brackets.isEmpty()
        for (bracket in brackets) bracket.onOpeningLine = false
    }

    /** Prints what goes before [kind]: a line break, blank lines and indentation, or the space on its line. */
    private fun start(
        kind: EventKind,
        index: Int,
        closing: Bracket? = null,
    ) {
        val bracket = closing ?: brackets.lastOrNull()
        val opensLine = bracket != null && bracket.multiline && bracket.onOpeningLine && previous != null &&
            closing == null && kind != EventKind.Comment
        if (opensLine) breakLine()
        val previous = previous
        if (previous != null) {
            out.append(spaceBetween(previous, kind))
            return
        }
        val blank = when {
            !printedAny -> 0
            bracket != null -> if (!bracket.hasLine || closing != null) 0 else minOf(blankLines, 1)
            kind == EventKind.StdTableOpen || kind == EventKind.ArrayTableOpen ->
                if (lastLineWasComment && blankLines == 0) 0 else 1
            kind == EventKind.Comment && commentsOpenHeader(index) && (blankLines > 0 || !lastLineWasComment) -> 1
            else -> minOf(blankLines, 1)
        }
        repeat(blank) { out.append(blankLineBreak ?: lineBreak) }
        blankLines = 0
        blankLineBreak = null
        lineIndent = when {
            bracket == null -> ""
            closing != null -> bracket.indent
            else -> bracket.indent + INDENT
        }
        out.append(lineIndent)
        if (bracket != null && closing == null) bracket.hasLine = true
        lineStartsWithComment = kind == EventKind.Comment
        printedAny = true
    }

    // Whether the comment lines starting at [index] lead to a header with no blank line between.
    private fun commentsOpenHeader(index: Int): Boolean {
        var lineEnded = false
        for (event in events.subList(index + 1, events.size)) {
            when (event.kind) {
                EventKind.Newline -> if (lineEnded) return false else lineEnded = true
                EventKind.Comment -> lineEnded = false
                EventKind.StdTableOpen, EventKind.ArrayTableOpen -> return true
                else -> return false
            }
        }
        return false
    }

    private fun close(
        event: Event,
        index: Int,
    ) {
        val bracket = brackets.removeLast()
        if (bracket.lastValueEnd >= 0) {
            // TOML 1.0 reads no comma after the last entry of an inline table.
            val wantsComma = bracket.isArray || version == TomlVersion.V1_1
            when {
                bracket.multiline && wantsComma && !bracket.commaAfterLast -> out.insert(bracket.lastValueEnd, ',')
                !bracket.multiline && bracket.commaAfterLast -> out.deleteAt(bracket.commaAt)
            }
        }
        if (bracket.multiline && previous != null) breakLine()
        start(event.kind, index, closing = bracket)
        out.append(textOf(event))
        previous = event.kind
        brackets.lastOrNull()?.valueEnded()
    }
}

/** Whether each array and inline table, by the index of its opening event, has a line break of its own inside. */
private fun multilineBrackets(events: List<Event>): Map<Int, Boolean> {
    val result = HashMap<Int, Boolean>()
    val open = ArrayDeque<Int>()
    for ((index, event) in events.withIndex()) {
        when (event.kind) {
            EventKind.ArrayOpen, EventKind.InlineTableOpen -> {
                open.addLast(index)
                result[index] = false
            }
            EventKind.ArrayClose, EventKind.InlineTableClose -> open.removeLastOrNull()
            EventKind.Newline -> open.lastOrNull()?.let { result[it] = true }
            else -> Unit
        }
    }
    return result
}

private fun spaceBetween(
    previous: EventKind,
    kind: EventKind,
): String = when {
    kind == EventKind.ValueSep -> ""
    kind == EventKind.KeySep || previous == EventKind.KeySep -> ""
    kind == EventKind.KeyValSep || previous == EventKind.KeyValSep -> " "
    kind == EventKind.Comment -> " "
    kind == EventKind.StdTableClose || kind == EventKind.ArrayTableClose -> ""
    previous == EventKind.StdTableOpen || previous == EventKind.ArrayTableOpen -> ""
    kind == EventKind.ArrayClose -> ""
    kind == EventKind.InlineTableClose -> if (previous == EventKind.InlineTableOpen) "" else " "
    previous == EventKind.ArrayOpen -> ""
    else -> " "
}

private const val BYTE_ORDER_MARK = '\uFEFF'

/** A comment with one space after its `#`s, unless a space or tab or nothing follows them, and none at its end. */
private fun formatComment(raw: String): String {
    val comment = raw.trimEnd(' ', '\t')
    val marks = comment.takeWhile { it == '#' }.length
    val first = comment.getOrNull(marks)
    if (first == null || first == ' ' || first == '\t') return comment
    return comment.substring(0, marks) + " " + comment.substring(marks)
}
