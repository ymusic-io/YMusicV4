package kmphttp

import kmpcommon.io.AsyncSource
import kmpcommon.kilobytes
import kmphttp.MediaTypeX.toMediaTypeOrNull
import kmphttp.cache.CacheInterceptor
import kmphttp.internal.AsyncChain
import kmphttp.internal.AsyncSourceInputStream
import kmphttp.internal.ForwardingUrlSessionDataTaskDelegate
import kmphttp.internal.KMPHTTP_ERROR_EXCEPTION_KEY
import kmphttp.internal.SuspendCondition
import kmphttp.internal.http.BridgeInterceptor
import kmphttp.internal.http.FollowUpInterceptor
import kmphttp.internal.http.RecoverableIOException
import kmphttp.internal.http.RetryOnConnectionFailureInterceptor
import kmphttp.internal.http.TransportIOException
import kmphttp.internal.write
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.IOException
import platform.Foundation.HTTPBodyStream
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSHTTPCookieAcceptPolicy
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLErrorCallIsActive
import platform.Foundation.NSURLErrorCannotConnectToHost
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorDNSLookupFailed
import platform.Foundation.NSURLErrorDataNotAllowed
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorInternationalRoamingOff
import platform.Foundation.NSURLErrorNetworkConnectionLost
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSURLErrorSecureConnectionFailed
import platform.Foundation.NSURLErrorServerCertificateHasBadDate
import platform.Foundation.NSURLErrorServerCertificateHasUnknownRoot
import platform.Foundation.NSURLErrorServerCertificateNotYetValid
import platform.Foundation.NSURLErrorServerCertificateUntrusted
import platform.Foundation.NSURLErrorTimedOut
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.Foundation.NSUnderlyingErrorKey
import platform.Foundation.setHTTPBodyStream
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

internal fun defaultNsUrlSessionConfig() =
    NSURLSessionConfiguration.ephemeralSessionConfiguration().apply {
        // Disable automatic cookie handling - managed by BridgeInterceptor
        HTTPCookieAcceptPolicy = NSHTTPCookieAcceptPolicy.NSHTTPCookieAcceptPolicyNever
        HTTPShouldSetCookies = false
    }

/**
 * Handler for NSURLSession authentication challenges (e.g. self-signed cert trust, cert pinning).
 * Use [NSURLSessionAuthChallengeUseCredential], [NSURLSessionAuthChallengePerformDefaultHandling],
 * [NSURLSessionAuthChallengeCancelAuthenticationChallenge], etc. as disposition values.
 */
@OptIn(UnsafeNumber::class)
typealias AuthChallengeHandler = (
    challenge: NSURLAuthenticationChallenge,
    completionHandler: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit
) -> Unit

/**
 * Apple ([NSURLSession]-backed) implementation of [KmpHttpClient].
 *
 * The user [interceptors] run first, then the built-in redirect/cookie/cache chain
 * (FollowUp → Bridge → Cache) and [networkInterceptors], before the NSURLSession network call.
 *
 * [SslConfig] (custom trust anchors, certificate pinning, and PKCS#12 client certificates) is
 * applied via an NSURLSession auth-challenge handler. [Builder.onReceiveChallenge], if set, is the
 * fallback for challenges [SslConfig] doesn't handle.
 */
@OptIn(
    ExperimentalForeignApi::class,
    BetaInteropApi::class,
    ExperimentalAtomicApi::class,
    UnsafeNumber::class,
)
class UrlSessionKmpHttpClient(builder: Builder) : KmpHttpClient {
    override val interceptors: List<AsyncInterceptor> = builder.interceptors.toList()
    override val networkInterceptors: List<AsyncInterceptor> = builder.networkInterceptors.toList()
    override val cache: Cache? = builder.cache
    override val cookieJar: CookieJar = builder.cookieJar
    override val sslConfig: SslConfig = builder.sslConfig
    override val defaultRedirectOptions: RedirectOptions = builder.defaultRedirectOptions
    override val defaultExecuteTimeout: Duration? = builder.defaultExecuteTimeout
    override val defaultDispatcher: CoroutineDispatcher = builder.defaultDispatcher
    override val logger: kmpcommon.log.Logger? = builder.logger

