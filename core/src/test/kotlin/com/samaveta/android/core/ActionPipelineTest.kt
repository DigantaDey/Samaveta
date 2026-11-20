package com.samaveta.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.samaveta.android.core.security.ActionEvidence
import com.samaveta.android.core.security.ActionExecutor
import com.samaveta.android.core.security.ActionPipeline
import com.samaveta.android.core.security.ActionRequest
import com.samaveta.android.core.security.ActionStatus
import com.samaveta.android.core.security.Actor
import com.samaveta.android.core.security.AuditLog
import com.samaveta.android.core.security.AuditOutcome
import com.samaveta.android.core.security.AuditRecord
import com.samaveta.android.core.security.Capability
import com.samaveta.android.core.security.CapabilityAuthorizer
import com.samaveta.android.core.security.CapabilityPolicy
import com.samaveta.android.core.security.CapabilityRegistry
import com.samaveta.android.core.security.ConfirmationBroker
import com.samaveta.android.core.security.DenialReason
import com.samaveta.android.core.security.GrantState
import com.samaveta.android.core.security.PlatformSupport
import com.samaveta.android.core.security.Sensitivity

/**
 * The pipeline is where the product's hardest promise lives: never report an action as done unless
 * evidence says it happened. These tests attack it from the outside — a model that lies, a caller
 * that skips confirmation, an executor that throws after the effect left the process.
 */
class ActionPipelineTest {

    private var now = 1_000L
    private var idCounter = 0

    private val audit = AuditLog(capacity = 100)
    private val broker = ConfirmationBroker(ttlMs = 60_000)

    /**
     * Grants capabilities using the *shipped* default policy for each one, so the tests exercise the
     * real confirmation rules (LOW needs none, mutating ones always do) rather than a test-only rule.
     */
    private fun registry(vararg granted: Capability) = CapabilityRegistry(
        granted.map { CapabilityPolicy.defaultFor(it).copy(grant = GrantState.PERSISTENT) },
    )

    private fun pipeline(
        vararg granted: Capability,
        paused: Boolean = false,
        stopped: Boolean = false,
        support: (Capability) -> PlatformSupport = { PlatformSupport.SUPPORTED },
        confirmations: ConfirmationBroker = broker,
    ) = ActionPipeline(
        authorizer = CapabilityAuthorizer(
            registry = registry(*granted),
            automationPaused = { paused },
            emergencyStopped = { stopped },
            platformSupport = support,
        ),
        confirmations = confirmations,
        auditLog = audit,
        idFactory = { "id-${++idCounter}" },
        clock = { now },
    )

    private fun request(
        capability: Capability = Capability.MESSAGING_SEND,
        target: String? = "+919000000001",
        token: String? = null,
        description: String = "Send the drafted reply to Asha",
    ) = ActionRequest(
        id = "act-1",
        capability = capability,
        target = target,
        description = description,
        confirmationToken = token,
    )

    private fun confirmedToken(capability: Capability = Capability.MESSAGING_SEND, target: String? = "+919000000001"): String {
        broker.request("tok-ok", capability, target, "Approve", now)
        assertTrue(broker.grant("tok-ok", now))
        return "tok-ok"
    }

    @Test
    fun deniedCapabilityNeverReachesTheExecutor() {
        var executed = false
        val outcome = pipeline().run(request()) { executed = true; ActionEvidence.Confirmed("sent", now) }
        assertFalse(executed)
        assertFalse(outcome.executed)
        assertEquals(ActionStatus.CANCELLED, outcome.report.status)
        assertEquals(DenialReason.NOT_GRANTED, outcome.audit.denialReason)
        assertEquals(AuditOutcome.DENIED, outcome.audit.outcome)
    }

    @Test
    fun emergencyStopBeatsAGrantedCapability() {
        var executed = false
        val outcome = pipeline(Capability.MESSAGING_SEND, stopped = true).run(request(token = confirmedToken())) {
            executed = true
            ActionEvidence.Confirmed("sent", now)
        }
        assertFalse("emergency stop must win over a grant", executed)
        assertEquals(DenialReason.EMERGENCY_STOP, outcome.audit.denialReason)
    }

