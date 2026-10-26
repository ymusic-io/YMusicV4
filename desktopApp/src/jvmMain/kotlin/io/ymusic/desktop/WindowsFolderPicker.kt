package io.ymusic.desktop

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * The Explorer folder picker (`IFileOpenDialog` + `FOS_PICKFOLDERS`): AWT's FileDialog only ever
 * picks files on Windows. Owned by [owner] (an HWND), so it opens centred on the app and blocks it.
 * Null when canceled.
 */
internal object WindowsFolderPicker {
    private const val COINIT_APARTMENTTHREADED = 0x2
    private const val COINIT_DISABLE_OLE1DDE = 0x4
    private const val CLSCTX_INPROC_SERVER = 0x1
    private const val FOS_PICKFOLDERS = 0x20
    private const val FOS_FORCEFILESYSTEM = 0x40
    private const val SIGDN_FILESYSPATH = 0x80058000.toInt()
    private const val HRESULT_CANCELLED = 0x800704C7.toInt()

    // IFileOpenDialog vtable slots (IUnknown, IModalWindow, IFileDialog order).
    private const val RELEASE = 2
    private const val SHOW = 3
    private const val SET_OPTIONS = 9
    private const val GET_OPTIONS = 10
    private const val SET_TITLE = 17
    private const val GET_RESULT = 20

    // IShellItem vtable slot.
    private const val GET_DISPLAY_NAME = 5

    private val CLSID_FILE_OPEN_DIALOG = guid("DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7")
    private val IID_IFILE_OPEN_DIALOG = guid("D57C7288-D4AD-4768-BE02-9D969532D960")
    private val ole32 by lazy { NativeLibrary.getInstance("Ole32") }

    /** Runs on a thread of its own: COM wants a single-threaded apartment, which the EDT can't promise. */
    suspend fun pick(owner: Pointer?, title: String): File? {
        val result = CompletableDeferred<File?>()
        Thread({
            runCatching { pickBlocking(owner, title) }
                .onSuccess { result.complete(it) }
                .onFailure { result.completeExceptionally(it) }
        }, "folder-picker").apply { isDaemon = true }.start()
        return result.await()
    }

    private fun pickBlocking(owner: Pointer?, title: String): File? {
        val init = ole32.getFunction("CoInitializeEx")
            .invokeInt(arrayOf(null, COINIT_APARTMENTTHREADED or COINIT_DISABLE_OLE1DDE))
        check(init >= 0, "CoInitializeEx", init)
        try {
            val dialogRef = PointerByReference()
            check(
                ole32.getFunction("CoCreateInstance").invokeInt(
                    arrayOf(CLSID_FILE_OPEN_DIALOG, null, CLSCTX_INPROC_SERVER, IID_IFILE_OPEN_DIALOG, dialogRef),
                ),
                "CoCreateInstance",
            )
            val dialog = dialogRef.value
            try {
                val options = IntByReference()
                check(call(dialog, GET_OPTIONS, options), "GetOptions")
                check(call(dialog, SET_OPTIONS, options.value or FOS_PICKFOLDERS or FOS_FORCEFILESYSTEM), "SetOptions")
                call(dialog, SET_TITLE, WString(title))
                val shown = call(dialog, SHOW, owner)
                if (shown == HRESULT_CANCELLED) return null
                check(shown, "Show")
                val itemRef = PointerByReference()
                check(call(dialog, GET_RESULT, itemRef), "GetResult")
                val item = itemRef.value
                try {
                    val pathRef = PointerByReference()
                    check(call(item, GET_DISPLAY_NAME, SIGDN_FILESYSPATH, pathRef), "GetDisplayName")
                    val path = pathRef.value
                    try {
                        return File(path.getWideString(0))
                    } finally {
                        ole32.getFunction("CoTaskMemFree").invokeVoid(arrayOf(path))
                    }
                } finally {
                    call(item, RELEASE)
                }
            } finally {
                call(dialog, RELEASE)
            }
        } finally {
            ole32.getFunction("CoUninitialize").invokeVoid(arrayOf())
        }
    }

    /** Calls vtable slot [index] of the COM object [obj], `this` first. */
    private fun call(obj: Pointer, index: Int, vararg args: Any?): Int {
        val slot = obj.getPointer(0).getPointer(index.toLong() * Native.POINTER_SIZE)
        return Function.getFunction(slot, Function.ALT_CONVENTION).invokeInt(arrayOf(obj, *args))
    }

    private fun check(hr: Int, what: String) = check(hr >= 0, what, hr)

    private fun check(ok: Boolean, what: String, hr: Int) {
        if (!ok) throw IOException("$what failed: HRESULT 0x%08X".format(hr))
    }

    /** A GUID laid out as Windows expects: Data1-3 little-endian, Data4 as bytes. */
    private fun guid(text: String): Memory {
        val uuid = UUID.fromString(text)
        val hi = uuid.mostSignificantBits
        val lo = uuid.leastSignificantBits
        return Memory(16).apply {
            setInt(0, (hi ushr 32).toInt())
            setShort(4, (hi ushr 16).toShort())
            setShort(6, hi.toShort())
            for (i in 0 until 8) setByte(8L + i, (lo ushr (56 - 8 * i)).toByte())
        }
    }
}
