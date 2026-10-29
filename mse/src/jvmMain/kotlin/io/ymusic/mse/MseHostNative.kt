package io.ymusic.mse

import androidx.annotation.Keep
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

interface MseHostSink {
    @Keep fun onPcm(rate: Int, channels: Int, s16: ByteArray)

    @Keep fun onProgress(positionSec: Double, durationSec: Double, width: Int, height: Int)
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

    /** Video goes to [presenter] (a `VideoPresenterNative` handle, 0 = none), bound with the host as the token. */
    @JvmStatic
    external fun bindPresenter(handle: Long, presenter: Long)

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

    private fun loadLibrary() {
        if (!loaded.compareAndSet(false, true)) return
        try {
            System.loadLibrary("jawt")
        } catch (e: UnsatisfiedLinkError) {
            MseLogBridge.fire(LOG_WARN, "mse: jawt did not load, no native video surface: $e")
        }
        val name = "mseffmpegjni"
        val libFileName = System.mapLibraryName(name)
        System.getProperty("mse.native.dir")?.let { dir ->
            loadWithDeps(File(dir), libFileName)
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
        sweepStaleTempCopies()
        val tmpDir = Files.createTempDirectory(TEMP_PREFIX).toFile().also { it.deleteOnExit() }
        for (n in names) {
            val stream = cl.getResourceAsStream("$folder/$n")
                ?: throw UnsatisfiedLinkError("No bundled $name at $folder/$n")
            stream.use { inp ->
                val dest = File(tmpDir, n).also { it.deleteOnExit() }
                Files.copy(inp, dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        loadWithDeps(tmpDir, libFileName)
    }

    @Suppress("UnsafeDynamicallyLoadedCode")
    private fun loadWithDeps(dir: File, libFileName: String) {
        val depErrors = preloadWindowsDeps(dir)
        try {
            System.load(File(dir, libFileName).absolutePath)
        } catch (error: UnsatisfiedLinkError) {
            depErrors.forEach(error::addSuppressed)
            throw error
        }
    }

    /** System.load is a plain LoadLibrary on Windows, which never looks next to a DLL for its imports:
     *  load each dependency by full path first so those imports bind to the module already loaded. */
    @Suppress("UnsafeDynamicallyLoadedCode")
    private fun preloadWindowsDeps(dir: File): List<UnsatisfiedLinkError> {
        if (!isWindows()) return emptyList()
        val deps = dir.listFiles().orEmpty()
            .mapNotNull { f -> windowsDepRank(f.name)?.let { it to f } }
            .sortedBy { it.first }
        val errors = ArrayList<UnsatisfiedLinkError>()
        for ((_, f) in deps) {
            try {
                System.load(f.absolutePath)
            } catch (e: UnsatisfiedLinkError) {
                MseLogBridge.fire(LOG_WARN, "mse: preloading ${f.name} failed: $e")
                errors += e
            }
        }
        return errors
    }

    /** Windows cannot delete a loaded DLL, so each launch's deleteOnExit copy stays behind. Best effort:
     *  a copy a running instance still holds keeps its locked DLLs, and the age spares one being unpacked. */
    private fun sweepStaleTempCopies() {
        if (!isWindows()) return
        val cutoff = System.currentTimeMillis() - STALE_TEMP_MS
        File(System.getProperty("java.io.tmpdir")).listFiles { f ->
            f.isDirectory && f.name.startsWith(TEMP_PREFIX) && f.lastModified() < cutoff
        }?.forEach { it.deleteRecursively() }
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase(Locale.US).contains("win")

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

private const val LOG_WARN = 2

private const val TEMP_PREFIX = "mseffmpegjni"
private const val STALE_TEMP_MS = 60 * 60 * 1000L

/** OS runtime first, then libav bottom-up; any soversion, with or without MinGW's `lib` prefix. */
private val WINDOWS_DEPS = listOf("zlib", "winpthread", "avutil", "swresample", "swscale", "avcodec", "avformat", "avfilter", "avdevice")
private val WINDOWS_DEP_NAME = Regex("""^(?:lib)?([a-z]+)-?\d*\.dll$""", RegexOption.IGNORE_CASE)

private fun windowsDepRank(fileName: String): Int? {
    val base = WINDOWS_DEP_NAME.matchEntire(fileName)?.groupValues?.get(1)?.lowercase(Locale.US) ?: return null
    return WINDOWS_DEPS.indexOf(base).takeIf { it >= 0 }
}
