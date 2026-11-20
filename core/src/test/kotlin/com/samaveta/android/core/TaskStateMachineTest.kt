package com.samaveta.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.samaveta.android.core.core.TaskRecord
import com.samaveta.android.core.core.TaskState
import com.samaveta.android.core.core.TaskStateMachine
import com.samaveta.android.core.core.TaskTransition
import com.samaveta.android.core.core.TransitionRejection

/**
 * Scenario J (process recovery) and the "never repeat a side effect" rule are acceptance criteria,
 * so they are tested as such.
 */
class TaskStateMachineTest {

    private fun record(
        state: TaskState = TaskState.PENDING,
        sideEffect: Boolean = false,
        completed: List<String> = emptyList(),
        interruptedAt: String? = if (state == TaskState.INTERRUPTED) "send" else null,
        failure: String? = if (state == TaskState.FAILED) "network" else null,
    ) = TaskRecord(
        id = "task-1",
        title = "Send the summary",
        state = state,
        createdAtEpochMs = 1_000,
        updatedAtEpochMs = 1_000,
        hasPerformedSideEffect = sideEffect,
        completedSteps = completed,
        interruptedAtStep = interruptedAt,
        failureReason = failure,
    )

    private fun TaskTransition.applied(): TaskRecord {
        assertTrue("expected Applied, got $this", this is TaskTransition.Applied)
        return (this as TaskTransition.Applied).record
    }

    @Test
    fun happyPathReachesSuccess() {
        var r = record()
        r = TaskStateMachine.start(r, 2_000).applied()
        assertEquals(TaskState.RUNNING, r.state)
        r = TaskStateMachine.completeStep(r, "plan", sideEffect = false, now = 3_000).applied()
        r = TaskStateMachine.completeStep(r, "summarise", sideEffect = false, now = 4_000).applied()
        r = TaskStateMachine.succeed(r, 5_000).applied()
        assertEquals(TaskState.SUCCEEDED, r.state)
        assertEquals(listOf("plan", "summarise"), r.completedSteps)
        assertTrue(r.state.isTerminal)
    }

    @Test
    fun sideEffectFlagIsSticky() {
        var r = TaskStateMachine.start(record(), 2_000).applied()
        r = TaskStateMachine.completeStep(r, "send", sideEffect = true, now = 3_000).applied()
        assertTrue(r.hasPerformedSideEffect)
        r = TaskStateMachine.completeStep(r, "log", sideEffect = false, now = 4_000).applied()
        assertTrue("a later read-only step must not clear the flag", r.hasPerformedSideEffect)
    }

    @Test
    fun cannotStartTwice() {
        val running = record(state = TaskState.RUNNING)
        val result = TaskStateMachine.start(running, 2_000)
        assertTrue(result is TaskTransition.Rejected)
    }

    @Test
    fun cannotCompleteStepsWhenNotRunning() {
        val result = TaskStateMachine.completeStep(record(state = TaskState.PENDING), "x", false, 2_000)
        assertEquals(
            TransitionRejection.NOT_RUNNING,
            (result as TaskTransition.Rejected).reason,
        )
    }

    // ---- process recovery (Scenario J) --------------------------------------------------------

    @Test
    fun runningTaskBecomesInterruptedAfterProcessDeath() {
        val running = record(state = TaskState.RUNNING, completed = listOf("plan"))
        val recovered = TaskStateMachine.recover(running, inFlightStep = "summarise", now = 9_000)
        assertEquals(TaskState.INTERRUPTED, recovered.state)
        assertEquals("summarise", recovered.interruptedAtStep)
        assertEquals("verified steps must be preserved", listOf("plan"), recovered.completedSteps)
    }

    @Test
    fun recoveryDoesNotChangePendingOrTerminalTasks() {
        val pending = record()
        assertEquals(TaskState.PENDING, TaskStateMachine.recover(pending, null, 1).state)
        val succeeded = record(state = TaskState.SUCCEEDED)
        assertEquals(TaskState.SUCCEEDED, TaskStateMachine.recover(succeeded, null, 1).state)
    }

