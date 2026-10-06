package com.fromwau.kotoml

import com.fromwau.kern.result.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** `toml_edit/tests/testsuite/edit.rs` */
class EditTest {

    private class Given(val doc: TomlDocument) {
        fun running(func: (StandardTable) -> Unit): Given {
            func(doc.root)
            return this
        }

        fun runningOnDoc(func: (TomlDocument) -> Unit): Given {
            func(doc)
            return this
        }

        fun prints(expected: String): Given {
            assertSnapshot(expected, doc.toString())
            return this
        }
    }

    private fun given(input: String): Given {
        return Given(TomlDocument.parse(input).getOrFail())
    }

    private fun assertKeySpellingRoundTrip(input: String, expected: String) {
        val value = Key.parse(input).getOrFail()
        val actual = value.toString()
        if (Key.parse(actual) is Result.Error) fail("invalid `Key`:\n```\n$actual\n```\n")
        assertSnapshot(expected, actual)
    }

    private fun assertKeyValueRoundTrip(input: String, expected: String) {
        val parsed = Key.parse(input).getOrFail()
        // Drops the written spelling.
        val value = Key(parsed.name)
        val actual = value.toString()
        if (Key.parse(actual) is Result.Error) fail("invalid `Key`:\n```\n$actual\n```\n")
        assertSnapshot(expected, actual)
    }
    @Test
    fun `a parsed document prints its input unchanged and keeps a value's decor across an edit`() {
        val input =
            "# leading comment\n[ parent . 'child' ] # header\nvalue = { dotted . key = [ 1, 2, ], other = 'text' } #" +
            " value\n\n[[ parent . 'entries' ]] # first\nname = \"first\"\n\n[[ parent . 'entries' ]] # second\nname " +
            "= \"second\"\n# trailing comment\n"
        val document = TomlDocument.parse(input.toCharArray().concatToString()).getOrFail()
        assertEquals(input, document.toString())

        val name = document["parent"]["entries"][1]["name"]
        val decor = name.getOrFail().valueOrNull!!.decor
        name.getOrFail().assign(Item(Value.String("updated")))
        name.getOrFail().valueOrNull!!.decor = decor
        assertEquals(input.replace("\"second\"", "\"updated\""), document.toString())
    }

    @Test
    fun `a parse error renders against the input it came from`() {
        val input = "[table]\nkey = 1\n[table]\nkey = 2\n"
        val error = TomlDocument.parse(input).errorOrFail()
        assertEquals(TomlParseError.Reason.DuplicateKey(listOf("table")), error.reason)
        assertTrue(error.render(input).contains("[table]"))
    }

    @Test
    fun `a line inserted into a CRLF document ends with CRLF`() {
        val document = TomlDocument.parse("# comment\r\n[table]\r\nvalue = { dotted.key = [1, 2] }\r\n").getOrFail()
        document["table"]["new key"] = "new value"
        // Upstream prints CRLF input with LF line endings; this port keeps each line's ending, which the toml-test
        // round trip requires (valid/newline-crlf), and an inserted line ends like the line before it.
        assertEquals(
            "# comment\r\n[table]\r\nvalue = { dotted.key = [1, 2] }\r\n\"new key\" = \"new value\"\r\n",
            document.toString(),
        )
    }

