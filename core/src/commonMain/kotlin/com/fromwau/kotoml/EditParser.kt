package com.fromwau.kotoml

import com.fromwau.kotoml.parser.ErrorSink
import com.fromwau.kotoml.parser.Event
import com.fromwau.kotoml.parser.EventReceiver
import com.fromwau.kotoml.parser.EventKind
import com.fromwau.kotoml.TomlParseError.Reason
import com.fromwau.kotoml.parser.ParseError
import com.fromwau.kotoml.parser.RecursionGuard
import com.fromwau.kotoml.parser.TomlSource
import com.fromwau.kotoml.parser.StringOutput
import com.fromwau.kotoml.parser.Token
import com.fromwau.kotoml.parser.ValidateWhitespace
import com.fromwau.kotoml.parser.eventReceiver
import com.fromwau.kotoml.parser.parseDocument as parseDocumentEvents
import com.fromwau.kotoml.parser.parseKey as parseKeyEvents
import com.fromwau.kotoml.parser.parseSimpleKey as parseSimpleKeyEvents
import com.fromwau.kotoml.parser.parseValue as parseValueEvents

/** How deeply arrays, inline tables and dotted keys may nest, upstream's `LIMIT`. */
internal const val LIMIT: Int = 80

private fun collectEvents(
    source: TomlSource,
    errors: TomlSink,
    parse: (List<Token>, EventReceiver, ErrorSink) -> Unit,
): List<Event> {
    val tokens = source.lex().intoVec()
    val events = ArrayList<Event>(tokens.size)
    val receiver = RecursionGuard(ValidateWhitespace(eventReceiver { events += it }, source), LIMIT.toUInt())
    parse(tokens, receiver, errors)
    return events
}

/** What one parse shares: its input, where errors go, the TOML version, and the order lines are read in. */
internal class ParseContext(val source: TomlSource, val errors: TomlSink, val version: TomlVersion) {
    private var nextOrder = 0L

    /** [errors] for decoding strings and keys; the decoder lists TOML 1.1's escapes, so 1.0 swaps in its own. */
    val decodeErrors: ErrorSink = if (version == TomlVersion.V1_0) {
        ErrorSink { error ->
            val escapes = error.reason == ParseErrorReason.MissingEscapedValue
            errors.reportError(if (escapes) error.copy(expected = EXPECTED_ESCAPES_V1_0) else error)
        }
    } else {
        errors
    }

    fun nextOrder(): Long = nextOrder++

    fun text(span: Span): String = source.get(span)?.asStr().orEmpty()
}

internal fun parseDocumentModel(source: TomlSource, errors: TomlSink, version: TomlVersion): TomlDocument {
    val events = collectEvents(source, errors, ::parseDocumentEvents)
    val context = ParseContext(source, errors, version)
    val lineEnding = events.firstOrNull { it.kind() == EventKind.Newline }?.let { context.text(it.span()) } ?: "\n"
    return DocumentBuilder(EventStream(events), context).document(lineEnding)
}

internal fun parseSimpleKeyItem(source: TomlSource, errors: TomlSink, version: TomlVersion): Key {
    val events = collectEvents(source, errors, ::parseSimpleKeyEvents)
    val context = ParseContext(source, errors, version)
    val event = events.firstOrNull { it.kind() == EventKind.SimpleKey }
    if (event != null) {
        val (raw, key) = onSimpleKey(event, context)
        return Key(key).withReprUnchecked(Repr.newUnchecked(raw))
    }
    val raw = RawString.withSpan(0, source.bytes.size)
    return Key(source.input()).withReprUnchecked(Repr.newUnchecked(raw))
}

