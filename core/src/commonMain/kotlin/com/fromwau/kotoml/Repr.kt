package com.fromwau.kotoml

import com.fromwau.kotoml.parser.Span
import com.fromwau.kotoml.writer.TomlStringBuilder
import com.fromwau.kotoml.writer.WriteTomlValue
import com.fromwau.kotoml.writer.toTomlValue

/**
 * Raw TOML text kept for formatting: whitespace, comments, or the spelling of a key or value.
 *
 * Text from a parsed [Document] is held as a [span] into the document until the document becomes a
 * [DocumentMut]; from then on, and for text you set yourself, [asStr] returns it.
 */
public class RawString private constructor(private val state: State) {

    private sealed interface State {
        data object Empty : State

        data class Explicit(val text: String) : State

        data class Spanned(val span: Span) : State
    }

    /** No text, upstream's `RawString::default()`. */
    public constructor() : this(State.Empty)

    /** The text, or null while it is still only a span into a [Document]'s input. */
    public fun asStr(): String? = when (state) {
        State.Empty -> ""
        is State.Explicit -> state.text
        is State.Spanned -> null
    }

    /** Where the text lies in the original document, while it is held as a span. */
    public fun span(): Span? = (state as? State.Spanned)?.span

    internal fun isSpanned(): Boolean = state is State.Spanned

    internal fun toStr(input: SourceText): String = when (state) {
        State.Empty -> ""
        is State.Explicit -> state.text
        is State.Spanned -> input.slice(state.span)
    }

    /** The text, or [default] while it is only a span. */
    internal fun toStrWithDefault(default: String): String = when (state) {
        State.Empty -> ""
        is State.Explicit -> state.text
        is State.Spanned -> default
    }

    internal fun despan(input: SourceText): RawString = when (state) {
        is State.Spanned -> if (state.span.isEmpty()) RawString() else from(input.slice(state.span))
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

    public companion object {
        /** Holds [text]; an empty string becomes the empty raw string. */
        public fun from(text: String): RawString = if (text.isEmpty()) RawString() else RawString(State.Explicit(text))

        internal fun withSpan(start: Int, end: Int): RawString = RawString(State.Spanned(Span(start, end)))

        internal fun withSpan(span: Span): RawString = RawString(State.Spanned(span))
    }
}

/** The input of a parse, kept as text and as the UTF-8 bytes every span counts. */
internal class SourceText(val text: String) {
    val bytes: ByteArray = text.encodeToByteArray()

    /** The text under [span], or the empty string when the span does not fit this input. */
    fun slice(span: Span): String {
        val start = span.start()
        val end = span.end()
        if (start < 0 || end > bytes.size || start > end) return ""
        return bytes.decodeToString(start, end)
    }
}

/**
 * How a value or key is spelled in TOML, such as `'value'` for the string `value` or `0x10` for 16.
 */
public class Repr internal constructor(private val rawValue: RawString) {

    /** The spelling. */
    public fun asRaw(): RawString = rawValue

    /** Where the spelling lies in the original document; see [RawString.span]. */
    public fun span(): Span? = rawValue.span()

    internal fun despan(input: SourceText): Repr = Repr(rawValue.despan(input))

    override fun equals(other: Any?): Boolean = other is Repr && rawValue == other.rawValue

    override fun hashCode(): Int = rawValue.hashCode()

    override fun toString(): String = rawValue.toString()

    internal companion object {
        fun newUnchecked(raw: String): Repr = Repr(RawString.from(raw))

        fun newUnchecked(raw: RawString): Repr = Repr(raw)
    }
}

/**
 * The text around an item: its prefix (leading whitespace, comments and newlines) and its suffix.
 *
 * A side that was never set is `null` and prints the default for where the item stands, such as one space
 * before a value after `=`.
 */
public class Decor() {
    private var prefix: RawString? = null
    private var suffix: RawString? = null

    /** A decor with both sides set. */
    public constructor(prefix: RawString, suffix: RawString) : this() {
        this.prefix = prefix
        this.suffix = suffix
    }

    /** A decor with both sides set to the given text. */
    public constructor(prefix: String, suffix: String) : this(RawString.from(prefix), RawString.from(suffix))

    /** Goes back to the defaults on both sides. */
    public fun clear() {
        prefix = null
        suffix = null
    }

    /** The prefix, or null for the default. */
    public fun prefix(): RawString? = prefix

    /** Sets the prefix. */
    public fun setPrefix(prefix: RawString) {
        this.prefix = prefix
    }

    /** Sets the prefix to [prefix]. */
    public fun setPrefix(prefix: String) {
        this.prefix = RawString.from(prefix)
    }

