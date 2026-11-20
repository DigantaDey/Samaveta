package com.samaveta.android.core.json

/**
 * A small, dependency-free JSON model + parser + writer.
 *
 * Why this exists rather than kotlinx.serialization:
 *  - The LAYA integration must map an external, versioned wire contract. Mapping code needs a JSON
 *    model it can inspect field-by-field (and validate), not only generated serializers.
 *  - Model manifests, structured model output and feed parsing all need defensive parsing of
 *    *untrusted* text with precise error positions.
 *  - Keeping `core` free of third-party runtime dependencies lets it be compiled and tested on a
 *    plain JVM, which is how this repository is verified.
 *
 * Numbers keep their original literal so integers do not lose precision through Double round-trips.
 */
sealed interface JsonValue {

    data class Obj(val fields: Map<String, JsonValue> = emptyMap()) : JsonValue {
        operator fun get(key: String): JsonValue? = fields[key]
        fun string(key: String): String? = (fields[key] as? Str)?.value
        fun long(key: String): Long? = (fields[key] as? Num)?.toLong()
        fun int(key: String): Int? = (fields[key] as? Num)?.toLong()?.toInt()
        fun double(key: String): Double? = (fields[key] as? Num)?.toDouble()
        fun bool(key: String): Boolean? = (fields[key] as? Bool)?.value
        fun obj(key: String): Obj? = fields[key] as? Obj
        fun array(key: String): Arr? = fields[key] as? Arr
        fun strings(key: String): List<String> =
            (fields[key] as? Arr)?.items?.mapNotNull { (it as? Str)?.value } ?: emptyList()
        fun has(key: String): Boolean = fields.containsKey(key)
    }

    data class Arr(val items: List<JsonValue> = emptyList()) : JsonValue {
        fun objects(): List<Obj> = items.filterIsInstance<Obj>()
        fun strings(): List<String> = items.mapNotNull { (it as? Str)?.value }
    }

    data class Str(val value: String) : JsonValue

    data class Num(val literal: String) : JsonValue {
        init {
            require(literal.isNotBlank()) { "numeric literal must not be blank" }
        }

        val isIntegral: Boolean get() = literal.none { it == '.' || it == 'e' || it == 'E' }
        fun toDouble(): Double = literal.toDouble()
        fun toLong(): Long = if (isIntegral) literal.toLong() else literal.toDouble().toLong()
        fun toInt(): Int = toLong().toInt()
    }

    data class Bool(val value: Boolean) : JsonValue

    data object Null : JsonValue

    companion object {
        fun of(value: String): Str = Str(value)
        fun of(value: Int): Num = Num(value.toString())
        fun of(value: Long): Num = Num(value.toString())
        fun of(value: Double): Num = Num(if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString())
        fun of(value: Boolean): Bool = Bool(value)
        fun of(value: Map<String, JsonValue>): Obj = Obj(value)
    }
}

class JsonParseException(message: String, val offset: Int, val line: Int, val column: Int) :
    IllegalArgumentException("$message at line $line, column $column (offset $offset)")

