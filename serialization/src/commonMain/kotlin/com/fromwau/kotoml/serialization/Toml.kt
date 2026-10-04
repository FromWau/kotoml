package com.fromwau.kotoml.serialization

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import com.fromwau.kotoml.TomlDocument
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializerOrNull
import kotlin.reflect.typeOf

/**
 * Turns `@Serializable` values into TOML and back, the way `Json` does for JSON.
 *
 * Use [Toml.Default], or make a configured instance with the `Toml { }` builder:
 *
 * ```kotlin
 * @Serializable
 * data class Server(val host: String, val port: Int = 8080)
 *
 * // Result.Success(Server("example.org", 8080))
 * val server = Toml.decodeFromString<Server>("host = \"example.org\"\n")
 * // Result.Success("host = \"example.org\"\nport = 80\n")
 * val text = Toml.encodeToString(Server("example.org", 80))
 * val lenient = Toml { ignoreUnknownKeys = true }
 * ```
 *
 * The value at the top of a document is a class or a `Map<String, *>`, since a document is a table. Every entry
 * point returns a [Result]: a [TomlDecodeError] or a [TomlEncodeError] says what went wrong and where, and no
 * call throws.
 *
 * TOML has no null, and a missing key means the field's default. So every entry point refuses a type that holds a
 * nullable type anywhere, as a field, a list element, a map value or a nested class, before it reads or writes
 * anything: [TomlDecodeError.NullableType] or [TomlEncodeError.NullableType] names where. A subclass only a default
 * polymorphic serializer picks is refused the same way when a value of it is written or read. Model an optional
 * value with a default instead, such as an empty list or string.
 */
