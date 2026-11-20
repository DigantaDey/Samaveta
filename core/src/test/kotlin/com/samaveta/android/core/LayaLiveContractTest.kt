package com.samaveta.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.samaveta.android.core.json.JsonValue
import com.samaveta.android.core.laya.v1.LayaHttpResponse
import com.samaveta.android.core.laya.v1.LayaHttpTransport
import com.samaveta.android.core.laya.v1.LayaPredictRequest
import com.samaveta.android.core.laya.v1.Abstention
import com.samaveta.android.core.laya.v1.LayaAnswer
import com.samaveta.android.core.laya.v1.LayaBatchRequest
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
        // Captured with all three checkpoints resident. `deviceIsPreference` is false because the
        // server measured them, and `revisions` is null-per-checkpoint because this fixture's
        // checkpoints were synthesised in-process rather than fetched with a revision.
        assertEquals(listOf("english", "multilingual", "typed-decisions"), health.loaded)
        assertFalse(health.deviceIsPreference)
        assertEquals(3, health.cpuFallbacks.size)
        assertTrue(health.cpuFallbacks.values.all { it == 0 })
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

    // ---------------------------------------------------------------- a real 200

    @Test
    fun aRealSuccessfulPredictionDecodesEndToEnd() {
        val prediction = LayaV1Codec.decodePrediction(fixture("prediction.json"))
        assertEquals("laya-rl-agent", prediction.model)
        assertEquals("laya generates nothing", 0, prediction.usage.outputTokens)

        val intent = prediction.answers["intent"] as LayaAnswer.Choice
        assertEquals("question", intent.choice)
        assertTrue(intent.probabilities.values.all { it in 0.0..1.0 })
        // A real gate ran: the server reported both the state and the threshold it used.
        assertEquals(Abstention.ABSTAINED, intent.abstention)
        assertEquals(0.5, intent.abstentionThreshold!!, 0.0001)

        val noul = prediction.answers["needs_confirmation"] as LayaAnswer.Noul
        assertEquals(Abstention.PASSED, noul.abstention)

        val score = prediction.answers["sensitivity"] as LayaAnswer.Score
        assertEquals(4, score.legend.size)
        assertEquals("personal", (score.legend["1"] as JsonValue.Str).value)

        assertEquals("english", prediction.routing!!.model)
        assertEquals(true, prediction.routing!!.detection!!.isEnglish)
    }

    @Test
    fun aRealResponseReportsOptionCollapsesAndWeTreatThemAsLostEvidence() {
        // This block only exists in live payloads; the documented examples omit it. Every question
        // here had 3-4 options collapsed to one span, so the answer separates them less than a full
        // read would have.
        val prediction = LayaV1Codec.decodePrediction(fixture("prediction.json"))
        val options = prediction.usage.options
        assertEquals(3, options.size)
        assertTrue(options.values.all { it.total > 1 })
        assertTrue(options.values.all { it.collapsed })
        assertTrue(prediction.usage.lostEvidence)
        assertEquals(setOf("intent", "needs_confirmation", "sensitivity"), prediction.usage.collapsedQuestions().toSet())
    }

    @Test
    fun aRealResponseWithCollapsedOptionsIsRefusedWithTheRealReason() {
        // This live capture (a tiny fixture checkpoint with a 6-token vocab) collapsed every
        // question's options into a single span, so the answers cannot distinguish the options. The
        // refusal must name that, not claim tokens were dropped -- it used to say "0 token(s)
        // dropped", which was both confusing and untrue.
        val prediction = LayaV1Codec.decodePrediction(fixture("prediction.json"))
        val reading = LayaQuestions.read(prediction) as LayaQuestions.Reading.Unusable
        assertTrue(reading.reason.contains("own token span"))
        assertFalse(reading.reason.contains("0 token(s)"))
    }

    @Test
    fun aRealBatchResponseDecodesPerResult() {
        val results = LayaV1Codec.decodeBatch(fixture("batch.json"))
        assertEquals(2, results.size)
        results.forEach { prediction ->
            val answer = prediction.answers["refund"] as LayaAnswer.Noul
            assertTrue(answer.probabilityTrue in 0.0..1.0)
            assertEquals(0, prediction.usage.outputTokens)
        }
        // min_confidence {default: 0.0} was sent, so a gate ran and reported its state.
        assertEquals(Abstention.PASSED, (results[0].answers["refund"] as LayaAnswer.Noul).abstention)
    }

    @Test
    fun aRealMissingBearerIsRejectedDistinctly() {
        val rejected = LayaV1Codec.decodeError(401, fixture("error-401-no-key.json")) as LayaV1Failure.Rejected
        assertEquals(401, rejected.statusCode)
        assertTrue(rejected.detail.contains("bearer"))
    }

    @Test
    fun theBatchShapeIsTheOneTheServerAccepts() {
        // 400 "must be an object with 'states' and 'questions' fields" is what the old
        // states[].questions encoding produced. Encode the accepted shape and re-check it parses.
        val encoded = LayaV1Codec.encodeBatch(
            LayaBatchRequest(listOf(JsonValue.Str("hello")), LayaQuestions.core("en")),
        )
        val parsed = com.samaveta.android.core.json.Json.parseObject(encoded)
        assertTrue(parsed.has("states"))
        assertTrue(parsed.has("questions"))
        assertTrue(parsed.array("states")!!.items.first() is JsonValue.Str)
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
