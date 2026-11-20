package com.samaveta.android.core.orchestration

import com.samaveta.android.core.core.TaskRecord
import com.samaveta.android.core.core.TaskState
import com.samaveta.android.core.core.TaskStateMachine
import com.samaveta.android.core.core.TaskTransition
import com.samaveta.android.core.laya.LayaPlan
import com.samaveta.android.core.laya.LayaPlanStep
import com.samaveta.android.core.laya.StepKind
import com.samaveta.android.core.security.ActionExecutor
import com.samaveta.android.core.security.ActionPipeline
import com.samaveta.android.core.security.ActionRequest
import com.samaveta.android.core.security.ActionStatus
import com.samaveta.android.core.security.Actor
import com.samaveta.android.core.security.AuditLog
import com.samaveta.android.core.security.AuditOutcome
import com.samaveta.android.core.security.AuditRecord
import com.samaveta.android.core.security.Capability
import com.samaveta.android.core.security.ConfirmationRequest
import com.samaveta.android.core.security.DenialReason

/**
 * Executes a LAYA plan step by step, on the SAMAVETA side of the boundary.
 *
 * LAYA decides *what* to do; this component decides whether SAMAVETA may do it and whether it
 * actually happened. Two rules are structural:
 *
 *  - LAYA cannot widen its own authority. A plan step may only invoke a capability that exists in
 *    [Capability] and that the plan itself declared up front. Unknown or undeclared capabilities
 *    reject the whole plan before a single step runs.
 *  - Nothing is assumed to have worked. A capability step is only SUCCEEDED when the action pipeline
 *    produced confirmed evidence; a model step is only SUCCEEDED when its executor returned text.
 *
 * The runner is suspending because a confirmation may involve waiting for the user, but it owns no
 * threads and no timers: all time and all side effects arrive through injected ports.
 */

/** A non-capability step (inference, OCR, retrieval, browser, memory) executed by the app layer. */
fun interface StepPort {
    suspend fun run(step: LayaPlanStep): StepResult

    /** Where the plan actually runs. Supplied by the Android layer; unused by the JVM tests. */
    class Unwired(private val kind: StepKind) : StepPort {
        override suspend fun run(step: LayaPlanStep): StepResult =
            StepResult.Failed("no ${kind.name.lowercase()} implementation is available on this build")
    }
}

sealed interface StepResult {
    /** The step produced its expected output. [output] is what the next step may consume. */
    data class Produced(val output: String) : StepResult

    /** The step ran but its effect could not be confirmed. Never treated as success. */
    data class Unconfirmed(val reason: String) : StepResult

    /** The step did not happen, for the stated reason. */
    data class Failed(val reason: String) : StepResult
}

/**
 * Asks the user to approve one exact action.
 *
 * It returns a plain yes/no rather than a token: the runner registers the approval with the
 * pipeline itself, so no other component can manufacture an approval.
 */
fun interface ConfirmationProvider {
    suspend fun requestApproval(request: ConfirmationRequest): Boolean
}

/** A provider used when nothing is wired yet: it approves nothing. */
object DenyAllConfirmations : ConfirmationProvider {
    override suspend fun requestApproval(request: ConfirmationRequest): Boolean = false
}

enum class StepStatus {
    /** Never attempted (plan rejected or a previous step stopped the run). */
    NOT_RUN,
    SUCCEEDED,
    /** Ran, but the effect could not be confirmed. Must not be shown as done. */
    UNCONFIRMED,
    FAILED,
    /** The user declined, or policy refused, before anything happened. */
    DENIED,
    ;

    val isSuccess: Boolean get() = this == SUCCEEDED
}

data class StepReport(
    val index: Int,
    val kind: StepKind,
    val description: String,
    val status: StepStatus,
    val message: String,
    /** Output text, present only when the step succeeded. */
    val output: String? = null,
)

