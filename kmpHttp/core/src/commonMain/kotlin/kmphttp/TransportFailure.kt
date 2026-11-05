package kmphttp

/**
 * True when this throwable (not its causes) is a connectivity failure — DNS, connect, reset/abort, TLS
 * transport, a certificate the network or the device's clock or trust store refused, a response cut off
 * while it was being read, timeout — that the user can only retry. A parser running out of a body the
 * transport finished cleanly, an HTTP status, a redirect loop, disk and parse failures are false even
 * though some are [okio.IOException]s too.
 */
expect fun Throwable.isTransportFailure(): Boolean