object Json {

    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) parser.fail("trailing content after JSON value")
        return value
    }

    /** Parses and requires a top-level object. */
    fun parseObject(text: String): JsonValue.Obj = when (val v = parse(text)) {
        is JsonValue.Obj -> v
        else -> throw JsonParseException("expected a JSON object at the top level", 0, 1, 1)
    }

    /** Parses, returning null instead of throwing — for untrusted external input. */
    fun parseOrNull(text: String): JsonValue? = try {
        parse(text)
    } catch (_: JsonParseException) {
        null
    }

    fun write(value: JsonValue, pretty: Boolean = false): String =
        StringBuilder().also { Writer(pretty).write(value, it) }.toString()

    private class Parser(private val text: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun fail(message: String): Nothing {
            var line = 1
            var column = 1
            for (i in 0 until minOf(index, text.length)) {
                if (text[i] == '\n') {
                    line++
                    column = 1
                } else {
                    column++
                }
            }
            throw JsonParseException(message, index, line, column)
        }

        fun parseValue(): JsonValue {
            skipWhitespace()
            if (atEnd()) fail("unexpected end of input")
            return when (val c = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't' -> parseLiteral("true", JsonValue.Bool(true))
                'f' -> parseLiteral("false", JsonValue.Bool(false))
                'n' -> parseLiteral("null", JsonValue.Null)
                else -> if (c == '-' || c.isDigit()) parseNumber() else fail("unexpected character '$c'")
            }
        }

        private fun parseLiteral(literal: String, value: JsonValue): JsonValue {
            if (!text.startsWith(literal, index)) fail("invalid literal, expected '$literal'")
            index += literal.length
            return value
        }

        private fun parseObject(): JsonValue.Obj {
            index++ // '{'
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (!atEnd() && text[index] == '}') {
                index++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || text[index] != '"') fail("expected a quoted object key")
                val key = parseString()
                skipWhitespace()
                if (atEnd() || text[index] != ':') fail("expected ':' after object key \"$key\"")
                index++
                val value = parseValue()
                if (fields.containsKey(key)) fail("duplicate key \"$key\" in object")
                fields[key] = value
                skipWhitespace()
                if (atEnd()) fail("unterminated object")
                when (text[index]) {
                    ',' -> index++
                    '}' -> { index++; return JsonValue.Obj(fields) }
                    else -> fail("expected ',' or '}' in object")
                }
            }
        }

        private fun parseArray(): JsonValue.Arr {
            index++ // '['
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (!atEnd() && text[index] == ']') {
                index++
                return JsonValue.Arr(items)
            }
            while (true) {
                items += parseValue()
                skipWhitespace()
                if (atEnd()) fail("unterminated array")
                when (text[index]) {
                    ',' -> index++
                    ']' -> { index++; return JsonValue.Arr(items) }
                    else -> fail("expected ',' or ']' in array")
                }
            }
        }

        private fun parseString(): String {
            index++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                when (val c = text[index]) {
                    '"' -> { index++; return sb.toString() }
                    '\\' -> {
                        index++
                        if (atEnd()) fail("unterminated escape sequence")
                        when (val esc = text[index]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (index + 4 >= text.length) fail("truncated \\u escape")
                                val hex = text.substring(index + 1, index + 5)
                                val code = hex.toIntOrNull(16) ?: fail("invalid \\u escape \"$hex\"")
                                sb.append(code.toChar())
                                index += 4
                            }
                            else -> fail("invalid escape sequence '\\$esc'")
                        }
                        index++
                    }
                    else -> {
                        if (c.code < 0x20) fail("unescaped control character in string")
                        sb.append(c)
                        index++
                    }
                }
            }
        }

        private fun parseNumber(): JsonValue.Num {
            val start = index
            if (!atEnd() && text[index] == '-') index++
            if (atEnd() || !text[index].isDigit()) fail("invalid number")
            if (text[index] == '0' && index + 1 < text.length && text[index + 1].isDigit()) {
                fail("leading zeros are not permitted in numbers")
            }
            while (!atEnd() && text[index].isDigit()) index++
            if (!atEnd() && text[index] == '.') {
                index++
                if (atEnd() || !text[index].isDigit()) fail("expected digits after decimal point")
                while (!atEnd() && text[index].isDigit()) index++
            }
            if (!atEnd() && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (!atEnd() && (text[index] == '+' || text[index] == '-')) index++
                if (atEnd() || !text[index].isDigit()) fail("expected exponent digits")
                while (!atEnd() && text[index].isDigit()) index++
            }
            return JsonValue.Num(text.substring(start, index))
        }
    }

    private class Writer(private val pretty: Boolean, private val indentUnit: String = "  ") {
        private val out = StringBuilder()
        private var depth = 0

        fun write(value: JsonValue, into: StringBuilder) {
            render(value)
            into.append(out)
        }

        private fun newline() {
            if (pretty) {
                out.append('\n')
                repeat(depth) { out.append(indentUnit) }
            }
        }

        private fun render(value: JsonValue) {
            when (value) {
                is JsonValue.Obj -> {
                    if (value.fields.isEmpty()) { out.append("{}"); return }
                    out.append('{')
                    depth++
                    var first = true
                    for ((k, v) in value.fields) {
                        if (!first) out.append(',')
                        first = false
                        newline()
                        writeString(k)
                        out.append(if (pretty) ": " else ":")
                        render(v)
                    }
                    depth--
                    newline()
                    out.append('}')
                }

                is JsonValue.Arr -> {
                    if (value.items.isEmpty()) { out.append("[]"); return }
                    out.append('[')
                    depth++
                    value.items.forEachIndexed { i, item ->
                        if (i > 0) out.append(',')
                        newline()
                        render(item)
                    }
                    depth--
                    newline()
                    out.append(']')
                }

                is JsonValue.Str -> writeString(value.value)
                is JsonValue.Num -> out.append(value.literal)
                is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
                JsonValue.Null -> out.append("null")
            }
        }

        private fun writeString(s: String) {
            out.append('"')
            for (c in s) {
                when (c) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    '\b' -> out.append("\\b")
                    '\u000C' -> out.append("\\f")
                    else -> if (c.code < 0x20) {
                        out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        out.append(c)
                    }
                }
            }
            out.append('"')
        }
    }
}

/** Tiny builder so call sites read like object literals. */
fun json(build: JsonObjectBuilder.() -> Unit): JsonValue.Obj =
    JsonValue.Obj(JsonObjectBuilder().apply(build).fields)

class JsonObjectBuilder {
    val fields = LinkedHashMap<String, JsonValue>()

    infix fun String.to(value: String) { fields[this] = JsonValue.Str(value) }
    infix fun String.to(value: Int) { fields[this] = JsonValue.of(value) }
    infix fun String.to(value: Long) { fields[this] = JsonValue.of(value) }
    infix fun String.to(value: Double) { fields[this] = JsonValue.of(value) }
    infix fun String.to(value: Boolean) { fields[this] = JsonValue.of(value) }
    infix fun String.to(value: JsonValue) { fields[this] = value }

    fun putIfNotNull(key: String, value: String?) {
        if (value != null) fields[key] = JsonValue.Str(value)
    }

    fun stringArray(key: String, values: Collection<String>) {
        fields[key] = JsonValue.Arr(values.map { JsonValue.Str(it) })
    }
}
