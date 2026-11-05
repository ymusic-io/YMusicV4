package io.ymusic.imageloader

import androidx.compose.ui.graphics.asComposeImageBitmap
import kmphttp.AsyncInterceptor
import kmphttp.Cache
import kmphttp.CookieJar
import kmphttp.KmpHttpClient
import kmphttp.Protocol
import kmphttp.RedirectOptions
import kmphttp.Request
import kmphttp.Response
import kmphttp.ResponseBuilder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.IOException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.jetbrains.skia.Bitmap
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

private class FakeImageServer(
    private val imageBytes: ByteArray,
    private val status: Int = 200,
) : KmpHttpClient {
    var fetchCount = 0
        private set

    override val interceptors: List<AsyncInterceptor> = emptyList()
    override val networkInterceptors: List<AsyncInterceptor> = emptyList()
    override val cache: Cache? = null
    override val cookieJar: CookieJar = CookieJar.NO_COOKIES
    override val sslConfig = kmphttp.SslConfig()
    override val defaultRedirectOptions = RedirectOptions(followRedirects = true, followSslRedirects = true)
    override val defaultExecuteTimeout: Duration? = null
    override val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
    override val logger: kmpcommon.log.Logger? = null

    override suspend fun execute(request: Request, options: RedirectOptions, timeout: Duration?): Response {
        fetchCount++
        return ResponseBuilder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message(if (status == 200) "OK" else "ERR")
            .body(imageBytes.toResponseBody(null))
            .build()
    }

    override fun newBuilder(): KmpHttpClient.BaseBuilder<out KmpHttpClient> = throw UnsupportedOperationException()
}

private fun pngBytes(width: Int, height: Int): ByteArray {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = Color.ORANGE
    g.fillRect(0, 0, width, height)
    g.dispose()
    val out = ByteArrayOutputStream()
    ImageIO.write(img, "png", out)
    return out.toByteArray()
}

class ImageLoaderTest {

    @Test
    fun decodesScaledDownToTheRequestedSize() = runTest {
        val server = FakeImageServer(pngBytes(400, 200))
        val loader = ImageLoader(listOf(networkFetcher(server)))
        // Requests bucket up to 64: a 400x200 source into 64x64 → 64x32 (aspect kept).
        val bitmap = loader.load("https://img.test/a.png", 50, 50)
        // 50 buckets up to 64; 400x200 scaled to cover 64x64
        assertEquals(128, bitmap.width)
        assertEquals(64, bitmap.height)
    }

    @Test
    fun smallImagesAreNeverUpscaled() = runTest {
        val server = FakeImageServer(pngBytes(40, 20))
        val loader = ImageLoader(listOf(networkFetcher(server)))
        val bitmap = loader.load("https://img.test/small.png", 500, 500)
        assertEquals(40, bitmap.width)
        assertEquals(20, bitmap.height)
    }

    @Test
    fun memoryCacheServesRepeatLoads() = runTest {
        val server = FakeImageServer(pngBytes(100, 100))
        val loader = ImageLoader(listOf(networkFetcher(server)))
        val first = loader.load("https://img.test/a.png", 64, 64)
        val second = loader.load("https://img.test/a.png", 64, 64)
        assertEquals(1, server.fetchCount, "second load must come from the memory cache")
        assertTrue(first === second, "must be the same cached instance")
    }

    @Test
    fun scaledCacheSkipsFetchAcrossLoaderInstances() = runTest {
        val fs = FakeFileSystem()
        val cache = DiskScaledImageCache(fs, "/img".toPath())
        val serverA = FakeImageServer(pngBytes(400, 200))
        ImageLoader(listOf(networkFetcher(serverA)), scaledCache = cache).load("https://img.test/a.png", 64, 64)
        assertEquals(1, serverA.fetchCount)

        // A fresh loader (cold memory cache) over the same disk cache: no fetch, same scaled size.
        val serverB = FakeImageServer(pngBytes(400, 200))
        val warm = ImageLoader(listOf(networkFetcher(serverB)), scaledCache = cache).load("https://img.test/a.png", 64, 64)
        assertEquals(0, serverB.fetchCount, "scaled cache must serve the warm load")
        assertEquals(128, warm.width)
        assertEquals(64, warm.height)
    }

