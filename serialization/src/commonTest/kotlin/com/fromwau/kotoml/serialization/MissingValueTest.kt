package com.fromwau.kotoml.serialization

import com.fromwau.kotoml.TomlType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a decode reports when the document has nothing where the model wants a value. */
class MissingValueTest {

    @Serializable
    private data class Server(val host: String, val port: Int)

    @Serializable
    private data class Servers(val servers: List<Server>)

    private data class Port(val port: Int)

    // Reads its field straight away, without asking which keys the table has.
    private object EagerPortSerializer : KSerializer<Port> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Port") { element<Int>("port") }

        override fun deserialize(decoder: Decoder): Port =
            decoder.decodeStructure(descriptor) { Port(decodeIntElement(descriptor, 0)) }

        override fun serialize(
            encoder: Encoder,
            value: Port,
        ) = encoder.encodeStructure(descriptor) { encodeIntElement(descriptor, 0, value.port) }
    }

    @Test
    fun `a generated serializer reports a field with no key and no default as a missing key`() {
        assertEquals(
            TomlDecodeError.MissingKey("port"),
            Toml.decodeFromString<Server>("host = \"h\"\n").errorOrFail(),
        )
    }

    @Test
    fun `a missing key inside a list element is reported at its full path`() {
        assertEquals(
            TomlDecodeError.MissingKey("servers[1].port"),
            Toml.decodeFromString<Servers>(
                "[[servers]]\nhost = \"a\"\nport = 1\n[[servers]]\nhost = \"b\"\n",
            ).errorOrFail(),
        )
    }

    @Test
    fun `a serializer that reads a field the document lacks gets a type mismatch that found Missing`() {
        assertEquals(
            TomlDecodeError.TypeMismatch("port", TomlType.Integer, TomlType.Missing),
            Toml.decodeFromString(EagerPortSerializer, "").errorOrFail(),
        )
    }
}
