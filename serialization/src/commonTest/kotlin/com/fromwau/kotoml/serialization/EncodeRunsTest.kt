package com.fromwau.kotoml.serialization

import com.fromwau.kotoml.TomlDocument
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Encoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** Counts how often `encodeInto` runs the serializer, which is all a serializer with side effects can observe. */
private class Counting<T>(private val inner: KSerializer<T>) : KSerializer<T> by inner {
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
 * With `encodeDefaults` off, `encodeInto` runs the serializer a second time only when a field at its default has a
 * key in the document, because only then does it need that default value.
 */
class EncodeRunsTest {

    private fun encoded(
        text: String,
        value: Endpoint,
        toml: Toml = Toml,
    ): Pair<Int, String> {
        val document = TomlDocument.parse(text).getOrFail()
        val serializer = Counting(Endpoint.serializer())
        toml.encodeInto(document, serializer, value).getOrFail()
        return serializer.runs to document.toString()
    }

    @Test
    fun `a file without default values is encoded in one run`() {
        val text = "hostname = \"h\"\nport = 1\n"
        assertEquals(1 to text, encoded(text, Endpoint("h", 1)))
    }

    @Test
    fun `a changed field is encoded in one run when no default key is in the file`() {
        assertEquals(
            1 to "hostname = \"h\"\nport = 2\n",
            encoded("hostname = \"h\"\nport = 1\n", Endpoint("h", 2)),
        )
    }

    @Test
    fun `a key holding a field at its default takes a second run to update it`() {
        assertEquals(
            2 to "hostname = \"h\"\nport = 1\ntimeout = \"15s\" # slow\n",
            encoded("hostname = \"h\"\nport = 1\ntimeout = \"30s\" # slow\n", Endpoint("h", 1, timeout = 15.seconds)),
        )
    }

    @Test
    fun `encodeDefaults set encodes in one run`() {
        assertEquals(
            1 to "hostname = \"h\"\nport = 1\ntimeout = \"15s\"\nmode = \"TCP\"\n",
            encoded("hostname = \"h\"\nport = 1\n", Endpoint("h", 1), Toml { encodeDefaults = true }),
        )
    }
}
