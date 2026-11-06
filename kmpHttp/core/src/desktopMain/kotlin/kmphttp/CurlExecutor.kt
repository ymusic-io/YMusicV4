/*
 * Copyright (C) 2025 YMusic
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kmphttp

import kmpcommon.io.AsyncSource
import kmpcommon.kilobytes
import kmphttp.CookieJar
import kmphttp.Headers
import kmphttp.HeadersBuilder
import kmphttp.HttpCache
import kmphttp.MediaType
import kmphttp.MediaTypeX.toMediaTypeOrNull
import kmphttp.Protocol
import kmphttp.RedirectOptions
import kmphttp.Request
import kmphttp.Response
import kmphttp.ResponseBody
import kmphttp.ResponseBuilder
import kmphttp.curl.CurlMultiApi
import kmphttp.curl.EasyHandle
import kmphttp.internal.SuspendCondition
import kmphttp.internal.http.RecoverableIOException
import kmphttp.internal.http.TransportIOException
import kmphttp.internal.read
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import libcurl.CURLE_COULDNT_CONNECT
import libcurl.CURLE_COULDNT_RESOLVE_HOST
import libcurl.CURLE_COULDNT_RESOLVE_PROXY
import libcurl.CURLE_GOT_NOTHING
import libcurl.CURLE_HTTP2_STREAM
import libcurl.CURLE_OK
import libcurl.CURLE_OPERATION_TIMEDOUT
import libcurl.CURLE_PARTIAL_FILE
import libcurl.CURLE_PEER_FAILED_VERIFICATION
import libcurl.CURLE_RECV_ERROR
import libcurl.CURLE_SEND_ERROR
import libcurl.CURLE_SSL_CONNECT_ERROR
import libcurl.CURLE_SSL_PINNEDPUBKEYNOTMATCH
import libcurl.CURL_READFUNC_ABORT
import libcurl.CURL_READFUNC_PAUSE
import libcurl.CURL_WRITEFUNC_ERROR
import libcurl.CURL_WRITEFUNC_PAUSE
import libcurl.curl_easy_strerror
import okio.Buffer
import okio.IOException
import platform.posix.size_t
import platform.posix.uint8_tVar
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * curl result codes for failures during connection establishment (DNS / connect / TLS handshake) or
 * a reset before a response was received — transient and safe to retry on a fresh connection. Cert
 * and pin verification failures (CURLE_PEER_FAILED_VERIFICATION, CURLE_SSL_PINNEDPUBKEYNOTMATCH) are
 * deliberately excluded so they surface immediately.
 */
@OptIn(ExperimentalForeignApi::class)
private val RECOVERABLE_CURL_ERRORS: Set<UInt> = setOf(
    CURLE_COULDNT_RESOLVE_PROXY,
    CURLE_COULDNT_RESOLVE_HOST,
    CURLE_COULDNT_CONNECT,
    CURLE_OPERATION_TIMEDOUT,
    CURLE_SSL_CONNECT_ERROR,
    CURLE_GOT_NOTHING,
    CURLE_SEND_ERROR,
    CURLE_RECV_ERROR,
)

/** Connectivity failures not retried: mid-transfer, after the request may have been delivered, or a
 *  certificate the network put in the way or the device's clock or trust store refuses. */
@OptIn(ExperimentalForeignApi::class)
private val TRANSPORT_CURL_ERRORS: Set<UInt> = setOf(
    CURLE_PARTIAL_FILE,
    CURLE_HTTP2_STREAM,
    CURLE_PEER_FAILED_VERIFICATION,
    CURLE_SSL_PINNEDPUBKEYNOTMATCH,
)

/**
 * Executes a single request, managing the curl easy-handle lifecycle. Driven by
 * [CurlKmpHttpClient]'s interceptor chain (it is the chain's network terminal).
 */
