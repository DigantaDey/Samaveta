package com.samaveta.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.samaveta.android.core.laya.LayaDecisionValidator
import com.samaveta.android.core.laya.LayaPlan
import com.samaveta.android.core.laya.LayaPlanStep
import com.samaveta.android.core.laya.StepKind
import com.samaveta.android.core.model.ModelRegistry
import com.samaveta.android.core.orchestration.PlanGate
import com.samaveta.android.core.security.Capability

/**
 * The gate is the composition of the two validators, so these tests concentrate on the composition:
 * that neither check can be skipped, that the more fundamental problem is the one reported, and that
 * confirmation is not lost in the hand-off.
 */
class PlanGateTest {

    private fun gate(
        capabilities: Set<String> = setOf("notifications.read", "messaging.send"),
        grantable: Set<String> = setOf("messaging.send"),
        confirmations: Set<String> = emptySet(),
        maxSteps: Int = PlanValidatorMaxSteps,
    ) = PlanGate(
        decisionValidator = LayaDecisionValidator(
            registry = ModelRegistry(emptyList()),
            device = TestFixtures.referenceDevice(),
            capabilities = TestFixtures.capabilitiesCpuOnly(),
            capabilityInventory = capabilities,
            grantableCapabilities = grantable,
            confirmations = confirmations,
        ),
        shapeValidatorMaxSteps = maxSteps,
    )

    private val PlanValidatorMaxSteps = 24

    private fun plan(
        steps: List<LayaPlanStep>,
        declared: Set<String> = emptySet(),
        requiresConfirmation: Boolean = false,
    ) = LayaPlan(
        requestId = "req-1",
        reasoningSummary = null,
        steps = steps,
        requestedCapabilities = declared,
        requiresUserConfirmation = requiresConfirmation,
    )

    private fun step(
        index: Int = 0,
        kind: StepKind = StepKind.RESPOND,
        capability: String? = null,
        instruction: String = "Respond to the user",
    ) = LayaPlanStep(index, kind, null, capability, instruction, null)

    @Test
    fun aPlainPlanIsAccepted() {
        val result = gate().validate(plan(listOf(step())))
        assertTrue(result is PlanGate.Result.Accepted)
        assertFalse((result as PlanGate.Result.Accepted).requiresUserConfirmation)
    }

    @Test
    fun theDeviceValidatorRunsFirstAndItsReasonIsWhatTheUserSees() {
        // The plan both names a capability the device cannot perform and uses it without declaring
        // it. The device answer is the more useful one to show.
        val result = gate(capabilities = setOf("notifications.read"), grantable = emptySet())
            .validate(plan(listOf(step(0, StepKind.CAPABILITY_INVOCATION, "messaging.send")), declared = emptySet()))
        val rejected = result as PlanGate.Result.Rejected
        assertTrue(rejected.issues.single().contains("not available on this device"))
    }

    @Test
    fun anUndeclaredCapabilityIsRejectedEvenWhenTheDeviceSupportsIt() {
        val result = gate().validate(
            plan(listOf(step(0, StepKind.CAPABILITY_INVOCATION, "messaging.send")), declared = emptySet()),
        )
        val rejected = result as PlanGate.Result.Rejected
        assertTrue(rejected.issues.single().contains("did not declare"))
    }

    @Test
    fun aCapabilityThatDoesNotExistAnywhereIsRejected() {
        // The device validator catches it first (it is not in the inventory); either way it must be
        // refused rather than handed to an executor that cannot possibly satisfy it.
        val result = gate().validate(
            plan(listOf(step(0, StepKind.CAPABILITY_INVOCATION, "device.root")), declared = setOf("device.root")),
        )
        val rejected = result as PlanGate.Result.Rejected
        assertTrue(
            "rejection must name the offending capability, got: ${rejected.issues}",
            rejected.issues.any { it.contains("'device.root'") },
        )
    }