    @Test
    fun mutatingActionWithoutConfirmationIsRefusedBeforeExecution() {
        var executed = false
        val outcome = pipeline(Capability.MESSAGING_SEND).run(request(token = null)) {
            executed = true
            ActionEvidence.Confirmed("sent", now)
        }
        assertFalse(executed)
        assertEquals(DenialReason.CONFIRMATION_REQUIRED, outcome.audit.denialReason)
    }

    @Test
    fun forgedTokenIsRefused() {
        var executed = false
        val outcome = pipeline(Capability.MESSAGING_SEND).run(request(token = "tok-guessed")) {
            executed = true
            ActionEvidence.Confirmed("sent", now)
        }
        assertFalse(executed)
        assertEquals(DenialReason.CONFIRMATION_REQUIRED, outcome.audit.denialReason)
    }

    @Test
    fun confirmationCannotBeReplayedForASecondSend() {
        val p = pipeline(Capability.MESSAGING_SEND)
        val token = confirmedToken()
        val first = p.run(request(token = token)) { ActionEvidence.Confirmed("sent", now) }
        assertEquals(ActionStatus.SUCCEEDED, first.report.status)

        var secondExecuted = false
        val second = p.run(request(token = token)) { secondExecuted = true; ActionEvidence.Confirmed("sent", now) }
        assertFalse("a confirmation is single-use", secondExecuted)
        assertEquals(DenialReason.CONFIRMATION_REQUIRED, second.audit.denialReason)
    }

    @Test
    fun confirmationForOneTargetCannotBeUsedForAnother() {
        val token = confirmedToken(target = "+919000000001")
        var executed = false
        val outcome = pipeline(Capability.MESSAGING_SEND).run(request(target = "+919999999999", token = token)) {
            executed = true
            ActionEvidence.Confirmed("sent", now)
        }
        assertFalse(executed)
        assertEquals(DenialReason.CONFIRMATION_MISMATCH, outcome.audit.denialReason)
    }

    @Test
    fun expiredConfirmationIsRefusedEvenAfterItWasGranted() {
        // Regression: grant() removes the pending request, so expiry must be carried on the grant.
        val p = pipeline(Capability.MESSAGING_SEND, confirmations = ConfirmationBroker(ttlMs = 1_000))
        val shortBroker = ConfirmationBroker(ttlMs = 1_000)
        shortBroker.request("tok-short", Capability.MESSAGING_SEND, "+919000000001", "Approve", now)
        assertTrue(shortBroker.grant("tok-short", now))

        val late = pipeline(Capability.MESSAGING_SEND, confirmations = shortBroker)
        now += 5_000
        var executed = false
        val outcome = late.run(request(token = "tok-short")) {
            executed = true
            ActionEvidence.Confirmed("sent", now)
        }
        assertFalse("an expired confirmation must not authorize anything", executed)
        assertEquals(DenialReason.CONFIRMATION_EXPIRED, outcome.audit.denialReason)
        assertNotNull(p)
    }

    @Test
    fun successRequiresConfirmedEvidence() {
        val token = confirmedToken()
        val outcome = pipeline(Capability.MESSAGING_SEND)
            .run(request(token = token)) { ActionEvidence.Confirmed("SMS sent receipt at 10:31", now) }
        assertEquals(ActionStatus.SUCCEEDED, outcome.report.status)
        assertTrue(outcome.executed)
        assertEquals(AuditOutcome.VERIFIED, outcome.audit.outcome)
        assertTrue(outcome.report.message.contains("SMS sent receipt"))
    }

    @Test
    fun silenceFromTheIntegrationIsNeverReportedAsSuccess() {
        val token = confirmedToken()
        val outcome = pipeline(Capability.MESSAGING_SEND)
            .run(request(token = token)) { ActionEvidence.Inconclusive("no sent receipt observed") }
        assertEquals(ActionStatus.UNCONFIRMED, outcome.report.status)
        assertFalse(outcome.report.status.isSuccess)
        assertEquals(AuditOutcome.EXECUTED, outcome.audit.outcome)
        assertNull(outcome.audit.denialReason)
    }

    @Test
    fun thrownExceptionOnAMutatingActionIsUnconfirmedNotFailed() {
        // The intent may already have left the process when the exception surfaced; claiming a clean
        // failure would invite a double send.
        val token = confirmedToken()
        val outcome = pipeline(Capability.MESSAGING_SEND).run(request(token = token)) {
            throw IllegalStateException("SmsManager threw after handoff")
        }
        assertEquals(ActionStatus.UNCONFIRMED, outcome.report.status)
        assertTrue(outcome.report.message.contains("SmsManager"))
    }

