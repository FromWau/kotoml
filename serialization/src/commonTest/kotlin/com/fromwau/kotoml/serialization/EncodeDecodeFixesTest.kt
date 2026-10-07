package com.fromwau.kotoml.serialization

import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kotoml.TextPosition
import com.fromwau.kotoml.TomlDocument
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.UByteArraySerializer
import kotlinx.serialization.builtins.UIntArraySerializer
import kotlinx.serialization.builtins.ULongArraySerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

/** Writing and reading agree: what one side accepts the other reads back, and what neither can hold is refused. */
@OptIn(ExperimentalUnsignedTypes::class)
class EncodeDecodeFixesTest {


    @Serializable
    private data class Marks(val m: Map<Char, Int>)

    private class Tag(val name: String)

    private object TagSerializer : KSerializer<Tag> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Tag", PrimitiveKind.STRING)

        override fun serialize(
            encoder: Encoder,
            value: Tag,
        ) = encoder.encodeString(value.name)

        override fun deserialize(decoder: Decoder): Tag = Tag(decoder.decodeString())
    }

    @Serializable
    private data class Tagged(val counts: Map<@Contextual Tag, Int>)

    @Serializable
    private sealed interface Signal

    @Serializable
    @SerialName("c")
    private data class Coded(
        val type: String = "x",
        val r: Int,
    ) : Signal

    @Serializable
    private data class Mark(val mark: Char)

    @JvmInline
    @Serializable
    private value class RawPort(val v: Int)

    @JvmInline
    @Serializable
    private value class BoxedServer(val server: Server)

    private val uints = MapSerializer(String.serializer(), UIntArraySerializer())

    private val ulongs = MapSerializer(String.serializer(), ULongArraySerializer())

    private val ubytes = MapSerializer(String.serializer(), UByteArraySerializer())

    @Test
    fun `a UIntArray is written as its unsigned values`() {
        assertEquals(
            "a = [4000000000]\n",
            Toml.encodeToString(uints, mapOf("a" to uintArrayOf(4_000_000_000u))).assertSuccess(),
        )
    }

    @Test
    fun `a UIntArray reads unsigned values past Int`() {
        val decoded = Toml.decodeFromString(uints, "a = [4000000000]\n").assertSuccess()
        assertEquals(listOf(4_000_000_000u), decoded.getValue("a").toList())
    }

    @Test
    fun `a negative integer is out of range for a UIntArray`() {
        assertEquals(
            TomlDecodeError.OutOfRange("a[0]", "-1", "kotlin.UInt", position = TextPosition(1, 1)),
            Toml.decodeFromString(uints, "a = [-1]\n").assertError<TomlDecodeError.OutOfRange>(),
        )
    }

    @Test
    fun `a ULongArray value past Long is out of range when written`() {
        assertEquals(
            TomlEncodeError.OutOfRange("a[0]", "18446744073709551615"),
            Toml.encodeToString(ulongs, mapOf("a" to ulongArrayOf(ULong.MAX_VALUE))).assertError<TomlEncodeError.OutOfRange>(),
        )
    }

    @Test
    fun `a ULongArray reads up to Long's largest value`() {
        val decoded = Toml.decodeFromString(ulongs, "a = [9223372036854775807]\n").assertSuccess()
        assertEquals(listOf(Long.MAX_VALUE.toULong()), decoded.getValue("a").toList())
    }

    @Test
    fun `a UByteArray is written as its unsigned values and read back`() {
        val text = Toml.encodeToString(ubytes, mapOf("a" to ubyteArrayOf(255u))).assertSuccess()
        assertEquals("a = [255]\n", text)
        assertEquals(listOf<UByte>(255u), Toml.decodeFromString(ubytes, text).assertSuccess().getValue("a").toList())
    }

    @Test
    fun `an integer past UByte is out of range for a UByteArray`() {
        assertEquals(
            TomlDecodeError.OutOfRange("a[0]", "256", "kotlin.UByte", position = TextPosition(1, 1)),
            Toml.decodeFromString(ubytes, "a = [256]\n").assertError<TomlDecodeError.OutOfRange>(),
        )
    }

    @Test
    fun `a map with Char keys round trips`() {
        val marks = Marks(mapOf('a' to 1))
        val text = Toml.encodeToString(marks).assertSuccess()
        assertEquals("[m]\na = 1\n", text)
        assertEquals(marks, Toml.decodeFromString<Marks>(text).assertSuccess())
    }

    @Test
    fun `a map whose contextual key serializer writes a string round trips`() {
        val toml = Toml { serializersModule = SerializersModule { contextual(Tag::class, TagSerializer) } }
        val text = toml.encodeToString(Tagged(mapOf(Tag("a") to 1))).assertSuccess()
        assertEquals("[counts]\na = 1\n", text)
        val decoded = toml.decodeFromString<Tagged>(text).assertSuccess()
        assertEquals(mapOf("a" to 1), decoded.counts.mapKeys { it.key.name })
    }

    @Test
    fun `a map whose contextual key has no serializer in the module is refused when read`() {
        assertEquals(
            TomlDecodeError.NonStringMapKey("counts", position = TextPosition(1, 2)),
            Toml.decodeFromString<Tagged>("[counts]\na = 1\n").assertError<TomlDecodeError.NonStringMapKey>(),
        )
    }

    @Test
    fun `a subtype field named like the discriminator is refused when read`() {
        assertEquals(
            TomlDecodeError.DiscriminatorConflict("", "type"),
            Toml.decodeFromString<Signal>("type = 'c'\nr = 1\n").assertError<TomlDecodeError.DiscriminatorConflict>(),
        )
    }

    @Test
    fun `a subtype field named like the discriminator is refused when written even at its default`() {
        assertEquals(
            TomlEncodeError.DiscriminatorConflict("", "type"),
            Toml.encodeToString<Signal>(Coded(r = 1)).assertError<TomlEncodeError.DiscriminatorConflict>(),
        )
    }

    @Test
    fun `a lone high surrogate Char is not Unicode`() {
        assertEquals(TomlEncodeError.NotUnicode("mark"), Toml.encodeToString(Mark('\uD83D')).assertError<TomlEncodeError.NotUnicode>())
    }

    @Test
    fun `a lone low surrogate in a string is not Unicode`() {
        assertEquals(TomlEncodeError.NotUnicode("text"), Toml.encodeToString(Text("a\uDE00b")).assertError<TomlEncodeError.NotUnicode>())
    }

    @Test
    fun `a high surrogate ending a string is not Unicode`() {
        assertEquals(TomlEncodeError.NotUnicode("text"), Toml.encodeToString(Text("a\uD83D")).assertError<TomlEncodeError.NotUnicode>())
    }

    @Test
    fun `a map key with a lone surrogate is not Unicode`() {
        assertEquals(
            TomlEncodeError.NotUnicode("ports"),
            Toml.encodeToString(Ports(mapOf("\uD800" to 1))).assertError<TomlEncodeError.NotUnicode>(),
        )
    }

    @Test
    fun `a lone surrogate is not Unicode when written into a document`() {
        assertEquals(TomlEncodeError.NotUnicode("mark"), Toml.encodeToDocument(Mark('\uDC00')).assertError<TomlEncodeError.NotUnicode>())
        val document = TomlDocument.parse("mark = 'a'\n").assertSuccess()
        assertEquals(TomlEncodeError.NotUnicode("mark"), Toml.encodeInto(document, Mark('\uDC00')).assertError<TomlEncodeError.NotUnicode>())
        assertEquals("mark = 'a'\n", document.toString())
    }

    @Test
    fun `a surrogate pair in a string round trips`() {
        val text = Text("smile 😀")
        assertEquals(text, Toml.decodeFromString<Text>(Toml.encodeToString(text).assertSuccess()).assertSuccess())
    }

    @Test
    fun `an inline class wrapping a number is refused as a root on both sides`() {
        val unsupported = RawPort.serializer().descriptor.serialName
        assertEquals(TomlEncodeError.UnsupportedRoot(unsupported), Toml.encodeToString(RawPort(1)).assertError<TomlEncodeError.UnsupportedRoot>())
        assertEquals(
            TomlDecodeError.UnsupportedRoot(unsupported),
            Toml.decodeFromString<RawPort>("v = 1\n").assertError<TomlDecodeError.UnsupportedRoot>(),
        )
    }

    @Test
    fun `an inline class wrapping a class is a root`() {
        val boxed = BoxedServer(Server("a"))
        val text = Toml.encodeToString(boxed).assertSuccess()
        assertEquals("host = \"a\"\n", text)
        assertEquals(boxed, Toml.decodeFromString<BoxedServer>(text).assertSuccess())
    }

    @Test
    fun `the new errors encode with their own type`() {
        assertEquals(
            """{"type":"NotUnicode","path":"mark"}""",
            Json.encodeToString(TomlEncodeError.serializer(), TomlEncodeError.NotUnicode("mark")),
        )
        assertEquals(
            """{"type":"DiscriminatorConflict","path":"","key":"type"}""",
            Json.encodeToString(TomlDecodeError.serializer(), TomlDecodeError.DiscriminatorConflict("", "type")),
        )
    }
}
