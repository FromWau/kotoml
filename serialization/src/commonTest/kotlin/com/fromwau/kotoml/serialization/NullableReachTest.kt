package com.fromwau.kotoml.serialization

import com.fromwau.kotoml.TextPosition
import com.fromwau.kern.result.Result
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.Test
import kotlin.test.assertEquals

private interface Pet

@Serializable
@SerialName("cat")
private data class Cat(val name: String?) : Pet

@Serializable
private data class Home(val pet: Pet)

@Serializable
private data class Note(val text: String?)

@Serializable
private data class Noted(@Contextual val note: Note)

@Serializable
private data class Tree(
    val name: String,
    val children: List<Tree> = emptyList(),
)

/** Writes and reads a null, as a hand-written serializer might. */
private object NullSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("NullSerializer", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: String,
    ) = encoder.encodeNull()

    override fun deserialize(decoder: Decoder): String = decoder.decodeNull() ?: ""
}

@Serializable
private data class Label(@Serializable(with = NullSerializer::class) val label: String)

@Suppress("UNCHECKED_CAST")
private val catsByDefault = SerializersModule {
    polymorphicDefaultSerializer(Pet::class) { Cat.serializer() as SerializationStrategy<Pet> }
    polymorphicDefaultDeserializer(Pet::class) { Cat.serializer() }
}

/**
 * How far the refusal of nullable types reaches: registered subclasses and contextual types are checked before
 * anything is written or read, and a null only a value can reveal is refused when it reaches the encoder or decoder.
 */
class NullableReachTest {

    private val registered = Toml {
        serializersModule = SerializersModule {
            polymorphic(Pet::class) { subclass(Cat::class) }
        }
    }

    @Test
    fun `a nullable field of a registered subclass is refused when writing and reading`() {
        assertEquals(Result.Error(TomlEncodeError.NullableType("pet.name")), registered.encodeToString(Home(Cat("c"))))
        assertEquals(
            Result.Error(TomlDecodeError.NullableType("pet.name")),
            registered.decodeFromString<Home>("[pet]\ntype = \"cat\"\nname = \"c\"\n"),
        )
    }

    @Test
    fun `a nullable field of a contextual type is refused`() {
        val toml = Toml { serializersModule = SerializersModule { contextual(Note::class, Note.serializer()) } }
        assertEquals(Result.Error(TomlEncodeError.NullableType("note.text")), toml.encodeToString(Noted(Note("n"))))
    }

    @Test
    fun `a type that holds itself is written and read`() {
        val tree = Tree("a", listOf(Tree("b")))
        val text = "name = \"a\"\n\n[[children]]\nname = \"b\"\n"
        assertEquals(Result.Success(text), Toml.encodeToString(tree))
        assertEquals(Result.Success(tree), Toml.decodeFromString<Tree>(text))
    }

    @Test
    fun `a nullable field of a subclass only the default serializer finds is refused when it is written`() {
        val toml = Toml { serializersModule = catsByDefault }
        assertEquals(Result.Error(TomlEncodeError.NullableType("pet.name")), toml.encodeToString(Home(Cat("c"))))
    }

    @Test
    fun `a nullable field of a subclass only the default deserializer finds is refused when it is read`() {
        val toml = Toml { serializersModule = catsByDefault }
        assertEquals(
            Result.Error(TomlDecodeError.NullableType("pet.name", position = TextPosition(3, 1))),
            toml.decodeFromString<Home>("[pet]\ntype = \"cat\"\nname = \"c\"\n"),
        )
    }

    @Test
    fun `a null a serializer writes or asks for is refused`() {
        assertEquals(Result.Error(TomlEncodeError.NullValue("label")), Toml.encodeToString(Label("l")))
        assertEquals(
            Result.Error(TomlDecodeError.NullValue("label", position = TextPosition(1, 1))),
            Toml.decodeFromString<Label>("label = \"l\"\n"),
        )
    }
}
