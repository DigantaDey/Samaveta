package com.samaveta.android.core.modelruntime

import com.samaveta.android.core.device.Accelerator
import com.samaveta.android.core.device.ThermalLevel
import com.samaveta.android.core.model.ModelBenchmarkResult
import com.samaveta.android.core.model.ModelCapability
import com.samaveta.android.core.model.ModelDescriptor
import com.samaveta.android.core.model.ModelFormat
import com.samaveta.android.core.model.ModelModule

/**
 * The inference boundary.
 *
 * SAMAVETA does not define model architectures; it defines the *contract* every runtime must
 * satisfy so that resource management, cancellation and verification are implemented once, in
 * [ModelResourceManager], rather than per-backend.
 *
 * Implementations will wrap LiteRT-LM / LiteRT / ONNX / native libraries. A deterministic
 * [FakeRuntime] exists for tests only and is never wired into a release build (enforced by the
 * repository hygiene check).
 */
interface ModelRuntime {
    /** Identifier reported in diagnostics, e.g. "litert-lm-0.9". */
    val runtimeId: String

    /**
     * Loads [module] for execution. Blocking; callers run it off the main thread.
     * Must throw [RuntimeLoadFailure] rather than returning a half-initialised handle.
     */
    fun load(module: ModelModule, accelerators: Set<Accelerator>): RuntimeHandle
}

/** A loaded model. Single-use lifetime is managed by the resource manager, not the caller. */
interface RuntimeHandle {
    val modelId: String

    /** Worst-case resident memory observed/estimated for this handle, in MB. */
    val residentRamMb: Int

    /**
     * Runs one generation. [tokenSink] receives streamed text so the UI can render progressively;
     * [cancel] is polled by the runtime between tokens so cancellation is prompt and bounded.
     */
    fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Double,
        cancel: CancellationToken,
        tokenSink: (String) -> Unit,
    ): GenerationResult

    /** Releases native resources. Must be idempotent. */
    fun close()
}

/** Cooperative cancellation. Polled by runtimes; never interrupts a thread mid-write. */
class CancellationToken {
    @Volatile
    private var cancelled = false

    val isCancelled: Boolean get() = cancelled

    fun cancel() {
        cancelled = true
    }

    /** Throws [CancelledException] when cancelled, so loops can bail out in one line. */
    fun throwIfCancelled() {
        if (cancelled) throw CancelledException()
    }
}

class CancelledException : Exception("inference cancelled")

sealed interface GenerationResult {
    data class Completed(
        val text: String,
        val promptTokens: Int,
        val outputTokens: Int,
        val firstTokenLatencyMs: Long,
        val totalLatencyMs: Long,
        val tokensPerSecond: Double,
    ) : GenerationResult

    data object Cancelled : GenerationResult

    data class Failed(val reason: GenerationFailure, val detail: String) : GenerationResult
}

enum class GenerationFailure(val userMessage: String) {
    OUT_OF_MEMORY("The device ran out of memory while generating a response."),
    CONTEXT_EXCEEDED("The conversation is longer than this model can hold."),
    RUNTIME_ERROR("The inference runtime reported an internal error."),
    INVALID_REQUEST("The request was malformed."),
    MODEL_UNLOADED("The model was unloaded before generation finished."),
    THERMAL_ABORT("Generation was stopped because the device became too hot."),
}

class RuntimeLoadFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What the manager knows about one model's runtime state. */
sealed interface ModelRuntimeState {
    data object NotLoaded : ModelRuntimeState

    data class Loading(val startedAtEpochMs: Long) : ModelRuntimeState

    data class Loaded(
        val handle: RuntimeHandle,
        val accelerators: Set<Accelerator>,
        val loadedAtEpochMs: Long,
        /** Number of callers currently holding the model. A model in use is never evicted. */
        val inUse: Int,
        val lastUsedEpochMs: Long,
        val generationsCompleted: Int,
    ) : ModelRuntimeState {
        val isBusy: Boolean get() = inUse > 0
    }

    data class Failed(val reason: String, val atEpochMs: Long) : ModelRuntimeState
}

/** Result of asking the manager for a model. */
sealed interface AcquireResult {
    data class Ready(val modelId: String, val handle: RuntimeHandle, val accelerators: Set<Accelerator>) : AcquireResult

    /** Could not be loaded now, but retrying later is reasonable (memory freed, download done). */
    data class Unavailable(val modelId: String, val reason: String, val retryable: Boolean) : AcquireResult

    /** A previous failure is still current; the caller should surface it, not retry blindly. */
    data class Failed(val modelId: String, val reason: String) : AcquireResult
}

