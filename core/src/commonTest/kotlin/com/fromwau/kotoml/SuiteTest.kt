package com.fromwau.kotoml

import com.fromwau.kern.result.Result
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The official toml-test suite, read from the `toml-test` submodule at the repository root. Each check runs
 * over every file a spec version's list names, parsing as that version of TOML, and a failure lists every file
 * that failed.
 */
class SuiteTest {

    private val root = "../toml-test/tests"

    private fun listed(version: String): List<String> = readBytes("$root/files-toml-$version")
        .decodeToString()
        .lines()
        .filter { it.endsWith(".toml") }

    private fun valid(version: String): List<String> = listed(version).filter { it.startsWith("valid/") }

    private fun invalid(version: String): List<String> = listed(version).filter { it.startsWith("invalid/") }

    private fun versionOf(list: String): TomlVersion = if (list == "1.0.0") TomlVersion.V1_0 else TomlVersion.V1_1

    private fun parse(bytes: ByteArray, version: String): Result<TomlDocument, TomlParseError> =
        TomlDocument.parse(bytes, versionOf(version))

    private fun expected(file: String): Tagged =
        parseTaggedJson(readBytes("$root/${file.removeSuffix(".toml")}.json").decodeToString())

    private fun assertNoFailures(version: String, check: String, failures: List<String>) {
        assertEquals(emptyList(), failures, "$check failed for ${failures.size} files of toml $version")
    }

    /** Every valid file decodes to exactly the values its `.json` names. */
    private fun decodes(version: String) {
        val failures = valid(version).filter { file ->
            val parsed = parse(readBytes("$root/$file"), version)
            parsed !is Result.Success || !parsed.value.toTagged().sameAs(expected(file))
        }
        assertNoFailures(version, "decoding", failures)
    }

    /** Every valid file survives a parse and `toString()` byte for byte. */
    private fun roundTrips(version: String) {
        val failures = valid(version).filter { file ->
            val bytes = readBytes("$root/$file")
            val parsed = parse(bytes, version)
            parsed !is Result.Success || !parsed.value.toString().encodeToByteArray().contentEquals(bytes)
        }
        assertNoFailures(version, "the round trip", failures)
    }

    /** Every valid file's tagged JSON, encoded by the port and decoded again, gives the same values. */
    private fun encodes(version: String) {
        val failures = valid(version).filter { file ->
            val expected = expected(file)
            val document = expected.toDocument()
            val reparsed = document?.let { TomlDocument.parse(it.toString(), versionOf(version)) }
            reparsed !is Result.Success || !reparsed.value.toTagged().sameAs(expected)
        }
        assertNoFailures(version, "encoding", failures)
    }

    /** Every invalid file is refused. */
    private fun refuses(version: String) {
        val failures = invalid(version).filter { file -> parse(readBytes("$root/$file"), version) is Result.Success }
        assertNoFailures(version, "refusing", failures)
    }

    @Test
    fun decodesValidToml100() = decodes("1.0.0")

    @Test
    fun decodesValidToml110() = decodes("1.1.0")

    @Test
    fun roundTripsValidToml100() = roundTrips("1.0.0")

    @Test
    fun roundTripsValidToml110() = roundTrips("1.1.0")

    @Test
    fun encodesValidToml100() = encodes("1.0.0")

    @Test
    fun encodesValidToml110() = encodes("1.1.0")

    @Test
    fun refusesInvalidToml100() = refuses("1.0.0")

    @Test
    fun refusesInvalidToml110() = refuses("1.1.0")
}
