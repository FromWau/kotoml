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
    fun `an empty value reports that string values must be quoted`() {
        t("", "value_empty")
    }

    @Test
    fun `a local date-time with a fraction parses as one scalar`() {
        t("1979-05-27T00:32:00.999999", "value_datetime")
    }

    @Test
    fun `a negative integer parses as one scalar`() {
        t("-239", "value_negative_integer")
    }

    @Test
    fun `a float with only an exponent parses as one scalar`() {
        t("1e200", "value_float")
    }

    @Test
    fun `a float with underscores in every part parses as one scalar`() {
        t("9_224_617.445_991_228_313", "value_integer_with_seps")
    }

    @Test
    fun `a multi-line literal string on one line keeps its backslashes`() {
        t("'''I [dw]on't need \\d{2} apples'''", "value_ml_string_literal_single_line")
    }

    @Test
    fun `a multi-line literal string over several lines parses as one scalar`() {
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
    fun `a basic string with a unicode escape parses as one scalar`() {
        t("\"Jos\\u00E9\\n\"", "value_string_escaped_unicode")
    }

    @Test
    fun `a basic string with every short escape parses as one scalar`() {
        t("\"\\\\\\\"\\b/\\f\\n\\r\\t\\u00E9\\U000A0000\"", "value_string_escaped")
    }

    @Test
    fun `an inline table value emits its keys and values`() {
        t("{ hello = \"world\", a = 1}", "value_inline_table")
    }

    @Test
    fun `an array value of inline tables emits each table's events`() {
        t(
            "[ { x = 1, a = \"2\" }, {a = \"a\",b = \"b\",     c =    \"c\"} ]",
            "value_array",
        )
    }

    @Test
    fun `an empty array parses as open and close`() {
        t("[]", "array_empty")
    }

    @Test
    fun `an array holding only whitespace parses as empty`() {
        t("[   ]", "array_whitespace")
    }

    @Test
    fun `an array split over lines keeps its newlines as events`() {
        t(
            """[
1, 2, 3
]""",
            "array_integers_newlines",
        )
    }

    @Test
    fun `an array keeps a comment after its trailing comma`() {
        t(
            """[
1,
2, # this is ok
]""",
            "array_integers_trailing_comment",
        )
    }

    @Test
    fun `an array holding only comments parses without values`() {
        t(
            """[# comment
# comment2


]""",
            "array_comments_only",
        )
    }

    @Test
    fun `an array keeps comments around its value and its comma`() {
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
    fun `an array of one integer parses`() {
        t("[1]", "array_integer")
    }

    @Test
    fun `an array of one value accepts a trailing comma`() {
        t("[1,]", "array_trailing_comma")
    }

    @Test
    fun `an array mixes all four string kinds`() {
        t(
            "[ \"all\", 'strings', \"\"\"are the same\"\"\", '''type''']",
            "array_string_types",
        )
    }

    @Test
    fun `an array of integers accepts a trailing comma`() {
        t("[ 100, -2,]", "array_integers_trailing_comma")
    }

    @Test
    fun `an array of integers parses`() {
        t("[1, 2, 3]", "array_integers")
    }

    @Test
    fun `an array of floats parses`() {
        t("[1.1, 2.1, 3.1]", "array_floats")
    }

    @Test
    fun `an array of basic strings parses`() {
        t("[\"a\", \"b\", \"c\"]", "array_strings")
    }

    @Test
    fun `nested arrays of integers parse`() {
        t("[ [ 1, 2 ], [3, 4, 5] ]", "array_nested_same_type")
    }

    @Test
    fun `nested arrays of different types parse`() {
        t("[ [ 1, 2 ], [\"a\", \"b\", \"c\"] ]", "array_nested_multiple_types")
    }

    @Test
    fun `an array of inline tables parses`() {
        t(
            "[ { x = 1, a = \"2\" }, {a = \"a\",b = \"b\",     c =    \"c\"} ]",
            "array_inline_table",
        )
    }

    @Test
    fun `an array without a closing bracket reports an unclosed array`() {
        t("[", "array_no_close")
    }

    @Test
    fun `an array holding only a comma reports an extra comma`() {
        t("[,]", "array_only_comma")
    }

    @Test
    fun `an array starting with a comma reports an extra comma`() {
        t("[,2]", "array_leading_comma")
    }

    @Test
    fun `an array ending in two commas reports an extra comma`() {
        t("[1e165,,]", "array_extra_trailing_comma")
    }

    @Test
    fun `an offset date-time accepts a lower case t`() {
        t("1979-05-27t07:32:00Z", "datetime_offset_lower_t")
    }

    @Test
    fun `an offset date-time accepts a space for the T`() {
        t("1979-05-27 07:32:00Z", "datetime_offset_space")
    }

    @Test
    fun `an offset date-time in UTC parses`() {
        t("1979-05-27T07:32:00Z", "datetime_offset_z")
    }

    @Test
    fun `an offset date-time accepts a lower case z`() {
        t("1979-05-27T07:32:00z", "datetime_offset_lower_z")
    }

    @Test
    fun `an offset date-time with an hour offset parses`() {
        t("1979-05-27T00:32:00-07:00", "datetime_offset_hour")
    }

    @Test
    fun `an offset date-time with a minutes offset parses`() {
        t("1979-05-27T00:32:00-00:36", "datetime_offset_minutes")
    }

    @Test
    fun `a local date-time parses`() {
        t("1979-05-27T07:32:00", "datetime_local")
    }

    @Test
    fun `a local date-time with fractional seconds parses`() {
        t("1979-05-27T00:32:00.999999", "datetime_local_fractional_seconds")
    }

    @Test
    fun `an offset date-time with more than nine fraction digits parses`() {
        t("1987-07-05T17:45:00.123456789012345Z", "datetime_local_fractional_seconds_truncated")
    }

    @Test
    fun `a local date parses`() {
        t("1979-05-27", "datetime_date")
    }

    @Test
    fun `a local time parses`() {
        t("07:32:00", "datetime_time")
    }

    @Test
    fun `a local time with fractional seconds parses`() {
        t("00:32:00.999999", "datetime_time_fractional_seconds")
    }

    @Test
    fun `an empty inline table parses as open and close`() {
        t("{}", "inline_table_empty")
    }

    @Test
    fun `an inline table holding only whitespace parses as empty`() {
        t("{   }", "inline_table_ws")
    }

    @Test
    fun `an inline table with one pair parses`() {
        t("{a = 1e165}", "inline_table_key_value")
    }

    @Test
    fun `an inline table with two pairs parses`() {
        t("{ hello = \"world\", a = 1}", "inline_table_multiple_key_values")
    }

    @Test
    fun `an inline table with a dotted key parses`() {
        t("{ hello.world = \"a\" }", "inline_table_dotted_key")
    }

    @Test
    fun `an inline table holding only a comma reports an extra comma`() {
        t("{,}", "inline_table_only_comma")
    }

    @Test
    fun `an inline table starting with a comma reports an extra comma`() {
        t("{ , hello  = \"a\" }", "inline_table_leading_comma")
    }

    @Test
    fun `an inline table accepts a trailing comma`() {
        t("{ hello  = \"a\", }", "inline_table_trailing_comma")
    }

    @Test
    fun `an inline table without a comma between pairs reports the missing comma`() {
        t("{ hello = \"a\" world = \"b\" }", "inline_table_no_comma")
    }

    @Test
    fun `an inline table pair without an equals sign reports the missing assignment`() {
        t("{ hello  \"a\" }", "inline_table_no_eq")
    }

    @Test
    fun `an inline table without a closing brace reports an unclosed inline table`() {
        t("{a = 1e165", "inline_table_no_close")
    }

    @Test
    fun `an inline table with a repeated key parses without an error`() {
        t("{ hello = \"world\", a = 2, hello = 1}", "inline_table_repeated_key")
    }

    @Test
    fun `a decimal integer parses`() {
        t("42", "integer")
    }

    @Test
    fun `an integer with a plus sign parses`() {
        t("+99", "integer_positive")
    }

    @Test
    fun `zero parses as an integer`() {
        t("0", "integer_zero")
    }

    @Test
    fun `an integer with a minus sign parses`() {
        t("-17", "integer_negative")
    }

    @Test
    fun `an integer with one underscore parses`() {
        t("1_000", "integer_one_sep")
    }

    @Test
    fun `an integer with several underscores parses`() {
        t("5_349_221", "integer_multiple_seps")
    }

    @Test
    fun `an integer with an underscore between every digit parses`() {
        t("1_2_3_4_5", "integer_every_other_sep")
    }

    @Test
    fun `an underscore after a minus sign reports a misplaced underscore`() {
        t("-_1", "integer_neg_sep")
    }

    @Test
    fun `an underscore after a plus sign reports a misplaced underscore`() {
        t("+_1", "integer_pos_sep")
    }

    @Test
    fun `a leading underscore reports a misplaced underscore`() {
        t("_1", "integer_leading_sep")
    }

    @Test
    fun `a trailing underscore reports a misplaced underscore`() {
        t("1_", "integer_trailing_sep")
    }

    @Test
    fun `a hexadecimal integer parses`() {
        t("0xF", "integer_hex")
    }

    @Test
    fun `a sign after the hex prefix reports an unexpected sign`() {
        t("0x-F", "integer_hex_neg")
    }

    @Test
    fun `a signed hexadecimal integer reports that a radix cannot be signed`() {
        t("-0xF", "integer_neg_hex")
    }

    @Test
    fun `an octal integer with underscores parses`() {
        t("0o0_755", "integer_oct_sep")
    }

    @Test
    fun `a binary integer with underscores parses`() {
        t("0b1_0_1", "integer_bin_sep")
    }

    @Test
    fun `the smallest Long parses`() {
        t(Long.MIN_VALUE.toString(), "integer_i64_min")
    }

    @Test
    fun `the largest Long parses`() {
        t(Long.MAX_VALUE.toString(), "integer_i64_max")
    }

    @Test
    fun `an integer beyond Long reports that it failed to parse`() {
        t("1000000000000000000000000000000000", "integer_exceed_i64")
    }

    @Test
    fun `a float with a fraction parses`() {
        t("3.1419", "float")
    }

    @Test
    fun `a float with a plus sign parses`() {
        t("+1.0", "float_positive")
    }

    @Test
    fun `a float with a minus sign parses`() {
        t("-0.01", "float_negative")
    }

    @Test
    fun `a float with a positive exponent and no sign parses`() {
        t("1e6", "float_pos")
    }

    @Test
    fun `a float with a plus signed exponent parses`() {
        t("5e+22", "float_exp_positive")
    }

    @Test
    fun `a float with a minus signed exponent parses`() {
        t("6.626e-34", "float_exp_negative")
    }

    @Test
    fun `a negative float with a negative upper case exponent parses`() {
        t("-2E-2", "float_negative_exp_negative")
    }

    @Test
    fun `a float with underscores in the fraction and exponent parses`() {
        t("9_224_617.445_991_228_313e1_000", "float_sep")
    }

    @Test
    fun `a float with misplaced underscores reports an invalid float and exponent`() {
        t("-_1_._0_e_-_1_", "float_sep_bad")
    }

    @Test
    fun `the most negative Double parses`() {
        t("-1.7976931348623157e+308", "float_f64_min")
    }

    @Test
    fun `the largest Double parses`() {
        t("1.7976931348623157e+308", "float_f64_max")
    }

    @Test
    fun `nan parses as a float`() {
        t("nan", "float_nan")
    }

    @Test
    fun `plus nan parses as a float`() {
        t("+nan", "float_nan_positive")
    }

    @Test
    fun `minus nan parses as a float`() {
        t("-nan", "float_nan_negative")
    }

    @Test
    fun `inf parses as a float`() {
        t("inf", "float_inf")
    }

    @Test
    fun `plus inf parses as a float`() {
        t("+inf", "float_inf_positive")
    }

    @Test
    fun `minus inf parses as a float`() {
        t("-inf", "float_inf_negative")
    }

    @Test
    fun `a float beyond Double parses without an error`() {
        t("1e+400", "float_exceed_f64")
    }

    @Test
    fun `a basic string with quotes tabs and long unicode escapes parses`() {
        t(
            "\"I'm a string. \\\"You can quote me\\\". Name\\tJos\\u00E9\\nLocation\\tSF. \\U0002070E\"",
            "string_escaped",
        )
    }

    @Test
    fun `a basic string accepts the escape character escape`() {
        t("\"\\e There is no escape! \\e\"", "string_escaped_escape")
    }

    @Test
    fun `a basic string accepts hex escapes`() {
        t("\"\\x20 \\x09 \\x1b \\x0d\\x0a\"", "hex_escape")
    }

    @Test
    fun `a multi-line basic string over several lines parses`() {
        t("\"\"\"\nRoses are red\nViolets are blue\"\"\"", "string_ml_string_multiple_lines")
    }

    @Test
    fun `a multi-line basic string holding an escaped quote and two quotes parses`() {
        t("\"\"\" \\\"\"\" \"\"\"", "string_confusing_quotes_1")
    }

    @Test
    fun `a multi-line basic string ending in an escaped backslash parses`() {
        t("\"\"\" \\\\\"\"\"", "string_confusing_quotes_2")
    }

    @Test
    fun `a multi-line basic string closed by only two quotes reports it as invalid`() {
        t("\"\"\"  \"\"", "string_confusing_quotes_3")
    }

    @Test
    fun `a multi-line basic string left open by an escaped quote reports a missing escape`() {
        t("\"\"\"  \\\"\"\"", "string_confusing_quotes_4")
    }

    @Test
    fun `a line-ending backslash in a multi-line basic string trims the next lines`() {
        t(
            "\"\"\"\nThe quick brown \\\n\n\n  fox jumps over \\\n    the lazy dog.\"\"\"",
            "string",
        )
    }

    @Test
    fun `a multi-line basic string holding only a line-ending backslash is empty`() {
        t("\"\"\"\\\n       \"\"\"", "string_trailing_slash_1")
    }

    @Test
    fun `consecutive line-ending backslashes leave a multi-line basic string empty`() {
        t("\"\"\"\n\\\n  \\\n\"\"\"", "string_trailing_slash_2")
    }

    @Test
    fun `a line-ending backslash before the closing quotes trims the indentation`() {
        t(
            "\"\"\"\\\n       The quick brown \\\n       fox jumps over \\\n       the lazy dog.\\\n       \"\"\"",
            "string_trailing_slash_3",
        )
    }

    @Test
    fun `a literal string keeps a Windows path's backslashes`() {
        t("'C:\\Users\\nodejs\\templates'", "string_literal_path_1")
    }

    @Test
    fun `a literal string keeps a UNC path's backslashes and dollar sign`() {
        t("'\\\\ServerX\\admin\$\\system32\\'", "string_literal_path_2")
    }

    @Test
    fun `a literal string keeps double quotes`() {
        t("'Tom \"Dubs\" Preston-Werner'", "string_literal_quotes")
    }

    @Test
    fun `a literal string keeps a regex's backslashes`() {
        t("'<\\i\\c*\\s*>'", "string_literal_escaped_1")
    }

    @Test
    fun `a multi-line literal string keeps a regex's backslashes`() {
        t("'''I [dw]on't need \\d{2} apples'''", "string_literal_escaped_2")
    }

    @Test
    fun `a multi-line literal string keeps one quote at each end`() {
        t("''''one_quote''''", "string_literal_confusing_quotes")
    }

    @Test
    fun `a multi-line literal string trims its first newline only`() {
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
    fun `text ending in a quote reports a missing opening quote`() {
        t("invalid url to tqdm-4.66.0-py3-none-any.whl\"", "string_missing_opening")
    }
}
