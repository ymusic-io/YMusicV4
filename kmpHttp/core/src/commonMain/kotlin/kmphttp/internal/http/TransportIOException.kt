package kmphttp.internal.http

import okio.IOException

/** An engine's connectivity failure (offline, reset, dropped mid-transfer) — see [kmphttp.isTransportFailure]. */
internal open class TransportIOException(message: String, cause: Throwable? = null) :
    IOException(message, cause)