    @Test
    fun `a parsed document keeps the whitespace and comments around every key and value`() {
        val input =
            "\n # top comment\n [ grandparent . parent ]  # table comment\n # table-value sep comment\n key . child =" +
            " 'value' # key comment\n key . inline-empty = { }  # empty inline-table comment\n key . inline = { inlin" +
            "e . child = 'inline-value' }  # inline-table comment\n key . array-empty = [  # inside empty array comme" +
            "nt\n ] # after empty array comment\n key . array = [ # start of array comment\n   'one' , # one comment" +
            "\n   'two' , # two comment\n] # after array comment\n # table-table sep comment\n [ another . table ]  #" +
            " table comment\n # final comment\n"
        assertDebugSnapshot(
            "\nDocumentMut {\n    root: Table(\n        Table {\n            decor: Decor {\n                pref" +
                "ix: \"default\",\n                suffix: \"default\",\n            },\n            implicit: false," +
                "\n            dotted: false,\n            doc_position: None,\n            span: None,\n            " +
                "items: {\n                Key {\n                    key: \"grandparent\",\n                    repr" +
                ": Some(\n                        \"grandparent\",\n                    ),\n                    leaf_" +
                "decor: Decor {\n                        prefix: \"default\",\n                        suffix: \"defa" +
                "ult\",\n                    },\n                    dotted_decor: Decor {\n                        p" +
                "refix: empty,\n                        suffix: \" \",\n                    },\n                }: Ta" +
                "ble(\n                    Table {\n                        decor: Decor {\n                         " +
                "   prefix: \"default\",\n                            suffix: \"default\",\n                        }" +
                ",\n                        implicit: true,\n                        dotted: false,\n                " +
                "        doc_position: None,\n                        span: None,\n                        items: {\n" +
                "                            Key {\n                                key: \"parent\",\n               " +
                "                 repr: Some(\n                                    \"parent\",\n                     " +
                "           ),\n                                leaf_decor: Decor {\n                                " +
                "    prefix: \" \",\n                                    suffix: \" \",\n                            " +
                "    },\n                                dotted_decor: Decor {\n                                    p" +
                "refix: \" \",\n                                    suffix: empty,\n                                }" +
                ",\n                            }: Table(\n                                Table {\n                 " +
                "                   decor: Decor {\n                                        prefix: \"\\n # top comme" +
                "nt\\n \",\n                                        suffix: \"  # table comment\",\n                 " +
                "                   },\n                                    implicit: false,\n                       " +
                "             dotted: false,\n                                    doc_position: Some(\n              " +
                "                          1,\n                                    ),\n                              " +
                "      span: None,\n                                    items: {\n                                   " +
                "     Key {\n                                            key: \"key\",\n                             " +
                "               repr: Some(\n                                                \"key\",\n              " +
                "                              ),\n                                            leaf_decor: Decor {\n " +
                "                                               prefix: \"default\",\n                               " +
                "                 suffix: \"default\",\n                                            },\n             " +
                "                               dotted_decor: Decor {\n                                              " +
                "  prefix: empty,\n                                                suffix: \" \",\n                  " +
                "                          },\n                                        }: Table(\n                   " +
                "                         Table {\n                                                decor: Decor {\n  " +
                "                                                  prefix: \"default\",\n                            " +
                "                        suffix: \"default\",\n                                                },\n  " +
                "                                              implicit: true,\n                                     " +
                "           dotted: true,\n                                                doc_position: None,\n     " +
                "                                           span: None,\n                                            " +
                "    items: {\n                                                    Key {\n                           " +
                "                             key: \"child\",\n                                                      " +
                "  repr: Some(\n                                                            \"child\",\n             " +
                "                                           ),\n                                                     " +
                "   leaf_decor: Decor {\n                                                            prefix: \" # tab" +
                "le-value sep comment\\n \",\n                                                            suffix: \" " +
                "\",\n                                                        },\n                                   " +
                "                     dotted_decor: Decor {\n                                                        " +
                "    prefix: \" \",\n                                                            suffix: empty,\n    " +
                "                                                    },\n                                            " +
                "        }: Value(\n                                                        String(\n                " +
                "                                            Formatted {\n                                           " +
                "                     value: \"value\",\n                                                            " +
                "    repr: \"'value'\",\n                                                                decor: Decor" +
                " {\n                                                                    prefix: \" \",\n            " +
                "                                                        suffix: \" # key comment\",\n               " +
                "                                                 },\n                                               " +
                "             },\n                                                        ),\n                       " +
                "                             ),\n                                                    Key {\n        " +
                "                                                key: \"inline-empty\",\n                            " +
                "                            repr: Some(\n                                                           " +
                " \"inline-empty\",\n                                                        ),\n                    " +
                "                                    leaf_decor: Decor {\n                                           " +
                "                 prefix: \" \",\n                                                            suffix:" +
                " \" \",\n                                                        },\n                               " +
                "                         dotted_decor: Decor {\n                                                    " +
                "        prefix: \" \",\n                                                            suffix: empty,\n" +
                "                                                        },\n                                        " +
                "            }: Value(\n                                                        InlineTable(\n       " +
                "                                                     InlineTable {\n                                " +
                "                                trailing: \" \",\n                                                  " +
                "              trailing_comma: false,\n                                                              " +
                "  implicit: false,\n                                                                decor: Decor {\n" +
                "                                                                    prefix: \" \",\n                " +
                "                                                    suffix: \"  # empty inline-table comment\",\n   " +
                "                                                             },\n                                   " +
                "                             span: None,\n                                                          " +
                "      dotted: false,\n                                                                items: {},\n  " +
                "                                                          },\n                                      " +
                "                  ),\n                                                    ),\n                      " +
                "                              Key {\n                                                        key: \"" +
                "inline\",\n                                                        repr: Some(\n                    " +
                "                                        \"inline\",\n                                               " +
                "         ),\n                                                        leaf_decor: Decor {\n          " +
                "                                                  prefix: \" \",\n                                  " +
                "                          suffix: \" \",\n                                                        }," +
                "\n                                                        dotted_decor: Decor {\n                   " +
                "                                         prefix: \" \",\n                                           " +
                "                 suffix: empty,\n                                                        },\n       " +
                "                                             }: Value(\n                                            " +
                "            InlineTable(\n                                                            InlineTable {" +
                "\n                                                                trailing: empty,\n                " +
                "                                                trailing_comma: false,\n                            " +
                "                                    implicit: false,\n                                              " +
                "                  decor: Decor {\n                                                                  " +
                "  prefix: \" \",\n                                                                    suffix: \"  # " +
                "inline-table comment\",\n                                                                },\n       " +
                "                                                         span: None,\n                              " +
                "                                  dotted: false,\n                                                  " +
                "              items: {\n                                                                    Key {\n " +
                "                                                                       key: \"inline\",\n           " +
                "                                                             repr: Some(\n                          " +
                "                                                  \"inline\",\n                                     " +
                "                                   ),\n                                                             " +
                "           leaf_decor: Decor {\n                                                                    " +
                "        prefix: \"default\",\n                                                                      " +
                "      suffix: \"default\",\n                                                                        " +
                "},\n                                                                        dotted_decor: Decor {\n " +
                "                                                                           prefix: empty,\n         " +
                "                                                                   suffix: \" \",\n                 " +
                "                                                       },\n                                         " +
                "                           }: Value(\n                                                              " +
                "          InlineTable(\n                                                                            " +
                "InlineTable {\n                                                                                trail" +
                "ing: empty,\n                                                                                trailin" +
                "g_comma: false,\n                                                                                imp" +
                "licit: true,\n                                                                                decor:" +
                " Decor {\n                                                                                    prefix" +
                ": \"default\",\n                                                                                    " +
                "suffix: \"default\",\n                                                                              " +
                "  },\n                                                                                span: None,\n " +
                "                                                                               dotted: true,\n      " +
                "                                                                          items: {\n                " +
                "                                                                    Key {\n                         " +
                "                                                               key: \"child\",\n                    " +
                "                                                                    repr: Some(\n                   " +
                "                                                                         \"child\",\n               " +
                "                                                                         ),\n                       " +
                "                                                                 leaf_decor: Decor {\n              " +
                "                                                                              prefix: \" \",\n      " +
                "                                                                                      suffix: \" \"," +
                "\n                                                                                        },\n      " +
                "                                                                                  dotted_decor: Deco" +
                "r {\n                                                                                            pre" +
                "fix: \" \",\n                                                                                       " +
                "     suffix: empty,\n                                                                               " +
                "         },\n                                                                                    }: " +
                "Value(\n                                                                                        Stri" +
                "ng(\n                                                                                            For" +
                "matted {\n                                                                                          " +
                "      value: \"inline-value\",\n                                                                    " +
                "                            repr: \"'inline-value'\",\n                                             " +
                "                                                   decor: Decor {\n                                 " +
                "                                                                   prefix: \" \",\n                 " +
                "                                                                                   suffix: \" \",\n " +
                "                                                                                               },\n " +
                "                                                                                           },\n     " +
                "                                                                                   ),\n             " +
                "                                                                       ),\n                         " +
                "                                                       },\n                                         " +
                "                                   },\n                                                             " +
                "           ),\n                                                                    ),\n             " +
                "                                                   },\n                                             " +
                "               },\n                                                        ),\n                     " +
                "                               ),\n                                                    Key {\n      " +
                "                                                  key: \"array-empty\",\n                           " +
                "                             repr: Some(\n                                                          " +
                "  \"array-empty\",\n                                                        ),\n                    " +
                "                                    leaf_decor: Decor {\n                                           " +
                "                 prefix: \" \",\n                                                            suffix:" +
                " \" \",\n                                                        },\n                               " +
                "                         dotted_decor: Decor {\n                                                    " +
                "        prefix: \" \",\n                                                            suffix: empty,\n" +
                "                                                        },\n                                        " +
                "            }: Value(\n                                                        Array(\n             " +
                "                                               Array {\n                                            " +
                "                    trailing: \"  # inside empty array comment\\n \",\n                             " +
                "                                   trailing_comma: false,\n                                         " +
                "                       decor: Decor {\n                                                             " +
                "       prefix: \" \",\n                                                                    suffix: " +
                "\" # after empty array comment\",\n                                                                }" +
                ",\n                                                                span: None,\n                    " +
                "                                            values: [],\n                                           " +
                "                 },\n                                                        ),\n                   " +
                "                                 ),\n                                                    Key {\n    " +
                "                                                    key: \"array\",\n                               " +
                "                         repr: Some(\n                                                            \"" +
                "array\",\n                                                        ),\n                              " +
                "                          leaf_decor: Decor {\n                                                     " +
                "       prefix: \" \",\n                                                            suffix: \" \",\n " +
                "                                                       },\n                                         " +
                "               dotted_decor: Decor {\n                                                            pr" +
                "efix: \" \",\n                                                            suffix: empty,\n          " +
                "                                              },\n                                                  " +
                "  }: Value(\n                                                        Array(\n                       " +
                "                                     Array {\n                                                      " +
                "          trailing: \" # two comment\\n\",\n                                                        " +
                "        trailing_comma: true,\n                                                                decor" +
                ": Decor {\n                                                                    prefix: \" \",\n     " +
                "                                                               suffix: \" # after array comment\",\n" +
                "                                                                },\n                                " +
                "                                span: None,\n                                                       " +
                "         values: [\n                                                                    Value(\n    " +
                "                                                                    String(\n                       " +
                "                                                     Formatted {\n                                  " +
                "                                              value: \"one\",\n                                     " +
                "                                           repr: \"'one'\",\n                                       " +
                "                                         decor: Decor {\n                                           " +
                "                                         prefix: \" # start of array comment\\n   \",\n             " +
                "                                                                       suffix: \" \",\n             " +
                "                                                                   },\n                             " +
                "                                               },\n                                                 " +
                "                       ),\n                                                                    ),\n " +
                "                                                                   Value(\n                         " +
                "                                               String(\n                                            " +
                "                                Formatted {\n                                                       " +
                "                         value: \"two\",\n                                                          " +
                "                      repr: \"'two'\",\n                                                            " +
                "                    decor: Decor {\n                                                                " +
                "                    prefix: \" # one comment\\n   \",\n                                             " +
                "                                       suffix: \" \",\n                                             " +
                "                                   },\n                                                             " +
                "               },\n                                                                        ),\n     " +
                "                                                               ),\n                                 " +
                "                               ],\n                                                            },\n " +
                "                                                       ),\n                                         " +
                "           ),\n                                                },\n                                 " +
                "           },\n                                        ),\n                                    },\n " +
                "                               },\n                            ),\n                        },\n     " +
                "               },\n                ),\n                Key {\n                    key: \"another\"," +
                "\n                    repr: Some(\n                        \"another\",\n                    ),\n   " +
                "                 leaf_decor: Decor {\n                        prefix: \"default\",\n                " +
                "        suffix: \"default\",\n                    },\n                    dotted_decor: Decor {\n   " +
                "                     prefix: empty,\n                        suffix: \" \",\n                    }," +
                "\n                }: Table(\n                    Table {\n                        decor: Decor {\n  " +
                "                          prefix: \"default\",\n                            suffix: \"default\",\n  " +
                "                      },\n                        implicit: true,\n                        dotted: f" +
                "alse,\n                        doc_position: None,\n                        span: None,\n           " +
                "             items: {\n                            Key {\n                                key: \"tab" +
                "le\",\n                                repr: Some(\n                                    \"table\",\n" +
                "                                ),\n                                leaf_decor: Decor {\n           " +
                "                         prefix: \" \",\n                                    suffix: \" \",\n       " +
                "                         },\n                                dotted_decor: Decor {\n                " +
                "                    prefix: \" \",\n                                    suffix: empty,\n            " +
                "                    },\n                            }: Table(\n                                Table" +
                " {\n                                    decor: Decor {\n                                        pref" +
                "ix: \" # table-table sep comment\\n \",\n                                        suffix: \"  # table" +
                " comment\",\n                                    },\n                                    implicit: f" +
                "alse,\n                                    dotted: false,\n                                    doc_p" +
                "osition: Some(\n                                        2,\n                                    ),\n" +
                "                                    span: None,\n                                    items: {},\n   " +
                "                             },\n                            ),\n                        },\n       " +
                "             },\n                ),\n            },\n        },\n    ),\n    trailing: \" # final co" +
                "mment\\n\",\n}\n\n",
            TomlDocument.parse(input).getOrFail().dbg(),
        )
    }

