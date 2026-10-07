package com.fromwau.kotoml

import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `TomlArray.isMultiline` reads whether a line break stands in the array's own text, and `setMultiline` puts the array
 * on one line, refused when that drops a comment unless forced, or across lines, one element per line.
 */
class ArrayMultilineTest {

    private fun parse(
        text: String,
        version: TomlVersion = TomlVersion.V1_1,
    ): TomlDocument = TomlDocument.parse(text, version).assertSuccess()

    private fun TomlDocument.array(vararg path: String): TomlArray {
        var item = this[path.first()].assertSuccess()
        for (key in path.drop(1)) item = item[key].assertSuccess()
        return item.arrayOrNull!!
    }

    /** Checks that [doc] prints [expected], which reads back as the data [doc] holds. */
    private fun assertPrints(
        expected: String,
        doc: TomlDocument,
    ) {
        assertEquals(expected, doc.toString())
        val reread = TomlDocument.parse(expected, doc.version).assertSuccess()
        assertTrue(reread.toTagged().sameAs(doc.toTagged()), "reads back as other data")
    }

    @Test
    fun `isMultiline reads a line break before or after an element or before the closing bracket`() {
        assertFalse(parse("a = [1, 2]\n").array("a").isMultiline)
        assertFalse(parse("a = [ 1 , 2 , ]\n").array("a").isMultiline)
        assertFalse(parse("a = []\n").array("a").isMultiline)
        assertTrue(parse("a = [\n  1,\n  2,\n]\n").array("a").isMultiline)
        assertTrue(parse("a = [1,\n2]\n").array("a").isMultiline)
        assertTrue(parse("a = [1, 2\n]\n").array("a").isMultiline)
        assertTrue(parse("a = [1 # one\n, 2]\n").array("a").isMultiline)
        assertTrue(parse("a = [\n]\n").array("a").isMultiline)
        assertTrue(parse("a = [ # c\n]\n").array("a").isMultiline)
    }

    @Test
    fun `isMultiline leaves out the line breaks inside an element`() {
        val doc = parse("a = [[1,\n2]]\nb = [\"\"\"x\ny\"\"\"]\nc = [{ d = [\n  1,\n] }]\n")
        assertFalse(doc.array("a").isMultiline)
        assertTrue(doc.array("a").getOrNull(0)!!.arrayOrNull!!.isMultiline)
        assertFalse(doc.array("b").isMultiline)
        assertFalse(doc.array("c").isMultiline)
    }

    @Test
    fun `format keeps what isMultiline reads of every array`() {
        val text = "a = [[1,\n2], [3, 4]]\nb = [ 1 ,\n 2 ]\nc = [\"\"\"x\ny\"\"\", [\n]]\nd = [ # c\n]\ne = []\n"
        val doc = parse(text)
        val arrays = listOf("a", "b", "c", "d", "e").flatMap { key ->
            val array = doc.array(key)
            listOf(array) + array.mapNotNull { it.arrayOrNull }
        }
        val before = arrays.map { it.isMultiline }
        doc.format()
        assertEquals(before, arrays.map { it.isMultiline })
    }

