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
 * was changed prints in its new form, and an inserted line ends like the line before it. Text without a final
 * newline keeps that only while the document prints as it was read; once changed it ends with a newline.
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
        root.setDotted(false)
        root.isDocumentRoot = true
    }

    // The parser skips a byte order mark at the start of the text, so the document prints it back itself.
    private var byteOrderMark: Boolean = parsedFrom?.startsWith(BYTE_ORDER_MARK) == true

    // Text read without a final newline, kept to print it back while the document prints as it did when read.
    private var readWithoutFinalNewline: ReadText? = parsedFrom
        ?.takeIf { !it.endsWith('\n') }
        ?.let { text -> ReadText(text, render()) }
        ?.takeIf { it.printed == it.text + "\n" || it.printed == it.text + "\r\n" }

    /** An empty document. */
    public constructor() : this(StandardTable.withPosition(0), KeptText(), "\n", null)

    /** A document whose root is [root], held as is rather than copied; a dotted table stops being dotted. */
    public constructor(root: StandardTable) : this(root, KeptText(), "\n", null)

    /**
     * The version of TOML the document is printed and written as: the one it was parsed as, or [TomlVersion.V1_1] for
     * a new document. A document made from a root takes the version of the document that root came from.
     *
     * Under [TomlVersion.V1_0] what TOML 1.1 added prints in its TOML 1.0 form where it has one: a time written without
     * seconds, such as `07:32`, prints as `07:32:00`, and a string or key spelled with a `\e` or `\x` escape prints
     * in its default spelling. An inline table across lines, with a comment or with a trailing comma has none, and
     * [writeTo] refuses it.
     *
     * ```kotlin
     * val doc = TomlDocument.parse("t = 07:32\n").getOrElse { error(it) }
     * doc.version = TomlVersion.V1_0
     * doc.toString() // "t = 07:32:00\n"
     * ```
     */
    public var version: TomlVersion
        get() = root.version
        set(value) {
            root.version = value
        }

    /** The root table's key/item pairs, in order. */
    override fun iterator(): Iterator<Pair<String, Item>> = root.iterator()

    /** The text after the last item, such as comments and blank lines at the end of the file. */
    public var trailing: String
        get() = rawTrailing.textOr("")
        set(value) {
            rawTrailing = KeptText.from(value)
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

    /** A deep copy. */
    public fun copy(): TomlDocument {
        val copy = TomlDocument(root.copy(), rawTrailing, lineEnding, null)
        copy.byteOrderMark = byteOrderMark
        copy.readWithoutFinalNewline = readWithoutFinalNewline
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
     * @return [TomlWriteError.NotInToml10], with nothing written, when the document's [version] is TOML 1.0 and it
     *   holds what only TOML 1.1 reads; [TomlWriteError.NotUnicode] when the text holds half of a surrogate pair,
     *   which UTF-8 cannot encode; or [TomlIoError] when the sink fails to take the text or is closed.
     */
    public fun writeTo(sink: Sink): EmptyResult<TomlWriteError> {
        settleHeadersForDepth(root)
        tooDeepPathAtTop(root)?.let { return Err(TomlWriteError.TooDeep(it)) }
        if (version == TomlVersion.V1_0) root.toml11Only()?.let { return Err(it) }
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

    /** The document as TOML text. */
    override fun toString(): String {
        val text = render()
        val read = readWithoutFinalNewline
        return if (read != null && text == read.printed) read.text else text
    }

    private fun render(): String = buildString {
        if (byteOrderMark) append(BYTE_ORDER_MARK)
        encodeDocument(this, this@TomlDocument)
    }

    // What the document printed right after it was read from [text].
    private class ReadText(
        val text: String,
        val printed: String,
    )

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
    is Value.Array -> array.firstNotNullOfOrNull { it.loneSurrogatePath() }
    is Value.InlineTable -> table.loneSurrogatePath()
    is Value.Integer, is Value.Float, is Value.Boolean, is Value.Datetime -> null
}

/**
 * [TomlWriteError.NotInToml10] for the first value under this table, in the order the tables hold them, whose TOML
 * 1.0 text a TOML 1.0 parse refuses; or null when there is none.
 */
private fun Table.toml11Only(path: List<String> = emptyList()): TomlWriteError.NotInToml10? {
    for ((key, item) in entries) {
        val keyPath = path + key
        val found = item.standardTableOrNull?.toml11Only(keyPath)
            ?: item.arrayOfTablesOrNull?.firstNotNullOfOrNull { it.toml11Only(keyPath) }
            ?: item.valueOrNull?.toml11Only(keyPath)
        if (found != null) return found
    }
    return null
}

private fun Value.toml11Only(path: List<String>): TomlWriteError.NotInToml10? {
    // A dotted inline table prints as the parts of dotted keys, not in braces.
    if (this is Value.InlineTable && table.isDotted) return table.toml11Only(path)
    if (this !is Value.Array && this !is Value.InlineTable) return null
    val text = buildString { encodeValue(this, this@toml11Only, "", "", RawDecor.EMPTY, TomlVersion.V1_0) }
    val parsed = Value.parse(text, TomlVersion.V1_0) as? Result.Error ?: return null
    val reason = parsed.error.reason as? TomlParseError.Reason.NotInToml10 ?: return null
    return TomlWriteError.NotInToml10(path, reason.feature)
}