    @Test
    fun `a document's decor and trailing text print around its tables`() {
        given(
            "[package]\nname = \"hello\"\nversion = \"1.0.0\"\n\n[[bin]]\nname = \"world\"\npath = \"src/bin/world/ma" +
            "in.rs\"\n\n[dependencies]\nnom = \"4.0\" # future is here\n\n[[bin]]\nname = \"delete me please\"\npath " +
            "= \"src/bin/dmp/main.rs\"")
            .runningOnDoc { document ->
                    document.decor = Decor(prefix = "# Some Header\n\n", suffix = "# Some Footer")
                    document.trailing = "\n\ntrailing..."
            }
            .prints("""
# Some Header

[package]
name = "hello"
version = "1.0.0"

[[bin]]
name = "world"
path = "src/bin/world/main.rs"

[dependencies]
nom = "4.0" # future is here

[[bin]]
name = "delete me please"
path = "src/bin/dmp/main.rs"
# Some Footer

trailing...
""")
    }

    @Test
    fun `a parsed document has no decor and prints its trailing text at the end`() {
        given(
            "[package]\nname = \"hello\"\nversion = \"1.0.0\"\n\n[[bin]]\nname = \"world\"\npath = \"src/bin/world/ma" +
            "in.rs\"\n\n[dependencies]\nnom = \"4.0\" # future is here\n\n[[bin]]\nname = \"delete me please\"\npath " +
            "= \"src/bin/dmp/main.rs\"")
            .runningOnDoc { document ->
                    assertNull(document.decor.prefix)
                    assertNull(document.decor.suffix)
                    document.trailing = "\n\ntrailing..."
            }
            .prints("""
[package]
name = "hello"
version = "1.0.0"

[[bin]]
name = "world"
path = "src/bin/world/main.rs"

[dependencies]
nom = "4.0" # future is here

[[bin]]
name = "delete me please"
path = "src/bin/dmp/main.rs"


trailing...
""")
    }

