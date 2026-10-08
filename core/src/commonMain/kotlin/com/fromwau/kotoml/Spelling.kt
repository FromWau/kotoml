package com.fromwau.kotoml

import com.fromwau.kotoml.writer.TomlStringBuilder
import com.fromwau.kotoml.writer.WriteTomlValue
import com.fromwau.kotoml.writer.toTomlValue

/**
 * Raw TOML text kept for formatting: text kotoml made, text read from a document, text a caller set, or a span into a
 * parse's input until it is resolved.
 */
internal class KeptText private constructor(private val state: State) {

    private sealed interface State {
        data object Empty : State

        data class Explicit(val text: String) : State

        // Text read from a parse's input, as opposed to text a caller gave.
        data class Read(val text: String) : State

        // Text a caller set in the decor of something stored nowhere yet, as opposed to the document's own text.
        data class Given(val text: String) : State

        data class Spanned(val span: Span) : State
    }

    constructor() : this(State.Empty)

    /** The text, or null while it is still only a span into a parse's input. */
    val textOrNull: String?
        get() = when (state) {
            State.Empty -> ""
            is State.Explicit -> state.text
            is State.Read -> state.text
            is State.Given -> state.text
            is State.Spanned -> null
        }

    /** Whether this text was read from a document rather than given by a caller. */
    val isRead: Boolean
        get() = state is State.Read

    /** Whether a caller set this text in the decor of something stored nowhere yet, rather than in a document. */
    val isGiven: Boolean
        get() = state is State.Given

    val span: Span?
        get() = (state as? State.Spanned)?.span

    fun text(input: SourceText): String = when (state) {
        State.Empty -> ""
        is State.Explicit -> state.text
        is State.Read -> state.text
        is State.Given -> state.text
        is State.Spanned -> input.slice(state.span)
    }

    /** The text, or [default] while it is only a span. */
    fun textOr(default: String): String = when (state) {
        State.Empty -> ""
        is State.Explicit -> state.text
        is State.Read -> state.text
        is State.Given -> state.text
        is State.Spanned -> default
    }

    /** This text changed by [transform], read or given as this one was. */
    fun mapText(transform: (String) -> String): KeptText = when (state) {
        State.Empty, is State.Spanned -> this
        is State.Explicit -> from(transform(state.text))
        is State.Read -> read(transform(state.text))
        is State.Given -> given(transform(state.text))
    }

    /** This text as the document's own: text a caller gave counts as text kotoml made, which a copy only fills with. */
    fun settled(): KeptText = if (state is State.Given) from(state.text) else this

    fun resolveSpans(input: SourceText): KeptText = when (state) {
        is State.Spanned -> if (state.span.length <= 0) KeptText() else KeptText(State.Read(input.slice(state.span)))
        else -> this
    }

    // Text equals text however it came to be; a span equals only the same span.
    override fun equals(other: Any?): Boolean {
        if (other !is KeptText) return false
        val text = textOrNull
        return if (text != null) text == other.textOrNull else state == other.state
    }

    override fun hashCode(): Int = textOrNull?.hashCode() ?: state.hashCode()

    /** The text as given, or `start..end` for a span. */
    override fun toString(): String = when (state) {
        State.Empty -> ""
        is State.Explicit -> state.text
        is State.Read -> state.text
        is State.Given -> state.text
        is State.Spanned -> state.span.toString()
    }

    companion object {
        /** Holds [text]; an empty string becomes the empty raw string. */
        fun from(text: String): KeptText = if (text.isEmpty()) KeptText() else KeptText(State.Explicit(text))

        /** Holds [text] as read from a document, as the text a copy brings from there. */
        fun read(text: String): KeptText = if (text.isEmpty()) KeptText() else KeptText(State.Read(text))

        /** Holds [text] as a caller set it in a decor. */
        fun given(text: String): KeptText = if (text.isEmpty()) KeptText() else KeptText(State.Given(text))

        fun withSpan(start: Int, end: Int): KeptText = KeptText(State.Spanned(Span(start, end)))

        fun withSpan(span: Span): KeptText = KeptText(State.Spanned(span))
    }
}

