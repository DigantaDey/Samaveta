package com.samaveta.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.samaveta.android.core.laya.LayaDecisionValidator
import com.samaveta.android.core.laya.LayaPlan
import com.samaveta.android.core.laya.LayaPlanStep
import com.samaveta.android.core.laya.PlanVerdict
import com.samaveta.android.core.laya.StepKind
import com.samaveta.android.core.model.InstallationState
import com.samaveta.android.core.model.ModelRegistry

/**
 * These tests encode the product's hardest safety rule: an external decision engine may *propose*
 * work, but SAMAVETA's deterministic validator decides what may execute. A regression here is a
 * release blocker.
 */
class LayaDecisionValidatorTest {

    private val device = TestFixtures.referenceDevice()
    private val caps = TestFixtures.capabilitiesCpuOnly()

    private fun registry(vararg entries: com.samaveta.android.core.model.RegistryEntry) = ModelRegistry(entries.toList())

    private fun validator(
        registry: ModelRegistry,
        capabilities: Set<String> = setOf("notifications.read", "calendar.read"),
    ) = LayaDecisionValidator(registry, device, caps, capabilities)

    private fun plan(vararg steps: LayaPlanStep, requested: Set<String> = emptySet(), confirm: Boolean = false) =
        LayaPlan(
            requestId = "req-1",
            reasoningSummary = "test plan",
            steps = steps.toList(),
            requestedCapabilities = requested,
            requiresUserConfirmation = confirm,
        )

    private fun inference(index: Int, modelId: String) = LayaPlanStep(
        index = index,
        kind = StepKind.MODEL_INFERENCE,
        modelId = modelId,
        capability = null,
        instruction = "run $modelId",
        expectedOutputDescription = "text",
    )

    @Test
    fun acceptsAdmissiblePlan() {
        val reg = registry(TestFixtures.installedEntry(TestFixtures.smallTextModel()))
        val verdict = validator(reg).validate(plan(inference(0, "test-llama-1b")))
        assertTrue(verdict is PlanVerdict.Acceptable)
    }

    @Test
    fun rejectsModelThatIsNotInstalled() {
        val reg = registry(TestFixtures.notInstalledEntry(TestFixtures.smallTextModel()))
        val verdict = validator(reg).validate(plan(inference(0, "test-llama-1b")))
        assertTrue(verdict is PlanVerdict.Rejected)
        val issues = (verdict as PlanVerdict.Rejected).issues
        assertEquals(1, issues.size)
        assertEquals(com.samaveta.android.core.laya.IssueCode.MODEL_NOT_EXECUTABLE, issues[0].code)
    }

    @Test
    fun rejectsModelWhoseIntegrityWasNeverVerified() {
        val reg = registry(TestFixtures.unverifiedEntry(TestFixtures.smallTextModel()))
        val verdict = validator(reg).validate(plan(inference(0, "test-llama-1b")))
        assertTrue("unverified installs must never execute", verdict is PlanVerdict.Rejected)
    }

    @Test
    fun rejectsUnknownModelId() {
        val verdict = validator(ModelRegistry()).validate(plan(inference(0, "ghost-model")))
        assertTrue(verdict is PlanVerdict.Rejected)
    }

    @Test
    fun rejectsModelRequiringUnavailableNpu() {
        val reg = registry(TestFixtures.installedEntry(TestFixtures.npuOnlyModel()))
        val verdict = validator(reg).validate(plan(inference(0, "test-npu-vlm")))
        val issues = (verdict as PlanVerdict.Rejected).issues
        assertEquals(com.samaveta.android.core.laya.IssueCode.MODEL_NOT_EXECUTABLE, issues[0].code)
        assertTrue(issues[0].detail.contains("not verified"))
    }

    @Test
    fun rejectsUnauthorizedCapabilityInvocation() {
        val reg = registry(TestFixtures.installedEntry(TestFixtures.smallTextModel()))
        val step = LayaPlanStep(
            index = 0,
            kind = StepKind.CAPABILITY_INVOCATION,
            modelId = null,
            capability = "contacts.delete_all",
            instruction = "remove contacts",
            expectedOutputDescription = null,
        )
        val verdict = validator(reg).validate(plan(step))
        val issues = (verdict as PlanVerdict.Rejected).issues
        assertEquals(com.samaveta.android.core.laya.IssueCode.CAPABILITY_UNAVAILABLE, issues[0].code)
    }

    @Test
    fun rejectsPlanRequestingUnauthorizedCapabilityEvenIfStepsAreHarmless() {
        val reg = registry(TestFixtures.installedEntry(TestFixtures.smallTextModel()))
        val verdict = validator(reg).validate(
            plan(inference(0, "test-llama-1b"), requested = setOf("sms.send")),
        )
        assertTrue(verdict is PlanVerdict.Rejected)
    }

