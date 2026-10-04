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
import com.fromwau.kotoml.parser.isValid
import com.fromwau.kotoml.parser.parseDocument as parseDocumentEvents
import com.fromwau.kotoml.parser.parseKey as parseKeyEvents
import com.fromwau.kotoml.parser.parseSimpleKey as parseSimpleKeyEvents
import com.fromwau.kotoml.parser.parseValue as parseValueEvents


private fun collectEvents(
    source: TomlSource,
    errors: TomlSink,
    parse: (List<Token>, EventReceiver, ErrorSink) -> Unit,
): List<Event> {
    val tokens = source.lex().toList()
    val events = ArrayList<Event>(tokens.size)
    val validated = ValidateWhitespace(eventReceiver { events += it }, source)
    val receiver = RecursionGuard(validated, TomlDocument.NESTING_LIMIT.toUInt())
    parse(tokens, receiver, errors)
    return events
}

/** What one parse shares: its input, where errors go, the TOML version, and the order lines are read in. */
internal class ParseContext(val source: TomlSource, val errors: TomlSink, val version: TomlVersion) {
    private var nextOrder = 0L

    /**
     * [errors] for decoding strings and keys. The decoder reads TOML 1.1's escapes, so 1.0 swaps in its own list,
     * and a malformed `\x` escape, which 1.0 does not have, is reported as an unknown escape at its `x`.
     */
    val decodeErrors: ErrorSink = if (version == TomlVersion.V1_0) {
        ErrorSink { error ->
            val reported = when (error.reason) {
                ParseErrorReason.MissingEscapedValue -> error.copy(expected = EXPECTED_ESCAPES_V1_0)
                ParseErrorReason.TooFewUnicodeDigits -> unknownHexEscape(error) ?: error
                else -> error
            }
            errors.reportError(reported)
        }
    } else {
        errors
    }

    /** [error], a hex escape with too few digits, as TOML 1.0 sees it when the escape is `\x`; null otherwise. */
    private fun unknownHexEscape(error: ParseError): ParseError? {
        val bytes = source.bytes
        // The digits the escape did have lie between its letter and where the error points.
        var letter = (error.unexpected ?: return null).start - 1
        while (letter >= 0 && bytes[letter].toInt().toChar().isAsciiHexDigit()) letter--
        if (letter < 1 || bytes[letter] != 'x'.code.toByte() || bytes[letter - 1] != '\\'.code.toByte()) return null
        return error.copy(
            reason = ParseErrorReason.MissingEscapedValue,
            unexpected = Span(letter, letter),
            expected = EXPECTED_ESCAPES_V1_0,
        )
    }

    /**
     * How deeply the value being built nests: one level per enclosing array or inline table, and one per dot of the
     * keys leading to it from its line. The event parser bounds brackets alone and each key's dots alone, but the
     * tree is as deep as both together, and dotted keys inside nested inline tables would multiply past either bound.
     */
    var nesting = 0

    fun nextOrder(): Long = nextOrder++

    fun text(span: Span): String = source.get(span)?.text.orEmpty()
}

internal fun parseDocumentModel(source: TomlSource, errors: TomlSink, version: TomlVersion): TomlDocument {
    val events = collectEvents(source, errors, ::parseDocumentEvents)
    val context = ParseContext(source, errors, version)
    val lineEnding = events.firstOrNull { it.kind == EventKind.Newline }?.let { context.text(it.span) } ?: "\n"
    return DocumentBuilder(EventStream(events), context).document(lineEnding)
}

internal fun parseSimpleKeyItem(source: TomlSource, errors: TomlSink, version: TomlVersion): Key {
    val events = collectEvents(source, errors, ::parseSimpleKeyEvents)
    val context = ParseContext(source, errors, version)
    val event = events.firstOrNull { it.kind == EventKind.SimpleKey }
    if (event != null) {
        val (raw, key) = onSimpleKey(event, context)
        return Key(key).withSpelling(Spelling(raw))
    }
    val raw = KeptText.withSpan(0, source.bytes.size)
    return Key(source.input).withSpelling(Spelling(raw))
}