internal fun parseKeyPath(source: TomlSource, errors: TomlSink, version: TomlVersion): List<Key> {
    val events = collectEvents(source, errors, ::parseKeyEvents)
    val context = ParseContext(source, errors, version)
    val input = EventStream(events)
    var prefix: RawString? = null
    var path: List<Key>? = null
    var key: Key? = null
    var suffix: RawString? = null
    while (true) {
        val event = input.nextToken() ?: break
        if (event.kind() == EventKind.Whitespace) {
            val raw = RawString.withSpan(event.span())
            if (prefix == null) {
                prefix = raw
            } else if (suffix == null) {
                suffix = raw
            }
        } else {
            val (localPath, localKey) = onKey(event, input, context)
            path = localPath
            key = localKey
        }
    }
    val last = key ?: return emptyList()
    prefix?.let { last.rawLeafDecor.prefix = it }
    suffix?.let { last.rawLeafDecor.suffix = it }
    return (path ?: emptyList()) + last
}

internal fun parseValueItem(source: TomlSource, errors: TomlSink, version: TomlVersion): Value {
    val events = collectEvents(source, errors, ::parseValueEvents)
    return value(EventStream(events), ParseContext(source, errors, version))
}

/** The events of a parse, consumed front to back. */
internal class EventStream(private val events: List<Event>) {
    private var position = 0

    fun nextToken(): Event? = if (position < events.size) events[position++] else null

    fun first(): Event? = events.getOrNull(position)

    fun get(offset: Int): Event? = events.getOrNull(position + offset)
}

private class TableHeader(val path: List<Key>, val key: Key?, val span: Span, val isArray: Boolean)

/** Builds a [TomlDocument] from the events of a whole document, upstream's `document` and its `State`. */
private class DocumentBuilder(private val input: EventStream, private val context: ParseContext) {
    private val source = context.source
    private val errors = context.errors
    private var root = Table()
    private var currentTable = Table()
    private var currentTrailing: Span? = null
    private var currentHeader: TableHeader? = null
    private var currentPosition = 0L

    fun document(lineEnding: String): TomlDocument {
        while (true) {
            val event = input.nextToken() ?: break
            when (event.kind()) {
                EventKind.InlineTableOpen,
                EventKind.InlineTableClose,
                EventKind.ArrayOpen,
                EventKind.ArrayClose,
                EventKind.Scalar,
                EventKind.ValueSep,
                EventKind.Error,
                EventKind.KeySep,
                EventKind.KeyValSep,
                EventKind.StdTableClose,
                EventKind.ArrayTableClose,
                -> continue
                EventKind.StdTableOpen, EventKind.ArrayTableOpen -> {
                    finishTable()

                    val prefix = takeTrailing()
                    val header = onTable(event)
                    val (suffixSpan, newline) = wsCommentNewline(input)
                    val suffix = suffixSpan?.let { RawString.withSpan(it) } ?: RawString()
                    startTable(header, RawDecor(prefix, suffix))
                    currentTable.headerLineEnding = newline?.let { context.text(it.span()) }
                }
                EventKind.SimpleKey -> {
                    val keyPrefix = takeTrailing()
                    val (path, parsedKey) = onKey(event, input, context)
                    val key = parsedKey ?: break
                    var next = input.nextToken() ?: break
                    var keySuffix: Event? = null
                    if (next.kind() == EventKind.Whitespace) {
                        keySuffix = next
                        next = input.nextToken() ?: break
                    }
                    if (next.kind() != EventKind.KeyValSep) break
                    key.rawLeafDecor.prefix = keyPrefix
                    key.rawLeafDecor.suffix = keySuffix?.let { RawString.withSpan(it.span()) } ?: RawString()

                    val valuePrefix = if (input.first()?.kind() == EventKind.Whitespace) {
                        input.nextToken()?.let { RawString.withSpan(it.span()) }
                    } else {
                        null
                    } ?: RawString()
                    val value = value(input, context)
                    val (suffixSpan, newline) = wsCommentNewline(input)
                    val valueSuffix = suffixSpan?.let { RawString.withSpan(it) } ?: RawString()
                    val decor = value.rawDecor()
                    decor.prefix = valuePrefix
                    decor.suffix = valueSuffix

                    val entry = captureKeyValue(path, key, value)
                    entry?.lineEnding = newline?.let { context.text(it.span()) }
                }
                EventKind.Whitespace, EventKind.Comment, EventKind.Newline -> captureTrailing(event)
            }
        }

        finishTable()

        val text = source.input()
        val input = SourceText(text)
        root.despan(input)
        return TomlDocument(root, takeTrailing().despan(input), lineEnding, text)
    }