    @Test
    fun capabilityInvocationAlwaysNeedsConfirmation() {
        val reg = registry(TestFixtures.installedEntry(TestFixtures.smallTextModel()))
        val step = LayaPlanStep(
            index = 0,
            kind = StepKind.CAPABILITY_INVOCATION,
            modelId = null,
            capability = "calendar.read",
            instruction = "read calendar",
            expectedOutputDescription = null,
        )
        val verdict = validator(reg).validate(plan(step))
        assertTrue(verdict is PlanVerdict.Acceptable)
        assertTrue("state-changing capabilities need explicit user confirmation", (verdict as PlanVerdict.Acceptable).requiresUserConfirmation)
    }

    @Test
    fun sensitiveCapabilityForcesConfirmationEvenWithoutSideEffectingStep() {
        val reg = registry(TestFixtures.installedEntry(TestFixtures.smallTextModel()))
        val verdict = validator(reg, capabilities = setOf("messaging.send")).validate(
            plan(inference(0, "test-llama-1b"), requested = setOf("messaging.send")),
        )
        assertTrue(verdict is PlanVerdict.Acceptable)
        assertTrue((verdict as PlanVerdict.Acceptable).requiresUserConfirmation)
    }

    @Test
    fun readOnlyPlanDoesNotRequireConfirmation() {
        val reg = registry(TestFixtures.installedEntry(TestFixtures.smallTextModel()))
        val verdict = validator(reg).validate(plan(inference(0, "test-llama-1b")))
        assertTrue(verdict is PlanVerdict.Acceptable)
        assertFalse((verdict as PlanVerdict.Acceptable).requiresUserConfirmation)
        assertNull(verdict.confirmationReason)
    }

    @Test
    fun rejectsDuplicateStepIndices() {
        val reg = registry(TestFixtures.installedEntry(TestFixtures.smallTextModel()))
        val verdict = validator(reg).validate(
            plan(inference(0, "test-llama-1b"), inference(0, "test-llama-1b")),
        )
        assertTrue(verdict is PlanVerdict.Rejected)
        assertTrue((verdict as PlanVerdict.Rejected).issues.any { it.code == com.samaveta.android.core.laya.IssueCode.DUPLICATE_STEP_INDEX })
    }

    @Test
    fun rejectsOutOfOrderSteps() {
        val reg = registry(TestFixtures.installedEntry(TestFixtures.smallTextModel()))
        val verdict = validator(reg).validate(
            plan(inference(1, "test-llama-1b"), inference(0, "test-llama-1b")),
        )
        assertTrue((verdict as PlanVerdict.Rejected).issues.any { it.code == com.samaveta.android.core.laya.IssueCode.OUT_OF_ORDER_STEPS })
    }

    @Test
    fun rejectsOversizedPlans() {
        val reg = registry(TestFixtures.installedEntry(TestFixtures.smallTextModel()))
        val steps = (0..12).map { inference(it, "test-llama-1b") }
        val verdict = LayaDecisionValidator(reg, device, caps, emptySet(), maxSteps = 8)
            .validate(plan(*steps.toTypedArray()))
        assertTrue((verdict as PlanVerdict.Rejected).issues.any { it.code == com.samaveta.android.core.laya.IssueCode.TOO_MANY_STEPS })
    }

    @Test
    fun rejectionFeedbackIsValidJsonWithIssueCodes() {
        val reg = registry(TestFixtures.notInstalledEntry(TestFixtures.smallTextModel()))
        val rejected = validator(reg).validate(plan(inference(0, "test-llama-1b"))) as PlanVerdict.Rejected
        val feedback = rejected.toFeedbackJson()
        val parsed = com.samaveta.android.core.json.Json.parseObject(feedback)
        assertEquals("plan_rejected", parsed.string("type"))
        assertEquals("req-1", parsed.string("request_id"))
        val issue = parsed.array("issues")!!.objects().first()
        assertEquals("MODEL_NOT_EXECUTABLE", issue.string("code"))
    }

    @Test
    fun lowMemoryDeviceRejectsModelThatFitsOnlyOnReferenceHardware() {
        val big = TestFixtures.smallTextModel(id = "big-model", peakRamMb = 3_000)
        val reg = registry(TestFixtures.installedEntry(big))
        val lowMem = TestFixtures.lowMemoryDevice()
        val verdict = LayaDecisionValidator(reg, lowMem, caps, emptySet())
            .validate(plan(inference(0, "big-model")))
        assertTrue("a 3GB device must refuse a 3GB model", verdict is PlanVerdict.Rejected)
    }

    @Test
    fun installationStateIsRecheckedOnEveryValidation() {
        val reg = registry(TestFixtures.installedEntry(TestFixtures.smallTextModel()))
        val v = validator(reg)
        assertTrue(v.validate(plan(inference(0, "test-llama-1b"))) is PlanVerdict.Acceptable)
        // Model is uninstalled by the user between turns: the next identical plan must be refused.
        reg.updateInstallation("test-llama-1b", InstallationState.NotInstalled)
        assertTrue(v.validate(plan(inference(0, "test-llama-1b"))) is PlanVerdict.Rejected)
    }
}