internal fun parseKeyPath(source: TomlSource, errors: TomlSink, version: TomlVersion): List<Key> {
    val events = collectEvents(source, errors, ::parseKeyEvents)
    val context = ParseContext(source, errors, version)
    val input = EventStream(events)
    var prefix: KeptText? = null
    var path: List<Key>? = null
    var key: Key? = null
    var suffix: KeptText? = null
    while (true) {
        val event = input.next() ?: break
        if (event.kind == EventKind.Whitespace) {
            val raw = KeptText.withSpan(event.span)
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

/** The parsed value, or null once an error is reported. */
internal fun parseValueItem(source: TomlSource, errors: TomlSink, version: TomlVersion): Value? {
    val events = collectEvents(source, errors, ::parseValueEvents)
    return value(EventStream(events), ParseContext(source, errors, version))
}

/** The events of a parse, consumed front to back. */
internal class EventStream(private val events: List<Event>) {
    private var position = 0

    fun next(): Event? = if (position < events.size) events[position++] else null

    fun peek(): Event? = events.getOrNull(position)

    fun get(offset: Int): Event? = events.getOrNull(position + offset)
}

private class TableHeader(val path: List<Key>, val key: Key?, val span: Span, val isArray: Boolean)

/** Builds a [TomlDocument] from the events of a whole document, upstream's `document` and its `State`. */
private class DocumentBuilder(private val input: EventStream, private val context: ParseContext) {
    private val source = context.source
    private val errors = context.errors
    private var root = StandardTable()
    private var currentTable = StandardTable()
    private var currentTrailing: Span? = null
    private var currentHeader: TableHeader? = null
    private var currentPosition = 0L

    fun document(lineEnding: String): TomlDocument {
        while (true) {
            val event = input.next() ?: break
            when (event.kind) {
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
                    val suffix = suffixSpan?.let { KeptText.withSpan(it) } ?: KeptText()
                    startTable(header, RawDecor(prefix, suffix))
                    currentTable.headerLineEnding = newline?.let { context.text(it.span) }
                }
                EventKind.SimpleKey -> {
                    val keyPrefix = takeTrailing()
                    val (path, parsedKey) = onKey(event, input, context)
                    val key = parsedKey ?: break
                    var next = input.next() ?: break
                    var keySuffix: Event? = null
                    if (next.kind == EventKind.Whitespace) {
                        keySuffix = next
                        next = input.next() ?: break
                    }
                    if (next.kind != EventKind.KeyValSep) break
                    key.rawLeafDecor.prefix = keyPrefix
                    key.rawLeafDecor.suffix = keySuffix?.let { KeptText.withSpan(it.span) } ?: KeptText()

                    val valuePrefix = if (input.peek()?.kind == EventKind.Whitespace) {
                        input.next()?.let { KeptText.withSpan(it.span) }
                    } else {
                        null
                    } ?: KeptText()
                    val value = context.nest(path.size) { value(input, context) }
                    val (suffixSpan, newline) = wsCommentNewline(input)
                    if (value == null) continue
                    val valueSuffix = suffixSpan?.let { KeptText.withSpan(it) } ?: KeptText()
                    val decor = value.rawDecor
                    decor.prefix = valuePrefix
                    decor.suffix = valueSuffix

                    val entry = captureKeyValue(path, key, value)
                    entry?.lineEnding = newline?.let { context.text(it.span) }
                }
                EventKind.Whitespace, EventKind.Comment, EventKind.Newline -> captureTrailing(event)
            }
        }

        finishTable()

        val text = source.input
        val input = SourceText(text)
        // A failed parse is thrown away, and its recovery spans need not fit the text, as other entry points also skip.
        val failed = errors.error != null
        if (!failed) {
            root.resolveSpans(input)
            root.settleHeaderSpellings(emptyList())
        }
        val trailing = takeTrailing().let { if (failed) it else it.resolveSpans(input) }
        return TomlDocument(root, trailing, lineEnding, text)
    }

    private fun onTable(openEvent: Event): TableHeader {
        val isArray = openEvent.kind == EventKind.ArrayTableOpen
        var currentPath: List<Key>? = null
        var currentKey: Key? = null
        var currentSpan = openEvent.span
        var currentPrefix: Span? = null
        var currentSuffix: Span? = null

        while (true) {
            val event = input.next() ?: break
            when (event.kind) {
                EventKind.ArrayTableClose, EventKind.StdTableClose -> {
                    currentSpan = currentSpan.append(event.span)
                    break
                }
                EventKind.SimpleKey -> {
                    if (currentPrefix == null) currentPrefix = event.span.before()
                    val (path, key) = onKey(event, input, context)
                    currentPath = path
                    currentKey = key
                    if (currentSuffix == null) currentSuffix = event.span.after()
                }
                EventKind.Whitespace -> if (currentKey != null) {
                    currentSuffix = event.span
                } else {
                    currentPrefix = event.span
                }
                else -> continue
            }
        }

        // The event parser reports a key, if only an empty one, inside every header.
        val prefix = currentPrefix ?: openEvent.span.after()
        val suffix = currentSuffix ?: openEvent.span.after()
        currentKey?.rawLeafDecor?.assign(RawDecor(KeptText.withSpan(prefix), KeptText.withSpan(suffix)))

        return TableHeader(currentPath ?: emptyList(), currentKey, currentSpan, isArray)
    }

    private fun captureTrailing(event: Event) {
        val trailing = currentTrailing
        currentTrailing = trailing?.append(event.span) ?: event.span
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
            parentTable.items.put(key, Item(value))
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
                context = existing.key.span,
            )
            return null
        }
    }

    private fun finishTable() {
        val previousTable = currentTable
        currentTable = StandardTable()
        val header = currentHeader
        if (header == null) {
            root = previousTable
            return
        }
        currentHeader = null
        val key = header.key ?: return
        previousTable.span = header.span

        val parentTable = descendPath(root, header.path, false, errors) ?: return
        previousTable.headerSpelling = PathSpelling.ofHeader(header.path + key)
        if (header.isArray) {
            val keySpan = keySpan(key)
            val entry = parentTable.items.getEntry(key.name)
                ?: KeyValue(key, Item(ArrayOfTables())).also { parentTable.items.put(key, it.item) }
            val array = entry.item.arrayOfTablesOrNull
            if (array == null) {
                errors.report(
                    Reason.DuplicateKey(key.name),
                    unexpected = keySpan,
                    context = entry.item.span,
                )
                return
            }
            array.add(previousTable)
            val first = array.values.firstOrNull()?.standardTableOrNull?.span
            val last = array.values.lastOrNull()?.standardTableOrNull?.span
            array.span = if (first != null && last != null) Span(first.start, last.end) else null
        } else {
            parentTable.items.put(key, Item(previousTable))
        }
    }

    private fun startTable(header: TableHeader, decor: RawDecor) {
        if (!header.isArray) {
            // Looking the table up here points a duplicate-key error at the right line, and keeps the child
            // tables an implicit table already has.
            val parentTable = descendPath(root, header.path, false, errors)
            val key = header.key
            val old = if (key != null) parentTable?.items?.remove(key.name) else null
            if (old != null && key != null) {
                val oldTable = old.item.standardTableOrNull
                if (oldTable != null && oldTable.isImplicit && !oldTable.isDotted) {
                    currentTable = oldTable
                } else {
                    errors.report(
                        Reason.DuplicateKey(key.name),
                        unexpected = keySpan(key),
                        context = old.key.span,
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

    private fun takeTrailing(): KeptText {
        val trailing = currentTrailing ?: return KeptText()
        currentTrailing = null
        return KeptText.withSpan(trailing)
    }
}

/**
 * Walks [path] down from [start], making the implicit tables it names, and returns the table it ends at. The keys
 * the walk passes through, as the tables hold them, are added to [shared].
 */
private fun descendPath(
    start: StandardTable,
    path: List<Key>,
    dotted: Boolean,
    errors: TomlSink,
    shared: MutableList<Key> = mutableListOf(),
): StandardTable? {
    var table = start
    for (key in path) {
        val existing = table.items.getEntry(key.name)
        if (existing == null) {
            val newTable = StandardTable()
            newTable.span = key.span
            newTable.isImplicit = true
            newTable.isDotted = dotted
            val held = key.copy()
            table.items.put(held, Item(newTable))
            shared += held
            table = newTable
            continue
        }
        shared += existing.key
        val item = existing.item
        val array = item.arrayOfTablesOrNull
        val child = item.standardTableOrNull
        val value = item.valueOrNull
        table = when {
            array != null -> array.values.lastOrNull()?.standardTableOrNull ?: return null
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
                    context = value.span,
                )
                return null
            }
            else -> return null
        }
    }
    return table
}

private fun keySpan(key: Key): Span? = key.written?.span

/** The whitespace and comment to the end of the line, and the newline ending it, if the input did not end first. */
private fun wsCommentNewline(input: EventStream): Pair<Span?, Event?> {
    var currentSpan: Span? = null
    while (true) {
        val event = input.next() ?: break
        when (event.kind) {
            EventKind.Whitespace, EventKind.Comment -> {
                val span = currentSpan
                currentSpan = span?.append(event.span) ?: event.span
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
        val prefixSpan = currentPrefix ?: key.span.before()
        currentPrefix = null
        val suffixSpan = currentSuffix ?: key.span.after()
        currentSuffix = null

        val raw = context.source.get(key)
        val decoded = StringOutput()
        raw?.decodeKey(decoded, context.decodeErrors)
        checkEscapes(key, context)

        val parsed = Key(decoded.toString())
            .withSpelling(Spelling(KeptText.withSpan(key.span)))
        parsed.rawDottedDecor.assign(RawDecor(KeptText.withSpan(prefixSpan), KeptText.withSpan(suffixSpan)))
        resultKey?.let { resultPath.add(it) }
        resultKey = parsed
    }

    if (moreKey(input)) {
        while (true) {
            val event = input.next() ?: break
            when (event.kind) {
                EventKind.SimpleKey -> {
                    currentKey = event
                    if (!moreKey(input)) break
                }
                EventKind.Whitespace -> if (currentKey != null) {
                    currentSuffix = event.span
                } else {
                    currentPrefix = event.span
                }
                EventKind.KeySep -> closeKey()
                else -> continue
            }
        }
    }

    closeKey()

    if (TomlDocument.NESTING_LIMIT <= context.nesting + resultPath.size) {
        errors.report(Reason.KeyTooDeep, unexpected = keyEvent.span)
        return emptyList<Key>() to null
    }

    return resultPath to resultKey
}

private fun moreKey(input: EventStream): Boolean {
    val first = input.get(0)?.kind
    val second = input.get(1)?.kind
    return first == EventKind.KeySep || (first == EventKind.Whitespace && second == EventKind.KeySep)
}

/** `simple-key = quoted-key / unquoted-key`: the key's raw span and its decoded text. */
private fun onSimpleKey(event: Event, context: ParseContext): Pair<KeptText, String> {
    val decoded = StringOutput()
    context.source.get(event)?.decodeKey(decoded, context.decodeErrors)
    checkEscapes(event, context)
    return KeptText.withSpan(event.span) to decoded.toString()
}

/** `val = string / boolean / array / inline-table / date-time / float / integer`, or null once an error is reported. */
private fun value(input: EventStream, context: ParseContext): Value? {
    val event = input.next()
    if (event != null) {
        when (event.kind) {
            EventKind.InlineTableOpen -> return onInlineTable(event, input, context)
            EventKind.ArrayOpen -> return onArray(event, input, context)
            EventKind.Scalar -> return onScalar(event, context)
            else -> {}
        }
    }
    return null
}

/** The scalar [event] spells, or null once an error is reported. */
private fun onScalar(event: Event, context: ParseContext): Value? {
    val errors = context.errors
    val valueRaw = KeptText.withSpan(event.span)
    val decoded = StringOutput()
    val kind = context.source.get(event)?.decodeScalar(decoded, context.decodeErrors) ?: ScalarKind.String
    val text = decoded.toString()
    return when (kind) {
        ScalarKind.String -> {
            checkEscapes(event, context)
            Value.String(Formatted.of(text).withSpelling(valueRaw))
        }
        is ScalarKind.Boolean -> Value.Boolean(Formatted.of(kind.value).withSpelling(valueRaw))
        ScalarKind.DateTime -> {
            val datetime = when (val parsed = Datetime.parse(text)) {
                is com.fromwau.kern.result.Result.Success -> parsed.value
                is com.fromwau.kern.result.Result.Error -> {
                    errors.report(Reason.InvalidDatetime(parsed.error), unexpected = event.span)
                    return null
                }
            }
            val secondsLeftOut = when (datetime) {
                is Datetime.LocalDate -> false
                is Datetime.LocalTime -> datetime.time.second == null
                is Datetime.LocalDateTime -> datetime.time.second == null
                is Datetime.OffsetDateTime -> datetime.time.second == null
            }
            if (context.version == TomlVersion.V1_0 && secondsLeftOut) {
                errors.report(Reason.NotInToml10(Toml11Feature.TimeWithoutSeconds), unexpected = event.span)
            }
            Value.Datetime(Formatted.of(datetime).withSpelling(valueRaw))
        }
        ScalarKind.Float -> {
            val parsed = parseTomlFloat(text)
            val float = if (parsed == null) {
                errors.reportError(ParseError(ParseErrorReason.InvalidScalar(kind), unexpected = event.span))
                return null
            } else {
                val unsigned = if (text.startsWith('+') || text.startsWith('-')) text.substring(1) else text
                if (parsed.isInfinite() && !unsigned.all { it in 'a'..'z' || it in 'A'..'Z' }) {
                    errors.report(Reason.FloatOverflow, unexpected = event.span)
                }
                parsed
            }
            Value.Float(Formatted.of(float).withSpelling(valueRaw))
        }
        is ScalarKind.Integer -> {
            val integer = parseTomlLong(text, kind.radix.base) ?: run {
                val digits = text.removePrefix("-").removePrefix("+")
                when {
                    digits.isEmpty() -> errors.report(Reason.MissingDigits(kind.radix), unexpected = event.span)
                    digits.all { kind.radix.isValid(it.code) } -> errors.report(Reason.IntegerOverflow, event.span)
                    // The decoder has reported what is wrong with malformed text already; this only backs it up.
                    else -> errors.reportError(ParseError(ParseErrorReason.InvalidScalar(kind), event.span))
                }
                return null
            }
            Value.Integer(Formatted.of(integer).withSpelling(valueRaw))
        }
    }
}

private fun Char.isAsciiHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

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
    val encoding = event.encoding
    if (encoding != Encoding.BasicString &&
        encoding != Encoding.MultilineBasicString
    ) {
        return
    }
    val bytes = context.source.bytes
    val span = event.span
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

private fun <T : Any> Formatted<T>.withSpelling(raw: KeptText): Formatted<T> {
    written = Spelling(raw)
    return this
}

/** `array = array-open array-values array-close` */
private fun onArray(openEvent: Event, input: EventStream, context: ParseContext): Value =
    context.nestBracket(openEvent) { arrayBody(openEvent, input, context) }

private fun arrayBody(openEvent: Event, input: EventStream, context: ParseContext): Value {
    val result = TomlArray()
    var closeSpan = openEvent.span

    var currentPrefix: Span? = null
    var currentValue: Value? = null
    var trailingStart: Int? = openEvent.span.end
    var currentSuffix: Span? = null

    fun captureValue(event: Event, value: Value?) {
        if (value == null) return
        trailingStart = null
        if (currentPrefix == null) currentPrefix = event.span.before()
        currentValue = value
    }

    fun finishValue(event: Event) {
        val value = currentValue ?: return
        currentValue = null
        val prefix = currentPrefix ?: event.span.before()
        currentPrefix = null
        val suffix = currentSuffix ?: event.span.before()
        currentSuffix = null
        val decor = value.rawDecor
        decor.prefix = KeptText.withSpan(prefix)
        decor.suffix = KeptText.withSpan(suffix)
        result.add(value)
    }

    while (true) {
        val event = input.next() ?: break
        closeSpan = event.span
        when (event.kind) {
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
                trailingStart = event.span.end
            }
            EventKind.Whitespace, EventKind.Comment, EventKind.Newline -> {
                if (currentValue == null) {
                    currentPrefix = currentPrefix?.append(event.span) ?: event.span
                } else {
                    currentSuffix = currentSuffix?.append(event.span) ?: event.span
                }
            }
            EventKind.ArrayClose -> {
                finishValue(event)
                val trailingComma = trailingStart != null && result.isNotEmpty()
                val start = trailingStart ?: event.span.start
                result.trailingComma = trailingComma
                result.rawTrailing = KeptText.withSpan(start, event.span.start)
                result.span = openEvent.span.append(event.span)
                break
            }
        }
    }
    if (result.span == null) result.span = Span(openEvent.span.start, closeSpan.end)

    return Value.Array(result)
}

/** `inline-table = inline-table-open [ inline-table-keyvals ] ws-comment-newline inline-table-close` */
private fun onInlineTable(openEvent: Event, input: EventStream, context: ParseContext): Value =
    context.nestBracket(openEvent) { inlineTableBody(openEvent, input, context) }

private fun inlineTableBody(openEvent: Event, input: EventStream, context: ParseContext): Value {
    val errors = context.errors
    val result = InlineTable()
    var closeSpan = openEvent.span

    var currentPrefix: Span? = null
    var currentKey: Pair<List<Key>, Key>? = null
    var seenKeyvalSep = false
    var currentValue: Value? = null
    var trailingStart: Int? = openEvent.span.end
    var lastSeparator: Span? = null
    var currentSuffix: Span? = null

    fun isPrefix(): Boolean = if (seenKeyvalSep) currentValue == null else currentKey == null

    fun keyDots(): Int = currentKey?.first?.size ?: 0

    fun finishValue(event: Event) {
        seenKeyvalSep = false
        val keyed = currentKey
        val value = currentValue
        currentKey = null
        currentValue = null
        if (keyed == null || value == null) return
        val (path, key) = keyed
        val prefix = currentPrefix ?: event.span.before()
        currentPrefix = null
        val suffix = currentSuffix ?: event.span.before()
        currentSuffix = null
        val shared = mutableListOf<Key>()
        val table = descendInlinePath(result, path, true, errors, shared) ?: return

        val decor = value.rawDecor
        decor.prefix = KeptText.withSpan(prefix)
        decor.suffix = KeptText.withSpan(suffix)

        // "Likewise, using dotted keys to redefine tables already defined in [table] form is not allowed"
        val mixedTableTypes = table.isDotted == path.isEmpty()
        val keySpan = key.written?.span ?: event.span
        if (mixedTableTypes) {
            errors.report(Reason.DuplicateKey(key.name), unexpected = keySpan)
        } else {
            val existing = table.items.getEntry(key.name)
            if (existing == null) {
                table.items.put(key, Item.inInlineTable(value))
                val entry = table.items.getEntry(key.name)
                entry?.order = context.nextOrder()
                if (path.isNotEmpty()) entry?.pathSpelling = PathSpelling.of(path, shared)
            } else {
                val oldSpan = existing.key.written?.span ?: event.span
                errors.report(Reason.DuplicateKey(key.name), unexpected = keySpan, context = oldSpan)
            }
        }
    }

    while (true) {
        val event = input.next() ?: break
        closeSpan = event.span
        when (event.kind) {
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
                if (currentPrefix == null) currentPrefix = event.span.before()
                if (key != null) currentKey = path to key
            }
            EventKind.KeyValSep -> {
                seenKeyvalSep = true
                val lastKey = currentKey?.second
                if (lastKey != null) {
                    val prefix = currentPrefix ?: event.span.before()
                    currentPrefix = null
                    val suffix = currentSuffix ?: event.span.before()
                    currentSuffix = null
                    lastKey.rawLeafDecor.assign(RawDecor(KeptText.withSpan(prefix), KeptText.withSpan(suffix)))
                }
            }
            EventKind.InlineTableOpen -> {
                val value = context.nest(keyDots()) { onInlineTable(event, input, context) }
                if (currentPrefix == null) currentPrefix = event.span.before()
                currentValue = value
            }
            EventKind.ArrayOpen -> {
                val value = context.nest(keyDots()) { onArray(event, input, context) }
                if (currentPrefix == null) currentPrefix = event.span.before()
                currentValue = value
            }
            EventKind.Scalar -> {
                val value = onScalar(event, context)
                if (currentPrefix == null) currentPrefix = event.span.before()
                currentValue = value
            }
            EventKind.ValueSep -> {
                finishValue(event)
                trailingStart = event.span.end
                lastSeparator = event.span
            }
            EventKind.Whitespace, EventKind.Comment, EventKind.Newline -> {
                if (context.version == TomlVersion.V1_0 && event.kind != EventKind.Whitespace) {
                    errors.report(
                        Reason.NotInToml10(Toml11Feature.MultilineInlineTable),
                        context = openEvent.span,
                        unexpected = event.span,
                    )
                }
                if (isPrefix()) {
                    currentPrefix = currentPrefix?.append(event.span) ?: event.span
                } else {
                    currentSuffix = currentSuffix?.append(event.span) ?: event.span
                }
            }
            EventKind.InlineTableClose -> {
                finishValue(event)
                val trailingComma = trailingStart != null && result.isNotEmpty()
                if (context.version == TomlVersion.V1_0 && trailingComma) {
                    errors.report(
                        Reason.NotInToml10(Toml11Feature.InlineTableTrailingComma),
                        context = openEvent.span,
                        unexpected = lastSeparator ?: event.span,
                    )
                }
                val start = trailingStart ?: event.span.start
                result.trailingComma = trailingComma
                result.rawTrailing = KeptText.withSpan(start, event.span.start)
                result.span = openEvent.span.append(event.span)
                break
            }
        }
    }
    if (result.span == null) result.span = Span(openEvent.span.start, closeSpan.end)

    return Value.InlineTable(result)
}

/** Runs [block] with [levels] more levels of [ParseContext.nesting]. */
private inline fun <T> ParseContext.nest(levels: Int, block: () -> T): T {
    nesting += levels
    val result = block()
    nesting -= levels
    return result
}

/** Runs [block] one bracket deeper, refusing the bracket at [open] when it nests past the limit. */
private inline fun <T> ParseContext.nestBracket(open: Event, block: () -> T): T = nest(1) {
    if (TomlDocument.NESTING_LIMIT < nesting) {
        errors.reportError(ParseError(ParseErrorReason.RecursionLimit, unexpected = open.span))
    }
    block()
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
            newTable.span = key.span
            newTable.isImplicit = true
            newTable.isDotted = dotted
            val held = key.copy()
            table.items.put(held, Item.inInlineTable(Value.InlineTable(newTable)))
            shared += held
            table = newTable
            continue
        }
        shared += existing.key
        val value = existing.item.moveToValue()
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
