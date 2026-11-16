package io.ymusic.dynamicengine

import io.ymusic.dynamicengine.internal.ENGINE_TEST_JS
import io.ymusic.dynamicengine.internal.ENGINE_TEST_JS_VERSION
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** A test-only P-256 key: its private half signed the two fixtures below and was then discarded. */
private const val TEST_KEY =
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAELb+QZFQDWvbVdnmvTsLJfYvl7ixBqxU7iPZxXhLUu9DBGjjUQG/+BA+E59X8N0Gcc4dXupri/9F1QINooLZnQQ=="

/** "remote-js-v21" as engine v21. */
private val SIGNED_V21 = SignedBundleRef(
    21L,
    "https://cdn/x.js",
    "MEUCICmR0fIeZ+dTszYFjkfXm8fPVugBHv0w/n+8Sp26ttRgAiEAqsGjrr7oIrl+w75op92pU70EiBqtPoWeL3aCLNPVLY8=",
)

/** "old-remote" as engine v3. */
private val SIGNED_V3 = SignedBundleRef(
    3L,
    "https://cdn/x.js",
    "MEUCIQCz9Ma53/XGR408+xm7P1hCkHuvhSLT0fx2i2M6yIcD+QIgbMjnPWu3lkvdTlREaRwKxKy4yWKzevwlRQz5pexSeUM=",
)

/** A config that names [bundle]. */
private fun configOf(bundle: SignedBundleRef?) = object : JsConfigProvider {
    override val published: Flow<SignedBundleRef?> = flowOf(bundle)
    override fun checkUpdate(acceptMaxAgeMs: Long) = Unit
}

class JsSourceProviderTest {

    private val dir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ymusic-js-source-test"
    private var clock = 1_000L

    @BeforeTest
    @AfterTest
    fun clean() {
        FileSystem.SYSTEM.deleteRecursively(dir)
    }

    private fun provider(
        config: JsConfigProvider = configOf(null),
        client: FakeHttpClient = FakeHttpClient(CannedResponse.ofText("")),
        embedVersion: Long = 5L,
        embedSource: suspend () -> String = { "embed-js" },
        publicKey: String = TEST_KEY,
    ) = DefaultJsSourceProvider(
        client = client,
        config = config,
        name = ENGINE_BUNDLE,
        publicKey = publicKey,
        embedVersion = embedVersion,
        embedSource = embedSource,
        fileSystem = FileSystem.SYSTEM,
        directory = dir,
        now = { clock },
    )

    @Test
    fun embed_serves_when_nothing_is_cached() = runTest {
        var reads = 0
        val p = provider(embedSource = { reads++; "embed-js" })
        assertNull(p.getCached())
        assertEquals("embed-js" to 5L, p.getEmbed())
        assertEquals("embed-js" to 5L, p.getEmbed())
        assertEquals(1, reads, "the embed asset loads lazily, once")
    }

    @Test
    fun download_verifies_and_store_caches() = runTest {
        val p = provider(client = FakeHttpClient(CannedResponse.ofText("remote-js-v21")))
        val fetched = p.download(SIGNED_V21)
        assertEquals("remote-js-v21", fetched.source)
        assertNull(p.getCached(), "nothing is cached before store")
        p.store(fetched)
        assertEquals("remote-js-v21" to 21L, p.getCached())
    }

    @Test
    fun a_bundle_failing_its_signature_is_refused() = runTest {
        val p = provider(client = FakeHttpClient(CannedResponse.ofText("tampered")))
        assertFailsWith<BundleSignatureException> { p.download(SIGNED_V21) }
        assertNull(p.getCached())
    }

    @Test
    fun without_a_key_nothing_is_published() = runTest {
        val p = provider(config = configOf(SIGNED_V21), publicKey = "")
        assertNull(p.published.first())
    }

    @Test
    fun a_cache_older_than_the_embed_is_superseded() = runTest {
        // Cache version 3 with an embed at version 2 — cached wins…
        val p3 = provider(embedVersion = 2L, client = FakeHttpClient(CannedResponse.ofText("old-remote")))
        p3.store(p3.download(SIGNED_V3))
        assertEquals("old-remote" to 3L, p3.getCached())
        // …then the app updates and ships embed version 5: the stale cache reads as absent.
        val p5 = provider(embedVersion = 5L)
        assertNull(p5.getCached())
        assertEquals("embed-js" to 5L, p5.getEmbed())
    }
}

/** The manager's provider path: cached boots (with the deadline), a rejected cache falls back to
 *  the embed, a newer published bundle boots at cold start, and later configs swap one in through
 *  the full boot gate. */
class ManagerJsSourceTest {

    private class FakeSource(
        var cached: Pair<String, Long>? = null,
        val embed: Pair<String, Long> = ENGINE_TEST_JS to 20L,
        var remoteJs: String = ENGINE_TEST_JS,
    ) : JsSourceProvider {
        var saved: Pair<String, Long>? = null
        override val published = MutableStateFlow<SignedBundleRef?>(null)
        override suspend fun getCached() = cached
        override suspend fun getEmbed() = embed
        override fun checkUpdate() = Unit
        override suspend fun download(remote: SignedBundleRef) =
            VerifiedBundle(remote, remoteJs, remoteJs.encodeUtf8())
        override suspend fun store(bundle: VerifiedBundle) {
            saved = bundle.source to bundle.version
        }
    }

    /** The revision ENGINE_TEST_JS declares — every bundle in these tests is it. */
    private val TEST_BACKEND = ENGINE_TEST_JS_VERSION.toInt()

    /** A published revision newer than every bundle these tests boot. */
    private val NEWER = TEST_BACKEND + 1L

    private fun manager(source: JsSourceProvider) = DynamicEngineManager(
        client = FakeHttpClient(CannedResponse.ofText("")),
        appVersion = 1,
        jsSource = source,
        remoteBootTimeoutMs = 2_000L,
    )

    @Test
    fun start_boots_the_cached_bundle() = runTest {
        val m = manager(FakeSource(cached = ENGINE_TEST_JS to 21L))
        try {
            assertEquals(TEST_BACKEND, m.start()) // ENGINE_TEST_JS self-declares its own revision, whatever the cache tag
        } finally {
            m.close()
        }
    }

    @Test
    fun a_rejected_cache_falls_back_to_the_embed() = runTest {
        val m = manager(FakeSource(cached = "throw new Error('rotten cache')" to 21L))
        try {
            assertEquals(TEST_BACKEND, m.start())
        } finally {
            m.close()
        }
    }

    @Test
    fun a_published_bundle_failing_the_boot_gate_is_rejected_and_not_cached() = runTest {
        val source = FakeSource(remoteJs = "throw new Error('bad push')")
        val m = manager(source)
        try {
            m.start()
            assertFailsWith<EngineError> { m.updateTo(SIGNED_V21.copy(version = NEWER)) }
            assertNull(source.saved)
            assertNull(m.updateTo(SIGNED_V21.copy(version = NEWER)), "the same bundle is not tried again")
        } finally {
            m.close()
        }
    }

    @Test
    fun a_newer_published_bundle_swaps_in_and_is_cached() = runTest {
        val source = FakeSource()
        val m = manager(source)
        try {
            m.start() // embed (nothing cached or published)
            assertEquals(TEST_BACKEND, m.updateTo(SIGNED_V21.copy(version = NEWER)))
            assertEquals(ENGINE_TEST_JS to NEWER, source.saved, "validated bundle is handed back for caching")
        } finally {
            m.close()
        }
    }
}