public sealed class Toml(
    /** Whether a key no field of the class names is skipped when reading, rather than refused. */
    public val ignoreUnknownKeys: Boolean,
    /** Whether a field holding its default value is written; reading always fills a missing key's default. */
    public val encodeDefaults: Boolean,
    /** The key that names the subclass of a polymorphic or sealed value, in that value's table. */
    public val classDiscriminator: String,
    /** How a list of classes or maps is written in a standard table. */
    public val tableListStyle: TableListStyle,
    /** The serializers for polymorphic and contextual types. */
    override val serializersModule: SerializersModule,
) : SerialFormat {

    /**
     * The default configuration: unknown keys refused, defaults left out, `type` as the class discriminator,
     * lists of classes as arrays of tables.
     */
    public companion object Default : Toml(
        ignoreUnknownKeys = false,
        encodeDefaults = false,
        classDiscriminator = "type",
        tableListStyle = TableListStyle.ArrayOfTables,
        serializersModule = EmptySerializersModule(),
    )

    /**
     * Parses [text] as TOML and reads it with [deserializer].
     *
     * @return the value, [TomlDecodeError.Parse] when the text is not TOML, [TomlDecodeError.NullableType] when
     *   the type holds a nullable type, or the error that stopped reading.
     */
    public fun <T> decodeFromString(
        deserializer: DeserializationStrategy<T>,
        text: String,
    ): Result<T, TomlDecodeError> = when (val parsed = TomlDocument.parse(text)) {
        is Result.Success -> decodeFromDocument(deserializer, parsed.value)
        is Result.Error -> Err(TomlDecodeError.Parse(parsed.error))
    }

    /**
     * Reads [document] with [deserializer]. The document is not changed. A missing key takes its field's default,
     * and is [TomlDecodeError.MissingKey] for a field without one.
     *
     * ```kotlin
     * val document = TomlDocument.parse(text).getOrElse { error(it) }
     * val config = Toml.decodeFromDocument(Config.serializer(), document)
     * ```
     *
     * @return the value, [TomlDecodeError.NullableType] when the type holds a nullable type, or the error that
     *   stopped reading.
     */
    public fun <T> decodeFromDocument(
        deserializer: DeserializationStrategy<T>,
        document: TomlDocument,
    ): Result<T, TomlDecodeError> {
        val descriptor = deserializer.descriptor
        if (!descriptor.isTableKind()) return Err(TomlDecodeError.UnsupportedRoot(descriptor.serialName))
        firstNullableType(descriptor, serializersModule)?.let { return Err(TomlDecodeError.NullableType("$it")) }
        return try {
            Ok(NodeDecoder(this, document.root.toInput(), TomlPath.Root).decodeSerializableValue(deserializer))
        } catch (failure: DecodeFailure) {
            Err(failure.error)
        }
    }

    /**
     * Writes [value] with [serializer] as TOML text.
     *
     * @return the text, [TomlEncodeError.NullableType] when the type holds a nullable type, or the error that
     *   stopped writing.
     */
    public fun <T> encodeToString(
        serializer: SerializationStrategy<T>,
        value: T,
    ): Result<String, TomlEncodeError> = encodeToDocument(serializer, value).map { it.toString() }

    /**
     * Writes [value] with [serializer] into a new document, which can be edited further before it is printed.
     *
     * @return the document, [TomlEncodeError.NullableType] when the type holds a nullable type, or the error that
     *   stopped writing.
     */
    public fun <T> encodeToDocument(
        serializer: SerializationStrategy<T>,
        value: T,
    ): Result<TomlDocument, TomlEncodeError> = encodeNode(serializer, value, encodeDefaults)
        .map { TomlDocument(DocumentWriter(tableListStyle).table(it)) }

    /**
     * Writes [value] with [serializer] into [document], changing only what differs, so a hand-written file keeps
     * its comments, blank lines, key spelling and key order.
     *
     * A key whose value changes keeps its place, its spelling and the comment after its value; a key the
     * document lacks is appended to its table. A key no field names stays, whatever [ignoreUnknownKeys] says,
     * while a map removes the keys it no longer has. A nested class updates its table in place. A list is merged
     * into the document's list, in the form the document wrote it: an element equal to one there keeps that
     * element's text and comments, even when it moved, and a changed element updates the old one sharing the most
     * values with it, or the next one left in a list of scalars. Other new elements are inserted and other old
     * ones removed. A field at its default, with [encodeDefaults] off, updates a key the document has and adds
     * none. Nothing is written when an error is returned, [TomlEncodeError.NullableType] among them.
     *
     * [serializer] runs once. With [encodeDefaults] off, it runs a second time when a field at its default has a
     * key in [document], to get the value that key is updated to.
     *
     * ```kotlin
     * val document = TomlDocument.parse("host = 'a'\nport = 8080 # the default\n").getOrElse { error(it) }
     * Toml.encodeInto(document, Server.serializer(), Server("a", 9090))
     * document.toString() // "host = 'a'\nport = 9090 # the default\n"
     * ```
     *
     * @return [Result.Success] once [document] holds [value], or the error that stopped writing.
     */
    public fun <T> encodeInto(
        document: TomlDocument,
        serializer: SerializationStrategy<T>,
        value: T,
    ): EmptyResult<TomlEncodeError> {
        val written = when (val encoded = encodeNode(serializer, value, encodeDefaults)) {
            is Result.Success -> encoded.value
            is Result.Error -> return encoded
        }
        val full = if (encodeDefaults || !document.root.toInput().holdsKeyAtDefault(written)) {
            written
        } else {
            when (val encoded = encodeNode(serializer, value, encodeDefaults = true, sparse = written)) {
                is Result.Success -> encoded.value
                is Result.Error -> return encoded
            }
        }
        val defaults = if (encodeDefaults) Defaults.Written else Defaults.Sparse(written)
        DocumentMerger(DocumentWriter(tableListStyle)).mergeTable(document.root, full, defaults)
        return Ok(Unit)
    }

    private fun <T> encodeNode(
        serializer: SerializationStrategy<T>,
        value: T,
        encodeDefaults: Boolean,
        sparse: TomlNode? = null,
    ): Result<TomlNode.Struct, TomlEncodeError> {
        val descriptor = serializer.descriptor
        val unsupported = TomlEncodeError.UnsupportedRoot(descriptor.serialName)
        if (!descriptor.isTableKind()) return Err(unsupported)
        firstNullableType(descriptor, serializersModule)?.let { return Err(TomlEncodeError.NullableType("$it")) }
        var node: TomlNode? = null
        return try {
            NodeEncoder(EncodeSettings(this, encodeDefaults), TomlPath.Root, sparse = sparse) { node = it }
                .encodeSerializableValue(serializer, value)
            (node as? TomlNode.Struct)?.let { Ok(it) } ?: Err(unsupported)
        } catch (failure: EncodeFailure) {
            Err(failure.error)
        }
    }
}

/**
 * Makes a [Toml] that starts from the configuration of [from] and changes what [builderAction] sets.
 *
 * ```kotlin
 * val toml = Toml {
 *     ignoreUnknownKeys = true
 *     tableListStyle = TableListStyle.InlineArray
 * }
 * ```
 */