@OptIn(
    ExperimentalTime::class, ExperimentalForeignApi::class, ExperimentalAtomicApi::class,
    ExperimentalCoroutinesApi::class
)
internal class CurlExecutor(
    private val request: Request,
    private val curlMultiApi: CurlMultiApi,
    private val sslConfig: CurlSslConfig
) {
    private var easyHandle: EasyHandle? = null
    private var requestBodyTransferRef: StableRef<RequestBodyTransfer>? = null
    private var responseHeaderHandlerRef: StableRef<ResponseHeaderHandler>? = null
    private var streamingResponseHandlerRef: StableRef<StreamingResponseHandler>? = null

    private val executed = AtomicBoolean(false)
    private val cleanedUp = AtomicBoolean(false)

    suspend fun execute(): Response {
        check(executed.compareAndSet(false, true))
        try {
            val sentRequestAtMillis = Clock.System.now().toEpochMilliseconds()

            val curl = EasyHandle.create()
            easyHandle = curl

            curl.setMethod(request.method)
            curl.setUrl(request.url.toString())
            curl.setFollowLocation(false)
            curl.setHeaders(request.headers.toList())
            sslConfig.apply(curl, request.url.host)

            // If request doesn't set Accept-Encoding, let curl handle compression automatically
            // Empty string = curl sends all supported encodings and auto-decompresses response
            if (request.header("Accept-Encoding") == null) {
                curl.setAcceptEncoding("")
            }

            request.body?.let { body ->
                val contentLength = body.contentLength()
                if (contentLength >= 0) {
                    curl.setContentLength(contentLength)
                }

                val handler = RequestBodyTransfer(
                    curl, curlMultiApi, body.openRead(),
                    // IO, not Unconfined: this loop is resumed from the READFUNCTION on the
                    // event-loop thread; Unconfined would run the body's blocking source.read
                    // inline in the curl callback, stalling the multi loop.
                    CoroutineScope(Dispatchers.IO)
                )
                StableRef.create(handler).let { ref ->
                    requestBodyTransferRef = ref
                    curl.setBody(readCallback, ref.asCPointer())
                }
            }

            // Setup response header collection
            val responseHeaderHandler = ResponseHeaderHandler()
            StableRef.create(responseHeaderHandler).let { ref ->
                responseHeaderHandlerRef = ref
                curl.setHeaderFunction(headerCallback, ref.asCPointer())
            }

            val streamingHandler = StreamingResponseHandler(curl, curlMultiApi, this::cleanUpThis)
            StableRef.create(streamingHandler).let { ref ->
                streamingResponseHandlerRef = ref
                curl.setWriteFunction(writeCallback, ref.asCPointer())
            }

            val curlResult = curlMultiApi.addJob(curl)
            curlMultiApi.start()
            curlResult.invokeOnCompletion { error ->
                if (error != null) {
                    streamingHandler.onPerformError(error)
                } else {
                    streamingHandler.onPerformComplete(curlResult.getCompleted())
                }
            }

            // Code/version come from the status line (parsed on the event-loop thread), not
            // curl_easy_getinfo — the caller must not touch the handle while the multi loop drives it.
            val headerResult = responseHeaderHandler.await()
            val receivedResponseAtMillis = Clock.System.now().toEpochMilliseconds()

            return buildResponse(
                request = request,
                code = headerResult.code,
                protocol = headerResult.protocol,
                headers = headerResult.headers,
                streamingHandler = streamingHandler,
                sentRequestAtMillis = sentRequestAtMillis,
                receivedResponseAtMillis = receivedResponseAtMillis
            )
        } catch (e: Throwable) {
            cleanUpThis(e)
            throw e
        }
    }

    private fun cleanUpThis(error: Throwable?) {
        if (cleanedUp.compareAndSet(false, true)) {
            val bodyRef = requestBodyTransferRef
            val headerRef = responseHeaderHandlerRef
            val streamRef = streamingResponseHandlerRef
            requestBodyTransferRef = null
            responseHeaderHandlerRef = null
            streamingResponseHandlerRef = null

            bodyRef?.get()?.cancel()
            headerRef?.get()?.cancel(error)
            streamRef?.get()?.let {
                if (error != null) it.onPerformError(error)
                else it.close()
            }

            // The StableRefs are the callbacks' userdata. removeAndCleanUpJob only QUEUES the
            // removal; until the event loop actually takes the handle off the multi,
            // curl_multi_perform can still fire read/write/header callbacks for it — so the refs
            // must stay alive until onRemoved runs on the event-loop thread (disposing them here
            // was a use-after-free under cancellation/early-close).
            val disposeRefs: () -> Unit = {
                bodyRef?.dispose()
                headerRef?.dispose()
                streamRef?.dispose()
            }
            val handle = easyHandle
            easyHandle = null
            if (handle != null) {
                curlMultiApi.removeAndCleanUpJob(handle, error, onRemoved = disposeRefs)
            } else {
                disposeRefs()
            }
        }
    }

    private fun buildResponse(
        request: Request,
        code: Int,
        protocol: Protocol?,
        headers: Headers,
        streamingHandler: StreamingResponseHandler,
        sentRequestAtMillis: Long,
        receivedResponseAtMillis: Long
    ): Response {
        val message = httpStatusMessage(code)
        val contentType = headers["Content-Type"]?.toMediaTypeOrNull()
        val contentLength = headers["Content-Length"]?.toLongOrNull() ?: -1L
        val body =
            StreamingResponseBody(contentType, contentLength, streamingHandler)

        return ResponseBuilder()
            .request(request)
            .protocol(protocol ?: Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .headers(headers)
            .body(body)
            .sentRequestAtMillis(sentRequestAtMillis)
            .receivedResponseAtMillis(receivedResponseAtMillis)
            .build()
    }

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

    companion object {
        private val readCallback = staticCFunction { buffer: CPointer<ByteVar>?,
                                                     size: size_t,
                                                     nitems: size_t,
                                                     userdata: COpaquePointer? ->
            if (buffer == null || userdata == null) return@staticCFunction 0uL

            val handler = userdata.asStableRef<RequestBodyTransfer>().get()
            val maxBytes = (size * nitems).toLong()

            handler.read(buffer, maxBytes).convert<size_t>()
        }

        private val writeCallback = staticCFunction { buffer: CPointer<ByteVar>?,
                                                      size: size_t,
                                                      nmemb: size_t,
                                                      userdata: COpaquePointer? ->
            if (buffer == null || userdata == null) return@staticCFunction 0uL

            val handler = userdata.asStableRef<StreamingResponseHandler>().get()
            val byteCount = (size * nmemb).toInt()
            handler.write(buffer, byteCount)
        }

        private val headerCallback = staticCFunction { buffer: CPointer<ByteVar>?,
                                                       size: size_t,
                                                       nitems: size_t,
                                                       userdata: COpaquePointer? ->
            if (buffer == null || userdata == null) return@staticCFunction 0uL

            val handler = userdata.asStableRef<ResponseHeaderHandler>().get()
            val byteCount = (size * nitems).toInt()
            val headerLine = buffer.readBytes(byteCount).decodeToString().trim()

            handler.addHeaderLine(headerLine)

            byteCount.convert<size_t>()
        }
    }
}

/**
 * Handles reading request body data for curl READFUNCTION callback.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
private class RequestBodyTransfer(
    private val easyHandle: EasyHandle,
    private val multiApi: CurlMultiApi,
    source: AsyncSource,
    scope: CoroutineScope
) {
    private val preBufferSize = 16.kilobytes
    private val buffer = Buffer()
    private val lock = SynchronizedObject()
    private val suspendCondition = SuspendCondition()
    private val paused = AtomicBoolean(false)
    private val eof = AtomicBoolean(false)
    private var sourceError: Throwable? = null

    private var asyncJob: Job? = scope.launch {
        var sourceExhausted = false
        val tempBuffer = Buffer()
        try {
            while (isActive) {
                check(tempBuffer.size == 0L)
                while (isActive && buffer.size <= preBufferSize && tempBuffer.size <= preBufferSize && !sourceExhausted) {
                    val totalRead = source.read(tempBuffer, preBufferSize)
                    if (totalRead < 0) {
                        sourceExhausted = true
                        break
                    }
                }
                if (!isActive) return@launch
                // multiApi calls must stay OUTSIDE `lock`: the event loop invokes this handle's
                // callbacks (which take `lock`) while holding the multi lock, so taking the multi
                // lock while holding `lock` is a lock-order inversion — a hard deadlock of the
                // whole event loop under backpressure.
                var unpauseNeeded = false
                val done = synchronized(lock) {
                    if (tempBuffer.size > 0) {
                        buffer.write(tempBuffer, tempBuffer.size)
                        check(tempBuffer.size == 0L)
                    }
                    if (sourceExhausted) {
                        eof.store(true)
                    }
                    unpauseNeeded = paused.compareAndSet(true, false)
                    if (buffer.size > 0) {
                        suspendCondition.pause()
                        return@synchronized false
                    } else {
                        check(sourceExhausted)
                        return@synchronized true
                    }
                }
                if (unpauseNeeded) multiApi.unpause(easyHandle)
                if (done) break
                else suspendCondition.await()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            synchronized(lock) {
                sourceError = e
                buffer.clear()
            }
            multiApi.removeAndCleanUpJob(easyHandle, e)
        } finally {
            tempBuffer.clear()
        }
    }.also {
        it.invokeOnCompletion {
            source.close()
        }
    }

    /**
     * Perform non-blocking read
     */
    fun read(dest: CPointer<ByteVar>, maxBytes: Long): Long {
        return try {
            synchronized(lock) {
                sourceError?.let {
                    return CURL_READFUNC_ABORT.toLong()
                }
                if (buffer.size == 0L) {
                    if (eof.load()) return 0L
                    else {
                        paused.store(true)
                        suspendCondition.resume()
                        return CURL_READFUNC_PAUSE.toLong()
                    }
                }
                val n = buffer.read(dest.reinterpret<uint8_tVar>(), maxBytes)
                if (buffer.size < preBufferSize) {
                    suspendCondition.resume()
                }
                n
            }
        } catch (e: Throwable) {
            multiApi.removeAndCleanUpJob(easyHandle, e)
            CURL_READFUNC_ABORT.toLong()
        }
    }

    fun cancel() {
        asyncJob?.cancel()
        asyncJob = null
        suspendCondition.release()
    }
}