    @Test
    fun thrownExceptionOnAReadOnlyActionIsAFailure() {
        val outcome = pipeline(Capability.NOTIFICATIONS_READ).run(
            request(capability = Capability.NOTIFICATIONS_READ, target = null, description = "Read notifications"),
        ) { throw IllegalStateException("listener unavailable") }
        assertEquals(ActionStatus.FAILED, outcome.report.status)
        assertEquals(AuditOutcome.FAILED, outcome.audit.outcome)
    }

    @Test
    fun missingExecutorIsReportedInsteadOfPretendingSuccess() {
        val outcome = pipeline(Capability.NOTIFICATIONS_READ).run(
            request(capability = Capability.NOTIFICATIONS_READ, target = null, description = "Read notifications"),
            executor = null,
        )
        assertFalse(outcome.executed)
        assertEquals(ActionStatus.UNCONFIRMED, outcome.report.status)
        assertTrue(outcome.report.message.contains("could not confirm"))
    }

    @Test
    fun everyOutcomeIsAuditedExactlyOnce() {
        val token = confirmedToken()
        val p = pipeline(Capability.MESSAGING_SEND)
        p.run(request(token = token)) { ActionEvidence.Confirmed("sent", now) }
        p.run(request(token = "tok-guessed")) { ActionEvidence.Confirmed("sent", now) }

        val records = audit.recent()
        assertEquals(2, records.size)
        assertEquals(AuditOutcome.VERIFIED, records[0].outcome)
        assertEquals(AuditOutcome.DENIED, records[1].outcome)
    }

    @Test
    fun auditRecordsCarryNoTargetFreeFormPayloadOnlyTheTargetId() {
        val token = confirmedToken()
        val outcome = pipeline(Capability.MESSAGING_SEND)
            .run(request(token = token)) { ActionEvidence.Confirmed("sent", now) }
        assertEquals("+919000000001", outcome.audit.target)
        assertNull("pipeline must not stuff message bodies into the audit detail", outcome.audit.detail)
    }

    @Test
    fun authorizationIsIndependentOfModelOutput() {
        // Same request, two "models": the pipeline never sees model confidence, and both outcomes
        // depend only on the deterministic engine.
        val denied = pipeline().run(request()) { ActionEvidence.Confirmed("sent", now) }
        val token = confirmedToken()
        val allowed = pipeline(Capability.MESSAGING_SEND).run(request(token = token)) {
            ActionEvidence.Confirmed("sent", now)
        }
        assertEquals(ActionStatus.CANCELLED, denied.report.status)
        assertEquals(ActionStatus.SUCCEEDED, allowed.report.status)
    }

    @Test
    fun pausedAutomationBlocksMutatingActionsButAllowsReading() {
        val p = pipeline(Capability.MESSAGING_SEND, Capability.NOTIFICATIONS_READ, paused = true)
        val send = p.run(request(token = null)) { ActionEvidence.Confirmed("sent", now) }
        assertEquals(DenialReason.AUTOMATION_PAUSED, send.audit.denialReason)

        val read = p.run(
            request(capability = Capability.NOTIFICATIONS_READ, target = null, description = "Read notifications"),
        ) { ActionEvidence.Confirmed("3 notifications", now) }
        assertEquals(ActionStatus.SUCCEEDED, read.report.status)
    }

    @Test
    fun unsupportedPlatformCapabilityIsDeniedWithItsDetail() {
        val outcome = pipeline(
            Capability.CALLS_PLACE,
            support = { PlatformSupport.Unsupported("no dialer intent handler on this device") },
        ).run(request(capability = Capability.CALLS_PLACE, target = "+919000000001", token = "whatever")) {
            ActionEvidence.Confirmed("called", now)
        }
        assertEquals(DenialReason.CAPABILITY_NOT_SUPPORTED, outcome.audit.denialReason)
    }

    @Test
    fun requiresConfirmationReflectsPolicyWithoutCarryingOutAnything() {
        val p = pipeline(Capability.NOTIFICATIONS_READ, Capability.MESSAGING_SEND)
        assertFalse(p.requiresConfirmation(Capability.NOTIFICATIONS_READ, null))
        assertTrue(p.requiresConfirmation(Capability.MESSAGING_SEND, "+919000000001"))
        assertTrue("asking about confirmation must not audit anything", audit.recent().isEmpty())
    }

