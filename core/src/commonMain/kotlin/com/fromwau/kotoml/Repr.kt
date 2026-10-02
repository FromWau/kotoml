package com.fromwau.kotoml

import com.fromwau.kotoml.writer.TomlStringBuilder
import com.fromwau.kotoml.writer.WriteTomlValue
import com.fromwau.kotoml.writer.toTomlValue

/** Raw TOML text kept for formatting: text you set, or a span into a parse's input until it is resolved. */
internal class RawString private constructor(private val state: State) {

    private sealed interface State {
        data object Empty : State

        data class Explicit(val text: String) : State

        data class Spanned(val span: Span) : State
    }

    constructor() : this(State.Empty)

    /** The text, or null while it is still only a span into a parse's input. */
    fun asStr(): String? = when (state) {
        State.Empty -> ""
        is State.Explicit -> state.text
        is State.Spanned -> null
    }

    fun span(): Span? = (state as? State.Spanned)?.span

    fun toStr(input: SourceText): String = when (state) {
        State.Empty -> ""
        is State.Explicit -> state.text
        is State.Spanned -> input.slice(state.span)
    }

    /** The text, or [default] while it is only a span. */
    fun toStrWithDefault(default: String): String = when (state) {
        State.Empty -> ""
        is State.Explicit -> state.text
        is State.Spanned -> default
    }

    fun despan(input: SourceText): RawString = when (state) {
        is State.Spanned -> if (state.span.length <= 0) RawString() else from(input.slice(state.span))
        else -> this
    }

    override fun equals(other: Any?): Boolean = other is RawString && state == other.state

    override fun hashCode(): Int = state.hashCode()

    /** The text as given, or `start..end` for a span. */
    override fun toString(): String = when (state) {
        State.Empty -> ""
        is State.Explicit -> state.text
        is State.Spanned -> state.span.toString()
    }

    companion object {
        /** Holds [text]; an empty string becomes the empty raw string. */
        fun from(text: String): RawString = if (text.isEmpty()) RawString() else RawString(State.Explicit(text))

        fun withSpan(start: Int, end: Int): RawString = RawString(State.Spanned(Span(start, end)))

        fun withSpan(span: Span): RawString = RawString(State.Spanned(span))
    }
}

/** The input of a parse, kept as text and as the UTF-8 bytes every span counts. */
internal class SourceText(val text: String) {
    val bytes: ByteArray = text.encodeToByteArray()

    /** The text under [span], or the empty string when the span does not fit this input. */
    fun slice(span: Span): String {
        val start = span.start
        val end = span.end
        if (start < 0 || end > bytes.size || start > end) return ""
        return bytes.decodeToString(start, end)
    }
}

/** How a value or key is spelled in TOML, such as `'value'` for the string `value` or `0x10` for 16. */
internal class Repr(private val rawValue: RawString) {

    fun asRaw(): RawString = rawValue

    fun span(): Span? = rawValue.span()

    fun despan(input: SourceText): Repr = Repr(rawValue.despan(input))

    override fun equals(other: Any?): Boolean = other is Repr && rawValue == other.rawValue

    override fun hashCode(): Int = rawValue.hashCode()

    override fun toString(): String = rawValue.toString()

    companion object {
        fun newUnchecked(raw: String): Repr = Repr(RawString.from(raw))

        fun newUnchecked(raw: RawString): Repr = Repr(raw)
    }
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
    var prefix: RawString? = null,
    var suffix: RawString? = null,
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
        prefix = prefix?.asStr(),
        suffix = suffix?.asStr(),
    )

    fun set(decor: Decor) {
        prefix = decor.prefix?.let(RawString::from)
        suffix = decor.suffix?.let(RawString::from)
    }

    fun copy(): RawDecor = RawDecor(prefix, suffix)

    fun prefixEncode(buf: StringBuilder, default: String) {
        val prefix = prefix
        if (prefix != null) encodeRaw(buf, prefix.toStrWithDefault(default)) else buf.append(default)
    }

    fun suffixEncode(buf: StringBuilder, default: String) {
        val suffix = suffix
        if (suffix != null) encodeRaw(buf, suffix.toStrWithDefault(default)) else buf.append(default)
    }

    fun despan(input: SourceText) {
        prefix = prefix?.despan(input)
        suffix = suffix?.despan(input)
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
 * A scalar value together with its [repr] (how it is spelled) and its [decor] (the text around it).
 *
 * One made with [Formatted.of] has no repr, so it prints in the default spelling until it comes from parsed text.
 */
internal class Formatted<T : Any> private constructor(
    val value: T,
    var repr: Repr?,
    val decor: RawDecor,
    private val toRepr: (T) -> Repr,
) {

    /** The spelling the value gets when it has none of its own. */
    fun defaultRepr(): Repr = toRepr(value)

    /** The spelling as written, or null when it has none or it is still a span. */
    fun spelling(): String? = repr?.asRaw()?.asStr()

    /** The spelling printed: the original one when there is one, otherwise the default. */
    fun displayRepr(): String = spelling() ?: defaultRepr()
        .asRaw()
        .asStr()
        .orEmpty()

    fun span(): Span? = repr?.span()

    fun despan(input: SourceText) {
        decor.despan(input)
        repr = repr?.despan(input)
    }

    fun copy(): Formatted<T> = Formatted(value, repr, decor.copy(), toRepr)

    override fun equals(other: Any?): Boolean {
        if (other !is Formatted<*>) return false
        return sameValue(value, other.value) && repr == other.repr && decor == other.decor
    }

    override fun hashCode(): Int = 31 * (31 * value.hashCode() + repr.hashCode()) + decor.hashCode()

    override fun toString(): String = buildString { encodeFormatted(this, this@Formatted, "", "") }

    companion object {
        fun of(value: String): Formatted<String> = Formatted(value, null, RawDecor(), ::stringRepr)

        fun of(value: Long): Formatted<Long> = Formatted(value, null, RawDecor(), ::integerRepr)

        /** The sign of a NaN is kept, and may print as `-nan`. */
        fun of(value: Double): Formatted<Double> = Formatted(value, null, RawDecor(), ::floatRepr)

        fun of(value: Boolean): Formatted<Boolean> = Formatted(value, null, RawDecor(), ::booleanRepr)

        fun of(value: Datetime): Formatted<Datetime> = Formatted(value, null, RawDecor(), ::datetimeRepr)
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

private fun stringRepr(value: String): Repr = Repr.newUnchecked(TomlStringBuilder(value).asDefault().toTomlValue())

private fun integerRepr(value: Long): Repr = Repr.newUnchecked(value.toString())

private fun floatRepr(value: Double): Repr = Repr.newUnchecked(WriteTomlValue.of(value).toTomlValue())

private fun booleanRepr(value: Boolean): Repr = Repr.newUnchecked(value.toString())

private fun datetimeRepr(value: Datetime): Repr = Repr.newUnchecked(value.toString())
