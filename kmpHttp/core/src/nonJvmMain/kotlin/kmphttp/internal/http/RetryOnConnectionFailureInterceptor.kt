package kmphttp.internal.http

import kmpcommon.log.debug
import kmphttp.AsyncInterceptor
import kmphttp.Request
import kmphttp.Response
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A [TransportIOException] for a failure that happened while *establishing* the connection — DNS, TCP
 * connect, or the TLS handshake — i.e. before the request was (necessarily) delivered, so retrying on
 * a fresh connection is safe. Engines throw this for their recoverable connection-level error codes;
 * [RetryOnConnectionFailureInterceptor] is what retries it. Cert/pin verification failures are NOT
 * recoverable and must never be this type, so they surface immediately.
 */
internal class RecoverableIOException(message: String, cause: Throwable? = null) :
    TransportIOException(message, cause)

/**
 * Retries a request that failed with a [RecoverableIOException] (a transient connection-establishment
 * failure, e.g. the server resetting the TLS handshake) on a fresh connection — mirroring OkHttp's
 * `retryOnConnectionFailure`, which is why the JVM/OkHttp engine doesn't see this flakiness. Bounded
 * by [maxRetries] with a small jittered backoff so we don't hammer a struggling server. Only retries
 * when the request body can be re-sent (absent or not one-shot).
 */
internal class RetryOnConnectionFailureInterceptor(
    private val maxRetries: Int = 3,
    private val baseBackoff: Duration = 150.milliseconds,
    private val logger: kmpcommon.log.Logger? = null,
) : AsyncInterceptor {

    override suspend fun intercept(chain: AsyncInterceptor.Chain): Response {
        var attempt = 0
        val earlierFailures = mutableListOf<Throwable>()
        while (true) {
            try {
                return chain.proceed(chain.request)
            } catch (e: Throwable) {
                if (e !is RecoverableIOException || attempt >= maxRetries || !chain.request.isRetryable()) {
                    if (e !is CancellationException) earlierFailures.forEach(e::addSuppressed)
                    throw e
                }
                earlierFailures += e
                attempt++
                // Silent retries make a flaky network look like a slow one — leave a trace.
                logger?.debug { "retry $attempt/$maxRetries for ${chain.request.url.host}: ${e.message}" }
                val jitter = Random.nextLong(0, baseBackoff.inWholeMilliseconds + 1).milliseconds
                delay(baseBackoff * attempt + jitter)
            }
        }
    }

    private fun Request.isRetryable(): Boolean {
        val body = this.body
        return body == null || !body.isOneShot()
    }
}
