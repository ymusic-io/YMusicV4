package kmphttp

import kmphttp.internal.AsyncChain
import kmphttp.internal.CallTraceListener
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.EOFException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

class OkKmpHttpClient : KmpHttpClient {
    override val interceptors: List<AsyncInterceptor>
    override val networkInterceptors: List<AsyncInterceptor>
    override val cache: Cache?
    override val cookieJar: CookieJar
    override val sslConfig: SslConfig
    override val defaultRedirectOptions: RedirectOptions
    override val defaultExecuteTimeout: Duration?
    override val defaultDispatcher: CoroutineDispatcher
    override val logger: kmpcommon.log.Logger?

    val okHttp: OkHttpClient

    /**
     * OkHttpClient variants keyed by redirect behaviour (see [redirectIndex]). All share the base
     * client's connection pool, dispatcher, cache, and TLS config via [OkHttpClient.newBuilder];
     * only the redirect flags differ. Lazily filled, guarded by [synchronized].
     */
    private val clients = arrayOfNulls<OkHttpClient>(3)

    constructor(builder: Builder) {
        this.interceptors = builder.interceptors.toList()
        this.networkInterceptors = builder.networkInterceptors.toList()
        this.cache = builder.cache
        this.cookieJar = builder.cookieJar
        this.sslConfig = builder.sslConfig
        this.defaultRedirectOptions = builder.defaultRedirectOptions
        this.defaultExecuteTimeout = builder.defaultExecuteTimeout
        this.defaultDispatcher = builder.defaultDispatcher
        this.logger = builder.logger

        this.okHttp = builder
            .okHttpBuilder
            .cache(builder.cache)
            .cookieJar(builder.cookieJar)
            .followRedirects(defaultRedirectOptions.followRedirects)
            .followSslRedirects(defaultRedirectOptions.followSslRedirects)
            .apply {
                sslConfig.applyTo(this)
                if (networkInterceptors().find { it is NetworkInterceptorBridge } == null) {
                    addNetworkInterceptor(NetworkInterceptorBridge())
                }
                if (interceptors().none { it is ReadTimeoutBridge }) addInterceptor(ReadTimeoutBridge())
                if (builder.interceptors.any { it is HttpLogInterceptor }) eventListenerFactory(CallTraceListener.factory)
            }
            .build()

        // Seed the cache: the base client already uses the default redirect options.
        clients[redirectIndex(defaultRedirectOptions)] = okHttp
    }

    /**
     * Maps redirect options to a [clients] slot. `followSslRedirects` is irrelevant when redirects
     * are disabled, so the four flag combinations collapse to three distinct clients.
     */
    private fun redirectIndex(options: RedirectOptions): Int = when {
        !options.followRedirects -> 0
        !options.followSslRedirects -> 1
        else -> 2
    }

    private fun clientFor(options: RedirectOptions): OkHttpClient {
        val index = redirectIndex(options)
        clients[index]?.let { return it }
        return synchronized(clients) {
            clients[index] ?: okHttp.newBuilder()
                .followRedirects(options.followRedirects)
                .followSslRedirects(options.followSslRedirects)
                .build()
                .also { clients[index] = it }
        }
    }

    override suspend fun execute(
        request: Request,
        options: RedirectOptions,
        timeout: Duration?
    ): Response {
        return withContext(defaultDispatcher) {
            val asyncChain = AsyncChain(
                interceptors = interceptors,
                interceptorIndex = 0,
                execute = { beforeNetworkRequest ->
                    val finalOkHttpClient = clientFor(options)

                    suspendCancellableCoroutine { cont ->
                        val request =
                            if (networkInterceptors.isNotEmpty() || timeout != null) {
                                beforeNetworkRequest.newBuilder()
                                    .apply {
                                        if (networkInterceptors.isNotEmpty()) {
                                            tag(OkKmpHttpClient::class, this@OkKmpHttpClient)
                                            tag(RedirectOptions::class, options)
                                            tag(Job::class, cont.context.job)
                                        }
                                        if (timeout != null) tag(ReadTimeout::class, ReadTimeout(timeout))
                                    }
                                    .build()
                            } else {
                                beforeNetworkRequest
                            }
                        val call = finalOkHttpClient.newCall(request)
                        cont.invokeOnCancellation { call.cancel() }   // runs on the canceller's thread, mid-flight
                        try {
                            val resp = call.execute()                  // blocks THIS thread
                            cont.resume(resp) { _, _, _ -> resp.close() }
                        } catch (e: Throwable) {
                            val failure = when {
                                // ReadTimeoutBridge's timeout fires just before the execute timeout: same contract.
                                timeout != null && e is SocketTimeoutException ->
                                    HttpTimeoutException("no response within $timeout").apply { initCause(e) }
                                // HTTP/2 gives every stream waiting on a dying connection the reader's EOFException.
                                e is EOFException -> cutOffResponse(e)
                                else -> e
                            }
                            if (cont.isActive) cont.resumeWithException(failure)
                        }
                    }
                },
                request = request,
                options = options
            )
            withExecuteTimeout(timeout) { asyncChain.proceed(request) }
        }
    }

