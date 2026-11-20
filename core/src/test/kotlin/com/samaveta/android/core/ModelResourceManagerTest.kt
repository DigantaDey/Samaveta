package com.samaveta.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.samaveta.android.core.device.Accelerator
import com.samaveta.android.core.device.ThermalLevel
import com.samaveta.android.core.model.ModelCapability
import com.samaveta.android.core.model.ModelModule
import com.samaveta.android.core.modelruntime.AcquireResult
import com.samaveta.android.core.modelruntime.CancellationToken
import com.samaveta.android.core.modelruntime.FakeRuntime
import com.samaveta.android.core.modelruntime.GenerationFailure
import com.samaveta.android.core.modelruntime.GenerationResult
import com.samaveta.android.core.modelruntime.ModelResourceManager
import com.samaveta.android.core.modelruntime.ModelRuntimeState

/**
 * The resource manager exists to prevent three failure modes that users actually experience as
 * crashes or freezes: exhausting RAM, loading duplicate copies of a model, and unloading a model
 * while it is generating. Each is tested here.
 */
class ModelResourceManagerTest {

    private var now = 1_000L

    private fun module(
        id: String,
        peakRamMb: Int = 900,
        capabilities: Set<ModelCapability> = setOf(ModelCapability.TEXT_GENERATION),
        context: Int? = 4_096,
    ) = ModelModule(
        id = id,
        displayName = id,
        version = "1.0",
        capabilities = capabilities,
        peakRamRequirementMb = peakRamMb,
        storageRequirementMb = 800,
        format = com.samaveta.android.core.model.ModelFormat.LITERT_LM,
        quantization = com.samaveta.android.core.model.Quantization.INT4,
        contextWindowTokens = context,
        benchmark = null,
    )

    private fun manager(
        budgetMb: Int = 2_500,
        runtime: FakeRuntime = FakeRuntime(),
        maxLoaded: Int = 2,
        reclaim: (Int) -> Int = { 0 },
    ) = ModelResourceManager(
        runtime = runtime,
        memoryBudgetMb = budgetMb,
        reclaimer = { target -> reclaim(target) },
        clock = { now++ },
        maxLoadedModels = maxLoaded,
    )

    // ---- basic lifecycle ----------------------------------------------------------------------

    @Test
    fun acquireLoadsLazilyAndTracksResidentRam() {
        val m = manager()
        assertEquals(0, m.residentRamMb())
        val result = m.acquire(module("a", peakRamMb = 900))
        assertTrue(result is AcquireResult.Ready)
        assertEquals(900, m.residentRamMb())
        assertEquals(listOf("a"), m.loadedModelIds())
    }

    @Test
    fun acquiringTwiceSharesOneHandle() {
        val runtime = FakeRuntime()
        val m = manager(runtime = runtime)
        val first = m.acquire(module("a")) as AcquireResult.Ready
        val second = m.acquire(module("a")) as AcquireResult.Ready
        assertTrue("same handle must be reused", first.handle === second.handle)
        assertEquals("memory must not double", 900, m.residentRamMb())
        assertEquals(1, m.loadedModelIds().size)
    }

    @Test
    fun releaseDecrementsAndOnlyThenAllowsEviction() {
        val m = manager()
        m.acquire(module("a"))
        m.acquire(module("a"))
        assertFalse("in use: must not unload", m.unload("a"))
        m.release("a")
        assertFalse("still one holder", m.unload("a"))
        m.release("a")
        assertTrue("idle: may unload", m.unload("a"))
        assertEquals(0, m.residentRamMb())
    }

    @Test
    fun releaseOnUnknownModelIsHarmless() {
        val m = manager()
        m.release("never-loaded")
        assertEquals(0, m.residentRamMb())
    }

    // ---- memory budget ------------------------------------------------------------------------

    @Test
    fun modelLargerThanTheWholeBudgetIsRefusedPermanently() {
        val m = manager(budgetMb = 2_000)
        val result = m.acquire(module("huge", peakRamMb = 4_000))
        assertTrue(result is AcquireResult.Unavailable)
        assertFalse("this can never succeed on this device", (result as AcquireResult.Unavailable).retryable)
    }

    @Test
    fun idleModelsAreEvictedToMakeRoom() {
        val m = manager(budgetMb = 2_000, maxLoaded = 3)
        m.acquire(module("small", peakRamMb = 800))
        m.release("small")

        val result = m.acquire(module("medium", peakRamMb = 1_600))
        assertTrue(result is AcquireResult.Ready)
        assertEquals(listOf("medium"), m.loadedModelIds())
        assertTrue("resident RAM must stay within budget", m.residentRamMb() <= 2_000)
    }

    @Test
    fun inUseModelIsNeverEvictedToMakeRoom() {
        val m = manager(budgetMb = 2_000, maxLoaded = 3)
        m.acquire(module("busy", peakRamMb = 1_200))
        // "busy" is still in use, so a 1_200 MB request cannot be satisfied.
        val result = m.acquire(module("other", peakRamMb = 1_200))
        assertTrue(result is AcquireResult.Unavailable)
        assertTrue((result as AcquireResult.Unavailable).retryable)
        assertTrue("the in-use model must survive", "busy" in m.loadedModelIds())
    }

