package com.samaveta.android.core.laya.v1

import com.samaveta.android.core.json.Json
import com.samaveta.android.core.json.JsonValue
import com.samaveta.android.core.json.json

/**
 * Encodes LAYA v1 requests and decodes its responses.
 *
 * Decoding is strict on purpose. LAYA's own TypeScript client refuses a payload whose answers are
 * malformed, and SAMAVETA has a stronger reason: an answer drives an action. A response we cannot
 * fully account for must become [LayaV1Failure.Malformed] before any of it is used, never a
 * best-effort guess.
 */
object LayaV1Codec {

    // ---------------------------------------------------------------- encode

    fun encodePredict(request: LayaPredictRequest): String = Json.write(
        json {
            "state" to request.state
            "questions" to JsonValue.Obj(request.questions.mapValues { (_, question) -> encodeQuestion(question) })
            putIfNotNull("model", request.model)
            putIfNotNull("task", request.task)
            putIfNotNull("lang", request.lang)
            putIfNotNull("lang_guess", request.langGuess)
            request.maxLen?.let { "max_len" to it }
            request.headMaxLen?.let { "head_max_len" to it }
            request.minConfidence?.let { "min_confidence" to encodeMinConfidence(it) }
        },
    )

    /**
     * Encodes a batch: `states` is a plain array of states and `questions` sits at the top level.
     * Verified against a live server, which rejects any other shape with a 400.
     */
    fun encodeBatch(request: LayaBatchRequest): String = Json.write(
        json {
            "states" to JsonValue.Arr(request.states)
            "questions" to JsonValue.Obj(
                request.questions.mapValues { (_, question) -> encodeQuestion(question) },
            )
            putIfNotNull("model", request.model)
            request.maxLen?.let { "max_len" to it }
            request.headMaxLen?.let { "head_max_len" to it }
            request.minConfidence?.let { "min_confidence" to encodeMinConfidence(it) }
        },
    )

    fun encodeRoute(request: LayaRouteRequest): String = Json.write(
        json {
            "state" to request.state
            "questions" to JsonValue.Obj(emptyMap())
            "explain" to request.explain
            putIfNotNull("model", request.model)
        },
    )

    private fun encodeQuestion(question: LayaQuestion): JsonValue.Obj = when (question) {
        is LayaQuestion.Choice -> json {
            "type" to "choice"
            "instructions" to question.instructions
            "criteria" to when {
                question.descriptions.isEmpty() -> JsonValue.Arr(question.criteria.map { JsonValue.Str(it) })
                else -> JsonValue.Obj(
                    question.criteria.associateWith { label ->
                        question.descriptions[label] ?: JsonValue.Str(label)
                    },
                )
            }
        }

        is LayaQuestion.Score -> json {
            "type" to "score"
            "instructions" to question.instructions
            "criteria" to JsonValue.Arr(question.criteria)
        }

        is LayaQuestion.Noul -> json {
            "type" to "noul"
            "instructions" to question.instructions
            if (question.trueLabel != null || question.falseLabel != null) {
                "criteria" to json {
                    putIfNotNull("true", question.trueLabel)
                    putIfNotNull("false", question.falseLabel)
                }
            }
        }
    }

    private fun encodeMinConfidence(minConfidence: MinConfidence): JsonValue = when (minConfidence) {
        is MinConfidence.Single -> JsonValue.of(minConfidence.threshold)
        is MinConfidence.Buckets -> JsonValue.Obj(
            minConfidence.byBucket.mapValues { (_, value) -> JsonValue.of(value) },
        )
    }

    // ---------------------------------------------------------------- decode

    fun decodePrediction(body: String): LayaPrediction {
        val root = parseObject(body)
        val model = root.string("model")?.takeIf { it.isNotBlank() }
            ?: throw LayaV1Failure.Malformed("response has no model field")
        val answerObject = root.obj("answers")
            ?: throw LayaV1Failure.Malformed("response has no answers object")
        if (answerObject.fields.isEmpty()) {
            throw LayaV1Failure.Malformed("response carried zero answers")
        }

        val answers = answerObject.fields.mapValues { (id, value) -> decodeAnswer(id, value) }
        val usage = root.obj("usage")?.let(::decodeUsage) ?: LayaUsage.UNKNOWN
        val routing = root.obj("routing")?.let(::decodeRouting)
        return LayaPrediction(model = model, answers = answers, usage = usage, routing = routing)
    }

