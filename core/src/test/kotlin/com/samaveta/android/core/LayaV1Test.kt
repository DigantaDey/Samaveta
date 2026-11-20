package com.samaveta.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.samaveta.android.core.json.Json
import com.samaveta.android.core.json.JsonValue
import com.samaveta.android.core.laya.v1.Abstention
import com.samaveta.android.core.laya.v1.LayaAnswer
import com.samaveta.android.core.laya.v1.LayaBatchItem
import com.samaveta.android.core.laya.v1.LayaHttpResponse
import com.samaveta.android.core.laya.v1.LayaHttpTransport
import com.samaveta.android.core.laya.v1.LayaPredictRequest
import com.samaveta.android.core.laya.v1.LayaQuestion
import com.samaveta.android.core.laya.v1.LayaQuestions
import com.samaveta.android.core.laya.v1.LayaRouteRequest
import com.samaveta.android.core.laya.v1.LayaV1Client
import com.samaveta.android.core.laya.v1.LayaV1Codec
import com.samaveta.android.core.laya.v1.LayaV1Config
import com.samaveta.android.core.laya.v1.LayaV1Failure
import com.samaveta.android.core.laya.v1.LayaV1State
import com.samaveta.android.core.laya.v1.MinConfidence
import com.samaveta.android.core.security.Capability

/**
 * The LAYA integration, tested against the contract as LAYA actually publishes it.
 *
 * The payload fixtures below are the same shapes LAYA's own SDK tests use
 * (`sdk/python/tests/_support.py`, `sdk/typescript/test/client.test.mjs`), including the awkward
 * ones: a noul answer with no confidence, an abstained answer, a truncated request.
 */
class LayaV1Test {

    // ---------------------------------------------------------------- fixtures

    /** LAYA's own README sample response, verbatim in shape. */
    private val sampleResponse = """
        {
          "model": "laya-rl-agent",
          "answers": {
            "intent": {"type": "choice", "choice": "command",
                       "probabilities": {"command": 0.9519, "question": 0.0327, "conversation": 0.0154},
                       "confidence": 0.797, "answer_confidence": 0.9519,
                       "action": {"act_probability": 1.0}, "x_jev_confidence": 0.9278},
            "capability": {"type": "choice", "choice": "messaging.send",
                           "probabilities": {"messaging.send": 0.88, "messaging.draft": 0.12},
                           "confidence": 0.31, "answer_confidence": 0.88},
            "needs_confirmation": {"type": "noul", "noul": 0.8727, "answer_confidence": 0.8727},
            "sensitivity": {"type": "score", "score": 1.6994,
                            "legend": {"0": "trivial", "1": "personal", "2": "consequential", "3": "irreversible"},
                            "probabilities": {"0": 0.0249, "1": 0.4136, "2": 0.3985, "3": 0.1629},
                            "confidence": 0.1925, "answer_confidence": 0.4136}
          },
          "usage": {"input_tokens": 83, "output_tokens": 0, "state_tokens": 12,
                    "state_tokens_dropped": 0, "truncated": false, "truncated_questions": []},
          "routing": {"model": "english", "repo": "convaiinnovations/laya",
                      "reason": "English Latin text",
                      "detection": {"script": "latin", "language": "en", "is_english": true,
                                    "language_undecided": false, "non_latin_fraction": 0.0,
                                    "mixed_segment": null},
                      "workflow": null}
        }
    """.trimIndent()

    private fun body(transform: (String) -> String): String = transform(sampleResponse)

    // ---------------------------------------------------------------- encoding