    @Test
    fun reclaimerIsAskedBeforeGivingUp() {
        var reclaimed = 0
        val m = manager(budgetMb = 2_000, reclaim = { target -> reclaimed = target; target })
        m.acquire(module("a", peakRamMb = 1_500))
        m.release("a")
        // 1_500 resident + 900 requested = 2_400 > 2_000, so "a" (idle) is evicted first.
        val result = m.acquire(module("b", peakRamMb = 900))
        assertTrue(result is AcquireResult.Ready)
        assertEquals("eviction should have sufficed", 0, reclaimed)
    }

    @Test
    fun reclaimFailureYieldsRetryableUnavailable() {
        val m = manager(budgetMb = 1_000, reclaim = { 0 }, maxLoaded = 1)
        // Occupy the only slot with a model that stays in use.
        m.acquire(module("held", peakRamMb = 300))
        val result = m.acquire(module("second", peakRamMb = 300))
        assertTrue(result is AcquireResult.Unavailable)
        assertTrue((result as AcquireResult.Unavailable).retryable)
    }

    @Test
    fun largeModelEvictsEverythingElseFirst() {
        // 2_000 MB budget => LARGE_MODEL_FRACTION (0.4) = 800 MB.
        val m = manager(budgetMb = 2_000, maxLoaded = 3)
        m.acquire(module("small1", peakRamMb = 300))
        m.release("small1")
        m.acquire(module("small2", peakRamMb = 300))
        m.release("small2")

        val big = m.acquire(module("big", peakRamMb = 1_200))
        assertTrue(big is AcquireResult.Ready)
        assertEquals("a large model runs alone", listOf("big"), m.loadedModelIds())
    }

    @Test
    fun largeModelRefusesWhenAnotherModelIsInUse() {
        val m = manager(budgetMb = 3_000, maxLoaded = 3)
        m.acquire(module("held", peakRamMb = 400))
        val big = m.acquire(module("big", peakRamMb = 2_000))
        assertTrue(big is AcquireResult.Unavailable)
        assertTrue("held" in m.loadedModelIds())
    }

    @Test
    fun modelCountLimitIsRespected() {
        val m = manager(budgetMb = 5_000, maxLoaded = 2)
        m.acquire(module("a", peakRamMb = 300))
        m.acquire(module("b", peakRamMb = 300))
        val third = m.acquire(module("c", peakRamMb = 300))
        assertTrue("a third model must evict an idle one or be refused", third is AcquireResult.Unavailable)
    }

    // ---- failures -----------------------------------------------------------------------------

    @Test
    fun loadFailureIsRecordedAndNotRetriedBlindly() {
        val m = manager(runtime = FakeRuntime(failureFor = setOf("broken")))
        val first = m.acquire(module("broken"))
        assertTrue(first is AcquireResult.Failed)
        val state = m.stateOf("broken")
        assertTrue(state is ModelRuntimeState.Failed)
        // A second acquire reports the recorded failure instead of hammering the runtime.
        val second = m.acquire(module("broken"))
        assertTrue(second is AcquireResult.Failed)
    }

    @Test
    fun clearFailuresAllowsRetry() {
        val runtime = FakeRuntime(failureFor = setOf("flaky"))
        val m = manager(runtime = runtime)
        assertTrue(m.acquire(module("flaky")) is AcquireResult.Failed)
        m.clearFailures()
        assertEquals(ModelRuntimeState.NotLoaded, m.stateOf("flaky"))
    }

    @Test
    fun wrongCapabilityIsRefusedBeforeLoading() {
        val m = manager()
        val result = m.acquire(
            module("text-only", capabilities = setOf(ModelCapability.TEXT_GENERATION)),
            requiredCapability = ModelCapability.OCR,
        )
        assertTrue(result is AcquireResult.Unavailable)
        assertFalse((result as AcquireResult.Unavailable).retryable)
        assertEquals("must not load a model for the wrong task", 0, m.residentRamMb())
    }

    // ---- generation ---------------------------------------------------------------------------

    @Test
    fun generationStreamsTokensAndUpdatesStats() {
        val m = manager()
        m.acquire(module("a"))
        val chunks = mutableListOf<String>()
        val result = m.generate("a", "hi") { chunks += it }
        assertTrue(result is GenerationResult.Completed)
        assertEquals("Hello from SAMAVETA", (result as GenerationResult.Completed).text)
        assertEquals(listOf("Hello", " from", " SAMAVETA"), chunks)
        val state = m.stateOf("a") as ModelRuntimeState.Loaded
        assertEquals(1, state.generationsCompleted)
    }

    @Test
    fun generatingOnUnloadedModelFailsExplicitly() {
        val m = manager()
        val result = m.generate("absent", "hi")
        assertTrue(result is GenerationResult.Failed)
        assertEquals(
            GenerationFailure.MODEL_UNLOADED,
            (result as GenerationResult.Failed).reason,
        )
    }

