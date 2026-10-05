package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.measureTime

/**
 * Printing takes time in proportion to the document's size. Each test prints a document of [SMALL] and one of
 * [LARGE] tables and compares the times: eight times the tables takes about eight times as long when printing is
 * linear and about sixty-four times as long when it is quadratic, so the bound of [MAX_RATIO] leaves room for a busy
 * machine either way. Each time is the fastest of several prints, after one print to warm up.
 */
class PrintScalingTest {

    private fun builtTables(count: Int): TomlDocument = buildTomlDocument {
        for (index in 0 until count) {
            table("t$index") {
                "a" to index
                table("x") { "b" to 1 }
                table("y") { "c" to 2 }
            }
        }
    }.getOrFail()

    private fun builtArrayOfTables(count: Int): TomlDocument = buildTomlDocument {
        arrayOfTables("servers") {
            for (index in 0 until count) {
                table {
                    "a" to index
                    table("x") { "b" to 1 }
                }
            }
        }
    }.getOrFail()

    private fun parsedArrayOfTables(count: Int): TomlDocument =
        TomlDocument.parse(builtArrayOfTables(count).toString()).getOrFail()

    private fun fastestPrint(document: TomlDocument): Duration {
        document.toString()
        return List(RUNS) { measureTime { document.toString() } }.min()
    }

    private fun assertPrintsInLinearTime(document: (Int) -> TomlDocument) {
        val small = fastestPrint(document(SMALL))
        val large = fastestPrint(document(LARGE))
        val ratio = large / small
        assertTrue(ratio < MAX_RATIO, "$LARGE tables printed $ratio times as long as $SMALL ($large against $small)")
    }

    @Test
    fun `a document of tables built in code prints in linear time`() {
        assertPrintsInLinearTime(::builtTables)
    }

    @Test
    fun `an array of tables built in code prints in linear time`() {
        assertPrintsInLinearTime(::builtArrayOfTables)
    }

    @Test
    fun `an array of tables read from text prints in linear time`() {
        assertPrintsInLinearTime(::parsedArrayOfTables)
    }

    private companion object {
        const val SMALL = 1000
        const val LARGE = 8000
        const val RUNS = 5
        const val MAX_RATIO = 24.0
    }
}