    /** The suffix, or null for the default. */
    public fun suffix(): RawString? = suffix

    /** Sets the suffix. */
    public fun setSuffix(suffix: RawString) {
        this.suffix = suffix
    }

    /** Sets the suffix to [suffix]. */
    public fun setSuffix(suffix: String) {
        this.suffix = RawString.from(suffix)
    }

    /** Makes this decor equal to [other], Rust's `*decor = other`. */
    public fun assign(other: Decor) {
        prefix = other.prefix
        suffix = other.suffix
    }

    /** A copy of this decor. */
    public fun clone(): Decor = Decor().also { it.assign(this) }

    internal fun prefixEncode(buf: StringBuilder, default: String) {
        val prefix = prefix
        if (prefix != null) encodeRaw(buf, prefix.toStrWithDefault(default)) else buf.append(default)
    }

    internal fun suffixEncode(buf: StringBuilder, default: String) {
        val suffix = suffix
        if (suffix != null) encodeRaw(buf, suffix.toStrWithDefault(default)) else buf.append(default)
    }

    internal fun despan(input: SourceText) {
        prefix = prefix?.despan(input)
        suffix = suffix?.despan(input)
    }

    override fun equals(other: Any?): Boolean = other is Decor && prefix == other.prefix && suffix == other.suffix

    override fun hashCode(): Int = 31 * prefix.hashCode() + suffix.hashCode()

    override fun toString(): String = "Decor(prefix=${prefix ?: "default"}, suffix=${suffix ?: "default"})"

    internal companion object {
        val EMPTY: Decor = Decor()
    }
}

internal fun encodeRaw(buf: StringBuilder, raw: String) {
    buf.append(raw)
}

/**
 * A scalar value together with its [Repr] (how it is spelled) and its [Decor] (the text around it).
 *
 * Make one with [Formatted.of]; it starts with no repr, so it prints in the default spelling until it comes
 * from parsed text.
 */
public class Formatted<T : Any> private constructor(
    private val value: T,
    private var repr: Repr?,
    private val decor: Decor,
    private val toRepr: (T) -> Repr,
) {

    internal fun setReprUnchecked(repr: Repr) {
        this.repr = repr
    }

    /** The value. */
    public fun value(): T = value

    /** The value, upstream's `into_value`. */
    public fun intoValue(): T = value

    /** How the value was spelled, if it came from parsed text. */
    public fun asRepr(): Repr? = repr

    /** The spelling the value gets when it has none of its own. */
    public fun defaultRepr(): Repr = toRepr(value)

    /** The spelling printed: the original one when there is one, otherwise the default. */
    public fun displayRepr(): String = repr?.asRaw()?.asStr() ?: defaultRepr().asRaw().asStr().orEmpty()

    /** Where the value lies in the original document; see [RawString.span]. */
    public fun span(): Span? = repr?.span()

    internal fun despan(input: SourceText) {
        decor.despan(input)
        repr = repr?.despan(input)
    }

    /** The text around the value, to change. */
    public fun decorMut(): Decor = decor

    /** The text around the value. */
    public fun decor(): Decor = decor

    /** Drops the original spelling so the value prints in its default one. */
    public fun fmt() {
        repr = null
    }

    /** A copy of this value with its repr and decor. */
    public fun clone(): Formatted<T> = Formatted(value, repr, decor.clone(), toRepr)

    override fun equals(other: Any?): Boolean {
        if (other !is Formatted<*>) return false
        return sameValue(value, other.value) && repr == other.repr && decor == other.decor
    }

    override fun hashCode(): Int = 31 * (31 * value.hashCode() + repr.hashCode()) + decor.hashCode()

    /** The value as TOML, with its decor. */
    override fun toString(): String = buildString { encodeFormatted(this, this@Formatted, "", "") }

    public companion object {
        /** A string value in its default spelling. */
        public fun of(value: String): Formatted<String> = Formatted(value, null, Decor(), ::stringRepr)

        /** An integer value in its default spelling. */
        public fun of(value: Long): Formatted<Long> = Formatted(value, null, Decor(), ::integerRepr)

        /** A float value in its default spelling. The sign of a NaN is kept, and may print as `-nan`. */
        public fun of(value: Double): Formatted<Double> = Formatted(value, null, Decor(), ::floatRepr)

        /** A boolean value in its default spelling. */
        public fun of(value: Boolean): Formatted<Boolean> = Formatted(value, null, Decor(), ::booleanRepr)

        /** A date-time value in its default spelling. */
        public fun of(value: Datetime): Formatted<Datetime> = Formatted(value, null, Decor(), ::datetimeRepr)
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