    private fun onTable(openEvent: Event): TableHeader {
        val isArray = openEvent.kind() == EventKind.ArrayTableOpen
        var currentPath: List<Key>? = null
        var currentKey: Key? = null
        var currentSpan = openEvent.span()
        var currentPrefix: Span? = null
        var currentSuffix: Span? = null

        while (true) {
            val event = input.nextToken() ?: break
            when (event.kind()) {
                EventKind.ArrayTableClose, EventKind.StdTableClose -> {
                    currentSpan = currentSpan.append(event.span())
                    break
                }
                EventKind.SimpleKey -> {
                    if (currentPrefix == null) currentPrefix = event.span().before()
                    val (path, key) = onKey(event, input, context)
                    currentPath = path
                    currentKey = key
                    if (currentSuffix == null) currentSuffix = event.span().after()
                }
                EventKind.Whitespace -> if (currentKey != null) {
                    currentSuffix = event.span()
                } else {
                    currentPrefix = event.span()
                }
                else -> continue
            }
        }

        // The event parser reports a key, if only an empty one, inside every header.
        val prefix = currentPrefix ?: openEvent.span().after()
        val suffix = currentSuffix ?: openEvent.span().after()
        currentKey?.rawLeafDecor?.assign(RawDecor(RawString.withSpan(prefix), RawString.withSpan(suffix)))

        return TableHeader(currentPath ?: emptyList(), currentKey, currentSpan, isArray)
    }

    private fun captureTrailing(event: Event) {
        val trailing = currentTrailing
        currentTrailing = trailing?.append(event.span()) ?: event.span()
    }

    /** Adds a key/value line to the current table; returns its entry, unless the line was refused. */
    private fun captureKeyValue(path: List<Key>, key: Key, value: Value): KeyValue? {
        val dotted = path.isNotEmpty()
        val shared = mutableListOf<Key>()
        val parentTable = descendPath(currentTable, path, dotted, errors, shared) ?: return null
        // "Likewise, using dotted keys to redefine tables already defined in [table] form is not allowed"
        val mixedTableTypes = dotted && !parentTable.isImplicit
        if (mixedTableTypes) {
            errors.report(Reason.DuplicateKey(key.name), unexpected = keySpan(key))
            return null
        }
        val existing = parentTable.items.getEntry(key.name)
        if (existing == null) {
            parentTable.items.insert(key, Item(value))
            val entry = parentTable.items.getEntry(key.name)
            entry?.order = context.nextOrder()
            if (dotted) entry?.pathSpelling = PathSpelling.of(path, shared)
            return entry
        } else {
            // "Since tables cannot be defined more than once, redefining such tables using a [table] header is
            // not allowed"
            errors.report(
                Reason.DuplicateKey(key.name),
                unexpected = keySpan(key),
                context = existing.key.span(),
            )
            return null
        }
    }

    private fun finishTable() {
        val previousTable = currentTable
        currentTable = Table()
        val header = currentHeader
        if (header == null) {
            previousTable.span = Span.DEFAULT
            root = previousTable
            return
        }
        currentHeader = null
        val key = header.key ?: return
        previousTable.span = header.span

        val shared = mutableListOf<Key>()
        val parentTable = descendPath(root, header.path, false, errors, shared) ?: return
        if (header.path.isNotEmpty()) previousTable.headerSpelling = PathSpelling.of(header.path, shared)
        if (header.isArray) {
            val keySpan = keySpan(key)
            val entry = parentTable.items.getEntry(key.name)
                ?: KeyValue(key, Item(ArrayOfTables())).also { parentTable.items.insert(key, it.item) }
            val array = entry.item.arrayOfTablesOrNull
            if (array == null) {
                errors.report(
                    Reason.DuplicateKey(key.name),
                    unexpected = keySpan,
                    context = entry.item.span(),
                )
                return
            }
            array.add(previousTable)
            val first = array.values.firstOrNull()?.tableOrNull?.span
            val last = array.values.lastOrNull()?.tableOrNull?.span
            array.span = if (first != null && last != null) Span(first.start, last.end) else null
        } else {
            parentTable.items.insert(key, Item(previousTable))
        }
    }