    @Test
    fun `a new sub-table prints after its parent's existing sub-tables`() {
        given("""[servers]

        [servers.alpha]
        ip = "10.0.0.1"
        dc = "eqdc10"

        [other.table]""")
            .running { root ->
                    root["servers"]["beta"] = StandardTable()
                    root["servers"]["beta"]["ip"] = "10.0.0.2"
                    root["servers"]["beta"]["dc"] = "eqdc10"
            }
            .prints("""
[servers]

        [servers.alpha]
        ip = "10.0.0.1"
        dc = "eqdc10"

[servers.beta]
ip = "10.0.0.2"
dc = "eqdc10"

        [other.table]
""")
    }

    @Test
    fun `a new sub-table prints after the last sibling table even past other tables`() {
        given("""
        [package]
        [dependencies]
        [[example]]
        [dependencies.opencl]
        [dev-dependencies]""")
            .running { root ->
                    root["dependencies"]["newthing"] = StandardTable()
            }
            .prints("""

        [package]
        [dependencies]
        [[example]]
        [dependencies.opencl]

[dependencies.newthing]
        [dev-dependencies]
""")
    }

    @Test
    fun `a table copied from another parsed document prints in its new document`() {
        given("[a]")
            .running { root ->
                val other = TomlDocument.parse("[b]").getOrFail()
                root["b"] = other["b"].getOrFail().copy()
            }
            .prints("""
[a]

[b]
""")
    }

    @Test
    fun `a new table holding only a sub-table prints the sub-table's header after the existing tables`() {
        given("""
        [other.table]""")
            .running { root ->
                    root["servers"] = StandardTable()
                    root["servers"]["alpha"] = StandardTable()
                    root["servers"]["alpha"]["ip"] = "10.0.0.1"
                    root["servers"]["alpha"]["dc"] = "eqdc10"
            }
            .prints("""

        [other.table]

[servers.alpha]
ip = "10.0.0.1"
dc = "eqdc10"
""")
    }

    @Test
    fun `a new array of tables prints one header per table after the existing tables`() {
        given("\n        [package]\n        title = \"withoutarray\"")
            .running { root ->
                root["bin"] = ArrayOfTables()
                val array = assertNotNull(root["bin"].getOrFail().arrayOfTablesOrNull)
                run {
                    val table = StandardTable()
                    table["hello"] = "world"
                    array.add(table)
                }
                array.add(StandardTable())
            }
            .prints("""

        [package]
        title = "withoutarray"

[[bin]]
hello = "world"

[[bin]]
""")
    }

    @Test
    fun `keys set under an implicit table print in a new header above its sub-table`() {
        given("""
        [tbl.son]""")
            .running { root ->
                    root["tbl"]["key1"] = "value1"
                    root["tbl"]["key2"] = 42L
                    root["tbl"]["key3"] = 8.1415926
            }
            .prints("""
[tbl]
key1 = "value1"
key2 = 42
key3 = 8.1415926

        [tbl.son]
""")
    }

    @Test
    fun `a new table key that needs quoting prints as a literal string in its header`() {
        given("""
        [package]
        name = "foo"

        [target]
        """)
            .running { root ->
                    root["target"]["cfg(target_os = \"linux\")"] = StandardTable()
                    root["target"]["cfg(target_os = \"linux\")"]["dependencies"] = StandardTable()
                    root["target"]["cfg(target_os = \"linux\")"]["dependencies"]["name"] = "dep"
            }
            .prints("""

        [package]
        name = "foo"

        [target]

[target.'cfg(target_os = "linux")'.dependencies]
name = "dep"
        
""")
    }

    @Test
    fun `removing a sub-table keeps the comment above its header`() {
        given(
            "\n        [servers]\n\n        # Indentation (tabs and/or spaces) is allowed but not required\n[servers." +
            "alpha]\n        ip = \"10.0.0.1\"\n        dc = \"eqdc10\"\n\n        [servers.beta]\n        ip = \"10." +
            "0.0.2\"\n        dc = \"eqdc10\"")
            .running { root ->
                val servers = root.getOrNull("servers")!!
                val serversTable = servers.standardTableOrNull!!
                assertNotNull(serversTable.remove("alpha"))
            }
            .prints("""

        [servers]

        # Indentation (tabs and/or spaces) is allowed but not required

        [servers.beta]
        ip = "10.0.0.2"
        dc = "eqdc10"
""")
    }

    @Test
    fun `removing a table removes its sub-tables and keeps only the comment above its first header`() {
        given("""
        title = "not relevant"

        # comment 1
        [a.b.c] # comment 1.1
        key1 = 1 # comment 1.2
        # comment 2
        [b] # comment 2.1
        key2 = 2 # comment 2.2

        # comment 3
        [a] # comment 3.1
        key3 = 3 # comment 3.2
        [[a.'array']]
        b = 1

        [[a.b.c.trololololololo]] # ohohohohoho
        c = 2
        key3 = 42

           # comment on some other table
           [some.other.table]




        # comment 4
        [a.b] # comment 4.1
        key4 = 4 # comment 4.2
        key41 = 41 # comment 4.3


    """)
            .running { root ->
                    assertNotNull(root.remove("a"))
            }
            .prints("""

        title = "not relevant"

        # comment 1
        # comment 2
        [b] # comment 2.1
        key2 = 2 # comment 2.2

           # comment on some other table
           [some.other.table]


    """)
    }

    @Test
    fun `removing an array of tables element removes only its header and body`() {
        given(
            "\n        [package]\n        name = \"hello\"\n        version = \"1.0.0\"\n\n        [[bin]]\n        n" +
            "ame = \"world\"\n        path = \"src/bin/world/main.rs\"\n\n        [dependencies]\n        nom = \"4.0" +
            "\" # future is here\n\n        [[bin]]\n        name = \"delete me please\"\n        path = \"src/bin/dm" +
            "p/main.rs\"")
            .running { root ->
                val dmp = root.getOrNull("bin")!!
                val dmpArray = assertNotNull(dmp.arrayOfTablesOrNull)
                assertEquals(2, dmpArray.size)
                dmpArray.removeAt(1)
                assertEquals(1, dmpArray.size)
            }
            .prints("""

        [package]
        name = "hello"
        version = "1.0.0"

        [[bin]]
        name = "world"
        path = "src/bin/world/main.rs"

        [dependencies]
        nom = "4.0" # future is here
""")
    }

