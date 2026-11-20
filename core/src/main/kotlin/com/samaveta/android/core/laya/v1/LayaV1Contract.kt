package com.samaveta.android.core.laya.v1

import com.samaveta.android.core.json.JsonValue

/**
 * LAYA v1: the **real** wire contract, as published by LAYA's own repository.
 *
 * Source of truth (read and reconciled, not guessed):
 *  - `docs/http-api.md` — `POST /v1/systemone`, `POST /v1/systemone/batch`, `POST /v1/route`, `GET /health`
 *  - `sdk/typescript/src/types.ts`, `sdk/typescript/src/validation.ts` — request/response shape and
 *    the client-side validation LAYA itself performs
 *  - `sdk/python/tests/_support.py` — LAYA's own fixture payload
 *
 * **This is not a planner.** The earlier `LayaDecision`/`LayaPlan` model in this module was a
 * *proposed* contract written before the real engine was available, and it was wrong in a way that
 * matters: LAYA answers **typed questions about a state** (`choice` / `score` / `noul`) in one
 * forward pass with calibrated probabilities. It does not emit multi-step plans, tool calls or
 * capability requests. SAMAVETA therefore asks a small, fixed set of decision questions and executes
 * the resulting plan itself, under `ActionPipeline`. See `LayaQuestions`.
 *
 * Design rules encoded here:
 *  - **Strict decoding.** Unknown answer `type`s, probabilities outside `[0,1]`, NaN/Infinity,
 *    malformed `routing`, and a non-object payload are all rejected. LAYA's own client behaves this
 *    way, and a permissive decoder would let a broken server silently change what SAMAVETA does.
 *  - **Absence is meaningful.** `abstention` absent means *no gate ran*; it is not "passed".
 *    `truncated` means part of the state never reached the model, so an answer may be based on less
 *    than SAMAVETA sent.
 *  - **Nothing is inferred from a confidence number.** `answer_confidence` is reported faithfully and
 *    the decision to act is taken by SAMAVETA policy, never by "confidence > 0.8" here.
 */

// ------------------------------------------------------------------ questions

/** Question id, stable across releases. Used as the key in LAYA's `questions` object. */
typealias QuestionId = String

sealed interface LayaQuestion {
    /** Shown to the model. JSON so a question can carry structured context. */
    val instructions: JsonValue

    /**
     * A closed set of options; the answer is the argmax with a full probability map.
     * `criteria` is either an array of unique string labels or an object of label → description.
     */
    data class Choice(
        override val instructions: JsonValue,
        val criteria: List<String>,
        val descriptions: Map<String, JsonValue> = emptyMap(),
    ) : LayaQuestion {
        init {
            require(criteria.size >= 2) { "a choice needs at least two options" }
            require(criteria.distinct().size == criteria.size) { "choice labels must be unique" }
        }
    }

    /**
     * An ordered rubric; the answer is an expected level index (may fall between levels) plus a
     * probability per level.
     */
    data class Score(
        override val instructions: JsonValue,
        val criteria: List<JsonValue>,
    ) : LayaQuestion {
        init {
            require(criteria.size >= 2) { "a score needs at least two levels" }
        }
    }

    /** A two-way decision. The answer is **P(true)**, a probability — never a boolean. */
    data class Noul(
        override val instructions: JsonValue,
        val trueLabel: String? = null,
        val falseLabel: String? = null,
    ) : LayaQuestion

    val type: String
        get() = when (this) {
            is Choice -> "choice"
            is Score -> "score"
            is Noul -> "noul"
        }
}

/** Per-option-count thresholds. One threshold does not transfer across option counts. */
sealed interface MinConfidence {
    data class Single(val threshold: Double) : MinConfidence

    /** Keyed by `type:size` bucket (`choice:2`, `score:3-5`, …, `noul:11+`) plus optional `default`. */
    data class Buckets(val byBucket: Map<String, Double>) : MinConfidence

    companion object {
        /** Bucket keys LAYA's core can produce. Anything else is a 422 at the server. */
        val VALID_BUCKET_KEYS: Set<String> = buildSet {
            val sizes = listOf("2", "3-5", "6-10", "11+")
            listOf("choice", "score", "noul").forEach { type ->
                sizes.forEach { add("$type:$it") }
            }
            add("default")
        }
    }
}

/** A route snapshot request: which checkpoint would answer, and why. No inference. */
data class LayaRouteRequest(
    val state: JsonValue,
    val explain: Boolean = false,
    /** Optional checkpoint pin; omit to let the deployed router choose. */
    val model: String? = null,
)

// ------------------------------------------------------------------ prediction request

