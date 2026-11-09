package io.ymusic.dynamicengine

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import projectcommon.error.CodedError
import projectcommon.error.DISK_FULL_CODE

/**
 * A failure of an engine call, rebuilt from the structured RESET reason the JS `commandHandler`
 * sends (HOST_PROTOCOLS "Errors across the boundary"): `{code, name, message, stack, userMessage}`.
 *
 * [code] is the boundary contract — a well-known category mapped to a subclass here, or a site
 * code (`E#####`, one hardcoded literal at one throw site, in any tier: app, remote JS, server).
 * The taxonomy is OPEN: remote JS and the server update independently of this app build, so a
 * category minted after this build lands as [Other] with the code intact — never a crash.
 */
sealed class EngineError(
    override val code: String,
    message: String,
    override val userMessage: String?,
    /** JS constructor name (`TypeError`, `JsEngineError`, …) when the failure originated in JS. */
    val jsName: String?,
    /** JS stack (capped at the boundary), for reports. */
    val jsStack: String?,
    cause: Throwable?,
) : Exception(message, cause), CodedError {

    /** Transport failure (DNS/connect/TLS/timeout) — retryable, "check your connection". */
    class Network internal constructor(m: String, u: String?, n: String?, s: String?, c: Throwable?) :
        EngineError("network", m, u, n, s, c)

    /** The scraped data's shape wasn't understood — the signal to ship updated engine JS. */
    class Parse internal constructor(m: String, u: String?, n: String?, s: String?, c: Throwable?) :
        EngineError("parse", m, u, n, s, c)

    /** YouTube answered with a bot challenge (google.com/sorry, reCAPTCHA) — the user clears it in a
     *  browser over the shared cookie jar ([io.ymusic.dynamicengine.yt.ytVerifyTarget]). */
    class Captcha internal constructor(m: String, u: String?, n: String?, s: String?, c: Throwable?) :
        EngineError("captcha", m, u, n, s, c)

    /** The JS engine itself gave out (OOM, stack overflow) — when it was even catchable. */
    class Oom internal constructor(m: String, u: String?, n: String?, s: String?, c: Throwable?) :
        EngineError("oom", m, u, n, s, c)

    /** A host store ran out of device storage (SQLITE_FULL behind a cache or cookie write). */
    class DiskFull internal constructor(m: String, u: String?, n: String?, s: String?, c: Throwable?) :
        EngineError(DISK_FULL_CODE, m, u, n, s, c)

    /** A site-coded technical failure (`E#####`) from any tier — rendered as the generic
     *  something-went-wrong message plus the code. */
    class Technical internal constructor(code: String, m: String, u: String?, n: String?, s: String?, c: Throwable?) :
        EngineError(code, m, u, n, s, c)

    /** A category this app build doesn't know (newer remote JS or server). The code is preserved
     *  for display ("… (region_blocked)") and telemetry. */
    class Other internal constructor(code: String, m: String, u: String?, n: String?, s: String?, c: Throwable?) :
        EngineError(code, m, u, n, s, c)
}

private val SITE_CODE = Regex("""E\d{5}""")
private val wireJson = Json { ignoreUnknownKeys = true }

/** Rebuild an [EngineError] from a pipe RESET [reason] by decoding the typed [WireError] contract.
 *  Fallback-tolerant: a reason that isn't the contract (the runtime's raw backstop text — a
 *  different, generic layer) still salvages an embedded site code, else mints the decoder's own. */
internal fun decodeEngineError(reason: String, cause: Throwable?): EngineError {
    var decodeFailure: Exception? = null
    val wire = reason.takeIf { it.startsWith("{") }?.let {
        try {
            wireJson.decodeFromString<WireError>(it)
        } catch (e: Exception) {
            decodeFailure = e
            null
        }
    }
    if (wire == null) {
        val embedded = SITE_CODE.find(reason)?.value
        return EngineError.Technical(embedded ?: "E20003", reason, null, null, null, cause) // E20003: unstructured pipe reset
            .also { error -> decodeFailure?.let(error::addSuppressed) }
    }
    val message = wire.message ?: reason
    return when {
        wire.code == "network" -> EngineError.Network(message, wire.userMessage, wire.name, wire.stack, cause)
        wire.code == "parse" -> EngineError.Parse(message, wire.userMessage, wire.name, wire.stack, cause)
        wire.code == "captcha" -> EngineError.Captcha(message, wire.userMessage, wire.name, wire.stack, cause)
        wire.code == "oom" -> EngineError.Oom(message, wire.userMessage, wire.name, wire.stack, cause)
        wire.code == DISK_FULL_CODE -> EngineError.DiskFull(message, wire.userMessage, wire.name, wire.stack, cause)
        SITE_CODE.matches(wire.code) ->
            EngineError.Technical(wire.code, message, wire.userMessage, wire.name, wire.stack, cause)
        else -> EngineError.Other(wire.code, message, wire.userMessage, wire.name, wire.stack, cause)
    }
}
