package com.samaveta.android.core

import com.samaveta.android.core.laya.v1.LayaHttpTransport
import com.samaveta.android.core.laya.v1.LayaPredictRequest
import com.samaveta.android.core.laya.v1.LayaQuestions
import com.samaveta.android.core.laya.v1.LayaUrlTransport
import com.samaveta.android.core.laya.v1.LayaV1Client
import com.samaveta.android.core.laya.v1.LayaV1Config
import com.samaveta.android.core.laya.v1.LayaV1Failure
import com.samaveta.android.core.laya.v1.LayaV1State
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The LAYA transport, executed against a **real socket**.
 *
 * Every other LAYA test drives a fake `LayaHttpTransport`, which proves the client's policy but not
 * that anything can actually speak HTTP. Here a loopback `HttpServer` stands in for `laya-serve`, and
 * `LayaUrlTransport` does the real thing: connect, write a body, read a bounded response, carry an
 * Authorization header. The server is not a mock of LAYA; it is a socket, and the assertions are about
 * what SAMAVETA's own transport does with what a server sends back.
 */
class LayaTransportTest {

    private var server: HttpServer? = null

    @After
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    /** Starts a server whose single handler is [handler], and returns its base URL. */
    private fun serve(handler: (HttpExchange) -> Unit): String {
        val instance = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        instance.createContext("/") { exchange ->
            try {
                handler(exchange)
            } catch (failure: Throwable) {
                System.err.println("handler failed: $failure")
                try {
                    exchange.sendResponseHeaders(500, -1)
                } catch (ignored: Throwable) {
                    // The response may already have been committed; nothing useful to do here.
                }
            } finally {
                exchange.close()
            }
        }
        instance.start()
        server = instance
        return "http://127.0.0.1:${instance.address.port}"
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    // ---- what the transport sends -------------------------------------------------------------

    @Test
    fun aPostCarriesTheBodyTheMethodAndTheBearerToken() {
        val seenBody = AtomicReference<String>()
        val seenAuth = AtomicReference<String>()
        val seenMethod = AtomicReference<String>()
        val base = serve { exchange ->
            seenMethod.set(exchange.requestMethod)
            seenAuth.set(exchange.requestHeaders.getFirst("Authorization"))
            seenBody.set(exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) })
            respond(exchange, 200, """{"ok":true}""")
        }

        val transport = LayaUrlTransport(apiKey = "integration-test")
        val response = transport.call("$base/v1/systemone", "POST", """{"state":"hi"}""", 5_000)