/**
 * The input of a parse, kept as text and as the UTF-8 bytes every span counts. [locatesKeys] is set for a whole
 * document, whose keys remember where they were read.
 */
internal class SourceText(
    val text: String,
    private val locatesKeys: Boolean = false,
) {
    val bytes: ByteArray = text.encodeToByteArray()

    private val lineStarts: List<Int> by lazy {
        val starts = mutableListOf(0)
        for ((index, byte) in bytes.withIndex()) {
            if (byte == '\n'.code.toByte()) starts += index + 1
        }
        starts
    }

    /** The line and column of byte [offset], counted as [TextPosition] counts them; null unless [locatesKeys]. */
    fun positionOf(offset: Int): TextPosition? {
        if (!locatesKeys) return null
        val found = lineStarts.binarySearch(offset)
        val line = if (found >= 0) found else -found - 2
        // A byte order mark shows nothing, so the first line's columns start after it.
        val start = if (line == 0 && text.startsWith(BYTE_ORDER_MARK)) BYTE_ORDER_MARK_BYTES else lineStarts[line]
        var column = 1
        for (index in start until offset) {
            if ((bytes[index].toInt() and 0xC0) != 0x80) column++
        }
        return TextPosition(line + 1, column)
    }

    /** The text under [span], which must lie within this input. */
    fun slice(span: Span): String {
        require(span.start in 0..span.end && span.end <= bytes.size) {
            "span $span is outside an input of ${bytes.size} bytes"
        }
        return bytes.decodeToString(span.start, span.end)
    }
}

private const val BYTE_ORDER_MARK_BYTES = 3

/** How a value or key is spelled in TOML, such as `'value'` for the string `value` or `0x10` for 16. */
internal data class Spelling(val raw: KeptText) {
    constructor(text: String) : this(KeptText.from(text))

    val span: Span?
        get() = raw.span

    fun resolveSpans(input: SourceText): Spelling = Spelling(raw.resolveSpans(input))

    override fun toString(): String = raw.toString()
}

/**
 * The text around a value, a key or a table header: its [prefix] before it and its [suffix] after it, such as
 * whitespace, comments and newlines.
 *
 * A null side prints the default for where the item stands, such as the one space between `=` and a value; an
 * empty string prints nothing there.
 *
 * ```kotlin
 * val answer = Value.Integer(42L, Decor(prefix = "  ", suffix = " # the answer"))
 * ```
 */
public data class Decor(
    /** The text before the item, or null for the default. */
    val prefix: String? = null,
    /** The text after the item, or null for the default. */
    val suffix: String? = null,
)

/** Where a value prints: after `key =` on a line of a table, in an inline table, or as an element of an array. */
internal enum class ValuePlace {
    Line,
    InlineTable,
    Array,
}

