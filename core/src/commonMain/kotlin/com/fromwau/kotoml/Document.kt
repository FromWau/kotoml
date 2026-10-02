package com.fromwau.kotoml

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kotoml.parser.TomlSource

/**
 * A parsed TOML document that still points into its text [raw].
 *
 * It is for reading. To edit and print, turn it into a [DocumentMut] with [intoMut]; editing a `Document`
 * directly is not supported, though Kotlin cannot stop it.
 */
public class Document internal constructor(
    internal val root: Item,
    internal val trailing: RawString,
    private val raw: String,
    private val lineEnding: String,
) {

    /** An empty document parsed from empty text. */
    public constructor() : this(Item.from(Table.withPosition(0)), RawString(), "", "\n")

    /** The root item, which always holds a table. */
    public fun asItem(): Item = root

    /** The root item, upstream's `into_item`. */
    public fun intoItem(): Item = root

    /** The root table. */
    public fun asTable(): Table = root.asTable() ?: Table()

    /** The root table, upstream's `into_table`. */
    public fun intoTable(): Table = asTable()

    /** The root table's key/item pairs. */
    public fun iter(): Iterator<Pair<String, Item>> = asTable().iter()

    /** The text after the last item. */
    public fun trailing(): String = trailing.toStr(SourceText(raw))

    /** The text the document was parsed from. */
    public fun raw(): String = raw

    /** Resolves every span into text and returns the document, ready to edit and print. */
    public fun intoMut(): DocumentMut {
        val input = SourceText(raw)
        root.despan(input)
        return DocumentMut(root, trailing.despan(input), lineEnding, raw)
    }

    /** The slot at [key] of the root table; see [Table.get]. */
    public operator fun get(key: String): Item = asTable()[key]

    public companion object {
        /** Parses [raw] as a TOML document of [version], failing at the first error. */
        public fun parse(raw: String, version: TomlVersion = TomlVersion.V1_1): Result<Document, TomlParseError> {
            val source = TomlSource(raw)
            val sink = TomlSink(raw)
            val document = parseDocumentModel(source, sink, version)
            sink.error()?.let { return Err(it) }
            return Ok(document)
        }
    }
}

/**
 * An editable TOML document: a root table, its sub-tables, and the text after the last item.
 *
 * Printing it with [toString] reproduces every comment, blank line, spelling and line ending that was not
 * changed, so an unchanged document prints back byte for byte. What was changed prints in its new form, and an
 * inserted line ends like the line before it. Text without a final newline keeps that only while the document
 * prints as it was read; once changed it ends with a newline.
 *
 * ```kotlin
 * val doc = DocumentMut.parse("\"hello\" = 'toml!' # comment\n['a'.b]\n").getOrNull()!!
 * doc["a"]["b"]["c"]["d"] = value("hello")
 * doc["a"]["b"]["c"].inlineTableOrNull?.fmt()
 * doc.toString() // "\"hello\" = 'toml!' # comment\n['a'.b]\nc = { d = \"hello\" }\n"
 * ```
 */
public class DocumentMut internal constructor(
    internal val root: Item,
    internal var rawTrailing: RawString,
    internal val lineEnding: String,
    // The text this document was parsed from, kept to print it back without a final newline it never had.
    private val parsedFrom: String?,
) : Iterable<Pair<String, Item>> {

    /** An empty document. */
    public constructor() : this(Item.from(Table.withPosition(0)), RawString(), "\n", null)

    /** A document whose root is [root]. */
    public constructor(root: Table) : this(Item.from(root), RawString(), "\n", null)

    /** The root item, which holds a table. */
    public fun asItem(): Item = root

    /** The root item, to change in place. */
    public fun asItemMut(): Item = root

    /** The root item, upstream's `into_item`. */
    public fun intoItem(): Item = root

    /**
     * The root table. Should the root item have been made to hold anything else, it is reset to an empty
     * table here, where upstream panics.
     */
    public fun asTable(): Table = root.asTable() ?: Table().also { root.holdTable(it) }

    /** The root table, to change in place. */
    public fun asTableMut(): Table = asTable()

    /** The root table, upstream's `into_table`. */
    public fun intoTable(): Table = asTable()

    /** The root table's key/item pairs. */
    public fun iter(): Iterator<Pair<String, Item>> = asTable().iter()

    override fun iterator(): Iterator<Pair<String, Item>> = iter()

    /** Sets the text after the last item. */
    public fun setTrailing(trailing: String) {
        rawTrailing = RawString.from(trailing)
    }

    /** The text after the last item. */
    public fun trailing(): String = rawTrailing.toStrWithDefault("")

    /** The slot at [key] of the root table; see [Table.get]. */
    public operator fun get(key: String): Item = asTable()[key]

    /** Stores [item] at [key] of the root table; see [Table.set]. */
    public operator fun set(key: String, item: Item) {
        asTable()[key] = item
    }

    /** The item at [key] of the root table, or null; see [Table.getOrNull]. */
    public fun getOrNull(key: String): Item? = asTable().getOrNull(key)

    /** Whether [key] of the root table holds an item. */
    public fun containsKey(key: String): Boolean = asTable().containsKey(key)

    /** Sets [key] of the root table to [item]; see [Table.insert]. */
    public fun insert(key: String, item: Item): Item? = asTable().insert(key, item)

    /** Removes [key] from the root table; see [Table.remove]. */
    public fun remove(key: String): Item? = asTable().remove(key)

    /** The entry for [key] of the root table; see [Table.entry]. */
    public fun entry(key: String): Entry = asTable().entry(key)

    /** The number of keys of the root table holding an item. */
    public fun len(): Int = asTable().len()

    /** Whether the root table holds no items. */
    public fun isEmpty(): Boolean = asTable().isEmpty()

    /** The text before the document's first line and after its last; see [Table.decor]. */
    public var decor: Decor
        get() = asTable().decor
        set(value) {
            asTable().decor = value
        }

    /** A deep copy. */
    public fun clone(): DocumentMut = DocumentMut(root.clone(), rawTrailing, lineEnding, parsedFrom)

    /** The document as TOML text. */
    override fun toString(): String {
        val text = buildString { encodeDocument(this, this@DocumentMut) }
        val original = parsedFrom ?: return text
        val printedAsRead = !original.endsWith('\n') &&
            text.startsWith(original) &&
            text.substring(original.length).let { it == "\n" || it == "\r\n" }
        return if (printedAsRead) original else text
    }

    public companion object {
        /** Parses [text] as TOML of [version] into an editable document, failing at the first error. */
        public fun parse(text: String, version: TomlVersion = TomlVersion.V1_1): Result<DocumentMut, TomlParseError> =
            when (val parsed = Document.parse(text, version)) {
                is Result.Success -> Ok(parsed.value.intoMut())
                is Result.Error -> parsed
            }

        /**
         * Parses [bytes] as UTF-8 TOML of [version] into an editable document, failing on bytes that are not
         * UTF-8 as on the first TOML error.
         */
        public fun parse(
            bytes: ByteArray,
            version: TomlVersion = TomlVersion.V1_1,
        ): Result<DocumentMut, TomlParseError> {
            utf8Error(bytes)?.let { return Err(TomlParseError(it)) }
            return parse(bytes.decodeToString(), version)
        }
    }
}
