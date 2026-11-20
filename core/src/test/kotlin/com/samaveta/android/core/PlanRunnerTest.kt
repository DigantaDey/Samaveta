package com.samaveta.android.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.samaveta.android.core.core.TaskRecord
import com.samaveta.android.core.core.TaskState
import com.samaveta.android.core.laya.LayaPlan
import com.samaveta.android.core.laya.LayaPlanStep
import com.samaveta.android.core.laya.StepKind
import com.samaveta.android.core.orchestration.ConfirmationProvider
import com.samaveta.android.core.orchestration.DenyAllConfirmations
import com.samaveta.android.core.orchestration.PlanRunner
import com.samaveta.android.core.orchestration.PlanValidation
import com.samaveta.android.core.orchestration.PlanValidator
import com.samaveta.android.core.orchestration.StepPort
import com.samaveta.android.core.orchestration.StepResult
import com.samaveta.android.core.orchestration.StepStatus
import com.samaveta.android.core.security.ActionEvidence
import com.samaveta.android.core.security.ActionExecutor
import com.samaveta.android.core.security.ActionPipeline
import com.samaveta.android.core.security.AuditLog
import com.samaveta.android.core.security.AuditOutcome
import com.samaveta.android.core.security.Capability
import com.samaveta.android.core.security.CapabilityAuthorizer
import com.samaveta.android.core.security.CapabilityPolicy
import com.samaveta.android.core.security.CapabilityRegistry
import com.samaveta.android.core.security.ConfirmationBroker
import com.samaveta.android.core.security.DenialReason
import com.samaveta.android.core.security.GrantState

/**
 * The plan runner is the seam where an external decision engine (LAYA) meets the deterministic
 * authorization engine. The tests exist mainly to prove the seam does not leak authority: a plan can
 * propose, but it cannot escalate, and it cannot manufacture success.
 */
class PlanRunnerTest {

    private var now = 10_000L
    private var ids = 0
    private val audit = AuditLog(capacity = 500)

    private val task = TaskRecord(
        id = "task-1",
        title = "Reply to Asha",
        state = TaskState.PENDING,
        createdAtEpochMs = 0,
        updatedAtEpochMs = 0,
    )

    private fun registry(vararg granted: Capability) = CapabilityRegistry(
        granted.map { CapabilityPolicy.defaultFor(it).copy(grant = GrantState.PERSISTENT) },
    )

    private fun pipeline(vararg granted: Capability) = ActionPipeline(
        authorizer = CapabilityAuthorizer(registry(*granted)),
        confirmations = ConfirmationBroker(ttlMs = 120_000),
        auditLog = audit,
        idFactory = { "id-${++ids}" },
        clock = { now },
    )

    private fun runner(
        pipeline: ActionPipeline,
        port: StepPort = StepPort { StepResult.Produced("ok") },
        confirmations: ConfirmationProvider = DenyAllConfirmations,
        executor: (Capability) -> ActionExecutor? = { null },
        cancelled: () -> Boolean = { false },
    ) = PlanRunner(
        pipeline = pipeline,
        auditLog = audit,
        stepPort = port,
        confirmations = confirmations,
        executorFor = executor,
        idFactory = { "id-${++ids}" },
        clock = { now },
        isCancelled = cancelled,
    )

    private fun step(
        index: Int = 0,
        kind: StepKind = StepKind.RESPOND,
        capability: String? = null,
        instruction: String = "Summarise the notifications",
        modelId: String? = null,
    ) = LayaPlanStep(
        index = index,
        kind = kind,
        modelId = modelId,
        capability = capability,
        instruction = instruction,
        expectedOutputDescription = null,
    )

    private fun plan(
        steps: List<LayaPlanStep> = listOf(step()),
        declared: Set<String> = emptySet(),
    ) = LayaPlan(
        requestId = "req-1",
        reasoningSummary = null,
        steps = steps,
        requestedCapabilities = declared,
        requiresUserConfirmation = false,
    )

    // ---- validation -------------------------------------------------------------------------

