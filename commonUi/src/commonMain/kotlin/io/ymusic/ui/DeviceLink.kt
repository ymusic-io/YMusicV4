package io.ymusic.ui

import io.ymusic.remoteconfig.ApiService
import kmphttp.CookieBuilder
import kmphttp.CookieJar
import kmphttp.HttpStatusException
import kmphttp.saveFromResponse
import kmphttp.toHttpUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import okio.IOException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** One YouTube/Google cookie moved between devices. [domain] is a suffix (no leading dot). */
data class SessionCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val secure: Boolean = true,
    val httpOnly: Boolean = false,
    val expiresAt: Long? = null,
)

/**
 * Desktop-minted relay slot: its id on our service plus the key, both encoded in the QR. The QR
 * carries no url — the phone sends to its own build's service, so a forged QR cannot redirect it.
 */
class DeviceLinkOffer(
    val id: String,
    val key: ByteArray,
) {
    val qrPayload: String
        get() = "$QR_PREFIX$id|$QR_SEP${key.toByteString().base64Url()}"
}

internal fun parseDeviceLinkQr(raw: String): Pair<String, ByteArray>? {
    val text = raw.trim()
    if (!text.startsWith(QR_PREFIX)) return null
    val rest = text.removePrefix(QR_PREFIX)
    val bar = rest.indexOf("|$QR_SEP")
    if (bar <= 0) return null
    val id = rest.substring(0, bar)
    if (!LINK_ID.matches(id)) return null
    val key = rest.substring(bar + 1 + QR_SEP.length).decodeBase64Url() ?: return null
    if (key.size != KEY_LEN) return null
    return id to key
}

suspend fun createDeviceLinkOffer(api: ApiService?): DeviceLinkOffer {
    val id = relay(api) { it.openDeviceLink() }
    check(LINK_ID.matches(id)) { "bad-payload" }
    return DeviceLinkOffer(id, deviceLinkRandomBytes(KEY_LEN))
}

/** Gives the slot up. Failures are ignored — the slot also expires on the service. */
suspend fun closeDeviceLink(api: ApiService?, id: String) {
    try {
        api?.closeDeviceLink(id)
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        // Fire-and-forget cleanup of the one-shot slot.
    }
}

fun deviceLinkSas(key: ByteArray): String {
    require(key.size == KEY_LEN)
    val mac = hmac(key, SAS_LABEL)
    var n = 0L
    for (i in 0 until 4) n = (n shl 8) or (mac[i].toLong() and 0xffL)
    val digits = (n % 1_000_000L).toString().padStart(6, '0')
    return digits.substring(0, 3) + "\u00a0" + digits.substring(3)
}

internal suspend fun pollDeviceLinkCookies(
    api: ApiService?,
    offer: DeviceLinkOffer,
): List<SessionCookie>? {
    val raw = try {
        relay(api) { it.takeDeviceLink(offer.id) } ?: return null
    } catch (gone: IllegalStateException) {
        // 404: the slot expired on the service.
        if ((gone.cause as? HttpStatusException)?.code == 404) error("timeout") else throw gone
    }
    val blob = raw.trim().decodeBase64Url() ?: error("bad-payload")
    val plain = deviceLinkDecrypt(offer.key, blob)
    return decodeCookies(plain).ifEmpty { error("bad-payload") }
}

internal suspend fun postDeviceLinkCookies(
    api: ApiService?,
    id: String,
    key: ByteArray,
    cookies: List<SessionCookie>,
) {
    check(LINK_ID.matches(id))
    val blob = deviceLinkEncrypt(key, encodeCookies(cookies))
    if (!relay(api) { it.putDeviceLink(id, blob.toByteString().base64Url()) }) error("timeout")
}

/** Runs [call] on the relay; an unreachable or refusing service is the "network" failure. */
private suspend fun <T> relay(api: ApiService?, call: suspend (ApiService) -> T): T {
    val service = api ?: error("network")
    return try {
        call(service)
    } catch (c: CancellationException) {
        throw c
    } catch (e: IOException) {
        throw IllegalStateException("network", e)
    } catch (e: HttpStatusException) {
        throw IllegalStateException("network", e)
    }
}

