package projectcommon.log

import kmpcommon.log.Logger
import projectcommon.error.isEnvironmental

/** [Logger.captureError] for a job that died, unless the device killed it ([isEnvironmental]): it stays
 *  a warning carrying the real [error], never a crash record. */
fun Logger.captureUnlessEnvironmental(error: Throwable, message: String? = null) {
    if (error.isEnvironmental()) {
        log(Logger.Level.WARN, message ?: "environmental failure", error)
    } else {
        captureError(error, message)
    }
}