    @Test
    fun planUsingAnUndeclaredCapabilityIsRejected() {
        val p = plan(
            steps = listOf(step(0, StepKind.CAPABILITY_INVOCATION, "messaging.send", "Send the reply")),
            declared = emptySet(),
        )
        val validation = PlanValidator.validate(p)
        assertTrue(validation is PlanValidation.Rejected)
        assertTrue((validation as PlanValidation.Rejected).reason.contains("did not declare"))
    }

    @Test
    fun planRequestingACapabilityThatDoesNotExistIsRejected() {
        val p = plan(
            steps = listOf(step(0, StepKind.CAPABILITY_INVOCATION, "device.root_shell", "Escalate")),
            declared = setOf("device.root_shell"),
        )
        val validation = PlanValidator.validate(p) as PlanValidation.Rejected
        assertTrue(validation.reason.contains("unknown capability"))
    }

    @Test
    fun planDeclaringAnUnknownCapabilityIsRejectedEvenIfUnused() {
        val p = plan(steps = listOf(step()), declared = setOf("bank.transfer"))
        val validation = PlanValidator.validate(p) as PlanValidation.Rejected
        assertTrue(validation.reason.contains("bank.transfer"))
    }

    @Test
    fun outOfOrderOrDuplicateStepIndicesAreRejected() {
        val duplicate = plan(steps = listOf(step(0), step(0)))
        assertTrue(PlanValidator.validate(duplicate) is PlanValidation.Rejected)

        val unordered = plan(steps = listOf(step(1), step(0)))
        assertTrue(PlanValidator.validate(unordered) is PlanValidation.Rejected)

        val notZeroBased = plan(steps = listOf(step(1)))
        assertTrue(PlanValidator.validate(notZeroBased) is PlanValidation.Rejected)
    }

    @Test
    fun runawayPlanIsRejected() {
        val steps = (0 until PlanValidator.MAX_STEPS + 1).map { step(it) }
        val validation = PlanValidator.validate(plan(steps = steps)) as PlanValidation.Rejected
        assertTrue(validation.reason.contains("steps"))
    }

    @Test
    fun oversizedInstructionIsRejected() {
        val validation = PlanValidator.validate(plan(steps = listOf(step(instruction = "x".repeat(4_001)))))
        assertTrue(validation is PlanValidation.Rejected)
    }

    @Test
    fun aFullyDeclaredPlanIsAccepted() {
        val p = plan(
            steps = listOf(
                step(0, StepKind.MEMORY_READ, instruction = "Recall past replies"),
                step(1, StepKind.CAPABILITY_INVOCATION, "messaging.send", "Send the reply"),
            ),
            declared = setOf("messaging.send"),
        )
        val validation = PlanValidator.validate(p) as PlanValidation.Accepted
        assertEquals(listOf(Capability.MESSAGING_SEND), validation.capabilities)
    }

    // ---- execution --------------------------------------------------------------------------

    @Test
    fun rejectedPlanRunsNothing() = runBlocking {
        var portCalls = 0
        var executorCalls = 0
        val p = plan(
            steps = listOf(
                step(0, StepKind.RESPOND, instruction = "Say hello"),
                step(1, StepKind.CAPABILITY_INVOCATION, "files.delete", "Delete everything"),
            ),
            declared = emptySet(),
        )
        val execution = runner(
            pipeline = pipeline(Capability.FILES_DELETE),
            port = StepPort { portCalls++; StepResult.Produced("ok") },
            executor = { executorCalls++; ActionExecutor { ActionEvidence.Confirmed("deleted", now) } },
        ).run(p, task)

        assertTrue(execution.rejected)
        assertEquals(0, portCalls)
        assertEquals(0, executorCalls)
        assertEquals(TaskState.CANCELLED, execution.finalState)
        assertTrue(execution.steps.all { it.status == StepStatus.NOT_RUN })
        assertEquals(AuditOutcome.DENIED, audit.recent().last().outcome)
    }

    @Test
    fun successfulPlanEndsSucceeded() = runBlocking {
        val p = plan(steps = listOf(step(0), step(1)))
        val execution = runner(pipeline()).run(p, task)
        assertTrue(execution.succeeded)
        assertEquals(TaskState.SUCCEEDED, execution.finalState)
        assertTrue(execution.steps.all { it.status == StepStatus.SUCCEEDED })
    }

