package com.fromwau.kotoml.app

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
            AppError.serializer().descriptor to """
                CannotApply(step, itemType)
                FilterSyntax(filter, position, expected)
                InvalidJson(path, position)
                InvalidToml(path, error)
                JsonTooDeep(path, limit)
                NoSuchIndex(path, index, size)
                NoSuchKey(path, key)
                NotTaggedJson(path, at, reason)
                ReadFailed(path, failure)
            """.trimIndent(),
            ReadFailure.serializer().descriptor to """
                HomeUnknown()
                Io(detail)
                IsADirectory()
                NotFound()
                PermissionDenied()
            """.trimIndent(),
            TaggedJsonProblem.serializer().descriptor to """
                BadValue(tomlType, value)
                ExtraKey(key)
                MissingValue()
                NotUnicode()
                RootNotObject()
                UnknownType(tomlType)
                Untagged()
                ValueNotString()
            """.trimIndent(),
            Step.serializer().descriptor to """
                Index(index)
                Iterate()
                Key(key)
            """.trimIndent(),
            AppError.FilterSyntax.serializer().descriptor.getElementDescriptor(2) to """
                Dot
                KeyOrBracket
                Quote
                Escape
                IndexOrKey
                IndexInRange
                CloseBracket
                PipeOrEnd
            """.trimIndent(),
            Checked.serializer().descriptor to "Checked(valid)",
            CheckedFile.serializer().descriptor to "CheckedFile(path, valid, error)",
        )
        for ((descriptor, schema) in expected) {
            assertEquals(schema, descriptor.wireSchema(), descriptor.serialName)
        }
    }
}
