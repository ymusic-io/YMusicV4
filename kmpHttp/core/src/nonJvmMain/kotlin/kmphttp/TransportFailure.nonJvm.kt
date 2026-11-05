package kmphttp

import kmphttp.internal.http.TransportIOException

actual fun Throwable.isTransportFailure(): Boolean =
    this is HttpTimeoutException || this is TransportIOException || this is SslException