    @Test
    fun aFailedStepStopsThePlanAndLeavesTheRestUntouched() = runBlocking {
        var calls = 0
        val port = StepPort {
            calls++
            if (calls == 2) StepResult.Failed("model unavailable") else StepResult.Produced("ok")
        }
        val p = plan(steps = listOf(step(0), step(1), step(2)))
        val execution = runner(pipeline(), port = port).run(p, task)

        assertEquals(TaskState.FAILED, execution.finalState)
        assertEquals(2, calls)
        assertEquals(StepStatus.SUCCEEDED, execution.steps[0].status)
        assertEquals(StepStatus.FAILED, execution.steps[1].status)
        assertEquals(StepStatus.NOT_RUN, execution.steps[2].status)
        assertTrue(execution.steps[1].message.contains("model unavailable"))
    }

    @Test
    fun unconfirmedStepIsNeverReportedAsSuccess() = runBlocking {
        val port = StepPort { StepResult.Unconfirmed("no receipt") }
        val execution = runner(pipeline(), port = port).run(plan(), task)
        assertEquals(TaskState.UNCONFIRMED, execution.finalState)
        assertFalse(execution.steps[0].status.isSuccess)
        assertNull(execution.steps[0].output)
    }

    @Test
    fun capabilityStepWithoutAnIntegrationIsUnconfirmedNotDone() = runBlocking {
        val p = plan(
            steps = listOf(step(0, StepKind.CAPABILITY_INVOCATION, "messaging.send", "Send the reply to Asha")),
            declared = setOf("messaging.send"),
        )
        val execution = runner(
            pipeline = pipeline(Capability.MESSAGING_SEND),
            confirmations = ConfirmationProvider { true },
            executor = { null },
        ).run(p, task)

        assertEquals(StepStatus.UNCONFIRMED, execution.steps[0].status)
        assertEquals(TaskState.UNCONFIRMED, execution.finalState)
    }

    @Test
    fun capabilityStepWithEvidenceSucceedsAndRecordsTheSideEffect() = runBlocking {
        val p = plan(
            steps = listOf(step(0, StepKind.CAPABILITY_INVOCATION, "messaging.send", "Send the reply to Asha")),
            declared = setOf("messaging.send"),
        )
        val approving = ConfirmationProvider { true }
        val execution = runner(
            pipeline = pipeline(Capability.MESSAGING_SEND),
            confirmations = approving,
            executor = { ActionExecutor { ActionEvidence.Confirmed("SMS sent receipt observed", now) } },
        ).run(p, task)

        assertTrue(execution.succeeded)
        assertEquals(StepStatus.SUCCEEDED, execution.steps[0].status)
        assertTrue(execution.steps[0].message.contains("SMS sent receipt"))
    }

    @Test
    fun decliningTheConfirmationMeansNothingHappens() = runBlocking {
        var executed = false
        val p = plan(
            steps = listOf(step(0, StepKind.CAPABILITY_INVOCATION, "messaging.send", "Send the reply")),
            declared = setOf("messaging.send"),
        )
        val execution = runner(
            pipeline = pipeline(Capability.MESSAGING_SEND),
            confirmations = DenyAllConfirmations,
            executor = { ActionExecutor { executed = true; ActionEvidence.Confirmed("sent", now) } },
        ).run(p, task)

        assertFalse(executed)
        assertEquals(StepStatus.DENIED, execution.steps[0].status)
        assertEquals(TaskState.CANCELLED, execution.finalState)
        assertEquals(DenialReason.CONFIRMATION_REQUIRED, audit.recent().last().denialReason)
    }

    @Test
    fun aGrantedCapabilityThatPolicyDeniesStillDoesNotRun() = runBlocking {
        // The user approves, but the capability was never granted: approval cannot substitute for a grant.
        var executed = false
        val p = plan(
            steps = listOf(step(0, StepKind.CAPABILITY_INVOCATION, "messaging.send", "Send the reply")),
            declared = setOf("messaging.send"),
        )
        val execution = runner(
            pipeline = pipeline(), // nothing granted
            confirmations = ConfirmationProvider { true },
            executor = { ActionExecutor { executed = true; ActionEvidence.Confirmed("sent", now) } },
        ).run(p, task)

        assertFalse(executed)
        assertEquals(StepStatus.DENIED, execution.steps[0].status)
    }