    private val sessionConfig: NSURLSessionConfiguration = builder.sessionConfig
    private val onReceiveChallenge: AuthChallengeHandler? = builder.onReceiveChallenge
    private val userAgent: String = "kmpHttp/${KmpHttp.VERSION}"

    private var urlSessionFailure: IOException? = null
    private val forwardingDelegate: ForwardingUrlSessionDataTaskDelegate
    private val urlSession: NSURLSession
    private val taskIdGenerator = AtomicInt(0)
    private val released = AtomicBoolean(false)

    init {
        cache?.logger = logger
        sslConfig.warnIfClientCertsUnsupported("UrlSessionKmpHttpClient", logger) {
            it is SslConfig.ClientCert.Pkcs12
        }

        // Translate SslConfig into the NSURLSession challenge handler; user-provided handler is
        // the fallback for challenges SslConfig doesn't cover.
        val challengeHandler =
            if (sslConfig.isDefault()) onReceiveChallenge
            else sslConfig.toAuthChallengeHandler(onReceiveChallenge)

        forwardingDelegate = ForwardingUrlSessionDataTaskDelegate(
            onBecomeInvalid = { session, didBecomeInvalidWithError ->
                didBecomeInvalidWithError?.run {
                    urlSessionFailure = IOException("$domain: $code - $localizedDescription")
                }
                session.invalidateAndCancel()
            },
            onReceiveChallenge = challengeHandler,
        )
        urlSession = NSURLSession.sessionWithConfiguration(
            configuration = sessionConfig,
            delegate = forwardingDelegate,
            delegateQueue = null
        )
    }

    override suspend fun execute(
        request: Request,
        options: RedirectOptions,
        timeout: Duration?
    ): Response = withContext(defaultDispatcher) {
        val chain = AsyncChain(
            interceptors = buildList {
                addAll(interceptors)
                add(RetryOnConnectionFailureInterceptor(logger = logger))
                add(FollowUpInterceptor())
                add(BridgeInterceptor(cookieJar, userAgent))
                add(CacheInterceptor(cache))
                addAll(networkInterceptors)
            },
            interceptorIndex = 0,
            execute = ::executeNetwork,
            request = request,
            options = options
        )
        withExecuteTimeout(timeout) { chain.proceed(request) }
    }

    /** Invalidates the underlying NSURLSession. The client is unusable afterwards. */
    fun release() {
        if (released.compareAndSet(false, true)) {
            urlSession.invalidateAndCancel()
        }
    }

    override fun newBuilder(): Builder = Builder(this)

    private suspend fun executeNetwork(request: Request): Response {
        urlSessionFailure?.let { throw IOException("URL session invalidated", it) }
        if (released.load()) throw IllegalStateException("This instance is invalidated.")

        val sentRequestAtMillis = currentTimeMillis()

        val taskDesc = taskIdGenerator.incrementAndGet().toString()
        val responseHandler = StreamingResponseHandler {
            forwardingDelegate.unregister(taskDesc)
        }
        forwardingDelegate.register(taskDesc, responseHandler)

        val urlRequest = request.toNSURLRequest { error ->
            responseHandler.cancel(error)
        }
        val task = urlSession.dataTaskWithRequest(urlRequest)
        task.taskDescription = taskDesc
        responseHandler.attachTask(task)

        try {
            task.resume()

            // Wait for initial response (headers)
            val (httpResponse, receivedResponseAtMillis) = responseHandler.awaitResponse()

            // Build Response with streaming body
            return buildResponse(
                request,
                httpResponse,
                responseHandler,
                sentRequestAtMillis,
                receivedResponseAtMillis
            )
        } catch (e: Throwable) {
            responseHandler.close()
            urlRequest.HTTPBodyStream?.close()
            throw e
        }
    }