    override fun newBuilder(): Builder {
        return Builder(this)
    }

    class Builder : KmpHttpClient.BaseBuilder<OkKmpHttpClient> {
        internal val okHttpBuilder: OkHttpClient.Builder

        constructor(okHttpBuilder: OkHttpClient.Builder = OkHttpClient.Builder()) : super() {
            this.okHttpBuilder = okHttpBuilder
        }

        constructor(copy: OkKmpHttpClient) : super(copy) {
            this.okHttpBuilder = copy.okHttp.newBuilder()
        }

        /** Bypass the JVM/macOS system proxy (Proxyman, mitmproxy, …). */
        fun direct(): Builder {
            okHttpBuilder.proxy(java.net.Proxy.NO_PROXY)
            return this
        }

        override fun build(): OkKmpHttpClient {
            return OkKmpHttpClient(this)
        }
    }

    private class ReadTimeout(executeTimeout: Duration) {
        val millis: Int = (executeTimeout.inWholeMilliseconds * READ_TIMEOUT_SHARE).toLong().coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * OkHttp's read timeout for the call, just under its execute timeout. The execute timeout cancels
     * the call, which never checks the connection; OkHttp's own timeout firing first does (an HTTP/2
     * degraded ping), so a silent pooled connection is retired instead of taking every next request.
     */
    private class ReadTimeoutBridge : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val readTimeout = chain.request().tag(ReadTimeout::class) ?: return chain.proceed(chain.request())
            return chain.withReadTimeout(readTimeout.millis, TimeUnit.MILLISECONDS).proceed(chain.request())
        }
    }

    private class NetworkInterceptorBridge : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val request = chain.request()
            val okKmp = request.tag(OkKmpHttpClient::class) ?: return chain.proceed(request)
            val redirectOptions =
                request.tag(RedirectOptions::class) ?: return chain.proceed(request)
            if (okKmp.networkInterceptors.isEmpty()) return chain.proceed(request)
            val parent = request.tag(Job::class) ?: return chain.proceed(request)
            // One-way linkage to the caller: SupervisorJob(parent) lets the caller's cancellation
            // reach the interceptors, but an ordinary network failure in here must NOT cancel the
            // caller's job tree. With runBlocking(parent) directly, every IOException from
            // chain.proceed first cancelled the caller (a failed child cancels a regular parent) —
            // taking down unrelated sibling work — and then arrived at an already-cancelled
            // continuation, where it was swallowed instead of surfacing from execute().
            val bridgeJob = SupervisorJob(parent)
            try {
                return runBlocking(bridgeJob) {
                    AsyncChain(
                        okKmp.networkInterceptors, 0,
                        { finalRequest -> chain.proceed(finalRequest) },
                        request, redirectOptions
                    ).proceed(request)
                }
            } finally {
                bridgeJob.complete() // don't linger as a child of the caller's job
            }
        }
    }
}

@Suppress("FunctionName")
actual fun KmpHttpClient.Companion.Builder(): KmpHttpClient.BaseBuilder<*> {
    return OkKmpHttpClient.Builder(OkHttpClient.Builder().dns(Ipv4PreferredDns))
}

private object Ipv4PreferredDns : Dns {
    // Returning both families would let fast fallback try IPv6 first.
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        return addresses.filter { it is Inet4Address }.ifEmpty { addresses }
    }
}

private const val READ_TIMEOUT_SHARE = 0.9
