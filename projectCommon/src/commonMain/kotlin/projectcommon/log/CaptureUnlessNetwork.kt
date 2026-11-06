package projectcommon.log

import kmpcommon.log.Logger
import projectcommon.error.NETWORK_CODE
import projectcommon.error.errorCode

/** [Logger.captureError] for a job that died, unless the network killed it: the user retries that, so it
 *  stays a warning carrying the real [error], never a crash record. */
fun Logger.captureUnlessNetwork(error: Throwable, message: String? = null) {
    if (error.errorCode() == NETWORK_CODE) {
        log(Logger.Level.WARN, message ?: "network failure", error)
    } else {
        captureError(error, message)
    }
}
