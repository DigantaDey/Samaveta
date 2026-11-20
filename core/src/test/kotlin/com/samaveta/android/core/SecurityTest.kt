package com.samaveta.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.samaveta.android.core.security.ActionEvidence
import com.samaveta.android.core.security.ActionStatus
import com.samaveta.android.core.security.ActionVerifier
import com.samaveta.android.core.security.Actor
import com.samaveta.android.core.security.AuditLog
import com.samaveta.android.core.security.AuditOutcome
import com.samaveta.android.core.security.AuditRecord
import com.samaveta.android.core.security.AuthorizationDecision
import com.samaveta.android.core.security.Capability
import com.samaveta.android.core.security.CapabilityAuthorizer
import com.samaveta.android.core.security.CapabilityPolicy
import com.samaveta.android.core.security.CapabilityRegistry
import com.samaveta.android.core.security.ConfirmationBroker
import com.samaveta.android.core.security.DenialReason
import com.samaveta.android.core.security.GrantState
import com.samaveta.android.core.security.PlatformSupport
import com.samaveta.android.core.security.ScopeRestriction
import com.samaveta.android.core.security.VerificationRule

class SecurityTest {

    private fun registry(vararg policies: CapabilityPolicy) = CapabilityRegistry(policies.toList())

    private fun granted(capability: Capability, confirmEach: Boolean = false) =
        CapabilityPolicy(capability, GrantState.PERSISTENT, confirmEachUse = confirmEach)

    @Test
    fun deniesEverythingByDefault() {
        val auth = CapabilityAuthorizer(CapabilityRegistry())
        Capability.entries.forEach { capability ->
            val decision = auth.authorize(capability)
            assertTrue("$capability must be denied by default", decision is AuthorizationDecision.Denied)
        }
    }

    @Test
    fun criticalCapabilityCannotBeRememberedPersistently() {
        val reg = registry(CapabilityPolicy(Capability.PURCHASES_EXECUTE, GrantState.PERSISTENT, confirmEachUse = false))
        assertEquals(GrantState.SESSION, reg.grantsFor(Capability.PURCHASES_EXECUTE))
        assertTrue(reg.policyFor(Capability.PURCHASES_EXECUTE).confirmEachUse)
    }

    @Test
    fun emergencyStopBeatsAGrantedCapability() {
        val auth = CapabilityAuthorizer(
            registry(granted(Capability.NOTIFICATIONS_READ)),
            emergencyStopped = { true },
        )
        val decision = auth.authorize(Capability.NOTIFICATIONS_READ)
        assertEquals(
            DenialReason.EMERGENCY_STOP,
            (decision as AuthorizationDecision.Denied).reason,
        )
    }

    @Test
    fun pausingAutomationBlocksMutationsButKeepsReads() {
        val auth = CapabilityAuthorizer(
            registry(granted(Capability.MESSAGING_SEND), granted(Capability.NOTIFICATIONS_READ)),
            automationPaused = { true },
        )
        assertTrue(auth.authorize(Capability.MESSAGING_SEND) is AuthorizationDecision.Denied)
        assertTrue(auth.authorize(Capability.NOTIFICATIONS_READ) is AuthorizationDecision.Authorized)
    }

    @Test
    fun stateChangingCapabilityAlwaysRequiresConfirmation() {
        val auth = CapabilityAuthorizer(registry(granted(Capability.MESSAGING_SEND, confirmEach = false)))
        val decision = auth.authorize(Capability.MESSAGING_SEND) as AuthorizationDecision.Authorized
        assertTrue("sending must never be silent", decision.requiresConfirmation)
    }

    @Test
    fun platformPermissionGapIsDistinctFromUserDenial() {
        val auth = CapabilityAuthorizer(
            registry(granted(Capability.CAMERA_CAPTURE)),
            platformSupport = { PlatformSupport.PermissionNotGranted },
        )
        val decision = auth.authorize(Capability.CAMERA_CAPTURE) as AuthorizationDecision.Denied
        assertEquals(DenialReason.PLATFORM_PERMISSION_MISSING, decision.reason)
    }

    @Test
    fun unsupportedCapabilityCarriesReason() {
        val auth = CapabilityAuthorizer(
            registry(granted(Capability.CALLS_PLACE)),
            platformSupport = { PlatformSupport.Unsupported("Requires default-dialer role") },
        )
        val decision = auth.authorize(Capability.CALLS_PLACE) as AuthorizationDecision.Denied
        assertEquals(DenialReason.CAPABILITY_NOT_SUPPORTED, decision.reason)
        assertTrue(decision.detail!!.contains("dialer"))
    }

    @Test
    fun scopeRestrictionLimitsRecipients() {
        val auth = CapabilityAuthorizer(
            registry(granted(Capability.MESSAGING_SEND)),
            scopes = mapOf(
                Capability.MESSAGING_SEND to ScopeRestriction(
                    Capability.MESSAGING_SEND,
                    allowedTargets = setOf("+919000000001"),
                ),
            ),
        )
        assertTrue(auth.authorize(Capability.MESSAGING_SEND, "+919000000001") is AuthorizationDecision.Authorized)
        val blocked = auth.authorize(Capability.MESSAGING_SEND, "+919999999999") as AuthorizationDecision.Denied
        assertEquals(DenialReason.SCOPE_VIOLATION, blocked.reason)
        val missingTarget = auth.authorize(Capability.MESSAGING_SEND) as AuthorizationDecision.Denied
        assertEquals(DenialReason.SCOPE_VIOLATION, missingTarget.reason)
    }

