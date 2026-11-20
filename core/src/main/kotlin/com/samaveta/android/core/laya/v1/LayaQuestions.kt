package com.samaveta.android.core.laya.v1

import com.samaveta.android.core.json.JsonValue
import com.samaveta.android.core.json.json
import com.samaveta.android.core.security.Capability

/**
 * The questions SAMAVETA asks LAYA, and the deterministic policy that turns the answers into one
 * proposed action.
 *
 * This is the reconciliation point between what LAYA actually is and what the product needs.
 *
 * LAYA does **not** emit plans. It answers typed questions about a state with calibrated
 * probabilities, and it abstains when it should not decide. So SAMAVETA:
 *
 *  1. asks a small, fixed set of questions — *is this a command or conversation?*, *which capability?*,
 *     *does it need the user's approval?*, and *what topic is it about?*;
 *  2. refuses to act on an abstained, unevaluated, truncated or low-confidence answer;
 *  3. maps the capability answer to a real [Capability] from the deterministic registry and runs the
 *     existing `ActionPipeline`, which is what actually authorises and verifies the action.
 *
 * Nothing here trusts a probability to *justify* an action: probabilities route between "ask the user",
 * "refuse", and "propose". The authority is still the capability registry, the confirmation broker and
 * the audit log.
 */
object LayaQuestions {

    const val INTENT: QuestionId = "intent"
    const val CAPABILITY: QuestionId = "capability"
    const val NEEDS_CONFIRMATION: QuestionId = "needs_confirmation"
    const val HOUSEHOLD_OR_PERSONAL: QuestionId = "sensitivity"

    /** Capability ids offered to LAYA. Exactly the registry's ids, so an answer always maps back. */
    val CAPABILITY_LABELS: List<String> = Capability.ids().sorted()

    /**
     * The four questions. Stable ids matter: the router records a `workflow` match by question ids,
     * and an answer cached or logged against `capability` must stay meaning the same thing.
     */
    fun core(locale: String): Map<QuestionId, LayaQuestion> = mapOf(
        INTENT to LayaQuestion.Choice(
            instructions = JsonValue.Str("Is the user asking SAMAVETA to do something on this device, or talking to it?"),
            criteria = listOf("command", "question", "conversation"),
            descriptions = mapOf(
                "command" to JsonValue.Str("An instruction to perform an action, e.g. send, open, set, find."),
                "question" to JsonValue.Str("A request for information that needs no device action."),
                "conversation" to JsonValue.Str("Greeting, acknowledgement, or chit-chat."),
            ),
        ),
        CAPABILITY to LayaQuestion.Choice(
            instructions = JsonValue.Str("Which single capability would carry out the user's instruction?"),
            criteria = CAPABILITY_LABELS,
        ),
        NEEDS_CONFIRMATION to LayaQuestion.Noul(
            instructions = JsonValue.Str("Would a careful assistant ask the user before doing this?"),
        ),
        HOUSEHOLD_OR_PERSONAL to LayaQuestion.Score(
            instructions = JsonValue.Str("How personal or consequential is this action?"),
            criteria = listOf(
                JsonValue.Str("trivial"),
                JsonValue.Str("personal"),
                JsonValue.Str("consequential"),
                JsonValue.Str("irreversible"),
            ),
        ),
    )

    /** The state SAMAVETA sends: the utterance plus whatever bounded context the user allowed. */
    fun state(
        utterance: String,
        locale: String,
        recentTurns: List<Pair<String, String>> = emptyList(),
        localeHint: Boolean = true,
    ): JsonValue = json {
        "utterance" to utterance
        "locale" to locale
        // Explicitly null-checked: an `infix to` with a nullable right-hand side silently builds a
        // discarded Pair instead of failing to compile, which once dropped this field entirely.
        if (localeHint) {
            val code = languageCodeOf(locale)
            if (code != null) "lang" to code
        }
        if (recentTurns.isNotEmpty()) {
            "recent_turns" to JsonValue.Arr(
                recentTurns.takeLast(MAX_TURNS).map { (role, text) ->
                    json {
                        "role" to role
                        "text" to text
                    }
                },
            )
        }
    }

    const val MAX_TURNS = 6

    /** `en-IN` → `en`. LAYA accepts a bare language code; an invalid one falls back to detection. */
    fun languageCodeOf(locale: String): String? =
        locale.substringBefore('-').takeIf { it.length in 2..3 && it.all { char -> char.isLetter() } }