@OptIn(ExperimentalTime::class)
suspend fun CookieJar.importSessionCookies(cookies: List<SessionCookie>) {
    val now = Clock.System.now().toEpochMilliseconds()
    val fallbackExpiry = now + PERSIST_MS
    val built = cookies.map { c ->
        val domain = c.domain.removePrefix(".").ifBlank { "youtube.com" }
        val builder = CookieBuilder()
            .name(c.name)
            .value(c.value)
            .domain(domain)
            .path(c.path.ifBlank { "/" })
            .expiresAt(c.expiresAt?.takeIf { it > now } ?: fallbackExpiry)
        if (c.secure) builder.secure()
        if (c.httpOnly) builder.httpOnly()
        builder.build()
    }
    withContext(Dispatchers.IO) {
        saveFromResponse("https://www.youtube.com/".toHttpUrl(), built)
    }
}

fun parseCookiePaste(text: String): List<SessionCookie> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()
    val json = parseJsonCookiePaste(trimmed)
    if (json.isNotEmpty()) return json
    val lines = trimmed.lineSequence().map { it.trimEnd() }.toList()
    val header = lines.firstOrNull { it.isNotBlank() && !it.startsWith('#') }.orEmpty()
    if (header.contains('\t') && header.contains("Name", ignoreCase = true) &&
        header.contains("Value", ignoreCase = true)
    ) {
        return parseChromeTsv(lines)
    }
    if (header.contains('\t')) {
        val netscape = parseNetscape(lines)
        if (netscape.isNotEmpty()) return netscape
        val tsv = parseChromeTsv(lines)
        if (tsv.isNotEmpty()) return tsv
    }
    val payload = cookiePayloadFromPaste(trimmed)
    if (payload != null) {
        val extracted = parseCookieHeader(payload)
        if (extracted.size >= 2 || looksLikeCurl(trimmed)) return extracted
    }
    val headerCookies = parseCookieHeader(trimmed)
    if (headerCookies.size >= 2) return headerCookies
    val named = parseNameValueLines(lines)
    return named.ifEmpty { headerCookies }
}

internal expect fun deviceLinkRandomBytes(n: Int): ByteArray

internal fun deviceLinkEncrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
    require(key.size == KEY_LEN)
    val iv = deviceLinkRandomBytes(IV_LEN)
    val encKey = hmac(key, ENC_LABEL)
    val macKey = hmac(key, MAC_LABEL)
    val ciphertext = xorKeystream(encKey, iv, plaintext)
    val mac = hmac(macKey, iv + ciphertext)
    return iv + ciphertext + mac
}

internal fun deviceLinkDecrypt(key: ByteArray, blob: ByteArray): ByteArray {
    require(key.size == KEY_LEN)
    if (blob.size < IV_LEN + MAC_LEN) error("bad-payload")
    val iv = blob.copyOfRange(0, IV_LEN)
    val mac = blob.copyOfRange(blob.size - MAC_LEN, blob.size)
    val ciphertext = blob.copyOfRange(IV_LEN, blob.size - MAC_LEN)
    val encKey = hmac(key, ENC_LABEL)
    val macKey = hmac(key, MAC_LABEL)
    val expect = hmac(macKey, iv + ciphertext)
    if (!macEquals(mac, expect)) error("bad-payload")
    return xorKeystream(encKey, iv, ciphertext)
}

private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
    data.toByteString().hmacSha256(key.toByteString()).toByteArray()

private fun hmac(key: ByteArray, label: String): ByteArray = hmac(key, label.encodeToByteArray())

private fun xorKeystream(encKey: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
    val out = ByteArray(data.size)
    var offset = 0
    var block = 0
    while (offset < data.size) {
        val ks = Buffer().apply {
            write(iv)
            writeInt(block)
        }.readByteString().hmacSha256(encKey.toByteString()).toByteArray()
        val n = minOf(ks.size, data.size - offset)
        for (i in 0 until n) out[offset + i] = (data[offset + i].toInt() xor ks[i].toInt()).toByte()
        offset += n
        block++
    }
    return out
}

private fun macEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var d = 0
    for (i in a.indices) d = d or (a[i].toInt() xor b[i].toInt())
    return d == 0
}

private const val FIELD = '\u001f'

