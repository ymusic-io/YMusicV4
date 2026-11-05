package io.ymusic.mse

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** The JVM's text for error 126. Any other load failure carries Windows' own reason, in the system's language. */
private const val MISSING_IMPORT = "Can't find dependent libraries"

/**
 * The message for [error], the JNI library failing to load. A DLL that failed for a reason of its own (a policy
 * block, a file in use) is the cause, and the "dependent libraries" errors around it only follow from it.
 */
internal fun windowsLoadFailureMessage(
    error: UnsatisfiedLinkError,
    dependencyErrors: List<UnsatisfiedLinkError>,
    dir: File,
): String {
    val ownReasons = (dependencyErrors + error).mapNotNull { it.message }.filterNot { it.endsWith(MISSING_IMPORT) }
    return ownReasons.joinToString("; ").ifEmpty { "${error.message}: ${describeMissingWindowsImports(dir)}" }
}

/**
 * Names the DLLs behind a Windows "Can't find dependent libraries": LoadLibrary reports only error 126,
 * never which import was missing. Checks every DLL in [dir] one level deep against [dir] and System32.
 */
internal fun describeMissingWindowsImports(dir: File): String {
    val dlls = dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".dll", ignoreCase = true) }
    val local = dlls.map { it.name.lowercase(Locale.US) }.toSet()
    val system32 = System.getenv("SystemRoot")?.let { File(it, "System32") }
    val missing = LinkedHashMap<String, MutableList<String>>()
    val unreadable = ArrayList<String>()
    for (dll in dlls) {
        val imports = try {
            peImportNames(dll)
        } catch (error: Exception) {
            // A malformed header (IndexOutOfBounds included) must not mask the load failure being described.
            unreadable += "${dll.name} ($error)"
            continue
        }
        for (name in imports) {
            val key = name.lowercase(Locale.US)
            // API sets are virtual: the loader maps them, no such file exists.
            if (key in local || key.startsWith("api-ms-") || key.startsWith("ext-ms-")) continue
            if (system32 != null && File(system32, name).isFile) continue
            missing.getOrPut(name) { ArrayList() } += dll.name
        }
    }
    val parts = missing.map { (name, importers) -> "$name missing (imported by ${importers.joinToString()})" } +
        unreadable.map { "unreadable $it" }
    return parts.joinToString("; ").ifEmpty { "every direct import resolves" }
}

/** The import table's DLL names (delay-loaded imports excluded: they never fail a load). */
private fun peImportNames(file: File): List<String> = RandomAccessFile(file, "r").use { raf ->
    fun read(offset: Long, size: Int): ByteBuffer {
        val bytes = ByteArray(size)
        raf.seek(offset)
        raf.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }
    val peOffset = read(0x3c, 4).int.toLong()
    val coff = read(peOffset, 24)
    if (coff.getInt(0) != PE_SIGNATURE) throw IOException("not a PE image")
    val sectionCount = coff.getShort(6).toInt() and 0xffff
    val optionalSize = coff.getShort(20).toInt() and 0xffff
    val optionalOffset = peOffset + 24
    val optional = read(optionalOffset, optionalSize)
    val dataDirectories = if (optional.getShort(0).toInt() == PE32_PLUS_MAGIC) 112 else 96
    val importRva = optional.getInt(dataDirectories + 8)
    if (importRva == 0) return@use emptyList()
    val sections = read(optionalOffset + optionalSize, sectionCount * 40)
    fun fileOffset(rva: Int): Long {
        for (i in 0 until sectionCount) {
            val base = i * 40
            val virtualAddress = sections.getInt(base + 12)
            val size = maxOf(sections.getInt(base + 8), sections.getInt(base + 16))
            if (rva - virtualAddress in 0 until size) return sections.getInt(base + 20).toLong() + (rva - virtualAddress)
        }
        throw IOException("RVA $rva outside every section")
    }
    fun cString(offset: Long): String {
        raf.seek(offset)
        return buildString {
            while (true) {
                val byte = raf.read()
                if (byte <= 0) break
                append(byte.toChar())
            }
        }
    }
    val names = ArrayList<String>()
    var descriptor = fileOffset(importRva)
    while (true) {
        val nameRva = read(descriptor + 12, 4).int
        if (nameRva == 0) break
        names += cString(fileOffset(nameRva))
        descriptor += 20
    }
    names
}

private const val PE_SIGNATURE = 0x00004550
private const val PE32_PLUS_MAGIC = 0x20b