    @Test
    fun `removing an array of tables removes every header and body it printed`() {
        given(
            "\n        [package]\n        name = \"hello\"\n        version = \"1.0.0\"\n\n        [[bin]]\n        n" +
            "ame = \"world\"\n        path = \"src/bin/world/main.rs\"\n\n        [dependencies]\n        nom = \"4.0" +
            "\" # future is here\n\n        [[bin]]\n        name = \"delete me please\"\n        path = \"src/bin/dm" +
            "p/main.rs\"")
            .running { root ->
                    assertNotNull(root.remove("bin"))
            }
            .prints("""

        [package]
        name = "hello"
        version = "1.0.0"

        [dependencies]
        nom = "4.0" # future is here
""")
    }

    @Test
    fun `removing a key returns its value and keeps the comment above it`() {
        given(
            "\n        name = \"hello\"\n        # delete this\n        version = \"1.0.0\" # please\n        documen" +
            "tation = \"https://docs.rs/hello\"")
            .running { root ->
                val value = root.remove("version")
                assertNotNull(value)
                val version = assertNotNull(value.valueOrNull)
                assertIs<Value.String>(version)
                assertSnapshot("1.0.0", version.stringOrNull!!)
            }
            .prints("""

        name = "hello"
        # delete this
        documentation = "https://docs.rs/hello"
""")
    }

    @Test
    fun `an implicit table left holding nothing prints its header`() {
        given("""
        [a]
        b = 1""")
            .running { root ->
                val a = root.getOrNull("a")!!
                val aTable = assertNotNull(a.standardTableOrNull)
                aTable.isImplicit = true
                val value = aTable.remove("b")
                assertNotNull(value)
                assertEquals(1L, assertNotNull(value.valueOrNull).longOrNull)
            }
            .prints("\n\n        [a]\n")
    }

    @Test
    fun `sorting a table's keys moves each key with its comments`() {
        given("""
        [a.z]

        [a]
        # this comment is attached to b
        b = 2 # as well as this
        a = 1
        c = 3

        [a.y]""")
            .running { root ->
                val a = root.getOrNull("a")!!
                a.standardTableOrNull!!.sortBy { (key, _) -> key }
            }
            .prints("""

        [a.y]

        [a]
        a = 1
        # this comment is attached to b
        b = 2 # as well as this
        c = 3

        [a.z]
""")
    }

    @Test
    fun `sorting a table's keys by a comparator orders them by their spelling`() {
        given("""
        [a.z]

        [a]
        # this comment is attached to b
        b = 2 # as well as this
        a = 1
        "c" = 3

        [a.y]""")
            .running { root ->
                val a = root.getOrNull("a")!!.standardTableOrNull!!
                // Sort by the representation, not the value. So "\"c\"" sorts before "a" because '"' sorts
                // before 'a'.
                a.sortWith { (k1, _), (k2, _) -> compareByCodePoint(k1.displaySpelling, k2.displaySpelling) }
            }
            .prints("""

        [a.y]

        [a]
        "c" = 3
        a = 1
        # this comment is attached to b
        b = 2 # as well as this

        [a.z]
""")
    }

    @Test
    fun `a table's position sets the order of its header in the document`() {
        given("""
        [package]
        [dependencies]
        [dependencies.opencl]
        [dev-dependencies]""")
            .running { root ->
                for ((header, table) in root) {
                    if (header == "dependencies") {
                        val tab = table.standardTableOrNull!!
                        tab.position = 0
                        val (_, segmented) = tab.first()
                        segmented.standardTableOrNull!!.position = 5
                    }
                }
            }
            .prints("""
        [dependencies]

        [package]
        [dev-dependencies]
        [dependencies.opencl]
""")
    }

    @Test
    fun `a table with a negative position prints before the other tables`() {
        given("""
        edition = "2015"
        [dependencies]
        [dependencies.opencl]
        [dev-dependencies]""")
            .running { root ->
                val pkg = root.getOrPut("package") {
                    val t = StandardTable()
                    t.position = -1
                    Item(t)
                }
                pkg["edition"] = "2024"
            }
            .prints("""

        edition = "2015"

[package]
edition = "2024"
        [dependencies]
        [dependencies.opencl]
        [dev-dependencies]
""")
    }

    @Test
    fun `tables with the same position keep their document order`() {
        given("""
        [package]
        [dependencies]
        [dependencies.opencl]
        a=""
        [dev-dependencies]""")
            .running { root ->
                for ((_, table) in root) {
                    table.standardTableOrNull!!.position = 0
                }
            }
            .prints("""

        [package]
        [dependencies]
        [dev-dependencies]
        [dependencies.opencl]
        a=""
""")
    }

    @Test
    fun `tables at the largest position print after the others in document order`() {
        given("""
        [package]
        [dependencies]
        [dependencies.opencl]
        a=""
        [dev-dependencies]""")
            .running { root ->
                for ((_, table) in root) {
                    table.standardTableOrNull!!.position = Long.MAX_VALUE
                }
            }
            .prints("""
        [dependencies.opencl]
        a=""

        [package]
        [dependencies]
        [dev-dependencies]
""")
    }

    @Test
    fun `inserting and setting array elements keeps their decor and replacing resets it`() {
        given("""
        a = [1,2,3]
        b = []""")
            .running { root ->
                run {
                    val a = root.getOrNull("a")!!.arrayOrNull!!
                    assertEquals(3, a.size)
                    assertNotNull(a.get(2))
                    a.add(4L)
                    assertEquals(4, a.size)
                    a.resetFormatting()
                }
                val b = root.getOrNull("b")!!.arrayOrNull!!
                assertTrue(b.isEmpty())
                b.add("hello")
                assertEquals(1, b.size)
                b.add(Value.String("world", Decor("\n", "\n")))
                b.add(Value.String("test", Decor("", "")))
                b.add(1, Value.String("beep")).getOrFail()
                b.add(2, Value.String("boop", Decor("   ", "   "))).getOrFail()
                // This should preserve formatting.
                assertEquals("boop", b.set(2, Value.String("zoink")).getOrFail().stringOrNull)
                // This should replace formatting.
                assertEquals(
                    "test",
                    b
                        .replace(4, Value.String("yikes", Decor("  ", "")), keepDecor = false)
                        .getOrFail()
                        .stringOrNull,
                )
            }
            .prints("""

        a = [1, 2, 3, 4]
        b = ["hello", "beep",   "zoink"   ,
"world"
,  "yikes"]
""")
    }

