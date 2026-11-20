package com.samaveta.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.samaveta.android.core.json.JsonValue
import com.samaveta.android.core.laya.v1.LayaHttpResponse
import com.samaveta.android.core.laya.v1.LayaHttpTransport
import com.samaveta.android.core.laya.v1.LayaPredictRequest
import com.samaveta.android.core.laya.v1.LayaQuestion
import com.samaveta.android.core.laya.v1.LayaQuestions
import com.samaveta.android.core.laya.v1.LayaV1Client
import com.samaveta.android.core.laya.v1.LayaV1Codec
import com.samaveta.android.core.laya.v1.LayaV1Config
import com.samaveta.android.core.laya.v1.LayaV1Failure
import com.samaveta.android.core.laya.v1.LayaV1State

/**
 * Contract tests against responses captured from a **real** `laya-serve` process.
 *
 * The fixtures live in `core/src/test/resources/laya-live/` with a README recording exactly how they
 * were produced. `LayaV1Test` pins the documented contract; this file pins what the server actually
 * sends, which is where the two usually differ.
 *
 * Because no successful prediction could be captured (checkpoint download is blocked in this
 * environment), the critical assertions here are about *failure* shapes — and about the fact that the
 * success path being untested is written down rather than implied.
 */
class LayaLiveContractTest {