data class LayaPredictRequest(
    val state: JsonValue,
    val questions: Map<QuestionId, LayaQuestion>,
    /** Checkpoint name or alias. Omit (null) to let the router choose — the recommended default. */
    val model: String? = null,
    val task: String? = null,
    val lang: String? = null,
    val langGuess: String? = null,
    val maxLen: Int? = null,
    val headMaxLen: Int? = null,
    val minConfidence: MinConfidence? = null,
) {
    init {
        require(questions.isNotEmpty()) { "a prediction needs at least one question" }
        require(questions.size <= MAX_QUESTIONS) { "at most $MAX_QUESTIONS questions per request" }
        require(maxLen == null || maxLen in 1..MAX_TOKEN_BUDGET) { "maxLen outside 1..$MAX_TOKEN_BUDGET" }
        require(headMaxLen == null || headMaxLen in 1..MAX_TOKEN_BUDGET) { "headMaxLen outside 1..$MAX_TOKEN_BUDGET" }
        val single = minConfidence as? MinConfidence.Single
        require(single == null || single.threshold in 0.0..1.0) { "minConfidence must be within 0..1" }
        val buckets = minConfidence as? MinConfidence.Buckets
        if (buckets != null) {
            buckets.byBucket.forEach { (key, value) ->
                require(key in MinConfidence.VALID_BUCKET_KEYS) { "unknown min-confidence bucket '$key'" }
                require(value in 0.0..1.0) { "minConfidence for '$key' must be within 0..1" }
            }
        }
    }

    companion object {
        const val MAX_QUESTIONS = 100
        /** LAYA's documented ceiling on `max_len` / `head_max_len`. */
        const val MAX_TOKEN_BUDGET = 8_192
    }
}

/** One entry of a batch request: the same `state` + `questions` shape, no per-item controls. */
data class LayaBatchItem(val state: JsonValue, val questions: Map<QuestionId, LayaQuestion>)

// ------------------------------------------------------------------ answers

/** Keys LAYA returns on every answer of a *gated* call, and only then. */
enum class Abstention {
    /** Confidence cleared the threshold. */
    PASSED,

    /** Confidence fell below the threshold; the answer is kept and flagged. */
    ABSTAINED,

    /** The answer carried no usable confidence, so the gate could not decide. Not a pass. */
    UNEVALUATED,
    ;

    companion object {
        fun fromWire(value: String): Abstention? = when (value) {
            "passed" -> PASSED
            "abstained" -> ABSTAINED
            "unevaluated" -> UNEVALUATED
            else -> null
        }
    }
}

sealed interface LayaAnswer {
    /**
     * Probability mass on the reported answer, `max(p)` — the only number comparable across all three
     * answer types, and the one the abstention gate thresholds on.
     */
    val answerConfidence: Double?

    /** Normalised entropy for choice/score, `max(p_yes,p_no)` for noul. Not comparable to above. */
    val confidence: Double?

    /** `act_probability`. Optional metadata LAYA may return. */
    val actProbability: Double?

    val abstention: Abstention?
    val abstentionThreshold: Double?

    data class Choice(
        val choice: String,
        val probabilities: Map<String, Double>,
        override val answerConfidence: Double?,
        override val confidence: Double?,
        override val actProbability: Double?,
        override val abstention: Abstention?,
        override val abstentionThreshold: Double?,
        /** Jev-compatibility number; absent on strict-mode servers. Never gate on it by accident. */
        val xJevConfidence: Double?,
    ) : LayaAnswer

    data class Score(
        /** Expected zero-based rubric index; may fall between levels. */
        val score: Double,
        val probabilities: Map<String, Double>,
        /** Index → level text, as the server echoed it. */
        val legend: Map<String, JsonValue>,
        override val answerConfidence: Double?,
        override val confidence: Double?,
        override val actProbability: Double?,
        override val abstention: Abstention?,
        override val abstentionThreshold: Double?,
        val xJevConfidence: Double?,
    ) : LayaAnswer

    data class Noul(
        /** P(true). A probability, not a decision. */
        val probabilityTrue: Double,
        override val answerConfidence: Double?,
        override val confidence: Double?,
        override val actProbability: Double?,
        override val abstention: Abstention?,
        override val abstentionThreshold: Double?,
    ) : LayaAnswer
}

/** Whether the model actually saw everything SAMETVETA sent. */
data class LayaUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val stateTokens: Int,
    val stateTokensDropped: Int,
    val truncated: Boolean,
    val truncatedQuestions: List<QuestionId>,
) {
    init {
        require(stateTokensDropped >= 0) { "stateTokensDropped must not be negative" }
    }

    /**
     * True when a question's own window was cut, or the state lost tokens. Either means an answer
     * may rest on less evidence than SAMAVETA supplied — the UI must be able to say so.
     */
    val lostEvidence: Boolean get() = truncated || stateTokensDropped > 0 || truncatedQuestions.isNotEmpty()

    companion object {
        val UNKNOWN = LayaUsage(0, 0, 0, 0, false, emptyList())
    }
}

