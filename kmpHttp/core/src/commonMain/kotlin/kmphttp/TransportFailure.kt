package kmphttp

/**
 * True when this throwable (not its causes) is a connectivity failure — DNS, connect, reset/abort, TLS
 * transport, a response cut off while it was being read, timeout — that a better connection would fix. A
 * parser running out of a body the transport finished cleanly, an HTTP status, a redirect loop, disk,
 * parse and certificate or pin rejections are false even though some are [okio.IOException]s too.
 */
expect fun Throwable.isTransportFailure(): Boolean
