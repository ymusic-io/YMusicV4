package io.ymusic.remoteconfig

import kmphttp.AsyncInterceptor
import kmphttp.Cache
import kmphttp.CookieJar
import kmphttp.HeadersBuilder
import kmphttp.HttpStatusException
import kmphttp.InMemoryCookieJar
import kmphttp.KmpHttpClient
import kmphttp.Protocol
import kmphttp.RedirectOptions
import kmphttp.Request
import kmphttp.Response
import kmphttp.ResponseBuilder
import kmphttp.SslConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

private const val BODY = """
{
  "features": {"variableA": 7, "featureX": true},
  "appUpdate": {"versionCode":1300,"versionName":"4.1.0","notes":"n","url":"https://x/y.msi","size":12,"sha256":"AB","signature":"sig"},
  "playerJs": {"version": 123, "url": "https://x/player.js", "signature": "sig-player"},
  "engineJs": {"version": 11, "url": "https://x/engine.js", "signature": "sig-engine"}
}
"""

/**
 * A [KmpHttpClient] answering from [body]. The real dependency is faked rather than an interface
 * invented for the tests: this is what HttpApiService actually talks to.
 */
private class FakeHttp(var body: String = BODY) : KmpHttpClient {
    var calls = 0
    var failWith: Exception? = null
    var code = 200
    var lastUrl: String? = null
    var lastRequest: Request? = null

    override val interceptors: List<AsyncInterceptor> = emptyList()
    override val networkInterceptors: List<AsyncInterceptor> = emptyList()
    override val cache: Cache? = null
    override val cookieJar: CookieJar = InMemoryCookieJar()
    override val sslConfig: SslConfig = SslConfig()
    override val defaultRedirectOptions: RedirectOptions =
        RedirectOptions(followRedirects = true, followSslRedirects = true)
    override val defaultExecuteTimeout: Duration? = null
    override val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
    override val logger: kmpcommon.log.Logger? = null

    override suspend fun execute(request: Request, options: RedirectOptions, timeout: Duration?): Response {
        calls++
        lastUrl = request.url.toString()
        lastRequest = request
        failWith?.let { throw it }
        return ResponseBuilder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .headers(HeadersBuilder().add("Content-Type", "application/json").build())
            .body(Buffer().write(body.encodeToByteArray()).asResponseBody(null, -1L))
            .build()
    }

    override fun newBuilder(): KmpHttpClient.BaseBuilder<out KmpHttpClient> =
        throw UnsupportedOperationException("FakeHttp has no builder")
}

class HttpApiServiceTest {

    private var clock = 1_000_000L

    private fun service(
        client: KmpHttpClient,
        baseUrl: String = "https://cfg.example",
        deviceId: String = "device-1",
        apiKey: String? = null,
    ) = HttpApiService(
        client = client,
        os = "windows",
        osVersion = "11",
        appVersionCode = 1233,
        appVersionName = "1.0.0",
        deviceId = deviceId,
        baseUrl = baseUrl,
        now = { clock },
        apiKey = apiKey,
    )

    @Test
    fun a_success_is_parsed_and_stamped_with_the_clock() = runTest {
        val data = service(FakeHttp()).getConfig()

        assertEquals(7, data.features.variableA)
        assertTrue(data.features.featureX)
        assertEquals(123L, data.playerJs?.version)
        assertEquals(11L, data.engineJs?.version)
        assertEquals("https://x/y.msi", data.appUpdate?.url)
        assertEquals(12L, data.appUpdate?.size)
        assertEquals(1300, data.appUpdate?.versionCode)
        assertEquals("ab", data.appUpdate?.sha256)
        assertEquals(clock, data.updatedAt, "stamped from our clock, not read off the wire")
    }

    @Test
    fun the_common_context_rides_as_headers_on_every_call() = runTest {
        val client = FakeHttp()
        service(client, apiKey = "k-123").getConfig()

        val request = assertNotNull(client.lastRequest)
        assertEquals("windows", request.header(HttpApiService.HEADER_OS))
        assertEquals("11", request.header(HttpApiService.HEADER_OS_VER))
        assertEquals("1233", request.header(HttpApiService.HEADER_APP_VER))
        assertEquals("1.0.0", request.header(HttpApiService.HEADER_APP_VER_NAME))
        assertEquals("device-1", request.header(HttpApiService.HEADER_DEVICE_ID))
        assertEquals("k-123", request.header(HttpApiService.HEADER_API_KEY))
        assertEquals("https://cfg.example/v1/config", client.lastUrl)
    }