    private fun Request.toNSURLRequest(onBodyError: (Throwable) -> Unit): NSMutableURLRequest {
        val url = NSURL(string = this.url.toString())

        return NSMutableURLRequest.requestWithURL(url).apply {
            setHTTPMethod(method)
            setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)

            // Set headers
            for ((name, value) in headers) {
                setValue(value, forHTTPHeaderField = name)
            }

            // Set streaming body if present
            body?.let { requestBody ->
                if (headers["Content-Type"] == null) {
                    // without content-type set, URLSession sent application/x-www-form-urlencoded as default
                    // should be better with application/octet-stream instead
                    setValue("application/octet-stream", forHTTPHeaderField = "Content-Type")
                }
                val inputStream = AsyncSourceInputStream(requestBody.openRead(), onBodyError)
                setHTTPBodyStream(inputStream)
            }
        }
    }

    private fun buildResponse(
        request: Request,
        httpResponse: NSHTTPURLResponse,
        responseHandler: StreamingResponseHandler,
        sentRequestAtMillis: Long,
        receivedResponseAtMillis: Long,
    ): Response {
        val code = httpResponse.statusCode.toInt()
        val message = httpStatusMessage(code)
        val protocol = Protocol.HTTP_1_1
        val headers = httpResponse.toHeaders()
        val contentType = headers["Content-Type"]?.toMediaTypeOrNull()
        val contentLength = headers["Content-Length"]?.toLongOrNull() ?: -1L
        val body = StreamingResponseBody(contentType, contentLength, responseHandler)

        return ResponseBuilder()
            .request(request)
            .protocol(protocol)
            .code(code)
            .message(message)
            .headers(headers)
            .body(body)
            .sentRequestAtMillis(sentRequestAtMillis)
            .receivedResponseAtMillis(receivedResponseAtMillis)
            .build()
    }

    private fun NSHTTPURLResponse.toHeaders(): Headers {
        val builder = HeadersBuilder()

        @Suppress("UNCHECKED_CAST")
        val headerFields = allHeaderFields
        for ((key, value) in headerFields) {
            if (key is String && value is String) {
                if (key.equals("Set-Cookie", ignoreCase = true)) {
                    // NSHTTPURLResponse exposes headers as a dictionary and folds repeated
                    // Set-Cookie fields into one comma-delimited value. A blind comma split breaks
                    // Expires=Wed, 21 Oct ...; split only where the next token is a cookie name.
                    splitCombinedSetCookie(value).forEach {
                        builder.addUnsafeNonAscii(key, it)
                    }
                } else {
                    builder.addUnsafeNonAscii(key, value)
                }
            }
        }
        return builder.build()
    }

    private fun splitCombinedSetCookie(value: String): List<String> =
        value.split(SET_COOKIE_BOUNDARY).map(String::trim).filter(String::isNotEmpty)

    private fun currentTimeMillis(): Long {
        return (NSDate().timeIntervalSince1970 * 1000).toLong()
    }

    private companion object {
        val SET_COOKIE_BOUNDARY = Regex(",(?=\\s*[!#$%&'*+\\-.^_`|~0-9A-Za-z]+=)")
    }

    class Builder : KmpHttpClient.BaseBuilder<UrlSessionKmpHttpClient> {
        /** Underlying NSURLSession configuration. Cookies/redirects are managed by the chain. */
        var sessionConfig: NSURLSessionConfiguration

        /** Optional NSURLSession auth-challenge handler (server trust, pinning, client certs). */
        var onReceiveChallenge: AuthChallengeHandler?

        constructor() : super() {
            this.sessionConfig = defaultNsUrlSessionConfig()
            this.onReceiveChallenge = null
        }

        constructor(copy: UrlSessionKmpHttpClient) : super(copy) {
            this.sessionConfig = copy.sessionConfig
            this.onReceiveChallenge = copy.onReceiveChallenge
        }

        override fun build(): UrlSessionKmpHttpClient = UrlSessionKmpHttpClient(this)
    }
}

@Suppress("FunctionName")
actual fun KmpHttpClient.Companion.Builder(): KmpHttpClient.BaseBuilder<*> =
    UrlSessionKmpHttpClient.Builder()