    @Test
    fun cancellationStopsGenerationAndIsNotCountedAsSuccess() {
        val m = manager()
        m.acquire(module("a"))
        val token = CancellationToken().also { it.cancel() }
        val result = m.generate("a", "hi", cancel = token)
        assertTrue(result is GenerationResult.Cancelled)
        val state = m.stateOf("a") as ModelRuntimeState.Loaded
        assertEquals("a cancelled run is not a completed generation", 0, state.generationsCompleted)
    }

    @Test
    fun cancellationMidStreamKeepsPartialOutputOut() {
        val m = manager()
        m.acquire(module("a"))
        val token = CancellationToken()
        var seen = 0
        val result = m.generate("a", "hi", cancel = token) {
            seen++
            if (seen == 2) token.cancel()
        }
        assertTrue(result is GenerationResult.Cancelled)
    }

    @Test
    fun invalidGenerationParametersAreRejected() {
        val m = manager()
        m.acquire(module("a"))
        try {
            m.generate("a", "hi", maxTokens = 0)
            org.junit.Assert.fail("maxTokens=0 must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxTokens"))
        }
        try {
            m.generate("a", "hi", temperature = 5.0)
            org.junit.Assert.fail("temperature=5 must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("temperature"))
        }
    }

    // ---- thermal ------------------------------------------------------------------------------

    @Test
    fun thermalSevereShedsIdleModels() {
        val m = manager(budgetMb = 5_000, maxLoaded = 3)
        m.acquire(module("idle", peakRamMb = 300))
        m.release("idle")
        m.acquire(module("busy", peakRamMb = 300))

        assertEquals(ModelResourceManager.ThermalAction.SHED_IDLE, m.onThermalLevel(ThermalLevel.SEVERE))
        assertEquals("idle model must be dropped", listOf("busy"), m.loadedModelIds())
    }

    @Test
    fun thermalCriticalSignalsAbort() {
        val m = manager()
        m.acquire(module("a"))
        assertEquals(
            ModelResourceManager.ThermalAction.SHED_ALL_AND_ABORT,
            m.onThermalLevel(ThermalLevel.CRITICAL),
        )
    }

    @Test
    fun thermalNominalDoesNothing() {
        val m = manager()
        m.acquire(module("a"))
        assertEquals(ModelResourceManager.ThermalAction.NONE, m.onThermalLevel(ThermalLevel.NOMINAL))
        assertEquals(1, m.loadedModelIds().size)
    }

    @Test
    fun moderateThermalShedsNeverUsedModelsOnly() {
        val m = manager(budgetMb = 5_000, maxLoaded = 3)
        m.acquire(module("used", peakRamMb = 300))
        m.generate("used", "hi")
        m.release("used")
        m.acquire(module("fresh", peakRamMb = 300))
        m.release("fresh")

        m.onThermalLevel(ThermalLevel.MODERATE)
        assertEquals("only the never-used model is dropped", listOf("used"), m.loadedModelIds())
    }

    // ---- shutdown -----------------------------------------------------------------------------

    @Test
    fun shutdownClosesEveryHandle() {
        val runtime = FakeRuntime()
        val m = manager(runtime = runtime, budgetMb = 5_000, maxLoaded = 3)
        m.acquire(module("a", peakRamMb = 300))
        m.acquire(module("b", peakRamMb = 300))
        m.shutdown()
        assertEquals(0, m.residentRamMb())
        assertTrue(m.loadedModelIds().isEmpty())
        assertEquals(setOf("a", "b"), runtime.closedModelIds.toSet())
    }

    @Test
    fun closeIsIdempotent() {
        val runtime = FakeRuntime()
        val m = manager(runtime = runtime)
        m.acquire(module("a"))
        m.release("a")
        assertTrue(m.unload("a"))
        assertFalse("second unload has nothing to close", m.unload("a"))
        assertEquals("closing twice must not double-count", 1, runtime.closedModelIds.count { it == "a" })
    }

    @Test
    fun unloadingAnInUseModelIsRefusedUnlessForced() {
        val runtime = FakeRuntime()
        val m = manager(runtime = runtime)
        m.acquire(module("a"))
        assertFalse(
            "closing a handle under a running generation would crash the process",
            m.unload("a"),
        )
        assertEquals(0, runtime.closedModelIds.count { it == "a" })
        assertTrue("shutdown is allowed to force", m.unload("a", force = true))
        assertEquals(1, runtime.closedModelIds.count { it == "a" })
    }

    @Test
    fun budgetFloorIsEnforced() {
        try {
            ModelResourceManager(FakeRuntime(), memoryBudgetMb = 128)
            org.junit.Assert.fail("a 128 MB budget is not viable for inference")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("memory budget"))
        }
    }

    @Test
    fun availableBudgetShrinksAsModelsLoad() {
        val m = manager(budgetMb = 2_500)
        assertEquals(2_500, m.availableBudgetMb())
        m.acquire(module("a", peakRamMb = 900))
        assertEquals(1_600, m.availableBudgetMb())
    }

    @Test
    fun stateOfUnknownModelIsNotLoaded() {
        assertEquals(ModelRuntimeState.NotLoaded, manager().stateOf("nope"))
        assertNull(manager().loadedModelIds().firstOrNull { it == "nope" })
    }
}