    @Test
    fun no_api_key_sends_no_api_key_header() = runTest {
        val client = FakeHttp()
        service(client).getConfig()

        assertNull(assertNotNull(client.lastRequest).header(HttpApiService.HEADER_API_KEY))
    }

    @Test
    fun pulse_posts_the_events_as_one_json_batch() = runTest {
        val client = FakeHttp()

        service(client).pulse(
            listOf(
                PulseEvent(name = "app_start", at = 42L, properties = mapOf("cold" to "true")),
                PulseEvent(name = "play", at = 43L),
            ),
        )

        assertEquals("https://cfg.example/v1/pulse", client.lastUrl)
        val request = assertNotNull(client.lastRequest)
        assertEquals("device-1", request.header(HttpApiService.HEADER_DEVICE_ID))
        val body = Buffer().also { assertNotNull(request.body).writeTo(it) }.readUtf8()
        assertEquals(
            """{"events":[{"name":"app_start","at":42,"props":{"cold":"true"}},{"name":"play","at":43}]}""",
            body,
        )
    }

    // Cookie handling is the client's jar now (PersistentCookieJar, tested in kmpHttp:core) —
    // this service neither reads nor writes Set-Cookie itself.

    @Test
    fun an_oversized_pulse_never_leaves_the_machine() = runTest {
        val client = FakeHttp()
        val events = List(PULSE_MAX_EVENTS + 1) { PulseEvent("app_start", it.toLong()) }

        assertFailsWith<IllegalArgumentException> { service(client).pulse(events) }

        assertEquals(0, client.calls, "the contract is enforced before the request, not by a 413")
    }

    @Test
    fun a_refused_pulse_throws_the_status() = runTest {
        val thrown = assertFailsWith<HttpStatusException> {
            service(FakeHttp().apply { code = 429 }).pulse(listOf(PulseEvent("app_start", 1L)))
        }

        assertEquals(429, thrown.code)
    }

    @Test
    fun a_refusal_throws_the_status() = runTest {
        val thrown = assertFailsWith<HttpStatusException> {
            service(FakeHttp().apply { code = 503 }).getConfig()
        }

        assertEquals(503, thrown.code)
        assertTrue(thrown.retryable, "a 5xx is the server's problem and may pass")
        assertFalse(HttpStatusException(404).retryable, "a 4xx is ours and will not")
        assertTrue(HttpStatusException(429).retryable)
    }

    @Test
    fun a_transport_failure_propagates_as_itself() = runTest {
        val boom = IOException("connection refused")

        val thrown = assertFailsWith<IOException> {
            service(FakeHttp().apply { failWith = boom }).getConfig()
        }

        // Equal message, not identity: crossing withContext, debug-mode stack recovery may hand back a copy.
        assertEquals(boom.message, thrown.message, "not wrapped: IOException already says what happened")
    }

    @Test
    fun a_body_that_is_not_json_throws() = runTest {
        assertFails {
            service(FakeHttp(body = "<html>502 Bad Gateway</html>")).getConfig()
        }
    }

    @Test
    fun missing_fields_fall_back_to_defaults() = runTest {
        val data = service(FakeHttp(body = "{}")).getConfig()

        assertEquals(0, data.features.variableA)
        assertFalse(data.features.featureX)
        assertNull(data.appUpdate)
        assertNull(data.playerJs)
    }

    @Test
    fun unknown_fields_are_ignored_so_the_service_can_add_one() = runTest {
        val body = """{"features":{"variableA":1,"featureZ":true},"somethingNew":{"a":[1,2]}}"""

        assertEquals(1, service(FakeHttp(body = body)).getConfig().features.variableA)
    }

    @Test
    fun a_base_url_that_is_not_a_url_throws_without_requesting() = runTest {
        val client = FakeHttp()

        assertFailsWith<IllegalArgumentException> { service(client, baseUrl = "not a url").getConfig() }

        assertEquals(0, client.calls, "a malformed request must never leave the machine")
    }
}