    private fun startTable(header: TableHeader, decor: RawDecor) {
        if (!header.isArray) {
            // Looking the table up here points a duplicate-key error at the right line, and keeps the child
            // tables an implicit table already has.
            val parentTable = descendPath(root, header.path, false, errors)
            val key = header.key
            val old = if (key != null) parentTable?.items?.shiftRemove(key.name) else null
            if (old != null && key != null) {
                val oldTable = old.item.tableOrNull
                if (oldTable != null && oldTable.isImplicit && !oldTable.isDotted) {
                    currentTable = oldTable
                } else {
                    errors.report(
                        Reason.DuplicateKey(key.name),
                        unexpected = keySpan(key),
                        context = old.key.span(),
                    )
                    if (oldTable != null) currentTable = oldTable
                }
            }
        }

        currentPosition += 1
        currentTable.rawDecor.assign(decor)
        currentTable.isImplicit = false
        currentTable.isDotted = false
        currentTable.position = currentPosition
        currentTable.span = header.span
        currentHeader = header
    }

    private fun takeTrailing(): RawString {
        val trailing = currentTrailing ?: return RawString()
        currentTrailing = null
        return RawString.withSpan(trailing)
    }
}

/**
 * Walks [path] down from [start], making the implicit tables it names, and returns the table it ends at. The keys
 * the walk passes through, as the tables hold them, are added to [shared].
 */
private fun descendPath(
    start: Table,
    path: List<Key>,
    dotted: Boolean,
    errors: TomlSink,
    shared: MutableList<Key> = mutableListOf(),
): Table? {
    var table = start
    for (key in path) {
        val existing = table.items.getEntry(key.name)
        if (existing == null) {
            val newTable = Table()
            newTable.span = key.span()
            newTable.isImplicit = true
            newTable.isDotted = dotted
            val held = key.copy()
            table.items.insert(held, Item(newTable))
            shared += held
            table = newTable
            continue
        }
        shared += existing.key
        val item = existing.item
        val array = item.arrayOfTablesOrNull
        val child = item.tableOrNull
        val value = item.valueOrNull
        table = when {
            array != null -> array.values.lastOrNull()?.tableOrNull ?: return null
            child != null -> {
                if (dotted && child.isImplicit) {
                    // A dotted key extends an implicit table, so it becomes part of the dotted key.
                    child.isDotted = true
                }
                // Tables cannot be defined more than once, and dotted keys cannot extend a table defined
                // with a header.
                if (dotted && !child.isImplicit) {
                    errors.report(Reason.DuplicateKey(key.name), unexpected = keySpan(key))
                    return null
                }
                child
            }
            value != null -> {
                errors.report(
                    Reason.DottedKeyExtendsValue(key.name, value.type),
                    unexpected = keySpan(key),
                    context = value.span(),
                )
                return null
            }
            else -> return null
        }
    }
    return table
}

private fun keySpan(key: Key): Span = key.repr?.span() ?: Span.DEFAULT

/** The whitespace and comment to the end of the line, and the newline ending it, if the input did not end first. */
private fun wsCommentNewline(input: EventStream): Pair<Span?, Event?> {
    var currentSpan: Span? = null
    while (true) {
        val event = input.nextToken() ?: break
        when (event.kind()) {
            EventKind.Whitespace, EventKind.Comment -> {
                val span = currentSpan
                currentSpan = span?.append(event.span()) ?: event.span()
            }
            EventKind.Newline -> return currentSpan to event
            else -> {}
        }
    }
    return currentSpan to null
}

