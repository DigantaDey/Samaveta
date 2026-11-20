package com.samaveta.android.core.core

/**
 * Task lifecycle.
 *
 * The product requires: cancellable operations, safe retry, accurate state after a process kill,
 * and never repeating a side-effecting action. Those requirements are encoded in this state machine
 * rather than in each screen, because getting them wrong once is enough to double-send a message.
 *
 * Recovery rule (Scenario J): a task found in [TaskState.RUNNING] after process death is *ambiguous*
 * — the step may or may not have completed. Such a task becomes [TaskState.INTERRUPTED] and requires
 * an explicit user decision before anything continues. It is never auto-retried if it has already
 * performed a side-effecting step.
 */
enum class TaskState {
    /** Created, nothing executed. Safe to start or discard. */
    PENDING,

    /** Executing. On process death this becomes INTERRUPTED, never silently resumed. */
    RUNNING,

    /** Finished, and the effect was confirmed by evidence. */
    SUCCEEDED,

    /** Finished, effect confirmed not to have happened. Safe to retry. */
    FAILED,

    /** Attempted; the effect could not be confirmed. Must never be auto-retried. */
    UNCONFIRMED,

    /** Stopped by the user or the emergency stop. */
    CANCELLED,

    /** Process died mid-execution. Requires a user decision. */
    INTERRUPTED,
    ;

    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == UNCONFIRMED || this == CANCELLED

    /** Only these may be retried without asking the user. */
    val mayAutoRetry: Boolean
        get() = this == PENDING || this == FAILED

    val userLabel: String
        get() = when (this) {
            PENDING -> "Queued"
            RUNNING -> "Running"
            SUCCEEDED -> "Done"
            FAILED -> "Not completed"
            UNCONFIRMED -> "Could not confirm"
            CANCELLED -> "Cancelled"
            INTERRUPTED -> "Interrupted"
        }
}

data class TaskRecord(
    val id: String,
    val title: String,
    val state: TaskState,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    /** True once any step has changed state outside SAMAVETA. */
    val hasPerformedSideEffect: Boolean = false,
    /** Steps completed and verified, in order. */
    val completedSteps: List<String> = emptyList(),
    /** Step in flight when the process died, if any. */
    val interruptedAtStep: String? = null,
    val failureReason: String? = null,
    val attempt: Int = 1,
) {
    init {
        require(id.isNotBlank()) { "task id required" }
        require(title.isNotBlank()) { "task title required" }
        require(attempt >= 1) { "attempt starts at 1" }
        if (state == TaskState.INTERRUPTED) {
            require(interruptedAtStep != null) { "an interrupted task must record where it stopped" }
        }
        if (state == TaskState.FAILED) {
            require(failureReason != null) { "a failed task must record why" }
        }
    }
}

sealed interface TaskTransition {
    data class Applied(val record: TaskRecord) : TaskTransition
    data class Rejected(val reason: TransitionRejection) : TaskTransition
}

enum class TransitionRejection(val userMessage: String) {
    ALREADY_TERMINAL("This task has already finished."),
    NOT_RUNNING("This task is not running."),
    UNCONFIRMED_RETRY_REQUIRES_USER("The previous attempt could not be confirmed. Check the app directly before retrying."),
    SIDE_EFFECT_REQUIRES_USER("This task already changed something outside SAMAVETA. Retrying needs your confirmation."),
    INTERRUPTED_REQUIRES_USER("This task was interrupted. Decide whether to retry or discard it."),
}

/**
 * Pure state machine. No coroutines, no clock: `now` is passed in, so every path is deterministically
 * testable, including process-death recovery.
 */
object TaskStateMachine {

    fun start(record: TaskRecord, now: Long): TaskTransition {
        if (record.state != TaskState.PENDING) {
            return TaskTransition.Rejected(TransitionRejection.ALREADY_TERMINAL)
        }
        return TaskTransition.Applied(record.copy(state = TaskState.RUNNING, updatedAtEpochMs = now))
    }

    /** Records a verified step. Side effects flip [TaskRecord.hasPerformedSideEffect] permanently. */
    fun completeStep(record: TaskRecord, step: String, sideEffect: Boolean, now: Long): TaskTransition {
        if (record.state != TaskState.RUNNING) return TaskTransition.Rejected(TransitionRejection.NOT_RUNNING)
        return TaskTransition.Applied(
            record.copy(
                completedSteps = record.completedSteps + step,
                hasPerformedSideEffect = record.hasPerformedSideEffect || sideEffect,
                updatedAtEpochMs = now,
            ),
        )
    }

