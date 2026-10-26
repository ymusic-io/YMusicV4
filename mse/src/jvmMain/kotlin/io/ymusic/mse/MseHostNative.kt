package io.ymusic.mse

import androidx.annotation.Keep
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

interface MseHostSink {
    @Keep fun onFrame(width: Int, height: Int, bgra: ByteBuffer)

    @Keep fun onPcm(rate: Int, channels: Int, s16: ByteArray)

    @Keep fun onProgress(positionSec: Double, durationSec: Double, width: Int, height: Int)

    /** Called on pause/seek via the C `MseAudioFlushFn` hook. Sinks that hold their own buffered
     *  audio (JVM javax.sound.sampled) flush here so stale samples don't drain over seconds. */
    @Keep fun onAudioFlush() = Unit
}

actual object MseHostNative {
    private val loaded = AtomicBoolean(false)
    private var frameScratch = ByteArray(0)
    private val frameMeta = IntArray(3)

    init {
        loadLibrary()
    }

    @JvmStatic
    actual external fun vtable(): Long

    @JvmStatic
    actual external fun create(): Long

    @JvmStatic
    actual external fun destroy(handle: Long)

    @JvmStatic
    actual external fun frames(handle: Long): Int

    @JvmStatic
    actual external fun samplesPlayed(handle: Long): Long

    actual fun copyFrame(handle: Long, afterGen: Int): NativeMseFrame? {
        if (handle == 0L) return null
        var n = copyFrameBytes(handle, afterGen, frameMeta, frameScratch)
        if (n <= 0) return null
        if (n > frameScratch.size) {
            frameScratch = ByteArray(n)
            n = copyFrameBytes(handle, afterGen, frameMeta, frameScratch)
            if (n <= 0 || n > frameScratch.size) return null
        }
        return NativeMseFrame(frameMeta[0], frameMeta[1], frameMeta[2], frameScratch)
    }

    @JvmStatic
    private external fun copyFrameBytes(handle: Long, afterGen: Int, meta: IntArray, dest: ByteArray): Int

    @JvmStatic
    external fun setSink(handle: Long, sink: MseHostSink)

    @JvmStatic
    external fun videoSize(handle: Long, outWh: IntArray): Boolean

    /** 0×0 = decode size. Downscale-only letterbox fit when both > 0. */
    @JvmStatic
    external fun setBlitSize(handle: Long, width: Int, height: Int)

    @JvmStatic
    actual external fun setVolume(handle: Long, vol: Float)

    @JvmStatic
    actual external fun setSpeed(handle: Long, speed: Float, tempo: Float)

    @JvmStatic
    actual external fun setPitch(handle: Long, pitch: Float)

    @JvmStatic
    actual external fun setEq(handle: Long, bands: FloatArray)

    @JvmStatic
    actual external fun setBass(handle: Long, db: Float)

    @JvmStatic
    actual external fun setVirtualizer(handle: Long, width: Float)

    actual external fun setPreamp(handle: Long, db: Float)

    @JvmStatic
    actual external fun pcmLevels(handle: Long, out: FloatArray): Int

    /** macOS: overlay VideoToolbox on [component] via JAWT. Other JVMs return false. */
    @JvmStatic
    external fun setVideoComponent(handle: Long, component: java.awt.Component?): Boolean

    private fun loadLibrary() {
        if (!loaded.compareAndSet(false, true)) return
        runCatching { System.loadLibrary("jawt") }
        val name = "mseffmpegjni"
        val libFileName = System.mapLibraryName(name)
        System.getProperty("mse.native.dir")?.let { dir ->
            preloadWindowsDeps(File(dir), libFileName)
            System.load(File(dir, libFileName).absolutePath)
            return
        }
        val libraryPathError = try {
            System.loadLibrary(name)
            return
        } catch (error: UnsatisfiedLinkError) {
            error
        }
        try {
            loadBundled(name, libFileName)
        } catch (error: Throwable) {
            error.addSuppressed(libraryPathError)
            throw error
        }
    }

    private fun loadBundled(name: String, libFileName: String) {
        // Unpack JNI + sibling libav into one temp dir so @loader_path / $ORIGIN resolves.
        val folder = "jni/${hostJniFolder()}"
        val cl = MseHostNative::class.java.classLoader
            ?: throw UnsatisfiedLinkError("No classloader for $name")
        val names = cl.getResourceAsStream("$folder/natives.txt")?.bufferedReader()?.use { it.readLines() }
            ?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOf(libFileName)
        val tmpDir = Files.createTempDirectory("mseffmpegjni").toFile().also { it.deleteOnExit() }
        for (n in names) {
            val stream = cl.getResourceAsStream("$folder/$n")
                ?: throw UnsatisfiedLinkError("No bundled $name at $folder/$n")
            stream.use { inp ->
                val dest = File(tmpDir, n).also { it.deleteOnExit() }
                Files.copy(inp, dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        preloadWindowsDeps(tmpDir, libFileName)
        System.load(File(tmpDir, libFileName).absolutePath)
    }

    /** Windows's LoadLibraryEx(LOAD_WITH_ALTERED_SEARCH_PATH) honors the altered dir only for the
     *  first-level DLL — transitive imports fall back to the standard search, which misses our
     *  tmpdir/appResources. Pre-loading each dep by absolute path puts them in the process module
     *  table under their filename, so subsequent LoadLibrary(name) during transitive resolution
     *  finds the already-loaded handle. @loader_path on macOS and $ORIGIN on Linux handle this
     *  without help. */
    private fun preloadWindowsDeps(dir: File, selfLibFileName: String) {
        val os = System.getProperty("os.name").lowercase(Locale.US)
        if (!os.contains("win")) return
        // Load in dependency order (OS-level first, then libav bottom-up). Missing files are fine:
        // not every pack ships zlib1/libwinpthread, and av* names depend on soversion.
        val order = listOf(
            "zlib1.dll", "libwinpthread-1.dll",
            "avutil-61.dll", "swresample-7.dll", "swscale-10.dll",
            "avcodec-63.dll", "avformat-63.dll", "avfilter-12.dll", "avdevice-63.dll",
        )
        for (n in order) {
            if (n == selfLibFileName) continue
            val f = File(dir, n)
            if (f.isFile) {
                @Suppress("UnsafeDynamicallyLoadedCode")
                runCatching { System.load(f.absolutePath) }
            }
        }
    }

    private fun hostJniFolder(): String {
        val os = System.getProperty("os.name").lowercase(Locale.US)
        val arch = System.getProperty("os.arch").lowercase(Locale.US)
        val osPrefix = when {
            os.contains("mac") || os.contains("osx") -> "macos"
            os.contains("linux") -> "linux"
            os.contains("win") -> "windows"
            else -> error("Unsupported OS: $os")
        }
        val archSuffix = when (arch) {
            "aarch64" -> "aarch64"
            "amd64", "x86_64" -> "x64"
            else -> error("Unsupported arch: $arch")
        }
        return "${osPrefix}_$archSuffix"
    }
}