    /**
     * SAMAVETA's own thresholds. These are policy, not model output, and they are deliberately
     * conservative: the cost of asking the user is a tap, the cost of acting wrongly is not.
     */
    data class Policy(
        /** Minimum probability for a capability answer to be proposed at all. */
        val capabilityFloor: Double = 0.55,
        /** Below this, SAMAVETA says it is unsure and asks rather than guesses. */
        val intentFloor: Double = 0.50,
        /** Above this, a noul "needs confirmation" answer is taken as yes. */
        val confirmationThreshold: Double = 0.40,
        /** Score at or above this is treated as consequential enough to always confirm. */
        val consequentialScore: Double = 2.0,
        /** Refuse to act when LAYA reports the state was truncated. */
        val refuseOnTruncation: Boolean = true,
    ) {
        init {
            require(capabilityFloor in 0.0..1.0) { "capabilityFloor must be within 0..1" }
            require(intentFloor in 0.0..1.0) { "intentFloor must be within 0..1" }
            require(confirmationThreshold in 0.0..1.0) { "confirmationThreshold must be within 0..1" }
        }
    }

    /**
     * The outcome of reading a prediction. Each case is something the UI can render honestly, and
     * only [Proposal] may reach an executor.
     */
    sealed interface Reading {
        /** Act on this, subject to the capability registry and confirmation broker. */
        data class Proposal(
            val capability: Capability,
            val capabilityProbability: Double,
            /** SAMAVETA's own judgement, independent of the model's noul answer. */
            val requiresConfirmation: Boolean,
            val rationale: String,
        ) : Reading

        /** LAYA or SAMAVETA declined to decide. [reason] is user-facing. */
        data class Unsure(val reason: String) : Reading

        /** Not an instruction to act. */
        data class NotACommand(val kind: String) : Reading

        /** The answer could not be used safely. */
        data class Unusable(val reason: String) : Reading
    }

    /**
     * Reads a prediction into a [Reading].
     *
     * Order matters, and it is the safety order: unusable evidence first (truncation, missing
     * answers), then abstention, then intent, then capability. A truncated state is refused before
     * anything else because every subsequent question was answered on partial evidence.
     */
    fun read(prediction: LayaPrediction, policy: Policy = Policy()): Reading {
        if (policy.refuseOnTruncation && prediction.usage.lostEvidence) {
            return Reading.Unusable(
                "LAYA did not receive the whole request " +
                    "(${prediction.usage.stateTokensDropped} token(s) dropped), so its answer cannot be trusted.",
            )
        }

        val anyAbstained = prediction.answers.values.any {
            it.abstention == Abstention.ABSTAINED || it.abstention == Abstention.UNEVALUATED
        }
        if (anyAbstained) {
            return Reading.Unsure("LAYA declined to decide this one. Tell me a little more, or do it yourself.")
        }

        val intent = prediction.answers[INTENT] as? LayaAnswer.Choice
            ?: return Reading.Unusable("LAYA returned no usable intent answer.")

        val intentProbability = intent.probabilities[intent.choice] ?: intent.answerConfidence ?: 0.0
        if (intentProbability < policy.intentFloor) {
            return Reading.Unsure("I am not sure what you are asking for.")
        }
        when (intent.choice) {
            "conversation" -> return Reading.NotACommand("conversation")
            "question" -> return Reading.NotACommand("question")
        }

        val capabilityAnswer = prediction.answers[CAPABILITY] as? LayaAnswer.Choice
            ?: return Reading.Unusable("LAYA did not answer which capability to use.")
        val probability = capabilityAnswer.probabilities[capabilityAnswer.choice] ?: 0.0
        if (probability < policy.capabilityFloor) {
            return Reading.Unsure(
                "I can tell this is a request, but not which action would satisfy it.",
            )
        }
        val capability = Capability.fromId(capabilityAnswer.choice)
            ?: return Reading.Unusable(
                "LAYA proposed '${capabilityAnswer.choice}', which is not a capability SAMAVETA has.",
            )

        // Confirmation is decided by SAMAVETA from two inputs: its own reading of the noul answer and
        // the consequence score. A model saying "no confirmation needed" never removes it, because
        // `CapabilityAuthorizer` forces confirmation for state-changing capabilities regardless.
        val noulYes = prediction.actionableProbability(NEEDS_CONFIRMATION) ?: 0.0
        val consequence = (prediction.answers[HOUSEHOLD_OR_PERSONAL] as? LayaAnswer.Score)?.score ?: 0.0
        val requiresConfirmation = capability.mutatesState ||
            noulYes >= policy.confirmationThreshold ||
            consequence >= policy.consequentialScore

        val rationale = buildString {
            append("LAYA read this as a '${intent.choice}' (p=${format(intentProbability)}). ")
            append("Capability '${capability.displayName}' at p=${format(probability)}")
            if (capabilityAnswer.abstentionThreshold != null) {
                append(", gated at ${format(capabilityAnswer.abstentionThreshold)}")
            }
            append(".")
            if (prediction.routing != null) {
                append(" Answered by '${prediction.routing.model}' (${prediction.routing.reason}).")
            }
        }

        return Reading.Proposal(
            capability = capability,
            capabilityProbability = probability,
            requiresConfirmation = requiresConfirmation,
            rationale = rationale,
        )
    }

    private fun format(value: Double): String {
        val rounded = kotlin.math.round(value * 100) / 100.0
        return rounded.toString()
    }
}
