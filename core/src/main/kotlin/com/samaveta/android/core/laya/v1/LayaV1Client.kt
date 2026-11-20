package com.samaveta.android.core.laya.v1

/**
 * The LAYA v1 HTTP surface as a seam, plus the adapter that owns everything SAMAVETA is responsible
 * for around it: timeouts, cancellation, admission back-off, and the mapping from HTTP to the
 * [LayaV1Failure] taxonomy.
 *
 * SAMAVETA contains **no decision logic**. It asks LAYA typed questions and applies its own policy to
 * the answers.
 */

/** One HTTP exchange. Deliberately dumb: no retries, no redirects, no interpretation. */
data class LayaHttpResponse(val statusCode: Int, val body: String)

fun interface LayaHttpTransport {
    /**
     * @param path one of `/health`, `/v1/systemone`, `/v1/systemone/batch`, `/v1/route`
     * @param body null for GET
     */
    fun call(path: String, method: String, body: String?, timeoutMs: Long): LayaHttpResponse
}

data class LayaV1Config(
    /** Base URL of a `laya-serve` deployment, e.g. `https://laya.internal:8000`. */
    val baseUrl: String?,
    val apiKey: String? = null,
    val timeoutMs: Long = 8_000,
    /** One retry is allowed, and only for a saturated server (503) or a dropped connection. */
    val maxRetries: Int = 1,
    val retryBackoffMs: Long = 400,
) {
    init {
        require(timeoutMs in 500..60_000) { "timeout must be between 500ms and 60s" }
        require(maxRetries in 0..2) { "retries must be between 0 and 2" }
        require(baseUrl == null || baseUrl.startsWith("https://") || isLoopback(baseUrl)) {
            "LAYA must be reached over https (loopback is allowed for on-device development)"
        }
    }

    val isConfigured: Boolean get() = !baseUrl.isNullOrBlank()

    companion object {
        /** The honest default: nothing configured. */
        val NOT_CONFIGURED = LayaV1Config(baseUrl = null)

        /**
         * A loopback exception exists because the realistic local deployment is `laya-serve` on the
         * same device (Termux/desktop companion). It is explicit rather than a general http allowance.
         */
        internal fun isLoopback(url: String): Boolean =
            url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost") || url.startsWith("http://[::1]")
    }
}

/** What SAMAVETA reports to the user about the connection. Mirrors the product's status vocabulary. */
enum class LayaV1State {
    NOT_CONFIGURED,
    /** Configured and reachable; health reported a live server. */
    CONNECTED,

    /** Configured, but the last call failed in a way the user should see. */
    DEGRADED,

    /** Configured but never successfully contacted yet. */
    NEVER_REACHED,
    ;

    val userLabel: String
        get() = when (this) {
            NOT_CONFIGURED -> "LAYA not connected"
            CONNECTED -> "LAYA connected"
            DEGRADED -> "LAYA degraded"
            NEVER_REACHED -> "LAYA not yet reached"
        }

    /** Only a health-confirmed connection may be described as connected. */
    val isOperational: Boolean get() = this == CONNECTED
}

/**
 * Drives LAYA over HTTP. Stateless apart from the last observed state, so it is cheap to construct
 * per call site and trivial to fake in tests.
 */