data class PlanExecution(
    val plan: LayaPlan,
    val taskId: String,
    val finalState: TaskState,
    val steps: List<StepReport>,
    val cancelled: Boolean,
    /** True when the plan was rejected before execution; [steps] then explains why. */
    val rejected: Boolean = false,
) {
    val succeeded: Boolean get() = finalState == TaskState.SUCCEEDED

    fun firstFailure(): StepReport? = steps.firstOrNull { !it.status.isSuccess }
}

/** Result of validating a plan against SAMAVETA's authority, before anything runs. */
sealed interface PlanValidation {
    data class Accepted(val capabilities: List<Capability>) : PlanValidation
    data class Rejected(val reason: String) : PlanValidation
}

object PlanValidator {

    /** A plan longer than this is almost certainly a runaway model loop rather than a task. */
    const val MAX_STEPS = 24

    /**
     * Checks a plan against the capability registry. Deliberately strict: an unknown capability id
     * is a rejection, not a skipped step, because a plan we cannot fully understand is a plan we
     * cannot safely half-execute.
     */
    fun validate(plan: LayaPlan): PlanValidation {
        if (plan.steps.size > MAX_STEPS) {
            return PlanValidation.Rejected("plan has ${plan.steps.size} steps; the limit is $MAX_STEPS")
        }
        val indices = plan.steps.map { it.index }
        if (indices != indices.sorted() || indices.distinct().size != indices.size) {
            return PlanValidation.Rejected("plan step indices must be unique and in order")
        }
        if (plan.steps.first().index != 0) {
            return PlanValidation.Rejected("plan must start at step 0")
        }

        val resolved = mutableListOf<Capability>()
        for (step in plan.steps) {
            if (step.instruction.length > 4_000) {
                return PlanValidation.Rejected("step ${step.index} instruction is too long")
            }
            if (step.kind != StepKind.CAPABILITY_INVOCATION) continue
            val id = step.capability ?: return PlanValidation.Rejected("step ${step.index} declares no capability")
            val capability = Capability.fromId(id)
                ?: return PlanValidation.Rejected("step ${step.index} requests unknown capability '$id'")
            resolved += capability
        }

        val declaredIds = plan.requestedCapabilities
        val unknownDeclared = declaredIds.filter { Capability.fromId(it) == null }
        if (unknownDeclared.isNotEmpty()) {
            return PlanValidation.Rejected("plan declares unknown capabilities ${unknownDeclared.sorted()}")
        }
        // Declaring a capability the user can see up front is the whole point of the declaration;
        // invoking one that was not declared is a silent escalation.
        val undeclared = resolved.map { it.id }.toSet() - declaredIds
        if (undeclared.isNotEmpty()) {
            return PlanValidation.Rejected("plan uses capabilities it did not declare: ${undeclared.sorted()}")
        }
        return PlanValidation.Accepted(resolved)
    }
}

/**
 * Runs a plan. One instance per task; not thread-safe.
 *
 * @param isCancelled polled between steps and before each confirmation, so a user-visible Cancel
 *   button stops the run at a step boundary rather than mid-effect.
 */
