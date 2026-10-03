package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kotoml.writer.FmtError
import com.fromwau.kotoml.writer.FmtWrite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** `toml_edit/tests/testsuite/edit.rs` */
class EditTest {

    private class Given(val doc: DocumentMut) {
        fun running(func: (Table) -> Unit): Given {
            func(doc.asTable())
            return this
        }

        fun runningOnDoc(func: (DocumentMut) -> Unit): Given {
            func(doc)
            return this
        }

        fun producesDisplay(expected: String): Given {
            assertData(expected, doc.toString())
            return this
        }
    }

    private fun given(input: String): Given {
        val doc = DocumentMut.parse(input)
        assertTrue(doc.isOk())
        return Given(doc.unwrap())
    }

    private fun assertKeyReprRoundtrip(input: String, expected: String) {
        val value = Key.parse(input).unwrap()
        val actual = value.toString()
        if (!Key.parse(actual).isOk()) fail("invalid `Key`:\n```\n$actual\n```\n")
        assertData(expected, actual)
    }

    private fun assertKeyValueRoundtrip(input: String, expected: String) {
        val parsed = Key.parse(input).unwrap()
        // Drops the repr.
        val value = Key(parsed.name)
        val actual = value.toString()
        if (!Key.parse(actual).isOk()) fail("invalid `Key`:\n```\n$actual\n```\n")
        assertData(expected, actual)
    }
    @Test
    fun parsedDocumentOutlivesInput() {
        val input =
            "# leading comment\n[ parent . 'child' ] # header\nvalue = { dotted . key = [ 1, 2, ], other = 'text' } #" +
            " value\n\n[[ parent . 'entries' ]] # first\nname = \"first\"\n\n[[ parent . 'entries' ]] # second\nname " +
            "= \"second\"\n# trailing comment\n"
        val document = DocumentMut.parse(input.toCharArray().concatToString()).unwrap()
        assertEquals(input, document.toString())

        val name = document["parent"]["entries"][1]["name"]
        val decor = name.valueOrNull!!.decor
        name.assign(Item(Value.String("updated")))
        name.valueOrNull!!.decor = decor
        assertEquals(input.replace("\"second\"", "\"updated\""), document.toString())
    }

    @Test
    fun parseErrorOutlivesInput() {
        val input = "[table]\nkey = 1\n[table]\nkey = 2\n"
        val error = DocumentMut.parse(input).unwrapErr()
        assertEquals(TomlParseError.Reason.DuplicateKey("table"), error.reason)
        assertTrue(error.render(input).contains("[table]"))
    }

    @Test
    fun documentDisplayPropagatesWriteErrors() {
        class LimitedWriter(var remaining: Int) : FmtWrite {
            override fun writeStr(text: String): EmptyResult<FmtError> {
                val length = text.encodeToByteArray().size
                if (length > remaining) return Err(FmtError)
                remaining -= length
                return Ok(Unit)
            }
        }

        val document = DocumentMut.parse("# comment\r\n[table]\r\nvalue = { dotted.key = [1, 2] }\r\n").unwrap()
        document["table"]["new key"] = "new value"
        val output = document.toString()
        // Upstream prints CRLF input with LF line endings; this port keeps each line's ending, which the toml-test
        // round trip requires (valid/newline-crlf), and an inserted line ends like the line before it.
        assertEquals(
            "# comment\r\n[table]\r\nvalue = { dotted.key = [1, 2] }\r\n\"new key\" = \"new value\"\r\n",
            output,
        )
        val length = output.encodeToByteArray().size
        for (limit in 0 until length) {
            val writer = LimitedWriter(limit)
            assertTrue(writer.writeStr(document.toString()) is Result.Error, "$limit")
        }
        val writer = LimitedWriter(length)
        writer.writeStr(document.toString()).unwrap()
        assertEquals(0, writer.remaining)
    }

    @Test
    fun assignWhitespace() {
        val input =
            "\n # top comment\n [ grandparent . parent ]  # table comment\n # table-value sep comment\n key . child =" +
            " 'value' # key comment\n key . inline-empty = { }  # empty inline-table comment\n key . inline = { inlin" +
            "e . child = 'inline-value' }  # inline-table comment\n key . array-empty = [  # inside empty array comme" +
            "nt\n ] # after empty array comment\n key . array = [ # start of array comment\n   'one' , # one comment" +
            "\n   'two' , # two comment\n] # after array comment\n # table-table sep comment\n [ another . table ]  #" +
            " table comment\n # final comment\n"
        assertDebug(
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
            DocumentMut.parse(input).unwrap().dbg(),
        )
    }