private fun httpStatusMessage(code: Int): String = when (code) {
    100 -> "Continue"
    101 -> "Switching Protocols"
    200 -> "OK"
    201 -> "Created"
    202 -> "Accepted"
    204 -> "No Content"
    206 -> "Partial Content"
    301 -> "Moved Permanently"
    302 -> "Found"
    303 -> "See Other"
    304 -> "Not Modified"
    307 -> "Temporary Redirect"
    308 -> "Permanent Redirect"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    405 -> "Method Not Allowed"
    408 -> "Request Timeout"
    409 -> "Conflict"
    410 -> "Gone"
    413 -> "Payload Too Large"
    414 -> "URI Too Long"
    415 -> "Unsupported Media Type"
    429 -> "Too Many Requests"
    500 -> "Internal Server Error"
    501 -> "Not Implemented"
    502 -> "Bad Gateway"
    503 -> "Service Unavailable"
    504 -> "Gateway Timeout"
    else -> "HTTP $code"
}

/**
 * Handles streaming response data from NSURLSession.
 */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private class StreamingResponseHandler(private val onClose: () -> Unit) : NSObject(),
    NSURLSessionDataDelegateProtocol, NSURLSessionTaskDelegateProtocol {

    private val preBufferedSize = 64.kilobytes
    private val hotBuffer = Buffer()
    private val hotBufferGuard = SynchronizedObject()
    private val suspendCondition = SuspendCondition()

    private var httpResponse = CompletableDeferred<Pair<NSHTTPURLResponse, Long>>()
    private var completed = false
    private var completeError: IOException? = null
    private var closed = false
    private var task: NSURLSessionDataTask? = null

    // NSURLSessionTask.suspend() is COUNTED (each needs its own resume() before data flows again),
    // though `state` reads Running after one resume — so suspend at most once and pair each resume.
    private var taskSuspended = false

    /**
     * Attach a task, which will be canceled automatically when close this instance.
     */
    fun attachTask(task: NSURLSessionDataTask) {
        this.task = task
    }

    suspend fun awaitResponse(): Pair<NSHTTPURLResponse, Long> {
        return httpResponse.await()
    }

    fun cancel(error: Throwable?) {
        if (error != null) httpResponse.completeExceptionally(error)
        else httpResponse.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun read(sink: Buffer, byteCount: Long): Long {
        // Check for error
        if (!httpResponse.isCompleted) throw IllegalStateException("Response not ready")
        httpResponse.getCompletionExceptionOrNull()?.let { throw it }

        while (true) {
            synchronized(hotBufferGuard) {
                completeError?.let { throw it }
                if (closed) return -1L

                // If buffer has data, return it
                if (!hotBuffer.exhausted()) {
                    val toRead = minOf(hotBuffer.size, byteCount)
                    sink.write(hotBuffer, toRead)
                    // Resume as soon as there is room again. Waiting until the buffer is entirely
                    // empty can deadlock a streaming consumer that needs the next network chunk to
                    // finish parsing the current value (the large YouTube Home response hit this).
                    if (hotBuffer.size < preBufferedSize) resumeTask()
                    return toRead
                }
                if (completed) return -1
                resumeTask()
                suspendCondition.pause()
            }
            suspendCondition.await()
        }
    }

    /** Call under [hotBufferGuard]. */
    private fun resumeTask() {
        if (taskSuspended) {
            taskSuspended = false
            task?.resume()
        }
    }

    fun close() {
        synchronized(hotBufferGuard) {
            if (!closed) {
                closed = true
                completed = true
                task?.cancel()
                task = null
                hotBuffer.clear()
            } else return
        }
        suspendCondition.release()
        onClose()
    }

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (NSURLSessionResponseDisposition) -> Unit
    ) {
        httpResponse.complete((didReceiveResponse as NSHTTPURLResponse) to currentTimeMillis())
        completionHandler(NSURLSessionResponseAllow)
    }

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveData: NSData
    ) {
        synchronized(hotBufferGuard) {
            if (closed) return
            hotBuffer.write(didReceiveData)
            if (hotBuffer.size >= preBufferedSize && !taskSuspended) {
                taskSuspended = true
                dataTask.suspend()
            }
        }
        suspendCondition.resume()
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?
    ) {
        onComplete(didCompleteWithError)
    }

    override fun URLSession(session: NSURLSession, didBecomeInvalidWithError: NSError?) {
        onComplete(didBecomeInvalidWithError)
    }

    private fun onComplete(error: NSError?) {
        var notifyClose = false
        synchronized(hotBufferGuard) {
            completed = true
            if (error != null) {
                error.toIOException().let {
                    completeError = it
                    httpResponse.completeExceptionally(it)
                }
                // Do not call close() while holding hotBufferGuard: the lock is non-reentrant.
                if (!closed) {
                    closed = true
                    task = null
                    hotBuffer.clear()
                    notifyClose = true
                }
            }
        }
        suspendCondition.release()
        if (notifyClose) onClose()
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit
    ) {
        completionHandler(null) // don't redirect
    }

    @OptIn(ExperimentalTime::class)
    private fun currentTimeMillis(): Long {
        return Clock.System.now().toEpochMilliseconds()
    }
}