    fun succeed(record: TaskRecord, now: Long): TaskTransition = when (record.state) {
        TaskState.RUNNING, TaskState.PENDING ->
            TaskTransition.Applied(record.copy(state = TaskState.SUCCEEDED, updatedAtEpochMs = now))
        else -> TaskTransition.Rejected(TransitionRejection.NOT_RUNNING)
    }

    fun fail(record: TaskRecord, reason: String, now: Long): TaskTransition = when (record.state) {
        TaskState.RUNNING, TaskState.PENDING ->
            TaskTransition.Applied(
                record.copy(state = TaskState.FAILED, failureReason = reason, updatedAtEpochMs = now),
            )
        else -> TaskTransition.Rejected(TransitionRejection.NOT_RUNNING)
    }

    /** Effect attempted, outcome unknown. Never treated as success and never auto-retried. */
    fun markUnconfirmed(record: TaskRecord, reason: String, now: Long): TaskTransition = when (record.state) {
        TaskState.RUNNING, TaskState.PENDING ->
            TaskTransition.Applied(
                record.copy(
                    state = TaskState.UNCONFIRMED,
                    failureReason = reason,
                    hasPerformedSideEffect = record.hasPerformedSideEffect,
                    updatedAtEpochMs = now,
                ),
            )
        else -> TaskTransition.Rejected(TransitionRejection.NOT_RUNNING)
    }

    fun cancel(record: TaskRecord, now: Long): TaskTransition = if (record.state.isTerminal) {
        TaskTransition.Rejected(TransitionRejection.ALREADY_TERMINAL)
    } else {
        TaskTransition.Applied(record.copy(state = TaskState.CANCELLED, updatedAtEpochMs = now))
    }

    /**
     * Called at process start for every non-terminal record. A task cannot still be "running" after
     * its process died, so its true state is unknown.
     */
    fun recover(record: TaskRecord, inFlightStep: String?, now: Long): TaskRecord = when {
        record.state.isTerminal -> record
        record.state == TaskState.PENDING -> record
        else -> record.copy(
            state = TaskState.INTERRUPTED,
            interruptedAtStep = inFlightStep ?: record.completedSteps.lastOrNull() ?: "unknown step",
            updatedAtEpochMs = now,
        )
    }

    /**
     * The only path that may move a terminal-or-interrupted task back to PENDING.
     *
     * Refusal cases encode the product's rule that an unconfirmed or outward-facing action must not
     * be repeated on the user's behalf.
     */
    fun retry(record: TaskRecord, userConfirmed: Boolean, now: Long): TaskTransition = when (record.state) {
        TaskState.PENDING -> TaskTransition.Applied(record)
        TaskState.FAILED -> {
            if (record.hasPerformedSideEffect && !userConfirmed) {
                TaskTransition.Rejected(TransitionRejection.SIDE_EFFECT_REQUIRES_USER)
            } else {
                TaskTransition.Applied(
                    record.copy(state = TaskState.PENDING, attempt = record.attempt + 1, updatedAtEpochMs = now),
                )
            }
        }

        TaskState.UNCONFIRMED ->
            TaskTransition.Rejected(TransitionRejection.UNCONFIRMED_RETRY_REQUIRES_USER)

        TaskState.INTERRUPTED -> {
            if (!userConfirmed) {
                TaskTransition.Rejected(TransitionRejection.INTERRUPTED_REQUIRES_USER)
            } else {
                // Resuming never repeats already-verified steps; the executor resumes after them.
                TaskTransition.Applied(
                    record.copy(
                        state = TaskState.PENDING,
                        attempt = record.attempt + 1,
                        interruptedAtStep = null,
                        updatedAtEpochMs = now,
                    ),
                )
            }
        }

        TaskState.RUNNING -> TaskTransition.Rejected(TransitionRejection.NOT_RUNNING)
        TaskState.SUCCEEDED, TaskState.CANCELLED ->
            TaskTransition.Rejected(TransitionRejection.ALREADY_TERMINAL)
    }

    /** From which step a resumed task must continue, so verified steps are never repeated. */
    fun resumePointFor(record: TaskRecord): Int = record.completedSteps.size
}
