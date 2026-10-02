package com.fromwau.kotoml.parser

import kotlin.test.Test

class ParseValueTest {

    private fun t(input: String, snapshot: String) {
        val actual = EventResults(input)
        val tokens = TomlSource(input).lex().intoVec()
        parseValue(tokens, actual.receiver, actual.sink)
        actual.validate("testsuite__parse_value__$snapshot")
    }

    private fun tRecurse(input: String, maxDepth: UInt, snapshot: String) {
        val actual = EventResults(input)
        val tokens = TomlSource(input).lex().intoVec()
        parseValue(tokens, RecursionGuard(actual.receiver, maxDepth), actual.sink)
        actual.validate("testsuite__parse_value__$snapshot")
    }

    @Test
    fun valueEmpty() {
        t("", "value_empty")
    }

    @Test
    fun valueDatetime() {
        t("1979-05-27T00:32:00.999999", "value_datetime")
    }

    @Test
    fun valueNegativeInteger() {
        t("-239", "value_negative_integer")
    }

    @Test
    fun valueFloat() {
        t("1e200", "value_float")
    }

    @Test
    fun valueIntegerWithSeps() {
        t("9_224_617.445_991_228_313", "value_integer_with_seps")
    }

    @Test
    fun valueMlStringLiteralSingleLine() {
        t("'''I [dw]on't need \\d{2} apples'''", "value_ml_string_literal_single_line")
    }

    @Test
    fun valueMlStringLiteralMultiline() {
        t(
            """'''
The first newline is
trimmed in raw strings.
   All other whitespace
   is preserved.
'''""",
            "value_ml_string_literal_multiline",
        )
    }

    @Test
    fun valueStringEscapedUnicode() {
        t("\"Jos\\u00E9\\n\"", "value_string_escaped_unicode")
    }

    @Test
    fun valueStringEscaped() {
        t("\"\\\\\\\"\\b/\\f\\n\\r\\t\\u00E9\\U000A0000\"", "value_string_escaped")
    }

    @Test
    fun valueInlineTable() {
        t("{ hello = \"world\", a = 1}", "value_inline_table")
    }

    @Test
    fun valueArray() {
        t(
            "[ { x = 1, a = \"2\" }, {a = \"a\",b = \"b\",     c =    \"c\"} ]",
            "value_array",
        )
    }

    @Test
    fun arrayEmpty() {
        t("[]", "array_empty")
    }

    @Test
    fun arrayWhitespace() {
        t("[   ]", "array_whitespace")
    }

    @Test
    fun arrayIntegersNewlines() {
        t(
            """[
1, 2, 3
]""",
            "array_integers_newlines",
        )
    }

    @Test
    fun arrayIntegersTrailingComment() {
        t(
            """[
1,
2, # this is ok
]""",
            "array_integers_trailing_comment",
        )
    }

    @Test
    fun arrayCommentsOnly() {
        t(
            """[# comment
# comment2


]""",
            "array_comments_only",
        )
    }

    @Test
    fun arrayIntegersSurroundedComment() {
        t(
            """[# comment
# comment2
    1

#sd
,
# comment3

]""",
            "array_integers_surrounded_comment",
        )
    }

    @Test
    fun arrayInteger() {
        t("[1]", "array_integer")
    }

    @Test
    fun arrayTrailingComma() {
        t("[1,]", "array_trailing_comma")
    }

    @Test
    fun arrayStringTypes() {
        t(
            "[ \"all\", 'strings', \"\"\"are the same\"\"\", '''type''']",
            "array_string_types",
        )
    }

    @Test
    fun arrayIntegersTrailingComma() {
        t("[ 100, -2,]", "array_integers_trailing_comma")
    }

    @Test
    fun arrayIntegers() {
        t("[1, 2, 3]", "array_integers")
    }

    @Test
    fun arrayFloats() {
        t("[1.1, 2.1, 3.1]", "array_floats")
    }

    @Test
    fun arrayStrings() {
        t("[\"a\", \"b\", \"c\"]", "array_strings")
    }

    @Test
    fun arrayNestedSameType() {
        t("[ [ 1, 2 ], [3, 4, 5] ]", "array_nested_same_type")
    }

    @Test
    fun arrayNestedMultipleTypes() {
        t("[ [ 1, 2 ], [\"a\", \"b\", \"c\"] ]", "array_nested_multiple_types")
    }

    @Test
    fun arrayInlineTable() {
        t(
            "[ { x = 1, a = \"2\" }, {a = \"a\",b = \"b\",     c =    \"c\"} ]",
            "array_inline_table",
        )
    }

    @Test
    fun arrayNoClose() {
        t("[", "array_no_close")
    }

    @Test
    fun arrayOnlyComma() {
        t("[,]", "array_only_comma")
    }

    @Test
    fun arrayLeadingComma() {
        t("[,2]", "array_leading_comma")
    }

    @Test
    fun arrayExtraTrailingComma() {
        t("[1e165,,]", "array_extra_trailing_comma")
    }

