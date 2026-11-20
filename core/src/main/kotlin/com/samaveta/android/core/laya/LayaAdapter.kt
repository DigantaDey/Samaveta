package com.samaveta.android.core.laya

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException

/**
 * Configuration for the LAYA driver. Present but empty by default: SAMAVETA ships without a LAYA
 * endpoint, because no production contract has been supplied.
 */
data class LayaConfig(
    val endpoint: String?,
    val timeoutMs: Long = 4_000,
    val maxRetries: Int = 1,
    val retryBackoffMs: Long = 250,
) {
    init {
        require(timeoutMs in 100..60_000) { "timeout must be between 100ms and 60s" }
        require(maxRetries in 0..3) { "retries must be between 0 and 3" }
        require(endpoint == null || endpoint.startsWith("https://")) {
            "LAYA endpoint must be https (no plaintext transport for conversational data)"
        }
    }

    val isConfigured: Boolean get() = !endpoint.isNullOrBlank()

    companion object {
        /** The honest default: nothing configured. */
        val NOT_CONFIGURED = LayaConfig(endpoint = null)
    }
}

/**
 * Drives LAYA. Owns everything SAMAVETA is responsible for around the external engine:
 * timeout, bounded retry, cancellation, error taxonomy and response validation.
 *
 * It does NOT contain any decision logic. If [transport] is null or [LayaConfig.isConfigured] is
 * false, [decide] fails with [LayaFailure.NotConfigured]; SAMAVETA then either uses an explicitly
 * documented deterministic path for that feature or tells the user LAYA is unavailable.
 */
class LayaAdapter(
    private val config: LayaConfig,
    private val transport: LayaTransport?,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    val state: LayaIntegrationState
        get() = when {
            transport == null || !config.isConfigured -> LayaIntegrationState.NOT_CONFIGURED
            transport.kind == TransportKind.MOCK -> LayaIntegrationState.MOCK_TEST_ONLY
            else -> LayaIntegrationState.CONFIGURED_UNVERIFIED
        }

    /**
     * Sends [request] to LAYA and returns a validated decision.
     *
     * Retries only on transient transport errors — never on a timeout that already consumed the
     * caller's deadline, and never on a malformed response (repeating a bad request is pointless
     * and could duplicate a side effect on the LAYA side).
     */
    suspend fun decide(request: LayaRequest): LayaOutcome {
        val started = clock()
        if (transport == null || !config.isConfigured) {
            return LayaOutcome.Failed(LayaFailure.NotConfigured(), clock() - started)
        }

        val budget = remainingBudgetMs(request.deadlineEpochMs, clock())
        if (budget <= 0) {
            return LayaOutcome.Failed(LayaFailure.Timeout(0), clock() - started)
        }

        val payload = LayaWireContract.encodeRequest(request)
        var attempt = 0
        var lastFailure: LayaFailure? = null

        while (attempt <= config.maxRetries) {
            val remaining = minOf(budget, config.timeoutMs)
            if (remaining <= 0) break
            val outcome = withTimeoutOrNull(remaining) {
                try {
                    LayaOutcome.Success(
                        LayaWireContract.decodeResponse(request.requestId, transport.exchange(payload, remaining)),
                        clock() - started,
                    )
                } catch (e: LayaFailure) {
                    LayaOutcome.Failed(e, clock() - started)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LayaOutcome.Failed(LayaFailure.Transport(e.message ?: e::class.simpleName.orEmpty(), e), clock() - started)
                }
            } ?: LayaOutcome.Failed(LayaFailure.Timeout(remaining), clock() - started)

            when (val result = outcome) {
                is LayaOutcome.Success -> return result
                is LayaOutcome.Failed -> {
                    lastFailure = result.failure
                    val retryable = result.failure is LayaFailure.Transport
                    if (!retryable || attempt == config.maxRetries) return result
                }
            }
            attempt++
            if (attempt <= config.maxRetries) delay(config.retryBackoffMs * attempt)
        }

        return LayaOutcome.Failed(
            lastFailure ?: LayaFailure.Timeout(config.timeoutMs),
            clock() - started,
        )
    }

    private fun remainingBudgetMs(deadlineEpochMs: Long, now: Long): Long = deadlineEpochMs - now
}

/**
 * Mock transport. Used only by integration tests to prove the adapter's contract handling.
 *
 * It is NOT a decision engine: it returns whatever scripted responses the test supplies. Wiring it
 * into a release build is prevented by `LayaIntegrationGuard`.
 */
class MockLayaTransport(
    private val responses: ArrayDeque<() -> String> = ArrayDeque(),
    private val delayMs: Long = 0,
) : LayaTransport {

    val sentRequests = mutableListOf<String>()

    override val kind: TransportKind = TransportKind.MOCK

    fun enqueue(responseBody: () -> String) {
        responses.addLast(responseBody)
    }

    override suspend fun exchange(requestJson: String, timeoutMs: Long): String {
        sentRequests += requestJson
        if (delayMs > 0) delay(delayMs)
        val next = responses.removeFirstOrNull()
            ?: throw LayaFailure.Unavailable("mock transport has no scripted response left")
        return next()
    }
}

/**
 * Release gate for the LAYA integration. A build must not ship claiming LAYA works when only the
 * mock is present.
 */
object LayaIntegrationGuard {

    fun releaseBlockers(state: LayaIntegrationState): List<String> = when (state) {
        LayaIntegrationState.VERIFIED, LayaIntegrationState.CONFIGURED_UNVERIFIED -> {
            if (state == LayaIntegrationState.CONFIGURED_UNVERIFIED) {
                listOf("LAYA is configured but has not been verified against the real implementation.")
            } else {
                emptyList()
            }
        }

        LayaIntegrationState.MOCK_TEST_ONLY ->
            listOf("LAYA mock adapter is active; this build must not be released as an operational LAYA integration.")

        LayaIntegrationState.NOT_CONFIGURED ->
            listOf("LAYA contract not available: autonomous decision pipeline is non-operational.")

        LayaIntegrationState.DEGRADED ->
            listOf("LAYA health checks are failing.")
    }
}
