package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kotoml.parser.TomlSource
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray

/**
 * An editable TOML document: a [root] table, its sub-tables, and the [trailing] text after the last item.
 *
 * Printing it with [toString] reproduces every comment, blank line, spelling and line ending that was not
 * changed, and the byte order mark the text began with, so an unchanged document prints back byte for byte. What
 * was changed prints in its new form, and an inserted line ends like the line before it. Text read without a final
 * newline keeps it missing after any edit.
 *
 * ```kotlin
 * val doc = TomlDocument.parse("\"hello\" = 'toml!' # comment\n['a'.b]\n").getOrElse { error(it) }
 * doc["a"]["b"]["c"] = Value.InlineTable(InlineTable())
 * doc["a"]["b"]["c"]["d"] = "hello"
 * doc.toString() // "\"hello\" = 'toml!' # comment\n['a'.b]\nc = { d = \"hello\" }\n"
 * ```
 *
 * The keyed operations here act on [root]; everything else a [StandardTable] offers is reached through [root].
 */
public class TomlDocument internal constructor(
    /** The root table, holding the document's top-level keys and, through them, every table. */
    public val root: StandardTable,
    internal var rawTrailing: KeptText,
    internal val lineEnding: String,
    parsedFrom: String?,
) : Iterable<Pair<String, Item>> {

    init {
        // The root prints its values as the document's first lines, never as the parts of a dotted key.
        root.markDotted(false)
        root.isDocumentRoot = true
        root.lineEnding = lineEnding
    }

    // The parser skips a byte order mark at the start of the text, so the document prints it back itself.
    private var byteOrderMark: Boolean = parsedFrom?.startsWith(BYTE_ORDER_MARK) == true

    private var withoutFinalNewline: Boolean =
        parsedFrom != null && parsedFrom.trimStart(BYTE_ORDER_MARK).isNotEmpty() && !parsedFrom.endsWith('\n')

    /** Whether the text the document was read from opens with a blank line, which printing then keeps. */
    internal var readStartingBlank: Boolean = parsedFrom
        ?.removePrefix(BYTE_ORDER_MARK.toString())
        ?.let { text -> '\n' in text && text.substringBefore('\n').isBlank() } == true
        private set

    /** An empty document. */
    public constructor() : this(StandardTable.withPosition(0), KeptText(), "\n", null)

    /** A document whose root is [root], held as is rather than copied; a dotted table stops being dotted. */
    public constructor(root: StandardTable) : this(root, KeptText(), "\n", null)

    /**
     * The version of TOML the document holds and prints: the one it was parsed as, or [TomlVersion.V1_1] for a new
     * document. A document made from a root takes the version of the document that root came from, and a copy the
     * version of its source. [setVersion] changes it.
     *
     * A TOML 1.0 document prints only what TOML 1.0 reads: an edit that would add what only TOML 1.1 reads is refused
     * with [TomlEditError.NotInToml10] and changes nothing. That is a key or string spelled with a `\e` or `\x`
     * escape, an inline table across lines, and a trailing comma in an inline table. A time without seconds, such as
     * `07:32`, is stored with seconds of 0, as [setVersion] gives them, and prints with `:00` after its minutes, which
     * TOML 1.0 reads; the value given to the store keeps no seconds.
     */
    public val version: TomlVersion
        get() = root.version

    /**
     * Makes the document TOML of [version]. To [TomlVersion.V1_0] it changes what only TOML 1.1 reads into what TOML
     * 1.0 reads: a time without seconds takes `:00` after its minutes, a key or string spelled with a `\e` or `\x`
     * escape takes the `\u` escape for it, such as `\u001B` for `\e`, keeping the rest of its spelling, an inline
     * table across lines goes on one line, as [Item.setLayout] puts one with [TableLayout.Inline], and an inline table
     * loses the comma after its last entry. Tables and values stay the same objects. To [TomlVersion.V1_1] nothing
     * changes but the version, since TOML 1.1 reads all of TOML 1.0.
     *
     * ```kotlin
     * val doc = TomlDocument.parse("t = 07:32\ns = \"\\e\"\n").getOrElse { error(it) }
     * doc.setVersion(TomlVersion.V1_0)
     * doc.toString() // "t = 07:32:00\ns = \"\\u001B\"\n"
     * ```
     *
     * @return [TomlEditError.CommentsWouldBeLost] when putting an inline table on one line would drop a comment and
     *   [force] is false, changing nothing: it names the first inline table that would drop one and counts every
     *   comment the change would drop. With [force] true the comments go.
     */
    public fun setVersion(
        version: TomlVersion,
        force: Boolean = false,
    ): EmptyResult<TomlEditError> {
        if (version == TomlVersion.V1_0 && this.version == TomlVersion.V1_1) {
            if (!force) toml10Conversion(copy().root).lostComments()?.let { return Err(it) }
            toml10Conversion(root)
        }
        root.version = version
        return Ok(Unit)
    }

    /** The root table's key/item pairs, in order. */
    override fun iterator(): Iterator<Pair<String, Item>> = root.iterator()

    /**
     * The text after the last item, such as comments and blank lines at the end of the file. Text ending in a line
     * break gives a document read without a final newline one.
     */
    public var trailing: String
        get() = rawTrailing.textOr("")
        set(value) {
            rawTrailing = KeptText.from(value)
            if (value.endsWith('\n')) withoutFinalNewline = false
        }

    /** The item at [key] of the root table, or [TomlEditError.NotFound]; see [StandardTable.get]. */
    public operator fun get(key: String): Result<Item, TomlEditError> = root[key]

    /** Stores a copy of [item] at [key] of the root table; see [StandardTable.set]. */
    public operator fun set(
        key: String,
        item: Item,
    ): EmptyResult<TomlEditError> = root.set(key, item)

    /** Stores a copy of [value] at [key] of the root table; see [StandardTable.set]. */
    public operator fun set(
        key: String,
        value: Value,
    ): EmptyResult<TomlEditError> = root.set(key, value)

    /** Stores a copy of [table] at [key] of the root table; see [StandardTable.set]. */
    public operator fun set(
        key: String,
        table: StandardTable,
    ): EmptyResult<TomlEditError> = root.set(key, table)

    /** Stores a copy of [arrayOfTables] at [key] of the root table; see [StandardTable.set]. */
    public operator fun set(
        key: String,
        arrayOfTables: ArrayOfTables,
    ): EmptyResult<TomlEditError> = root.set(key, arrayOfTables)

    /** The item at [key] of the root table, or null; see [StandardTable.getOrNull]. */
    public fun getOrNull(key: String): Item? = root.getOrNull(key)

    /** Whether [key] of the root table holds an item. */
    public fun containsKey(key: String): Boolean = root.containsKey(key)

    /** Whether [key] of the root table holds an item, for `key in document`. */
    public operator fun contains(key: String): Boolean = root.containsKey(key)

    /**
     * Sets [key] of the root table to a copy of [item], keeping the key's formatting, and returns the item it held;
     * see [StandardTable.put].
     */
    public fun put(
        key: String,
        item: Item,
    ): Result<Item?, TomlEditError> = root.put(key, item)

    /**
     * Sets [key] of the root table, with its spelling and decor, to a copy of [item], and returns the item it held;
     * see [StandardTable.put].
     */
    public fun put(
        key: Key,
        item: Item,
    ): Result<Item?, TomlEditError> = root.put(key, item)

    /** Stores a copy of every pair of [pairs] in the root table, in order; see [StandardTable.putAll]. */
    public fun putAll(pairs: Iterable<Pair<String, Item>>): EmptyResult<TomlEditError> = root.putAll(pairs)

    /**
     * Removes [key] from the root table and returns its item, or null when it held none. The comment and blank
     * lines above the key, or above a table's first header, stay where they print; see [StandardTable.remove].
     */
    public fun remove(key: String): Item? = root.remove(key)

    /**
     * Renames the key [old] of the root table to [new], keeping its place, comments, spacing and item, and the
     * headers of the tables under it; see [StandardTable.rename].
     *
     * @return [TomlEditError.NotFound] when there is no [old], or [TomlEditError.KeyTaken] when [new] is another key
     *   already there; either changes nothing.
     */
    public fun rename(
        old: String,
        new: String,
    ): EmptyResult<TomlEditError> = root.rename(old, new)

    /**
     * Keeps only the root table's pairs [predicate] accepts, and returns whether any was removed; see
     * [StandardTable.retainAll].
     */
    public fun retainAll(predicate: (Pair<String, Item>) -> Boolean): Boolean = root.retainAll(predicate)

    /**
     * Removes the root table's pairs [predicate] accepts, and returns whether any was; see
     * [StandardTable.removeAll].
     */
    public fun removeAll(predicate: (Pair<String, Item>) -> Boolean): Boolean = root.removeAll(predicate)

    /** Removes every key of the root table, keeping the lines above each; see [StandardTable.clear]. */
    public fun clear() {
        root.clear()
    }

    /** The item at [key] of the root table, first storing what [default] makes; see [StandardTable.getOrPut]. */
    public fun getOrPut(
        key: String,
        default: () -> Item,
    ): Result<Item, TomlEditError> = root.getOrPut(key, default)

    /** As [getOrPut], an inserted key keeping the spelling and decor of [key]; see [StandardTable.getOrPut]. */
    public fun getOrPut(
        key: Key,
        default: () -> Item,
    ): Result<Item, TomlEditError> = root.getOrPut(key, default)

    /** The keys of the root table holding an item, in order, as a copy; see [StandardTable.keys]. */
    public val keys: Set<String>
        get() = root.keys

    /** The number of keys of the root table holding an item. */
    public val size: Int
        get() = root.size

    /** Whether the root table holds no items. */
    public fun isEmpty(): Boolean = root.isEmpty()

    /** Whether the root table holds an item. */
    public fun isNotEmpty(): Boolean = root.isNotEmpty()

    /** The text before the document's first line and after its last; see [StandardTable.decor]. */
    public var decor: Decor
        get() = root.decor
        set(value) {
            root.decor = value
        }

    /** A deep copy of the whole document, which keeps every comment, its version and its line ending. */
    public fun copy(): TomlDocument {
        val copy = TomlDocument(root.deepCopy(), rawTrailing, lineEnding, null)
        copy.byteOrderMark = byteOrderMark
        copy.withoutFinalNewline = withoutFinalNewline
        copy.readStartingBlank = readStartingBlank
        return copy
    }

    /**
     * Writes the document, as [toString] prints it, to [sink] in UTF-8 and flushes the sink. The sink stays
     * open.
     *
     * ```kotlin
     * SystemFileSystem.sink(Path("config.toml")).buffered().use { document.writeTo(it) }
     * ```
     *
     * @return [TomlWriteError.NotUnicode] when the text holds half of a surrogate pair, which UTF-8 cannot encode;
     *   [TomlWriteError.TooDeep] when the document nests deeper than the parser reads; or [TomlIoError] when the sink
     *   fails to take the text or is closed. Nothing is written on the first two.
     */
    public fun writeTo(sink: Sink): EmptyResult<TomlWriteError> {
        settleHeadersForDepth(root)
        tooDeepPathAtTop(root)?.let { return Err(TomlWriteError.TooDeep(it)) }
        val text = toString()
        if (text.hasLoneSurrogate()) return Err(TomlWriteError.NotUnicode(root.loneSurrogatePath()))
        return try {
            sink.write(text.encodeToByteArray())
            sink.flush()
            Ok(Unit)
        } catch (error: IOException) {
            Err(TomlIoError(error.message))
        } catch (error: IllegalStateException) {
            Err(TomlIoError(error.message))
        }
    }

    /** The document as TOML text, in its [version]. */
    override fun toString(): String {
        val buf = StringBuilder()
        if (byteOrderMark) buf.append(BYTE_ORDER_MARK)
        encodeDocument(buf, this)
        val text = buf.toString()
        if (!withoutFinalNewline) return text
        return text.removeSuffix("\n").let { if (it.length < text.length) it.removeSuffix("\r") else it }
    }

    public companion object {
        /**
         * How deeply arrays, inline tables and the dots of keys may nest, counted together; deeper TOML is refused
         * when parsed.
         */
        public const val NESTING_LIMIT: Int = 80

        /** Parses [text] as TOML of [version] into an editable document, failing at the first error. */
        public fun parse(
            text: String,
            version: TomlVersion = TomlVersion.V1_1,
        ): Result<TomlDocument, TomlParseError> {
            loneSurrogateError(text)?.let { return Err(it) }
            val errors = TomlSink(text)
            val document = parseDocumentModel(TomlSource(text), errors, version)
            errors.error?.let { return Err(it) }
            return Ok(document)
        }

        /**
         * Parses [bytes] as UTF-8 TOML of [version] into an editable document, failing on bytes that are not
         * UTF-8 as on the first TOML error.
         */
        public fun parse(
            bytes: ByteArray,
            version: TomlVersion = TomlVersion.V1_1,
        ): Result<TomlDocument, TomlParseError> {
            utf8Error(bytes)?.let { reason ->
                val end = Span(reason.byteOffset, reason.byteOffset).toTextIndices(bytes)
                val (line, column) = translatePosition(bytes, reason.byteOffset)
                return Err(TomlParseError(reason, end, TextPosition(line + 1, column + 1)))
            }
            return parse(bytes.decodeToString(), version)
        }

        /**
         * Reads [source] to its end and parses what it held as UTF-8 TOML of [version]. The source stays open.
         *
         * ```kotlin
         * val read = SystemFileSystem.source(Path("config.toml")).buffered().use { TomlDocument.read(it) }
         * ```
         *
         * @return [TomlIoError] when reading fails or the source is closed, or [TomlReadError.Parse] when the bytes
         *   are not TOML.
         */
        public fun read(
            source: Source,
            version: TomlVersion = TomlVersion.V1_1,
        ): Result<TomlDocument, TomlReadError> {
            val bytes = try {
                source.readByteArray()
            } catch (error: IOException) {
                return Err(TomlIoError(error.message))
            } catch (error: IllegalStateException) {
                return Err(TomlIoError(error.message))
            }
            return when (val parsed = parse(bytes, version)) {
                is Result.Success -> parsed
                is Result.Error -> Err(TomlReadError.Parse(parsed.error, bytes.decodeToString()))
            }
        }
    }
}

