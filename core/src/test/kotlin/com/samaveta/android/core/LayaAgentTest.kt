package com.samaveta.android.core

import com.samaveta.android.core.laya.v1.LayaAgent
import com.samaveta.android.core.laya.v1.LayaUrlTransport
import com.samaveta.android.core.laya.v1.LayaV1Client
import com.samaveta.android.core.laya.v1.LayaV1Config
import com.samaveta.android.core.laya.v1.LayaV1State
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agent turn, from utterance to sentence, over a real socket.
 *
 * The server here is deliberately dumb: it answers with a body the test chooses. The point is not to
 * simulate LAYA's intelligence (there is none to simulate at this size) but to execute the whole path
 * — question set, encode, HTTP, strict decode, answer policy, user-facing wording — and check that
 * every failure mode produces a specific, honest sentence rather than a generic one.
 */
class LayaAgentTest {

    private var server: HttpServer? = null

    @After
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    private fun serve(status: Int, body: String, hits: AtomicInteger? = null): String {
        val instance = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        instance.createContext("/") { exchange: HttpExchange ->
            hits?.incrementAndGet()
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        instance.start()
        server = instance
        return "http://127.0.0.1:${instance.address.port}"
    }

    private fun agent(baseUrl: String?, canExecute: Boolean = false): LayaAgent = LayaAgent(
        client = LayaV1Client(
            config = if (baseUrl == null) LayaV1Config.NOT_CONFIGURED else LayaV1Config(baseUrl, timeoutMs = 5_000),
            transport = LayaUrlTransport(),
        ),
        canExecute = canExecute,
    )

    private fun captured(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("laya-live/$name")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }

    // ---- the honest failure paths, which are the common paths in this build --------------------

    @Test
    fun anUnconfiguredEngineSaysHowToConfigureItAndMakesNoCall() {
        val hits = AtomicInteger()
        // No server at all: nothing could be contacted even if the client tried.
        val turn = agent(null).handle("send a message to Asha", "en-IN")
        assertTrue(turn is LayaAgent.Turn.EngineUnavailable)
        assertTrue(turn.userMessage.contains("not configured"))
        assertTrue(turn.userMessage.contains("Settings"))
        assertEquals(0, hits.get())
    }

    @Test
    fun anUnreachableEngineIsDistinctFromAnUnconfiguredOne() {
        // A port that is closed: bind, note the port, stop the server.
        val base = serve(200, "{}")
        server!!.stop(0)
        server = null
        val turn = agent(base).handle("call Asha", "en-IN")
        assertTrue(turn is LayaAgent.Turn.EngineUnavailable)
        val message = turn.userMessage
        assertTrue("should not claim 'not configured': $message", !message.contains("not configured"))
        assertTrue("should say it could not be reached: $message", message.contains("could not be reached"))
    }

    @Test
    fun aRejectedRequestSurfacesTheServersOwnExplanation() {
        val base = serve(422, """{"detail":"unknown model '/nonexistent/ckpt'; choose one of [...]"}""")
        val turn = agent(base).handle("send a message", "en-IN")
        assertTrue(turn is LayaAgent.Turn.EngineUnavailable)
        assertTrue(turn.userMessage.contains("rejected the request"))
        assertTrue(
            "the server's precise reason must survive: ${turn.userMessage}",
            turn.userMessage.contains("unknown model"),
        )
    }

    @Test
    fun aMalformedAnswerIsRefusedNotPardoned() {
        // A 200 whose body is not a prediction. Optimistically parsing this is how an agent ends up
        // acting on something nobody said.
        val base = serve(200, """{"answers": {"intent": {"type": "nonsense"}}}""")
        val turn = agent(base).handle("send a message", "en-IN")
        assertTrue("expected a refusal, got $turn", turn is LayaAgent.Turn.Refused)
        assertTrue(turn.userMessage.contains("could not be trusted"))
    }

    @Test
    fun aNonJsonBodyIsRefusedRatherThanTreatedAsEmpty() {
        val base = serve(200, "<html>proxy error</html>")
        val turn = agent(base).handle("send a message", "en-IN")
        assertTrue(turn is LayaAgent.Turn.Refused)
    }

    // ---- the real captured answer, through the real policy ------------------------------------

    @Test
    fun theCapturedFixtureAnswerBecomesAQuestionForTheUserNotAnAction() {
        val base = serve(200, captured("prediction.json"))
        val turn = agent(base).handle("send a message to Asha saying I am late", "en-IN")
        // The fixture checkpoint collapsed every option, so SAMAVETA refuses first and says exactly
        // why. The important property: it is not a Proposal.
        assertTrue("a collapsed-option answer must never be a proposal: $turn", turn !is LayaAgent.Turn.Proposed)
        assertTrue(turn is LayaAgent.Turn.Refused)
        assertTrue(
            "the refusal must name the real cause: ${turn.userMessage}",
            turn.userMessage.contains("could not give each option"),
        )
        assertTrue(turn.userMessage.contains("Nothing was acted on"))
    }

    @Test
    fun nothingIsEverClaimedToHaveRun() {
        val base = serve(200, captured("prediction.json"))
        val turn = agent(base, canExecute = false).handle("send a message", "en-IN")
        assertFalse(turn.userMessage.contains("Sent"))
        assertFalse(turn.userMessage.contains("Done"))
        assertTrue(
            "an unexecutable build must say so: ${turn.userMessage}",
            turn.userMessage.contains("Nothing was executed") || turn.userMessage.contains("Nothing was acted on"),
        )
    }

    @Test
    fun theConnectionStateOnlyBecomesConnectedAfterASuccessfulCall() {
        val base = serve(200, captured("prediction.json"))
        val instance = agent(base)
        assertEquals(LayaV1State.NEVER_REACHED, instance.state())
        instance.handle("send a message", "en-IN")
        assertEquals(LayaV1State.CONNECTED, instance.state())
    }

    @Test
    fun aFailedCallLeavesTheStateDegradedRatherThanConnected() {
        val base = serve(500, """{"detail":"inference failed"}""")
        val instance = agent(base)
        val turn = instance.handle("send a message", "en-IN")
        assertTrue(turn is LayaAgent.Turn.EngineUnavailable)
        assertEquals(LayaV1State.DEGRADED, instance.state())
        assertFalse(instance.state().isOperational)
    }

    @Test
    fun anEmptyUtteranceIsNotSentToTheEngine() {
        val hits = AtomicInteger()
        val base = serve(200, captured("prediction.json"), hits)
        val turn = agent(base).handle("   ", "en-IN")
        assertTrue(turn is LayaAgent.Turn.Clarify)
        assertEquals("whitespace is not an instruction worth a network call", 0, hits.get())
    }

    // ---- a confident, well-formed answer ------------------------------------------------
    //
    // The captured fixture cannot produce one (its options collapse), so this case is built by hand.
    // It is the only place in the suite where a confident answer exists, and it exists to prove the
    // happy path is reachable and that SAMAVETA — not the model — still decides about confirmation.

    private fun confidentPrediction(
        intent: String = "command",
        capabilityId: String = com.samaveta.android.core.security.Capability.entries.first().id,
        noulYes: Double = 0.0,
        consequence: Double = 0.0,
    ): String {
        fun choice(id: String, labels: List<String>, picked: String, p: Double): String {
            val probabilities = labels.associateWith { if (it == picked) p else (1 - p) / (labels.size - 1) }
            return """{"type":"choice","instructions":"","choice":"$picked","answer_confidence":$p,""" +
                """"probabilities":${probabilities.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }}}"""
        }
        val intentLabels = listOf("command", "question", "conversation")
        val capabilityLabels = com.samaveta.android.core.security.Capability.ids().sorted()
        return """
        {
          "model": "test",
          "answers": {
            "intent": ${choice("intent", intentLabels, intent, 0.97)},
            "capability": ${choice("capability", capabilityLabels, capabilityId, 0.91)},
            "needs_confirmation": {"type":"noul","instructions":"","answer_confidence":0.9,"noul":$noulYes,"confidence":0.9},
            "sensitivity": {"type":"score","instructions":"","answer_confidence":0.8,"score":$consequence,"legend":{"0":"trivial","1":"personal","2":"consequential","3":"irreversible"},"probabilities":{"0":0.25,"1":0.25,"2":0.25,"3":0.25}}
          },
          "usage": {"input_tokens": 40, "output_tokens": 0, "state_tokens": 1, "state_tokens_dropped": 0, "truncated": false, "truncated_questions": []},
          "routing": {"model": "test", "reason": "unit test"}
        }
        """.trimIndent()
    }

    @Test
    fun aConfidentAnswerIsProposedAndStillSaysNothingRan() {
        val capability = com.samaveta.android.core.security.Capability.entries.first()
        val base = serve(200, confidentPrediction(capabilityId = capability.id))
        val turn = agent(base).handle("do the thing", "en-IN")
        assertTrue("expected a proposal, got $turn", turn is LayaAgent.Turn.Proposed)
        turn as LayaAgent.Turn.Proposed
        assertEquals(capability.id, turn.capability.id)
        assertTrue("an unexecutable build must say nothing ran", turn.userMessage.contains("Nothing was executed"))
        if (turn.capability.mutatesState) {
            assertTrue("a state-changing capability always needs approval", turn.requiresConfirmation)
        }
    }

    @Test
    fun aConfidentModelSayingNoConfirmationCannotRemoveIt() {
        val mutating = com.samaveta.android.core.security.Capability.entries.first { it.mutatesState }
        val base = serve(200, confidentPrediction(capabilityId = mutating.id, noulYes = 0.0, consequence = 0.0))
        val turn = agent(base).handle("do the thing", "en-IN") as LayaAgent.Turn.Proposed
        assertTrue("SAMAVETA decides, not the model", turn.requiresConfirmation)
        assertTrue(turn.userMessage.contains("needs your approval"))
    }

    @Test
    fun anInstructionIsNotReportedAsAConversation() {
        val base = serve(200, confidentPrediction(intent = "conversation"))
        val turn = agent(base).handle("thanks!", "en-IN")
        assertTrue(turn is LayaAgent.Turn.Conversation)
        assertTrue(turn.userMessage.contains("Nothing was executed"))
    }
}
