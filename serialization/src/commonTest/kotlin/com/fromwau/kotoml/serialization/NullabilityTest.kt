package com.fromwau.kotoml.serialization

import com.fromwau.kern.result.Result
import com.fromwau.kotoml.TomlDocument
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private data class Owned(
    val name: String,
    val owner: String? = null,
)

@Serializable
private data class Unnamed(
    val name: String?,
    val port: Int,
)

@Serializable
private data class MaybePorts(val ports: List<Int?>)

@Serializable
private data class Limits(val limits: Map<String, Int?>)

@Serializable
private data class Lease(val minutes: Int)

@Serializable
private data class Leased(val lease: Lease? = null)

@Serializable
private data class Pack(val listeners: List<Unnamed>)

@Serializable
private data class Plain(
    val name: String,
    val port: Int = 8080,
    val tags: List<String> = emptyList(),
)

/** TOML has no null and a missing key means the field's default, so kotoml refuses every nullable type, by type. */
class NullabilityTest {

    @Test
    fun `a nullable field with a null default is refused when writing`() {
        assertEquals(Result.Error(TomlEncodeError.NullableType("owner")), Toml.encodeToString(Owned("n")))
    }

    @Test
    fun `a nullable field with a null default is refused when reading`() {
        assertEquals(
            Result.Error(TomlDecodeError.NullableType("owner")),
            Toml.decodeFromString<Owned>("name = \"n\"\n"),
        )
    }

    @Test
    fun `a nullable field without a default is refused even when it holds a value`() {
        assertEquals(Result.Error(TomlEncodeError.NullableType("name")), Toml.encodeToString(Unnamed("n", 1)))
    }

    @Test
    fun `a nullable list element type is refused when writing and reading`() {
        assertEquals(
            Result.Error(TomlEncodeError.NullableType("ports[]")),
            Toml.encodeToString(MaybePorts(listOf(80))),
        )
        assertEquals(
            Result.Error(TomlDecodeError.NullableType("ports[]")),
            Toml.decodeFromString<MaybePorts>("ports = [80]\n"),
        )
    }

    @Test
    fun `a nullable map value type is refused`() {
        assertEquals(
            Result.Error(TomlEncodeError.NullableType("limits[]")),
            Toml.encodeToString(Limits(mapOf("cpu" to 2))),
        )
    }

    @Test
    fun `a nullable nested class is refused`() {
        assertEquals(Result.Error(TomlEncodeError.NullableType("lease")), Toml.encodeToString(Leased(Lease(5))))
    }

    @Test
    fun `a nullable field inside list elements is named under the list`() {
        assertEquals(
            Result.Error(TomlEncodeError.NullableType("listeners[].name")),
            Toml.encodeToString(Pack(listOf(Unnamed("n", 1)))),
        )
    }

    @Test
    fun `a nullable type is refused whatever encodeDefaults says`() {
        assertEquals(
            Result.Error(TomlEncodeError.NullableType("owner")),
            Toml { encodeDefaults = true }.encodeToString(Owned("n")),
        )
    }

    @Test
    fun `encodeInto refuses a nullable type and leaves the document as it was`() {
        val document = TomlDocument.parse("name = \"n\"\n").unwrap()
        assertEquals(Result.Error(TomlEncodeError.NullableType("owner")), Toml.encodeInto(document, Owned("n")))
        assertEquals("name = \"n\"\n", document.toString())
    }

    @Test
    fun `a model without nullable types reads missing keys as their defaults`() {
        assertEquals(Result.Success(Plain("n")), Toml.decodeFromString<Plain>("name = \"n\"\n"))
        assertEquals(Result.Success("name = \"n\"\n"), Toml.encodeToString(Plain("n")))
    }
}
