package com.samaveta.android.core.laya.v1

import com.samaveta.android.core.security.Capability

/**
 * One user turn, from an utterance to something the UI can render without lying.
 *
 * This is the seam the app's `AgentViewModel` calls. It lives in `core` — Android-free — for a
 * specific reason: it is the part of the agent with behaviour worth executing, and the only way to
 * execute it here is to keep it off the Android framework. `LayaAgentTest` drives it against a real
 * loopback HTTP server, so the whole path (encode → socket → decode → policy → user-facing sentence)
 * is actually run.
 *
 * What this class is **not**: it does not execute anything. A [Turn.Proposed] is a *proposal*. The
 * authority to act is `CapabilityAuthorizer` + `ConfirmationBroker` + `ActionPipeline`, and there is
 * no capability executor in this build, so the turn says so instead of implying that something
 * happened. That gap is deliberate and is recorded in `KNOWN_LIMITATIONS.md`.
 */
class LayaAgent(
    private val client: LayaV1Client,
    private val policy: LayaQuestions.Policy = LayaQuestions.Policy(),
    /**
     * Whether this build has any capability executor at all. False today. Threading it through
     * rather than hard-coding keeps the sentence honest if that ever changes.
     */
    private val canExecute: Boolean = false,
) {

    /** The outcome of one turn. Every case has a sentence the UI can show verbatim. */
    sealed interface Turn {
        /** A capability was proposed. Nothing has been run. */
        data class Proposed(
            val capability: Capability,
            val requiresConfirmation: Boolean,
            val rationale: String,
            val canExecute: Boolean,
        ) : Turn

        /** LAYA declined, or the evidence was insufficient. Asking the user is not a failure. */
        data class Clarify(val reason: String) : Turn

        /** The utterance was not an instruction. */
        data class Conversation(val kind: String) : Turn

        /** The answer existed but could not be trusted. */
        data class Refused(val reason: String) : Turn

        /** The engine itself could not be used. [detail] is the engine's own words where it had any. */
        data class EngineUnavailable(val reason: String, val detail: String?) : Turn

        /** The sentence shown to the user. Written once, here, so no screen paraphrases it. */
        val userMessage: String
            get() = when (this) {
                is Proposed -> buildString {
                    append("I read this as '${capability.displayName}'")
                    if (requiresConfirmation) {
                        append(", which needs your approval before it runs.")
                    } else {
                        append(".")
                    }
                    if (!canExecute) {
                        append(" Nothing was executed: this build has no executor for that capability yet.")
                    }
                }
                is Clarify -> reason
                is Conversation -> when (kind) {
                    "question" -> "That is a question rather than an instruction — nothing to execute."
                    else -> "Happy to chat. Nothing was executed."
                }
                is Refused -> reason
                is EngineUnavailable -> {
                    val tail = detail?.takeIf { it.isNotBlank() }?.let { " (${it.take(200)})" }.orEmpty()
                    "I can't answer that right now: $reason$tail"
                }
            }
    }

    /**
     * Runs one turn.
     *
     * Never throws. A decision engine that is down is a sentence the user reads, not a crash — and
     * the sentence distinguishes "I did not ask", "I asked and it was refused", and "it was too
     * confident about a state it could not see".
     */
    fun handle(utterance: String, locale: String): Turn {
        val trimmed = utterance.trim()
        if (trimmed.isEmpty()) return Turn.Clarify("I didn't catch an instruction there.")

        val request = try {
            LayaPredictRequest(
                state = LayaQuestions.state(trimmed, locale),
                questions = LayaQuestions.core(locale),
                // A gate on the server side means the answer carries its own `abstention` field. SAMAVETA
                // asks for it so that "the model was not sure" is visible rather than inferred from a
                // score, and still applies its own floors afterwards: the server's gate is not authority.
                minConfidence = MinConfidence.Single(policy.intentFloor),
            )
        } catch (invalid: IllegalArgumentException) {
            // e.g. an utterance that cannot be represented. Refuse rather than send something odd.
            return Turn.Refused("That instruction could not be encoded for the decision engine: ${invalid.message}")
        }

        val prediction = try {
            client.predict(request)
        } catch (failure: LayaV1Failure) {
            return failure.toTurn()
        } catch (unexpected: Exception) {
            return Turn.EngineUnavailable("the decision engine call failed unexpectedly", unexpected.message)
        }

        return when (val reading = LayaQuestions.read(prediction, policy)) {
            is LayaQuestions.Reading.Proposal -> Turn.Proposed(
                capability = reading.capability,
                requiresConfirmation = reading.requiresConfirmation,
                rationale = reading.rationale,
                canExecute = canExecute,
            )
            is LayaQuestions.Reading.Unsure -> Turn.Clarify(reading.reason)
            is LayaQuestions.Reading.NotACommand -> Turn.Conversation(reading.kind)
            is LayaQuestions.Reading.Unusable -> Turn.Refused(reading.reason)
        }
    }

    /**
     * Maps the failure taxonomy to user-facing text.
     *
     * The distinctions matter: "not configured" is the user's to fix in Settings, "unreachable" is a
     * network or server problem, "rejected" means SAMAVETA sent something the server refused (a bug
     * on our side, worth surfacing verbatim), and "overloaded" means try again shortly.
     */
    private fun LayaV1Failure.toTurn(): Turn = when (this) {
        is LayaV1Failure.NotConfigured -> Turn.EngineUnavailable(
            "the decision engine is not configured. Set its address in Settings.",
            null,
        )
        is LayaV1Failure.Unreachable -> Turn.EngineUnavailable("the decision engine could not be reached", message)
        is LayaV1Failure.Timeout -> Turn.EngineUnavailable(
            "the decision engine did not answer in time. It may still have been working, so nothing was retried.",
            message,
        )
        is LayaV1Failure.Overloaded -> Turn.EngineUnavailable("the decision engine is at capacity; try again shortly", null)
        is LayaV1Failure.Rejected -> Turn.EngineUnavailable("the decision engine rejected the request", detail)
        is LayaV1Failure.Malformed -> Turn.Refused("The engine's answer could not be trusted, so nothing was acted on: $message")
        is LayaV1Failure.ServerError -> Turn.EngineUnavailable("the decision engine failed internally", detail)
    }

    /** The connection state to show in the header. Straight from the client, never optimistic. */
    fun state(): LayaV1State = client.state
}