    @Test
    fun networkFetcherSharesOneDownloadAcrossSizes() = runTest {
        val fs = FakeFileSystem()
        val server = FakeImageServer(pngBytes(400, 200))
        val fetcher = networkFetcher(server, fs, "/src".toPath())
        ImageLoader(listOf(fetcher)).load("https://img.test/a.png", 64, 64)
        assertEquals(1, server.fetchCount)

        // A different size bucket on a cold loader decodes from the on-disk original — no refetch.
        val warm = ImageLoader(listOf(fetcher)).load("https://img.test/a.png", 200, 200)
        assertEquals(1, server.fetchCount, "the disk cache must serve other sizes without a refetch")
        assertEquals(400, warm.width) // 200 buckets up to 256; covering 256x256 needs no downscale of 400x200
    }

    @Test
    fun fileUrlDecodesFromDiskWithoutNetwork() = runTest {
        val tmp = kotlin.io.path.createTempFile("imgloader", ".png").toFile()
        try {
            tmp.writeBytes(pngBytes(400, 200))
            val loaded = ImageLoader(listOf(fileFetcher())).load("file://${tmp.absolutePath}", 64, 64)
            assertEquals(128, loaded.width)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun customFetcherResolvesItsScheme() = runTest {
        val server = FakeImageServer(pngBytes(10, 10))
        val art = pngBytes(400, 200)
        val loader = ImageLoader(
            listOf(
                ImageFetcher { uri, size, scale -> if (uri.scheme?.toString() == "art") decodeScaled(art, size, scale) else null },
                networkFetcher(server),
            ),
        )
        val loaded = loader.load("art:///music/track.mp3", 64, 64)
        assertEquals(0, server.fetchCount, "the fetcher must resolve its own scheme")
        assertEquals(128, loaded.width)
    }

    @Test
    fun corruptSourceEntryFailsWithoutRefetching() = runTest {
        val fs = FakeFileSystem()
        val server = FakeImageServer("not an image".encodeToByteArray())
        val loader = ImageLoader(listOf(networkFetcher(server, fs, "/src".toPath())))
        assertEquals(
            ImageLoadException.Reason.Undecodable,
            assertFailsWith<ImageLoadException> { loader.load("https://img.test/junk.png", 64, 64) }.reason,
        )
        assertEquals(1, server.fetchCount)

        // The garbage entry is committed; further loads must fail locally, not re-download it.
        assertFailsWith<ImageLoadException> { loader.load("https://img.test/junk.png", 64, 64) }
        assertEquals(1, server.fetchCount, "a decodable-as-garbage entry must not refetch per load")
    }

    @Test
    fun diskNetworkFetcherRejectsOversizedBody() = runTest {
        val fs = FakeFileSystem()
        val server = FakeImageServer(pngBytes(400, 200))
        val loader = ImageLoader(listOf(networkFetcher(server, fs, "/src".toPath(), maxImageBytes = 64)))
        assertFailsWith<IOException> { loader.load("https://img.test/a.png", 64, 64) }
    }

    @Test
    fun streamNetworkFetcherRejectsOversizedBody() = runTest {
        val server = FakeImageServer(pngBytes(400, 200))
        val loader = ImageLoader(listOf(networkFetcher(server, maxImageBytes = 64)))
        assertFailsWith<IOException> { loader.load("https://img.test/huge.png", 64, 64) }
    }

    @Test
    fun unscaledResultIsNotPersisted() = runTest {
        val fs = FakeFileSystem()
        val cache = DiskScaledImageCache(fs, "/img".toPath())
        val serverA = FakeImageServer(pngBytes(40, 20))
        ImageLoader(listOf(networkFetcher(serverA)), scaledCache = cache).load("https://img.test/small.png", 64, 64)
        assertEquals(1, serverA.fetchCount)

        // Nothing was shrunk, so nothing may be persisted: a cold loader must fetch again
        // (the HTTP cache already holds the original; a PNG re-encode would only waste disk).
        val serverB = FakeImageServer(pngBytes(40, 20))
        ImageLoader(listOf(networkFetcher(serverB)), scaledCache = cache).load("https://img.test/small.png", 64, 64)
        assertEquals(1, serverB.fetchCount, "an un-downscaled image must not be served from the scaled cache")
    }

    @Test
    fun rotatingSignatureSharesEntries() = runTest {
        val server = FakeImageServer(pngBytes(100, 100))
        val loader = ImageLoader(listOf(networkFetcher(server)))
        val stable = "https://img.test/vi/abc/hq720.jpg?sqp=size1"
        val a = loader.load("$stable&rs=1", 64, 64)
        val b = loader.load("$stable&rs=2", 64, 64)
        assertEquals(1, server.fetchCount, "a rotated rs signature must hit the cache")
        assertTrue(a === b)
        assertTrue(loader.peek("$stable&rs=3", 64, 64) === a)
        assertEquals(null, loader.peek("https://img.test/vi/abc/hq720.jpg?sqp=size2&rs=1", 64, 64), "sqp selects the image")
    }

    @Test
    fun peekIsSynchronousMemoryHit() = runTest {
        val server = FakeImageServer(pngBytes(100, 100))
        val loader = ImageLoader(listOf(networkFetcher(server)))
        assertEquals(null, loader.peek("https://img.test/a.png", 64, 64))
        val bitmap = loader.load("https://img.test/a.png", 64, 64)
        assertTrue(loader.peek("https://img.test/a.png", 64, 64) === bitmap)
        assertEquals(null, loader.peek("https://img.test/a.png", 500, 500), "other sizes stay misses")
    }

    @Test
    fun trimAndBudgetEvictTheMemoryCache() = runTest {
        val server = FakeImageServer(pngBytes(100, 100))
        val loader = ImageLoader(listOf(networkFetcher(server)))
        val url = "https://img.test/a.png"
        loader.load(url, 64, 64)

        loader.trimMemoryCache(0f) // pressure: clear, budget unchanged
        assertEquals(null, loader.peek(url, 64, 64))
        loader.load(url, 64, 64)
        assertTrue(loader.peek(url, 64, 64) != null, "cache regrows after a transient trim")

        loader.setMemoryCacheBudget(0)
        assertEquals(null, loader.peek(url, 64, 64))
        loader.load(url, 64, 64)
        assertEquals(null, loader.peek(url, 64, 64), "a zero budget keeps nothing")
    }

    @Test
    fun httpFailureThrows() = runTest {
        val server = FakeImageServer(pngBytes(10, 10), status = 404)
        val loader = ImageLoader(listOf(networkFetcher(server)))
        assertEquals(
            ImageLoadException.Reason.NotFound,
            assertFailsWith<ImageLoadException> { loader.load("https://img.test/missing.png", 64, 64) }.reason,
        )
    }

    @Test
    fun undecodableBodyThrows() = runTest {
        val server = FakeImageServer("not an image".encodeToByteArray())
        val loader = ImageLoader(listOf(networkFetcher(server)))
        assertEquals(
            ImageLoadException.Reason.Undecodable,
            assertFailsWith<ImageLoadException> { loader.load("https://img.test/junk.png", 64, 64) }.reason,
        )
    }

    @Test
    fun cacheInMemoryFalseSkipsTheMemoryLru() = runTest {
        val server = FakeImageServer(pngBytes(100, 100))
        val loader = ImageLoader(listOf(networkFetcher(server)))
        val url = "https://img.test/a.png"
        loader.load(url, 64, 64, cacheInMemory = false)
        assertEquals(null, loader.peek(url, 64, 64), "an opted-out load must not populate the LRU")
        loader.load(url, 64, 64, cacheInMemory = false)
        assertEquals(2, server.fetchCount, "without memory or scaled cache each load refetches")

        // An entry someone else already cached is still used — only the PUT is skipped.
        val cached = loader.load(url, 64, 64)
        assertTrue(loader.load(url, 64, 64, cacheInMemory = false) === cached)
    }

    @Test
    fun loadFileReturnsAPersistedScaledEntry() = runTest {
        val fs = FakeFileSystem()
        val cache = DiskScaledImageCache(fs, "/img".toPath())
        val server = FakeImageServer(pngBytes(400, 200))
        val loader = ImageLoader(listOf(networkFetcher(server)), scaledCache = cache)
        val url = "https://img.test/a.png"

        val path = loader.loadFile(url, 64, 64)
        assertNotNull(path)
        assertTrue(fs.exists(path), "the returned path must be a committed cache file")
        assertEquals(1, server.fetchCount)
        assertEquals(null, loader.peek(url, 64, 64), "loadFile must stay out of the memory LRU")

        assertEquals(path, loader.loadFile(url, 64, 64))
        assertEquals(1, server.fetchCount, "a repeat loadFile must serve the existing entry")

        // load() at the same bucket rides the same entry — no refetch.
        loader.load(url, 64, 64)
        assertEquals(1, server.fetchCount, "load must decode from the entry loadFile persisted")
    }

    @Test
    fun loadFilePersistsEvenUnscaledResults() = runTest {
        val fs = FakeFileSystem()
        val cache = DiskScaledImageCache(fs, "/img".toPath())
        val serverA = FakeImageServer(pngBytes(40, 20))
        val loader = ImageLoader(listOf(networkFetcher(serverA)), scaledCache = cache)
        // 40x20 into 64x64 is not downscaled — load() wouldn't persist it, loadFile must (the
        // caller needs a file, and an art:// source has no other on-disk form).
        assertNotNull(loader.loadFile("https://img.test/small.png", 64, 64))

        val serverB = FakeImageServer(pngBytes(40, 20))
        val cold = ImageLoader(listOf(networkFetcher(serverB)), scaledCache = cache)
        assertNotNull(cold.loadFile("https://img.test/small.png", 64, 64))
        assertEquals(0, serverB.fetchCount, "the persisted un-downscaled entry must serve cold")
    }

    @Test
    fun loadFileWithoutAScaledCacheReturnsNull() = runTest {
        val server = FakeImageServer(pngBytes(100, 100))
        val loader = ImageLoader(listOf(networkFetcher(server)))
        assertEquals(null, loader.loadFile("https://img.test/a.png", 64, 64))
        assertEquals(0, server.fetchCount, "no cache to persist into → nothing to fetch")
    }

    @Test
    fun diskCacheEvictsLeastRecentlyUsedWhenOverBudget() = runTest {
        val fs = FakeFileSystem()
        val warmup = DiskScaledImageCache(fs, "/img".toPath())
        warmup.put("aa", Bitmap().apply { allocN32Pixels(8, 8) }.asComposeImageBitmap())
        warmup.put("bb", Bitmap().apply { allocN32Pixels(8, 8) }.asComposeImageBitmap())
        warmup.get("aa") // refreshes "aa" — LRU, not FIFO: "bb" is now the eviction candidate
        val total = warmup.sizeBytes
        warmup.close()

        // Reopen one byte under the total: exactly the least-recently-used entry must go.
        val cache = DiskScaledImageCache(fs, "/img".toPath(), maxBytes = total - 1)
        cache.trimNow()
        assertEquals(null, cache.get("bb"))
        assertEquals(8, cache.get("aa")?.width)
    }
}
