package com.samaveta.android.core.security

/**
 * The action pipeline: the single sanctioned path from "someone proposed an action" to "the user is
 * told what happened".
 *
 * The individual pieces already existed (capability authorization, single-use confirmations, the
 * audit log, the task state machine, action verification) but nothing forced them to be used
 * *together*. A screen could previously call an integration directly, skip confirmation, and then
 * render "Sent" because no exception was thrown. This type makes that impossible: the only way to
 * obtain an [ActionReport] is through [ActionPipeline], and a report can only say SUCCEEDED when an
 * integration returned [ActionEvidence.Confirmed].
 *
 * Ordering is deliberate and must not be reordered:
 *  1. authorization (deterministic, deny-by-default, ignores model output)
 *  2. confirmation (single-use token bound to this exact capability + target)
 *  3. execution (the integration performs the real-world effect)
 *  4. verification (evidence decides success — never the absence of an exception)
 *  5. audit (every outcome is recorded, including denials)
 */

/** What the caller wants to do. Proposed by the agent or LAYA; never trusted on its own. */
data class ActionRequest(
    val id: String,
    val capability: Capability,
    /** The concrete object the action applies to (recipient, path, URL, event id). */
    val target: String?,
    /** Short user-facing description, e.g. "Send the drafted reply to Asha". */
    val description: String,
    /** Confirmation token the UI obtained, when the policy demands one. */
    val confirmationToken: String? = null,
    val taskId: String? = null,
    val actor: Actor = Actor.AGENT,
) {
    init {
        require(id.isNotBlank()) { "action id required" }
        require(description.isNotBlank()) { "action description required" }
    }
}

/**
 * The integration that performs the effect. Implementations live in the Android layer (SmsManager,
 * MediaStore, NotificationListenerService, ...) and must return honest evidence: when they cannot
 * confirm the effect they return [ActionEvidence.Inconclusive], which the pipeline reports as
 * UNCONFIRMED rather than as success.
 */
fun interface ActionExecutor {
    fun execute(request: ActionRequest): ActionEvidence
}

/** Everything the UI needs to render an outcome, plus the audit trail entry it produced. */
data class PipelineOutcome(
    val request: ActionRequest,
    val report: ActionReport,
    val audit: AuditRecord,
    /** True when the executor ran. False means nothing outside SAMAVETA was touched. */
    val executed: Boolean,
)

/**
 * Orchestrates authorization, confirmation, execution, verification and audit for one action.
 *
 * Not thread-safe by design: each task owns its pipeline instance, and the Android layer serialises
 * per-task work. Sharing one instance across threads would allow two actions to interleave between
 * authorization and execution, which is exactly the race this component exists to prevent.
 */
