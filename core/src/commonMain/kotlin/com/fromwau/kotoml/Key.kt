package com.fromwau.kotoml

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kotoml.parser.TomlSource
import com.fromwau.kotoml.writer.TomlKeyBuilder
import com.fromwau.kotoml.writer.toTomlKey

/**
 * A key of a key/value pair, in a [StandardTable] or an [InlineTable], with how it was spelled.
 *
 * TOML has bare keys (`version`), basic quoted keys (`"basic key"`) and literal quoted keys (`'literal'`).
 * A key compares, hashes and orders by its text alone; its spelling and decor do not take part.
 *
 * The leaf decor is the text around the whole key where it ends a key/value line or a header; the dotted
 * decor is the text around this key's part of a dotted key, between the dots.
 *
 * @param leafDecor the text around the whole key; a `null` side prints the default spacing for where it stands.
 * @param dottedDecor the text around this key's part of a dotted key.
 */
public class Key(
    /** The key's text, as it reads after unquoting. */
    public val name: String,
    leafDecor: Decor = Decor(),
    dottedDecor: Decor = Decor(),
) : Comparable<Key> {
    internal var written: Spelling? = null
    internal val rawLeafDecor: RawDecor = RawDecor()
    internal val rawDottedDecor: RawDecor = RawDecor()

    init {
        rawLeafDecor.set(leafDecor)
        rawDottedDecor.set(dottedDecor)
    }

    internal fun withSpelling(spelling: Spelling): Key {
        this.written = spelling
        return this
    }

    /** The text the key was written as, such as `"a b"` for the key `a b`, or null when it has none of its own. */
    public val spelling: String?
        get() = written?.raw?.textOrNull

    /** The text the key prints as: its [spelling], or the default one, bare when it can be. */
    public val displaySpelling: String
        get() = spelling ?: defaultSpelling()
            .raw
            .textOrNull
            .orEmpty()

    internal fun defaultSpelling(): Spelling = Spelling(
        TomlKeyBuilder(name)
            .asDefault()
            .toTomlKey(),
    )

    /** The text around the key where it ends a key/value line or a table header. */
    public var leafDecor: Decor
        get() = rawLeafDecor.toDecor()
        set(value) {
            rawLeafDecor.set(value)
        }

    /** The text around this key's part of a dotted key, between the dots. */
    public var dottedDecor: Decor
        get() = rawDottedDecor.toDecor()
        set(value) {
            rawDottedDecor.set(value)
        }

    internal val span: Span?
        get() = written?.span

    internal fun resolveSpans(input: SourceText) {
        rawLeafDecor.resolveSpans(input)
        rawDottedDecor.resolveSpans(input)
        written = written?.resolveSpans(input)
    }

    /** Drops the key's spelling and decor so it prints in its default form. */
    public fun resetFormatting() {
        written = null
        rawLeafDecor.clear()
        rawDottedDecor.clear()
    }

    /** A copy of this key with its spelling and decor. */
    public fun copy(): Key = Key(name).also {
        it.written = written
        it.rawLeafDecor.assign(rawLeafDecor)
        it.rawDottedDecor.assign(rawDottedDecor)
    }

    override fun compareTo(other: Key): Int = compareByCodePoint(name, other.name)

    override fun equals(other: Any?): Boolean = other is Key && name == other.name

    override fun hashCode(): Int = name.hashCode()

    /** The key as TOML, in its own spelling or the default one. */
    override fun toString(): String = buildString { encodeKey(this, this@Key) }

    public companion object {
        /**
         * Parses one undotted key, bare or quoted, keeping its spelling: `"a b"` gives the key `a b`.
         *
         * @return the key, or the parse error when [text] is not exactly one key.
         */
        public fun parse(
            text: String,
            version: TomlVersion = TomlVersion.V1_1,
        ): Result<Key, TomlParseError> {
            val source = TomlSource(text)
            val sink = TomlSink(text)
            val key = parseSimpleKeyItem(source, sink, version)
            sink.error?.let { return Err(it) }
            key.resolveSpans(SourceText(text))
            return Ok(key)
        }

        /**
         * Parses a key path, dotted or not, into its parts: `a."b c".d` gives three keys. Whitespace around the
         * parts and dots is allowed and kept as each key's dotted decor.
         */
        public fun parsePath(
            text: String,
            version: TomlVersion = TomlVersion.V1_1,
        ): Result<List<Key>, TomlParseError> {
            val source = TomlSource(text)
            val sink = TomlSink(text)
            val keys = parseKeyPath(source, sink, version)
            sink.error?.let { return Err(it) }
            val input = SourceText(text)
            for (key in keys) key.resolveSpans(input)
            return Ok(keys)
        }
    }
}

/**
 * Orders by code point, as byte-wise UTF-8 comparison does. Kotlin's `compareTo` orders by UTF-16 unit, which
 * puts U+E000..U+FFFF after the supplementary planes.
 */
internal fun compareByCodePoint(left: String, right: String): Int {
    var leftIndex = 0
    var rightIndex = 0
    while (leftIndex < left.length && rightIndex < right.length) {
        val leftCode = codePointAt(left, leftIndex)
        val rightCode = codePointAt(right, rightIndex)
        if (leftCode != rightCode) return leftCode.compareTo(rightCode)
        leftIndex += if (leftCode > 0xFFFF) 2 else 1
        rightIndex += if (rightCode > 0xFFFF) 2 else 1
    }
    return (left.length - leftIndex).compareTo(right.length - rightIndex)
}

private fun codePointAt(text: String, index: Int): Int {
    val high = text[index]
    if (high.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
        return 0x10000 + ((high.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00)
    }
    return high.code
}