    /** Batch responses wrap predictions in `results`; each is decoded independently and strictly. */
    fun decodeBatch(body: String): List<LayaPrediction> {
        val root = parseObject(body)
        val results = root.array("results")
            ?: throw LayaV1Failure.Malformed("batch response has no results array")
        if (results.items.isEmpty()) throw LayaV1Failure.Malformed("batch response carried zero results")
        return results.items.mapIndexed { index, value ->
            val obj = value as? JsonValue.Obj
                ?: throw LayaV1Failure.Malformed("batch result[$index] is not an object")
            try {
                decodePrediction(Json.write(obj))
            } catch (failure: LayaV1Failure) {
                throw LayaV1Failure.Malformed("batch result[$index]: ${failure.message}")
            }
        }
    }

    fun decodeHealth(body: String): LayaHealth {
        val root = parseObject(body)
        // A deployment with LAYA_API_KEY answers an unauthenticated probe with {"status":"ok"} only.
        val status = root.string("status") ?: throw LayaV1Failure.Malformed("health response has no status")
        return LayaHealth(
            status = status,
            loaded = root.array("loaded")?.strings() ?: emptyList(),
            revisions = root.obj("revisions")?.fields?.mapNotNull { (key, value) ->
                (value as? JsonValue.Str)?.let { key to it.value }
            }?.toMap() ?: emptyMap(),
            device = root.string("device") ?: "unknown",
            deviceIsPreference = root.bool("device_is_preference") ?: (root.obj("loaded") == null),
            cpuFallbacks = root.obj("cpu_fallbacks")?.fields?.mapNotNull { (key, value) ->
                (value as? JsonValue.Obj)?.int("count")?.let { key to it }
            }?.toMap() ?: emptyMap(),
        )
    }

    fun decodeRoute(body: String): LayaRouteSnapshot {
        val root = parseObject(body)
        return LayaRouteSnapshot(
            model = root.string("model") ?: throw LayaV1Failure.Malformed("route response has no model"),
            repo = root.string("repo"),
            reason = root.string("reason") ?: "",
        )
    }