class LayaV1Client(
    private val config: LayaV1Config,
    private val transport: LayaHttpTransport?,
) {

    /** Last observed state. Updated by [health] and by failed calls; never optimistically upgraded. */
    var state: LayaV1State = if (config.isConfigured && transport != null) {
        LayaV1State.NEVER_REACHED
    } else {
        LayaV1State.NOT_CONFIGURED
    }
        private set

    /** Last failure, for diagnostics. Cleared by a successful call. */
    var lastFailure: LayaV1Failure? = null
        private set

    fun health(): LayaHealth {
        val body = execute("/health", "GET", null)
        val health = LayaV1Codec.decodeHealth(body)
        if (health.isAlive) {
            state = LayaV1State.CONNECTED
            lastFailure = null
        } else {
            state = LayaV1State.DEGRADED
        }
        return health
    }

    fun route(request: LayaRouteRequest): LayaRouteSnapshot =
        LayaV1Codec.decodeRoute(execute("/v1/route", "POST", LayaV1Codec.encodeRoute(request)))

    fun predict(request: LayaPredictRequest): LayaPrediction =
        LayaV1Codec.decodePrediction(execute("/v1/systemone", "POST", LayaV1Codec.encodePredict(request)))

    fun predictBatch(request: LayaBatchRequest): List<LayaPrediction> =
        LayaV1Codec.decodeBatch(execute("/v1/systemone/batch", "POST", LayaV1Codec.encodeBatch(request)))

    /**
     * Runs one call with bounded retry.
     *
     * Retry policy is intentionally narrow, and informed by observed server behaviour:
     *
     *  - `422` will never succeed on a repeat (the request is malformed). Not retried.
     *  - A timeout may mean the server ran the inference anyway. Repeating it risks paying twice, so
     *    it is surfaced as [LayaV1Failure.Timeout], not retried.
     *  - `503` is the admission limiter. Retried once.
     *  - `500` is **deterministic**: `laya-serve` answers `{"detail":"inference failed"}` for a missing
     *    checkpoint, a broken graph or a missing runtime — all conditions a repeat cannot fix. It is
     *    therefore *not* retried, despite `test_serve.py` documenting that a *foreign* ASGI
     *    middleware's bare 503 can reach a client as this 500. Retrying that rare case would mean
     *    retrying every genuinely broken deployment, which is the worse trade.
     *  - A dropped connection is retried once.
     */
    private fun execute(path: String, method: String, body: String?): String {
        val baseUrl = config.baseUrl
        val transport = transport
        if (baseUrl == null || transport == null) {
            state = LayaV1State.NOT_CONFIGURED
            throw LayaV1Failure.NotConfigured()
        }

        var attempt = 0
        var lastError: LayaV1Failure? = null
        while (attempt <= config.maxRetries) {
            try {
                val response = transport.call(
                    path = baseUrl.trimEnd('/') + path,
                    method = method,
                    body = body,
                    timeoutMs = config.timeoutMs,
                )
                if (response.statusCode in 200..299) {
                    if (path != "/health") {
                        state = LayaV1State.CONNECTED
                        lastFailure = null
                    }
                    return response.body
                }
                val failure = LayaV1Codec.decodeError(response.statusCode, response.body)
                lastError = failure
                val retryable = failure is LayaV1Failure.Overloaded
                if (!retryable || attempt == config.maxRetries) {
                    markFailure(failure)
                    throw failure
                }
            } catch (failure: LayaV1Failure) {
                lastError = failure
                // Re-throw anything that is not worth repeating. Only Overloaded is retried above.
                markFailure(failure)
                throw failure
            } catch (interruption: InterruptedException) {
                Thread.currentThread().interrupt()
                throw LayaV1Failure.Unreachable("call interrupted", interruption)
            } catch (failure: Exception) {
                val unreachable = LayaV1Failure.Unreachable(failure.message ?: "connection failed", failure)
                lastError = unreachable
                if (attempt == config.maxRetries) throw unreachable
            }
            attempt++
            if (attempt <= config.maxRetries && config.retryBackoffMs > 0) {
                Thread.sleep(config.retryBackoffMs * attempt)
            }
        }
        val failure = lastError ?: LayaV1Failure.Unreachable("no attempt was made")
        markFailure(failure)
        throw failure
    }

    private fun markFailure(failure: LayaV1Failure) {
        lastFailure = failure
        state = if (failure is LayaV1Failure.NotConfigured) {
            LayaV1State.NOT_CONFIGURED
        } else {
            LayaV1State.DEGRADED
        }
    }
}
