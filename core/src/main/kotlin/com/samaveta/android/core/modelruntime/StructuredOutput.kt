package com.samaveta.android.core.modelruntime

import com.samaveta.android.core.json.Json
import com.samaveta.android.core.json.JsonValue

/**
 * Structured model output.
 *
 * Two product rules make this necessary:
 *  1. "Structured output validity" is a required AI test — a model that returns prose where a schema
 *     was requested must fail loudly, not be guessed at.
 *  2. Model output is *untrusted input*. The same parser is used for web content, so anything a model
 *     can emit, a hostile page can cause it to emit.
 *
 * The parser never "repairs" JSON. A tolerant parser is how a malformed tool call becomes a wrong
 * action, so every deviation raises a typed [StructuredOutputError].
 */
sealed class StructuredOutputError(message: String) : Exception(message) {
    abstract val detail: String

    data class NotJson(override val detail: String) : StructuredOutputError(detail)
    data class NotAnObject(override val detail: String) : StructuredOutputError(detail)
    data class MissingField(override val detail: String) : StructuredOutputError(detail)
    data class WrongType(override val detail: String) : StructuredOutputError(detail)
    data class ValueNotAllowed(override val detail: String) : StructuredOutputError(detail)
    data class TrailingContent(override val detail: String) : StructuredOutputError(detail)

    /** User-facing, non-technical explanation. Never includes raw model output. */
    val userMessage: String
        get() = "The model's response did not match the required format, so nothing was executed."
}

/**
 * Extracts exactly one JSON object from model output.
 *
 * Models frequently wrap JSON in prose or fences. We tolerate *surrounding text* (that is a
 * formatting habit, not a semantic error) but not malformed JSON inside the object: an unbalanced
 * brace or a trailing fragment is rejected.
 */
object StructuredOutput {

    fun extractSingleObject(raw: String): Result<JsonValue.Obj> {
        val candidate = stripFence(raw.trim())
        val start = candidate.indexOf('{')
        if (start < 0) return Result.failure(StructuredOutputError.NotJson("no JSON object found in output"))

        // Scan for the matching close brace, respecting strings and escapes, so embedded braces
        // inside string values do not terminate the object early.
        var depth = 0
        var inString = false
        var escaped = false
        var end = -1
        for (i in start until candidate.length) {
            val c = candidate[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) { end = i; break }
                }
            }
        }
        if (end < 0) return Result.failure(StructuredOutputError.NotJson("unbalanced braces in output"))

        val jsonText = candidate.substring(start, end + 1)
        return when (val parsed = Json.parseOrNull(jsonText)) {
            null -> Result.failure(StructuredOutputError.NotJson("invalid JSON in output"))
            is JsonValue.Obj -> Result.success(parsed)
            else -> Result.failure(StructuredOutputError.NotAnObject("top-level JSON value is not an object"))
        }
    }

    private fun stripFence(text: String): String {
        if (!text.startsWith("```")) return text
        val firstNewline = text.indexOf('\n')
        if (firstNewline < 0) return text
        val closing = text.lastIndexOf("```")
        if (closing <= firstNewline) return text.substring(firstNewline + 1)
        return text.substring(firstNewline + 1, closing)
    }
}

/**
 * A typed schema step. Composition (rather than annotation processing) keeps validation explicit and
 * testable, and lets every failure name the exact path that failed.
 */
sealed interface FieldSchema<T> {
    /** @param key the field name to look up in [obj]; @param path the dotted path used in errors. */
    fun read(obj: JsonValue.Obj, key: String, path: String): Result<T>

    companion object {
        fun string(allowed: Set<String>? = null): FieldSchema<String> = StringField(allowed)
        fun int(min: Int? = null, max: Int? = null): FieldSchema<Int> = IntField(min, max)
        fun bool(): FieldSchema<Boolean> = BoolField()
        fun strings(): FieldSchema<List<String>> = StringListField()

        fun <T> obj(schema: ObjectSchema<T>): FieldSchema<T> = ObjectField(schema)
        fun <T> list(schema: ObjectSchema<T>): FieldSchema<List<T>> = ObjectListField(schema)
    }
}