    @Test
    fun anUnknownDeclaredCapabilityIsRefusedEvenWhenNoStepUsesIt() {
        // Declared but never used. The device validator rejects the declaration; the shape check
        // would reject it too. What matters is that a plan cannot smuggle a capability in by
        // declaring it and hoping nobody checks.
        val result = gate().validate(plan(listOf(step()), declared = setOf("device.root")))
        val rejected = result as PlanGate.Result.Rejected
        assertTrue(
            "rejection must name the declared capability, got: ${rejected.issues}",
            rejected.issues.any { it.contains("'device.root'") },
        )
    }

    @Test
    fun theShapeCheckRejectsAnUnknownDeclarationTheDeviceValidatorWouldAlsoReject() {
        // Directly exercise the shape validator's declaration check, independent of the gate.
        val validation = com.samaveta.android.core.orchestration.PlanValidator.validate(
            plan(listOf(step()), declared = setOf("device.root")),
        )
        val rejected = validation as com.samaveta.android.core.orchestration.PlanValidation.Rejected
        assertTrue(rejected.reason.contains("unknown capabilities"))
    }

    @Test
    fun anAcceptedPlanCarriesItsResolvedCapabilities() {
        val result = gate().validate(
            plan(
                steps = listOf(
                    step(0, StepKind.MEMORY_READ, instruction = "Recall the last reply"),
                    step(1, StepKind.CAPABILITY_INVOCATION, "messaging.send", "Send the reply"),
                ),
                declared = setOf("messaging.send"),
            ),
        ) as PlanGate.Result.Accepted
        assertEquals(listOf(Capability.MESSAGING_SEND), result.capabilities)
    }

    @Test
    fun aSensitivePlanStillRequiresTheUsersApproval() {
        val result = gate(confirmations = emptySet()).validate(
            plan(
                steps = listOf(step(0, StepKind.CAPABILITY_INVOCATION, "messaging.send", "Send the reply")),
                declared = setOf("messaging.send"),
            ),
        ) as PlanGate.Result.Accepted
        assertTrue(result.requiresUserConfirmation)
        assertNotNull(result.confirmationReason)
    }

    @Test
    fun aConfirmedCapabilityStillRequiresConfirmationBecauseTheStepItselfMutatesState() {
        // Explicit confirmation of the *capability* does not waive per-action confirmation: the plan
        // still performs a state-changing step, so the gate still asks. Only the per-capability
        // prompt list is narrowed.
        val result = gate(confirmations = setOf("messaging.send")).validate(
            plan(
                steps = listOf(step(0, StepKind.CAPABILITY_INVOCATION, "messaging.send", "Send the reply")),
                declared = setOf("messaging.send"),
            ),
        ) as PlanGate.Result.Accepted
        assertTrue(result.requiresUserConfirmation)
    }

    @Test
    fun theRunnerCanTightenTheStepBudgetBelowItsOwnMaximum() {
        val strict = gate(maxSteps = 3)
        val plan = plan((0..3).map { step(it) })
        val result = strict.validate(plan) as PlanGate.Result.Rejected
        assertTrue(result.issues.single().contains("limit is 3"))
    }

    @Test
    fun aRunawayPlanIsRejectedByTheShapeCheck() {
        // The gate's own ceiling is bounded by PlanValidator.MAX_STEPS, so build the plan past it.
        val overLimit = com.samaveta.android.core.orchestration.PlanValidator.MAX_STEPS + 1
        val result = gate().validate(plan((0 until overLimit).map { step(it) }))
        val rejected = result as PlanGate.Result.Rejected
        assertTrue(rejected.issues.single().contains("limit is"))
    }

    @Test
    fun theGateRefusesAStepBudgetBeyondTheHardCeiling() {
        try {
            gate(maxSteps = com.samaveta.android.core.orchestration.PlanValidator.MAX_STEPS + 1)
            // Constructing the gate is fine; validating with it must fail loudly rather than allow
            // an unbounded plan.
            gate(maxSteps = 999).validate(plan(listOf(step())))
            throw AssertionError("expected an IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("maxSteps"))
        }
    }

    @Test
    fun anEmptyPlanIsRejectedByTheContractNotSilentlyAccepted() {
        // LayaPlan itself refuses an empty step list, so this is a construction-time guarantee.
        try {
            plan(emptyList())
            throw AssertionError("an empty plan must not be constructible")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("at least one step"))
        }
    }
}