    @Test
    fun datetimeOffsetLowerT() {
        t("1979-05-27t07:32:00Z", "datetime_offset_lower_t")
    }

    @Test
    fun datetimeOffsetSpace() {
        t("1979-05-27 07:32:00Z", "datetime_offset_space")
    }

    @Test
    fun datetimeOffsetZ() {
        t("1979-05-27T07:32:00Z", "datetime_offset_z")
    }

    @Test
    fun datetimeOffsetLowerZ() {
        t("1979-05-27T07:32:00z", "datetime_offset_lower_z")
    }

    @Test
    fun datetimeOffsetHour() {
        t("1979-05-27T00:32:00-07:00", "datetime_offset_hour")
    }

    @Test
    fun datetimeOffsetMinutes() {
        t("1979-05-27T00:32:00-00:36", "datetime_offset_minutes")
    }

    @Test
    fun datetimeLocal() {
        t("1979-05-27T07:32:00", "datetime_local")
    }

    @Test
    fun datetimeLocalFractionalSeconds() {
        t("1979-05-27T00:32:00.999999", "datetime_local_fractional_seconds")
    }

    @Test
    fun datetimeLocalFractionalSecondsTruncated() {
        t("1987-07-05T17:45:00.123456789012345Z", "datetime_local_fractional_seconds_truncated")
    }

    @Test
    fun datetimeDate() {
        t("1979-05-27", "datetime_date")
    }

    @Test
    fun datetimeTime() {
        t("07:32:00", "datetime_time")
    }

    @Test
    fun datetimeTimeFractionalSeconds() {
        t("00:32:00.999999", "datetime_time_fractional_seconds")
    }

    @Test
    fun inlineTableEmpty() {
        t("{}", "inline_table_empty")
    }

    @Test
    fun inlineTableWs() {
        t("{   }", "inline_table_ws")
    }

    @Test
    fun inlineTableKeyValue() {
        t("{a = 1e165}", "inline_table_key_value")
    }

    @Test
    fun inlineTableMultipleKeyValues() {
        t("{ hello = \"world\", a = 1}", "inline_table_multiple_key_values")
    }

    @Test
    fun inlineTableDottedKey() {
        t("{ hello.world = \"a\" }", "inline_table_dotted_key")
    }

    @Test
    fun inlineTableOnlyComma() {
        t("{,}", "inline_table_only_comma")
    }

    @Test
    fun inlineTableLeadingComma() {
        t("{ , hello  = \"a\" }", "inline_table_leading_comma")
    }

    @Test
    fun inlineTableTrailingComma() {
        t("{ hello  = \"a\", }", "inline_table_trailing_comma")
    }

    @Test
    fun inlineTableNoComma() {
        t("{ hello = \"a\" world = \"b\" }", "inline_table_no_comma")
    }

    @Test
    fun inlineTableNoEq() {
        t("{ hello  \"a\" }", "inline_table_no_eq")
    }

    @Test
    fun inlineTableNoClose() {
        t("{a = 1e165", "inline_table_no_close")
    }

    @Test
    fun inlineTableRepeatedKey() {
        t("{ hello = \"world\", a = 2, hello = 1}", "inline_table_repeated_key")
    }

    @Test
    fun integer() {
        t("42", "integer")
    }

    @Test
    fun integerPositive() {
        t("+99", "integer_positive")
    }

    @Test
    fun integerZero() {
        t("0", "integer_zero")
    }

    @Test
    fun integerNegative() {
        t("-17", "integer_negative")
    }

    @Test
    fun integerOneSep() {
        t("1_000", "integer_one_sep")
    }

    @Test
    fun integerMultipleSeps() {
        t("5_349_221", "integer_multiple_seps")
    }

    @Test
    fun integerEveryOtherSep() {
        t("1_2_3_4_5", "integer_every_other_sep")
    }

    @Test
    fun integerNegSep() {
        t("-_1", "integer_neg_sep")
    }

    @Test
    fun integerPosSep() {
        t("+_1", "integer_pos_sep")
    }

    @Test
    fun integerLeadingSep() {
        t("_1", "integer_leading_sep")
    }

    @Test
    fun integerTrailingSep() {
        t("1_", "integer_trailing_sep")
    }

    @Test
    fun integerHex() {
        t("0xF", "integer_hex")
    }

    @Test
    fun integerHexNeg() {
        t("0x-F", "integer_hex_neg")
    }

    @Test
    fun integerNegHex() {
        t("-0xF", "integer_neg_hex")
    }

    @Test
    fun integerOctSep() {
        t("0o0_755", "integer_oct_sep")
    }

    @Test
    fun integerBinSep() {
        t("0b1_0_1", "integer_bin_sep")
    }

    @Test
    fun integerI64Min() {
        t(Long.MIN_VALUE.toString(), "integer_i64_min")
    }

    @Test
    fun integerI64Max() {
        t(Long.MAX_VALUE.toString(), "integer_i64_max")
    }

    @Test
    fun integerExceedI64() {
        t("1000000000000000000000000000000000", "integer_exceed_i64")
    }