    private fun fixture(name: String): String {
        val stream = javaClass.classLoader.getResourceAsStream("laya-live/$name")
            ?: error("missing captured fixture 'laya-live/$name'")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun clientReturning(
        status: Int,
        body: String,
        config: LayaV1Config = LayaV1Config("https://laya.example:8000", maxRetries = 1, retryBackoffMs = 0),
        calls: MutableList<Int> = mutableListOf(),
    ) = LayaV1Client(
        config = config,
        transport = LayaHttpTransport { _, _, _, _ ->
            calls += 1
            LayaHttpResponse(status, body)
        },
    )

    @Test
    fun theCapturedHealthPayloadDecodesExactly() {
        val health = LayaV1Codec.decodeHealth(fixture("health.json"))
        assertTrue(health.isAlive)
        assertEquals("ok", health.status)
        assertEquals("cpu", health.device)
        // Nothing was resident because preload was off and weights were unavailable. This is a normal
        // state, not an error, and the decoder must not invent loaded checkpoints.
        assertTrue(health.loaded.isEmpty())
        assertTrue(health.deviceIsPreference)
        assertTrue(health.cpuFallbacks.isEmpty())
    }

    @Test
    fun theCapturedRoutePayloadDecodes() {
        val snapshot = LayaV1Codec.decodeRoute(fixture("route.json"))
        assertEquals("multilingual", snapshot.model)
        assertEquals("convaiinnovations/laya/multilingual", snapshot.repo)
        assertTrue(snapshot.reason.contains("default"))
    }

    @Test
    fun aRealUnknownModelRejectionDecodesAsRejectedWithTheServersOwnText() {
        val failure = LayaV1Codec.decodeError(422, fixture("error-422-unknown-model.json"))
        assertTrue(failure is LayaV1Failure.Rejected)
        val rejected = failure as LayaV1Failure.Rejected
        assertEquals(422, rejected.statusCode)
        // The real 422 carries `detail` as a plain string. An earlier decode assumed an object.
        assertTrue(rejected.detail.contains("unknown model '/nonexistent/ckpt'"))
        assertTrue(rejected.detail.contains("multilingual"))
    }

    @Test
    fun theRealTokenCeilingIs8192AndItIsEnforcedServerSide() {
        val rejected = LayaV1Codec.decodeError(422, fixture("error-422-max-len.json")) as LayaV1Failure.Rejected
        assertTrue(rejected.detail.contains("8192"))
        assertEquals(
            "the client-side cap must match the server's documented limit",
            LayaPredictRequest.MAX_TOKEN_BUDGET,
            8_192,
        )
    }

    @Test
    fun aRealMissingQuestionsRejectionDecodes() {
        val rejected = LayaV1Codec.decodeError(400, fixture("error-400-missing-questions.json")) as LayaV1Failure.Rejected
        assertTrue(rejected.detail.contains("questions"))
    }

    @Test
    fun aRealInferenceFailureIsNotRetriedBecauseItIsDeterministic() {
        // Observed live: a missing checkpoint produces 500 {"detail":"inference failed"} on every
        // attempt. Retrying it would multiply latency and log noise on a deployment that cannot work.
        val calls = mutableListOf<Int>()
        val c = clientReturning(500, fixture("error-500-inference-failed.json"), calls = calls)
        try {
            c.predict(LayaPredictRequest(JsonValue.Str("s"), mapOf("q" to LayaQuestion.Noul(JsonValue.Str("?")))))
            throw AssertionError("expected ServerError")
        } catch (expected: LayaV1Failure.ServerError) {
            assertEquals(500, expected.statusCode)
            assertEquals("inference failed", expected.detail)
            assertEquals("a deterministic 500 must be attempted exactly once", 1, calls.size)
        }
        assertEquals(LayaV1State.DEGRADED, c.state)
    }

    @Test
    fun aReal422IsNeverRetriedEither() {
        val calls = mutableListOf<Int>()
        val c = clientReturning(422, fixture("error-422-unknown-model.json"), calls = calls)
        try {
            c.predict(LayaPredictRequest(JsonValue.Str("s"), mapOf("q" to LayaQuestion.Noul(JsonValue.Str("?")))))
            throw AssertionError("expected Rejected")
        } catch (expected: LayaV1Failure.Rejected) {
            assertEquals(1, calls.size)
        }
    }

    @Test
    fun theCapturedHealthPayloadMarksTheClientConnected() {
        val c = clientReturning(200, fixture("health.json"))
        c.health()
        assertEquals(LayaV1State.CONNECTED, c.state)
    }

    @Test
    fun noSuccessfulPredictionHasBeenCapturedAndThatIsRecordedNotImplied() {
        // Guard against someone later adding a "prediction.json" fixture that was hand-written:
        // a captured success must carry the server's own `routing` block and a zero output_tokens.
        val resources = javaClass.classLoader.getResource("laya-live")
        assertTrue("fixture directory must exist", resources != null)
        val predictionFixture = javaClass.classLoader.getResourceAsStream("laya-live/prediction.json")
        if (predictionFixture != null) {
            val payload = predictionFixture.use { it.readBytes().toString(Charsets.UTF_8) }
            val parsed = LayaV1Codec.decodePrediction(payload)
            assertTrue("a captured success must include routing", parsed.routing != null)
            assertEquals("laya generates nothing", 0, parsed.usage.outputTokens)
        }
        // Otherwise: nothing to assert. The absence is documented in the fixture README and in
        // LAYA_INTEGRATION.md §8, and must not be presented as verification.
    }

    @Test
    fun theQuestionsWeAskAreValidAgainstTheRealServerGrammar() {
        // LAYA validates question shape itself: a noul may only describe true/false, a choice needs
        // unique non-empty labels. Encoding our real question set must satisfy that.
        val encoded = LayaV1Codec.encodePredict(
            LayaPredictRequest(
                state = LayaQuestions.state("Send a reply to Asha saying I will be late", "en-IN"),
                questions = LayaQuestions.core("en-IN"),
            ),
        )
        assertTrue(encoded.contains("\"type\":\"choice\""))
        assertTrue(encoded.contains("\"type\":\"score\""))
        assertTrue(encoded.contains("\"type\":\"noul\""))
        assertFalse("noul must not carry a criteria array", encoded.contains("\"type\":\"noul\",\"instructions\":\"Would a careful assistant ask the user before doing this?\",\"criteria\":[]"))
    }
}