    @Test
    fun `removing array elements returns them and leaves an empty array as brackets`() {
        given("""
        a = [1, 2, 3, 4]
        b = ["hello"]""")
            .running { root ->
                run {
                    val a = root.getOrNull("a")!!.arrayOrNull!!
                    assertEquals(4, a.size)
                    assertTrue(a.removeAt(3).getOrFail() is Value.Integer)
                    assertEquals(3, a.size)
                }
                val b = root.getOrNull("b")!!.arrayOrNull!!
                assertEquals(1, b.size)
                assertTrue(b.removeAt(0).getOrFail() is Value.String)
                assertTrue(b.isEmpty())
            }
            .prints("""

        a = [1, 2, 3]
        b = []
""")
    }

    @Test
    fun `resetting an array's formatting prints it on one line with default spacing`() {
        given("""
    a = [
      1,
            "2",
      3.0,
    ]
    """)
            .running { root ->
                for ((_, v) in root) {
                    v.arrayOrNull?.resetFormatting()
                }
            }
            .prints("""

    a = [1, "2", 3.0]
    """)
    }

    @Test
    fun `getOrPut adds a key to an inline table and resetting prints default spacing`() {
        given("""
        a = {a=2,  c = 3}
        b = {}""")
            .running { root ->
                run {
                    val a = root.getOrNull("a")!!.inlineTableOrNull!!
                    assertEquals(2, a.size)
                    assertTrue(a.containsKey("a"))
                    assertTrue("a" in a)
                    a["c"].getOrFail()
                    a.getOrPut("b") { Value.Integer(42L) }
                    assertEquals(3, a.size)
                    a.resetFormatting()
                }
                val b = root.getOrNull("b")!!.inlineTableOrNull!!
                assertTrue(b.isEmpty())
                b.getOrPut("hello") { Value.String("world") }
                assertEquals(1, b.size)
                b.resetFormatting()
            }
            .prints("""

        a = { a = 2, c = 3, b = 42 }
        b = { hello = "world" }
""")
    }

    @Test
    fun `removing inline table keys keeps the spacing of the ones left`() {
        given("""
        a = {a=2,  c = 3, b = 42}
        b = {'hello' = "world"}""")
            .running { root ->
                run {
                    val a = root.getOrNull("a")!!.inlineTableOrNull!!
                    assertEquals(3, a.size)
                    assertNotNull(a.remove("c"))
                    assertEquals(2, a.size)
                }
                val b = root.getOrNull("b")!!.inlineTableOrNull!!
                assertEquals(1, b.size)
                assertNotNull(b.remove("hello"))
                assertTrue(b.isEmpty())
            }
            .prints("""

        a = {a=2, b = 42}
        b = {}
""")
    }

    @Test
    fun `tables and inline tables read as Table and an array of tables does not`() {
        given("""
        a = {a=2,  c = 3, b = 42}
        x = {}
        [[bin]]
        [b]
        x = "y"
        [empty]""")
            .running { root ->
                val a = root["a"].getOrFail().tableOrNull
                assertNotNull(a)
                assertEquals(3, a.entries.size)
                assertEquals(3, a.size)
                assertEquals(2L, a.getItem("a")?.longOrNull)
                val b = root["b"].getOrFail().tableOrNull
                assertNotNull(b)
                assertEquals(1, b.entries.size)
                assertEquals(1, b.size)
                assertEquals("y", b.getItem("x")?.stringOrNull)
                assertEquals(0, root["x"].getOrFail().tableOrNull?.entries?.size)
                assertEquals(true, root["empty"].getOrFail().tableOrNull?.isEmpty())
                assertNull(root["bin"].getOrFail().tableOrNull)
            }
    }

    @Test
    fun `putAll copies another inline table's pairs and leaves that table as it was`() {
        val a = Value.InlineTable(
            inlineTableOf("a" to Value.Integer(1L), "b" to Value.Integer(2L), "c" to Value.Integer(3L)).getOrFail(),
        )
        val aTable = a.inlineTableOrNull!!
        val b = Value.InlineTable(
            inlineTableOf("c" to Value.Integer(4L), "d" to Value.Integer(5L), "e" to Value.Integer(6L)).getOrFail(),
        )
        val bTable = b.inlineTableOrNull!!
        aTable.putAll(bTable.map { (key, value) -> key to value.copy() })
        assertEquals(5, aTable.size)
        assertTrue(aTable.containsKey("e"))
        assertEquals(3, bTable.size)
    }

    @Test
    fun `a dotted sub-table set before its keys prints as dotted keys under the table header`() {
        given("")
            .running { root ->
                root["nixpkgs"] = StandardTable()
                root["nixpkgs"]["src"] = StandardTable()
                root["nixpkgs"]["src"].getOrFail().tableOrNull!!.isDotted = true
                root["nixpkgs"]["src"]["git"] = "https://github.com/nixos/nixpkgs"
            }
            .prints("""
[nixpkgs]
src.git = "https://github.com/nixos/nixpkgs"

""")
    }

    @Test
    fun `a sub-table made dotted after its keys prints as dotted keys under the table header`() {
        given("")
            .running { root ->
                root["nixpkgs"] = StandardTable()
                root["nixpkgs"]["src"] = Value.InlineTable(InlineTable())
                root["nixpkgs"]["src"]["git"] = "https://github.com/nixos/nixpkgs"
                root["nixpkgs"]["src"].getOrFail().tableOrNull!!.isDotted = true
            }
            .prints("""
[nixpkgs]
src.git = "https://github.com/nixos/nixpkgs"

""")
    }

    @Test
    fun `an array of strings sorts with a comparator over its values`() {
        val values = listOf("foo", "qux", "bar")
        val array = values.map { Value.String(it) }.toTomlArray().getOrFail()
        array.sortWith { lhs, rhs -> compareValues(lhs.stringOrNull, rhs.stringOrNull) }
        assertEquals("[\"bar\", \"foo\", \"qux\"]", array.toString())
    }

    @Test
    fun `an array of tables put at a string key replaces the value there`() {
        val document = TomlDocument.parse("bookmark = 1010").getOrFail()
        val key = "bookmark"
        document.put(key, Item(ArrayOfTables()))
        val table = document[key].getOrFail().arrayOfTablesOrNull!!
        val bookmarkTable = StandardTable()
        bookmarkTable["name"] = "test.swf"
        table.add(bookmarkTable)
        assertSnapshot("""
[[bookmark]]
name = "test.swf"
""", document.toString())
    }