    @Test
    fun aPredictRequestEncodesTheDocumentedShape() {
        val request = LayaPredictRequest(
            state = LayaQuestions.state("Send a reply to Asha", "en-IN"),
            questions = LayaQuestions.core("en-IN"),
            model = "multilingual",
            maxLen = 4096,
            minConfidence = MinConfidence.Single(0.6),
        )
        val encoded = Json.parseObject(LayaV1Codec.encodePredict(request))

        assertEquals("Send a reply to Asha", encoded.obj("state")!!.string("utterance"))
        assertEquals("en", encoded.obj("state")!!.string("lang"))
        assertEquals("multilingual", encoded.string("model"))
        assertEquals(4096L, encoded.long("max_len"))
        assertEquals(0.6, encoded.double("min_confidence")!!, 0.0001)

        val questions = encoded.obj("questions")!!
        assertTrue(questions.has("intent"))
        assertEquals("choice", questions.obj("intent")!!.string("type"))
        assertEquals("noul", questions.obj("needs_confirmation")!!.string("type"))
        assertEquals("score", questions.obj("sensitivity")!!.string("type"))
    }

    @Test
    fun absentOptionalFieldsAreOmittedRatherThanSentAsNull() {
        val encoded = Json.parseObject(
            LayaV1Codec.encodePredict(
                LayaPredictRequest(
                    state = JsonValue.Str("hello"),
                    questions = mapOf("q" to LayaQuestion.Noul(JsonValue.Str("Is it?"))),
                ),
            ),
        )
        assertFalse("model must be omitted so the router decides", encoded.has("model"))
        assertFalse(encoded.has("max_len"))
        assertFalse(encoded.has("min_confidence"))
        assertFalse(encoded.has("task"))
    }

    @Test
    fun aChoiceWithDescriptionsEncodesAsAnObject() {
        val question = LayaQuestion.Choice(
            instructions = JsonValue.Str("Which?"),
            criteria = listOf("a", "b"),
            descriptions = mapOf("a" to JsonValue.Str("first")),
        )
        val encoded = Json.parseObject(
            LayaV1Codec.encodePredict(LayaPredictRequest(JsonValue.Str("s"), mapOf("q" to question))),
        ).obj("questions")!!.obj("q")!!.obj("criteria")!!
        assertEquals("first", encoded.string("a"))
        assertEquals("b", encoded.string("b"))
    }

