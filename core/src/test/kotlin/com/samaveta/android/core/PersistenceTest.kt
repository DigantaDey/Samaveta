package com.samaveta.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.samaveta.android.core.core.TaskRecord
import com.samaveta.android.core.core.TaskState
import com.samaveta.android.core.core.TaskStateMachine
import com.samaveta.android.core.core.TaskTransition
import com.samaveta.android.core.memory.MemoryItem
import com.samaveta.android.core.memory.MemoryKind
import com.samaveta.android.core.memory.MemorySource
import com.samaveta.android.core.persistence.AuditCodec
import com.samaveta.android.core.persistence.MemoryCodec
import com.samaveta.android.core.persistence.PersistenceException
import com.samaveta.android.core.persistence.PersistenceSchema
import com.samaveta.android.core.persistence.TaskCodec
import com.samaveta.android.core.security.Actor
import com.samaveta.android.core.security.AuditOutcome
import com.samaveta.android.core.security.AuditRecord
import com.samaveta.android.core.security.Capability
import com.samaveta.android.core.security.DenialReason

/**
 * Persistence is where "local-first" stops being a slogan: if this data is lost, the product forgets
 * the user. The tests therefore care as much about what happens to *bad* data as to good data.
 */
class PersistenceTest {

    private fun memory(
        id: String = "m-1",
        kind: MemoryKind = MemoryKind.SEMANTIC,
        text: String = "Asha prefers replies in Hindi",
        source: MemorySource = MemorySource.ExplicitUserStatement("conv-1"),
        confidence: Double = 0.9,
    ) = MemoryItem(
        id = id,
        kind = kind,
        text = text,
        createdAtEpochMs = 1_700_000_000_000,
        lastAccessedEpochMs = 1_700_000_100_000,
        confidence = confidence,
        source = source,
        tags = setOf("people", "language"),
        subject = "asha",
    )

    private fun auditRecord(
        id: String = "a-1",
        capability: Capability? = Capability.MESSAGING_SEND,
        outcome: AuditOutcome = AuditOutcome.VERIFIED,
        detail: String? = null,
        denialReason: DenialReason? = null,
    ) = AuditRecord(
        id = id,
        atEpochMs = 1_700_000_000_000,
        actor = Actor.AGENT,
        capability = capability,
        action = "Send the drafted reply to Asha",
        outcome = outcome,
        target = "+919000000001",
        taskId = "task-1",
        detail = detail,
        denialReason = denialReason,
    )

    private fun task(
        id: String = "task-1",
        state: TaskState = TaskState.PENDING,
        interruptedAtStep: String? = null,
        failureReason: String? = null,
    ) = TaskRecord(
        id = id,
        title = "Reply to Asha",
        state = state,
        createdAtEpochMs = 1_700_000_000_000,
        updatedAtEpochMs = 1_700_000_100_000,
        hasPerformedSideEffect = true,
        completedSteps = listOf("Draft reply", "Confirm recipient"),
        interruptedAtStep = interruptedAtStep,
        failureReason = failureReason,
        attempt = 2,
    )

    // ---- memories -------------------------------------------------------------------------

    @Test
    fun memoriesSurviveARoundTrip() {
        val original = listOf(
            memory(),
            memory(id = "m-2", kind = MemoryKind.EPISODIC, source = MemorySource.FeedbackDerived("card-9")),
            memory(id = "m-3", kind = MemoryKind.PROCEDURAL, source = MemorySource.InferredBehaviour("repeated", 4)),
        )
        val decoded = MemoryCodec.decode(MemoryCodec.encode(original))
        assertEquals(original, decoded.items)
        assertFalse(decoded.hasRejections)
    }

    @Test
    fun everyMemorySourceVariantRoundTrips() {
        val sources = listOf(
            MemorySource.ExplicitUserStatement("conv-1"),
            MemorySource.ExplicitUserStatement(null),
            MemorySource.InferredBehaviour("user muted three tech stories", 3),
            MemorySource.FeedbackDerived("card-42"),
            MemorySource.UserEdited("user corrected the spelling"),
            MemorySource.UserEdited(null),
        )
        sources.forEach { source ->
            val decoded = MemoryCodec.decode(MemoryCodec.encode(listOf(memory(source = source))))
            assertEquals("source $source must survive", source, decoded.items.single().source)
        }
    }

    @Test
    fun emptyCollectionsRoundTrip() {
        assertEquals(emptyList<MemoryItem>(), MemoryCodec.decode(MemoryCodec.encode(emptyList())).items)
        assertTrue(AuditCodec.decode(AuditCodec.encode(emptyList())).items.isEmpty())
        assertTrue(TaskCodec.decode(TaskCodec.encode(emptyList())).items.isEmpty())
    }

