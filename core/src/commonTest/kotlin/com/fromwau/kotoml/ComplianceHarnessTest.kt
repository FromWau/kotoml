package com.fromwau.kotoml

import com.fromwau.kern.result.Result
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `toml_edit/tests/decoder_compliance.rs` and `encoder_compliance.rs`: toml-test's harness at spec 1.1.0, plus
 * upstream's own fixtures under `tests/fixtures`. Every refusal is checked against upstream's error snapshot
 * under `tests/snapshots`, copied unedited.
 */
class ComplianceHarnessTest {

    private val suite = "../toml-test/tests"
    private val upstream = "src/commonTest/snapshots/toml_edit"

    private class Case(val name: String, val fixture: ByteArray, val expected: () -> Tagged)

    private class InvalidCase(val name: String, val fixture: ByteArray)

    private fun valid(): List<Case> {
        val listed = readBytes("$suite/files-toml-1.1.0")
            .decodeToString()
            .lines()
            .filter { it.startsWith("valid/") && it.endsWith(".toml") }
            .map { file ->
                Case(file, readBytes("$suite/$file")) {
                    parseTaggedJson(readBytes("$suite/${file.removeSuffix(".toml")}.json").decodeToString())
                }
            }
        val extended = walkFiles("$upstream/fixtures/valid")
            .filter { it.endsWith(".toml") }
            .map { file ->
                Case("valid/$file", readBytes("$upstream/fixtures/valid/$file")) {
                    val json = "$upstream/fixtures/valid/${file.removeSuffix(".toml")}.json"
                    parseTaggedJson(readBytes(json).decodeToString())
                }
            }
        return listed + extended
    }

    private fun invalid(): List<InvalidCase> {
        val listed = readBytes("$suite/files-toml-1.1.0")
            .decodeToString()
            .lines()
            .filter { it.startsWith("invalid/") && it.endsWith(".toml") }
            .map { InvalidCase(it, readBytes("$suite/$it")) }
        val extended = walkFiles("$upstream/fixtures/invalid")
            .filter { it.endsWith(".toml") }
            .map { InvalidCase("invalid/$it", readBytes("$upstream/fixtures/invalid/$it")) }
        return listed + extended
    }

    /** upstream's `Decoder::decode`: refused when the text is not UTF-8, else the parse's outcome. */
    private sealed interface Outcome {
        data class Decoded(val document: TomlDocument) : Outcome

        data class Refused(val message: String) : Outcome
    }

    private fun outcome(fixture: ByteArray): Outcome {
        return when (val parsed = TomlDocument.parse(fixture)) {
            is Result.Success -> Outcome.Decoded(parsed.value)
            is Result.Error -> {
                val message = parsed.error.render(fixture.decodeToString())
                // Upstream's harness prints Rust's `Utf8Error` here, which ends without the newline a `TomlError` has.
                val utf8 = parsed.error.reason is TomlParseError.Reason.InvalidUtf8
                Outcome.Refused(if (utf8) message.removeSuffix("\n") else message)
            }
        }
    }

    @Test
    fun decoderComplianceValid() {
        val failures = valid().filter { case ->
            val decoded = outcome(case.fixture)
            decoded !is Outcome.Decoded || !decoded.document.toTagged().sameAs(case.expected())
        }
        assertEquals(emptyList(), failures.map { it.name })
    }

    @Test
    fun decoderComplianceInvalid() {
        val failures = invalid().mapNotNull { case ->
            when (val decoded = outcome(case.fixture)) {
                is Outcome.Decoded -> "${case.name}: should have failed"
                is Outcome.Refused -> {
                    val snapshot = readBytes("$upstream/${case.name.removeSuffix(".toml")}.stderr").decodeToString()
                    if (normalizeNewlines(snapshot) == normalizeNewlines(decoded.message)) {
                        null
                    } else {
                        "${case.name}: expected\n$snapshot\nbut was\n${decoded.message}"
                    }
                }
            }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun encoderCompliance() {
        val failures = valid().filter { case ->
            val expected = case.expected()
            val encoded = expected.toDocument()?.toString()
            val decoded = encoded?.let { outcome(it.encodeToByteArray()) }
            decoded !is Outcome.Decoded || !decoded.document.toTagged().sameAs(expected)
        }
        assertEquals(emptyList(), failures.map { it.name })
    }
}