    @Test
    fun confirmationIsSingleUse() {
        val broker = ConfirmationBroker(ttlMs = 10_000)
        broker.request("tok-1", Capability.MESSAGING_SEND, "+919000000001", "Send reply", now = 1_000)
        assertTrue(broker.grant("tok-1", now = 2_000))
        assertTrue(
            broker.consume("tok-1", Capability.MESSAGING_SEND, "+919000000001", now = 3_000)
                is AuthorizationDecision.Authorized,
        )
        val replay = broker.consume("tok-1", Capability.MESSAGING_SEND, "+919000000001", now = 3_500)
        assertEquals(DenialReason.CONFIRMATION_REQUIRED, (replay as AuthorizationDecision.Denied).reason)
    }

    @Test
    fun confirmationCannotBeRedirectedToAnotherTarget() {
        val broker = ConfirmationBroker()
        broker.request("tok-2", Capability.MESSAGING_SEND, "+919000000001", "Send reply", now = 0)
        broker.grant("tok-2", now = 1)
        val redirected = broker.consume("tok-2", Capability.MESSAGING_SEND, "+919999999999", now = 2)
        assertEquals(DenialReason.CONFIRMATION_MISMATCH, (redirected as AuthorizationDecision.Denied).reason)
    }

    @Test
    fun expiredConfirmationIsRejected() {
        val broker = ConfirmationBroker(ttlMs = 1_000)
        broker.request("tok-3", Capability.FILES_DELETE, "/docs/a.pdf", "Delete", now = 0)
        assertFalse("granting after expiry must fail", broker.grant("tok-3", now = 5_000))
    }

    @Test
    fun auditLogRecordsAndBoundsSize() {
        val log = AuditLog(capacity = 3)
        repeat(5) { i ->
            log.record(
                AuditRecord(
                    id = "r$i", atEpochMs = i.toLong(), actor = Actor.AGENT,
                    capability = Capability.NOTIFICATIONS_READ, action = "read",
                    outcome = AuditOutcome.EXECUTED, target = null, taskId = "t1", detail = null,
                ),
            )
        }
        assertEquals(3, log.size())
        assertEquals(listOf("r2", "r3", "r4"), log.recent(10).map { it.id })
        assertEquals(3, log.forTask("t1").size)
    }

    @Test
    fun auditRecordRefusesUnredactedSecrets() {
        try {
            AuditRecord(
                id = "r", atEpochMs = 0, actor = Actor.AGENT, capability = null,
                action = "call", outcome = AuditOutcome.FAILED, target = null, taskId = null,
                detail = "Authorization: Bearer abc123",
            )
            org.junit.Assert.fail("records containing credentials must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sensitive"))
        }
    }

    @Test
    fun actionCannotBeReportedSuccessfulWithoutConfirmedEvidence() {
        try {
            com.samaveta.android.core.security.ActionReport(
                capability = Capability.MESSAGING_SEND,
                action = "Send message",
                status = ActionStatus.SUCCEEDED,
                message = "Sent",
                evidence = ActionEvidence.Inconclusive("no receipt"),
                atEpochMs = 0,
            )
            org.junit.Assert.fail("SUCCEEDED without Confirmed evidence must be impossible")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("confirmed evidence"))
        }
    }

    @Test
    fun inconclusiveEvidenceYieldsUnconfirmedNotSuccess() {
        val report = ActionVerifier.resolved(
            Capability.MESSAGING_SEND, "Send message",
            ActionEvidence.Inconclusive("no delivery receipt"), now = 5,
        )
        assertEquals(ActionStatus.UNCONFIRMED, report.status)
        assertFalse(report.status.isSuccess)
    }

    @Test
    fun timeoutIsReportedAsUnconfirmed() {
        val rule = VerificationRule(Capability.MESSAGING_SEND, "SMS sent callback", timeoutMs = 3_000)
        val report = ActionVerifier.timedOut(Capability.MESSAGING_SEND, "Send message", rule, now = 10)
        assertEquals(ActionStatus.UNCONFIRMED, report.status)
        assertTrue(report.message.contains("could not confirm"))
    }

    @Test
    fun notPerformedYieldsFailure() {
        val report = ActionVerifier.resolved(
            Capability.FILES_DELETE, "Delete file",
            ActionEvidence.NotPerformed("file is read-only"), now = 1,
        )
        assertEquals(ActionStatus.FAILED, report.status)
    }

    @Test
    fun confirmedEvidenceYieldsSuccess() {
        val report = ActionVerifier.resolved(
            Capability.FILES_DELETE, "Delete file",
            ActionEvidence.Confirmed("file no longer exists at path", 100), now = 101,
        )
        assertEquals(ActionStatus.SUCCEEDED, report.status)
        assertTrue(report.message.startsWith("Done:"))
    }

    @Test
    fun sessionGrantsAreCleared() {
        val reg = registry(CapabilityPolicy(Capability.CAMERA_CAPTURE, GrantState.SESSION, confirmEachUse = false))
        assertTrue(reg.grantsFor(Capability.CAMERA_CAPTURE).isGranted)
        reg.clearSessionGrants()
        assertFalse(reg.grantsFor(Capability.CAMERA_CAPTURE).isGranted)
    }

    @Test
    fun revokeAllClearsEveryGrant() {
        val reg = registry(granted(Capability.NOTIFICATIONS_READ), granted(Capability.CALENDAR_READ))
        assertTrue(reg.grantedCapabilityIds().isNotEmpty())
        reg.revokeAll()
        assertTrue(reg.grantedCapabilityIds().isEmpty())
    }

    @Test
    fun capabilityIdsAreStableAndUnique() {
        assertEquals(Capability.entries.size, Capability.ids().size)
        assertEquals(Capability.MESSAGING_SEND, Capability.fromId("messaging.send"))
        assertTrue(Capability.fromId("messaging.send_all") == null)
    }
}