    @Test
    fun oneCorruptMemoryDoesNotCostTheUsersOtherMemories() {
        val good = MemoryCodec.encode(
            listOf(memory("m-1"), memory("m-2", text = "Second memory about the Kolkata trip")),
        )
        // Corrupt the second item only: blank its text, which MemoryItem rejects.
        val tampered = good.replace("\"Second memory about the Kolkata trip\"", "\"\"")
        val decoded = MemoryCodec.decode(tampered)
        assertTrue("the untouched memory must survive", decoded.items.isNotEmpty())
        assertTrue(decoded.hasRejections)
    }

    @Test
    fun unknownMemoryKindIsRejectedNotGuessed() {
        val payload = MemoryCodec.encode(listOf(memory())).replace("\"SEMANTIC\"", "\"TELEPATHIC\"")
        val decoded = MemoryCodec.decode(payload)
        assertTrue(decoded.items.isEmpty())
        assertTrue(decoded.rejected.single().contains("TELEPATHIC"))
    }

    @Test
    fun invalidConfidenceFromStorageIsRejected() {
        val payload = MemoryCodec.encode(listOf(memory())).replace("\"confidence\":0.9", "\"confidence\":1.7")
        assertTrue(MemoryCodec.decode(payload).allRejected)
    }

    @Test
    fun requireCleanThrowsSoUserDataIsNeverSilentlyDropped() {
        val payload = MemoryCodec.encode(listOf(memory())).replace("\"SEMANTIC\"", "\"WHATEVER\"")
        try {
            MemoryCodec.decode(payload).requireClean()
            throw AssertionError("expected a PersistenceException")
        } catch (expected: PersistenceException) {
            assertTrue(expected.message!!.contains("could not be read"))
        }
    }

    // ---- audit ----------------------------------------------------------------------------

    @Test
    fun auditRecordsRoundTripIncludingTheDenialReason() {
        val records = listOf(
            auditRecord(),
            auditRecord(id = "a-2", outcome = AuditOutcome.DENIED, denialReason = DenialReason.NOT_GRANTED),
            auditRecord(id = "a-3", capability = null, outcome = AuditOutcome.EXECUTED),
            auditRecord(id = "a-4", outcome = AuditOutcome.FAILED, detail = "executor threw IllegalStateException"),
        )
        val decoded = AuditCodec.decode(AuditCodec.encode(records))
        assertEquals(records, decoded.items)
    }

    @Test
    fun aTamperedAuditDetailCannotReintroduceSecrets() {
        // Even if an attacker edits the store to add a token, AuditRecord's invariant refuses it.
        val payload = AuditCodec.encode(listOf(auditRecord()))
            .replace("\"detail\"" , "\"detail\"") // no-op guard for readability
            .replaceFirst("\"outcome\"", "\"detail\":\"Bearer abc123\",\"outcome\"")
        val decoded = AuditCodec.decode(payload)
        assertTrue("the record with a leaked credential must be refused", decoded.allRejected)
        assertTrue(decoded.rejected.single().contains("redact"))
    }

    @Test
    fun unknownCapabilityInStoredAuditIsRejected() {
        val payload = AuditCodec.encode(listOf(auditRecord())).replace("\"messaging.send\"", "\"device.root\"")
        assertTrue(AuditCodec.decode(payload).allRejected)
    }

    @Test
    fun auditOrderIsPreservedSoTheTimelineReadsCorrectly() {
        val records = (1..5).map { auditRecord(id = "a-$it") }
        assertEquals(records.map { it.id }, AuditCodec.decode(AuditCodec.encode(records)).items.map { it.id })
    }

    // ---- tasks ----------------------------------------------------------------------------

    @Test
    fun tasksRoundTrip() {
        val tasks = listOf(
            task("t-1", TaskState.PENDING),
            task("t-2", TaskState.SUCCEEDED),
            task("t-3", TaskState.FAILED, failureReason = "network unreachable"),
            task("t-4", TaskState.INTERRUPTED, interruptedAtStep = "Send the message"),
        )
        assertEquals(tasks, TaskCodec.decode(TaskCodec.encode(tasks)).items)
    }

    @Test
    fun aTaskStoredMidRunIsFlaggedForRecovery() {
        val running = task(state = TaskState.RUNNING)
        assertTrue(TaskCodec.needsRecovery(running))
        assertFalse(TaskCodec.needsRecovery(task(state = TaskState.SUCCEEDED)))
    }