private class StringField(private val allowed: Set<String>?) : FieldSchema<String> {
    override fun read(obj: JsonValue.Obj, key: String, path: String): Result<String> {
        val v = obj[key] ?: return Result.failure(StructuredOutputError.MissingField("missing '$path'"))
        val s = (v as? JsonValue.Str)?.value
            ?: return Result.failure(StructuredOutputError.WrongType("'$path' must be a string"))
        if (allowed != null && s !in allowed) {
            return Result.failure(
                StructuredOutputError.ValueNotAllowed("'$path' value '$s' is not one of ${allowed.joinToString()}"),
            )
        }
        return Result.success(s)
    }
}

private class IntField(private val min: Int?, private val max: Int?) : FieldSchema<Int> {
    override fun read(obj: JsonValue.Obj, key: String, path: String): Result<Int> {
        val v = obj[key] ?: return Result.failure(StructuredOutputError.MissingField("missing '$path'"))
        val n = (v as? JsonValue.Num)?.takeIf { it.isIntegral }?.toInt()
            ?: return Result.failure(StructuredOutputError.WrongType("'$path' must be an integer"))
        if (min != null && n < min) return Result.failure(StructuredOutputError.ValueNotAllowed("'$path' below minimum $min"))
        if (max != null && n > max) return Result.failure(StructuredOutputError.ValueNotAllowed("'$path' above maximum $max"))
        return Result.success(n)
    }
}

private class BoolField : FieldSchema<Boolean> {
    override fun read(obj: JsonValue.Obj, key: String, path: String): Result<Boolean> {
        val v = obj[key] ?: return Result.failure(StructuredOutputError.MissingField("missing '$path'"))
        return when (v) {
            is JsonValue.Bool -> Result.success(v.value)
            // Accepting "true"/"yes" here would make the boundary fuzzy; a strict schema is the point.
            else -> Result.failure(StructuredOutputError.WrongType("'$path' must be a boolean"))
        }
    }
}

private class StringListField : FieldSchema<List<String>> {
    override fun read(obj: JsonValue.Obj, key: String, path: String): Result<List<String>> {
        val v = obj[key] ?: return Result.failure(StructuredOutputError.MissingField("missing '$path'"))
        val arr = v as? JsonValue.Arr
            ?: return Result.failure(StructuredOutputError.WrongType("'$path' must be an array"))
        if (arr.items.any { it !is JsonValue.Str }) {
            return Result.failure(StructuredOutputError.WrongType("'$path' must contain only strings"))
        }
        return Result.success(arr.strings())
    }
}

private class ObjectField<T>(private val schema: ObjectSchema<T>) : FieldSchema<T> {
    override fun read(obj: JsonValue.Obj, key: String, path: String): Result<T> {
        val v = obj[key] ?: return Result.failure(StructuredOutputError.MissingField("missing '$path'"))
        val child = v as? JsonValue.Obj
            ?: return Result.failure(StructuredOutputError.WrongType("'$path' must be an object"))
        return schema.read(child, prefix = path)
    }
}

private class ObjectListField<T>(private val schema: ObjectSchema<T>) : FieldSchema<List<T>> {
    override fun read(obj: JsonValue.Obj, key: String, path: String): Result<List<T>> {
        val v = obj[key] ?: return Result.failure(StructuredOutputError.MissingField("missing '$path'"))
        val arr = v as? JsonValue.Arr
            ?: return Result.failure(StructuredOutputError.WrongType("'$path' must be an array"))
        val out = mutableListOf<T>()
        arr.items.forEachIndexed { i, item ->
            val child = item as? JsonValue.Obj
                ?: return Result.failure(StructuredOutputError.WrongType("'$path[$i]' must be an object"))
            // Child elements are read by their own field names; only the error path is prefixed.
            val element = schema.read(child, prefix = "$path[$i]")
            if (element.isSuccess) {
                out += element.getOrThrow()
            } else {
                return Result.failure(element.exceptionOrNull() ?: StructuredOutputError.NotJson("unreadable element"))
            }
        }
        return Result.success(out)
    }
}