private fun encodeCookies(cookies: List<SessionCookie>): ByteArray {
    val sb = StringBuilder()
    for (c in cookies) {
        sb.append(c.name).append(FIELD)
            .append(c.value).append(FIELD)
            .append(c.domain.removePrefix(".")).append(FIELD)
            .append(c.path.ifBlank { "/" }).append(FIELD)
            .append(if (c.secure) '1' else '0').append(FIELD)
            .append(if (c.httpOnly) '1' else '0').append(FIELD)
            .append(c.expiresAt ?: "")
            .append('\n')
    }
    return sb.toString().encodeToByteArray()
}

private fun decodeCookies(bytes: ByteArray): List<SessionCookie> =
    bytes.decodeToString().split('\n').mapNotNull { line ->
        if (line.isEmpty()) return@mapNotNull null
        val p = line.split(FIELD)
        if (p.size != 7 || p[0].isEmpty() || p[1].isEmpty()) return@mapNotNull null
        SessionCookie(
            name = p[0],
            value = p[1],
            domain = p[2].removePrefix(".").ifBlank { "youtube.com" },
            path = p[3].ifBlank { "/" },
            secure = p[4] == "1",
            httpOnly = p[5] == "1",
            expiresAt = p[6].toLongOrNull(),
        )
    }

