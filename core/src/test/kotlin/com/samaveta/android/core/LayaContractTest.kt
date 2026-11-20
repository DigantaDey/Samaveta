package com.samaveta.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import com.samaveta.android.core.device.Accelerator
import com.samaveta.android.core.device.ThermalLevel
import com.samaveta.android.core.json.Json
import com.samaveta.android.core.laya.AdmissionSummary
import com.samaveta.android.core.laya.LAYA_WIRE_SCHEMA_VERSION
import com.samaveta.android.core.laya.LayaAdapter
import com.samaveta.android.core.laya.LayaConfig
import com.samaveta.android.core.laya.LayaContext
import com.samaveta.android.core.laya.LayaDeviceSnapshot
import com.samaveta.android.core.laya.LayaFailure
import com.samaveta.android.core.laya.LayaIntegrationGuard
import com.samaveta.android.core.laya.LayaIntegrationState
import com.samaveta.android.core.laya.LayaOutcome
import com.samaveta.android.core.laya.LayaPlan
import com.samaveta.android.core.laya.LayaRequest
import com.samaveta.android.core.laya.LayaRequestMapper
import com.samaveta.android.core.laya.LayaTransport
import com.samaveta.android.core.laya.LayaTurn
import com.samaveta.android.core.laya.LayaWireContract
import com.samaveta.android.core.laya.MockLayaTransport
import com.samaveta.android.core.laya.StepKind
import com.samaveta.android.core.laya.TransportKind
import com.samaveta.android.core.laya.TurnRole
import com.samaveta.android.core.model.ModelCapability
import com.samaveta.android.core.model.ModelFormat
import com.samaveta.android.core.model.ModelRegistry
import com.samaveta.android.core.model.Quantization
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

class LayaContractTest {

    private fun request(id: String = "req-1", deadline: Long = Long.MAX_VALUE) = LayaRequest(
        requestId = id,
        sessionId = "sess-1",
        utterance = "Summarise the top Android AI updates",
        context = LayaContext(
            recentTurns = listOf(LayaTurn(TurnRole.USER, "hi", 1_000L)),
            activeTaskId = null,
            userConfirmedIntents = emptyList(),
        ),
        device = LayaDeviceSnapshot(
            memoryTier = "comfortable",
            totalRamMb = 8_192,
            availableRamMb = 3_000,
            availableStorageMb = 90_000,
            thermalLevel = ThermalLevel.NOMINAL,
            batteryPercent = 80,
            isCharging = false,
            availableAccelerators = setOf(Accelerator.CPU),
            androidApiLevel = 34,
            abis = setOf("arm64-v8a"),
            offline = true,
        ),
        modelInventory = emptyList(),
        capabilityInventory = setOf("notifications.read"),
        deadlineEpochMs = deadline,
        locale = "en-IN",
    )

    private fun planResponse(requestId: String, steps: String = """[{"index":0,"kind":"model_inference","model_id":"llama-3.2-1b","instruction":"Answer"}]""") =
        """{"schema_version":"$LAYA_WIRE_SCHEMA_VERSION","type":"plan","request_id":"$requestId","steps":$steps,"reasoning_summary":"simple"}"""

    private suspend fun awaitOutcome(adapter: LayaAdapter, request: LayaRequest): LayaOutcome =
        async { adapter.decide(request) }.await()

    @Test
    fun encodesRequestWithSchemaVersionAndInventory() {
        val text = LayaWireContract.encodeRequest(request())
        val parsed = Json.parseObject(text)
        assertEquals(LAYA_WIRE_SCHEMA_VERSION, parsed.string("schema_version"))
        assertEquals("decision_request", parsed.string("type"))
        assertEquals("req-1", parsed.string("request_id"))
        assertEquals("en-IN", parsed.string("locale"))
        assertEquals(1, parsed.obj("context")!!.array("recent_turns")!!.items.size)
        val device = parsed.obj("device")!!
        assertEquals(8192L, device.long("total_ram_mb"))
        assertEquals(true, device.bool("offline"))
        assertEquals(listOf("arm64-v8a"), device.strings("abis"))
    }

    @Test
    fun decodesPlanResponse() {
        val decision = LayaWireContract.decodeResponse("req-1", planResponse("req-1"))
        assertTrue(decision is LayaPlan)
        val plan = decision as LayaPlan
        assertEquals(1, plan.steps.size)
        assertEquals(StepKind.MODEL_INFERENCE, plan.steps[0].kind)
    }

    @Test
    fun rejectsMismatchedRequestId() {
        try {
            LayaWireContract.decodeResponse("req-1", planResponse("req-OTHER"))
            fail("a response for a different request must be rejected")
        } catch (e: LayaFailure.MalformedResponse) {
            assertTrue(e.message!!.contains("does not match"))
        }
    }

    @Test
    fun rejectsUnknownSchemaVersion() {
        val body = """{"schema_version":"1999-01","type":"no_plan","request_id":"req-1","reason":"x"}"""
        try {
            LayaWireContract.decodeResponse("req-1", body)
            fail("unknown versions must be rejected")
        } catch (e: LayaFailure.UnsupportedVersion) {
            assertEquals("1999-01", e.version)
        }
    }

