package com.samaveta.android.core.security

/**
 * Audit trail + action verification.
 *
 * Two product rules are enforced structurally here:
 *  1. Every capability invocation is recorded with its authorization outcome (auditability).
 *  2. An action is never reported as successful unless independent evidence of the effect was
 *     observed. "Called the API without an exception" is not evidence.
 */

enum class AuditOutcome { AUTHORIZED, DENIED, EXECUTED, VERIFIED, FAILED, CANCELLED }

data class AuditRecord(
    val id: String,
    val atEpochMs: Long,
    val actor: Actor,
    val capability: Capability?,
    val action: String,
    val outcome: AuditOutcome,
    val target: String?,
    val taskId: String?,
    /** Never contains message bodies, file contents, contact details or tokens. */
    val detail: String?,
    val denialReason: DenialReason? = null,
) {
    init {
        require(id.isNotBlank()) { "audit record id required" }
        require(action.isNotBlank()) { "audit action required" }
        // Redaction is enforced, not documented: refuse records that look like they embed content.
        require(detail == null || !looksLikeSensitiveContent(detail)) {
            "audit detail appears to contain sensitive content; redact before recording"
        }
    }

    private fun looksLikeSensitiveContent(value: String): Boolean =
        REDACTION_MARKERS.any { value.contains(it, ignoreCase = true) }

    companion object {
        private val REDACTION_MARKERS = listOf(
            "Bearer ",
            "password=",
            "otp=",
            "-----BEGIN",
            "api_key=",
        )
    }
}

enum class Actor { USER, AGENT, LAYA, SYSTEM, SCHEDULER }

/** Bounded, in-memory audit log. The Android layer persists this to Room. */
class AuditLog(private val capacity: Int = 2_000) {
    init {
        require(capacity > 0) { "audit capacity must be positive" }
    }

    private val records = ArrayDeque<AuditRecord>()

    fun record(record: AuditRecord) {
        records.addLast(record)
        while (records.size > capacity) records.removeFirst()
    }

    fun recent(limit: Int = 50): List<AuditRecord> = records.takeLast(limit.coerceAtLeast(0))

    fun forTask(taskId: String): List<AuditRecord> = records.filter { it.taskId == taskId }

    fun count(outcome: AuditOutcome): Int = records.count { it.outcome == outcome }

    fun size(): Int = records.size
}

/**
 * Evidence that an action actually took effect. Supplied by the integration that performed the
 * action (e.g. the SMS sent-receipt, the file-system re-stat, the notification listener callback).
 */
sealed interface ActionEvidence {
    /** Positive proof with a description of what was observed. */
    data class Confirmed(val description: String, val observedAtEpochMs: Long) : ActionEvidence

    /** The action was attempted but could not be confirmed either way. */
    data class Inconclusive(val reason: String) : ActionEvidence

    /** Positive proof the action did not take effect. */
    data class NotPerformed(val reason: String) : ActionEvidence
}

/**
 * Verification policy for one capability: what counts as evidence, and how long to wait for it.
 */
data class VerificationRule(
    val capability: Capability,
    val evidenceDescription: String,
    val timeoutMs: Long = 5_000,
) {
    init {
        require(evidenceDescription.isNotBlank()) { "verification rule must describe required evidence" }
        require(timeoutMs in 100..120_000) { "verification timeout out of range" }
    }
}

/**
 * Outcome reported to the user. [status] is the only thing the UI may render as success, and
 * [ActionStatus.SUCCEEDED] can only be produced by [ActionVerifier.resolved] with
 * [ActionEvidence.Confirmed].
 */
enum class ActionStatus {
    SUCCEEDED,
    FAILED,
    UNCONFIRMED,
    CANCELLED,
    ;

    val isSuccess: Boolean get() = this == SUCCEEDED
}

data class ActionReport(
    val capability: Capability?,
    val action: String,
    val status: ActionStatus,
    val message: String,
    val evidence: ActionEvidence?,
    val atEpochMs: Long,
) {
    init {
        if (status == ActionStatus.SUCCEEDED) {
            require(evidence is ActionEvidence.Confirmed) {
                "an action may only be reported SUCCEEDED with confirmed evidence"
            }
        }
    }
}

object ActionVerifier {

    /**
     * Maps evidence onto a user-facing report.
     *
     * A caller that observed nothing must pass [ActionEvidence.Inconclusive]; the resulting status
     * is [ActionStatus.UNCONFIRMED] and the UI must not show a success state.
     */
    fun resolved(
        capability: Capability?,
        action: String,
        evidence: ActionEvidence,
        now: Long,
    ): ActionReport = when (evidence) {
        is ActionEvidence.Confirmed -> ActionReport(
            capability = capability,
            action = action,
            status = ActionStatus.SUCCEEDED,
            message = "Done: ${evidence.description}.",
            evidence = evidence,
            atEpochMs = now,
        )

        is ActionEvidence.NotPerformed -> ActionReport(
            capability = capability,
            action = action,
            status = ActionStatus.FAILED,
            message = "Not completed: ${evidence.reason}.",
            evidence = evidence,
            atEpochMs = now,
        )

        is ActionEvidence.Inconclusive -> ActionReport(
            capability = capability,
            action = action,
            status = ActionStatus.UNCONFIRMED,
            message = "Attempted, but SAMAVETA could not confirm the result (${evidence.reason}). Check the app directly.",
            evidence = evidence,
            atEpochMs = now,
        )
    }

    /**
     * A timeout is *not* success and *not* a clean failure: the action may have happened. Reporting
     * it as unconfirmed is the only honest option.
     */
    fun timedOut(capability: Capability?, action: String, rule: VerificationRule, now: Long): ActionReport =
        resolved(
            capability,
            action,
            ActionEvidence.Inconclusive("no confirmation within ${rule.timeoutMs}ms; expected: ${rule.evidenceDescription}"),
            now,
        )
}
