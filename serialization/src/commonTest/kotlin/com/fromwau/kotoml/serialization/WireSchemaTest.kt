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
                DatetimeKindMismatch(path, expected, found, position)
                DiscriminatorConflict(path, key, position)
                LeapSecond(path, position)
                MissingKey(path, position)
                NoSerializer(type, path, position)
                NonStringMapKey(path, position)
                NullValue(path, position)
                NullableType(path, position)
                OutOfRange(path, value, target, position)
                Parse(error)
                Serializer(path, detail, position)
                TypeMismatch(path, expected, found, value, position)
                UnknownEnumValue(path, value, names, suggestion, position)
                UnknownKey(path, suggestion, position)
                UnknownSubtype(path, name, names, suggestion, position)
                Unparsable(path, text, target, position)
                UnsupportedRoot(serialName)
            """.trimIndent(),
            TomlEncodeError.serializer().descriptor to """
                DiscriminatorConflict(path, key)
                NoSerializer(type, path)
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