/** `key = simple-key / dotted-key`: the path before the last key, and the last key. */
internal fun onKey(keyEvent: Event, input: EventStream, context: ParseContext): Pair<List<Key>, Key?> {
    val errors = context.errors
    val resultPath = mutableListOf<Key>()
    var resultKey: Key? = null

    var currentPrefix: Span? = null
    var currentKey: Event? = keyEvent
    var currentSuffix: Span? = null

    fun closeKey() {
        val key = currentKey ?: return
        currentKey = null
        val prefixSpan = currentPrefix ?: key.span().before()
        currentPrefix = null
        val suffixSpan = currentSuffix ?: key.span().after()
        currentSuffix = null

        val raw = context.source.get(key)
        val decoded = StringOutput()
        raw?.decodeKey(decoded, context.decodeErrors)
        checkEscapes(key, context)

        val parsed = Key(decoded.toString())
            .withReprUnchecked(Repr.newUnchecked(RawString.withSpan(key.span())))
        parsed.rawDottedDecor.assign(RawDecor(RawString.withSpan(prefixSpan), RawString.withSpan(suffixSpan)))
        resultKey?.let { resultPath.add(it) }
        resultKey = parsed
    }

    if (moreKey(input)) {
        while (true) {
            val event = input.nextToken() ?: break
            when (event.kind()) {
                EventKind.SimpleKey -> {
                    currentKey = event
                    if (!moreKey(input)) break
                }
                EventKind.Whitespace -> if (currentKey != null) {
                    currentSuffix = event.span()
                } else {
                    currentPrefix = event.span()
                }
                EventKind.KeySep -> closeKey()
                else -> continue
            }
        }
    }

    closeKey()

    if (LIMIT <= resultPath.size) {
        errors.report(Reason.KeyTooDeep)
        return emptyList<Key>() to null
    }

    return resultPath to resultKey
}

private fun moreKey(input: EventStream): Boolean {
    val first = input.get(0)?.kind()
    val second = input.get(1)?.kind()
    return first == EventKind.KeySep || (first == EventKind.Whitespace && second == EventKind.KeySep)
}

/** `simple-key = quoted-key / unquoted-key`: the key's raw span and its decoded text. */
private fun onSimpleKey(event: Event, context: ParseContext): Pair<RawString, String> {
    val decoded = StringOutput()
    context.source.get(event)?.decodeKey(decoded, context.decodeErrors)
    checkEscapes(event, context)
    return RawString.withSpan(event.span()) to decoded.toString()
}

/** `val = string / boolean / array / inline-table / date-time / float / integer` */
private fun value(input: EventStream, context: ParseContext): Value {
    val event = input.nextToken()
    if (event != null) {
        when (event.kind()) {
            EventKind.InlineTableOpen -> return onInlineTable(event, input, context)
            EventKind.ArrayOpen -> return onArray(event, input, context)
            EventKind.Scalar -> return onScalar(event, context)
            else -> {}
        }
    }

    val formatted = Formatted.of(0L)
    formatted.repr = Repr.newUnchecked(RawString.withSpan(0, 0))
    return Value.Integer(formatted)
}