    @Test
    fun `an array across lines goes on one line keeping the comment after the closing bracket`() {
        val text = "a = [\n  1, # one\n  2,\n] # a\n"
        val doc = parse(text)
        val array = doc.array("a")
        val error = array.setMultiline(false).assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("a"), 1), error)
        assertEquals(text, doc.toString())
        array.setMultiline(false, force = true).assertSuccess()
        assertPrints("a = [1, 2] # a\n", doc)
        assertFalse(array.isMultiline)
    }

    @Test
    fun `a refusal counts every comment inside the brackets and names the key path`() {
        val text = "[t]\na = [ # open\n  1, # one\n  # above two\n  2\n  # end\n] # after\n"
        val doc = parse(text)
        val error = doc.array("t", "a").setMultiline(false).assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("t", "a"), 4), error)
        assertEquals(text, doc.toString())
        doc.array("t", "a").setMultiline(false, force = true).assertSuccess()
        assertPrints("[t]\na = [1, 2] # after\n", doc)
    }

    @Test
    fun `an array across lines without comments goes on one line without force`() {
        val doc = parse("a = [\n  1,\n  'two',\n  [3],\n]\nb = 1\n")
        doc.array("a").setMultiline(false).assertSuccess()
        assertPrints("a = [1, 'two', [3]]\nb = 1\n", doc)
    }

    @Test
    fun `an array on one line goes across lines one element per line with a trailing comma`() {
        val doc = parse("a = [1, 'two', 3.0] # a\n")
        doc.array("a").setMultiline(true).assertSuccess()
        assertPrints("a = [\n  1,\n  'two',\n  3.0,\n] # a\n", doc)
        assertTrue(doc.array("a").isMultiline)
    }

    @Test
    fun `the elements are indented from the line holding the opening bracket`() {
        val doc = parse("[t]\n  a = [1, 2]\n  b = [\n    [3, 4],\n  ]\n  c = { d = [5] }\n")
        doc.array("t", "a").setMultiline(true).assertSuccess()
        doc.array("t", "b").getOrNull(0)!!.arrayOrNull!!.setMultiline(true).assertSuccess()
        doc.array("t", "c", "d").setMultiline(true).assertSuccess()
        val expected = "[t]\n  a = [\n    1,\n    2,\n  ]\n  b = [\n    [\n      3,\n      4,\n    ],\n  ]\n" +
            "  c = { d = [\n    5,\n  ] }\n"
        assertPrints(expected, doc)
    }

    @Test
    fun `only the array itself changes and the arrays inside keep their layout`() {
        val doc = parse("a = [[1, 2], [3,\n4]]\n")
        doc.array("a").setMultiline(true).assertSuccess()
        assertPrints("a = [\n  [1, 2],\n  [3,\n4],\n]\n", doc)
        doc.array("a").setMultiline(false).assertSuccess()
        assertPrints("a = [[1, 2], [3,\n4]]\n", doc)
    }

    @Test
    fun `the comments inside an inner array stay when the outer one goes on one line`() {
        val doc = parse("a = [\n  [\n    1, # one\n  ],\n  2,\n]\n")
        doc.array("a").setMultiline(false).assertSuccess()
        assertPrints("a = [[\n    1, # one\n  ], 2]\n", doc)
    }

    @Test
    fun `asking for the layout an array has succeeds and changes nothing`() {
        val texts = listOf("a = [ 1 , 2 ] # a\n", "a = [1,\n  2 # two\n] # a\n", "a = []\n", "a = [\n]\n")
        for (text in texts) {
            val doc = parse(text)
            val array = doc.array("a")
            array.setMultiline(array.isMultiline).assertSuccess()
            array.setMultiline(array.isMultiline, force = true).assertSuccess()
            assertEquals(text, doc.toString())
        }
    }

    @Test
    fun `setting a layout twice gives the same text`() {
        val doc = parse("a = [1, [2, 3], { b = 4 }]\n")
        val array = doc.array("a")
        array.setMultiline(true).assertSuccess()
        val across = doc.toString()
        array.setMultiline(true).assertSuccess()
        assertEquals(across, doc.toString())
        array.setMultiline(false).assertSuccess()
        val oneLine = doc.toString()
        array.setMultiline(false).assertSuccess()
        assertEquals(oneLine, doc.toString())
        assertEquals("a = [1, [2, 3], { b = 4 }]\n", oneLine)
    }

    @Test
    fun `an empty array goes across lines as a line break before the closing bracket`() {
        val doc = parse("[t]\n  a = []\n")
        doc.array("t", "a").setMultiline(true).assertSuccess()
        assertPrints("[t]\n  a = [\n  ]\n", doc)
        assertTrue(doc.array("t", "a").isMultiline)
        doc.array("t", "a").setMultiline(false).assertSuccess()
        assertPrints("[t]\n  a = []\n", doc)
    }

    @Test
    fun `an empty array holding a comment goes on one line only when forced`() {
        val doc = parse("a = [\n  # nothing yet\n]\n")
        doc.array("a").setMultiline(false).assertError<TomlEditError.CommentsWouldBeLost>()
        doc.array("a").setMultiline(false, force = true).assertSuccess()
        assertPrints("a = []\n", doc)
    }

    @Test
    fun `the new lines end as the document's lines do`() {
        val doc = parse("a = [1, 2]\r\nb = 1\r\n")
        doc.array("a").setMultiline(true).assertSuccess()
        assertPrints("a = [\r\n  1,\r\n  2,\r\n]\r\nb = 1\r\n", doc)
        doc.array("a").setMultiline(false).assertSuccess()
        assertPrints("a = [1, 2]\r\nb = 1\r\n", doc)
    }

    @Test
    fun `a TOML 1_0 document takes both layouts and stays TOML 1_0`() {
        val doc = parse("a = [1, 2]\nt = { b = [3] }\n", TomlVersion.V1_0)
        doc.array("a").setMultiline(true).assertSuccess()
        doc.array("t", "b").setMultiline(true).assertSuccess()
        assertPrints("a = [\n  1,\n  2,\n]\nt = { b = [\n  3,\n] }\n", doc)
        doc.array("a").setMultiline(false).assertSuccess()
        doc.array("t", "b").setMultiline(false).assertSuccess()
        assertPrints("a = [1, 2]\nt = { b = [3] }\n", doc)
    }

    @Test
    fun `an array in an array of tables names the key path to it`() {
        val doc = parse("[[x]]\n[[x]]\na = [\n  1, # one\n]\n")
        val array = doc["x"][1]["a"].assertSuccess().arrayOrNull!!
        val error = array.setMultiline(false).assertError<TomlEditError.CommentsWouldBeLost>()
        assertEquals(TomlEditError.CommentsWouldBeLost(listOf("x", "a"), 1), error)
    }

    @Test
    fun `an array stored nowhere changes its layout too`() {
        val array = tomlArrayOf(Value.Integer(1L), Value.String("two")).assertSuccess()
        array.setMultiline(true).assertSuccess()
        assertEquals("[\n  1,\n  \"two\",\n]", array.toString())
        array.setMultiline(false).assertSuccess()
        assertEquals("[1, \"two\"]", array.toString())
    }
}
