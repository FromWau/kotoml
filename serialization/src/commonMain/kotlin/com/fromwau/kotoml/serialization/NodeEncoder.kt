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
    private val emit: (TomlNode?) -> Unit,
) : Encoder {
    override val serializersModule: SerializersModule
        get() = settings.toml.serializersModule

    private fun scalar(value: Value) = emit(TomlNode.Scalar(value))

    override fun encodeNull() = emit(null)

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

    override fun encodeChar(value: Char) = scalar(Value.String(value.toString()))

    override fun encodeString(value: String) = scalar(Value.String(value))

    override fun encodeEnum(
        enumDescriptor: SerialDescriptor,
        index: Int,
    ) = scalar(Value.String(enumDescriptor.getElementName(index)))

    override fun encodeInline(descriptor: SerialDescriptor): Encoder = this

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder = when (descriptor.kind) {
        StructureKind.LIST -> ListEncoder(settings, path, emit)
        StructureKind.MAP -> MapEncoder(settings, path, emit)
        is PolymorphicKind -> PolymorphicEncoder(settings, path, emit)
        else -> ClassEncoder(settings, path, emit)
    }

    override fun <T> encodeSerializableValue(
        serializer: SerializationStrategy<T>,
        value: T,
    ) {
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

/** Encodes the elements of one class, list, map or polymorphic value, each through its own [NodeEncoder]. */
internal abstract class NodeCompositeEncoder(
    protected val settings: EncodeSettings,
    protected val path: TomlPath,
    private val emit: (TomlNode?) -> Unit,
) : CompositeEncoder {
    override val serializersModule: SerializersModule
        get() = settings.toml.serializersModule

    protected abstract fun put(
        descriptor: SerialDescriptor,
        index: Int,
        node: TomlNode?,
    )

    protected abstract fun childPath(
        descriptor: SerialDescriptor,
        index: Int,
    ): TomlPath

    protected abstract fun build(): TomlNode

    protected open fun isMapKey(index: Int): Boolean = false

    private fun child(
        descriptor: SerialDescriptor,
        index: Int,
    ): NodeEncoder = NodeEncoder(settings, childPath(descriptor, index), isMapKey(index)) {
        put(descriptor, index, it)
    }

    override fun shouldEncodeElementDefault(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean = settings.encodeDefaults

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
    emit: (TomlNode?) -> Unit,
) : NodeCompositeEncoder(settings, path, emit) {
    private val entries = linkedMapOf<String, TomlNode>()
    private val omitted = linkedSetOf<String>()

    override fun childPath(
        descriptor: SerialDescriptor,
        index: Int,
    ): TomlPath = path.key(descriptor.getElementName(index))

    override fun put(
        descriptor: SerialDescriptor,
        index: Int,
        node: TomlNode?,
    ) {
        val name = descriptor.getElementName(index)
        if (node == null) omitted.add(name) else entries[name] = node
    }

    override fun build(): TomlNode = TomlNode.Struct(entries, omitted, ownsAllKeys = false)
}

private class ListEncoder(
    settings: EncodeSettings,
    path: TomlPath,
    emit: (TomlNode?) -> Unit,
) : NodeCompositeEncoder(settings, path, emit) {
    private val elements = mutableListOf<TomlNode>()

    override fun childPath(
        descriptor: SerialDescriptor,
        index: Int,
    ): TomlPath = path.index(index)

    override fun put(
        descriptor: SerialDescriptor,
        index: Int,
        node: TomlNode?,
    ) {
        if (node == null) throw EncodeFailure(TomlEncodeError.NullElement("${path.index(index)}"))
        elements.add(node)
    }

    override fun build(): TomlNode = TomlNode.Sequence(elements)
}

/** A map's elements alternate: a key at each even index, its value at the odd index after it. */
private class MapEncoder(
    settings: EncodeSettings,
    path: TomlPath,
    emit: (TomlNode?) -> Unit,
) : NodeCompositeEncoder(settings, path, emit) {
    private val entries = linkedMapOf<String, TomlNode>()
    private var key: String = ""

    override fun isMapKey(index: Int): Boolean = index % 2 == 0

    override fun childPath(
        descriptor: SerialDescriptor,
        index: Int,
    ): TomlPath = if (isMapKey(index)) path else path.key(key)

    override fun put(
        descriptor: SerialDescriptor,
        index: Int,
        node: TomlNode?,
    ) {
        if (isMapKey(index)) {
            val name = (node as? TomlNode.Scalar)?.value?.stringOrNull
            key = name ?: throw EncodeFailure(TomlEncodeError.NonStringMapKey("$path"))
        } else if (node != null) {
            entries[key] = node
        }
    }

    override fun build(): TomlNode = TomlNode.Struct(entries, emptySet(), ownsAllKeys = true)
}

/** A polymorphic value is its subclass's serial name at index 0 and the value itself at index 1. */
private class PolymorphicEncoder(
    settings: EncodeSettings,
    path: TomlPath,
    emit: (TomlNode?) -> Unit,
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
        node: TomlNode?,
    ) {
        if (index == 0) name = node else value = node
    }

    override fun build(): TomlNode {
        val key = settings.toml.classDiscriminator
        val subtype = name
        val struct = value as? TomlNode.Struct
        if (subtype == null || struct == null || key in struct.entries || key in struct.omitted) {
            throw EncodeFailure(TomlEncodeError.DiscriminatorConflict("$path", key))
        }
        return TomlNode.Struct(
            entries = linkedMapOf(key to subtype) + struct.entries,
            omitted = struct.omitted,
            ownsAllKeys = struct.ownsAllKeys,
            discriminator = key,
        )
    }
}