    @Test
    fun testAddRootDecor() {
        given(
            "[package]\nname = \"hello\"\nversion = \"1.0.0\"\n\n[[bin]]\nname = \"world\"\npath = \"src/bin/world/ma" +
            "in.rs\"\n\n[dependencies]\nnom = \"4.0\" # future is here\n\n[[bin]]\nname = \"delete me please\"\npath " +
            "= \"src/bin/dmp/main.rs\"")
            .runningOnDoc { document ->
                    document.decor = Decor(prefix = "# Some Header\n\n", suffix = "# Some Footer")
                    document.setTrailing("\n\ntrailing...")
            }
            .producesDisplay("""
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
    fun testNoRootDecor() {
        given(
            "[package]\nname = \"hello\"\nversion = \"1.0.0\"\n\n[[bin]]\nname = \"world\"\npath = \"src/bin/world/ma" +
            "in.rs\"\n\n[dependencies]\nnom = \"4.0\" # future is here\n\n[[bin]]\nname = \"delete me please\"\npath " +
            "= \"src/bin/dmp/main.rs\"")
            .runningOnDoc { document ->
                    assertTrue(document.decor.prefix == null)
                    assertTrue(document.decor.suffix == null)
                    document.setTrailing("\n\ntrailing...")
            }
            .producesDisplay("""
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
    fun testInsertLeafTable() {
        given("""[servers]

        [servers.alpha]
        ip = "10.0.0.1"
        dc = "eqdc10"

        [other.table]""")
            .running { root ->
                    root["servers"]["beta"] = Table()
                    root["servers"]["beta"]["ip"] = "10.0.0.2"
                    root["servers"]["beta"]["dc"] = "eqdc10"
            }
            .producesDisplay("""
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
    fun testInsertedLeafTableGoesAfterLastSibling() {
        given("""
        [package]
        [dependencies]
        [[example]]
        [dependencies.opencl]
        [dev-dependencies]""")
            .running { root ->
                    root["dependencies"]["newthing"] = Table()
            }
            .producesDisplay("""

        [package]
        [dependencies]
        [[example]]
        [dependencies.opencl]

[dependencies.newthing]
        [dev-dependencies]

""")
    }

    @Test
    fun testInsertingTablesFromDifferentParsedDocs() {
        given("[a]")
            .running { root ->
                val other = DocumentMut.parse("[b]").unwrap()
                root["b"] = other["b"].copy()
            }
            .producesDisplay("""
[a]
[b]

""")
    }

    @Test
    fun testInsertNonleafTable() {
        given("""
        [other.table]""")
            .running { root ->
                    root["servers"] = Table()
                    root["servers"]["alpha"] = Table()
                    root["servers"]["alpha"]["ip"] = "10.0.0.1"
                    root["servers"]["alpha"]["dc"] = "eqdc10"
            }
            .producesDisplay("""

        [other.table]

[servers]

[servers.alpha]
ip = "10.0.0.1"
dc = "eqdc10"

""")
    }

    @Test
    fun testInsertArray() {
        given("\n        [package]\n        title = \"withoutarray\"")
            .running { root ->
                root["bin"] = ArrayOfTables()
                assertTrue(root["bin"].arrayOfTablesOrNull != null)
                val array = root["bin"].arrayOfTablesOrNull!!
                run {
                    val table = Table()
                    table["hello"] = "world"
                    array.add(table)
                }
                array.add(Table())
            }
            .producesDisplay("""

        [package]
        title = "withoutarray"

[[bin]]
hello = "world"

[[bin]]

""")
    }

    @Test
    fun testInsertValues() {
        given("""
        [tbl.son]""")
            .running { root ->
                    root["tbl"]["key1"] = "value1"
                    root["tbl"]["key2"] = 42L
                    root["tbl"]["key3"] = 8.1415926
            }
            .producesDisplay("""
[tbl]
key1 = "value1"
key2 = 42
key3 = 8.1415926

        [tbl.son]

""")
    }

    @Test
    fun testInsertKeyWithQuotes() {
        given("""
        [package]
        name = "foo"

        [target]
        """)
            .running { root ->
                    root["target"]["cfg(target_os = \"linux\")"] = Table()
                    root["target"]["cfg(target_os = \"linux\")"]["dependencies"] = Table()
                    root["target"]["cfg(target_os = \"linux\")"]["dependencies"]["name"] = "dep"
            }
            .producesDisplay("""

        [package]
        name = "foo"

        [target]

[target.'cfg(target_os = "linux")']

[target.'cfg(target_os = "linux")'.dependencies]
name = "dep"
        
""")
    }

    @Test
    fun testRemoveLeafTable() {
        given(
            "\n        [servers]\n\n        # Indentation (tabs and/or spaces) is allowed but not required\n[servers." +
            "alpha]\n        ip = \"10.0.0.1\"\n        dc = \"eqdc10\"\n\n        [servers.beta]\n        ip = \"10." +
            "0.0.2\"\n        dc = \"eqdc10\"")
            .running { root ->
                val servers = root.getOrNull("servers")!!
                val serversTable = servers.tableOrNull!!
                assertTrue(serversTable.remove("alpha") != null)
            }
            .producesDisplay("""

        [servers]

        [servers.beta]
        ip = "10.0.0.2"
        dc = "eqdc10"

""")
    }

    @Test
    fun testRemoveNonleafTable() {
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
                    assertTrue(root.remove("a") != null)
            }
            .producesDisplay("""

        title = "not relevant"
        # comment 2
        [b] # comment 2.1
        key2 = 2 # comment 2.2

           # comment on some other table
           [some.other.table]


    """)
    }

    @Test
    fun testRemoveArrayEntry() {
        given(
            "\n        [package]\n        name = \"hello\"\n        version = \"1.0.0\"\n\n        [[bin]]\n        n" +
            "ame = \"world\"\n        path = \"src/bin/world/main.rs\"\n\n        [dependencies]\n        nom = \"4.0" +
            "\" # future is here\n\n        [[bin]]\n        name = \"delete me please\"\n        path = \"src/bin/dm" +
            "p/main.rs\"")
            .running { root ->
                val dmp = root.getOrNull("bin")!!
                assertTrue(dmp.arrayOfTablesOrNull != null)
                val dmpArray = dmp.arrayOfTablesOrNull!!
                assertEquals(2, dmpArray.size)
                dmpArray.removeAt(1)
                assertEquals(1, dmpArray.size)
            }
            .producesDisplay("""

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
    fun testRemoveArray() {
        given(
            "\n        [package]\n        name = \"hello\"\n        version = \"1.0.0\"\n\n        [[bin]]\n        n" +
            "ame = \"world\"\n        path = \"src/bin/world/main.rs\"\n\n        [dependencies]\n        nom = \"4.0" +
            "\" # future is here\n\n        [[bin]]\n        name = \"delete me please\"\n        path = \"src/bin/dm" +
            "p/main.rs\"")
            .running { root ->
                    assertTrue(root.remove("bin") != null)
            }
            .producesDisplay("""

        [package]
        name = "hello"
        version = "1.0.0"

        [dependencies]
        nom = "4.0" # future is here

""")
    }

    @Test
    fun testRemoveValue() {
        given(
            "\n        name = \"hello\"\n        # delete this\n        version = \"1.0.0\" # please\n        documen" +
            "tation = \"https://docs.rs/hello\"")
            .running { root ->
                val value = root.remove("version")
                assertTrue(value != null)
                assertTrue(value.valueOrNull != null)
                val version = value.valueOrNull!!
                assertTrue(version is Value.String)
                assertData("1.0.0", version.stringOrNull!!)
            }
            .producesDisplay("""

        name = "hello"
        documentation = "https://docs.rs/hello"

""")
    }

    @Test
    fun testRemoveLastValueFromImplicit() {
        given("""
        [a]
        b = 1""")
            .running { root ->
                val a = root.getOrNull("a")!!
                assertTrue(a.tableOrNull != null)
                val aTable = a.tableOrNull!!
                aTable.isImplicit = true
                val value = aTable.remove("b")
                assertTrue(value != null)
                assertTrue(value.valueOrNull != null)
                assertEquals(1L, value.valueOrNull!!.longOrNull)
            }
            .producesDisplay("")
    }

    @Test
    fun testSortValues() {
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
                a.tableOrNull!!.sortValues()
            }
            .producesDisplay("""

        [a.z]

        [a]
        a = 1
        # this comment is attached to b
        b = 2 # as well as this
        c = 3

        [a.y]

""")
    }

    @Test
    fun testSortValuesBy() {
        given("""
        [a.z]

        [a]
        # this comment is attached to b
        b = 2 # as well as this
        a = 1
        "c" = 3

        [a.y]""")
            .running { root ->
                val a = root.getOrNull("a")!!.tableOrNull!!
                // Sort by the representation, not the value. So "\"c\"" sorts before "a" because '"' sorts
                // before 'a'.
                a.sortValuesWith { (k1, _), (k2, _) -> compareRustStr(k1.displaySpelling, k2.displaySpelling) }
            }
            .producesDisplay("""

        [a.z]

        [a]
        "c" = 3
        a = 1
        # this comment is attached to b
        b = 2 # as well as this

        [a.y]

""")
    }

    @Test
    fun testSetPosition() {
        given("""
        [package]
        [dependencies]
        [dependencies.opencl]
        [dev-dependencies]""")
            .running { root ->
                for ((header, table) in root) {
                    if (header == "dependencies") {
                        val tab = table.tableOrNull!!
                        tab.position = 0
                        val (_, segmented) = tab.first()
                        segmented.tableOrNull!!.position = 5
                    }
                }
            }
            .producesDisplay("""
        [dependencies]

        [package]
        [dev-dependencies]
        [dependencies.opencl]

""")
    }

    @Test
    fun testSetPositionNegative() {
        given("""
        edition = "2015"
        [dependencies]
        [dependencies.opencl]
        [dev-dependencies]""")
            .running { root ->
                val pkg = root.getOrPut("package") {
                    val t = Table()
                    t.position = -1
                    Item(t)
                }
                pkg["edition"] = "2024"
            }
            .producesDisplay("""

        edition = "2015"

[package]
edition = "2024"
        [dependencies]
        [dependencies.opencl]
        [dev-dependencies]

""")
    }

    @Test
    fun testMultipleZeroPositions() {
        given("""
        [package]
        [dependencies]
        [dependencies.opencl]
        a=""
        [dev-dependencies]""")
            .running { root ->
                for ((_, table) in root) {
                    table.tableOrNull!!.position = 0
                }
            }
            .producesDisplay("""

        [package]
        [dependencies]
        [dev-dependencies]
        [dependencies.opencl]
        a=""

""")
    }

    @Test
    fun testMultipleMaxUsizePositions() {
        given("""
        [package]
        [dependencies]
        [dependencies.opencl]
        a=""
        [dev-dependencies]""")
            .running { root ->
                for ((_, table) in root) {
                    table.tableOrNull!!.position = Long.MAX_VALUE
                }
            }
            .producesDisplay("""
        [dependencies.opencl]
        a=""

        [package]
        [dependencies]
        [dev-dependencies]

""")
    }

    @Test
    fun testInsertReplaceIntoArray() {
        given("""
        a = [1,2,3]
        b = []""")
            .running { root ->
                run {
                    val a = root.getOrNull("a")!!.arrayOrNull!!
                    assertEquals(3, a.size)
                    assertTrue(a.get(2) != null)
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
                b.add(1, Value.String("beep")).unwrap()
                b.add(2, Value.String("boop", Decor("   ", "   "))).unwrap()
                // This should preserve formatting.
                assertEquals("boop", b.set(2, Value.String("zoink")).unwrap().stringOrNull)
                // This should replace formatting.
                assertEquals(
                    "test",
                    b
                        .replace(4, Value.String("yikes", Decor("  ", "")), keepDecor = false)
                        .unwrap()
                        .stringOrNull,
                )
            }
            .producesDisplay("""

        a = [1, 2, 3, 4]
        b = ["hello", "beep",   "zoink"   ,
"world"
,  "yikes"]

""")
    }

    @Test
    fun testRemoveFromArray() {
        given("""
        a = [1, 2, 3, 4]
        b = ["hello"]""")
            .running { root ->
                run {
                    val a = root.getOrNull("a")!!.arrayOrNull!!
                    assertEquals(4, a.size)
                    assertTrue(a.removeAt(3).unwrap() is Value.Integer)
                    assertEquals(3, a.size)
                }
                val b = root.getOrNull("b")!!.arrayOrNull!!
                assertEquals(1, b.size)
                assertTrue(b.removeAt(0).unwrap() is Value.String)
                assertTrue(b.isEmpty())
            }
            .producesDisplay("""

        a = [1, 2, 3]
        b = []

""")
    }

    @Test
    fun testFormatArray() {
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
            .producesDisplay("""

    a = [1, "2", 3.0]
    """)
    }

    @Test
    fun testInsertIntoInlineTable() {
        given("""
        a = {a=2,  c = 3}
        b = {}""")
            .running { root ->
                run {
                    val a = root.getOrNull("a")!!.inlineTableOrNull!!
                    assertEquals(2, a.size)
                    assertTrue(a.containsKey("a") && "a" in a && a["c"] != null)
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
            .producesDisplay("""

        a = { a = 2, c = 3, b = 42 }
        b = { hello = "world" }

""")
    }

    @Test
    fun testRemoveFromInlineTable() {
        given("""
        a = {a=2,  c = 3, b = 42}
        b = {'hello' = "world"}""")
            .running { root ->
                run {
                    val a = root.getOrNull("a")!!.inlineTableOrNull!!
                    assertEquals(3, a.size)
                    assertTrue(a.remove("c") != null)
                    assertEquals(2, a.size)
                }
                val b = root.getOrNull("b")!!.inlineTableOrNull!!
                assertEquals(1, b.size)
                assertTrue(b.remove("hello") != null)
                assertTrue(b.isEmpty())
            }
            .producesDisplay("""

        a = {a=2, b = 42}
        b = {}

""")
    }

    @Test
    fun testAsTableLike() {
        given("""
        a = {a=2,  c = 3, b = 42}
        x = {}
        [[bin]]
        [b]
        x = "y"
        [empty]""")
            .running { root ->
                val a = root["a"].tableLikeOrNull
                assertTrue(a != null)
                assertEquals(3, a.items().size)
                assertEquals(3, a.size)
                assertEquals(2L, a.item("a")?.longOrNull)
                val b = root["b"].tableLikeOrNull
                assertTrue(b != null)
                assertEquals(1, b.items().size)
                assertEquals(1, b.size)
                assertEquals("y", b.item("x")?.stringOrNull)
                assertEquals(0, root["x"].tableLikeOrNull?.items()?.size)
                assertEquals(true, root["empty"].tableLikeOrNull?.isEmpty())
                assertTrue(root["bin"].tableLikeOrNull == null)
            }
    }

    @Test
    fun testInlineTableAppend() {
        val a = Value.InlineTable(
            inlineTableOf("a" to Value.Integer(1L), "b" to Value.Integer(2L), "c" to Value.Integer(3L)),
        )
        val aTable = a.inlineTableOrNull!!
        val b = Value.InlineTable(
            inlineTableOf("c" to Value.Integer(4L), "d" to Value.Integer(5L), "e" to Value.Integer(6L)),
        )
        val bTable = b.inlineTableOrNull!!
        aTable.putAll(bTable.map { (key, value) -> key to value.copy() })
        assertEquals(5, aTable.size)
        assertTrue(aTable.containsKey("e"))
        assertEquals(3, bTable.size)
    }

    @Test
    fun testInsertDottedIntoStdTable() {
        given("")
            .running { root ->
                root["nixpkgs"] = Table()
                root["nixpkgs"]["src"] = Table()
                root["nixpkgs"]["src"].tableLikeOrNull!!.isDotted = true
                root["nixpkgs"]["src"]["git"] = "https://github.com/nixos/nixpkgs"
            }
            .producesDisplay("""
[nixpkgs]
src.git = "https://github.com/nixos/nixpkgs"

""")
    }

    @Test
    fun testInsertDottedIntoImplicitTable() {
        given("")
            .running { root ->
                root["nixpkgs"] = Table()
                root["nixpkgs"]["src"]["git"] = "https://github.com/nixos/nixpkgs"
                root["nixpkgs"]["src"].tableLikeOrNull!!.isDotted = true
            }
            .producesDisplay("""
[nixpkgs]
src.git = "https://github.com/nixos/nixpkgs"

""")
    }

    @Test
    fun sortingWithReferences() {
        val values = listOf("foo", "qux", "bar")
        val array = values.map { Value.String(it) }.toTomlArray()
        array.sortWith { lhs, rhs -> compareValues(lhs.stringOrNull, rhs.stringOrNull) }
    }

    @Test
    fun tableStrKeyWhitespace() {
        val document = DocumentMut.parse("bookmark = 1010").unwrap()
        val key = "bookmark"
        document.put(key, Item(ArrayOfTables()))
        val table = document[key].arrayOfTablesOrNull!!
        val bookmarkTable = Table()
        bookmarkTable["name"] = "test.swf"
        table.add(bookmarkTable)
        assertData("""
[[bookmark]]
name = "test.swf"

""", document.toString())
    }

    @Test
    fun tableKeyDecorWhitespace() {
        val document = DocumentMut.parse("bookmark = 1010").unwrap()
        val key = Key.parsePath("  bookmark   ").unwrap()[0]
        document.asTable().put(key, Item(ArrayOfTables()))
        val table = document[key.name].arrayOfTablesOrNull!!
        val bookmarkTable = Table()
        bookmarkTable["name"] = "test.swf"
        table.add(bookmarkTable)
        assertData("""
[[  bookmark   ]]
name = "test.swf"

""", document.toString())
    }

    @Test
    fun tableIntoInline() {
        val toml = """
[table]
string = "value"
array = [1, 2, 3]
inline = { "1" = 1, "2" = 2 }

[table.child]
other = "world"
"""
        val doc = DocumentMut.parse(toml).unwrap()
        doc.getOrNull("table")!!.makeValue().unwrap()
        val actual = doc.toString()
        // `table=` is because the table key was not re-formatted, only the value.
        assertData(
            "\ntable= { string = \"value\", array = [1, 2, 3], inline = { \"1\" = 1, \"2\" = 2 }, child = { other = " +
            "\"world\" } }\n\n", actual)
    }

    @Test
    fun inlineTableToTable() {
        val toml =
            "table = { string = \"value\", array = [1, 2, 3], inline = { \"1\" = 1, \"2\" = 2 }, child = { other = \"" +
            "world\" } }\n"
        val doc = DocumentMut.parse(toml).unwrap()
        val t = doc.remove("table")!!
        val inline = t.inlineTableOrNull ?: fail("Unexpected $t")
        val table = inline.toTable()
        doc.put("table", Item(table))
        val actual = doc.toString()
        assertData("""
[table]
string = "value"
array = [1, 2, 3]
inline = { "1" = 1, "2" = 2 }
child = { other = "world" }

""", actual)
    }

    @Test
    fun arrayOfTablesToArray() {
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
        val doc = DocumentMut.parse(toml).unwrap()
        doc.getOrNull("table")!!.makeValue().unwrap()
        val actual = doc.toString()
        // `table=` is because the table key was not re-formatted, only the value.
        assertData(
            "\ntable= [{ string = \"value\", array = [1, 2, 3], inline = { \"1\" = 1, \"2\" = 2 }, child = { other = " +
            "\"world\" } }, { string = \"value\", array = [1, 2, 3], inline = { \"1\" = 1, \"2\" = 2 }, child = { oth" +
            "er = \"world\" } }]\n\n", actual)
    }

    @Test
    fun testKeyFromStr() {
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
    fun despanKeys() {
        val doc = DocumentMut.parse("aaaaaa = 1").unwrap()
        val key = Key.parse("bbb").unwrap()
        val table = doc.asTable()
        table.put(key, Item(Value.Integer(2L)))
        assertEquals("aaaaaa = 1\nbbb = 2\n", doc.toString())
    }

    @Test
    fun keyReprRoundtrip() {
        assertKeyReprRoundtrip("\"\"", "\"\"")
        assertKeyReprRoundtrip("\"a\"", "\"a\"")
        assertKeyReprRoundtrip("\"tab \\t tab\"", "\"tab \\t tab\"")
        assertKeyReprRoundtrip("\"lf \\n lf\"", "\"lf \\n lf\"")
        assertKeyReprRoundtrip("\"crlf \\r\\n crlf\"", "\"crlf \\r\\n crlf\"")
        assertKeyReprRoundtrip("\"bell \\b bell\"", "\"bell \\b bell\"")
        assertKeyReprRoundtrip("\"feed \\f feed\"", "\"feed \\f feed\"")
        assertKeyReprRoundtrip("\"backslash \\\\ backslash\"", "\"backslash \\\\ backslash\"")
        assertKeyReprRoundtrip("\"squote ' squote\"", "\"squote ' squote\"")
        assertKeyReprRoundtrip("\"triple squote ''' triple squote\"", "\"triple squote ''' triple squote\"")
        assertKeyReprRoundtrip("\"end squote '\"", "\"end squote '\"")
        assertKeyReprRoundtrip("\"quote \\\" quote\"", "\"quote \\\" quote\"")
        assertKeyReprRoundtrip(
            "\"triple quote \\\"\\\"\\\" triple quote\"",
            "\"triple quote \\\"\\\"\\\" triple quote\"",
        )
        assertKeyReprRoundtrip("\"end quote \\\"\"", "\"end quote \\\"\"")
        assertKeyReprRoundtrip("\"quoted \\\"content\\\" quoted\"", "\"quoted \\\"content\\\" quoted\"")
        assertKeyReprRoundtrip("\"squoted 'content' squoted\"", "\"squoted 'content' squoted\"")
        assertKeyReprRoundtrip(
            "\"mixed quoted \\\"start\\\" 'end'' mixed quote\"",
            "\"mixed quoted \\\"start\\\" 'end'' mixed quote\"",
        )
    }

    @Test
    fun keyValueRoundtrip() {
        assertKeyValueRoundtrip("\"\"", "\"\"")
        assertKeyValueRoundtrip("\"a\"", "a")
        assertKeyValueRoundtrip("\"tab \\t tab\"", "\"tab \\t tab\"")
        assertKeyValueRoundtrip("\"lf \\n lf\"", "\"lf \\n lf\"")
        assertKeyValueRoundtrip("\"crlf \\r\\n crlf\"", "\"crlf \\r\\n crlf\"")
        assertKeyValueRoundtrip("\"bell \\b bell\"", "\"bell \\b bell\"")
        assertKeyValueRoundtrip("\"feed \\f feed\"", "\"feed \\f feed\"")
        assertKeyValueRoundtrip("\"backslash \\\\ backslash\"", "'backslash \\ backslash'")
        assertKeyValueRoundtrip("\"squote ' squote\"", "\"squote ' squote\"")
        assertKeyValueRoundtrip("\"triple squote ''' triple squote\"", "\"triple squote ''' triple squote\"")
        assertKeyValueRoundtrip("\"end squote '\"", "\"end squote '\"")
        assertKeyValueRoundtrip("\"quote \\\" quote\"", "'quote \" quote'")
        assertKeyValueRoundtrip("\"triple quote \\\"\\\"\\\" triple quote\"", "'triple quote \"\"\" triple quote'")
        assertKeyValueRoundtrip("\"end quote \\\"\"", "'end quote \"'")
        assertKeyValueRoundtrip("\"quoted \\\"content\\\" quoted\"", "'quoted \"content\" quoted'")
        assertKeyValueRoundtrip("\"squoted 'content' squoted\"", "\"squoted 'content' squoted\"")
        assertKeyValueRoundtrip(
            "\"mixed quoted \\\"start\\\" 'end'' mixed quote\"",
            "\"mixed quoted \\\"start\\\" 'end'' mixed quote\"",
        )
    }

    @Test
    fun tableUnderInline() {
        val doc = DocumentMut()
        // An inline table holds only values, so a dotted inline table stands in for upstream's table here.
        val dotted = InlineTable()
        dotted.isDotted = true
        doc["tool"]["typst-test"] = Value.InlineTable(dotted)
        doc["tool"]["typst-test"]["tests"] = "tests"
        assertData("""
tool = { typst-test.tests = "tests" }

""", doc.toString())
    }

    @Test
    fun arrayOfTablesInsertAtBeginning() {
        given("""[[fruit]]
name = "apple"

[[fruit]]
name = "banana"
""")
            .running { root ->
                    val array = root["fruit"].arrayOfTablesOrNull!!
                    val newTable = Table()
                    newTable.put("name", Item(Value.String("cherry")))
                    array.add(0, newTable)
                    // The previously-first table (now at index 1) had no leading blank line
                    // from parsing; fix it up so it separates properly.
                    val moved = array[1]!!
                    moved.decor = moved.decor.copy(prefix = "\n")
            }
            .producesDisplay("""
[[fruit]]
name = "cherry"

[[fruit]]
name = "apple"

[[fruit]]
name = "banana"

""")
    }

    @Test
    fun arrayOfTablesInsertInMiddle() {
        given("""[[fruit]]
name = "apple"

[[fruit]]
name = "banana"
""")
            .running { root ->
                    val array = root["fruit"].arrayOfTablesOrNull!!
                    val newTable = Table()
                    newTable.put("name", Item(Value.String("cherry")))
                    // Give the new table a leading blank line to match the existing style.
                    newTable.decor = newTable.decor.copy(prefix = "\n")
                    array.add(1, newTable)
            }
            .producesDisplay("""
[[fruit]]
name = "apple"

[[fruit]]
name = "cherry"

[[fruit]]
name = "banana"

""")
    }

    @Test
    fun arrayOfTablesInsertAtEnd() {
        given("""[[fruit]]
name = "apple"

[[fruit]]
name = "banana"
""")
            .running { root ->
                    val array = root["fruit"].arrayOfTablesOrNull!!
                    val newTable = Table()
                    newTable.put("name", Item(Value.String("cherry")))
                    // Give the new table a leading blank line to match the existing style.
                    newTable.decor = newTable.decor.copy(prefix = "\n")
                    array.add(2, newTable)
            }
            .producesDisplay("""
[[fruit]]
name = "apple"

[[fruit]]
name = "banana"

[[fruit]]
name = "cherry"

""")
    }

    @Test
    fun arrayOfTablesInsertOutOfBounds() {
        val array = ArrayOfTables()
        val t = Table()
        assertEquals(TomlEditError.IndexOutOfBounds(1, 0), array.add(1, t).unwrapErr())
    }

    @Test
    fun inlineTableToTableWithComment() {
        given("""
# hello i'm a comment
foo = { bar = 1 }
""")
            .running { root ->
                val newFoo = Table()
                newFoo.put("bar", Item(Value.Integer(1L)))
                newFoo.put("baz", Item(Value.Integer(2L)))
                root.getOrNull("foo")!!.assign(Item(newFoo))
            }
            .producesDisplay("""

# hello i'm a comment
[foo]
bar = 1
baz = 2

""")
    }

    @Test
    fun inlineTableToArrayOfTablesWithComment() {
        given("""
# hello i'm a comment
foo = [{ bar = 1 }]
""")
            .running { root ->
                val newFooEntry = Table()
                newFooEntry.put("bar", Item(Value.Integer(1L)))
                newFooEntry.put("baz", Item(Value.Integer(2L)))
                val arr = ArrayOfTables()
                arr.add(newFooEntry)
                root.getOrNull("foo")!!.assign(Item(arr))
            }
            .producesDisplay("""

# hello i'm a comment
[[foo]]
bar = 1
baz = 2

""")
    }

    @Test
    fun arrayOfTablesReplace() {
        given("""[[fruit]]
name = "apple"

# tropical
[[fruit]]
name = "banana"
""")
            .running { root ->
                val array = root["fruit"].arrayOfTablesOrNull!!
                val newTable = Table()
                newTable.put("name", Item(Value.String("cherry")))
                val old = array.set(1, newTable).unwrap()
                assertEquals("banana", old["name"].stringOrNull)
            }
            .producesDisplay("""
[[fruit]]
name = "apple"

[[fruit]]
name = "cherry"

""")
    }

    @Test
    fun arrayOfTablesReplaceOutOfBounds() {
        val array = ArrayOfTables()
        val t = Table()
        assertEquals(TomlEditError.IndexOutOfBounds(0, 0), array.set(0, t).unwrapErr())
    }
}