    @Test
    fun float() {
        t("3.1419", "float")
    }

    @Test
    fun floatPositive() {
        t("+1.0", "float_positive")
    }

    @Test
    fun floatNegative() {
        t("-0.01", "float_negative")
    }

    @Test
    fun floatPos() {
        t("1e6", "float_pos")
    }

    @Test
    fun floatExpPositive() {
        t("5e+22", "float_exp_positive")
    }

    @Test
    fun floatExpNegative() {
        t("6.626e-34", "float_exp_negative")
    }

    @Test
    fun floatNegativeExpNegative() {
        t("-2E-2", "float_negative_exp_negative")
    }

    @Test
    fun floatSep() {
        t("9_224_617.445_991_228_313e1_000", "float_sep")
    }

    @Test
    fun floatSepBad() {
        t("-_1_._0_e_-_1_", "float_sep_bad")
    }

    @Test
    fun floatF64Min() {
        t("-1.7976931348623157e+308", "float_f64_min")
    }

    @Test
    fun floatF64Max() {
        t("1.7976931348623157e+308", "float_f64_max")
    }

    @Test
    fun floatNan() {
        t("nan", "float_nan")
    }

    @Test
    fun floatNanPositive() {
        t("+nan", "float_nan_positive")
    }

    @Test
    fun floatNanNegative() {
        t("-nan", "float_nan_negative")
    }

    @Test
    fun floatInf() {
        t("inf", "float_inf")
    }

    @Test
    fun floatInfPositive() {
        t("+inf", "float_inf_positive")
    }

    @Test
    fun floatInfNegative() {
        t("-inf", "float_inf_negative")
    }

    @Test
    fun floatExceedF64() {
        t("1e+400", "float_exceed_f64")
    }

    @Test
    fun stringEscaped() {
        t(
            "\"I'm a string. \\\"You can quote me\\\". Name\\tJos\\u00E9\\nLocation\\tSF. \\U0002070E\"",
            "string_escaped",
        )
    }

    @Test
    fun stringEscapedEscape() {
        t("\"\\e There is no escape! \\e\"", "string_escaped_escape")
    }

    @Test
    fun hexEscape() {
        t("\"\\x20 \\x09 \\x1b \\x0d\\x0a\"", "hex_escape")
    }

    @Test
    fun stringMlStringMultipleLines() {
        t("\"\"\"\nRoses are red\nViolets are blue\"\"\"", "string_ml_string_multiple_lines")
    }

    @Test
    fun stringConfusingQuotes1() {
        t("\"\"\" \\\"\"\" \"\"\"", "string_confusing_quotes_1")
    }

    @Test
    fun stringConfusingQuotes2() {
        t("\"\"\" \\\\\"\"\"", "string_confusing_quotes_2")
    }

    @Test
    fun stringConfusingQuotes3() {
        t("\"\"\"  \"\"", "string_confusing_quotes_3")
    }

    @Test
    fun stringConfusingQuotes4() {
        t("\"\"\"  \\\"\"\"", "string_confusing_quotes_4")
    }

    @Test
    fun string() {
        t(
            "\"\"\"\nThe quick brown \\\n\n\n  fox jumps over \\\n    the lazy dog.\"\"\"",
            "string",
        )
    }

    @Test
    fun stringTrailingSlash1() {
        t("\"\"\"\\\n       \"\"\"", "string_trailing_slash_1")
    }

    @Test
    fun stringTrailingSlash2() {
        t("\"\"\"\n\\\n  \\\n\"\"\"", "string_trailing_slash_2")
    }

    @Test
    fun stringTrailingSlash3() {
        t(
            "\"\"\"\\\n       The quick brown \\\n       fox jumps over \\\n       the lazy dog.\\\n       \"\"\"",
            "string_trailing_slash_3",
        )
    }

    @Test
    fun stringLiteralPath1() {
        t("'C:\\Users\\nodejs\\templates'", "string_literal_path_1")
    }

    @Test
    fun stringLiteralPath2() {
        t("'\\\\ServerX\\admin\$\\system32\\'", "string_literal_path_2")
    }

    @Test
    fun stringLiteralQuotes() {
        t("'Tom \"Dubs\" Preston-Werner'", "string_literal_quotes")
    }

    @Test
    fun stringLiteralEscaped1() {
        t("'<\\i\\c*\\s*>'", "string_literal_escaped_1")
    }

    @Test
    fun stringLiteralEscaped2() {
        t("'''I [dw]on't need \\d{2} apples'''", "string_literal_escaped_2")
    }

    @Test
    fun stringLiteralConfusingQuotes() {
        t("''''one_quote''''", "string_literal_confusing_quotes")
    }

    @Test
    fun stringMlStringLiteralMultipleLines() {
        t(
            """'''
The first newline is
trimmed in raw strings.
   All other whitespace
   is preserved.
'''""",
            "string_ml_string_literal_multiple_lines",
        )
    }

    @Test
    fun stringMissingOpening() {
        t("invalid url to tqdm-4.66.0-py3-none-any.whl\"", "string_missing_opening")
    }
}