private fun onScalar(event: Event, context: ParseContext): Value {
    val errors = context.errors
    val valueRaw = RawString.withSpan(event.span())
    val decoded = StringOutput()
    val kind = context.source.get(event)?.decodeScalar(decoded, context.decodeErrors) ?: ScalarKind.String
    val text = decoded.toString()
    return when (kind) {
        ScalarKind.String -> {
            checkEscapes(event, context)
            Value.String(Formatted.of(text).withRepr(valueRaw))
        }
        is ScalarKind.Boolean -> Value.Boolean(Formatted.of(kind.value).withRepr(valueRaw))
        ScalarKind.DateTime -> {
            val datetime = when (val parsed = Datetime.parse(text)) {
                is com.fromwau.kern.result.Result.Success -> parsed.value
                is com.fromwau.kern.result.Result.Error -> {
                    errors.report(Reason.InvalidDatetime(parsed.error), unexpected = event.span())
                    Datetime(date = null, time = null, offset = null)
                }
            }
            if (context.version == TomlVersion.V1_0 && datetime.time != null && datetime.time.second == null) {
                errors.report(Reason.NotInToml10(Toml11Feature.TimeWithoutSeconds), unexpected = event.span())
            }
            Value.Datetime(Formatted.of(datetime).withRepr(valueRaw))
        }
        ScalarKind.Float -> {
            val parsed = parseRustF64(text)
            val float = if (parsed == null) {
                errors.reportError(ParseError(ParseErrorReason.InvalidScalar(kind), unexpected = event.span()))
                Double.NaN
            } else {
                val unsigned = if (text.startsWith('+') || text.startsWith('-')) text.substring(1) else text
                if (parsed.isInfinite() && !unsigned.all { it in 'a'..'z' || it in 'A'..'Z' }) {
                    errors.report(Reason.FloatOverflow, unexpected = event.span())
                }
                parsed
            }
            Value.Float(Formatted.of(float).withRepr(valueRaw))
        }
        is ScalarKind.Integer -> {
            // The decoder validated the digits there are, so a failure here is no digits or overflow.
            val integer = parseRustI64(text, kind.radix.value()) ?: run {
                val digits = text.removePrefix("-").removePrefix("+")
                val reason = if (digits.isEmpty()) Reason.MissingDigits(kind.radix) else Reason.IntegerOverflow
                errors.report(reason, unexpected = event.span())
                Long.MAX_VALUE
            }
            Value.Integer(Formatted.of(integer).withRepr(valueRaw))
        }
    }
}

private val EXPECTED_ESCAPES_V1_0 = listOf(
    Expected.Literal("b"),
    Expected.Literal("f"),
    Expected.Literal("n"),
    Expected.Literal("r"),
    Expected.Literal("t"),
    Expected.Literal("\\"),
    Expected.Literal("\""),
    Expected.Literal("u"),
    Expected.Literal("U"),
)

/** Refuses the `\x` and `\e` escapes of a basic string or key when reading TOML 1.0, which has neither. */
private fun checkEscapes(event: Event, context: ParseContext) {
    if (context.version != TomlVersion.V1_0) return
    val encoding = event.encoding()
    if (encoding != Encoding.BasicString &&
        encoding != Encoding.MlBasicString
    ) {
        return
    }
    val bytes = context.source.bytes
    val span = event.span()
    var index = span.start
    while (index < span.end - 1) {
        if (bytes[index] != '\\'.code.toByte()) {
            index++
            continue
        }
        val escape = bytes[index + 1].toInt().toChar()
        if (escape == 'x' || escape == 'e') {
            context.errors.reportError(
                ParseError(
                    ParseErrorReason.MissingEscapedValue,
                    context = span,
                    expected = EXPECTED_ESCAPES_V1_0,
                    unexpected = Span(index + 1, index + 1),
                ),
            )
            return
        }
        index += 2
    }
}

private fun <T : Any> Formatted<T>.withRepr(raw: RawString): Formatted<T> {
    repr = Repr.newUnchecked(raw)
    return this
}

