package com.samaveta.android.core.laya

/**
 * SUPERSEDED — this is the **PROPOSED planner contract**, kept for historical context and for the
 * tests that pin its validation behaviour. It is **not** LAYA's interface.
 *
 * The real engine (github.com/NandhaKishorM/laya) is a typed decision model: it answers
 * `choice` / `score` / `noul` questions about a state and returns calibrated probabilities. It does
 * not emit plans, steps, capability requests or tool calls. The integration SAMAVETA actually uses
 * lives in `core/.../laya/v1/` and is documented in `LAYA_INTEGRATION.md` §1.1.
 *
 * Nothing in this file may be wired to a server, and no UI may read it. Planning belongs to
 * SAMAVETA (`orchestration/PlanGate`, `PlanRunner`), not to the external engine.
 */

import com.samaveta.android.core.device.Accelerator
import com.samaveta.android.core.device.DeviceCapabilities
import com.samaveta.android.core.device.DeviceProfile
import com.samaveta.android.core.device.ThermalLevel
import com.samaveta.android.core.json.Json
import com.samaveta.android.core.json.JsonValue
import com.samaveta.android.core.json.json
import com.samaveta.android.core.model.ModelCapability
import com.samaveta.android.core.model.ModelDescriptor
import com.samaveta.android.core.model.ModelRegistry

/**
 * LAYA INTEGRATION CONTRACT — DESIGNED, NOT YET SUPPLIED BY LAYA'S DEVELOPER.
 *
 * ---------------------------------------------------------------------------------------------
 * STATUS: BLOCKED (production). The actual LAYA interface has not been provided.
 * ---------------------------------------------------------------------------------------------
 *
 * Per the product directive, SAMAVETA must not invent LAYA's SDK classes, function names or data
 * structures, and must not reimplement LAYA's decision engine internally. This file therefore
 * defines only:
 *
 *   1. The *lifecycle and transport* the driver (`LayaAdapter`) needs — deliberate,
 *      SAMAVETA-owned surface (timeouts, retries, cancellation, error taxonomy).
 *   2. An explicit, versioned *wire contract* ([LayaWireContract]) that SAMAVETA proposes and that
 *      must be reconciled against LAYA's real interface before production use.
 *
 * Everything in the wire contract is clearly labelled as PROPOSED. When LAYA's developer supplies
 * the real contract, only [LayaRequestMapper] and [LayaResponseMapper] should need to change;
 * nothing outside this package should be affected.
 *
 * See docs/LAYA_INTEGRATION.md for the reconciliation checklist and the exact fields that must be
 * confirmed.
 */

/** Version of the *transport* contract SAMAVETA speaks to its LAYA driver. */
const val LAYA_TRANSPORT_VERSION = "0.1.0-samaveta"

/**
 * The proposed LAYA decision-request/response wire schema version.
 *
 * PROPOSED — must be replaced by the version LAYA's developer actually publishes.
 */
const val LAYA_WIRE_SCHEMA_VERSION = "2026-11-proposed-1"

/** A user request that LAYA must reason about. */
data class LayaRequest(
    val requestId: String,
    val sessionId: String,
    val utterance: String,
    val context: LayaContext,
    val device: LayaDeviceSnapshot,
    val modelInventory: List<LayaModelInventoryEntry>,
    val capabilityInventory: Set<String>,
    val deadlineEpochMs: Long,
    val locale: String,
)

/**
 * Conversation/session context. Deliberately minimal and explicitly bounded: SAMAVETA sends only
 * what LAYA needs, and the user can inspect the exact payload (network transparency requirement).
 */
data class LayaContext(
    val recentTurns: List<LayaTurn>,
    val activeTaskId: String?,
    val userConfirmedIntents: List<String>,
) {
    init {
        require(recentTurns.size <= MAX_TURNS) { "context limited to $MAX_TURNS turns, got ${recentTurns.size}" }
    }

    companion object {
        const val MAX_TURNS = 12
    }
}

data class LayaTurn(val role: TurnRole, val text: String, val atEpochMs: Long) {
    init {
        require(text.length <= 8_000) { "turn text exceeds 8000 characters" }
    }
}

enum class TurnRole { USER, ASSISTANT, SYSTEM }

/**
 * Device conditions SAMAVETA shares with LAYA so it can make realistic decisions.
 * SAMAVETA remains the authority on whether a decision is executable.
 */
data class LayaDeviceSnapshot(
    val memoryTier: String,
    val totalRamMb: Int,
    val availableRamMb: Int,
    val availableStorageMb: Long,
    val thermalLevel: ThermalLevel,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val availableAccelerators: Set<Accelerator>,
    val androidApiLevel: Int,
    val abis: Set<String>,
    val offline: Boolean,
)

