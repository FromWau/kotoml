package com.fromwau.kotoml.serialization

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The names every error and the types inside one put on the `--json` wire. A change here is a breaking change for
 * programs that read them: rename on purpose, and update this test with it.
 */
class WireSchemaTest {

    @Test
    fun `every error type keeps its wire names and fields`() {
        val expected = listOf(
            TomlDecodeError.serializer().descriptor to """
                DatetimeKindMismatch(path, expected, found)
                DiscriminatorConflict(path, key)
                LeapSecond(path)
                MissingKey(path)
                NoSerializer(type)
                NonStringMapKey(path)
                NullValue(path)
                NullableType(path)
                OutOfRange(path, value, target)
                Parse(error)
                Serializer(path, detail)
                TypeMismatch(path, expected, found)
                UnknownEnumValue(path, value, names, suggestion)
                UnknownKey(path)
                UnknownSubtype(path, name, names, suggestion)
                Unparsable(path, text, target)
                UnsupportedRoot(serialName)
            """.trimIndent(),
            TomlEncodeError.serializer().descriptor to """
                DiscriminatorConflict(path, key)
                NoSerializer(type)
                NonStringMapKey(path)
                NotUnicode(path)
                NullValue(path)
                NullableType(path)
                OutOfRange(path, value)
                Serializer(path, detail)
                TooDeep(path)
                UnsupportedRoot(serialName)
            """.trimIndent(),
        )
        for ((descriptor, schema) in expected) {
            assertEquals(schema, descriptor.wireSchema(), descriptor.serialName)
        }
    }
}