public fun Toml(
    from: Toml = Toml.Default,
    builderAction: TomlBuilder.() -> Unit,
): Toml {
    val builder = TomlBuilder(from)
    builder.builderAction()
    return TomlImpl(builder)
}

/** The settings of a [Toml] being made; each starts as the [Toml] it is made from has it. */
public class TomlBuilder internal constructor(from: Toml) {
    /** Whether a key no field of the class names is skipped when reading, rather than refused. */
    public var ignoreUnknownKeys: Boolean = from.ignoreUnknownKeys

    /** Whether a field holding its default value is written; reading always fills a missing key's default. */
    public var encodeDefaults: Boolean = from.encodeDefaults

    /** The key that names the subclass of a polymorphic or sealed value, in that value's table. */
    public var classDiscriminator: String = from.classDiscriminator

    /** How a list of classes or maps is written in a standard table. */
    public var tableListStyle: TableListStyle = from.tableListStyle

    /** The serializers for polymorphic and contextual types. */
    public var serializersModule: SerializersModule = from.serializersModule
}

/** How [Toml] writes a list whose elements are classes or maps. */
public enum class TableListStyle {
    /** Each element is a table under its own `[[key]]` header. */
    ArrayOfTables,

    /** The list is an inline array of inline tables, `key = [{ a = 1 }, { a = 2 }]`. */
    InlineArray,
}

private class TomlImpl(builder: TomlBuilder) : Toml(
    ignoreUnknownKeys = builder.ignoreUnknownKeys,
    encodeDefaults = builder.encodeDefaults,
    classDiscriminator = builder.classDiscriminator,
    tableListStyle = builder.tableListStyle,
    serializersModule = builder.serializersModule,
)

// An inline class is a table when what it wraps is one.
private fun SerialDescriptor.isTableKind(): Boolean = when {
    isInline -> getElementDescriptor(0).isTableKind()
    kind == StructureKind.CLASS || kind == StructureKind.OBJECT || kind == StructureKind.MAP -> true
    kind == SerialKind.CONTEXTUAL || kind is PolymorphicKind -> true
    else -> false
}

/**
 * Parses [text] as TOML and reads it as a [T].
 *
 * ```kotlin
 * val config: Result<Config, TomlDecodeError> = Toml.decodeFromString<Config>(text)
 * ```
 */
public inline fun <reified T> Toml.decodeFromString(text: String): Result<T, TomlDecodeError> {
    val serializer = serializerOrNull<T>() ?: return Err(TomlDecodeError.NoSerializer("${typeOf<T>()}"))
    return decodeFromString(serializer, text)
}

/** Reads [document] as a [T]; see [Toml.decodeFromDocument]. */
public inline fun <reified T> Toml.decodeFromDocument(document: TomlDocument): Result<T, TomlDecodeError> {
    val serializer = serializerOrNull<T>() ?: return Err(TomlDecodeError.NoSerializer("${typeOf<T>()}"))
    return decodeFromDocument(serializer, document)
}

/** Writes [value] as TOML text; see [Toml.encodeToString]. */
public inline fun <reified T> Toml.encodeToString(value: T): Result<String, TomlEncodeError> {
    val serializer = serializerOrNull<T>() ?: return Err(TomlEncodeError.NoSerializer("${typeOf<T>()}"))
    return encodeToString(serializer, value)
}

/** Writes [value] into a new document; see [Toml.encodeToDocument]. */
public inline fun <reified T> Toml.encodeToDocument(value: T): Result<TomlDocument, TomlEncodeError> {
    val serializer = serializerOrNull<T>() ?: return Err(TomlEncodeError.NoSerializer("${typeOf<T>()}"))
    return encodeToDocument(serializer, value)
}

/** Writes [value] into [document], changing only what differs; see [Toml.encodeInto]. */
public inline fun <reified T> Toml.encodeInto(
    document: TomlDocument,
    value: T,
): EmptyResult<TomlEncodeError> {
    val serializer = serializerOrNull<T>() ?: return Err(TomlEncodeError.NoSerializer("${typeOf<T>()}"))
    return encodeInto(document, serializer, value)
}

@PublishedApi
internal inline fun <reified T> Toml.serializerOrNull(): KSerializer<T>? {
    @Suppress("UNCHECKED_CAST")
    return serializersModule.serializerOrNull(typeOf<T>()) as KSerializer<T>?
}