data class LayaModelInventoryEntry(
    val modelId: String,
    val installed: Boolean,
    val available: Boolean,
    val capabilities: Set<ModelCapability>,
    val modalitiesIn: Set<String>,
    val peakRamMb: Int,
    val storageMb: Long,
    val accelerators: Set<Accelerator>,
    val contextWindowTokens: Int?,
    val unavailabilityReason: String?,
)

/** What LAYA is allowed to return: a decision, options, or a clarification request. */
sealed interface LayaDecision {
    val requestId: String
    val reasoningSummary: String?
}

data class LayaPlan(
    override val requestId: String,
    override val reasoningSummary: String?,
    val steps: List<LayaPlanStep>,
    val requestedCapabilities: Set<String>,
    val requiresUserConfirmation: Boolean,
) : LayaDecision {
    init {
        require(steps.isNotEmpty()) { "a plan must contain at least one step" }
    }
}

data class LayaPlanStep(
    val index: Int,
    val kind: StepKind,
    val modelId: String?,
    val capability: String?,
    val instruction: String,
    val expectedOutputDescription: String?,
) {
    init {
        require(index >= 0) { "step index must be non-negative" }
        require(instruction.isNotBlank()) { "step instruction must not be blank" }
        when (kind) {
            StepKind.MODEL_INFERENCE ->
                requireNotNull(modelId) { "MODEL_INFERENCE step requires a modelId" }
            StepKind.CAPABILITY_INVOCATION ->
                requireNotNull(capability) { "CAPABILITY_INVOCATION step requires a capability" }
            else -> Unit
        }
    }
}

enum class StepKind {
    MODEL_INFERENCE,
    PERCEPTION_OCR,
    PERCEPTION_VISION,
    CAPABILITY_INVOCATION,
    INFORMATION_RETRIEVAL,
    BROWSER_TASK,
    MEMORY_READ,
    MEMORY_WRITE,
    RESPOND,
}

data class LayaClarification(
    override val requestId: String,
    override val reasoningSummary: String?,
    val question: String,
    val options: List<String>,
) : LayaDecision

data class LayaNoPlan(
    override val requestId: String,
    override val reasoningSummary: String?,
    val reason: String,
) : LayaDecision

/** Failure taxonomy. Every case must surface to diagnostics and to the user in plain language. */
sealed class LayaFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The endpoint is not configured at all — the normal state until LAYA ships its contract. */
    class NotConfigured :
        LayaFailure("LAYAY is not configured: no production LAYA contract or endpoint is available.")

    class Unavailable(detail: String) :
        LayaFailure("LAYAY is unavailable: $detail")

    class Timeout(val timeoutMs: Long) :
        LayaFailure("LAYAY did not respond within ${timeoutMs}ms.")

    class Transport(detail: String, cause: Throwable?) :
        LayaFailure("LAYAY transport error: $detail", cause)

    class MalformedResponse(detail: String) :
        LayaFailure("LAYAY returned a response that does not match the contract: $detail")

    class UnsupportedVersion(val version: String, val supported: String) :
        LayaFailure("LAYAY contract version '$version' is not supported (expected '$supported').")

    class Rejected(detail: String) : LayaFailure("LAYAY rejected the request: $detail")
}

/** Result of one adapter invocation. */
sealed interface LayaOutcome {
    data class Success(val decision: LayaDecision, val latencyMs: Long) : LayaOutcome

    data class Failed(val failure: LayaFailure, val latencyMs: Long) : LayaOutcome
}

/**
 * The transport abstraction SAMAVETA owns. A production implementation is *not* provided because
 * LAYA's interface is unknown; [MockLayaTransport] exists for integration testing only and is
 * never wired into a release build (see `LayaIntegrationState`).
 */
interface LayaTransport {
    val kind: TransportKind

    /** Sends a serialized request and returns the raw response body, or throws [LayaFailure]. */
    suspend fun exchange(requestJson: String, timeoutMs: Long): String
}

enum class TransportKind { HTTP_JSON, LOCAL_IPC, SDK_LIBRARY, MOCK }

/** Integration status, surfaced in developer diagnostics. */
enum class LayaIntegrationState {
    /** No contract supplied by LAYA's developer. The documented, expected state today. */
    NOT_CONFIGURED,

    /** Contract supplied and configuration present, but not yet verified end to end. */
    CONFIGURED_UNVERIFIED,

    /** Verified against the real LAYA implementation. */
    VERIFIED,

    /** Configured but failing health checks. */
    DEGRADED,