/** `array = array-open array-values array-close` */
private fun onArray(openEvent: Event, input: EventStream, context: ParseContext): Value {
    val result = TomlArray()
    var closeSpan = openEvent.span()

    var currentPrefix: Span? = null
    var currentValue: Value? = null
    var trailingStart: Int? = openEvent.span().end
    var currentSuffix: Span? = null

    fun captureValue(event: Event, value: Value) {
        trailingStart = null
        if (currentPrefix == null) currentPrefix = event.span().before()
        currentValue = value
    }

    fun finishValue(event: Event) {
        val value = currentValue ?: return
        currentValue = null
        val prefix = currentPrefix ?: event.span().before()
        currentPrefix = null
        val suffix = currentSuffix ?: event.span().before()
        currentSuffix = null
        val decor = value.rawDecor()
        decor.prefix = RawString.withSpan(prefix)
        decor.suffix = RawString.withSpan(suffix)
        result.add(value)
    }

    while (true) {
        val event = input.nextToken() ?: break
        closeSpan = event.span()
        when (event.kind()) {
            EventKind.StdTableOpen,
            EventKind.ArrayTableOpen,
            EventKind.InlineTableClose,
            EventKind.SimpleKey,
            EventKind.KeySep,
            EventKind.KeyValSep,
            EventKind.StdTableClose,
            EventKind.ArrayTableClose,
            -> break
            EventKind.Error -> continue
            EventKind.InlineTableOpen -> captureValue(event, onInlineTable(event, input, context))
            EventKind.ArrayOpen -> captureValue(event, onArray(event, input, context))
            EventKind.Scalar -> captureValue(event, onScalar(event, context))
            EventKind.ValueSep -> {
                finishValue(event)
                trailingStart = event.span().end
            }
            EventKind.Whitespace, EventKind.Comment, EventKind.Newline -> {
                if (currentValue == null) {
                    currentPrefix = currentPrefix?.append(event.span()) ?: event.span()
                } else {
                    currentSuffix = currentSuffix?.append(event.span()) ?: event.span()
                }
            }
            EventKind.ArrayClose -> {
                finishValue(event)
                val trailingComma = trailingStart != null && result.isNotEmpty()
                val start = trailingStart ?: event.span().start
                result.trailingComma = trailingComma
                result.rawTrailing = RawString.withSpan(start, event.span().start)
                result.span = openEvent.span().append(event.span())
                break
            }
        }
    }
    if (result.span == null) result.span = Span(openEvent.span().start, closeSpan.end)

    return Value.Array(result)
}