private const val BYTE_ORDER_MARK: Char = '\uFEFF'

/** Whether this holds half of a surrogate pair without the other half, which no Unicode encoding can write. */
internal fun String.hasLoneSurrogate(): Boolean = loneSurrogateIndex() >= 0

/** Where this holds the first half of a surrogate pair without the other half, or -1 when it holds none. */
internal fun String.loneSurrogateIndex(): Int {
    var index = 0
    while (index < length) {
        val char = this[index]
        when {
            char.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> index += 2
            char.isSurrogate() -> return index
            else -> index++
        }
    }
    return -1
}

/**
 * The keys leading to the first key or string under this table, in the order the tables hold them, that holds half
 * of a surrogate pair, the key holding it last; or null when there is none. Array indices are left out.
 */
private fun Table.loneSurrogatePath(): List<String>? {
    for ((key, item) in entries) {
        val inside = if (key.hasLoneSurrogate()) emptyList() else item.loneSurrogatePath()
        if (inside != null) return listOf(key) + inside
    }
    return null
}

private fun Item.loneSurrogatePath(): List<String>? {
    standardTableOrNull?.let { return it.loneSurrogatePath() }
    arrayOfTablesOrNull?.let { tables -> return tables.firstNotNullOfOrNull { it.loneSurrogatePath() } }
    return valueOrNull?.loneSurrogatePath()
}

private fun Value.loneSurrogatePath(): List<String>? = when (this) {
    is Value.String -> if (value.hasLoneSurrogate()) emptyList() else null
    is Value.Array -> value.firstNotNullOfOrNull { it.loneSurrogatePath() }
    is Value.InlineTable -> value.loneSurrogatePath()
    is Value.Integer, is Value.Float, is Value.Boolean, is Value.Datetime -> null
}