    @Test
    fun requiresConfirmationIsFalseForACapabilityThatIsDeniedAnyway() {
        // A UI must not prompt for something that cannot run; the denial is the message to show.
        val p = pipeline()
        assertFalse(p.requiresConfirmation(Capability.MESSAGING_SEND, "+919000000001"))
        assertEquals(DenialReason.NOT_GRANTED, (p.decision(Capability.MESSAGING_SEND, "+919000000001") as com.samaveta.android.core.security.AuthorizationDecision.Denied).reason)
    }

    @Test
    fun requestedConfirmationCarriesTheExactActionAndExpires() {
        val p = pipeline(Capability.MESSAGING_SEND)
        val confirmation = p.requestConfirmation(request())
        assertEquals(Capability.MESSAGING_SEND, confirmation.capability)
        assertEquals("+919000000001", confirmation.target)
        assertTrue(confirmation.expiresAtEpochMs > confirmation.issuedAtEpochMs)
    }

    @Test
    fun criticalCapabilityIsNeverPersistentlyGranted() {
        // Even if the caller tries to persist it, the registry downgrades to session-only.
        val registry = CapabilityRegistry(
            listOf(CapabilityPolicy(Capability.PURCHASES_EXECUTE, GrantState.PERSISTENT, confirmEachUse = false)),
        )
        assertEquals(GrantState.SESSION, registry.grantsFor(Capability.PURCHASES_EXECUTE))
        assertTrue(registry.policyFor(Capability.PURCHASES_EXECUTE).confirmEachUse)
    }

    @Test
    fun readOnlyExecutorStillNeedsAGrant() {
        var executed = false
        val outcome = pipeline().run(
            request(capability = Capability.NOTIFICATIONS_READ, target = null, description = "Read notifications"),
        ) { executed = true; ActionEvidence.Confirmed("3", now) }
        assertFalse(executed)
        assertEquals(DenialReason.NOT_GRANTED, outcome.audit.denialReason)
    }

    @Test
    fun auditLogEntriesAreOrderedAndBounded() {
        val small = AuditLog(capacity = 2)
        repeat(5) { index ->
            small.record(
                AuditRecord(
                    id = "r$index",
                    atEpochMs = index.toLong(),
                    actor = Actor.SYSTEM,
                    capability = null,
                    action = "tick",
                    outcome = AuditOutcome.EXECUTED,
                    target = null,
                    taskId = null,
                    detail = null,
                ),
            )
        }
        assertEquals(2, small.size())
        assertEquals(listOf("r3", "r4"), small.recent().map { it.id })
    }

    @Test
    fun lowSensitivityGrantStillNeedsNoConfirmationButHighDoes() {
        // Sensitivity drives the default policy; the pipeline only consumes what it implies.
        val low = CapabilityRegistry().policyFor(Capability.BROWSER_NAVIGATE)
        assertEquals(Sensitivity.LOW, Capability.BROWSER_NAVIGATE.sensitivity)
        assertFalse(low.confirmEachUse)

        val high = CapabilityRegistry().policyFor(Capability.MESSAGING_SEND)
        assertTrue(high.confirmEachUse)
        assertEquals(GrantState.DENIED, high.grant)
    }

    @Test
    fun executorIsOnlyInvokedOncePerRun() {
        val token = confirmedToken()
        var calls = 0
        pipeline(Capability.MESSAGING_SEND).run(request(token = token)) {
            calls++
            ActionEvidence.Confirmed("sent", now)
        }
        assertEquals(1, calls)
    }

    @Test
    fun pipelineDoesNotTrustAnExecutorThatReportsSuccessForADeniedAction() {
        // Belt and braces: even a malicious executor cannot flip a denial into a success, because
        // the executor is never constructed.
        val constructed = java.util.concurrent.atomic.AtomicBoolean(false)
        val executor = ActionExecutor {
            constructed.set(true)
            ActionEvidence.Confirmed("sent", now)
        }
        val outcome = pipeline().run(request(), executor)
        assertFalse(constructed.get())
        assertFalse(outcome.report.status.isSuccess)
    }
}