/** `inline-table = inline-table-open [ inline-table-keyvals ] ws-comment-newline inline-table-close` */
private fun onInlineTable(openEvent: Event, input: EventStream, context: ParseContext): Value {
    val errors = context.errors
    val result = InlineTable()
    var closeSpan = openEvent.span()

    var currentPrefix: Span? = null
    var currentKey: Pair<List<Key>, Key>? = null
    var seenKeyvalSep = false
    var currentValue: Value? = null
    var trailingStart: Int? = openEvent.span().end
    var lastSeparator: Span? = null
    var currentSuffix: Span? = null

    fun isPrefix(): Boolean = if (seenKeyvalSep) currentValue == null else currentKey == null

    fun finishValue(event: Event) {
        seenKeyvalSep = false
        val keyed = currentKey
        val value = currentValue
        currentKey = null
        currentValue = null
        if (keyed == null || value == null) return
        val (path, key) = keyed
        val prefix = currentPrefix ?: event.span().before()
        currentPrefix = null
        val suffix = currentSuffix ?: event.span().before()
        currentSuffix = null
        val shared = mutableListOf<Key>()
        val table = descendInlinePath(result, path, true, errors, shared) ?: return

        val decor = value.rawDecor()
        decor.prefix = RawString.withSpan(prefix)
        decor.suffix = RawString.withSpan(suffix)

        // "Likewise, using dotted keys to redefine tables already defined in [table] form is not allowed"
        val mixedTableTypes = table.isDotted == path.isEmpty()
        val keySpan = key.repr?.span() ?: event.span()
        if (mixedTableTypes) {
            errors.report(Reason.DuplicateKey(key.name), unexpected = keySpan)
        } else {
            val existing = table.items.getEntry(key.name)
            if (existing == null) {
                table.items.insert(key, Item.inInlineTable(value))
                val entry = table.items.getEntry(key.name)
                entry?.order = context.nextOrder()
                if (path.isNotEmpty()) entry?.pathSpelling = PathSpelling.of(path, shared)
            } else {
                val oldSpan = existing.key.repr?.span() ?: event.span()
                errors.report(Reason.DuplicateKey(key.name), unexpected = keySpan, context = oldSpan)
            }
        }
    }

    while (true) {
        val event = input.nextToken() ?: break
        closeSpan = event.span()
        when (event.kind()) {
            EventKind.StdTableOpen,
            EventKind.ArrayTableOpen,
            EventKind.StdTableClose,
            EventKind.ArrayClose,
            EventKind.ArrayTableClose,
            EventKind.KeySep,
            -> break
            EventKind.Error -> continue
            EventKind.SimpleKey -> {
                val (path, key) = onKey(event, input, context)
                trailingStart = null
                if (currentPrefix == null) currentPrefix = event.span().before()
                if (key != null) currentKey = path to key
            }
            EventKind.KeyValSep -> {
                seenKeyvalSep = true
                val lastKey = currentKey?.second
                if (lastKey != null) {
                    val prefix = currentPrefix ?: event.span().before()
                    currentPrefix = null
                    val suffix = currentSuffix ?: event.span().before()
                    currentSuffix = null
                    lastKey.rawLeafDecor.assign(RawDecor(RawString.withSpan(prefix), RawString.withSpan(suffix)))
                }
            }
            EventKind.InlineTableOpen -> {
                val value = onInlineTable(event, input, context)
                if (currentPrefix == null) currentPrefix = event.span().before()
                currentValue = value
            }
            EventKind.ArrayOpen -> {
                val value = onArray(event, input, context)
                if (currentPrefix == null) currentPrefix = event.span().before()
                currentValue = value
            }
            EventKind.Scalar -> {
                val value = onScalar(event, context)
                if (currentPrefix == null) currentPrefix = event.span().before()
                currentValue = value
            }
            EventKind.ValueSep -> {
                finishValue(event)
                trailingStart = event.span().end
                lastSeparator = event.span()
            }
            EventKind.Whitespace, EventKind.Comment, EventKind.Newline -> {
                if (context.version == TomlVersion.V1_0 && event.kind() != EventKind.Whitespace) {
                    errors.report(
                        Reason.NotInToml10(Toml11Feature.MultilineInlineTable),
                        context = openEvent.span(),
                        unexpected = event.span(),
                    )
                }
                if (isPrefix()) {
                    currentPrefix = currentPrefix?.append(event.span()) ?: event.span()
                } else {
                    currentSuffix = currentSuffix?.append(event.span()) ?: event.span()
                }
            }
            EventKind.InlineTableClose -> {
                finishValue(event)
                val trailingComma = trailingStart != null && result.isNotEmpty()
                if (context.version == TomlVersion.V1_0 && trailingComma) {
                    errors.report(
                        Reason.NotInToml10(Toml11Feature.InlineTableTrailingComma),
                        context = openEvent.span(),
                        unexpected = lastSeparator ?: event.span(),
                    )
                }
                val start = trailingStart ?: event.span().start
                result.trailingComma = trailingComma
                result.rawTrailing = RawString.withSpan(start, event.span().start)
                result.span = openEvent.span().append(event.span())
                break
            }
        }
    }
    if (result.span == null) result.span = Span(openEvent.span().start, closeSpan.end)

    return Value.InlineTable(result)
}

private fun descendInlinePath(
    start: InlineTable,
    path: List<Key>,
    dotted: Boolean,
    errors: TomlSink,
    shared: MutableList<Key>,
): InlineTable? {
    var table = start
    for (key in path) {
        val existing = table.items.getEntry(key.name)
        if (existing == null) {
            val newTable = InlineTable()
            newTable.span = key.span()
            newTable.isImplicit = true
            newTable.isDotted = dotted
            val held = key.copy()
            table.items.insert(held, Item.inInlineTable(Value.InlineTable(newTable)))
            shared += held
            table = newTable
            continue
        }
        shared += existing.key
        // An occupied entry always holds a value, as InlineTable.entryFormat guarantees upstream.
        val value = existing.item.intoValue() ?: Value.InlineTable(InlineTable())
        existing.item = Item.inInlineTable(value)
        table = when (value) {
            is Value.InlineTable -> {
                // Tables cannot be defined more than once, and dotted keys cannot extend an inline table.
                if (dotted && !value.table.isImplicit) {
                    errors.report(Reason.DuplicateKey(key.name), unexpected = keySpan(key))
                    return null
                }
                value.table
            }
            else -> {
                errors.report(Reason.DottedKeyExtendsValue(key.name, value.type), unexpected = keySpan(key))
                return null
            }
        }
    }
    return table
}
