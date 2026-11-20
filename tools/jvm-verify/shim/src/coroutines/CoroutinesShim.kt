/*
 * SAMAVETA — offline JVM verification shim for the small slice of kotlinx.coroutines that `core`
 * uses. The real Gradle build compiles `core` against kotlinx-coroutines-core; this file lets the
 * same sources run in the offline sandbox. Only single-threaded, immediate execution is provided —
 * concurrency behaviour is asserted in instrumentation tests on device, not here.
 */
@file:Suppress("unused", "UNUSED_PARAMETER")

package kotlinx.coroutines

open class CancellationException(message: String? = null) : IllegalStateException(message)

/** Marker for suspending entry points; the shim runs everything inline. */
interface CoroutineScope

suspend fun <T> withTimeoutOrNull(timeoutMs: Long, block: suspend () -> T): T? {
    if (timeoutMs <= 0) return null
    return block()
}

suspend fun <T> withTimeout(timeoutMs: Long, block: suspend () -> T): T =
    withTimeoutOrNull(timeoutMs, block) ?: throw TimeoutCancellationException("timed out after ${timeoutMs}ms")

class TimeoutCancellationException(message: String) : CancellationException(message)

suspend fun delay(timeMillis: Long) {
    if (timeMillis > 0) Thread.sleep(timeMillis)
}

suspend fun <T> async(block: suspend () -> T): Deferred<T> = Deferred(block)

class Deferred<T>(private val block: suspend () -> T) {
    suspend fun await(): T = block()
}

suspend fun awaitAll(vararg deferreds: Deferred<*>): List<Any?> = deferreds.map { it.await() }

/**
 * Runs a suspending body to completion on the calling thread. Mirrors kotlinx.coroutines.runBlocking,
 * which is what the real Gradle build links against, so tests stay source-compatible.
 *
 * Every suspension primitive in this shim completes inline (delay == Thread.sleep), so a test body
 * either returns its value directly or resumes the continuation before returning.
 */
@Suppress("UNCHECKED_CAST")
fun <T> runBlocking(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    val completion = object : kotlin.coroutines.Continuation<T> {
        override val context: kotlin.coroutines.CoroutineContext
            get() = kotlin.coroutines.EmptyCoroutineContext

        override fun resumeWith(result: Result<T>) {
            outcome = result
        }
    }
    val immediate = (block as Function1<kotlin.coroutines.Continuation<T>, Any?>).invoke(completion)
    if (immediate !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) return immediate as T
    return requireNotNull(outcome) { "coroutine suspended without resuming; the shim only supports inline completion" }
        .getOrThrow()
}
