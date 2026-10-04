package com.fromwau.kotoml

import com.fromwau.kotoml.writer.TomlStringBuilder
import com.fromwau.kotoml.writer.WriteTomlValue
import com.fromwau.kotoml.writer.toTomlValue

/** Raw TOML text kept for formatting: text you set, or a span into a parse's input until it is resolved. */
internal class KeptText private constructor(private val state: State) {

    private sealed interface State {
        data object Empty : State

        data class Explicit(val text: String) : State

        data class Spanned(val span: Span) : State
    }

    constructor() : this(State.Empty)

    /** The text, or null while it is still only a span into a parse's input. */
    val textOrNull: String?
        get() = when (state) {
            State.Empty -> ""
            is State.Explicit -> state.text
            is State.Spanned -> null
        }

    val span: Span?
        get() = (state as? State.Spanned)?.span

    fun text(input: SourceText): String = when (state) {
        State.Empty -> ""
        is State.Explicit -> state.text
        is State.Spanned -> input.slice(state.span)
    }

    /** The text, or [default] while it is only a span. */
    fun textOr(default: String): String = when (state) {
        State.Empty -> ""
        is State.Explicit -> state.text
        is State.Spanned -> default
    }

    fun resolveSpans(input: SourceText): KeptText = when (state) {
        is State.Spanned -> if (state.span.length <= 0) KeptText() else from(input.slice(state.span))
        else -> this
    }

    override fun equals(other: Any?): Boolean = other is KeptText && state == other.state

    override fun hashCode(): Int = state.hashCode()

    /** The text as given, or `start..end` for a span. */
    override fun toString(): String = when (state) {
        State.Empty -> ""
        is State.Explicit -> state.text
        is State.Spanned -> state.span.toString()
    }

    companion object {
        /** Holds [text]; an empty string becomes the empty raw string. */
        fun from(text: String): KeptText = if (text.isEmpty()) KeptText() else KeptText(State.Explicit(text))

        fun withSpan(start: Int, end: Int): KeptText = KeptText(State.Spanned(Span(start, end)))

        fun withSpan(span: Span): KeptText = KeptText(State.Spanned(span))
    }
}

/** The input of a parse, kept as text and as the UTF-8 bytes every span counts. */
internal class SourceText(val text: String) {
    val bytes: ByteArray = text.encodeToByteArray()

    /** The text under [span], which must lie within this input. */
    fun slice(span: Span): String {
        require(span.start in 0..span.end && span.end <= bytes.size) {
            "span $span is outside an input of ${bytes.size} bytes"
        }
        return bytes.decodeToString(span.start, span.end)
    }
}

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

    fun set(decor: Decor) {
        prefix = decor.prefix?.let(KeptText::from)
        suffix = decor.suffix?.let(KeptText::from)
    }

    fun copy(): RawDecor = RawDecor(prefix, suffix)

    /** Takes [other]'s side wherever this one is left at the default. */
    fun fillDefaultsFrom(other: RawDecor) {
        if (prefix == null) prefix = other.prefix
        if (suffix == null) suffix = other.suffix
    }

    fun prefixEncode(buf: StringBuilder, default: String) {
        val prefix = prefix
        if (prefix != null) encodeRaw(buf, prefix.textOr(default)) else buf.append(default)
    }

    fun suffixEncode(buf: StringBuilder, default: String) {
        val suffix = suffix
        if (suffix != null) encodeRaw(buf, suffix.textOr(default)) else buf.append(default)
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
