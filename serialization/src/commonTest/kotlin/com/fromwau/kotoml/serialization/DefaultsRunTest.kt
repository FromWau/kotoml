package com.fromwau.kotoml.serialization

import com.fromwau.kotoml.TomlDocument
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private data class Hosts(val hosts: Map<String, Server>)

@Serializable
private sealed interface Store

@Serializable
@SerialName("disk")
private data class Disk(val size: Int = 10) : Store

@Serializable
private data class Storage(val store: Store)

/** A list that leaves out its zeros the way a class leaves out a field at its default. */
private object ZerosAsDefaults : KSerializer<List<Int>> {
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
private data class Counts(@Serializable(with = ZerosAsDefaults::class) val counts: List<Int>)

private class RunCounter<T>(private val inner: KSerializer<T>) : KSerializer<T> by inner {
    var runs = 0

    override fun serialize(
        encoder: Encoder,
        value: T,
    ) {
        runs++
        inner.serialize(encoder, value)
    }
}

/**
 * Where `encodeInto` looks for a key holding a field at its default: in nested tables, map entries, subtypes and
 * every element of a list the document holds. Finding one takes a second run, finding none keeps it at one.
 */
class DefaultsRunTest {

    private fun <T> assertEncoded(
        text: String,
        serializer: KSerializer<T>,
        value: T,
        expected: String,
        runs: Int,
        toml: Toml = Toml,
    ) {
        val document = TomlDocument.parse(text).getOrFail()
        val counter = RunCounter(serializer)
        toml.encodeInto(document, counter, value).getOrFail()
        assertEquals(expected, document.toString())
        assertEquals(runs, counter.runs)
    }

    @Test
    fun `a nested class at its default whose table is written takes a second run`() = assertEncoded(
        text = "url = \"u\"\n[pool]\nsize = 8\n",
        serializer = Database.serializer(),
        value = Database("u"),
        expected = "url = \"u\"\n[pool]\nsize = 4\n",
        runs = 2,
    )

    @Test
    fun `a nested class at its default without a table is encoded in one run`() = assertEncoded(
        text = "url = \"x\"\n",
        serializer = Database.serializer(),
        value = Database("u"),
        expected = "url = \"u\"\n",
        runs = 1,
    )

    @Test
    fun `a field at its default in a written nested table takes a second run`() = assertEncoded(
        text = "url = \"u\"\n[pool]\nsize = 8\nidle = \"1m\"\n",
        serializer = Database.serializer(),
        value = Database("u", Pool(size = 8)),
        expected = "url = \"u\"\n[pool]\nsize = 8\nidle = \"0s\"\n",
        runs = 2,
    )

    @Test
    fun `a list element whose default key is written takes a second run`() = assertEncoded(
        text = "name = \"f\"\n[[servers]]\nhost = \"a\"\nport = 80\n",
        serializer = Fleet.serializer(),
        value = Fleet("f", listOf(Server("a"))),
        expected = "name = \"f\"\n[[servers]]\nhost = \"a\"\nport = 8080\n",
        runs = 2,
    )

    @Test
    fun `a moved element whose default key is written takes a second run`() = assertEncoded(
        text = "name = \"f\"\n[[servers]]\nhost = \"a\"\nport = 8080\n[[servers]]\nhost = \"b\"\n",
        serializer = Fleet.serializer(),
        value = Fleet("f", listOf(Server("b"), Server("a"))),
        expected = "name = \"f\"\n[[servers]]\nhost = \"b\"\n[[servers]]\nhost = \"a\"\nport = 8080\n",
        runs = 2,
    )

    @Test
    fun `list elements at their default are encoded in one run when no element writes a default key`() =
        assertEncoded(
            text = "name = \"f\"\n[[servers]]\nhost = \"a\"\n",
            serializer = Fleet.serializer(),
            value = Fleet("f", listOf(Server("a"), Server("b", 80))),
            expected = "name = \"f\"\n[[servers]]\nhost = \"a\"\n\n[[servers]]\nhost = \"b\"\nport = 80\n",
            runs = 1,
        )

    @Test
    fun `a map value whose default key is written takes a second run`() = assertEncoded(
        text = "[hosts.a]\nhost = \"a\"\nport = 80\n",
        serializer = Hosts.serializer(),
        value = Hosts(mapOf("a" to Server("a"))),
        expected = "[hosts.a]\nhost = \"a\"\nport = 8080\n",
        runs = 2,
    )

    @Test
    fun `a subtype field at its default whose key is written takes a second run`() = assertEncoded(
        text = "[store]\ntype = \"disk\"\nsize = 20\n",
        serializer = Storage.serializer(),
        value = Storage(Disk()),
        expected = "[store]\ntype = \"disk\"\nsize = 10\n",
        runs = 2,
    )

    @Test
    fun `encodeDefaults set updates a written default key in one run`() = assertEncoded(
        text = "hostname = \"h\"\nport = 1\ntimeout = \"30s\"\n",
        serializer = Endpoint.serializer(),
        value = Endpoint("h", 1),
        expected = "hostname = \"h\"\nport = 1\ntimeout = \"15s\"\nmode = \"TCP\"\n",
        runs = 1,
        toml = Toml { encodeDefaults = true },
    )

    @Test
    fun `a written list that leaves out an element at its default takes a second run`() = assertEncoded(
        text = "counts = [1, 2]\n",
        serializer = Counts.serializer(),
        value = Counts(listOf(1, 0)),
        expected = "counts = [1, 0]\n",
        runs = 2,
    )
}
