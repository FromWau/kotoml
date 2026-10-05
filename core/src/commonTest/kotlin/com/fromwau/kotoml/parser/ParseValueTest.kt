package com.fromwau.kotoml.parser

import kotlin.test.Test

class ParseValueTest {

    private fun assertEvents(input: String, snapshot: String) {
        val actual = EventResults(input)
        val tokens = TomlSource(input).lex().toList()
        parseValue(tokens, actual.receiver, actual.sink)
        actual.validate("testsuite__parse_value__$snapshot")
    }

    private fun assertEventsWithDepthLimit(input: String, maxDepth: UInt, snapshot: String) {
        val actual = EventResults(input)
        val tokens = TomlSource(input).lex().toList()
        parseValue(tokens, RecursionGuard(actual.receiver, maxDepth), actual.sink)
        actual.validate("testsuite__parse_value__$snapshot")
    }

    @Test
    fun `an empty value reports a missing value`() {
        assertEvents("", "value_empty")
    }

    @Test
    fun `a local date-time with a fraction parses as one scalar`() {
        assertEvents("1979-05-27T00:32:00.999999", "value_datetime")
    }

    @Test
    fun `a negative integer parses as one scalar`() {
        assertEvents("-239", "value_negative_integer")
    }

    @Test
    fun `a float with only an exponent parses as one scalar`() {
        assertEvents("1e200", "value_float")
    }

    @Test
    fun `a float with underscores in every part parses as one scalar`() {
        assertEvents("9_224_617.445_991_228_313", "value_integer_with_seps")
    }

    @Test
    fun `a multi-line literal string on one line keeps its backslashes`() {
        assertEvents("'''I [dw]on't need \\d{2} apples'''", "value_ml_string_literal_single_line")
    }