/** Declarative object schema. Fails on the first violation, naming the exact path. */
class ObjectSchema<T>(
    private val required: List<Pair<String, FieldSchema<*>>>,
    private val build: (Map<String, Any?>) -> T,
    private val optional: List<Pair<String, FieldSchema<*>>> = emptyList(),
    private val forbidUnknownFields: Boolean = true,
) {
    private val known = (required + optional).map { it.first }.toSet()

    fun read(obj: JsonValue.Obj, prefix: String = ""): Result<T> {
        if (forbidUnknownFields) {
            val unknown = obj.fields.keys - known
            if (unknown.isNotEmpty()) {
                // An unexpected field is often the signature of injected instructions riding along
                // with a legitimate response.
                return Result.failure(
                    StructuredOutputError.ValueNotAllowed("unexpected field(s): ${unknown.joinToString()}"),
                )
            }
        }
        val values = mutableMapOf<String, Any?>()
        for ((name, schema) in required) {
            val path = if (prefix.isEmpty()) name else "$prefix.$name"
            val field = schema.read(obj, name, path)
            if (field.isSuccess) {
                values[name] = field.getOrThrow()
            } else {
                return Result.failure(field.exceptionOrNull() ?: StructuredOutputError.NotJson("unreadable field"))
            }
        }
        for ((name, schema) in optional) {
            val path = if (prefix.isEmpty()) name else "$prefix.$name"
            if (obj.has(name)) {
                val field = schema.read(obj, name, path)
                if (field.isSuccess) {
                    values[name] = field.getOrThrow()
                } else {
                    return Result.failure(field.exceptionOrNull() ?: StructuredOutputError.NotJson("unreadable field"))
                }
            }
        }
        return Result.success(build(values))
    }
}

/**
 * Prompt-injection containment.
 *
 * The real defence is architectural: model output never becomes an action without passing
 * [com.samaveta.android.core.laya.LayaDecisionValidator] and
 * [com.samaveta.android.core.security.CapabilityAuthorizer]. This class is a *detector*, used to
 * quarantine untrusted content and to flag it in diagnostics — not a security boundary by itself.
 *
 * The asymmetry is deliberate: a false positive costs one redacted paragraph; a false negative
 * could cost a user their money, so detection is biased towards flagging.
 */
object InjectionDetector {

    data class Finding(val category: Category, val excerpt: String)

    enum class Category(val label: String) {
        INSTRUCTION_OVERRIDE("attempts to override prior instructions"),
        ROLE_IMPERSONATION("impersonates a system or developer message"),
        SECRET_EXFILTRATION("attempts to obtain credentials or personal data"),
        CAPABILITY_ESCALATION("attempts to enable or bypass permissions"),
        TOOL_CALL_SMUGGLING("contains executable or tool-call-shaped content"),
        HIDDEN_TEXT("contains hidden or invisible instruction text"),
    }

    /** Phrases that are suspicious in *content* but normal in the user's own request. */
    private val patterns: List<Pair<Category, Regex>> = listOf(
        Category.INSTRUCTION_OVERRIDE to Regex(
            "\\b(ignore|disregard|forget)\\b[^.]{0,40}\\b(previous|prior|above|earlier|system)\\b[^.]{0,20}\\b(instructions?|prompts?|rules?)\\b",
            RegexOption.IGNORE_CASE,
        ),
        Category.ROLE_IMPERSONATION to Regex(
            "(^|\\n)\\s*(system|developer|admin)\\s*(prompt|message)?\\s*:",
            RegexOption.IGNORE_CASE,
        ),
        Category.SECRET_EXFILTRATION to Regex(
            "\\b(send|share|reveal|print|output|paste)\\b[^.]{0,40}\\b(password|api[ _-]?key|token|otp|one[ -]?time code|seed phrase|recovery phrase|card number|cvv)\\b",
            RegexOption.IGNORE_CASE,
        ),
        Category.CAPABILITY_ESCALATION to Regex(
            "\\b(grant|enable|disable|bypass|turn off)\\b[^.]{0,40}\\b(permission|authori[sz]ation|confirmation|security|verification|safety)\\b",
            RegexOption.IGNORE_CASE,
        ),
        Category.TOOL_CALL_SMUGGLING to Regex(
            "<\\s*(tool|function|action|capability)[^>]*>|\\bcall\\s+the\\s+\\w+\\s+tool\\b",
            RegexOption.IGNORE_CASE,
        ),
        Category.HIDDEN_TEXT to Regex(
            "[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069]|font-size\\s*:\\s*0|display\\s*:\\s*none",
            RegexOption.IGNORE_CASE,
        ),
    )

    fun scan(content: String): List<Finding> = patterns.mapNotNull { (category, regex) ->
        val match = regex.find(content) ?: return@mapNotNull null
        val start = (match.range.first - 20).coerceAtLeast(0)
        val end = (match.range.last + 40).coerceAtMost(content.length)
        Finding(category, content.substring(start, end).replace('\n', ' ').trim())
    }

    fun isSuspicious(content: String): Boolean = scan(content).isNotEmpty()
}
