package kmphttp

import kmphttp.internal.http.TransportIOException
import okhttp3.internal.http2.StreamResetException
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

// InterruptedIOException covers SocketTimeoutException and OkHttp's call timeout; SocketException covers
// connect refused, no route, connection reset and software-caused abort; SSLException covers certificate
// rejections too: an untrusted or not-yet-valid chain or a peer answering for another host is what a captive
// portal, an intercepting network or a wrong device clock looks like.
actual fun Throwable.isTransportFailure(): Boolean = when (this) {
    is HttpTimeoutException,
    is TransportIOException,
    is InterruptedIOException,
    is UnknownHostException,
    is StreamResetException,
    is SSLException,
    -> true
    is SocketException -> !isFileDescriptorExhaustion()
    else -> isPeerClosedBeforeHead()
}

/**
 * Runs a read of a response body. [EOFException] and [ProtocolException] say nothing by themselves: a truncated
 * local file and a redirect loop throw them too, and R8 strips the OkHttp frames that would tell them apart. Out
 * of this read they can only be the connection, since a source at its end returns -1, so they are marked here.
 */
internal inline fun <T> readingResponse(read: () -> T): T = try {
    read()
} catch (e: EOFException) {
    throw cutOffResponse(e)
} catch (e: ProtocolException) {
    throw cutOffResponse(e)
}

internal fun cutOffResponse(cause: IOException) = TransportIOException(cause.message ?: "connection lost mid-response", cause)

// OkHttp's "unexpected end of stream on <url>": the peer closed the connection before the response head.
private fun Throwable.isPeerClosedBeforeHead(): Boolean =
    this::class == IOException::class && cause is EOFException && message?.startsWith("unexpected end of stream") == true

// EMFILE/ENFILE surface as a SocketException but are a local resource leak, not the network.
private fun SocketException.isFileDescriptorExhaustion(): Boolean {
    val text = message ?: return false
    return "Too many open files" in text || "EMFILE" in text || "ENFILE" in text
}