/** The stored form of a [Decor], whose sides may still be spans into a parse's input. */
internal class RawDecor(
    var prefix: KeptText? = null,
    var suffix: KeptText? = null,
) {

    /** Goes back to the defaults on both sides. */
    fun clear() {
        prefix = null
        suffix = null
    }

    fun assign(other: RawDecor) {
        prefix = other.prefix
        suffix = other.suffix
    }

    /** A side still held as a span reads as the default, as it prints. */
    fun toDecor(): Decor = Decor(
        prefix = prefix?.textOrNull,
        suffix = suffix?.textOrNull,
    )

    /**
     * Takes the sides of [decor], a caller's: the document's own text when this decor is [inDocument], since setting it
     * writes the document, otherwise given text. A side set to the text it already holds stays as it was.
     */
    fun set(
        decor: Decor,
        inDocument: Boolean,
    ) {
        prefix = givenOrKept(prefix, decor.prefix, inDocument)
        suffix = givenOrKept(suffix, decor.suffix, inDocument)
    }

    private fun givenOrKept(
        side: KeptText?,
        text: String?,
        inDocument: Boolean,
    ): KeptText? = when {
        text == null -> null
        side?.textOrNull == text -> side
        inDocument -> KeptText.from(text)
        else -> KeptText.given(text)
    }

    fun copy(): RawDecor = RawDecor(prefix, suffix)

    /** Takes [other]'s side wherever this one is left at the default. */
    fun fillDefaultsFrom(other: RawDecor) {
        if (prefix == null) prefix = other.prefix
        if (suffix == null) suffix = other.suffix
    }

    /**
     * Drops the sides of a value's decor that cannot stand where a copy of it is stored: a line break before a value
     * after `=`, or a comment after a value inside brackets or braces, which would run over what closes them. The
     * comment lines between a value and its comma stay in brackets or braces, where the place lays them out; for a line
     * of a table, [takeCommentLinesBelow] takes them out first.
     */
    fun fitFor(place: ValuePlace) {
        val before = prefix?.textOrNull.orEmpty()
        val after = suffix?.textOrNull.orEmpty()
        if (place != ValuePlace.Array && '\n' in before) prefix = null
        val below = commentLinesBelow()
        val unfit = when (place) {
            ValuePlace.Line -> '\n' in after
            ValuePlace.InlineTable -> below.isEmpty() && ('\n' in after || '#' in after)
            ValuePlace.Array -> below.isEmpty() && '#' in after
        }
        if (unfit) suffix = null
    }

    /**
     * Takes out the comment lines below the value's line, before its comma, each ending in a line break, leaving the
     * comment on its line.
     */
    fun takeCommentLinesBelow(): String {
        val below = commentLinesBelow()
        if (below.isEmpty()) return ""
        val lineBreak = suffix?.textOrNull.orEmpty().lineBreak()
        suffix = KeptText.read(commentOnLine())
        return below.joinToString("") { it + lineBreak }
    }

    /** The comment lines this decor of a value holds below its line, before the comma after it, unindented. */
    fun commentLinesBelow(): List<String> {
        val text = suffix?.textOrNull.orEmpty()
        val firstBreak = text.indexOf('\n')
        if (firstBreak < 0) return emptyList()
        return text
            .substring(firstBreak + 1)
            .lines()
            .map { it.trim() }
            .filter { it.startsWith('#') }
    }

    /** Whether a caller gave the comment ending the line of the item this decor surrounds, rather than reading it. */
    fun givesComment(): Boolean = suffix?.isGiven == true && '#' in commentOnLine()

    /** The comment ending the line of the value this decor surrounds, with the spacing before it; "" for none. */
    fun commentOnLine(): String {
        val text = suffix?.textOrNull.orEmpty()
        val firstBreak = text.indexOf('\n')
        val line = if (firstBreak < 0) text else text.substring(0, text.lineEndBefore(firstBreak))
        return line.takeIf { '#' in it }.orEmpty()
    }

    /**
     * Lays the comment lines below a value out for brackets or braces across lines, whose entries start with
     * [indent]: each on a line of its own, and the comma after them on the next.
     */
    fun indentCommentLinesBelow(
        indent: String,
        lineBreak: String,
    ) {
        val below = commentLinesBelow()
        if (below.isEmpty()) return
        val lines = below.joinToString("") { indent + it + lineBreak }
        suffix = KeptText.read(commentOnLine() + lineBreak + lines + indent)
    }

    fun prefixEncode(buf: StringBuilder, default: String) {
        val prefix = prefix
        if (prefix != null) encodeRaw(buf, prefix.textOr(default)) else buf.append(default)
    }

    fun suffixEncode(buf: StringBuilder, default: String) {
        val suffix = suffix
        if (suffix != null) encodeRaw(buf, suffix.textOr(default)) else buf.append(default)
    }

    fun settleGiven() {
        prefix = prefix?.settled()
        suffix = suffix?.settled()
    }

    fun resolveSpans(input: SourceText) {
        prefix = prefix?.resolveSpans(input)
        suffix = suffix?.resolveSpans(input)
    }

    override fun equals(other: Any?): Boolean = other is RawDecor && prefix == other.prefix && suffix == other.suffix

    override fun hashCode(): Int = 31 * prefix.hashCode() + suffix.hashCode()

    override fun toString(): String = "Decor(prefix=${prefix ?: "default"}, suffix=${suffix ?: "default"})"

    companion object {
        val EMPTY: RawDecor = RawDecor()
    }
}