/**
 * Streaming ResponseBody that reads from StreamingResponseHandler.
 */
private class StreamingResponseBody(
    private val contentType: MediaType?,
    private val contentLength: Long,
    private val handler: StreamingResponseHandler,
) : ResponseBody() {

    private val source: AsyncSource = object : AsyncSource {
        override suspend fun read(sink: Buffer, byteCount: Long): Long {
            return handler.read(sink, byteCount)
        }

        override fun close() {
            handler.close()
        }
    }

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = contentLength

    override fun asyncSource(): AsyncSource {
        return source
    }
}

/**
 * NSURLErrorDomain codes for failures while establishing/transporting the connection — the request
 * was not necessarily delivered, so retrying on a fresh connection is safe. This is the NSURLSession
 * analog of curl's recoverable connect errors. Deliberately EXCLUDED so they surface immediately and
 * aren't pointlessly retried: TLS cert/pin failures (ServerCertificate*, ClientCertificate*),
 * authentication, user cancellation (-999), and "not connected to the internet" (-1009).
 */
@OptIn(UnsafeNumber::class)
private val RECOVERABLE_NSURL_ERRORS: Set<Long> = setOf(
    NSURLErrorNetworkConnectionLost,
    NSURLErrorCannotConnectToHost,
    NSURLErrorTimedOut,
    NSURLErrorDNSLookupFailed,
    NSURLErrorCannotFindHost,
    NSURLErrorSecureConnectionFailed,
).map { it.toLong() }.toSet()

/** Connectivity failures NOT retried: the device is offline or barred from data, or a certificate the
 *  network put in the way or the device's clock or trust store refuses. */
@OptIn(UnsafeNumber::class)
private val TRANSPORT_NSURL_ERRORS: Set<Long> = setOf(
    NSURLErrorNotConnectedToInternet,
    NSURLErrorInternationalRoamingOff,
    NSURLErrorDataNotAllowed,
    NSURLErrorCallIsActive,
    NSURLErrorServerCertificateHasBadDate,
    NSURLErrorServerCertificateUntrusted,
    NSURLErrorServerCertificateHasUnknownRoot,
    NSURLErrorServerCertificateNotYetValid,
).map { it.toLong() }.toSet()

@OptIn(UnsafeNumber::class)
private fun NSError.toIOException(): IOException {
    // An engine-attached Kotlin exception (e.g. the SslException born at a trust rejection)
    // propagates as-is — type, message, and birth-site stack intact.
    (userInfo?.get(KMPHTTP_ERROR_EXCEPTION_KEY) as? IOException)?.let { return it }
    val message = "NSURLSession error ($domain $code): $localizedDescription"
    val cause = when (val underlying = userInfo?.get(NSUnderlyingErrorKey)) {
        is Throwable -> underlying
        is NSError -> underlying.toIOException()
        else -> null
    }
    return if (domain == NSURLErrorDomain && code.toLong() in RECOVERABLE_NSURL_ERRORS) {
        RecoverableIOException(message, cause)
    } else if (domain == NSURLErrorDomain && code.toLong() in TRANSPORT_NSURL_ERRORS) {
        TransportIOException(message, cause)
    } else {
        IOException(message, cause)
    }
}