private fun flattenShellContinuations(text: String): String = buildString(text.length) {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' || c == '^' || c == '`') {
            val next = text.getOrNull(i + 1)
            if (next == '\n') {
                append(' ')
                i += 2
                continue
            }
            if (next == '\r' && text.getOrNull(i + 2) == '\n') {
                append(' ')
                i += 3
                continue
            }
        }
        if (c == '\n' || c == '\r') append(' ') else append(c)
        i++
    }
}

private val CURL_CMD = Regex("""\bcurl(?:\.exe)?\s+""", RegexOption.IGNORE_CASE)
private val CURL_HEADER_COOKIE = Regex(
    """(?:-H|--header)(?:\s+|=)\$?(['"])\s*[Cc]ookie\s*:\s*(.*?)\1""",
)
private val CURL_COOKIE_FLAG = Regex(
    """(?:(?:^|[\s])(?:-b|--cookie))(?:\s+|=)(['"])(.*?)\1""",
)
private val COOKIE_HEADER_LINE = Regex("""^[Cc]ookie\s*:\s*(.+)$""", RegexOption.MULTILINE)

fun looksLikeCurlPaste(text: String): Boolean = CURL_CMD.containsMatchIn(text.trim())

private fun looksLikeCurl(text: String): Boolean = looksLikeCurlPaste(text)

private fun cookiePayloadFromPaste(text: String): String? {
    val flat = flattenShellContinuations(text)
    if (looksLikeCurl(flat)) return cookieValueFromCurl(flat).orEmpty()
    return COOKIE_HEADER_LINE.find(text)?.groupValues?.get(1)?.trim()
}

private fun cookieValueFromCurl(flat: String): String? {
    CURL_HEADER_COOKIE.find(flat)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    CURL_COOKIE_FLAG.find(flat)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return null
}

private fun parseCookieHeader(text: String): List<SessionCookie> {
    val collapsed = text.replace('\n', ' ').replace('\r', ' ').trim()
    val body = if (collapsed.startsWith("Cookie:", ignoreCase = true)) {
        collapsed.substringAfter(':').trim()
    } else {
        collapsed
    }
    if (!body.contains('=')) return emptyList()
    return body.split(';').mapNotNull { pair ->
        val eq = pair.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val name = pair.substring(0, eq).trim()
        val value = pair.substring(eq + 1).trim()
        if (name.isEmpty() || '=' in name || ' ' in name || ':' in name) return@mapNotNull null
        SessionCookie(name, value, domainForName(name), httpOnly = false)
    }
}

private fun parseNameValueLines(lines: List<String>): List<SessionCookie> =
    lines.mapNotNull { line ->
        val t = line.trim()
        if (t.isEmpty() || t.startsWith('#') || t.startsWith("//")) return@mapNotNull null
        val eq = t.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val name = t.substring(0, eq).trim()
        val value = t.substring(eq + 1).trim()
        if (name.isEmpty() || name.contains(' ')) return@mapNotNull null
        SessionCookie(name, value, domainForName(name), httpOnly = false)
    }

private fun parseNetscape(lines: List<String>): List<SessionCookie> =
    lines.mapNotNull { line ->
        val t = line.trim()
        if (t.isEmpty() || t.startsWith('#')) return@mapNotNull null
        val cols = t.split('\t')
        if (cols.size < 7) return@mapNotNull null
        val domain = cols[0].removePrefix("#HttpOnly_").removePrefix(".")
        val secure = cols[3].equals("TRUE", ignoreCase = true)
        val expiry = cols[4].toLongOrNull()?.let { if (it < 1_000_000_000_000L) it * 1000 else it }
        val name = cols[5]
        val value = cols.drop(6).joinToString("\t")
        if (name.isEmpty() || domain.isEmpty()) return@mapNotNull null
        SessionCookie(name, value, domain, cols[2].ifBlank { "/" }, secure, httpOnly = true, expiry)
    }

private fun parseChromeTsv(lines: List<String>): List<SessionCookie> {
    val nonEmpty = lines.filter { it.isNotBlank() }
    if (nonEmpty.isEmpty()) return emptyList()
    val headerCols = nonEmpty[0].split('\t').map { it.trim().lowercase() }
    val hasHeader = headerCols.any { it == "name" } && headerCols.any { it == "value" }
    val nameI = if (hasHeader) headerCols.indexOf("name") else 0
    val valueI = if (hasHeader) headerCols.indexOf("value") else 1
    val domainI = headerIndex(headerCols, hasHeader, "domain", 2)
    val pathI = headerIndex(headerCols, hasHeader, "path", 3)
    val expiresI = headerCols.indexOfFirst { it.startsWith("expire") }
    val httpOnlyI = headerCols.indexOfFirst { it == "httponly" || it == "http only" }
    val rows = if (hasHeader) nonEmpty.drop(1) else nonEmpty
    return rows.mapNotNull { row ->
        val cols = row.split('\t')
        val name = cols.getOrNull(nameI)?.trim().orEmpty()
        val value = cols.getOrNull(valueI).orEmpty()
        if (name.isEmpty() || name.equals("name", ignoreCase = true)) return@mapNotNull null
        val domain = cols.getOrNull(domainI)?.trim()?.removePrefix(".")?.ifBlank { null }
            ?: domainForName(name)
        val path = cols.getOrNull(pathI)?.trim()?.ifBlank { "/" } ?: "/"
        val expiry = cols.getOrNull(expiresI)?.let { parseExpiry(it) }
        val httpOnly = truthy(cols.getOrNull(httpOnlyI))
        SessionCookie(name, value, domain, path, secure = true, httpOnly = httpOnly, expiresAt = expiry)
    }
}

private fun parseJsonCookiePaste(text: String): List<SessionCookie> {
    if (text.firstOrNull() !in setOf('{', '[')) return emptyList()
    val objects = Regex("""\{[^{}]+\}""").findAll(text).map { it.value }.toList()
    return objects.mapNotNull { obj ->
        val name = jsonString(obj, "name") ?: jsonString(obj, "n") ?: return@mapNotNull null
        val value = jsonString(obj, "value") ?: jsonString(obj, "v") ?: return@mapNotNull null
        val domain = (jsonString(obj, "domain") ?: jsonString(obj, "d") ?: domainForName(name))
            .removePrefix(".")
        val path = jsonString(obj, "path") ?: jsonString(obj, "p") ?: "/"
        val secure = jsonBool(obj, "secure") ?: jsonBool(obj, "s") ?: true
        val httpOnly = jsonBool(obj, "httpOnly") ?: jsonBool(obj, "httponly") ?: jsonBool(obj, "h") ?: false
        val expiry = jsonString(obj, "expiresAt")?.toLongOrNull()
            ?: jsonString(obj, "expirationDate")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
            ?: jsonString(obj, "e")?.toLongOrNull()
        SessionCookie(name, value, domain, path, secure, httpOnly, expiry)
    }
}

private fun jsonString(obj: String, key: String): String? {
    val m = Regex(""""$key"\s*:\s*("(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?)""").find(obj) ?: return null
    val raw = m.groupValues[1]
    if (raw.startsWith('"')) return unescapeJson(raw.substring(1, raw.length - 1))
    return raw
}

private fun jsonBool(obj: String, key: String): Boolean? {
    val m = Regex(""""$key"\s*:\s*(true|false)""").find(obj) ?: return null
    return m.groupValues[1] == "true"
}

private fun unescapeJson(s: String): String = buildString(s.length) {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '\\' || i == s.lastIndex) {
            append(c)
            i++
            continue
        }
        when (val n = s[i + 1]) {
            'n' -> append('\n')
            'r' -> append('\r')
            't' -> append('\t')
            '"' -> append('"')
            '\\' -> append('\\')
            'u' if i + 5 < s.length -> {
                append(s.substring(i + 2, i + 6).toInt(16).toChar())
                i += 4
            }
            else -> append(n)
        }
        i += 2
    }
}

private fun headerIndex(header: List<String>, hasHeader: Boolean, name: String, fallback: Int): Int {
    if (!hasHeader) return fallback
    val i = header.indexOf(name)
    return if (i >= 0) i else fallback
}

private fun truthy(raw: String?): Boolean {
    val v = raw?.trim()?.lowercase().orEmpty()
    return v == "true" || v == "✓" || v == "✔" || v == "1" || v == "yes"
}

private fun parseExpiry(raw: String): Long? {
    val t = raw.trim()
    t.toLongOrNull()?.let { return if (it in 1 until 1_000_000_000_000L) it * 1000 else it }
    t.toDoubleOrNull()?.let { return (it * 1000).toLong() }
    return null
}

private fun domainForName(@Suppress("UNUSED_PARAMETER") name: String): String = "youtube.com"

private fun String.decodeBase64Url(): ByteArray? {
    val padded = when (length % 4) {
        2 -> "$this=="
        3 -> "$this="
        else -> this
    }.replace('-', '+').replace('_', '/')
    return padded.decodeBase64()?.toByteArray()
}

private const val QR_PREFIX = "ymusic-link:2:"
private const val QR_SEP = "k="
private val LINK_ID = Regex("^[A-Za-z0-9_-]{22}$")
private const val KEY_LEN = 32
private const val IV_LEN = 16
private const val MAC_LEN = 32
private const val ENC_LABEL = "enc"
private const val MAC_LABEL = "mac"
private const val SAS_LABEL = "sas"
private const val PERSIST_MS = 400L * 24 * 60 * 60 * 1000
/** The service keeps a slot open this long. */
private const val LINK_TTL_SEC = 600

internal const val DEVICE_LINK_POLL_MS = 2_000L

/** Consecutive network failures (each up to the 10s request timeout, plus the poll gap) before the wait gives up. */
internal const val DEVICE_LINK_MAX_FAILED_POLLS = 3
internal const val DEVICE_LINK_TIMEOUT_MS = LINK_TTL_SEC * 1000L

internal val ESSENTIAL_COOKIE_NAMES = setOf(
    "LOGIN_INFO",
    "SAPISID",
    "APISID",
    "HSID",
    "SSID",
    "SIDCC",
    "__Secure-1PAPISID",
    "__Secure-3PAPISID",
)

fun List<SessionCookie>.looksSignedIn(): Boolean =
    any { it.name in ESSENTIAL_COOKIE_NAMES && it.value.isNotEmpty() }

suspend fun awaitDeviceLinkCookies(
    api: ApiService?,
    offer: DeviceLinkOffer,
): List<SessionCookie> {
    val deadline = DEVICE_LINK_TIMEOUT_MS
    var waited = 0L
    // The wait spans minutes of short polls: one stalled poll must not end it, only a sustained outage.
    var failedPolls = 0
    while (waited <= deadline) {
        val cookies = try {
            pollDeviceLinkCookies(api, offer).also { failedPolls = 0 }
        } catch (e: IllegalStateException) {
            if (e.message != "network" || ++failedPolls >= DEVICE_LINK_MAX_FAILED_POLLS) throw e
            null
        }
        if (cookies != null) return cookies
        delay(DEVICE_LINK_POLL_MS)
        waited += DEVICE_LINK_POLL_MS
    }
    error("timeout")
}