    @Test
    fun interruptedTaskRequiresExplicitUserDecisionToResume() {
        val interrupted = record(state = TaskState.INTERRUPTED)
        val refused = TaskStateMachine.retry(interrupted, userConfirmed = false, now = 10_000)
        assertEquals(
            TransitionRejection.INTERRUPTED_REQUIRES_USER,
            (refused as TaskTransition.Rejected).reason,
        )
        val resumed = TaskStateMachine.retry(interrupted, userConfirmed = true, now = 10_000).applied()
        assertEquals(TaskState.PENDING, resumed.state)
        assertEquals(2, resumed.attempt)
    }

    @Test
    fun resumePointSkipsVerifiedSteps() {
        val interrupted = record(state = TaskState.INTERRUPTED, completed = listOf("plan", "search"))
        assertEquals(2, TaskStateMachine.resumePointFor(interrupted))
    }

    // ---- retry policy -------------------------------------------------------------------------

    @Test
    fun unconfirmedTaskIsNeverAutoRetried() {
        val unconfirmed = record(state = TaskState.UNCONFIRMED)
        val result = TaskStateMachine.retry(unconfirmed, userConfirmed = true, now = 1)
        assertEquals(
            TransitionRejection.UNCONFIRMED_RETRY_REQUIRES_USER,
            (result as TaskTransition.Rejected).reason,
        )
    }

    @Test
    fun failedTaskWithoutSideEffectMayBeRetried() {
        val failed = record(state = TaskState.FAILED, sideEffect = false)
        val retried = TaskStateMachine.retry(failed, userConfirmed = false, now = 1).applied()
        assertEquals(TaskState.PENDING, retried.state)
        assertEquals(2, retried.attempt)
    }

    @Test
    fun failedTaskWithSideEffectRequiresConfirmation() {
        val failed = record(state = TaskState.FAILED, sideEffect = true)
        val refused = TaskStateMachine.retry(failed, userConfirmed = false, now = 1)
        assertEquals(
            TransitionRejection.SIDE_EFFECT_REQUIRES_USER,
            (refused as TaskTransition.Rejected).reason,
        )
        assertTrue(TaskStateMachine.retry(failed, userConfirmed = true, now = 1) is TaskTransition.Applied)
    }

    @Test
    fun terminalSuccessAndCancellationCannotBeRetried() {
        assertTrue(
            TaskStateMachine.retry(record(state = TaskState.SUCCEEDED), true, 1) is TaskTransition.Rejected,
        )
        assertTrue(
            TaskStateMachine.retry(record(state = TaskState.CANCELLED), true, 1) is TaskTransition.Rejected,
        )
    }

    @Test
    fun cancelIsIdempotentButRejectsFinishedTasks() {
        val running = record(state = TaskState.RUNNING)
        assertEquals(TaskState.CANCELLED, TaskStateMachine.cancel(running, 2).applied().state)
        assertTrue(
            TaskStateMachine.cancel(record(state = TaskState.SUCCEEDED), 2) is TaskTransition.Rejected,
        )
    }

    @Test
    fun autoRetryPermissionMatchesDocumentedPolicy() {
        assertTrue(TaskState.PENDING.mayAutoRetry)
        assertTrue(TaskState.FAILED.mayAutoRetry)
        assertFalse("an unconfirmed effect must never be retried automatically", TaskState.UNCONFIRMED.mayAutoRetry)
        assertFalse(TaskState.INTERRUPTED.mayAutoRetry)
        assertFalse(TaskState.RUNNING.mayAutoRetry)
    }

    @Test
    fun unconfirmedStateCarriesAReasonAndIsNotSuccess() {
        val running = record(state = TaskState.RUNNING)
        val r = TaskStateMachine.markUnconfirmed(running, "no delivery receipt", 5_000).applied()
        assertEquals(TaskState.UNCONFIRMED, r.state)
        assertEquals("no delivery receipt", r.failureReason)
        assertFalse(r.state.isTerminal && r.state == TaskState.SUCCEEDED)
    }

    @Test
    fun everyStateHasAUserFacingLabel() {
        TaskState.entries.forEach { state ->
            assertTrue("${state.name} needs a label", state.userLabel.isNotBlank())
        }
    }
}