    /** Mock adapter active. Release builds must refuse to operate in this state. */
    MOCK_TEST_ONLY,
    ;

    val isProductionOperational: Boolean get() = this == VERIFIED

    val userFacingLabel: String
        get() = when (this) {
            NOT_CONFIGURED -> "LAYA not connected"
            CONFIGURED_UNVERIFIED -> "LAYA configured, unverified"
            VERIFIED -> "LAYA connected"
            DEGRADED -> "LAYA degraded"
            MOCK_TEST_ONLY -> "LAYA mock (testing only)"
        }
}

/**
 * PROPOSED wire contract. Field names here are SAMAVETA's proposal; they must be reconciled with
 * LAYA's published schema before this mapping is used against a real LAYA deployment.
 */
object LayaWireContract {

    private const val FIELD_VERSION = "schema_version"
    private const val FIELD_REQUEST_ID = "request_id"
    private const val FIELD_TYPE = "type"

    fun encodeRequest(request: LayaRequest): String = Json.write(json {
        FIELD_VERSION to LAYA_WIRE_SCHEMA_VERSION
        FIELD_TYPE to "decision_request"
        FIELD_REQUEST_ID to request.requestId
        "session_id" to request.sessionId
        "utterance" to request.utterance
        "locale" to request.locale
        "deadline_epoch_ms" to request.deadlineEpochMs
        "context" to json {
            "active_task_id" to (request.context.activeTaskId ?: JsonValue.Null)
            "user_confirmed_intents" to JsonValue.Arr(request.context.userConfirmedIntents.map { JsonValue.Str(it) })
            "recent_turns" to JsonValue.Arr(request.context.recentTurns.map { turn ->
                json {
                    "role" to turn.role.name.lowercase()
                    "text" to turn.text
                    "at_epoch_ms" to turn.atEpochMs
                }
            })
        }
        "device" to json {
            val d = request.device
            "memory_tier" to d.memoryTier
            "total_ram_mb" to d.totalRamMb
            "available_ram_mb" to d.availableRamMb
            "available_storage_mb" to d.availableStorageMb
            "thermal_level" to d.thermalLevel.name.lowercase()
            "battery_percent" to d.batteryPercent
            "charging" to d.isCharging
            "accelerators" to JsonValue.Arr(d.availableAccelerators.map { JsonValue.Str(it.name.lowercase()) })
            "android_api_level" to d.androidApiLevel
            "abis" to JsonValue.Arr(d.abis.sorted().map { JsonValue.Str(it) })
            "offline" to d.offline
        }
        "models" to JsonValue.Arr(request.modelInventory.map { m ->
            json {
                "model_id" to m.modelId
                "installed" to m.installed
                "available" to m.available
                "capabilities" to JsonValue.Arr(m.capabilities.map { JsonValue.Str(it.name.lowercase()) })
                "input_modalities" to JsonValue.Arr(m.modalitiesIn.map { JsonValue.Str(it.lowercase()) })
                "peak_ram_mb" to m.peakRamMb
                "storage_mb" to m.storageMb
                "accelerators" to JsonValue.Arr(m.accelerators.map { JsonValue.Str(it.name.lowercase()) })
                "context_window_tokens" to (m.contextWindowTokens?.let { JsonValue.of(it) } ?: JsonValue.Null)
                "unavailable_reason" to (m.unavailabilityReason?.let { JsonValue.Str(it) } ?: JsonValue.Null)
            }
        })
        "capabilities" to JsonValue.Arr(request.capabilityInventory.sorted().map { JsonValue.Str(it) })
    })