class ActionPipeline(
    private val authorizer: CapabilityAuthorizer,
    private val confirmations: ConfirmationBroker,
    private val auditLog: AuditLog,
    private val idFactory: () -> String,
    private val clock: () -> Long,
) {

    /**
     * Runs [request] end to end.
     *
     * @param executor performs the effect; may be null for a dry run, in which case nothing is
     *   executed and the outcome is reported as unconfirmed with an explicit reason.
     */
    fun run(request: ActionRequest, executor: ActionExecutor?): PipelineOutcome {
        val now = clock()

        // 1. Authorization. A denial is final for this attempt: no executor is consulted and no
        //    confirmation is consumed, so a denial cannot be "retried past" by the caller.
        when (val decision = authorizer.authorize(request.capability, request.target)) {
            is AuthorizationDecision.Denied -> return deny(request, decision, now)
            is AuthorizationDecision.Authorized -> if (decision.requiresConfirmation) {
                // 2. Confirmation. Consumed *before* execution so a replay lands on a burnt token.
                val token = request.confirmationToken
                    ?: return deny(request, ConfirmationMissing, now, explicitReason = DenialReason.CONFIRMATION_REQUIRED)
                val confirmed = confirmations.consume(token, request.capability, request.target, now)
                if (confirmed is AuthorizationDecision.Denied) return deny(request, confirmed, now)
            }
        }

        // 3. Execution. The pipeline must not claim to have done anything it did not delegate.
        if (executor == null) {
            val report = ActionVerifier.resolved(
                capability = request.capability,
                action = request.description,
                evidence = ActionEvidence.Inconclusive("no executor is wired for ${request.capability.id}"),
                now = now,
            )
            return finish(request, report, executed = false, now = now)
        }

        val evidence = try {
            executor.execute(request)
        } catch (failure: Exception) {
            // A thrown exception means the effect did *not* demonstrably happen; but for a
            // mutating capability it also does not prove it did not happen (the failure may occur
            // after the intent left the process). Report honestly in both cases.
            if (request.capability.mutatesState) {
                ActionEvidence.Inconclusive("executor threw ${failure::class.simpleName}: ${failure.message.orEmpty()}")
            } else {
                ActionEvidence.NotPerformed("executor threw ${failure::class.simpleName}: ${failure.message.orEmpty()}")
            }
        }

        // 4. Verification. Success is decided solely by evidence, produced after the call returned.
        val report = ActionVerifier.resolved(request.capability, request.description, evidence, clock())
        return finish(request, report, executed = true, now = now)
    }

    /**
     * Registers a confirmation request for [request] and returns the token the UI must show the user
     * for. Split from [run] because the user answers asynchronously.
     */
    fun requestConfirmation(request: ActionRequest): ConfirmationRequest =
        confirmations.request(
            token = idFactory(),
            capability = request.capability,
            target = request.target,
            actionDescription = request.description,
            now = clock(),
        )

    /** The authorization decision alone, so a UI can explain a refusal before prompting. */
    fun decision(capability: Capability, target: String?): AuthorizationDecision =
        authorizer.authorize(capability, target)

    /** True when the user must approve [request] before it can run. */
    fun requiresConfirmation(capability: Capability, target: String?): Boolean =
        when (val decision = authorizer.authorize(capability, target)) {
            is AuthorizationDecision.Denied -> false
            is AuthorizationDecision.Authorized -> decision.requiresConfirmation
        }

    private fun deny(
        request: ActionRequest,
        decision: AuthorizationDecision.Denied,
        now: Long,
        explicitReason: DenialReason? = null,
    ): PipelineOutcome {
        val reason = explicitReason ?: decision.reason
        val report = ActionReport(
            capability = request.capability,
            action = request.description,
            status = ActionStatus.CANCELLED,
            message = reason.userMessage,
            evidence = null,
            atEpochMs = now,
        )
        return finish(request, report, executed = false, now = now, denialReason = reason, detail = decision.detail)
    }

    private fun finish(
        request: ActionRequest,
        report: ActionReport,
        executed: Boolean,
        now: Long,
        denialReason: DenialReason? = null,
        detail: String? = null,
    ): PipelineOutcome {
        val outcome = when {
            denialReason != null -> AuditOutcome.DENIED
            report.status == ActionStatus.SUCCEEDED -> AuditOutcome.VERIFIED
            report.status == ActionStatus.CANCELLED -> AuditOutcome.CANCELLED
            report.status == ActionStatus.FAILED -> AuditOutcome.FAILED
            else -> AuditOutcome.EXECUTED
        }
        val record = AuditRecord(
            id = idFactory(),
            atEpochMs = now,
            actor = request.actor,
            capability = request.capability,
            action = request.description,
            outcome = outcome,
            target = request.target,
            taskId = request.taskId,
            // Only the denial detail is ever propagated, and AuditRecord re-checks it for secrets.
            detail = if (denialReason != null) detail else null,
            denialReason = denialReason,
        )
        auditLog.record(record)
        return PipelineOutcome(request = request, report = report, audit = record, executed = executed)
    }

    private companion object {
        /**
         * A synthetic denial used when the policy demands confirmation and the caller supplied no
         * token. Expressing it as a denied decision keeps the audit path identical to a real denial.
         */
        val ConfirmationMissing = AuthorizationDecision.Denied(
            capability = null,
            reason = DenialReason.CONFIRMATION_REQUIRED,
        )
    }
}