/** Frees memory when the manager needs room. Implemented by the Android layer (trim caches). */
fun interface MemoryReclaimer {
    /** @return MB actually released, for diagnostics. */
    fun reclaim(targetMb: Int): Int
}

/**
 * Owns every loaded model.
 *
 * Responsibilities (all unit-tested):
 *  - lazy load on first use, reference counting, eviction of idle models only;
 *  - a hard memory budget: the sum of resident RAM of loaded models never exceeds [memoryBudgetMb];
 *  - "one large model at a time": a model above [LARGE_MODEL_FRACTION] of the budget evicts
 *    everything else first, because co-residency is what triggers LMKD kills;
 *  - no duplicate loading: concurrent acquires share one handle;
 *  - failure isolation: a failed load is recorded and surfaced, never silently retried in a loop;
 *  - thermal response: shedding idle models before the OS kills the process.
 *
 * Deliberately synchronous: the manager only *tracks* state. Blocking work happens on a background
 * dispatcher supplied by the caller, which keeps this class deterministically testable.
 */
class ModelResourceManager(
    private val runtime: ModelRuntime,
    private val memoryBudgetMb: Int,
    private val reclaimer: MemoryReclaimer = MemoryReclaimer { 0 },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxLoadedModels: Int = 2,
) {
    init {
        require(memoryBudgetMb >= MIN_BUDGET_MB) {
            "memory budget of $memoryBudgetMb MB is below the ${MIN_BUDGET_MB} MB floor; inference is not viable"
        }
        require(maxLoadedModels in 1..4) { "maxLoadedModels out of range" }
    }

    private val states = LinkedHashMap<String, ModelRuntimeState>()

    fun stateOf(modelId: String): ModelRuntimeState = states[modelId] ?: ModelRuntimeState.NotLoaded

    fun loadedModelIds(): List<String> =
        states.filterValues { it is ModelRuntimeState.Loaded }.keys.toList()

    /** Sum of resident RAM across loaded models. */
    fun residentRamMb(): Int = states.values.sumOf { state ->
        (state as? ModelRuntimeState.Loaded)?.handle?.residentRamMb ?: 0
    }

    fun availableBudgetMb(): Int = memoryBudgetMb - residentRamMb()

    /**
     * Ensures [module] is loaded and marks it in use. Callers must [release] in a `finally`.
     *
     * [requiredCapability] is re-checked against the module so a stale registry entry cannot run a
     * model for the wrong task.
     */
    fun acquire(
        module: ModelModule,
        accelerators: Set<Accelerator> = setOf(Accelerator.CPU),
        requiredCapability: ModelCapability? = null,
    ): AcquireResult {
        val modelId = module.id

        if (requiredCapability != null && requiredCapability !in module.capabilities) {
            return AcquireResult.Unavailable(
                modelId,
                "Model '$modelId' does not provide ${requiredCapability.name}.",
                retryable = false,
            )
        }

        when (val existing = stateOf(modelId)) {
            is ModelRuntimeState.Loaded -> {
                states[modelId] = existing.copy(inUse = existing.inUse + 1, lastUsedEpochMs = clock())
                return AcquireResult.Ready(modelId, existing.handle, existing.accelerators)
            }

            is ModelRuntimeState.Failed -> return AcquireResult.Failed(modelId, existing.reason)

            is ModelRuntimeState.Loading -> return AcquireResult.Unavailable(
                modelId,
                "Model '$modelId' is already loading.",
                retryable = true,
            )

            ModelRuntimeState.NotLoaded -> Unit
        }

        if (module.peakRamRequirementMb > memoryBudgetMb) {
            return AcquireResult.Unavailable(
                modelId,
                "Model '$modelId' needs ${module.peakRamRequirementMb} MB, which exceeds this " +
                    "device's ${memoryBudgetMb} MB inference budget on its own.",
                retryable = false,
            )
        }

        // A very large model gets the device to itself.
        val isLarge = module.peakRamRequirementMb > (memoryBudgetMb * LARGE_MODEL_FRACTION)
        if (isLarge) {
            val evicted = unloadIdle { true }
            if (evicted.anyBlocked) {
                return AcquireResult.Unavailable(
                    modelId,
                    "Model '$modelId' needs the whole memory budget, but another model is currently in use.",
                    retryable = true,
                )
            }
        }

        // Make room: evict idle models (LRU first) and ask the OS to reclaim caches.
        while (residentRamMb() + module.peakRamRequirementMb > memoryBudgetMb) {
            val evicted = unloadIdle { true }
            if (!evicted.anyEvicted && evicted.anyBlocked) {
                return AcquireResult.Unavailable(
                    modelId,
                    "Not enough memory for '$modelId' (${module.peakRamRequirementMb} MB needed, " +
                        "${availableBudgetMb()} MB available, other models in use).",
                    retryable = true,
                )
            }
            if (!evicted.anyEvicted) {
                val target = module.peakRamRequirementMb - availableBudgetMb()
                val freed = reclaimer.reclaim(target)
                if (freed < target) {
                    return AcquireResult.Unavailable(
                        modelId,
                        "Not enough memory for '$modelId': ${availableBudgetMb()} MB available after reclaiming caches.",
                        retryable = true,
                    )
                }
            }
        }

        while (loadedModelIds().size >= maxLoadedModels) {
            val evicted = unloadIdle { true }
            if (!evicted.anyEvicted) {
                return AcquireResult.Unavailable(
                    modelId,
                    "The model limit ($maxLoadedModels) is reached and every loaded model is in use.",
                    retryable = true,
                )
            }
        }

        states[modelId] = ModelRuntimeState.Loading(clock())
        return try {
            val handle = runtime.load(module, accelerators)
            val now = clock()
            states[modelId] = ModelRuntimeState.Loaded(
                handle = handle,
                accelerators = accelerators,
                loadedAtEpochMs = now,
                inUse = 1,
                lastUsedEpochMs = now,
                generationsCompleted = 0,
            )
            AcquireResult.Ready(modelId, handle, accelerators)
        } catch (e: RuntimeLoadFailure) {
            states[modelId] = ModelRuntimeState.Failed(e.message ?: "load failed", clock())
            AcquireResult.Failed(modelId, e.message ?: "load failed")
        } catch (e: Exception) {
            states[modelId] = ModelRuntimeState.Failed(e.message ?: e::class.simpleName.orEmpty(), clock())
            AcquireResult.Failed(modelId, e.message ?: "unexpected failure loading the model")
        }
    }

    /** Drops one reference; the model becomes evictable when the count reaches zero. */
    fun release(modelId: String) {
        val state = states[modelId] as? ModelRuntimeState.Loaded ?: return
        val remaining = (state.inUse - 1).coerceAtLeast(0)
        states[modelId] = state.copy(inUse = remaining, lastUsedEpochMs = clock())
    }

    /** Runs one generation against a model, updating usage statistics. */
    fun generate(
        modelId: String,
        prompt: String,
        maxTokens: Int = 512,
        temperature: Double = 0.7,
        cancel: CancellationToken = CancellationToken(),
        tokenSink: (String) -> Unit = {},
    ): GenerationResult {
        require(maxTokens in 1..8_192) { "maxTokens out of range" }
        require(temperature in 0.0..2.0) { "temperature out of range" }

        val state = states[modelId] as? ModelRuntimeState.Loaded
            ?: return GenerationResult.Failed(
                GenerationFailure.MODEL_UNLOADED,
                "Model '$modelId' is not loaded.",
            )

        if (cancel.isCancelled) return GenerationResult.Cancelled

        val result = try {
            state.handle.generate(prompt, maxTokens, temperature, cancel, tokenSink)
        } catch (_: CancelledException) {
            GenerationResult.Cancelled
        } catch (e: Exception) {
            GenerationResult.Failed(GenerationFailure.RUNTIME_ERROR, e.message ?: "runtime error")
        }

        val current = states[modelId] as? ModelRuntimeState.Loaded
        if (current != null) {
            states[modelId] = current.copy(
                lastUsedEpochMs = clock(),
                generationsCompleted = current.generationsCompleted +
                    if (result is GenerationResult.Completed) 1 else 0,
            )
        }
        return result
    }

    /**
     * Explicit unload. Refuses to close a model that is in use — closing a handle under a running
     * generation would crash the process rather than fail gracefully.
     */
    fun unload(modelId: String, force: Boolean = false): Boolean {
        val state = states[modelId] as? ModelRuntimeState.Loaded ?: return false
        if (state.isBusy && !force) return false
        state.handle.close()
        states.remove(modelId)
        return true
    }

    /** Sheds idle models in least-recently-used order. Returns what happened, for diagnostics. */
    fun unloadIdle(predicate: (LoadedInfo) -> Boolean = { true }): EvictionReport {
        val candidates = states.entries
            .mapNotNull { (id, state) ->
                (state as? ModelRuntimeState.Loaded)?.let { LoadedInfo(id, it) }
            }
            .sortedBy { it.state.lastUsedEpochMs }

        var evicted = 0
        var blocked = false
        for (candidate in candidates) {
            if (!predicate(candidate)) continue
            if (candidate.state.isBusy) {
                blocked = true
                continue
            }
            candidate.state.handle.close()
            states.remove(candidate.modelId)
            evicted++
        }
        return EvictionReport(evicted, blocked)
    }

    /**
     * Response to thermal pressure. Idle models are dropped immediately; a model in use is only
     * interrupted at CRITICAL, because losing a half-written answer is better than a thermal
     * shutdown but worse than a short delay.
     */
    fun onThermalLevel(level: ThermalLevel): ThermalAction {
        val action = when {
            level.severity >= ThermalLevel.CRITICAL.severity -> ThermalAction.SHED_ALL_AND_ABORT
            level.severity >= ThermalLevel.SEVERE.severity -> ThermalAction.SHED_IDLE
            level == ThermalLevel.MODERATE -> ThermalAction.SHED_PREFETCHED
            else -> ThermalAction.NONE
        }
        when (action) {
            ThermalAction.SHED_IDLE, ThermalAction.SHED_ALL_AND_ABORT -> unloadIdle { true }
            ThermalAction.SHED_PREFETCHED -> unloadIdle { it.state.generationsCompleted == 0 }
            ThermalAction.NONE -> Unit
        }
        return action
    }

    /** Full shutdown: closes everything, including in-use handles (process teardown only). */
    fun shutdown() {
        states.values.forEach { state ->
            (state as? ModelRuntimeState.Loaded)?.handle?.close()
        }
        states.clear()
    }

    /** Clears recorded load failures so the user can retry after fixing the cause. */
    fun clearFailures() {
        states.entries.removeIf { it.value is ModelRuntimeState.Failed }
    }

    data class LoadedInfo(val modelId: String, val state: ModelRuntimeState.Loaded)

    data class EvictionReport(val evictedCount: Int, val anyBlocked: Boolean) {
        val anyEvicted: Boolean get() = evictedCount > 0
    }

    enum class ThermalAction { NONE, SHED_PREFETCHED, SHED_IDLE, SHED_ALL_AND_ABORT }

    companion object {
        /** Below this, on-device inference cannot work reliably; refuse rather than thrash. */
        const val MIN_BUDGET_MB = 512

        /** A model above this share of the budget runs alone. */
        const val LARGE_MODEL_FRACTION = 0.4
    }
}

