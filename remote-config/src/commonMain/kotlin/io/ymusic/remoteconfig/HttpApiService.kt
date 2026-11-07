package io.ymusic.remoteconfig

import io.ymusic.remoteconfig.PulseEvent.Companion.value
import kmpcommon.io.buffer
import kmphttp.HttpStatusException
import kmphttp.HttpUrl
import kmphttp.HttpUrlBuilder
import kmphttp.KmpHttpClient
import kmphttp.MediaTypeX.toMediaType
import kmphttp.Request
import kmphttp.RequestBody
import kmphttp.RequestBodyX.asOneshotRequestBody
import kmphttp.RequestBodyX.toRequestBody
import kmphttp.RequestBuilder
import kmphttp.Response
import kmphttp.asyncSource
import kmphttp.deleteWithoutBody
import kmphttp.json.JsonReader
import kmphttp.json.JsonWriter
import kmphttp.toHttpUrlOrNull
import kmphttp.utf8String
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.use
import projectcommon.json.objects
import projectcommon.json.string

/**
 * The real [ApiService], over [KmpHttpClient]. Holds everything request-shaped: the base url and
 * what every call carries — os, arch, app version, the anonymous device uuid and the api key, all as
 * headers — so a caller gets a typed answer or a thrown failure, never a url or a status code to
 * interpret.
 *
 * Only construct with a non-blank [baseUrl]: a build without a service passes null [ApiService]
 * around instead. A [baseUrl] that is not a url throws from every call — a packaging mistake worth
 * hearing about, not a request worth sending.
 */
class HttpApiService(
    private val client: KmpHttpClient,
    /** Coarse platform name the service keys releases by: windows, macos, linux, android, ios. */
    private val os: String,
    /** The platform's own version string: Android release ("15"), macOS/Windows version, etc. */
    private val osVersion: String,
    private val appVersionCode: Int,
    /** The human-readable version ("1.0.0"), for the dashboard; [appVersionCode] decides updates. */
    private val appVersionName: String,
    private val deviceId: String,
    private val baseUrl: String,
    private val now: () -> Long,
    private val apiKey: String? = null,
    /** Which build this device needs: the CPU (x64, arm64) on desktop, the primary ABI on Android. */
    private val arch: String? = null,
    /** The JS bundle revisions to report; a header is omitted while its revision is unknown. */
    private val bundles: RunningBundles? = null,
    /** "tv" on an Android TV, which shares [os] with phones (same APK, same updates) but is counted apart. */
    private val formFactor: String? = null,
) : ApiService {

    override suspend fun getConfig(): RemoteConfigData {
        val url = url().addPathSegment("v1").addPathSegment("config").build()
        return exchange(request(url).get().build()) { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            // Parsed straight off the socket — the body is never held whole. [now] stamps
            // updatedAt at the moment of parse; see RemoteConfigData.from for who may override it.
            RemoteConfigData.from(JsonReader(response.body.asyncSource().buffer()).readObject(), now)
        }
    }

    override suspend fun pulse(events: List<PulseEvent>) {
        require(events.size <= PULSE_MAX_EVENTS) {
            "pulse carries at most $PULSE_MAX_EVENTS events, got ${events.size} — chunk the queue"
        }
        val writer = JsonWriter.Sync()
        with(writer) {
            beginObject()
            name("events").beginArray()
            events.forEach { value(it) }
            endArray()
            endObject()
        }
        val url = url().addPathSegment("v1").addPathSegment("pulse").build()
        val body = writer.buffer.asOneshotRequestBody("application/json".toMediaType())
        exchange(request(url).post(body).build()) { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
        }
    }

    override suspend fun openDeviceLink(): String {
        return exchange(request(linkUrl()).post(RequestBody.EMPTY).build()) { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            JsonReader(response.body.asyncSource().buffer()).readObject().string("id")
        }
    }

    override suspend fun putDeviceLink(id: String, payload: String): Boolean {
        val body = payload.toRequestBody("text/plain".toMediaType())
        return exchange(request(linkUrl(id)).put(body).build()) { response ->
            when {
                response.code == 404 -> false
                response.isSuccessful -> true
                else -> throw HttpStatusException(response.code)
            }
        }
    }

    override suspend fun takeDeviceLink(id: String): String? =
        exchange(request(linkUrl(id)).get().build()) { response ->
            when (response.code) {
                200 -> response.body.utf8String()
                204 -> null
                else -> throw HttpStatusException(response.code)
            }
        }

    override suspend fun closeDeviceLink(id: String) {
        exchange(request(linkUrl(id)).deleteWithoutBody().build()) { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
        }
    }

    override suspend fun getSkipSegments(videoId: String): List<SkipSegment> {
        val url = url().addPathSegment("v1").addPathSegment("sponsorblock").addPathSegment("segments")
            .addQueryParameter("v", videoId).build()
        return exchange(request(url).get().build()) { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            JsonReader(response.body.asyncSource().buffer()).readObject().objects("segments").map(SkipSegment::from)
        }
    }

    // Off the caller's thread end to end: on JVM, closing an okhttp response drains the rest of a
    // cache-written body over the socket, a blocking read.
    private suspend fun <T> exchange(request: Request, read: suspend (Response) -> T): T =
        withContext(Dispatchers.IO) { client.execute(request).use { read(it) } }

    private fun linkUrl(id: String? = null): HttpUrl {
        val url = url().addPathSegment("v1").addPathSegment("link")
        id?.let { url.addPathSegment(it) }
        return url.build()
    }

    private fun url(): HttpUrlBuilder =
        requireNotNull(baseUrl.toHttpUrlOrNull()) { "not a url: $baseUrl" }.newBuilder()

    /** Every call goes out with the common headers; a new endpoint only picks its url and verb. */
    private fun request(url: HttpUrl): RequestBuilder {
        val request = RequestBuilder().url(url)
            .header(HEADER_OS, os)
            .header(HEADER_OS_VER, osVersion)
            .header(HEADER_APP_VER, appVersionCode.toString())
            .header(HEADER_APP_VER_NAME, appVersionName)
            .header(HEADER_DEVICE_ID, deviceId)
        arch?.let { request.header(HEADER_ARCH, it) }
        formFactor?.let { request.header(HEADER_FORM_FACTOR, it) }
        apiKey?.let { request.header(HEADER_API_KEY, it) }
        bundles?.reportedEngine?.let { request.header(HEADER_ENGINE_JS_VER, it.toString()) }
        bundles?.reportedPlayer?.let { request.header(HEADER_PLAYER_JS_VER, it.toString()) }
        return request
    }

    companion object {
        const val HEADER_OS = "x-os"
        const val HEADER_OS_VER = "x-os-ver"
        const val HEADER_FORM_FACTOR = "x-form-factor"
        const val HEADER_APP_VER = "x-app-ver"
        const val HEADER_APP_VER_NAME = "x-app-ver-name"
        const val HEADER_DEVICE_ID = "x-device-id"
        const val HEADER_ARCH = "x-arch"
        const val HEADER_API_KEY = "x-api-key"
        const val HEADER_ENGINE_JS_VER = "x-engine-js-ver"
        const val HEADER_PLAYER_JS_VER = "x-player-js-ver"
    }
}
