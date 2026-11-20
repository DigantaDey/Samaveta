package com.samaveta.android.core.persistence

import com.samaveta.android.core.core.TaskRecord
import com.samaveta.android.core.core.TaskState
import com.samaveta.android.core.json.Json
import com.samaveta.android.core.json.JsonValue
import com.samaveta.android.core.json.json
import com.samaveta.android.core.memory.MemoryItem
import com.samaveta.android.core.memory.MemoryKind
import com.samaveta.android.core.memory.MemorySource
import com.samaveta.android.core.security.Actor
import com.samaveta.android.core.security.AuditOutcome
import com.samaveta.android.core.security.AuditRecord
import com.samaveta.android.core.security.Capability
import com.samaveta.android.core.security.DenialReason

/**
 * Durable state, encoded to and from JSON, so the Android layer can persist it with Room or a file
 * without the domain model depending on either.
 *
 * Three rules drive the shape of this codec:
 *
 *  1. **Versioned.** Saved data outlives the code that wrote it: an app update must not lose the
 *     user's memories. Every payload carries a schema version and an unknown version is refused
 *     rather than guessed at.
 *  2. **Strict on write, explicit on read.** Encoding is total. Decoding never invents data: a record
 *     that fails validation is dropped *and reported*, because silently resuming from a
 *     half-understood task is how an action gets performed twice.
 *  3. **No secrets.** The audit codec re-uses [AuditRecord]'s redaction rules, so persisted details
 *     cannot contain credentials even if a caller tries.
 */

object PersistenceSchema {
    /** Bump only with a migration, and never accept a newer version at runtime. */
    const val VERSION = 1

    const val KIND_MEMORY = "memory"
    const val KIND_AUDIT = "audit"
    const val KIND_TASKS = "tasks"
}

/** Raised when stored data cannot be understood. Callers must not silently discard user data. */
class PersistenceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Result of decoding a batch: what survived, and what was refused and why. */
data class DecodeResult<T>(
    val items: List<T>,
    val rejected: List<String>,
) {
    val hasRejections: Boolean get() = rejected.isNotEmpty()
    val allRejected: Boolean get() = items.isEmpty() && rejected.isNotEmpty()

    /**
     * Decoding rejects individual records rather than throwing, because one corrupt memory should
     * not cost the user every other memory. The caller decides what to surface.
     */
    fun requireClean(): List<T> {
        if (hasRejections) {
            throw PersistenceException("${rejected.size} stored record(s) could not be read: ${rejected.joinToString("; ")}")
        }
        return items
    }
}

// ---------------------------------------------------------------- memories

object MemoryCodec {

    fun encode(items: Collection<MemoryItem>): String = Json.write(
        json {
            "version" to PersistenceSchema.VERSION
            "kind" to PersistenceSchema.KIND_MEMORY
            "items" to JsonValue.Arr(items.map { encodeItem(it) })
        },
    )

    fun decode(text: String): DecodeResult<MemoryItem> {
        val root = readRoot(text, PersistenceSchema.KIND_MEMORY)
        val array = root.array("items")
            ?: throw PersistenceException("memory payload has no items array")
        val items = mutableListOf<MemoryItem>()
        val rejected = mutableListOf<String>()
        array.items.forEachIndexed { index, value ->
            val obj = value as? JsonValue.Obj
            if (obj == null) {
                rejected += "memory[$index]: not an object"
                return@forEachIndexed
            }
            try {
                items += decodeItem(obj)
            } catch (failure: IllegalArgumentException) {
                rejected += "memory[$index]: ${failure.message}"
            } catch (failure: PersistenceException) {
                rejected += "memory[$index]: ${failure.message}"
            }
        }
        return DecodeResult(items, rejected)
    }

    private fun encodeItem(item: MemoryItem): JsonValue.Obj = json {
        "id" to item.id
        "kind" to item.kind.name
        "text" to item.text
        "createdAt" to item.createdAtEpochMs
        "lastAccessedAt" to item.lastAccessedEpochMs
        "confidence" to item.confidence
        "source" to encodeSource(item.source)
        stringArray("tags", item.tags)
        putIfNotNull("subject", item.subject)
    }