class PlanRunner(
    private val pipeline: ActionPipeline,
    private val auditLog: AuditLog,
    private val stepPort: StepPort = StepPort.Unwired(StepKind.RESPOND),
    /**
     * Supplies the integration that performs a capability. Returning null is legitimate and means
     * "this build cannot do that": the pipeline then reports the step as unconfirmed rather than
     * pretending it worked.
     */
    private val executorFor: (Capability) -> ActionExecutor? = { null },
    private val confirmations: ConfirmationProvider = DenyAllConfirmations,
    private val idFactory: () -> String,
    private val clock: () -> Long,
    private val isCancelled: () -> Boolean = { false },
) {

    suspend fun run(plan: LayaPlan, task: TaskRecord): PlanExecution {
        when (val validation = PlanValidator.validate(plan)) {
            is PlanValidation.Rejected -> {
                recordPlanRejection(plan, task, validation.reason)
                return PlanExecution(
                    plan = plan,
                    taskId = task.id,
                    finalState = TaskState.CANCELLED,
                    steps = plan.steps.map { step ->
                        StepReport(step.index, step.kind, step.instruction, StepStatus.NOT_RUN, validation.reason)
                    },
                    cancelled = false,
                    rejected = true,
                )
            }
            is PlanValidation.Accepted -> Unit
        }

        var record = transition(TaskStateMachine.start(task, clock()), task)
        val reports = mutableListOf<StepReport>()

        for (step in plan.steps) {
            if (isCancelled()) {
                return finishCancelled(plan, record, reports)
            }

            val execution = when (step.kind) {
                StepKind.CAPABILITY_INVOCATION -> runCapabilityStep(step, record)
                StepKind.MEMORY_WRITE -> runCapabilityStep(step, record, Capability.MEMORY_WRITE)
                StepKind.MEMORY_READ -> runPortStep(step)
                else -> runPortStep(step)
            }
            reports += execution
            record = recordStep(record, step, execution)

            if (!execution.status.isSuccess) {
                val state = when (execution.status) {
                    StepStatus.UNCONFIRMED -> TaskState.UNCONFIRMED
                    StepStatus.DENIED -> TaskState.CANCELLED
                    else -> TaskState.FAILED
                }
                val finalised = when (state) {
                    TaskState.UNCONFIRMED -> TaskStateMachine.markUnconfirmed(record, execution.message, clock())
                    TaskState.CANCELLED -> TaskStateMachine.cancel(record, clock())
                    else -> TaskStateMachine.fail(record, execution.message, clock())
                }
                // Report every step the plan contained, so the UI can show what was skipped and why
                // rather than silently truncating the plan at the failure.
                return PlanExecution(
                    plan = plan,
                    taskId = task.id,
                    finalState = transition(finalised, record).state,
                    steps = reports + unrunSteps(plan, reports.size, "Not run: an earlier step stopped the task."),
                    cancelled = state == TaskState.CANCELLED,
                )
            }
        }

        val done = transition(TaskStateMachine.succeed(record, clock()), record)
        return PlanExecution(plan, task.id, done.state, reports, cancelled = false)
    }

    private suspend fun runCapabilityStep(
        step: LayaPlanStep,
        record: TaskRecord,
        override: Capability? = null,
    ): StepReport {
        val capabilityId = override?.id ?: step.capability
        val capability = capabilityId?.let { Capability.fromId(it) }
            ?: return StepReport(
                step.index, step.kind, step.instruction, StepStatus.FAILED,
                "step ${step.index} names an unknown capability",
            )

        val target = extractTarget(step.instruction)
        val base = ActionRequest(
            id = "step-${step.index}-${idFactory()}",
            capability = capability,
            target = target,
            description = step.instruction,
            taskId = record.id,
            actor = Actor.AGENT,
        )

        // Ask before authorizing-and-running, but only when the policy actually wants an answer.
        val token: String? = if (pipeline.requiresConfirmation(capability, target)) {
            val confirmation = pipeline.requestConfirmation(base)
            val approved = confirmations.requestApproval(confirmation) &&
                pipeline.approve(confirmation) != null
            if (!approved) {
                recordDecline(capability, step, record)
                return StepReport(
                    step.index, step.kind, step.instruction, StepStatus.DENIED,
                    "Not approved: ${capability.displayName.lowercase()}.",
                )
            }
            confirmation.token
        } else {
            null
        }

        // Core does not know how to send a message or write a file; it only knows that it must not
        // claim it did. The integration arrives as a port, and its absence is reported honestly.
        val outcome = pipeline.run(base.copy(confirmationToken = token), executorFor(capability))

        val status = when (outcome.report.status) {
            ActionStatus.SUCCEEDED -> StepStatus.SUCCEEDED
            ActionStatus.UNCONFIRMED -> StepStatus.UNCONFIRMED
            ActionStatus.FAILED -> StepStatus.FAILED
            ActionStatus.CANCELLED -> StepStatus.DENIED
        }
        return StepReport(
            index = step.index,
            kind = step.kind,
            description = step.instruction,
            status = status,
            message = outcome.report.message,
            output = if (status == StepStatus.SUCCEEDED) outcome.report.message else null,
        )
    }

    private suspend fun runPortStep(step: LayaPlanStep): StepReport {
        val result = try {
            stepPort.run(step)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            StepResult.Failed("${failure::class.simpleName}: ${failure.message.orEmpty()}")
        }
        return when (result) {
            is StepResult.Produced -> StepReport(
                step.index, step.kind, step.instruction, StepStatus.SUCCEEDED,
                "Step ${step.index} completed.", result.output,
            )
            is StepResult.Unconfirmed -> StepReport(
                step.index, step.kind, step.instruction, StepStatus.UNCONFIRMED,
                "Step ${step.index} could not be confirmed: ${result.reason}",
            )
            is StepResult.Failed -> StepReport(
                step.index, step.kind, step.instruction, StepStatus.FAILED,
                "Step ${step.index} failed: ${result.reason}",
            )
        }
    }


    private fun recordStep(record: TaskRecord, step: LayaPlanStep, report: StepReport): TaskRecord =
        if (report.status.isSuccess) {
            transition(
                TaskStateMachine.completeStep(
                    record,
                    step = step.instruction,
                    sideEffect = step.kind == StepKind.CAPABILITY_INVOCATION,
                    now = clock(),
                ),
                record,
            )
        } else {
            record
        }

    private fun finishCancelled(plan: LayaPlan, record: TaskRecord, reports: List<StepReport>): PlanExecution {
        val done = transition(TaskStateMachine.cancel(record, clock()), record)
        return PlanExecution(
            plan = plan,
            taskId = record.id,
            finalState = done.state,
            steps = reports + unrunSteps(plan, reports.size, "Cancelled before this step."),
            cancelled = true,
        )
    }

    private fun unrunSteps(plan: LayaPlan, from: Int, reason: String): List<StepReport> =
        plan.steps.drop(from).map { step ->
            StepReport(step.index, step.kind, step.instruction, StepStatus.NOT_RUN, reason)
        }

    private fun recordPlanRejection(plan: LayaPlan, task: TaskRecord, reason: String) {
        auditLog.record(
            AuditRecord(
                id = idFactory(),
                atEpochMs = clock(),
                actor = Actor.LAYA,
                capability = null,
                action = "Execute plan",
                outcome = AuditOutcome.DENIED,
                target = null,
                taskId = task.id,
                detail = reason,
                denialReason = DenialReason.UNKNOWN_CAPABILITY,
            ),
        )
    }

    private fun recordDecline(capability: Capability, step: LayaPlanStep, record: TaskRecord) {
        auditLog.record(
            AuditRecord(
                id = idFactory(),
                atEpochMs = clock(),
                actor = Actor.USER,
                capability = capability,
                action = step.instruction,
                outcome = AuditOutcome.CANCELLED,
                target = null,
                taskId = record.id,
                detail = null,
                denialReason = DenialReason.CONFIRMATION_REQUIRED,
            ),
        )
    }

    /**
     * Best-effort target extraction. LAYA is expected to describe the target in the step instruction;
     * the app layer may also supply a structured target. Without one, a scoped capability simply
     * fails scope checks, which is the safe direction.
     */
    private fun extractTarget(instruction: String): String? {
        val marker = Regex("""target\s*[:=]\s*(\S+)""", RegexOption.IGNORE_CASE)
        return marker.find(instruction)?.groupValues?.get(1)?.trim('"', '\'')
    }

    private fun transition(outcome: TaskTransition, current: TaskRecord): TaskRecord =
        when (outcome) {
            is TaskTransition.Applied -> outcome.record
            is TaskTransition.Rejected -> current
        }
}