    @Test
    fun `an array of tables put at a parsed key prints the key's whitespace in its header`() {
        val document = TomlDocument.parse("bookmark = 1010").getOrFail()
        val key = Key.parsePath("  bookmark   ").getOrFail()[0]
        document.root.put(key, Item(ArrayOfTables()))
        val table = document[key.name].getOrFail().arrayOfTablesOrNull!!
        val bookmarkTable = StandardTable()
        bookmarkTable["name"] = "test.swf"
        table.add(bookmarkTable)
        assertSnapshot("""
[[  bookmark   ]]
name = "test.swf"
""", document.toString())
    }

    @Test
    fun `makeValue turns a table and its sub-tables into one inline table`() {
        val toml = """
[table]
string = "value"
array = [1, 2, 3]
inline = { "1" = 1, "2" = 2 }

[table.child]
other = "world"
"""
        val doc = TomlDocument.parse(toml).getOrFail()
        doc.getOrNull("table")!!.makeValue().getOrFail()
        val actual = doc.toString()
        assertSnapshot(
            "\ntable = { string = \"value\", array = [1, 2, 3], inline = { \"1\" = 1, \"2\" = 2 }, child = { other = " +
            "\"world\" } }\n\n", actual)
    }

    @Test
    fun `an inline table converted to a table prints as a table with a header`() {
        val toml =
            "table = { string = \"value\", array = [1, 2, 3], inline = { \"1\" = 1, \"2\" = 2 }, child = { other = \"" +
            "world\" } }\n"
        val doc = TomlDocument.parse(toml).getOrFail()
        val t = doc.remove("table")!!
        val inline = t.inlineTableOrNull ?: fail("Unexpected $t")
        val table = inline.toStandardTable()
        doc.put("table", Item(table))
        val actual = doc.toString()
        assertSnapshot("""
[table]
string = "value"
array = [1, 2, 3]
inline = { "1" = 1, "2" = 2 }
child = { other = "world" }

""", actual)
    }

    @Test
    fun `makeValue turns an array of tables into an array of inline tables`() {
        val toml = """
[[table]]
string = "value"
array = [1, 2, 3]
inline = { "1" = 1, "2" = 2 }

[table.child]
other = "world"

[[table]]
string = "value"
array = [1, 2, 3]
inline = { "1" = 1, "2" = 2 }

[table.child]
other = "world"
"""
        val doc = TomlDocument.parse(toml).getOrFail()
        doc.getOrNull("table")!!.makeValue().getOrFail()
        val actual = doc.toString()
        assertSnapshot(
            "\ntable = [{ string = \"value\", array = [1, 2, 3], inline = { \"1\" = 1, \"2\" = 2 }, " +
            "child = { other = " +
            "\"world\" } }, { string = \"value\", array = [1, 2, 3], inline = { \"1\" = 1, \"2\" = 2 }, child = { oth" +
            "er = \"world\" } }]\n\n", actual)
    }

    @Test
    fun `Key parse reads bare quoted and literal keys to their names`() {
        fun testKey(text: String, expected: String) {
            when (val key = Key.parse(text)) {
                is Result.Success -> assertEquals(expected, key.value.name, "")
                is Result.Error -> fail("failed with ${key.error}")
            }
        }

        testKey("a", "a")
        testKey("'hello key'", "hello key")
        testKey("\"Jos\\u00E9\\U000A0000\\n\\t\\r\\f\\b\\\"\"", "José\uDA40\uDC00\n\t\r\u000C\u0008\"")
        testKey("\"\"", "")
        testKey("\"'hello key'bla\"", "'hello key'bla")
        testKey(
            "'C:\\Users\\appveyor\\AppData\\Local\\Temp\\1\\cargo-edit-test.YizxPxxElXn9'",
            "C:\\Users\\appveyor\\AppData\\Local\\Temp\\1\\cargo-edit-test.YizxPxxElXn9",
        )
    }

    @Test
    fun `a parsed key put into a document prints after the existing keys`() {
        val doc = TomlDocument.parse("aaaaaa = 1").getOrFail()
        val key = Key.parse("bbb").getOrFail()
        val table = doc.root
        table.put(key, Item(Value.Integer(2L)))
        assertEquals("aaaaaa = 1\nbbb = 2", doc.toString())
    }

    @Test
    fun `a parsed key prints back with its own quoting`() {
        assertKeySpellingRoundTrip("\"\"", "\"\"")
        assertKeySpellingRoundTrip("\"a\"", "\"a\"")
        assertKeySpellingRoundTrip("\"tab \\t tab\"", "\"tab \\t tab\"")
        assertKeySpellingRoundTrip("\"lf \\n lf\"", "\"lf \\n lf\"")
        assertKeySpellingRoundTrip("\"crlf \\r\\n crlf\"", "\"crlf \\r\\n crlf\"")
        assertKeySpellingRoundTrip("\"bell \\b bell\"", "\"bell \\b bell\"")
        assertKeySpellingRoundTrip("\"feed \\f feed\"", "\"feed \\f feed\"")
        assertKeySpellingRoundTrip("\"backslash \\\\ backslash\"", "\"backslash \\\\ backslash\"")
        assertKeySpellingRoundTrip("\"squote ' squote\"", "\"squote ' squote\"")
        assertKeySpellingRoundTrip("\"triple squote ''' triple squote\"", "\"triple squote ''' triple squote\"")
        assertKeySpellingRoundTrip("\"end squote '\"", "\"end squote '\"")
        assertKeySpellingRoundTrip("\"quote \\\" quote\"", "\"quote \\\" quote\"")
        assertKeySpellingRoundTrip(
            "\"triple quote \\\"\\\"\\\" triple quote\"",
            "\"triple quote \\\"\\\"\\\" triple quote\"",
        )
        assertKeySpellingRoundTrip("\"end quote \\\"\"", "\"end quote \\\"\"")
        assertKeySpellingRoundTrip("\"quoted \\\"content\\\" quoted\"", "\"quoted \\\"content\\\" quoted\"")
        assertKeySpellingRoundTrip("\"squoted 'content' squoted\"", "\"squoted 'content' squoted\"")
        assertKeySpellingRoundTrip(
            "\"mixed quoted \\\"start\\\" 'end'' mixed quote\"",
            "\"mixed quoted \\\"start\\\" 'end'' mixed quote\"",
        )
    }