    /**
     * Decodes an error body. LAYA answers either `{"error": {message, code, details}}` or FastAPI's
     * `{"detail": ...}` (a string for a raised ValueError, a list for a validation error).
     */
    fun decodeError(statusCode: Int, body: String): LayaV1Failure {
        if (statusCode == 503) return LayaV1Failure.Overloaded(retryAfterMs = null)
        val root = Json.parseOrNull(body) as? JsonValue.Obj
        val errorObject = root?.obj("error")
        val code = errorObject?.string("code") ?: "http_error"
        val message = errorObject?.string("message")
            ?: root?.string("detail")
            ?: root?.array("detail")?.let { details ->
                // FastAPI validation errors: a list of {loc, msg, type}. Summarise the first.
                details.objects().firstOrNull()?.string("msg")
            }
            ?: if (body.isBlank()) "no detail in the response body" else body.take(300)
        return when {
            statusCode in 500..599 -> LayaV1Failure.ServerError(statusCode, message)
            else -> LayaV1Failure.Rejected(statusCode, code, message)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun parseObject(body: String): JsonValue.Obj = try {
        Json.parseObject(body)
    } catch (failure: Exception) {
        throw LayaV1Failure.Malformed("response is not a JSON object: ${failure.message}")
    }

    private fun decodeAnswer(id: QuestionId, value: JsonValue): LayaAnswer {
        val obj = value as? JsonValue.Obj
            ?: throw LayaV1Failure.Malformed("answer '$id' is not an object")
        val type = obj.string("type")
            ?: throw LayaV1Failure.Malformed("answer '$id' has no type")
        val answerConfidence = optionalProbability(obj, "answer_confidence", id)
        val confidence = optionalProbability(obj, "confidence", id)
        val actProbability = obj.obj("action")?.let { optionalProbability(it, "act_probability", id) }
        val abstention = obj.string("abstention")?.let { raw ->
            Abstention.fromWire(raw)
                ?: throw LayaV1Failure.Malformed("answer '$id' has unknown abstention '$raw'")
        }
        val threshold = optionalProbability(obj, "abstention_threshold", id)

        return when (type) {
            "choice" -> {
                val choice = obj.string("choice")
                    ?: throw LayaV1Failure.Malformed("choice answer '$id' has no choice")
                val probabilities = decodeProbabilities(obj, id)
                if (choice !in probabilities) {
                    // A label that is not among its own probabilities cannot be mapped to an option.
                    throw LayaV1Failure.Malformed("choice answer '$id' reported '$choice' outside its probabilities")
                }
                LayaAnswer.Choice(
                    choice = choice,
                    probabilities = probabilities,
                    answerConfidence = answerConfidence,
                    confidence = confidence,
                    actProbability = actProbability,
                    abstention = abstention,
                    abstentionThreshold = threshold,
                    xJevConfidence = optionalProbability(obj, "x_jev_confidence", id),
                )
            }

            "score" -> {
                val score = obj.double("score")
                    ?: throw LayaV1Failure.Malformed("score answer '$id' has no numeric score")
                val legend = obj.obj("legend")?.fields
                    ?: throw LayaV1Failure.Malformed("score answer '$id' has no legend")
                LayaAnswer.Score(
                    score = score,
                    probabilities = decodeProbabilities(obj, id),
                    legend = legend,
                    answerConfidence = answerConfidence,
                    confidence = confidence,
                    actProbability = actProbability,
                    abstention = abstention,
                    abstentionThreshold = threshold,
                    xJevConfidence = optionalProbability(obj, "x_jev_confidence", id),
                )
            }

            "noul" -> {
                val probability = obj.double("noul")
                    ?: throw LayaV1Failure.Malformed("noul answer '$id' has no probability")
                if (probability !in 0.0..1.0) {
                    throw LayaV1Failure.Malformed("noul answer '$id' probability $probability is outside 0..1")
                }
                LayaAnswer.Noul(
                    probabilityTrue = probability,
                    answerConfidence = answerConfidence,
                    confidence = confidence,
                    actProbability = actProbability,
                    abstention = abstention,
                    abstentionThreshold = threshold,
                )
            }

            else -> throw LayaV1Failure.Malformed("answer '$id' has unknown type '$type'")
        }
    }

    private fun decodeProbabilities(obj: JsonValue.Obj, id: QuestionId): Map<String, Double> {
        val map = obj.obj("probabilities")
            ?: throw LayaV1Failure.Malformed("answer '$id' has no probabilities object")
        if (map.fields.isEmpty()) throw LayaV1Failure.Malformed("answer '$id' has empty probabilities")
        return map.fields.mapValues { (label, value) ->
            val number = (value as? JsonValue.Num)?.toDouble()
                ?: throw LayaV1Failure.Malformed("probability '$id.$label' is not a number")
            if (!number.isFinite() || number !in 0.0..1.0) {
                throw LayaV1Failure.Malformed("probability '$id.$label' = $number is outside 0..1")
            }
            number
        }
    }

    private fun decodeUsage(obj: JsonValue.Obj): LayaUsage = LayaUsage(
        inputTokens = nonNegativeInt(obj, "input_tokens"),
        outputTokens = nonNegativeInt(obj, "output_tokens"),
        stateTokens = nonNegativeInt(obj, "state_tokens"),
        stateTokensDropped = nonNegativeInt(obj, "state_tokens_dropped"),
        truncated = obj.bool("truncated") ?: false,
        truncatedQuestions = obj.array("truncated_questions")?.strings() ?: emptyList(),
        options = obj.obj("options")?.fields?.mapNotNull { (id, value) ->
            val collapse = value as? JsonValue.Obj ?: return@mapNotNull null
            val total = collapse.int("total") ?: return@mapNotNull null
            val distinct = collapse.int("distinct") ?: total
            id to LayaOptionCollapse(
                total = total,
                distinct = distinct,
                tokensPerOption = collapse.int("tokens_per_option"),
            )
        }?.toMap() ?: emptyMap(),
    )

    private fun decodeRouting(obj: JsonValue.Obj): LayaRouting = LayaRouting(
        model = obj.string("model") ?: throw LayaV1Failure.Malformed("routing has no model"),
        repo = obj.string("repo") ?: "",
        reason = obj.string("reason") ?: "",
        detection = obj.obj("detection")?.let { detection ->
            LayaLanguageDetection(
                script = detection.string("script") ?: "",
                language = detection.string("language"),
                isEnglish = detection.bool("is_english") ?: false,
                languageUndecided = detection.bool("language_undecided") ?: false,
                nonLatinFraction = detection.double("non_latin_fraction") ?: 0.0,
                mixedSegment = detection.string("mixed_segment"),
            )
        },
        workflow = obj.string("workflow"),
    )

    private fun nonNegativeInt(obj: JsonValue.Obj, key: String): Int {
        val value = obj.int(key) ?: return 0
        return value.coerceAtLeast(0)
    }

    /**
     * Reads an optional probability field. Absence yields null (meaning "not reported"); a present
     * but invalid value is a hard failure, because a NaN confidence would poison every comparison
     * downstream.
     */
    private fun optionalProbability(obj: JsonValue.Obj, key: String, id: QuestionId): Double? {
        val value = obj.fields[key] ?: return null
        if (value is JsonValue.Null) return null
        val number = (value as? JsonValue.Num)?.toDouble()
            ?: throw LayaV1Failure.Malformed("'$key' on answer '$id' is not a number")
        if (!number.isFinite() || number !in 0.0..1.0) {
            throw LayaV1Failure.Malformed("'$key' on answer '$id' = $number is outside 0..1")
        }
        return number
    }
}