/**
 * Deterministic runtime for tests. It is intentionally *not* wired into the app: the release guard
 * rejects `FakeRuntime(` anywhere under `app/src/main`.
 */
class FakeRuntime(
    private val residentRamMb: (ModelModule) -> Int = { it.peakRamRequirementMb },
    private val failureFor: Set<String> = emptySet(),
    private val tokens: List<String> = listOf("Hello", " from", " SAMAVETA"),
) : ModelRuntime {

    override val runtimeId: String = "fake-runtime-0"

    val closedModelIds = mutableListOf<String>()

    override fun load(module: ModelModule, accelerators: Set<Accelerator>): RuntimeHandle {
        if (module.id in failureFor) throw RuntimeLoadFailure("simulated failure loading ${module.id}")
        val resident = residentRamMb(module)
        return object : RuntimeHandle {
            override val modelId: String = module.id
            override val residentRamMb: Int = resident
            private var closed = false

            override fun generate(
                prompt: String,
                maxTokens: Int,
                temperature: Double,
                cancel: CancellationToken,
                tokenSink: (String) -> Unit,
            ): GenerationResult {
                val emitted = mutableListOf<String>()
                for (token in tokens) {
                    if (cancel.isCancelled) return GenerationResult.Cancelled
                    if (emitted.size >= maxTokens) break
                    emitted += token
                    tokenSink(token)
                }
                val text = emitted.joinToString("")
                return GenerationResult.Completed(
                    text = text,
                    promptTokens = prompt.length / 4,
                    outputTokens = emitted.size,
                    firstTokenLatencyMs = 12,
                    totalLatencyMs = 12L * emitted.size,
                    tokensPerSecond = (emitted.size * 1_000.0 / (12L * emitted.size)).coerceAtLeast(0.1),
                )
            }

            override fun close() {
                // Idempotent: closing twice must not double-count in diagnostics.
                if (!closed) {
                    closed = true
                    closedModelIds += module.id
                }
            }
        }
    }
}

/** Helper used by tests and by the registry bridge. */
internal fun ModelDescriptor.toModule(): ModelModule = ModelModule(
    id = id,
    displayName = displayName,
    version = version,
    capabilities = capabilities,
    peakRamRequirementMb = peakRamRequirementMb,
    storageRequirementMb = storageRequirementMb,
    format = format,
    quantization = quantization,
    contextWindowTokens = contextWindowTokens,
    benchmark = benchmark,
)

/** Convenience for diagnostics overlays. */
fun ModelBenchmarkResult.summaryLine(): String =
    "$deviceModel · ${coldLoadMs}ms load · ${firstTokenLatencyMs}ms first token · " +
        (tokensPerSecond?.let { "%.1f tok/s".format(it) } ?: "tok/s not measured")