    @Test
    fun bucketThresholdsEncodeAsAMapAndAreValidatedAgainstLayasOwnKeys() {
        val ok = MinConfidence.Buckets(mapOf("choice:3-5" to 0.9, "default" to 0.4))
        val encoded = Json.parseObject(
            LayaV1Codec.encodePredict(
                LayaPredictRequest(
                    JsonValue.Str("s"),
                    mapOf("q" to LayaQuestion.Noul(JsonValue.Str("?"))),
                    minConfidence = ok,
                ),
            ),
        ).obj("min_confidence")!!
        assertEquals(0.9, encoded.double("choice:3-5")!!, 0.0001)

        // LAYA itself 422s this key; SAMAVETA refuses it before the request leaves the device.
        try {
            MinConfidence.Buckets(mapOf("choice:2-5" to 0.9)).also {
                LayaPredictRequest(JsonValue.Str("s"), mapOf("q" to LayaQuestion.Noul(JsonValue.Str("?"))), minConfidence = it)
                throw AssertionError("expected the request to be refused")
            }
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("choice:2-5"))
        }
    }

    @Test
    fun aBatchRequestWrapsStatesWithQuestions() {
        val encoded = Json.parseObject(
            LayaV1Codec.encodeBatch(
                listOf(
                    LayaBatchItem(JsonValue.Str("one"), mapOf("q" to LayaQuestion.Noul(JsonValue.Str("?")))),
                    LayaBatchItem(JsonValue.Str("two"), mapOf("q" to LayaQuestion.Noul(JsonValue.Str("?")))),
                ),
            ),
        )
        assertEquals(2, encoded.array("states")!!.items.size)
    }

    @Test
    fun anEmptyQuestionSetIsRefusedBeforeTheRequestIsBuilt() {
        try {
            LayaPredictRequest(JsonValue.Str("s"), emptyMap())
            throw AssertionError("expected refusal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("at least one question"))
        }
    }

    // ---------------------------------------------------------------- decoding: happy path

    @Test
    fun decodesLayasDocumentedResponse() {
        val prediction = LayaV1Codec.decodePrediction(sampleResponse)
        assertEquals("laya-rl-agent", prediction.model)
        assertEquals(4, prediction.answers.size)
        assertFalse(prediction.usage.lostEvidence)

        val intent = prediction.answers["intent"] as LayaAnswer.Choice
        assertEquals("command", intent.choice)
        assertEquals(0.9519, intent.probabilities["command"]!!, 0.0001)
        assertEquals(0.9519, intent.answerConfidence!!, 0.0001)
        assertEquals(0.797, intent.confidence!!, 0.0001)
        assertEquals(1.0, intent.actProbability!!, 0.0001)
        assertEquals(0.9278, intent.xJevConfidence!!, 0.0001)

        val noul = prediction.answers["needs_confirmation"] as LayaAnswer.Noul
        assertEquals(0.8727, noul.probabilityTrue, 0.0001)

        val score = prediction.answers["sensitivity"] as LayaAnswer.Score
        assertEquals(1.6994, score.score, 0.0001)
        assertEquals("personal", (score.legend["1"] as JsonValue.Str).value)

        assertEquals("english", prediction.routing!!.model)
        assertEquals(true, prediction.routing!!.detection!!.isEnglish)
    }

    @Test
    fun aNoulAnswerWithoutConfidenceIsAccepted() {
        // LAYA's own SDK fixture has a noul answer carrying only `noul`; the field is optional.
        val response = """{"model":"m","answers":{"refund":{"type":"noul","noul":0.7}},
                          "usage":{"input_tokens":1,"output_tokens":0,"state_tokens":1,
                                   "state_tokens_dropped":0,"truncated":false,"truncated_questions":[]}}"""
        val prediction = LayaV1Codec.decodePrediction(response)
        val noul = prediction.answers["refund"] as LayaAnswer.Noul
        assertEquals(0.7, noul.probabilityTrue, 0.0001)
        assertNull(noul.answerConfidence)
        assertNull("absent routing is legal", prediction.routing)
    }

    @Test
    fun abstentionStatesDecodeAndAbsenceMeansNoGateRan() {
        val gated = body { it.replace("\"answer_confidence\": 0.9519,", "\"answer_confidence\": 0.9519, \"abstention\": \"passed\", \"abstention_threshold\": 0.5,") }
        assertEquals(Abstention.PASSED, LayaV1Codec.decodePrediction(gated).answers["intent"]!!.abstention)

        val ungated = LayaV1Codec.decodePrediction(sampleResponse)
        assertNull("absent abstention means no gate ran — never 'passed'", ungated.answers["intent"]!!.abstention)
        assertNull(ungated.answers["intent"]!!.abstentionThreshold)
    }

    @Test
    fun batchResponsesDecodeEachResultIndependently() {
        val batch = """{"results":[
            {"model":"a","answers":{"q":{"type":"noul","noul":0.5}},
             "usage":{"input_tokens":1,"output_tokens":0,"state_tokens":1,"state_tokens_dropped":0,"truncated":false,"truncated_questions":[]}},
            {"model":"a","answers":{"q":{"type":"noul","noul":0.9}},
             "usage":{"input_tokens":1,"output_tokens":0,"state_tokens":1,"state_tokens_dropped":0,"truncated":false,"truncated_questions":[]}}
        ]}"""
        val results = LayaV1Codec.decodeBatch(batch)
        assertEquals(2, results.size)
        assertEquals(0.5, (results[0].answers["q"] as LayaAnswer.Noul).probabilityTrue, 0.0001)
        assertEquals(0.9, (results[1].answers["q"] as LayaAnswer.Noul).probabilityTrue, 0.0001)
    }

    @Test
    fun healthHandlesBothTheOpenAndRestrictedPayloads() {
        val open = LayaV1Codec.decodeHealth(
            """{"status":"ok","loaded":["english"],"revisions":{"english":"abc"},
                "device":"cuda","device_is_preference":false,
                "checkpoint_devices":{"english":"cuda"},
                "cpu_fallbacks":{"english":{"count":2,"last_reason":"OOM"}}}""",
        )
        assertTrue(open.isAlive)
        assertEquals(listOf("english"), open.loaded)
        assertEquals("abc", open.revisions["english"])
        assertEquals(2, open.cpuFallbacks["english"])
        assertFalse(open.deviceIsPreference)

        // With LAYA_API_KEY set, an unauthenticated probe sees only {"status":"ok"}.
        val restricted = LayaV1Codec.decodeHealth("""{"status":"ok"}""")
        assertTrue(restricted.isAlive)
        assertTrue(restricted.loaded.isEmpty())

        assertFalse(LayaV1Codec.decodeHealth("""{"status":"degraded"}""").isAlive)
    }

    // ---------------------------------------------------------------- decoding: hostile input

    @Test
    fun anUnknownAnswerTypeIsRejectedRatherThanGuessed() {
        val tampered = body { it.replace("\"type\": \"noul\"", "\"type\": \"ranking\"") }
        assertMalformed(tampered)
    }

    @Test
    fun aChoiceOutsideItsOwnProbabilitiesIsRejected() {
        val tampered = body { it.replace("\"choice\": \"command\"", "\"choice\": \"purchase\"") }
        assertMalformed(tampered)
    }

    @Test
    fun aProbabilityAboveOneIsRejected() {
        assertMalformed(body { it.replace("\"messaging.send\": 0.88", "\"messaging.send\": 1.88") })
    }

    @Test
    fun aNegativeProbabilityIsRejected() {
        assertMalformed(body { it.replace("\"messaging.draft\": 0.12", "\"messaging.draft\": -0.12") })
    }

    @Test
    fun aNoulProbabilityOutsideZeroToOneIsRejected() {
        assertMalformed(body { it.replace("\"noul\": 0.8727", "\"noul\": 2.0") })
    }

    @Test
    fun aNonNumericProbabilityIsRejected() {
        assertMalformed(body { it.replace("\"messaging.draft\": 0.12", "\"messaging.draft\": \"high\"") })
    }

    @Test
    fun anUnknownAbstentionStateIsRejected() {
        val tampered = body { it.replace("\"answer_confidence\": 0.9519,", "\"answer_confidence\": 0.9519, \"abstention\": \"probably\",") }
        assertMalformed(tampered)
    }

    @Test
    fun aResponseWithNoAnswersIsRejected() {
        assertMalformed("""{"model":"m","answers":{},"usage":{"input_tokens":0,"output_tokens":0,"state_tokens":0,"state_tokens_dropped":0,"truncated":false,"truncated_questions":[]}}""")
    }

    @Test
    fun aNonObjectPayloadIsRejected() {
        assertMalformed("[1,2,3]")
        assertMalformed("not json at all")
        assertMalformed("")
    }

    @Test
    fun aScoreWithoutALegendIsRejected() {
        val tampered = body { it.replace("\"legend\": {\"0\": \"trivial\", \"1\": \"personal\", \"2\": \"consequential\", \"3\": \"irreversible\"},", "") }
        assertMalformed(tampered)
    }

    @Test
    fun anErrorBodyIsDecodedIntoLayasOwnTaxonomy() {
        val validation = LayaV1Codec.decodeError(
            422,
            """{"detail":[{"loc":["body","max_len"],"msg":"ensure this value is less than 8192","type":"value_error"}]}""",
        )
        assertTrue(validation is LayaV1Failure.Rejected)
        assertEquals(422, (validation as LayaV1Failure.Rejected).statusCode)
        assertTrue(validation.detail.contains("less than 8192"))

        val coded = LayaV1Codec.decodeError(
            422,
            """{"error":{"message":"unknown model","code":"unknown_model"}}""",
        ) as LayaV1Failure.Rejected
        assertEquals("unknown_model", coded.code)
        assertEquals("unknown model", coded.detail)

        assertTrue(LayaV1Codec.decodeError(503, "") is LayaV1Failure.Overloaded)
        assertTrue(LayaV1Codec.decodeError(500, "boom") is LayaV1Failure.ServerError)
    }

    private fun assertMalformed(payload: String) {
        try {
            LayaV1Codec.decodePrediction(payload)
            throw AssertionError("expected the payload to be rejected: ${payload.take(120)}")
        } catch (expected: LayaV1Failure.Malformed) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }

    // ---------------------------------------------------------------- client behaviour

    private fun client(
        responses: List<LayaHttpResponse>,
        config: LayaV1Config = LayaV1Config("https://laya.example:8000", maxRetries = 1, retryBackoffMs = 0),
        calls: MutableList<String> = mutableListOf(),
    ): LayaV1Client {
        var index = 0
        return LayaV1Client(
            config = config,
            transport = LayaHttpTransport { path, method, _, _ ->
                calls += "$method $path"
                responses.getOrElse(index) { LayaHttpResponse(500, "unexpected extra call") }.also { index++ }
            },
        )
    }

    @Test
    fun healthConfirmsAConnectionAndOnlyThenIsOperational() {
        val c = client(listOf(LayaHttpResponse(200, """{"status":"ok","loaded":["english"],"device":"cpu"}""")))
        assertEquals(LayaV1State.NEVER_REACHED, c.state)
        assertFalse(c.state.isOperational)

        c.health()
        assertEquals(LayaV1State.CONNECTED, c.state)
        assertTrue(c.state.isOperational)
    }

    @Test
    fun anUnconfiguredClientFailsHonestlyWithoutCallingAnything() {
        val calls = mutableListOf<String>()
        val c = LayaV1Client(LayaV1Config.NOT_CONFIGURED, LayaHttpTransport { path, _, _, _ -> calls += path; LayaHttpResponse(200, "{}") })
        assertEquals(LayaV1State.NOT_CONFIGURED, c.state)
        try {
            c.health()
            throw AssertionError("expected NotConfigured")
        } catch (expected: LayaV1Failure.NotConfigured) {
            assertTrue(calls.isEmpty())
        }
    }

    @Test
    fun aBusyServerIsRetriedOnceThenSucceeds() {
        val calls = mutableListOf<String>()
        val c = client(
            listOf(
                LayaHttpResponse(503, ""),
                LayaHttpResponse(200, sampleResponse),
            ),
            calls = calls,
        )
        val prediction = c.predict(
            LayaPredictRequest(JsonValue.Str("s"), LayaQuestions.core("en")),
        )
        assertEquals(4, prediction.answers.size)
        // The client joins the base URL, so assert on the endpoint rather than a bare path.
        assertEquals(2, calls.size)
        assertTrue(calls.all { it == "POST https://laya.example:8000/v1/systemone" })
        assertEquals(LayaV1State.CONNECTED, c.state)
    }

    @Test
    fun aValidationErrorIsNeverRetried() {
        val calls = mutableListOf<String>()
        val c = client(listOf(LayaHttpResponse(422, """{"detail":"unknown model"}""")), calls = calls)
        try {
            c.predict(LayaPredictRequest(JsonValue.Str("s"), LayaQuestions.core("en")))
            throw AssertionError("expected Rejected")
        } catch (expected: LayaV1Failure.Rejected) {
            assertEquals("repeating a 422 is pointless", 1, calls.size)
            assertEquals("unknown model", expected.detail)
        }
        assertEquals(LayaV1State.DEGRADED, c.state)
        assertTrue(c.lastFailure is LayaV1Failure.Rejected)
    }

    @Test
    fun aConnectionFailureBecomesUnreachableAndMarksDegraded() {
        val c = LayaV1Client(
            LayaV1Config("https://laya.example:8000", maxRetries = 0),
            LayaHttpTransport { _, _, _, _ -> throw java.io.IOException("connection refused") },
        )
        try {
            c.health()
            throw AssertionError("expected Unreachable")
        } catch (expected: LayaV1Failure.Unreachable) {
            assertTrue(expected.message!!.contains("connection refused"))
        }
        assertTrue("a failed call must leave the client visibly degraded", c.state != LayaV1State.CONNECTED)
    }

    @Test
    fun aPlaintextNonLoopbackEndpointIsRefusedAtConfiguration() {
        try {
            LayaV1Config("http://laya.example.com:8000")
            throw AssertionError("plaintext remote LAYA must be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("https"))
        }
    }

    @Test
    fun aLoopbackEndpointIsAllowedForOnDeviceDevelopment() {
        assertTrue(LayaV1Config("http://127.0.0.1:8000").isConfigured)
        assertTrue(LayaV1Config("http://localhost:8000").isConfigured)
        assertFalse(LayaV1Config.NOT_CONFIGURED.isConfigured)
    }

    @Test
    fun routeSnapshotsDecode() {
        val c = client(listOf(LayaHttpResponse(200, """{"model":"multilingual","repo":"convaiinnovations/laya/multilingual","reason":"default"}""")))
        val snapshot = c.route(LayaRouteRequest(JsonValue.Str("Turn lights off"), explain = true))
        assertEquals("multilingual", snapshot.model)
        assertEquals("default", snapshot.reason)
    }

    // ---------------------------------------------------------------- policy reading

    private fun prediction(vararg overrides: Pair<String, String>): com.samaveta.android.core.laya.v1.LayaPrediction {
        var json = sampleResponse
        overrides.forEach { (from, to) -> json = json.replace(from, to) }
        return LayaV1Codec.decodePrediction(json)
    }

    @Test
    fun aConfidentCommandBecomesAProposalForARealCapability() {
        val reading = LayaQuestions.read(prediction())
        assertTrue(reading is LayaQuestions.Reading.Proposal)
        val proposal = reading as LayaQuestions.Reading.Proposal
        assertEquals(Capability.MESSAGING_SEND, proposal.capability)
        assertTrue("sending mutates state, so confirmation is forced", proposal.requiresConfirmation)
        assertTrue(proposal.rationale.contains("messaging.send".let { "Messaging" }) || proposal.rationale.contains("Send messages"))
    }

    @Test
    fun aTruncatedRequestIsRefusedBeforeAnythingElseIsConsidered() {
        val truncated = prediction(
            "\"state_tokens_dropped\": 0" to "\"state_tokens_dropped\": 412",
            "\"truncated\": false" to "\"truncated\": true",
        )
        val reading = LayaQuestions.read(truncated)
        assertTrue(reading is LayaQuestions.Reading.Unusable)
        assertTrue((reading as LayaQuestions.Reading.Unusable).reason.contains("412"))
    }

    @Test
    fun anAbstainedAnswerIsNeverActedOn() {
        val abstained = prediction(
            "\"answer_confidence\": 0.9519," to "\"answer_confidence\": 0.9519, \"abstention\": \"abstained\", \"abstention_threshold\": 0.99,",
        )
        assertTrue(LayaQuestions.read(abstained) is LayaQuestions.Reading.Unsure)
    }

    @Test
    fun anUnevaluatedGateIsNotTreatedAsAPass() {
        val unevaluated = prediction(
            "\"answer_confidence\": 0.9519," to "\"answer_confidence\": 0.9519, \"abstention\": \"unevaluated\",",
        )
        assertTrue(LayaQuestions.read(unevaluated) is LayaQuestions.Reading.Unsure)
    }

    @Test
    fun conversationAndQuestionsAreNotCommands() {
        // Raising the label alone is not enough: the probability must support it, or the low-intent
        // floor correctly reports uncertainty instead.
        val conversation = prediction(
            "\"choice\": \"command\"" to "\"choice\": \"conversation\"",
            "\"command\": 0.9519, \"question\": 0.0327, \"conversation\": 0.0154" to
                "\"command\": 0.0519, \"question\": 0.0327, \"conversation\": 0.9154",
        )
        assertEquals("conversation", (LayaQuestions.read(conversation) as LayaQuestions.Reading.NotACommand).kind)

        val question = prediction(
            "\"choice\": \"command\"" to "\"choice\": \"question\"",
            "\"command\": 0.9519, \"question\": 0.0327, \"conversation\": 0.0154" to
                "\"command\": 0.0519, \"question\": 0.9154, \"conversation\": 0.0327",
        )
        assertEquals("question", (LayaQuestions.read(question) as LayaQuestions.Reading.NotACommand).kind)
    }

    @Test
    fun aLabelWithAnUnsupportedProbabilityIsTreatedAsUncertainty() {
        // The answer says "conversation" but the mass is still on "command": that is ambiguous.
        val inconsistent = prediction("\"choice\": \"command\"" to "\"choice\": \"conversation\"")
        assertTrue(LayaQuestions.read(inconsistent) is LayaQuestions.Reading.Unsure)
    }

    @Test
    fun aLowConfidenceCapabilityAnswerAsksInsteadOfGuessing() {
        val unsure = prediction(
            "\"messaging.send\": 0.88, \"messaging.draft\": 0.12" to "\"messaging.send\": 0.51, \"messaging.draft\": 0.49",
        )
        assertTrue(LayaQuestions.read(unsure) is LayaQuestions.Reading.Unsure)
    }

    @Test
    fun aCapabilitySamavetaDoesNotHaveIsUnusableNotIgnored() {
        // `messaging.send` is real; a server returning something else must not be silently mapped.
        val bogus = prediction(
            "\"messaging.send\": 0.88, \"messaging.draft\": 0.12" to "\"messaging.send\": 0.88, \"messaging.teleport\": 0.12",
        ).let { prediction ->
            val choice = (prediction.answers["capability"] as LayaAnswer.Choice)
            com.samaveta.android.core.laya.v1.LayaPrediction(
                model = prediction.model,
                answers = prediction.answers + ("capability" to choice.copy(
                    choice = "messaging.teleport",
                    probabilities = choice.probabilities + ("messaging.teleport" to 0.88),
                )),
                usage = prediction.usage,
                routing = prediction.routing,
            )
        }
        assertTrue(LayaQuestions.read(bogus) is LayaQuestions.Reading.Unusable)
    }

    @Test
    fun aModelsOpinionCannotRemoveMandatoryConfirmation() {
        // LAYA says "no confirmation needed", but sending mutates state: SAMAVETA still asks.
        val noConfirm = prediction(
            "\"noul\": 0.8727" to "\"noul\": 0.02",
            "\"score\": 1.6994" to "\"score\": 0.1",
        )
        val proposal = LayaQuestions.read(noConfirm) as LayaQuestions.Reading.Proposal
        assertTrue(proposal.requiresConfirmation)
    }

    @Test
    fun aHighConsequenceScoreForcesConfirmation() {
        val consequential = prediction("\"score\": 1.6994" to "\"score\": 3.0")
        val proposal = LayaQuestions.read(consequential) as LayaQuestions.Reading.Proposal
        assertTrue(proposal.requiresConfirmation)
    }

    @Test
    fun readingCarriesTheRoutingEvidenceForTheAuditTrail() {
        val proposal = LayaQuestions.read(prediction()) as LayaQuestions.Reading.Proposal
        assertTrue(proposal.rationale.contains("english"))
        assertTrue(proposal.rationale.contains("English Latin text"))
    }

    @Test
    fun policyThresholdsAreValidated() {
        try {
            LayaQuestions.Policy(capabilityFloor = 1.5)
            throw AssertionError("expected refusal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("capabilityFloor"))
        }
    }

    @Test
    fun localeHintIsTrimmedToALanguageCodeAndInvalidOnesAreDropped() {
        assertEquals("en", LayaQuestions.languageCodeOf("en-IN"))
        assertEquals("hi", LayaQuestions.languageCodeOf("hi"))
        assertNull(LayaQuestions.languageCodeOf("!!"))
        assertNull(LayaQuestions.languageCodeOf("e"))
    }

    @Test
    fun capabilityLabelsComeFromTheRegistrySoEveryAnswerMapsBack() {
        assertTrue(LayaQuestions.CAPABILITY_LABELS.containsAll(Capability.ids()))
        assertEquals(Capability.ids().size, LayaQuestions.CAPABILITY_LABELS.size)
        LayaQuestions.CAPABILITY_LABELS.forEach { label ->
            assertTrue("'$label' must resolve to a Capability", Capability.fromId(label) != null)
        }
    }
}