    @Test
    fun `a key built from a name prints with the simplest quoting that parses back`() {
        assertKeyValueRoundTrip("\"\"", "\"\"")
        assertKeyValueRoundTrip("\"a\"", "a")
        assertKeyValueRoundTrip("\"tab \\t tab\"", "\"tab \\t tab\"")
        assertKeyValueRoundTrip("\"lf \\n lf\"", "\"lf \\n lf\"")
        assertKeyValueRoundTrip("\"crlf \\r\\n crlf\"", "\"crlf \\r\\n crlf\"")
        assertKeyValueRoundTrip("\"bell \\b bell\"", "\"bell \\b bell\"")
        assertKeyValueRoundTrip("\"feed \\f feed\"", "\"feed \\f feed\"")
        assertKeyValueRoundTrip("\"backslash \\\\ backslash\"", "'backslash \\ backslash'")
        assertKeyValueRoundTrip("\"squote ' squote\"", "\"squote ' squote\"")
        assertKeyValueRoundTrip("\"triple squote ''' triple squote\"", "\"triple squote ''' triple squote\"")
        assertKeyValueRoundTrip("\"end squote '\"", "\"end squote '\"")
        assertKeyValueRoundTrip("\"quote \\\" quote\"", "'quote \" quote'")
        assertKeyValueRoundTrip("\"triple quote \\\"\\\"\\\" triple quote\"", "'triple quote \"\"\" triple quote'")
        assertKeyValueRoundTrip("\"end quote \\\"\"", "'end quote \"'")
        assertKeyValueRoundTrip("\"quoted \\\"content\\\" quoted\"", "'quoted \"content\" quoted'")
        assertKeyValueRoundTrip("\"squoted 'content' squoted\"", "\"squoted 'content' squoted\"")
        assertKeyValueRoundTrip(
            "\"mixed quoted \\\"start\\\" 'end'' mixed quote\"",
            "\"mixed quoted \\\"start\\\" 'end'' mixed quote\"",
        )
    }

    @Test
    fun `a key written under a dotted inline table prints as a dotted key inside it`() {
        val doc = TomlDocument()
        // An inline table holds only values, so a dotted inline table stands in for upstream's table here.
        val dotted = InlineTable()
        dotted.isDotted = true
        doc["tool"] = Value.InlineTable(InlineTable())
        doc["tool"]["typst-test"] = Value.InlineTable(dotted)
        doc["tool"]["typst-test"]["tests"] = "tests"
        assertSnapshot("""
tool = { typst-test.tests = "tests" }

""", doc.toString())
    }

    @Test
    fun `a table added at the start of an array of tables prints first`() {
        given("""[[fruit]]
name = "apple"

[[fruit]]
name = "banana"
""")
            .running { root ->
                    val array = root["fruit"].getOrFail().arrayOfTablesOrNull!!
                    val newTable = StandardTable()
                    newTable.put("name", Item(Value.String("cherry")))
                    array.add(0, newTable)
                    // The previously-first table (now at index 1) had no leading blank line
                    // from parsing; fix it up so it separates properly.
                    val moved = array[1].getOrFail()
                    moved.decor = moved.decor.copy(prefix = "\n")
            }
            .prints("""
[[fruit]]
name = "cherry"

[[fruit]]
name = "apple"

[[fruit]]
name = "banana"

""")
    }

    @Test
    fun `a table added in the middle of an array of tables prints between its neighbours`() {
        given("""[[fruit]]
name = "apple"

[[fruit]]
name = "banana"
""")
            .running { root ->
                    val array = root["fruit"].getOrFail().arrayOfTablesOrNull!!
                    val newTable = StandardTable()
                    newTable.put("name", Item(Value.String("cherry")))
                    // Give the new table a leading blank line to match the existing style.
                    newTable.decor = newTable.decor.copy(prefix = "\n")
                    array.add(1, newTable)
            }
            .prints("""
[[fruit]]
name = "apple"

[[fruit]]
name = "cherry"

[[fruit]]
name = "banana"

""")
    }

    @Test
    fun `a table added at the end of an array of tables prints last`() {
        given("""[[fruit]]
name = "apple"

[[fruit]]
name = "banana"
""")
            .running { root ->
                    val array = root["fruit"].getOrFail().arrayOfTablesOrNull!!
                    val newTable = StandardTable()
                    newTable.put("name", Item(Value.String("cherry")))
                    // Give the new table a leading blank line to match the existing style.
                    newTable.decor = newTable.decor.copy(prefix = "\n")
                    array.add(2, newTable)
            }
            .prints("""
[[fruit]]
name = "apple"

[[fruit]]
name = "banana"

[[fruit]]
name = "cherry"

""")
    }

    @Test
    fun `adding a table past the end of an array of tables fails with IndexOutOfBounds`() {
        val array = ArrayOfTables()
        val t = StandardTable()
        assertEquals(TomlEditError.IndexOutOfBounds(1, 0), array.add(1, t).errorOrFail())
    }

    @Test
    fun `an inline table replaced by a table keeps the comment above its key`() {
        given("""
# hello i'm a comment
foo = { bar = 1 }
""")
            .running { root ->
                val newFoo = StandardTable()
                newFoo.put("bar", Item(Value.Integer(1L)))
                newFoo.put("baz", Item(Value.Integer(2L)))
                root.getOrNull("foo")!!.assign(Item(newFoo))
            }
            .prints("""

# hello i'm a comment
[foo]
bar = 1
baz = 2

""")
    }

    @Test
    fun `an array of inline tables replaced by an array of tables keeps the comment above its key`() {
        given("""
# hello i'm a comment
foo = [{ bar = 1 }]
""")
            .running { root ->
                val newFooEntry = StandardTable()
                newFooEntry.put("bar", Item(Value.Integer(1L)))
                newFooEntry.put("baz", Item(Value.Integer(2L)))
                val arr = ArrayOfTables()
                arr.add(newFooEntry)
                root.getOrNull("foo")!!.assign(Item(arr))
            }
            .prints("""

# hello i'm a comment
[[foo]]
bar = 1
baz = 2

""")
    }

    @Test
    fun `setting an array of tables element returns the old table and prints the new one`() {
        given("""[[fruit]]
name = "apple"

# tropical
[[fruit]]
name = "banana"
""")
            .running { root ->
                val array = root["fruit"].getOrFail().arrayOfTablesOrNull!!
                val newTable = StandardTable()
                newTable.put("name", Item(Value.String("cherry")))
                val old = array.set(1, newTable).getOrFail()
                assertEquals("banana", old["name"].getOrFail().stringOrNull)
            }
            .prints("""
[[fruit]]
name = "apple"

# tropical
[[fruit]]
name = "cherry"

""")
    }

    @Test
    fun `setting a table past the end of an array of tables fails with IndexOutOfBounds`() {
        val array = ArrayOfTables()
        val t = StandardTable()
        assertEquals(TomlEditError.IndexOutOfBounds(0, 0), array.set(0, t).errorOrFail())
    }
}