    private fun decodeItem(obj: JsonValue.Obj): MemoryItem = MemoryItem(
        id = obj.requiredString("id"),
        kind = enumOf(obj.requiredString("kind"), MemoryKind.entries, "kind"),
        text = obj.requiredString("text"),
        createdAtEpochMs = obj.requiredLong("createdAt"),
        lastAccessedEpochMs = obj.requiredLong("lastAccessedAt"),
        confidence = obj.requiredDouble("confidence"),
        source = decodeSource(obj.obj("source") ?: throw PersistenceException("missing source")),
        tags = obj.array("tags")?.strings()?.toSet() ?: emptySet(),
        subject = obj.string("subject"),
    )

    private fun encodeSource(source: MemorySource): JsonValue.Obj = when (source) {
        is MemorySource.ExplicitUserStatement -> json {
            "type" to "explicit"
            putIfNotNull("conversationId", source.conversationId)
        }
        is MemorySource.InferredBehaviour -> json {
            "type" to "inferred"
            "signal" to source.signal
            "occurrences" to source.occurrences
        }
        is MemorySource.FeedbackDerived -> json {
            "type" to "feedback"
            "cardId" to source.cardId
        }
        is MemorySource.UserEdited -> json {
            "type" to "edited"
            putIfNotNull("editorNote", source.editorNote)
        }
    }

    private fun decodeSource(obj: JsonValue.Obj): MemorySource = when (val type = obj.requiredString("type")) {
        "explicit" -> MemorySource.ExplicitUserStatement(obj.string("conversationId"))
        "inferred" -> MemorySource.InferredBehaviour(
            signal = obj.requiredString("signal"),
            occurrences = obj.requiredLong("occurrences").toInt(),
        )
        "feedback" -> MemorySource.FeedbackDerived(obj.requiredString("cardId"))
        "edited" -> MemorySource.UserEdited(obj.string("editorNote"))
        else -> throw PersistenceException("unknown memory source '$type'")
    }
}

// ---------------------------------------------------------------- audit

object AuditCodec {

    fun encode(records: Collection<AuditRecord>): String = Json.write(
        json {
            "version" to PersistenceSchema.VERSION
            "kind" to PersistenceSchema.KIND_AUDIT
            "records" to JsonValue.Arr(records.map { encodeRecord(it) })
        },
    )

    fun decode(text: String): DecodeResult<AuditRecord> {
        val root = readRoot(text, PersistenceSchema.KIND_AUDIT)
        val array = root.array("records")
            ?: throw PersistenceException("audit payload has no records array")
        val records = mutableListOf<AuditRecord>()
        val rejected = mutableListOf<String>()
        array.items.forEachIndexed { index, value ->
            val obj = value as? JsonValue.Obj
            if (obj == null) {
                rejected += "audit[$index]: not an object"
                return@forEachIndexed
            }
            try {
                records += decodeRecord(obj)
            } catch (failure: IllegalArgumentException) {
                rejected += "audit[$index]: ${failure.message}"
            } catch (failure: PersistenceException) {
                rejected += "audit[$index]: ${failure.message}"
            }
        }
        return DecodeResult(records, rejected)
    }

    private fun encodeRecord(record: AuditRecord): JsonValue.Obj = json {
        "id" to record.id
        "at" to record.atEpochMs
        "actor" to record.actor.name
        putIfNotNull("capability", record.capability?.id)
        "action" to record.action
        "outcome" to record.outcome.name
        putIfNotNull("target", record.target)
        putIfNotNull("taskId", record.taskId)
        putIfNotNull("detail", record.detail)
        putIfNotNull("denialReason", record.denialReason?.name)
    }

    private fun decodeRecord(obj: JsonValue.Obj): AuditRecord = AuditRecord(
        id = obj.requiredString("id"),
        atEpochMs = obj.requiredLong("at"),
        actor = enumOf(obj.requiredString("actor"), Actor.entries, "actor"),
        capability = obj.string("capability")?.let { id ->
            Capability.fromId(id) ?: throw PersistenceException("unknown capability '$id'")
        },
        action = obj.requiredString("action"),
        outcome = enumOf(obj.requiredString("outcome"), AuditOutcome.entries, "outcome"),
        target = obj.string("target"),
        taskId = obj.string("taskId"),
        // AuditRecord's own invariant rejects credential-like detail, so a tampered store cannot
        // smuggle secrets back into the log.
        detail = obj.string("detail"),
        denialReason = obj.string("denialReason")?.let { name ->
            enumOf(name, DenialReason.entries, "denialReason")
        },
    )
}

// ---------------------------------------------------------------- tasks

object TaskCodec {