    @Test
    fun rejectsMalformedAndUnknownStepKinds() {
        val body = """{"schema_version":"$LAYA_WIRE_SCHEMA_VERSION","type":"plan","request_id":"req-1",
            "steps":[{"index":0,"kind":"become_omnipotent","instruction":"do everything"}]}"""
        try {
            LayaWireContract.decodeResponse("req-1", body.trimIndent())
            fail("unknown step kinds must be rejected, not guessed")
        } catch (e: LayaFailure.MalformedResponse) {
            assertTrue(e.message!!.contains("unknown kind"))
        }
    }

    @Test
    fun rejectsEmptyPlan() {
        try {
            LayaWireContract.decodeResponse("req-1", planResponse("req-1", steps = "[]"))
            fail("an empty plan is not actionable")
        } catch (e: LayaFailure.MalformedResponse) {
            assertTrue(e.message!!.contains("zero steps"))
        }
    }

    @Test
    fun notConfiguredAdapterFailsHonestly() = kotlinx.coroutines.runBlocking {
        val adapter = LayaAdapter(LayaConfig.NOT_CONFIGURED, null)
        assertEquals(LayaIntegrationState.NOT_CONFIGURED, adapter.state)
        val outcome = awaitOutcome(adapter, request())
        assertTrue(outcome is LayaOutcome.Failed)
        assertTrue((outcome as LayaOutcome.Failed).failure is LayaFailure.NotConfigured)
    }

    @Test
    fun mockTransportIsReportedAsTestOnlyAndBlocksRelease() = kotlinx.coroutines.runBlocking {
        val transport = MockLayaTransport()
        transport.enqueue { planResponse("req-1") }
        val adapter = LayaAdapter(LayaConfig(endpoint = "https://laya.invalid/decide"), transport)
        assertEquals(LayaIntegrationState.MOCK_TEST_ONLY, adapter.state)
        assertTrue(LayaIntegrationGuard.releaseBlockers(adapter.state).isNotEmpty())
        val outcome = awaitOutcome(adapter, request())
        assertTrue(outcome is LayaOutcome.Success)
    }

    @Test
    fun transportFailureIsRetriedThenSurfaced() = kotlinx.coroutines.runBlocking {
        var attempts = 0
        val transport = object : LayaTransport {
            override val kind = TransportKind.HTTP_JSON
            override suspend fun exchange(requestJson: String, timeoutMs: Long): String {
                attempts++
                throw LayaFailure.Transport("connection reset", null)
            }
        }
        val adapter = LayaAdapter(LayaConfig("https://laya.invalid", maxRetries = 2, retryBackoffMs = 0), transport)
        val outcome = awaitOutcome(adapter, request())
        assertTrue(outcome is LayaOutcome.Failed)
        assertEquals("transport errors should be retried (1 + 2 retries)", 3, attempts)
    }

    @Test
    fun malformedResponseIsNotRetried() = kotlinx.coroutines.runBlocking {
        var attempts = 0
        val transport = object : LayaTransport {
            override val kind = TransportKind.HTTP_JSON
            override suspend fun exchange(requestJson: String, timeoutMs: Long): String {
                attempts++
                return "not json at all"
            }
        }
        val adapter = LayaAdapter(LayaConfig("https://laya.invalid", maxRetries = 2, retryBackoffMs = 0), transport)
        val outcome = awaitOutcome(adapter, request())
        assertTrue(outcome is LayaOutcome.Failed)
        assertEquals("a malformed response must not be retried", 1, attempts)
    }

    @Test
    fun cancelledPastDeadlineFailsWithoutSending() = kotlinx.coroutines.runBlocking {
        val transport = MockLayaTransport()
        transport.enqueue { planResponse("req-1") }
        val adapter = LayaAdapter(LayaConfig("https://laya.invalid"), transport, clock = { 10_000L })
        val outcome = awaitOutcome(adapter, request(deadline = 5_000L))
        assertTrue(outcome is LayaOutcome.Failed)
        assertTrue((outcome as LayaOutcome.Failed).failure is LayaFailure.Timeout)
        assertEquals("no request must be sent past the deadline", 0, transport.sentRequests.size)
    }

    @Test
    fun inventoryReportsUnavailabilityReason() {
        val registry = ModelRegistry(listOf(TestFixtures.installedEntry(TestFixtures.smallTextModel())))
        val entries = LayaRequestMapper.modelInventory(registry) {
            AdmissionSummary(available = false, reason = "needs more RAM")
        }
        assertEquals(1, entries.size)
        assertEquals(false, entries[0].available)
        assertEquals("needs more RAM", entries[0].unavailabilityReason)
    }

    @Test
    fun deviceSnapshotCarriesOnlyVerifiedAccelerators() {
        val snapshot = LayaRequestMapper.deviceSnapshot(
            TestFixtures.referenceDevice(),
            TestFixtures.capabilitiesCpuOnly(),
            ThermalLevel.LIGHT,
            batteryPercent = 42,
            isCharging = true,
            offline = true,
        )
        assertEquals(setOf(Accelerator.CPU), snapshot.availableAccelerators)
        assertEquals(ThermalLevel.LIGHT, snapshot.thermalLevel)
    }
}