    @Test
    fun recoveredTaskBecomesInterruptedAndIsNeverAutoResumed() {
        // The round trip this guards: persist RUNNING -> process dies -> reload -> recover.
        val decoded = TaskCodec.decode(TaskCodec.encode(listOf(task(state = TaskState.RUNNING)))).items.single()
        assertTrue(TaskCodec.needsRecovery(decoded))

        val recovered = (TaskStateMachine.recover(decoded, inFlightStep = "Send the message", now = 9_000))
        assertEquals(TaskState.INTERRUPTED, recovered.state)
        assertEquals("Send the message", recovered.interruptedAtStep)
        assertFalse("a side-effecting interrupted task must never auto-retry", recovered.state.mayAutoRetry)

        val retried = TaskStateMachine.retry(recovered, userConfirmed = false, now = 10_000)
        assertTrue(retried is TaskTransition.Rejected)
    }

    @Test
    fun interruptedTaskWithoutItsStepIsRejectedOnRead() {
        // TaskRecord requires interruptedAtStep for INTERRUPTED; a store that lost it is corrupt.
        val payload = TaskCodec.encode(listOf(task("t-1", TaskState.INTERRUPTED, interruptedAtStep = "step")))
            .replace(",\"interruptedAtStep\":\"step\"", "")
        assertTrue(TaskCodec.decode(payload).allRejected)
    }

    @Test
    fun failedTaskWithoutAReasonIsRejectedOnRead() {
        val payload = TaskCodec.encode(listOf(task("t-1", TaskState.FAILED, failureReason = "boom")))
            .replace(",\"failureReason\":\"boom\"", "")
        assertTrue(TaskCodec.decode(payload).allRejected)
    }

    // ---- schema ---------------------------------------------------------------------------

    @Test
    fun aNewerSchemaVersionIsRefusedRatherThanGuessed() {
        val payload = MemoryCodec.encode(listOf(memory()))
            .replace("\"version\":${PersistenceSchema.VERSION}", "\"version\":${PersistenceSchema.VERSION + 1}")
        try {
            MemoryCodec.decode(payload)
            throw AssertionError("expected a PersistenceException")
        } catch (expected: PersistenceException) {
            assertTrue(expected.message!!.contains("newer than this build"))
        }
    }

    @Test
    fun aPayloadOfTheWrongKindIsRefused() {
        val memoryPayload = MemoryCodec.encode(listOf(memory()))
        try {
            AuditCodec.decode(memoryPayload)
            throw AssertionError("expected a PersistenceException")
        } catch (expected: PersistenceException) {
            assertTrue(expected.message!!.contains("expected a 'audit' payload"))
        }
    }

    @Test
    fun aMissingVersionIsRefused() {
        val payload = MemoryCodec.encode(listOf(memory())).replace("\"version\":1,", "")
        try {
            MemoryCodec.decode(payload)
            throw AssertionError("expected a PersistenceException")
        } catch (expected: PersistenceException) {
            assertTrue(expected.message!!.contains("no schema version"))
        }
    }

    @Test
    fun invalidJsonIsReportedAsAPersistenceFailureNotACrash() {
        try {
            TaskCodec.decode("{ this is not json")
            throw AssertionError("expected a PersistenceException")
        } catch (expected: PersistenceException) {
            assertTrue(expected.message!!.contains("not valid JSON"))
        }
    }

    @Test
    fun payloadsAreStableAndReadable() {
        // Stored data is also debuggable data; a compact stable encoding keeps diffs meaningful.
        val encoded = TaskCodec.encode(listOf(task()))
        assertTrue(encoded.contains("\"version\":1"))
        assertTrue(encoded.contains("\"kind\":\"tasks\""))
        assertFalse("payload must not be pretty-printed by default", encoded.contains("\n"))
    }

    @Test
    fun unicodeSurvivesStorage() {
        val item = memory(text = "आशा को हिंदी में उत्तर पसंद है — 日本語も可 🙂")
        val decoded = MemoryCodec.decode(MemoryCodec.encode(listOf(item))).items.single()
        assertEquals(item.text, decoded.text)
    }

    @Test
    fun largeCollectionsRoundTrip() {
        val many = (1..500).map { memory(id = "m-$it") }
        val decoded = MemoryCodec.decode(MemoryCodec.encode(many))
        assertEquals(500, decoded.items.size)
        assertFalse(decoded.hasRejections)
    }

    @Test
    fun taskWithNoOptionalFieldsRoundTrips() {
        val minimal = TaskRecord(
            id = "t-min",
            title = "Minimal",
            state = TaskState.PENDING,
            createdAtEpochMs = 0,
            updatedAtEpochMs = 0,
        )
        val decoded = TaskCodec.decode(TaskCodec.encode(listOf(minimal))).items.single()
        assertEquals(minimal, decoded)
        assertNull(decoded.interruptedAtStep)
        assertNull(decoded.failureReason)
    }
}