    fun encode(tasks: Collection<TaskRecord>): String = Json.write(
        json {
            "version" to PersistenceSchema.VERSION
            "kind" to PersistenceSchema.KIND_TASKS
            "tasks" to JsonValue.Arr(tasks.map { encodeTask(it) })
        },
    )

    fun decode(text: String): DecodeResult<TaskRecord> {
        val root = readRoot(text, PersistenceSchema.KIND_TASKS)
        val array = root.array("tasks")
            ?: throw PersistenceException("task payload has no tasks array")
        val tasks = mutableListOf<TaskRecord>()
        val rejected = mutableListOf<String>()
        array.items.forEachIndexed { index, value ->
            val obj = value as? JsonValue.Obj
            if (obj == null) {
                rejected += "task[$index]: not an object"
                return@forEachIndexed
            }
            try {
                tasks += decodeTask(obj)
            } catch (failure: IllegalArgumentException) {
                rejected += "task[$index]: ${failure.message}"
            } catch (failure: PersistenceException) {
                rejected += "task[$index]: ${failure.message}"
            }
        }
        return DecodeResult(tasks, rejected)
    }

    private fun encodeTask(task: TaskRecord): JsonValue.Obj = json {
        "id" to task.id
        "title" to task.title
        "state" to task.state.name
        "createdAt" to task.createdAtEpochMs
        "updatedAt" to task.updatedAtEpochMs
        "hasPerformedSideEffect" to task.hasPerformedSideEffect
        stringArray("completedSteps", task.completedSteps)
        putIfNotNull("interruptedAtStep", task.interruptedAtStep)
        putIfNotNull("failureReason", task.failureReason)
        "attempt" to task.attempt
    }

    private fun decodeTask(obj: JsonValue.Obj): TaskRecord = TaskRecord(
        id = obj.requiredString("id"),
        title = obj.requiredString("title"),
        state = enumOf(obj.requiredString("state"), TaskState.entries, "state"),
        createdAtEpochMs = obj.requiredLong("createdAt"),
        updatedAtEpochMs = obj.requiredLong("updatedAt"),
        hasPerformedSideEffect = obj.bool("hasPerformedSideEffect") ?: false,
        completedSteps = obj.array("completedSteps")?.strings() ?: emptyList(),
        interruptedAtStep = obj.string("interruptedAtStep"),
        failureReason = obj.string("failureReason"),
        attempt = obj.int("attempt") ?: 1,
    )

    /**
     * A task stored as RUNNING means the process died mid-execution: the step in flight may or may
     * not have completed. [TaskStateMachine][com.samaveta.android.core.core.TaskStateMachine.recover]
     * converts it to INTERRUPTED, which requires a user decision. This helper exists so callers do
     * not hand-roll that conversion (and do not accidentally auto-resume a side effect).
     */
    fun needsRecovery(task: TaskRecord): Boolean = task.state == TaskState.RUNNING
}

// ---------------------------------------------------------------- shared helpers

private fun readRoot(text: String, expectedKind: String): JsonValue.Obj {
    val root = try {
        Json.parseObject(text)
    } catch (failure: Exception) {
        throw PersistenceException("stored payload is not valid JSON: ${failure.message}", failure)
    }
    val version = root.int("version")
        ?: throw PersistenceException("stored payload has no schema version")
    if (version > PersistenceSchema.VERSION) {
        // Written by a newer build. Refusing is the only safe option: we would be guessing at fields.
        throw PersistenceException(
            "stored payload version $version is newer than this build supports (${PersistenceSchema.VERSION})",
        )
    }
    if (version < 1) throw PersistenceException("stored payload has invalid version $version")
    val kind = root.string("kind")
        ?: throw PersistenceException("stored payload has no kind")
    if (kind != expectedKind) {
        throw PersistenceException("expected a '$expectedKind' payload but found '$kind'")
    }
    return root
}

private fun JsonValue.Obj.requiredString(key: String): String =
    string(key)?.takeIf { it.isNotBlank() } ?: throw PersistenceException("missing '$key'")

private fun JsonValue.Obj.requiredLong(key: String): Long =
    long(key) ?: throw PersistenceException("missing '$key'")

private fun JsonValue.Obj.requiredDouble(key: String): Double =
    double(key) ?: throw PersistenceException("missing '$key'")

private fun <T : Enum<T>> enumOf(name: String, values: List<T>, field: String): T =
    values.firstOrNull { it.name == name }
        ?: throw PersistenceException("unknown $field '$name'")
