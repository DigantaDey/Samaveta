package com.samaveta.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import com.samaveta.android.core.json.Json
import com.samaveta.android.core.json.JsonParseException
import com.samaveta.android.core.json.JsonValue
import com.samaveta.android.core.json.json

class JsonTest {

    @Test
    fun parsesScalarsAndObjects() {
        val v = Json.parseObject("""{"a":1,"b":"two","c":true,"d":null,"e":[1,2],"f":{"g":1.5}}""")
        assertEquals(1L, v.long("a"))
        assertEquals("two", v.string("b"))
        assertEquals(true, v.bool("c"))
        assertTrue(v.has("d"))
        assertNull(v["d"] as? JsonValue.Str)
        assertEquals(2, v.array("e")!!.items.size)
        assertEquals(1.5, v.obj("f")!!.double("g")!!, 0.0001)
    }

    @Test
    fun preservesIntegerPrecisionBeyondDouble() {
        // 2^53 + 1 cannot be represented exactly as a Double; a manifest SHA length or byte offset
        // must not be silently corrupted.
        val raw = "9007199254740993"
        val v = Json.parseObject("""{"n":$raw}""")
        assertEquals(9007199254740993L, v.long("n"))
    }

    @Test
    fun handlesUnicodeEscapesAndDevanagari() {
        val v = Json.parseObject("""{"name":"\u0938\u092e\u0935\u0947\u0924"}""")
        assertEquals("समवेत", v.string("name"))
    }

    @Test
    fun rejectsDuplicateKeys() {
        try {
            Json.parse("""{"a":1,"a":2}""")
            fail("duplicate keys must be rejected")
        } catch (e: JsonParseException) {
            assertTrue(e.message!!.contains("duplicate key"))
        }
    }

    @Test
    fun rejectsTrailingContent() {
        try {
            Json.parse("""{"a":1} garbage""")
            fail("trailing content must be rejected")
        } catch (e: JsonParseException) {
            assertTrue(e.message!!.contains("trailing content"))
        }
    }

    @Test
    fun reportsLineAndColumnForErrors() {
        try {
            Json.parse("{\n  \"a\": ,\n}")
            fail("expected parse failure")
        } catch (e: JsonParseException) {
            assertEquals(2, e.line)
            assertTrue("offset should be inside the input", e.offset > 0)
        }
    }

    @Test
    fun rejectsControlCharactersInStrings() {
        try {
            Json.parse("{\"a\":\"line\u0001break\"}")
            fail("raw control characters must be rejected")
        } catch (e: JsonParseException) {
            assertTrue(e.message!!.contains("control character"))
        }
    }

    @Test
    fun parseOrNullDoesNotThrow() {
        assertNull(Json.parseOrNull("{oops"))
        assertNotNull(Json.parseOrNull("{}"))
    }

    @Test
    fun roundTripsThroughWriter() {
        val original = json {
            "model" to "llama-3.2-1b-instruct"
            "steps" to JsonValue.Arr(listOf(JsonValue.of(1), JsonValue.of(2)))
            "note" to "quote\" and \\ and \n newline"
            "ok" to true
        }
        val text = Json.write(original)
        val reparsed = Json.parseObject(text)
        assertEquals(original, reparsed)
    }

    @Test
    fun prettyOutputIsValidAndIndented() {
        val text = Json.write(json { "a" to 1; "b" to JsonValue.Arr(listOf(JsonValue.of("x"))) }, pretty = true)
        assertTrue(text.contains("\n"))
        assertEquals("""{"a":1,"b":["x"]}""", Json.write(Json.parse(text)))
    }

    @Test
    fun deepNestingDoesNotCrash() {
        val depth = 200
        val text = "[".repeat(depth) + "]".repeat(depth)
        val parsed = Json.parse(text)
        assertNotNull(parsed)
    }
}