    @Test
    fun `a multi-line literal string over several lines parses as one scalar`() {
        assertEvents(
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
    fun `a basic string with a unicode escape parses as one scalar`() {
        assertEvents("\"Jos\\u00E9\\n\"", "value_string_escaped_unicode")
    }

    @Test
    fun `a basic string with every short escape parses as one scalar`() {
        assertEvents("\"\\\\\\\"\\b/\\f\\n\\r\\t\\u00E9\\U000A0000\"", "value_string_escaped")
    }

    @Test
    fun `an inline table value emits its keys and values`() {
        assertEvents("{ hello = \"world\", a = 1}", "value_inline_table")
    }

    @Test
    fun `an array value of inline tables emits each table's events`() {
        assertEvents(
            "[ { x = 1, a = \"2\" }, {a = \"a\",b = \"b\",     c =    \"c\"} ]",
            "value_array",
        )
    }

    @Test
    fun `an empty array parses as open and close`() {
        assertEvents("[]", "array_empty")
    }

    @Test
    fun `an array holding only whitespace parses as empty`() {
        assertEvents("[   ]", "array_whitespace")
    }

    @Test
    fun `an array split over lines keeps its newlines as events`() {
        assertEvents(
            """[
1, 2, 3
]""",
            "array_integers_newlines",
        )
    }

    @Test
    fun `an array keeps a comment after its trailing comma`() {
        assertEvents(
            """[
1,
2, # this is ok
]""",
            "array_integers_trailing_comment",
        )
    }

    @Test
    fun `an array holding only comments parses without values`() {
        assertEvents(
            """[# comment
# comment2


]""",
            "array_comments_only",
        )
    }

    @Test
    fun `an array keeps comments around its value and its comma`() {
        assertEvents(
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
    fun `an array of one integer parses`() {
        assertEvents("[1]", "array_integer")
    }

    @Test
    fun `an array of one value accepts a trailing comma`() {
        assertEvents("[1,]", "array_trailing_comma")
    }

    @Test
    fun `an array mixes all four string kinds`() {
        assertEvents(
            "[ \"all\", 'strings', \"\"\"are the same\"\"\", '''type''']",
            "array_string_types",
        )
    }

    @Test
    fun `an array of integers accepts a trailing comma`() {
        assertEvents("[ 100, -2,]", "array_integers_trailing_comma")
    }

    @Test
    fun `an array of integers parses`() {
        assertEvents("[1, 2, 3]", "array_integers")
    }

    @Test
    fun `an array of floats parses`() {
        assertEvents("[1.1, 2.1, 3.1]", "array_floats")
    }

    @Test
    fun `an array of basic strings parses`() {
        assertEvents("[\"a\", \"b\", \"c\"]", "array_strings")
    }

    @Test
    fun `nested arrays of integers parse`() {
        assertEvents("[ [ 1, 2 ], [3, 4, 5] ]", "array_nested_same_type")
    }

    @Test
    fun `nested arrays of different types parse`() {
        assertEvents("[ [ 1, 2 ], [\"a\", \"b\", \"c\"] ]", "array_nested_multiple_types")
    }

    @Test
    fun `an array of inline tables parses`() {
        assertEvents(
            "[ { x = 1, a = \"2\" }, {a = \"a\",b = \"b\",     c =    \"c\"} ]",
            "array_inline_table",
        )
    }

    @Test
    fun `an array without a closing bracket reports an unclosed array`() {
        assertEvents("[", "array_no_close")
    }

    @Test
    fun `an array holding only a comma reports an extra comma`() {
        assertEvents("[,]", "array_only_comma")
    }

    @Test
    fun `an array starting with a comma reports an extra comma`() {
        assertEvents("[,2]", "array_leading_comma")
    }

    @Test
    fun `an array ending in two commas reports an extra comma`() {
        assertEvents("[1e165,,]", "array_extra_trailing_comma")
    }

    @Test
    fun `an offset date-time accepts a lower case t`() {
        assertEvents("1979-05-27t07:32:00Z", "datetime_offset_lower_t")
    }

    @Test
    fun `an offset date-time accepts a space for the T`() {
        assertEvents("1979-05-27 07:32:00Z", "datetime_offset_space")
    }

    @Test
    fun `an offset date-time in UTC parses`() {
        assertEvents("1979-05-27T07:32:00Z", "datetime_offset_z")
    }

    @Test
    fun `an offset date-time accepts a lower case z`() {
        assertEvents("1979-05-27T07:32:00z", "datetime_offset_lower_z")
    }

    @Test
    fun `an offset date-time with an hour offset parses`() {
        assertEvents("1979-05-27T00:32:00-07:00", "datetime_offset_hour")
    }

    @Test
    fun `an offset date-time with a minutes offset parses`() {
        assertEvents("1979-05-27T00:32:00-00:36", "datetime_offset_minutes")
    }

    @Test
    fun `a local date-time parses`() {
        assertEvents("1979-05-27T07:32:00", "datetime_local")
    }

    @Test
    fun `a local date-time with fractional seconds parses`() {
        assertEvents("1979-05-27T00:32:00.999999", "datetime_local_fractional_seconds")
    }

    @Test
    fun `an offset date-time with more than nine fraction digits parses`() {
        assertEvents("1987-07-05T17:45:00.123456789012345Z", "datetime_local_fractional_seconds_truncated")
    }

    @Test
    fun `a local date parses`() {
        assertEvents("1979-05-27", "datetime_date")
    }

    @Test
    fun `a local time parses`() {
        assertEvents("07:32:00", "datetime_time")
    }

    @Test
    fun `a local time with fractional seconds parses`() {
        assertEvents("00:32:00.999999", "datetime_time_fractional_seconds")
    }

    @Test
    fun `an empty inline table parses as open and close`() {
        assertEvents("{}", "inline_table_empty")
    }

    @Test
    fun `an inline table holding only whitespace parses as empty`() {
        assertEvents("{   }", "inline_table_ws")
    }

    @Test
    fun `an inline table with one pair parses`() {
        assertEvents("{a = 1e165}", "inline_table_key_value")
    }

    @Test
    fun `an inline table with two pairs parses`() {
        assertEvents("{ hello = \"world\", a = 1}", "inline_table_multiple_key_values")
    }

    @Test
    fun `an inline table with a dotted key parses`() {
        assertEvents("{ hello.world = \"a\" }", "inline_table_dotted_key")
    }

    @Test
    fun `an inline table holding only a comma reports an extra comma`() {
        assertEvents("{,}", "inline_table_only_comma")
    }

    @Test
    fun `an inline table starting with a comma reports an extra comma`() {
        assertEvents("{ , hello  = \"a\" }", "inline_table_leading_comma")
    }

    @Test
    fun `an inline table accepts a trailing comma`() {
        assertEvents("{ hello  = \"a\", }", "inline_table_trailing_comma")
    }

    @Test
    fun `an inline table without a comma between pairs reports the missing comma`() {
        assertEvents("{ hello = \"a\" world = \"b\" }", "inline_table_no_comma")
    }

    @Test
    fun `an inline table pair without an equals sign reports the missing assignment`() {
        assertEvents("{ hello  \"a\" }", "inline_table_no_eq")
    }

    @Test
    fun `an inline table without a closing brace reports an unclosed inline table`() {
        assertEvents("{a = 1e165", "inline_table_no_close")
    }

    @Test
    fun `an inline table with a repeated key parses without an error`() {
        assertEvents("{ hello = \"world\", a = 2, hello = 1}", "inline_table_repeated_key")
    }

    @Test
    fun `a decimal integer parses`() {
        assertEvents("42", "integer")
    }

    @Test
    fun `an integer with a plus sign parses`() {
        assertEvents("+99", "integer_positive")
    }

    @Test
    fun `zero parses as an integer`() {
        assertEvents("0", "integer_zero")
    }

    @Test
    fun `an integer with a minus sign parses`() {
        assertEvents("-17", "integer_negative")
    }

    @Test
    fun `an integer with one underscore parses`() {
        assertEvents("1_000", "integer_one_sep")
    }

    @Test
    fun `an integer with several underscores parses`() {
        assertEvents("5_349_221", "integer_multiple_seps")
    }

    @Test
    fun `an integer with an underscore between every digit parses`() {
        assertEvents("1_2_3_4_5", "integer_every_other_sep")
    }

    @Test
    fun `an underscore after a minus sign reports a misplaced underscore`() {
        assertEvents("-_1", "integer_neg_sep")
    }

    @Test
    fun `an underscore after a plus sign reports a misplaced underscore`() {
        assertEvents("+_1", "integer_pos_sep")
    }

    @Test
    fun `a leading underscore reports a misplaced underscore`() {
        assertEvents("_1", "integer_leading_sep")
    }

    @Test
    fun `a trailing underscore reports a misplaced underscore`() {
        assertEvents("1_", "integer_trailing_sep")
    }

    @Test
    fun `a hexadecimal integer parses`() {
        assertEvents("0xF", "integer_hex")
    }

    @Test
    fun `a sign after the hex prefix reports an unexpected sign`() {
        assertEvents("0x-F", "integer_hex_neg")
    }

    @Test
    fun `a signed hexadecimal integer reports that a radix cannot be signed`() {
        assertEvents("-0xF", "integer_neg_hex")
    }

    @Test
    fun `an octal integer with underscores parses`() {
        assertEvents("0o0_755", "integer_oct_sep")
    }

    @Test
    fun `a binary integer with underscores parses`() {
        assertEvents("0b1_0_1", "integer_bin_sep")
    }

    @Test
    fun `the smallest Long parses`() {
        assertEvents(Long.MIN_VALUE.toString(), "integer_i64_min")
    }

    @Test
    fun `the largest Long parses`() {
        assertEvents(Long.MAX_VALUE.toString(), "integer_i64_max")
    }

    @Test
    fun `an integer beyond Long reports that it failed to parse`() {
        assertEvents("1000000000000000000000000000000000", "integer_exceed_i64")
    }

    @Test
    fun `a float with a fraction parses`() {
        assertEvents("3.1419", "float")
    }

    @Test
    fun `a float with a plus sign parses`() {
        assertEvents("+1.0", "float_positive")
    }

    @Test
    fun `a float with a minus sign parses`() {
        assertEvents("-0.01", "float_negative")
    }

    @Test
    fun `a float with a positive exponent and no sign parses`() {
        assertEvents("1e6", "float_pos")
    }

    @Test
    fun `a float with a plus signed exponent parses`() {
        assertEvents("5e+22", "float_exp_positive")
    }

    @Test
    fun `a float with a minus signed exponent parses`() {
        assertEvents("6.626e-34", "float_exp_negative")
    }

    @Test
    fun `a negative float with a negative upper case exponent parses`() {
        assertEvents("-2E-2", "float_negative_exp_negative")
    }

    @Test
    fun `a float with underscores in the fraction and exponent parses`() {
        assertEvents("9_224_617.445_991_228_313e1_000", "float_sep")
    }

    @Test
    fun `a float with misplaced underscores reports an invalid float and exponent`() {
        assertEvents("-_1_._0_e_-_1_", "float_sep_bad")
    }

    @Test
    fun `the most negative Double parses`() {
        assertEvents("-1.7976931348623157e+308", "float_f64_min")
    }

    @Test
    fun `the largest Double parses`() {
        assertEvents("1.7976931348623157e+308", "float_f64_max")
    }

    @Test
    fun `nan parses as a float`() {
        assertEvents("nan", "float_nan")
    }

    @Test
    fun `plus nan parses as a float`() {
        assertEvents("+nan", "float_nan_positive")
    }

    @Test
    fun `minus nan parses as a float`() {
        assertEvents("-nan", "float_nan_negative")
    }

    @Test
    fun `inf parses as a float`() {
        assertEvents("inf", "float_inf")
    }

    @Test
    fun `plus inf parses as a float`() {
        assertEvents("+inf", "float_inf_positive")
    }

    @Test
    fun `minus inf parses as a float`() {
        assertEvents("-inf", "float_inf_negative")
    }

    @Test
    fun `a float beyond Double parses without an error`() {
        assertEvents("1e+400", "float_exceed_f64")
    }

    @Test
    fun `a basic string with quotes tabs and long unicode escapes parses`() {
        assertEvents(
            "\"I'm a string. \\\"You can quote me\\\". Name\\tJos\\u00E9\\nLocation\\tSF. \\U0002070E\"",
            "string_escaped",
        )
    }

    @Test
    fun `a basic string accepts the escape character escape`() {
        assertEvents("\"\\e There is no escape! \\e\"", "string_escaped_escape")
    }

    @Test
    fun `a basic string accepts hex escapes`() {
        assertEvents("\"\\x20 \\x09 \\x1b \\x0d\\x0a\"", "hex_escape")
    }

    @Test
    fun `a multi-line basic string over several lines parses`() {
        assertEvents("\"\"\"\nRoses are red\nViolets are blue\"\"\"", "string_ml_string_multiple_lines")
    }

    @Test
    fun `a multi-line basic string holding an escaped quote and two quotes parses`() {
        assertEvents("\"\"\" \\\"\"\" \"\"\"", "string_confusing_quotes_1")
    }

    @Test
    fun `a multi-line basic string ending in an escaped backslash parses`() {
        assertEvents("\"\"\" \\\\\"\"\"", "string_confusing_quotes_2")
    }

    @Test
    fun `a multi-line basic string closed by only two quotes reports it as invalid`() {
        assertEvents("\"\"\"  \"\"", "string_confusing_quotes_3")
    }

    @Test
    fun `a multi-line basic string left open by an escaped quote reports a missing escape`() {
        assertEvents("\"\"\"  \\\"\"\"", "string_confusing_quotes_4")
    }

    @Test
    fun `a line-ending backslash in a multi-line basic string trims the next lines`() {
        assertEvents(
            "\"\"\"\nThe quick brown \\\n\n\n  fox jumps over \\\n    the lazy dog.\"\"\"",
            "string",
        )
    }

    @Test
    fun `a multi-line basic string holding only a line-ending backslash is empty`() {
        assertEvents("\"\"\"\\\n       \"\"\"", "string_trailing_slash_1")
    }

    @Test
    fun `consecutive line-ending backslashes leave a multi-line basic string empty`() {
        assertEvents("\"\"\"\n\\\n  \\\n\"\"\"", "string_trailing_slash_2")
    }

    @Test
    fun `a line-ending backslash before the closing quotes trims the indentation`() {
        assertEvents(
            "\"\"\"\\\n       The quick brown \\\n       fox jumps over \\\n       the lazy dog.\\\n       \"\"\"",
            "string_trailing_slash_3",
        )
    }

    @Test
    fun `a literal string keeps a Windows path's backslashes`() {
        assertEvents("'C:\\Users\\nodejs\\templates'", "string_literal_path_1")
    }

    @Test
    fun `a literal string keeps a UNC path's backslashes and dollar sign`() {
        assertEvents("'\\\\ServerX\\admin\$\\system32\\'", "string_literal_path_2")
    }

    @Test
    fun `a literal string keeps double quotes`() {
        assertEvents("'Tom \"Dubs\" Preston-Werner'", "string_literal_quotes")
    }

    @Test
    fun `a literal string keeps a regex's backslashes`() {
        assertEvents("'<\\i\\c*\\s*>'", "string_literal_escaped_1")
    }

    @Test
    fun `a multi-line literal string keeps a regex's backslashes`() {
        assertEvents("'''I [dw]on't need \\d{2} apples'''", "string_literal_escaped_2")
    }

    @Test
    fun `a multi-line literal string keeps one quote at each end`() {
        assertEvents("''''one_quote''''", "string_literal_confusing_quotes")
    }

    @Test
    fun `a multi-line literal string trims its first newline only`() {
        assertEvents(
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
    fun `text ending in a quote reports a missing opening quote`() {
        assertEvents("invalid url to tqdm-4.66.0-py3-none-any.whl\"", "string_missing_opening")
    }
}