internal fun encodeRaw(buf: StringBuilder, raw: String) {
    buf.append(raw)
}

/**
 * A scalar value together with its [written] spelling and its [decor] (the text around it).
 *
 * One made with [Formatted.of] has no written spelling, so it prints in the default spelling until it comes from
 * parsed text.
 */
internal class Formatted<T : Any> private constructor(
    val value: T,
    var written: Spelling?,
    val decor: RawDecor,
    private val toSpelling: (T) -> Spelling,
) {

    /** The spelling the value gets when it has none of its own. */
    fun defaultSpelling(): Spelling = toSpelling(value)

    /** The spelling as written, or null when it has none or it is still a span. */
    val spelling: String?
        get() = written?.raw?.textOrNull

    /** The spelling printed: the original one when there is one, otherwise the default. */
    val displaySpelling: String
        get() = spelling ?: defaultSpelling().raw.textOrNull.orEmpty()

    val span: Span?
        get() = written?.span

    fun resolveSpans(input: SourceText) {
        decor.resolveSpans(input)
        written = written?.resolveSpans(input)
    }

    fun copy(): Formatted<T> = Formatted(value, written, decor.copy(), toSpelling)

    /** This value replaced by [value], spelled [written], with the same decor. */
    fun withValue(
        value: T,
        written: Spelling?,
    ): Formatted<T> = Formatted(value, written, decor, toSpelling)

    override fun equals(other: Any?): Boolean {
        if (other !is Formatted<*>) return false
        return sameValue(value, other.value) && written == other.written && decor == other.decor
    }

    override fun hashCode(): Int = 31 * (31 * value.hashCode() + written.hashCode()) + decor.hashCode()

    override fun toString(): String = buildString { encodeFormatted(this, this@Formatted, "", "") }

    companion object {
        fun of(value: String): Formatted<String> = Formatted(value, null, RawDecor(), ::stringSpelling)

        fun of(value: Long): Formatted<Long> = Formatted(value, null, RawDecor(), ::integerSpelling)

        /** The sign of a NaN is kept, and may print as `-nan`. */
        fun of(value: Double): Formatted<Double> = Formatted(value, null, RawDecor(), ::floatSpelling)

        fun of(value: Boolean): Formatted<Boolean> = Formatted(value, null, RawDecor(), ::booleanSpelling)

        fun of(value: Datetime): Formatted<Datetime> = Formatted(value, null, RawDecor(), ::datetimeSpelling)
    }
}

// Rust compares floats by IEEE rules: NaN is unequal to itself, and -0.0 equals 0.0.
private fun sameValue(left: Any, right: Any?): Boolean {
    if (left is Double && right is Double) {
        val a: Double = left
        val b: Double = right
        return a == b
    }
    return left == right
}

private fun stringSpelling(value: String): Spelling = Spelling(TomlStringBuilder(value).asDefault().toTomlValue())

private fun integerSpelling(value: Long): Spelling = Spelling(value.toString())

private fun floatSpelling(value: Double): Spelling = Spelling(WriteTomlValue.of(value).toTomlValue())

private fun booleanSpelling(value: Boolean): Spelling = Spelling(value.toString())

private fun datetimeSpelling(value: Datetime): Spelling = Spelling(value.toString())
