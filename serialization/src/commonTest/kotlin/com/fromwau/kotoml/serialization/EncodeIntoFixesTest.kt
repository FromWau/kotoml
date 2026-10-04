package com.fromwau.kotoml.serialization

import com.fromwau.kotoml.TomlDocument
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private data class WeightTable(val m: Map<String, Int> = mapOf("a" to 1, "b" to 2))

@Serializable
private data class ListenPorts(val ports: List<Int>)

@Serializable
private data class TallyMap(val m: Map<String, Int>)

@Serializable
private data class Shelf(val x: Int)

@Serializable
private data class ShelfMap(val m: Map<String, Shelf>)

/** A list that leaves out its zeros the way a class leaves out a field at its default. */
private object ZeroSkippingList : KSerializer<List<Int>> {
    private val list = ListSerializer(Int.serializer())

    override val descriptor: SerialDescriptor = list.descriptor

    override fun serialize(
        encoder: Encoder,
        value: List<Int>,
    ) {
        val output = encoder.beginCollection(descriptor, value.size)
        for ((index, element) in value.withIndex()) {
            if (output.shouldEncodeElementDefault(descriptor, index) || element != 0) {
                output.encodeIntElement(descriptor, index, element)
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): List<Int> = list.deserialize(decoder)
}

@Serializable
private data class Readings(@Serializable(with = ZeroSkippingList::class) val counts: List<Int>)

/** What `encodeInto` writes for defaults that are not their type's own, moved array elements and emptied maps. */
@Serializable
private data class Listener(val host: String = "localhost", val port: Int = 8080)

@Serializable
private data class ListenerConfig(val listener: Listener = Listener(port = 9090))

@Serializable
private data class ServiceLimits(val retries: Int = 3, val timeout: Int = 30)

@Serializable
private data class Service(val name: String = "api", val limits: ServiceLimits = ServiceLimits(timeout = 5))

@Serializable
private data class Deployment(val service: Service = Service())

@Serializable
private data class PlainConfig(val listener: Listener = Listener())

class EncodeIntoFixesTest {

    private fun <T> encoded(
        text: String,
        serializer: SerializationStrategy<T>,
        value: T,
    ): String {
        val document = TomlDocument.parse(text).getOrFail()
        Toml.encodeInto(document, serializer, value).getOrFail()
        return document.toString()
    }

    @Test
    fun `a map at its field's default adds the entries the document lacks`() = assertEquals(
        "[m]\na = 1\nb = 2\n",
        encoded("[m]\na = 1\n", WeightTable.serializer(), WeightTable()),
    )

    @Test
    fun `reordered elements of a multi-line array keep the comment on their own line`() = assertEquals(
        "ports = [\n  8443, # tls\n  8080, # http\n]\n",
        encoded(
            "ports = [\n  8080, # http\n  8443, # tls\n]\n",
            ListenPorts.serializer(),
            ListenPorts(listOf(8443, 8080)),
        ),
    )

    @Test
    fun `a map emptied in a dotted key is written as an empty inline table`() = assertEquals(
        "m = {}\n",
        encoded("m.a = 1\n", TallyMap.serializer(), TallyMap(emptyMap())),
    )

    @Test
    fun `a map emptied in a table only deeper headers name is written as an empty inline table`() = assertEquals(
        "m = {}\n",
        encoded("[m.a]\nx = 1\n", ShelfMap.serializer(), ShelfMap(emptyMap())),
    )

    @Test
    fun `the first number updated into a later slot of a one-line list keeps the space after the comma`() =
        assertEquals(
            "ports = [2, 3]\n",
            encoded("ports = [1, 2]\n", ListenPorts.serializer(), ListenPorts(listOf(2, 3))),
        )

    @Test
    fun `a one-line list that drops a middle number and updates the first keeps the space after the comma`() =
        assertEquals(
            "ports = [3, 4]\n",
            encoded("ports = [1, 2, 3]\n", ListenPorts.serializer(), ListenPorts(listOf(3, 4))),
        )

    @Test
    fun `a list that leaves out an element at its default writes each element at its own index`() = assertEquals(
        "counts = [0, 5]\n",
        encoded("counts = [7]\n", Readings.serializer(), Readings(listOf(0, 5))),
    )

    @Test
    fun `a nested class at a default that differs from its own defaults writes the keys that differ`() {
        val document = TomlDocument.parse("[listener]\nhost = \"localhost\"\n").getOrFail()
        Toml.encodeInto(document, ListenerConfig()).getOrFail()
        assertEquals("[listener]\nhost = \"localhost\"\nport = 9090\n", document.toString())
        assertEquals(ListenerConfig(), Toml.decodeFromDocument<ListenerConfig>(document).getOrFail())
    }

    @Test
    fun `a default two levels down that differs from its class's defaults is written`() {
        val document = TomlDocument.parse("[service]\nname = \"api\"\n\n[service.limits]\nretries = 3\n").getOrFail()
        Toml.encodeInto(document, Deployment()).getOrFail()
        assertEquals(
            "[service]\nname = \"api\"\n\n[service.limits]\nretries = 3\ntimeout = 5\n",
            document.toString(),
        )
        assertEquals(Deployment(), Toml.decodeFromDocument<Deployment>(document).getOrFail())
    }

    @Test
    fun `a nested class at its own class's defaults adds no keys`() {
        val document = TomlDocument.parse("[listener]\nhost = \"localhost\"\n").getOrFail()
        Toml.encodeInto(document, PlainConfig()).getOrFail()
        assertEquals("[listener]\nhost = \"localhost\"\n", document.toString())
    }
}
