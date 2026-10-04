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
 * changed, so an unchanged document prints back byte for byte. What was changed prints in its new form, and an
 * inserted line ends like the line before it. Text without a final newline keeps that only while the document
 * prints as it was read; once changed it ends with a newline.
 *
 * ```kotlin
 * val doc = TomlDocument.parse("\"hello\" = 'toml!' # comment\n['a'.b]\n").getOrElse { error(it) }
 * doc["a"]["b"]["c"]["d"] = "hello"
 * doc["a"]["b"]["c"].inlineTableOrNull?.resetFormatting()
 * doc.toString() // "\"hello\" = 'toml!' # comment\n['a'.b]\nc = { d = \"hello\" }\n"
 * ```
 *
 * The keyed operations here act on [root]; everything else a [Table] offers is reached through [root].
 */
public class TomlDocument internal constructor(
    /** The root table, holding the document's top-level keys and, through them, every table. */
    public val root: Table,
    internal var rawTrailing: RawString,
    internal val lineEnding: String,
    // The text this document was parsed from, kept to print it back without a final newline it never had.
    private val parsedFrom: String?,
) : Iterable<Pair<String, Item>> {

    /** An empty document. */
    public constructor() : this(Table.withPosition(0), RawString(), "\n", null)

    /** A document whose root is [root], held as is rather than copied. */
    public constructor(root: Table) : this(root, RawString(), "\n", null)

    /** The root table's key/item pairs, in order. */
    override fun iterator(): Iterator<Pair<String, Item>> = root.iterator()

    /** The text after the last item, such as comments and blank lines at the end of the file. */
    public var trailing: String
        get() = rawTrailing.toStrWithDefault("")
        set(value) {
            rawTrailing = RawString.from(value)
        }

    /** The slot at [key] of the root table; see [Table.get]. */
    public operator fun get(key: String): Item = root[key]

    /** Stores a copy of [item] at [key] of the root table; see [Table.set]. */
    public operator fun set(
        key: String,
        item: Item,
    ) {
        root[key] = item
    }

    /** Stores a copy of [value] at [key] of the root table; see [Table.set]. */
    public operator fun set(
        key: String,
        value: Value,
    ) {
        root[key] = value
    }

    /** Stores a copy of [table] at [key] of the root table; see [Table.set]. */
    public operator fun set(
        key: String,
        table: Table,
    ) {
        root[key] = table
    }

    /** Stores a copy of [arrayOfTables] at [key] of the root table; see [Table.set]. */
    public operator fun set(
        key: String,
        arrayOfTables: ArrayOfTables,
    ) {
        root[key] = arrayOfTables
    }

    /** The item at [key] of the root table, or null; see [Table.getOrNull]. */
    public fun getOrNull(key: String): Item? = root.getOrNull(key)

    /** Whether [key] of the root table holds an item. */
    public fun containsKey(key: String): Boolean = root.containsKey(key)

    /** Whether [key] of the root table holds an item, for `key in document`. */
    public operator fun contains(key: String): Boolean = root.containsKey(key)

    /**
     * Sets [key] of the root table to a copy of [item], keeping the key's formatting, and returns the item it held;
     * see [Table.put].
     */
    public fun put(
        key: String,
        item: Item,
    ): Item? = root.put(key, item)

    /**
     * Sets [key] of the root table, with its spelling and decor, to a copy of [item], and returns the item it held;
     * see [Table.put].
     */
    public fun put(
        key: Key,
        item: Item,
    ): Item? = root.put(key, item)

    /** Stores a copy of every pair of [pairs] in the root table, in order; see [Table.putAll]. */
    public fun putAll(pairs: Iterable<Pair<String, Item>>) {
        root.putAll(pairs)
    }

    /**
     * Removes [key] from the root table and returns its item, or null when it held none. The comment and blank
     * lines above the key, or above a table's first header, stay where they print; see [Table.remove].
     */
    public fun remove(key: String): Item? = root.remove(key)

    /**
     * Keeps only the root table's pairs [predicate] accepts, and returns whether any was removed; see
     * [Table.retainAll].
     */
    public fun retainAll(predicate: (Pair<String, Item>) -> Boolean): Boolean = root.retainAll(predicate)

    /** Removes the root table's pairs [predicate] accepts, and returns whether any was; see [Table.removeAll]. */
    public fun removeAll(predicate: (Pair<String, Item>) -> Boolean): Boolean = root.removeAll(predicate)

    /** Removes every key of the root table, keeping the lines above each; see [Table.clear]. */
    public fun clear() {
        root.clear()
    }

    /** The item at [key] of the root table, first storing what [default] makes; see [Table.getOrPut]. */
    public fun getOrPut(
        key: String,
        default: () -> Item,
    ): Item = root.getOrPut(key, default)

    /** As [getOrPut], an inserted key keeping the spelling and decor of [key]; see [Table.getOrPut]. */
    public fun getOrPut(
        key: Key,
        default: () -> Item,
    ): Item = root.getOrPut(key, default)

    /** The keys of the root table holding an item, in order, as a copy; see [Table.keys]. */
    public val keys: Set<String>
        get() = root.keys

    /** The number of keys of the root table holding an item. */
    public val size: Int
        get() = root.size

    /** Whether the root table holds no items. */
    public fun isEmpty(): Boolean = root.isEmpty()

    /** Whether the root table holds an item. */
    public fun isNotEmpty(): Boolean = root.isNotEmpty()

    /** The text before the document's first line and after its last; see [Table.decor]. */
    public var decor: Decor
        get() = root.decor
        set(value) {
            root.decor = value
        }

    /** A deep copy. */
    public fun copy(): TomlDocument = TomlDocument(root.copy(), rawTrailing, lineEnding, parsedFrom)

    /**
     * Writes the document, as [toString] prints it, to [sink] in UTF-8 and flushes the sink. The sink stays
     * open.
     *
     * ```kotlin
     * SystemFileSystem.sink(Path("Cargo.toml")).buffered().use { document.writeTo(it) }
     * ```
     *
     * @return [TomlIoError] when the sink fails to take the text.
     */
    public fun writeTo(sink: Sink): EmptyResult<TomlIoError> = try {
        sink.write(toString().encodeToByteArray())
        sink.flush()
        Ok(Unit)
    } catch (error: IOException) {
        Err(TomlIoError(error.message))
    }

    /** The document as TOML text. */
    override fun toString(): String {
        val text = buildString { encodeDocument(this, this@TomlDocument) }
        val original = parsedFrom ?: return text
        val printedAsRead = !original.endsWith('\n') &&
            text.startsWith(original) &&
            text.substring(original.length).let { it == "\n" || it == "\r\n" }
        return if (printedAsRead) original else text
    }

    public companion object {
        /** How deeply arrays, inline tables and dotted keys may nest; deeper TOML is refused when parsed. */
        public const val NESTING_LIMIT: Int = 80

        /** Parses [text] as TOML of [version] into an editable document, failing at the first error. */
        public fun parse(
            text: String,
            version: TomlVersion = TomlVersion.V1_1,
        ): Result<TomlDocument, TomlParseError> {
            val errors = TomlSink(text)
            val document = parseDocumentModel(TomlSource(text), errors, version)
            errors.error()?.let { return Err(it) }
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
                val span = Span(reason.validUpTo, reason.validUpTo + (reason.errorLength ?: 1))
                val (line, column) = translatePosition(bytes, reason.validUpTo)
                return Err(TomlParseError(reason, span, TextPosition(line + 1, column + 1)))
            }
            return parse(bytes.decodeToString(), version)
        }

        /**
         * Reads [source] to its end and parses what it held as UTF-8 TOML of [version]. The source stays open.
         *
         * ```kotlin
         * val read = SystemFileSystem.source(Path("Cargo.toml")).buffered().use { TomlDocument.read(it) }
         * ```
         *
         * @return [TomlIoError] when reading fails, or [TomlReadError.Parse] when the bytes are not TOML.
         */
        public fun read(
            source: Source,
            version: TomlVersion = TomlVersion.V1_1,
        ): Result<TomlDocument, TomlReadError> {
            val bytes = try {
                source.readByteArray()
            } catch (error: IOException) {
                return Err(TomlIoError(error.message))
            }
            return when (val parsed = parse(bytes, version)) {
                is Result.Success -> parsed
                is Result.Error -> Err(TomlReadError.Parse(parsed.error, bytes.decodeToString()))
            }
        }
    }
}