data class LayaLanguageDetection(
    val script: String,
    val language: String?,
    val isEnglish: Boolean,
    val languageUndecided: Boolean,
    val nonLatinFraction: Double,
    /** The segment that made a mostly-English state non-English, when LAYA names one. */
    val mixedSegment: String?,
)

data class LayaRouting(
    val model: String,
    val repo: String,
    val reason: String,
    val detection: LayaLanguageDetection?,
    val workflow: String?,
)

data class LayaPrediction(
    /** Constant name of the decision head; the checkpoint that answered is in [routing]. */
    val model: String,
    val answers: Map<QuestionId, LayaAnswer>,
    val usage: LayaUsage,
    val routing: LayaRouting?,
) {
    init {
        require(answers.isNotEmpty()) { "a prediction with no answers is not a prediction" }
    }

    /**
     * The answer for [id] when it is an answer SAMAVETA's policy may act on, else null.
     *
     * This deliberately refuses three cases rather than coercing them:
     *  - no answer for that question,
     *  - an answer the gate abstained on or could not evaluate,
     *  - a score answer, which is a level, not a decision.
     */
    fun actionableChoice(id: QuestionId): String? {
        val answer = answers[id] as? LayaAnswer.Choice ?: return null
        if (answer.abstention == Abstention.ABSTAINED || answer.abstention == Abstention.UNEVALUATED) return null
        return answer.choice
    }

    /**
     * P(true) for a two-way question, or null when the answer is missing/abstained/unevaluated.
     * SAMAVETA applies its **own** threshold to this; the probability is never treated as a verdict.
     */
    fun actionableProbability(id: QuestionId): Double? {
        val answer = answers[id] as? LayaAnswer.Noul ?: return null
        if (answer.abstention == Abstention.ABSTAINED || answer.abstention == Abstention.UNEVALUATED) return null
        return answer.probabilityTrue
    }
}

// ------------------------------------------------------------------ health / route / errors

data class LayaHealth(
    val status: String,
    /** Checkpoints resident in memory. Empty after an idle unload, or before the first request. */
    val loaded: List<String>,
    /** Artifact revision per resident checkpoint, so a deployment can confirm what it serves. */
    val revisions: Map<String, String>,
    /** Device a resident checkpoint really computes on — which may not be what was requested. */
    val device: String,
    /** True only while nothing is resident; then [device] is the configuration, not a measurement. */
    val deviceIsPreference: Boolean,
    val cpuFallbacks: Map<String, Int>,
) {
    val isAlive: Boolean get() = status == "ok"
}

data class LayaRouteSnapshot(val model: String, val repo: String?, val reason: String)

/** Error taxonomy, mapped from LAYA's HTTP surface rather than invented. */
sealed class LayaV1Failure(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** No endpoint configured. The shipped default. */
    class NotConfigured : LayaV1Failure("LAYA is not configured for this build.")

    /** A configured server could not be reached at all. */
    class Unreachable(detail: String, cause: Throwable? = null) :
        LayaV1Failure("LAYA could not be reached: $detail", cause)

    class Timeout(val timeoutMs: Long) : LayaV1Failure("LAYA did not answer within ${timeoutMs}ms.")

    /**
     * A non-2xx answer. LAYA returns `{"error": {"message", "code", "details"}}` or FastAPI's
     * `{"detail": ...}`; both are surfaced, never swallowed, because a 422 names the exact field
     * that was wrong.
     */
    class Rejected(
        val statusCode: Int,
        val code: String,
        val detail: String,
    ) : LayaV1Failure("LAYA rejected the request ($statusCode $code): $detail")

    /** 503 from the admission limiter: the server is saturated, not broken. */
    class Overloaded(val retryAfterMs: Long?) :
        LayaV1Failure("LAYA is at capacity; try again shortly.")

    /** A 2xx body that is not a valid prediction. Never partially trusted. */
    class Malformed(detail: String) : LayaV1Failure("LAYA returned a response SAMAVETA cannot trust: $detail")

    /** Unexpected 5xx. */
    class ServerError(val statusCode: Int, val detail: String) :
        LayaV1Failure("LAYA failed internally ($statusCode): $detail")
}

/** Marker used by the release guard: v1 integration is real, the old PROPOSED planner is test-only. */
const val LAYA_V1_CONTRACT_SOURCE =
    "github.com/NandhaKishorM/laya @ docs/http-api.md, sdk/typescript/src/types.ts"
