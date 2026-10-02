package com.fromwau.kotoml

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kotoml.parser.TomlSource
import com.fromwau.kotoml.writer.TomlKeyBuilder
import com.fromwau.kotoml.writer.toTomlKey

/**
 * A key of a key/value pair, in a [Table] or an [InlineTable], with how it was spelled.
 *
 * TOML has bare keys (`version`), basic quoted keys (`"basic key"`) and literal quoted keys (`'literal'`).
 * A key compares, hashes and orders by its text alone; its spelling and decor do not take part.
 *
 * The leaf decor is the text around the whole key where it ends a key/value line or a header; the dotted
 * decor is the text around this key's part of a dotted key, between the dots.
 */
public class Key(private val key: String) : Comparable<Key> {
    internal var repr: Repr? = null
    internal val rawLeafDecor: RawDecor = RawDecor()
    internal val rawDottedDecor: RawDecor = RawDecor()

    internal fun withReprUnchecked(repr: Repr): Key {
        this.repr = repr
        return this
    }

    /** A view that can change this key's formatting but not its text. */
    public fun asMut(): KeyMut = KeyMut(this)

    /** The key's text. */
    public fun get(): String = key

    /** The text the key was written as, such as `"a b"` for the key `a b`, or null when it has none of its own. */
    public val spelling: String?
        get() = repr?.asRaw()?.asStr()

    /** The text the key prints as: its [spelling], or the default one, bare when it can be. */
    public val displaySpelling: String
        get() = spelling ?: defaultRepr()
            .asRaw()
            .asStr()
            .orEmpty()

    internal fun defaultRepr(): Repr = Repr.newUnchecked(
        TomlKeyBuilder(key)
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

    internal fun span(): Span? = repr?.span()

    internal fun despan(input: SourceText) {
        rawLeafDecor.despan(input)
        rawDottedDecor.despan(input)
        repr = repr?.despan(input)
    }

    /** Drops the key's spelling and decor so it prints in its default form. */
    public fun fmt() {
        repr = null
        rawLeafDecor.clear()
        rawDottedDecor.clear()
    }

    /** A copy of this key with its spelling and decor. */
    public fun clone(): Key = Key(key).also {
        it.repr = repr
        it.rawLeafDecor.assign(rawLeafDecor)
        it.rawDottedDecor.assign(rawDottedDecor)
    }

    override fun compareTo(other: Key): Int = compareRustStr(key, other.key)

    override fun equals(other: Any?): Boolean = other is Key && key == other.key

    override fun hashCode(): Int = key.hashCode()

    /** The key as TOML, in its own spelling or the default one. */
    override fun toString(): String = buildString { encodeKey(this, this@Key) }

    public companion object {
        /**
         * Parses a key expression, dotted or not, into its parts: `a."b c".d` gives three keys.
         *
         * Unlike [parseSimple], whitespace around the parts and dots is allowed and kept as decor.
         */
        public fun parse(
            repr: String,
            version: TomlVersion = TomlVersion.V1_1,
        ): Result<List<Key>, TomlParseError> {
            val source = TomlSource(repr)
            val sink = TomlSink(repr)
            val keys = parseKeyPath(source, sink, version)
            sink.error()?.let { return Err(it) }
            val input = SourceText(repr)
            for (key in keys) key.despan(input)
            return Ok(keys)
        }

        /** Parses one undotted key, bare or quoted: upstream's `FromStr for Key`. */
        public fun parseSimple(
            repr: String,
            version: TomlVersion = TomlVersion.V1_1,
        ): Result<Key, TomlParseError> {
            val source = TomlSource(repr)
            val sink = TomlSink(repr)
            val key = parseSimpleKeyItem(source, sink, version)
            sink.error()?.let { return Err(it) }
            key.despan(SourceText(repr))
            return Ok(key)
        }
    }
}

/** A view of a [Key] that can change its formatting but not its text. */
public class KeyMut internal constructor(private val key: Key) : Comparable<KeyMut> {

    /** The key's text. */
    public fun get(): String = key.get()

    /** The text the key was written as, or null when it has none of its own; see [Key.spelling]. */
    public val spelling: String?
        get() = key.spelling

    /** The text the key prints as; see [Key.displaySpelling]. */
    public val displaySpelling: String
        get() = key.displaySpelling

    /** The text around the key where it ends a key/value line or a table header. */
    public var leafDecor: Decor
        get() = key.leafDecor
        set(value) {
            key.leafDecor = value
        }

    /** The text around this key's part of a dotted key, between the dots. */
    public var dottedDecor: Decor
        get() = key.dottedDecor
        set(value) {
            key.dottedDecor = value
        }

    /** Drops the key's spelling and decor so it prints in its default form. */
    public fun fmt() {
        key.fmt()
    }

    override fun compareTo(other: KeyMut): Int = key.compareTo(other.key)

    override fun equals(other: Any?): Boolean = other is KeyMut && key == other.key

    override fun hashCode(): Int = key.hashCode()

    /** The key as TOML, in its own spelling or the default one. */
    override fun toString(): String = key.toString()
}

/**
 * Rust's `Ord for str`: byte-wise over UTF-8, which orders by code point. Kotlin's `compareTo` orders by
 * UTF-16 unit, which puts U+E000..U+FFFF after the supplementary planes.
 */
internal fun compareRustStr(left: String, right: String): Int {
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