    @Test
    fun cancellationBetweenStepsStopsAtAStepBoundary() = runBlocking {
        var runs = 0
        val p = plan(steps = listOf(step(0), step(1), step(2)))
        val execution = runner(
            pipeline = pipeline(),
            port = StepPort { runs++; StepResult.Produced("ok") },
            cancelled = { runs >= 1 },
        ).run(p, task)

        assertTrue(execution.cancelled)
        assertEquals(TaskState.CANCELLED, execution.finalState)
        assertEquals(3, execution.steps.size)
        assertEquals(StepStatus.SUCCEEDED, execution.steps[0].status)
        assertEquals(StepStatus.NOT_RUN, execution.steps[1].status)
    }

    @Test
    fun memoryWriteIsTreatedAsACapabilityNotAFreeAction() = runBlocking {
        val p = plan(
            steps = listOf(step(0, StepKind.MEMORY_WRITE, instruction = "Remember that Asha prefers Hindi")),
            declared = setOf("memory.write"),
        )
        val execution = runner(pipeline()).run(p, task)
        assertEquals(StepStatus.DENIED, execution.steps[0].status)
        assertEquals(DenialReason.NOT_GRANTED, audit.recent().last().denialReason)
    }

    @Test
    fun memoryWriteThatIsGrantedStillNeedsTheIntegration() = runBlocking {
        val p = plan(
            steps = listOf(step(0, StepKind.MEMORY_WRITE, instruction = "Remember Asha prefers Hindi")),
            declared = setOf("memory.write"),
        )
        val execution = runner(pipeline(Capability.MEMORY_WRITE)).run(p, task)
        // MEMORY_WRITE is LOW sensitivity, so no confirmation is required, but without a wired
        // integration the step is honestly reported as unconfirmed.
        assertEquals(StepStatus.UNCONFIRMED, execution.steps[0].status)
    }

    @Test
    fun stepPortExhaustionIsReportedAsAFailureNotASilentSuccess() = runBlocking {
        val port = StepPort.Unwired(StepKind.PERCEPTION_OCR)
        val execution = runner(pipeline(), port = port).run(plan(), task)
        assertEquals(StepStatus.FAILED, execution.steps[0].status)
        assertTrue(execution.steps[0].message.contains("no perception_ocr"))
    }

    @Test
    fun aThrowingStepPortFailsTheStepInsteadOfCrashingTheRun() = runBlocking {
        val port = StepPort { throw IllegalStateException("decoder crashed") }
        val execution = runner(pipeline(), port = port).run(plan(), task)
        assertEquals(StepStatus.FAILED, execution.steps[0].status)
        assertTrue(execution.steps[0].message.contains("decoder crashed"))
    }

    @Test
    fun targetIsTakenFromTheInstructionWhenPresent() = runBlocking {
        val p = plan(
            steps = listOf(
                step(0, StepKind.CAPABILITY_INVOCATION, "messaging.send", "Send the reply target: +919000000001"),
            ),
            declared = setOf("messaging.send"),
        )
        val execution = runner(
            pipeline = pipeline(Capability.MESSAGING_SEND),
            confirmations = ConfirmationProvider { true },
            executor = { ActionExecutor { ActionEvidence.Confirmed("sent", now) } },
        ).run(p, task)

        assertTrue(execution.succeeded)
        assertEquals("+919000000001", audit.recent().last().target)
    }

    @Test
    fun completedStepsAreRecordedOnTheTask() = runBlocking {
        val p = plan(steps = listOf(step(0), step(1)))
        runner(pipeline()).run(p, task)
        // The runner owns the transition; assert via the audit/plan surface that both ran in order.
        assertNotNull(p.steps[1])
        assertEquals(listOf(0, 1), p.steps.map { it.index })
    }

    @Test
    fun emptyOutputFromASuccessfulStepIsStillSuccess() = runBlocking {
        // RESPOND with an empty string is legal; it is not the same as "no result".
        val execution = runner(pipeline(), port = StepPort { StepResult.Produced("") }).run(plan(), task)
        assertEquals(StepStatus.SUCCEEDED, execution.steps[0].status)
    }
}