        assertEquals(200, response.statusCode)
        assertEquals("""{"ok":true}""", response.body)
        assertEquals("POST", seenMethod.get())
        assertEquals("Bearer integration-test", seenAuth.get())
        assertEquals("""{"state":"hi"}""", seenBody.get())
    }

    @Test
    fun noApiKeyMeansNoAuthorizationHeaderAtAll() {
        val seenAuth = AtomicReference<String>("unset")
        val base = serve { exchange ->
            seenAuth.set(exchange.requestHeaders.getFirst("Authorization"))
            respond(exchange, 200, "{}")
        }
        LayaUrlTransport(apiKey = null).call("$base/health", "GET", null, 5_000)
        // An empty header would still be a header. Absent is absent.
        assertNull(seenAuth.get())
    }

    // ---- what the transport refuses to do -----------------------------------------------------

    @Test
    fun aRedirectIsReturnedRatherThanFollowed() {
        val base = serve { exchange ->
            exchange.responseHeaders.add("Location", "https://evil.example/collect")
            exchange.sendResponseHeaders(302, -1)
        }
        val response = LayaUrlTransport().call("$base/v1/systemone", "GET", null, 5_000)
        assertEquals(302, response.statusCode)
        assertEquals("", response.body)
        // Nothing followed it: the transport never contacted the target, and the caller decides.
    }

    @Test
    fun plaintextToARemoteHostIsRefusedBeforeAnyConnection() {
        try {
            LayaUrlTransport().call("http://laya.example.com/health", "GET", null, 1_000)
            fail("plaintext to a remote host must not be attempted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("https"))
        }
    }

    @Test
    fun loopbackHttpIsTheOnlyPlaintextExceptionAllowed() {
        val base = serve { exchange -> respond(exchange, 200, "{}") }
        assertTrue(base.startsWith("http://127.0.0.1:"))
        // This one succeeds precisely because it is loopback.
        assertEquals(200, LayaUrlTransport().call("$base/health", "GET", null, 5_000).statusCode)

        listOf("http://192.168.1.10:8000/health", "http://localhost.evil.example/health")
            .forEach { hostile ->
                try {
                    LayaUrlTransport().call(hostile, "GET", null, 500)
                    fail("$hostile should have been refused")
                } catch (expected: IllegalArgumentException) {
                    assertTrue(expected.message!!.contains("https"))
                }
            }
    }

    @Test
    fun aPostErrorBodyIsNotLostToStreamingModeBuffering() {
        // Regression: with `setFixedLengthStreamingMode`, a JDK HttpURLConnection returns a null
        // error stream for a POST that fails, so LAYA's `detail` vanished. The transport no longer
        // uses that mode; this test is what keeps it that way.
        val detail = """{"detail":"max_len exceeds server limit (999999 > 8192)"}"""
        val base = serve { exchange ->
            exchange.requestBody.use { it.readBytes() }
            respond(exchange, 422, detail)
        }
        val response = LayaUrlTransport().call(
            "$base/v1/systemone",
            "POST",
            """{"state":"a state long enough to be a real body","questions":{}}""",
            5_000,
        )
        assertEquals(422, response.statusCode)
        assertEquals(detail, response.body)
    }

    @Test
    fun anOversizedResponseIsDiscardedRatherThanReadIntoMemory() {
        val base = serve { exchange ->
            val big = "x".repeat(200_000)
            respond(exchange, 200, big)
        }
        try {
            LayaUrlTransport(maxResponseBytes = 1_024).call("$base/health", "GET", null, 10_000)
            fail("a 200 KB body must not be accepted under a 1 KB cap")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!.contains("exceeded"))
        }
    }

    @Test
    fun aHangingServerBecomesATimeoutNotAHang() {
        val base = serve { exchange ->
            Thread.sleep(3_000)
            respond(exchange, 200, "{}")
        }
        val started = System.currentTimeMillis()
        try {
            LayaUrlTransport().call("$base/health", "GET", null, 500)
            fail("expected a timeout")
        } catch (expected: LayaV1Failure.Timeout) {
            val elapsed = System.currentTimeMillis() - started
            assertTrue("timeout took ${elapsed}ms, expected under 2.5s", elapsed < 2_500)
        }
    }

    @Test
    fun anErrorBodyIsKeptBecauseLAYANamesTheFieldThatWasWrong() {
        val detail = """{"detail":"request body must be an object with 'states' and 'questions' fields"}"""
        val base = serve { exchange -> respond(exchange, 400, detail) }
        val response = LayaUrlTransport().call("$base/v1/systemone/batch", "POST", "{}", 5_000)
        assertEquals(400, response.statusCode)
        assertEquals(detail, response.body)
    }

    // ---- the whole path, over a real socket ---------------------------------------------------

    @Test
    fun aCapturedPredictionTravelsOverARealSocketAndIsStillRefusedByPolicy() {
        // The body served here is the payload captured from a running laya-serve, byte for byte.
        val captured = javaClass.classLoader!!
            .getResourceAsStream("laya-live/prediction.json")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }
        val receivedPath = AtomicReference<String>()
        val base = serve { exchange ->
            receivedPath.set(exchange.requestURI.path)
            respond(exchange, 200, captured)
        }

        val transport: LayaHttpTransport = LayaUrlTransport(apiKey = "integration-test")
        val client = LayaV1Client(
            config = LayaV1Config(baseUrl = base, apiKey = "integration-test", timeoutMs = 5_000),
            transport = transport,
        )
        assertEquals(LayaV1State.NEVER_REACHED, client.state)

        val prediction = client.predict(
            LayaPredictRequest(
                state = LayaQuestions.state("send a message to Asha", "en-IN"),
                questions = LayaQuestions.core("en-IN"),
                minConfidence = com.samaveta.android.core.laya.v1.MinConfidence.Single(0.5),
            ),
        )

        assertEquals("/v1/systemone", receivedPath.get())
        // Only a real 2xx may upgrade the state: this is what "connected" means.
        assertEquals(LayaV1State.CONNECTED, client.state)
        assertNull(client.lastFailure)
        assertEquals(36, prediction.usage.inputTokens)
        assertEquals(0, prediction.usage.outputTokens)

        // And the fixture checkpoint's near-uniform answer is refused rather than acted on. The
        // transport being real does not make the model's opinion usable.
        val reading = LayaQuestions.read(prediction)
        assertTrue(
            "a collapsed-option response must never become a proposal, got $reading",
            reading !is LayaQuestions.Reading.Proposal,
        )
    }

    @Test
    fun aHealthyServerThatRejectsTheTokenIsNotReportedAsConnected() {
        // Verified live against laya-serve: /health is UNAUTHENTICATED, so it answers 200 with a wrong
        // token while every prediction is refused with 401. The first version of this client upgraded
        // itself to CONNECTED on that health check and then failed every message, which is exactly the
        // "claims to be connected while nothing works" defect this project exists to avoid.
        val health = javaClass.classLoader!!
            .getResourceAsStream("laya-live/health.json")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }
        val base = serve { exchange ->
            if (exchange.requestURI.path == "/health") {
                respond(exchange, 200, health)
            } else {
                respond(exchange, 401, """{"detail":"invalid or missing bearer token"}""")
            }
        }
        val client = LayaV1Client(
            config = LayaV1Config(baseUrl = base, apiKey = "wrong", timeoutMs = 5_000),
            transport = LayaUrlTransport(apiKey = "wrong"),
        )

        // Health alone still looks fine — that is the trap.
        assertTrue(client.health().isAlive)
        assertEquals(LayaV1State.CONNECTED, client.state)

        // A real call exposes it, and the state must fall to UNAUTHORISED rather than DEGRADED.
        try {
            client.predict(
                LayaPredictRequest(
                    state = LayaQuestions.state("hi", "en"),
                    questions = LayaQuestions.core("en"),
                ),
            )
            fail("expected the 401 to surface")
        } catch (expected: LayaV1Failure) {
            assertTrue(expected.message!!.contains("invalid or missing bearer token"))
        }
        assertTrue(client.credentialsRejected)
        assertEquals(LayaV1State.UNAUTHORISED, client.state)
        assertFalse("a refused token is not a working connection", client.state.isOperational)

        // And a later successful health check must not be allowed to launder that back to CONNECTED.
        assertTrue(client.health().isAlive)
        assertEquals(LayaV1State.UNAUTHORISED, client.state)
    }

    @Test
    fun aRejectedCallLeavesTheClientVisiblyDegradedWithTheServersOwnWords() {
        val base = serve { exchange ->
            respond(exchange, 401, """{"detail":"invalid or missing bearer token"}""")
        }
        val client = LayaV1Client(
            config = LayaV1Config(baseUrl = base, timeoutMs = 5_000),
            transport = LayaUrlTransport(),
        )
        try {
            client.health()
            fail("expected the 401 to surface")
        } catch (failure: LayaV1Failure) {
            // Whatever the taxonomy calls it, the server's own detail must survive to the message.
            assertTrue(failure.message!!.contains("invalid or missing bearer token"))
        }
        assertFalse("a failed call must not be reported as connected", client.state == LayaV1State.CONNECTED)
        // A 401 is a credential problem, not a flaky server: it gets its own state so Settings can say
        // what to fix.
        assertEquals(LayaV1State.UNAUTHORISED, client.state)
    }

    @Test
    fun aHealthResponseOverARealSocketIsTheOnlyThingThatSaysConnected() {
        val health = javaClass.classLoader!!
            .getResourceAsStream("laya-live/health.json")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }
        val base = serve { exchange -> respond(exchange, 200, health) }
        val client = LayaV1Client(
            config = LayaV1Config(baseUrl = base, timeoutMs = 5_000),
            transport = LayaUrlTransport(),
        )
        val decoded = client.health()
        assertTrue(decoded.isAlive)
        assertEquals(LayaV1State.CONNECTED, client.state)
        assertTrue(client.state.isOperational)
        // The captured health body is LAYA's own reporting, not a SAMAVETA claim.
        assertTrue(decoded.status.isNotBlank())
    }
}