/**
 * Handles streaming response body with pause/resume support.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class, ExperimentalTime::class)
private class StreamingResponseHandler(
    private val easyHandle: EasyHandle,
    private val multiApi: CurlMultiApi,
    private val cleanUp: (error: Throwable?) -> Unit
) {
    private val preBufferedSize = 64.kilobytes
    private val hotBuffer = Buffer()
    private val hotBufferGuard = SynchronizedObject()
    private val suspendCondition = SuspendCondition()

    private var completed = AtomicBoolean(false)
    private var completeError: IOException? = null
    private var closed = AtomicBoolean(false)
    private var paused = AtomicBoolean(false)

    /**
     * Called from curl's WRITEFUNCTION callback.
     * Returns number of bytes handled, or CURL_WRITEFUNC_PAUSE to pause.
     */
    fun write(buffer: CPointer<ByteVar>, count: Int): size_t {
        synchronized(hotBufferGuard) {
            // write should not happen after completed
            if (closed.load() || completed.load()) return CURL_WRITEFUNC_ERROR.convert()
            suspendCondition.resume()

            // If buffer is too large, pause curl
            if (hotBuffer.size >= preBufferedSize) {
                paused.store(true)
                // CURL_WRITEFUNC_PAUSE = 0x10000001
                return CURL_WRITEFUNC_PAUSE.convert()
            }

            hotBuffer.write(buffer.readBytes(count))
        }
        return count.convert()
    }

    /**
     * Read data from the streaming buffer.
     */
    suspend fun read(sink: Buffer, byteCount: Long): Long {
        while (true) {
            // unpause() must run OUTSIDE hotBufferGuard: the event loop's curl_easy_pause(CONT)
            // synchronously reenters write() (which takes hotBufferGuard) while holding the multi
            // lock — calling into the multi lock from under hotBufferGuard is the opposite order,
            // a lock-order inversion that deadlocks the whole event loop.
            var unpauseNeeded = false
            val result = synchronized(hotBufferGuard) {
                completeError?.let { throw it }
                if (closed.load()) return@synchronized ReadResult.Closed

                if (hotBuffer.size > 0) {
                    val toRead = minOf(hotBuffer.size, byteCount)
                    sink.write(hotBuffer, toRead)

                    // Resume curl if it was paused and buffer has space
                    if (hotBuffer.size < preBufferedSize && paused.compareAndSet(true, false)) {
                        unpauseNeeded = true
                    }

                    return@synchronized ReadResult.Success(toRead)
                }

                if (completed.load()) {
                    return@synchronized ReadResult.Closed
                }

                // Resume curl if paused (buffer is empty now)
                if (paused.compareAndSet(true, false)) {
                    unpauseNeeded = true
                }

                suspendCondition.pause()
                ReadResult.Wait
            }
            if (unpauseNeeded) multiApi.unpause(easyHandle)

            when (result) {
                is ReadResult.Success -> return result.bytes
                ReadResult.Closed -> return -1L
                ReadResult.Wait -> suspendCondition.await()
            }
        }
    }

    private sealed class ReadResult {
        data class Success(val bytes: Long) : ReadResult()
        object Closed : ReadResult()
        object Wait : ReadResult()
    }

    fun onPerformComplete(result: UInt) {
        synchronized(hotBufferGuard) {
            if (completed.compareAndSet(false, true)) {
                if (result != CURLE_OK) {
                    val errorMsg = curl_easy_strerror(result)?.toKString() ?: "Unknown error"
                    val message = "curl failed ($result): $errorMsg"
                    // Connection-establishment failures (handshake reset, connect/DNS, etc.) are
                    // transient and safe to retry on a fresh connection; tag them so
                    // RetryOnConnectionFailureInterceptor picks them up. Cert/pin verification
                    // failures are never retried: they surface immediately.
                    completeError = if (result in RECOVERABLE_CURL_ERRORS) {
                        RecoverableIOException(message)
                    } else if (result in TRANSPORT_CURL_ERRORS) {
                        TransportIOException(message)
                    } else {
                        IOException(message)
                    }

                    close()
                }
            } else {
            }
        }
        suspendCondition.release()
    }

    fun onPerformError(e: Throwable) {
        synchronized(hotBufferGuard) {
            if (completed.compareAndSet(false, true)) {
                completeError = IOException("curl request failed: ${e.message}", e)
                close()
            }
        }
        suspendCondition.release()
    }

    fun close() {
        synchronized(hotBufferGuard) {
            if (closed.compareAndSet(false, true)) {
                completed.store(true)
                hotBuffer.clear()

                cleanUp(completeError)
            }
        }
        suspendCondition.release()
    }
}

