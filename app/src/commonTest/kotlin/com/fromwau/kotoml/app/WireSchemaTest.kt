package com.fromwau.kotoml.app

import kotlinx.serialization.serializer
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
            AppError.serializer() to """
                CannotApply(step, itemType)
                FilterSyntax(filter, position, expected)
                InvalidJson(position)
                InvalidToml(error)
                JsonTooDeep(limit)
                NoSuchIndex(path, index, size)
                NoSuchKey(path, key)
                NotTaggedJson(path, reason)
                ReadFailed(path, failure)
            """.trimIndent(),
            ReadFailure.serializer() to """
                Io(detail)
                IsADirectory()
                NotFound()
                PermissionDenied()
            """.trimIndent(),
            TaggedJsonProblem.serializer() to """
                BadValue(tomlType, value)
                ExtraKey(key)
                MissingValue()
                NotUnicode()
                RootNotObject()
                UnknownType(tomlType)
                Untagged()
                ValueNotString()
            """.trimIndent(),
            Step.serializer() to """
                Index(index)
                Iterate()
                Key(key)
            """.trimIndent(),
            serializer<FilterExpected>() to """
                Dot
                KeyOrBracket
                Quote
                Escape
                IndexOrKey
                IndexInRange
                CloseBracket
                PipeOrEnd
            """.trimIndent(),
            Checked.serializer() to "Checked(valid)",
        )
        for ((serializer, schema) in expected) {
            assertEquals(schema, serializer.wireSchema(), serializer.descriptor.serialName)
        }
    }
}
