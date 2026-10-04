package com.fromwau.kotoml.serialization

import com.fromwau.kotoml.Value
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule

/** What every encoder of one encoding shares. */
internal class EncodeSettings(
    val toml: Toml,
    val encodeDefaults: Boolean,
)

/** Encodes one value at [path] into a [TomlNode], handing it to [emit]. */
internal class NodeEncoder(
    private val settings: EncodeSettings,
    private val path: TomlPath,
    private val isMapKey: Boolean = false,
    private val emit: (TomlNode) -> Unit,
) : Encoder {
    override val serializersModule: SerializersModule
        get() = settings.toml.serializersModule

    private fun scalar(value: Value) = emit(TomlNode.Scalar(value))

    override fun encodeNull() = throw EncodeFailure(TomlEncodeError.NullValue("$path"))

    override fun encodeBoolean(value: Boolean) = scalar(Value.Boolean(value))

    override fun encodeByte(value: Byte) = scalar(Value.Integer(value.toLong()))

    override fun encodeShort(value: Short) = scalar(Value.Integer(value.toLong()))

    override fun encodeInt(value: Int) = scalar(Value.Integer(value.toLong()))

    override fun encodeLong(value: Long) = scalar(Value.Integer(value))

    // Through its decimal text, so 0.1f writes as 0.1 rather than as the double nearest the float.
    override fun encodeFloat(value: Float) = scalar(
        Value.Float(if (value.isFinite()) value.toString().toDouble() else value.toDouble()),
    )

    override fun encodeDouble(value: Double) = scalar(Value.Float(value))

    override fun encodeChar(value: Char) = encodeString(value.toString())

    // TOML text is UTF-8 and its escapes name Unicode scalar values, so a half of a surrogate pair cannot be written.
    override fun encodeString(value: String) {
        if (value.hasLoneSurrogate()) throw EncodeFailure(TomlEncodeError.NotUnicode("$path"))
        scalar(Value.String(value))
    }

    override fun encodeEnum(
        enumDescriptor: SerialDescriptor,
        index: Int,
    ) = scalar(Value.String(enumDescriptor.getElementName(index)))

    override fun encodeInline(descriptor: SerialDescriptor): Encoder =
        if (descriptor.isUnsignedInteger) UnsignedEncoder(this, path) else this

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder = when (descriptor.kind) {
        StructureKind.LIST -> ListEncoder(settings, path, emit)
        StructureKind.MAP -> MapEncoder(settings, path, emit)
        is PolymorphicKind -> PolymorphicEncoder(settings, path, emit)
        else -> ClassEncoder(settings, path, emit)
    }

    override fun <T : Any> encodeNullableSerializableValue(
        serializer: SerializationStrategy<T>,
        value: T?,
    ) = throw EncodeFailure(TomlEncodeError.NullableType("$path"))

    override fun <T> encodeSerializableValue(
        serializer: SerializationStrategy<T>,
        value: T,
    ) {
        if (serializer.descriptor.isNullable) throw EncodeFailure(TomlEncodeError.NullableType("$path"))
        try {
            val serialName = serializer.descriptor.serialName
            val known = knownValueOrNull(serialName, value, path, nativeDatetime = !isMapKey)
            if (known == null) {
                serializer.serialize(this, value)
            } else {
                emit(TomlNode.Scalar(known) { existing -> readsBackAs(serialName, existing, value) })
            }
        } catch (failure: EncodeFailure) {
            throw failure
        } catch (failure: Exception) {
            throw EncodeFailure(TomlEncodeError.Serializer("$path", failure.message))
        }
    }
}

private fun String.hasLoneSurrogate(): Boolean {
    var index = 0
    while (index < length) {
        val char = this[index]
        when {
            char.isHighSurrogate() && getOrNull(index + 1)?.isLowSurrogate() == true -> index += 2
            char.isSurrogate() -> return true
            else -> index++
        }
    }
    return false
}

/** Encodes the elements of one class, list, map or polymorphic value, each through its own [NodeEncoder]. */
internal abstract class NodeCompositeEncoder(
    protected val settings: EncodeSettings,
    protected val path: TomlPath,
    private val emit: (TomlNode) -> Unit,
) : CompositeEncoder {
    override val serializersModule: SerializersModule
        get() = settings.toml.serializersModule

    protected abstract fun put(
        descriptor: SerialDescriptor,
        index: Int,
        node: TomlNode,
    )

    protected abstract fun childPath(
        descriptor: SerialDescriptor,
        index: Int,
    ): TomlPath

    protected abstract fun build(): TomlNode

    protected open fun isMapKey(index: Int): Boolean = false

    /** What the element at [index] is called in [defaulted]: its key, in a class or a map. */
    protected open fun elementName(
        descriptor: SerialDescriptor,
        index: Int,
    ): String = descriptor.getElementName(index)

    private val skipped = mutableMapOf<Int, String>()

    /** The keys of the elements left out because they hold their default. */
    protected val defaulted: Set<String>
        get() = skipped.values.toSet()

    private fun child(
        descriptor: SerialDescriptor,
        index: Int,
    ): NodeEncoder {
        skipped.remove(index)
        return NodeEncoder(settings, childPath(descriptor, index), isMapKey(index)) { put(descriptor, index, it) }
    }

    // Asked before comparing with the default, so an element asked about and then not encoded holds its default.
    override fun shouldEncodeElementDefault(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        if (!settings.encodeDefaults) skipped[index] = elementName(descriptor, index)
        return settings.encodeDefaults
    }

    override fun endStructure(descriptor: SerialDescriptor) = emit(build())

    override fun encodeBooleanElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: Boolean,
    ) = child(descriptor, index).encodeBoolean(value)

    override fun encodeByteElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: Byte,
    ) = child(descriptor, index).encodeByte(value)

    override fun encodeShortElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: Short,
    ) = child(descriptor, index).encodeShort(value)

    override fun encodeIntElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: Int,
    ) = child(descriptor, index).encodeInt(value)

    override fun encodeLongElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: Long,
    ) = child(descriptor, index).encodeLong(value)

    override fun encodeFloatElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: Float,
    ) = child(descriptor, index).encodeFloat(value)

    override fun encodeDoubleElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: Double,
    ) = child(descriptor, index).encodeDouble(value)

    override fun encodeCharElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: Char,
    ) = child(descriptor, index).encodeChar(value)

    override fun encodeStringElement(
        descriptor: SerialDescriptor,
        index: Int,
        value: String,
    ) = child(descriptor, index).encodeString(value)

    override fun encodeInlineElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Encoder = child(descriptor, index).encodeInline(descriptor.getElementDescriptor(index))

    override fun <T> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T,
    ) = child(descriptor, index).encodeSerializableValue(serializer, value)

    override fun <T : Any> encodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T?,
    ) = child(descriptor, index).encodeNullableSerializableValue(serializer, value)
}