/**
 * Collects response headers from curl HEADERFUNCTION callback.
 */
private class ResponseHeaderHandler {
    /** Headers plus the status code and HTTP version, all derived on the event-loop thread from
     *  the status line — so the caller never reads them off the shared easy handle. */
    class HeaderResult(val headers: Headers, val code: Int, val protocol: Protocol)

    private val finalHeader = CompletableDeferred<HeaderResult>()
    private var statusLine: String? = null
    private val headerLines = mutableListOf<String>()
    private val lock = SynchronizedObject()

    fun addHeaderLine(line: String) {
        synchronized(lock) {
            // Skip status line (e.g., "HTTP/1.1 200 OK")
            if (line.startsWith("HTTP/")) {
                statusLine = line
                return
            }
            // Skip empty lines
            if (line.isEmpty()) {
                val statusLine = statusLine
                check(statusLine != null) { "Receive header terminator but missing status line" }
                val spaceIndex = statusLine.indexOf(' ')
                var code = 0
                if (spaceIndex > -1 && spaceIndex + 3 < statusLine.length) {
                    val parsed = statusLine.substring(spaceIndex + 1, spaceIndex + 4).toIntOrNull()
                    if (parsed == 100) {
                        // 100-continue: reset the status line and drop any 1xx headers.
                        this.statusLine = null
                        headerLines.clear()
                        return
                    }
                    if (parsed != null) code = parsed
                }

                val builder = HeadersBuilder()
                for (line in headerLines) {
                    val colonIndex = line.indexOf(':')
                    if (colonIndex > 0) {
                        val name = line.substring(0, colonIndex).trim()
                        val value = line.substring(colonIndex + 1).trim()
                        builder.addUnsafeNonAscii(name, value)
                    }
                }
                finalHeader.complete(
                    HeaderResult(builder.build(), code, protocolFromStatusLine(statusLine)),
                )
                return
            }
            headerLines.add(line)
        }
    }

    private fun protocolFromStatusLine(statusLine: String): Protocol =
        when (statusLine.substringBefore(' ')) {
            "HTTP/1.0" -> Protocol.HTTP_1_0
            "HTTP/1.1" -> Protocol.HTTP_1_1
            "HTTP/2", "HTTP/2.0" -> Protocol.HTTP_2
            "HTTP/3", "HTTP/3.0" -> Protocol.HTTP_3
            else -> Protocol.HTTP_1_1
        }

    suspend fun await() = finalHeader.await()
    fun cancel(e: Throwable?) {
        if (!finalHeader.isActive) return
        if (e != null) finalHeader.completeExceptionally(e)
        else finalHeader.cancel()
    }
}

/**
 * Streaming ResponseBody that reads from StreamingResponseHandler.
 */
@OptIn(ExperimentalForeignApi::class)
private class StreamingResponseBody(
    private val contentType: MediaType?,
    private val contentLength: Long,
    private val handler: StreamingResponseHandler
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

    override fun asyncSource(): AsyncSource = source
}