    /** Parses a LAYA response, mapping schema violations onto [LayaFailure.MalformedResponse]. */
    fun decodeResponse(requestId: String, body: String): LayaDecision {
        val root = Json.parseOrNull(body)
            ?: throw LayaFailure.MalformedResponse("response body is not valid JSON")
        val obj = root as? JsonValue.Obj
            ?: throw LayaFailure.MalformedResponse("response must be a JSON object")

        val version = obj.string(FIELD_VERSION)
            ?: throw LayaFailure.MalformedResponse("missing $FIELD_VERSION")
        if (version != LAYA_WIRE_SCHEMA_VERSION) {
            throw LayaFailure.UnsupportedVersion(version, LAYA_WIRE_SCHEMA_VERSION)
        }
        val returnedId = obj.string(FIELD_REQUEST_ID)
            ?: throw LayaFailure.MalformedResponse("missing $FIELD_REQUEST_ID")
        if (returnedId != requestId) {
            // Correlated responses are mandatory: acting on a decision for a different request is
            // exactly how an agent performs the wrong action.
            throw LayaFailure.MalformedResponse("response request_id '$returnedId' does not match '$requestId'")
        }
        val reasoning = obj.string("reasoning_summary")
        return when (val type = obj.string(FIELD_TYPE)) {
            "plan" -> {
                val stepsArr = obj.array("steps")
                    ?: throw LayaFailure.MalformedResponse("plan response has no steps array")
                if (stepsArr.items.isEmpty()) throw LayaFailure.MalformedResponse("plan has zero steps")
                val steps = stepsArr.items.mapIndexed { i, raw ->
                    val s = raw as? JsonValue.Obj
                        ?: throw LayaFailure.MalformedResponse("step $i is not an object")
                    val kindName = s.string("kind")?.uppercase()
                        ?: throw LayaFailure.MalformedResponse("step $i missing kind")
                    val kind = try {
                        StepKind.valueOf(kindName)
                    } catch (_: IllegalArgumentException) {
                        throw LayaFailure.MalformedResponse("step $i has unknown kind '$kindName'")
                    }
                    LayaPlanStep(
                        index = s.int("index") ?: i,
                        kind = kind,
                        modelId = s.string("model_id"),
                        capability = s.string("capability"),
                        instruction = s.string("instruction")
                            ?: throw LayaFailure.MalformedResponse("step $i missing instruction"),
                        expectedOutputDescription = s.string("expected_output"),
                    )
                }
                LayaPlan(
                    requestId = requestId,
                    reasoningSummary = reasoning,
                    steps = steps,
                    requestedCapabilities = obj.strings("requested_capabilities").toSet(),
                    requiresUserConfirmation = obj.bool("requires_user_confirmation") ?: false,
                )
            }

            "clarification" -> {
                val question = obj.string("question")
                    ?: throw LayaFailure.MalformedResponse("clarification missing question")
                LayaClarification(requestId, reasoning, question, obj.strings("options"))
            }

            "no_plan" -> LayaNoPlan(
                requestId,
                reasoning,
                obj.string("reason") ?: "no reason supplied",
            )

            null -> throw LayaFailure.MalformedResponse("missing $FIELD_TYPE")
            else -> throw LayaFailure.MalformedResponse("unknown response type '$type'")
        }
    }

    fun encodeError(requestId: String, failure: LayaFailure): String = Json.write(json {
        FIELD_VERSION to LAYA_WIRE_SCHEMA_VERSION
        FIELD_TYPE to "client_error"
        FIELD_REQUEST_ID to requestId
        "error_class" to failure::class.simpleName.orEmpty()
        "detail" to (failure.message ?: "")
    })
}

/** Builds LAYA requests from SAMAVETA state. Pure mapping, no I/O. */
object LayaRequestMapper {

    fun deviceSnapshot(
        device: DeviceProfile,
        capabilities: DeviceCapabilities,
        thermal: ThermalLevel,
        batteryPercent: Int,
        isCharging: Boolean,
        offline: Boolean,
    ): LayaDeviceSnapshot = LayaDeviceSnapshot(
        memoryTier = device.memoryTier().name.lowercase(),
        totalRamMb = device.totalRamMb,
        availableRamMb = device.availableRamMb,
        availableStorageMb = device.availableStorageMb,
        thermalLevel = thermal,
        batteryPercent = batteryPercent,
        isCharging = isCharging,
        availableAccelerators = capabilities.availableAccelerators(),
        androidApiLevel = device.androidApiLevel,
        abis = device.supportedAbis,
        offline = offline,
    )

    /**
     * Publishes the inventory. `availabilityOf` is supplied by the caller (i.e. the registry's
     * admission check) so that the model list LAYA sees is exactly what SAMAVETA can execute.
     */
    fun modelInventory(
        registry: ModelRegistry,
        availabilityOf: (ModelDescriptor) -> AdmissionSummary,
    ): List<LayaModelInventoryEntry> = registry.all().map { entry ->
        val d = entry.descriptor
        val summary = availabilityOf(d)
        LayaModelInventoryEntry(
            modelId = d.id,
            installed = entry.isUsable,
            available = summary.available,
            capabilities = d.capabilities,
            modalitiesIn = d.inputModalities.map { it.name.lowercase() }.toSet(),
            peakRamMb = d.peakRamRequirementMb,
            storageMb = d.storageRequirementMb,
            accelerators = d.requiredAccelerators + d.optionalAccelerators,
            contextWindowTokens = d.contextWindowTokens,
            unavailabilityReason = summary.reason,
        )
    }
}

data class AdmissionSummary(val available: Boolean, val reason: String?)