private class ClassEncoder(
    settings: EncodeSettings,
    path: TomlPath,
    emit: (TomlNode) -> Unit,
) : NodeCompositeEncoder(settings, path, emit) {
    private val entries = linkedMapOf<String, TomlNode>()

    override fun childPath(
        descriptor: SerialDescriptor,
        index: Int,
    ): TomlPath = path.key(descriptor.getElementName(index))

    override fun put(
        descriptor: SerialDescriptor,
        index: Int,
        node: TomlNode,
    ) {
        entries[descriptor.getElementName(index)] = node
    }

    override fun build(): TomlNode = TomlNode.Struct(
        entries = entries,
        ownsAllKeys = false,
        defaulted = defaulted,
    )
}

private class ListEncoder(
    settings: EncodeSettings,
    path: TomlPath,
    emit: (TomlNode) -> Unit,
) : NodeCompositeEncoder(settings, path, emit) {
    private val elements = mutableListOf<TomlNode>()

    override fun childPath(
        descriptor: SerialDescriptor,
        index: Int,
    ): TomlPath = path.index(index)

    override fun put(
        descriptor: SerialDescriptor,
        index: Int,
        node: TomlNode,
    ) {
        elements.add(node)
    }

    override fun build(): TomlNode = TomlNode.Sequence(elements, leavesOutDefaults = defaulted.isNotEmpty())
}

/** A map's elements alternate: a key at each even index, its value at the odd index after it. */
private class MapEncoder(
    settings: EncodeSettings,
    path: TomlPath,
    emit: (TomlNode) -> Unit,
) : NodeCompositeEncoder(settings, path, emit) {
    private val entries = linkedMapOf<String, TomlNode>()
    private var key: String? = null

    private val currentKey: String
        get() = checkNotNull(key) { "a map value is encoded after its key" }

    override fun isMapKey(index: Int): Boolean = index % 2 == 0

    override fun elementName(
        descriptor: SerialDescriptor,
        index: Int,
    ): String = currentKey

    override fun childPath(
        descriptor: SerialDescriptor,
        index: Int,
    ): TomlPath = if (isMapKey(index)) path else path.key(currentKey)

    override fun put(
        descriptor: SerialDescriptor,
        index: Int,
        node: TomlNode,
    ) {
        if (isMapKey(index)) {
            val name = (node as? TomlNode.Scalar)?.value?.stringOrNull
            key = name ?: throw EncodeFailure(TomlEncodeError.NonStringMapKey("$path"))
        } else {
            entries[currentKey] = node
        }
    }

    override fun build(): TomlNode = TomlNode.Struct(
        entries = entries,
        ownsAllKeys = true,
        defaulted = defaulted,
    )
}

/** A polymorphic value is its subclass's serial name at index 0 and the value itself at index 1. */
private class PolymorphicEncoder(
    settings: EncodeSettings,
    path: TomlPath,
    emit: (TomlNode) -> Unit,
) : NodeCompositeEncoder(settings, path, emit) {
    private var name: TomlNode? = null
    private var value: TomlNode? = null

    override fun childPath(
        descriptor: SerialDescriptor,
        index: Int,
    ): TomlPath = path

    override fun put(
        descriptor: SerialDescriptor,
        index: Int,
        node: TomlNode,
    ) {
        if (index == 0) name = node else value = node
    }

    // Checked on the subclass's fields, so a field named like the key conflicts even while it holds its default.
    override fun <T> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T,
    ) {
        val key = settings.toml.classDiscriminator
        if (serializer.descriptor.hasField(key)) {
            throw EncodeFailure(TomlEncodeError.DiscriminatorConflict("$path", key))
        }
        super.encodeSerializableElement(descriptor, index, serializer, value)
    }

    override fun build(): TomlNode {
        val key = settings.toml.classDiscriminator
        val subtype = name
        val struct = value as? TomlNode.Struct
        if (subtype == null || struct == null || key in struct.entries) {
            throw EncodeFailure(TomlEncodeError.DiscriminatorConflict("$path", key))
        }
        return TomlNode.Struct(
            entries = linkedMapOf(key to subtype) + struct.entries,
            ownsAllKeys = struct.ownsAllKeys,
            defaulted = struct.defaulted,
            discriminator = key,
        )
    }
}
