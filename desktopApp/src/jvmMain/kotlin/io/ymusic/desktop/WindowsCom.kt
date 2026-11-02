package io.ymusic.desktop

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.IOException
import java.util.UUID

/** The bits of COM the shell integrations ([WindowsJumpList], [WindowsFolderPicker]) call through JNA. */
internal object WindowsCom {
    private val ole32 by lazy { NativeLibrary.getInstance("ole32") }

    /** Joins the calling thread to a single-threaded apartment, as the shell's objects need; returns the HRESULT. */
    fun initApartment(): Int =
        ole32.getFunction("CoInitializeEx").invokeInt(arrayOf(null, COINIT_APARTMENTTHREADED or COINIT_DISABLE_OLE1DDE))

    fun uninitialize() {
        ole32.getFunction("CoUninitialize").invokeVoid(arrayOf())
    }

    fun create(clsid: String, iid: String): Pointer {
        val out = PointerByReference()
        ole32.getFunction("CoCreateInstance")
            .invokeInt(arrayOf(guid(clsid), null, CLSCTX_INPROC_SERVER, guid(iid), out))
            .checkHresult("CoCreateInstance($clsid)")
        return out.value
    }

    fun taskMemFree(memory: Pointer) {
        ole32.getFunction("CoTaskMemFree").invokeVoid(arrayOf(memory))
    }

    /** A GUID laid out as Windows expects: Data1-3 little-endian, Data4 as bytes. */
    fun guid(text: String): Memory {
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

    private const val COINIT_APARTMENTTHREADED = 0x2
    private const val COINIT_DISABLE_OLE1DDE = 0x4
    private const val CLSCTX_INPROC_SERVER = 0x1
}

/** Calls vtable slot [index] of this COM object, `this` first; returns the HRESULT. */
internal fun Pointer.comCall(index: Int, vararg args: Any?): Int {
    val slot = getPointer(0).getPointer(index.toLong() * Native.POINTER_SIZE)
    return Function.getFunction(slot, Function.ALT_CONVENTION).invokeInt(arrayOf(this, *args))
}

/** `IUnknown::Release`. */
internal fun Pointer.comRelease() {
    comCall(2)
}

internal fun Int.checkHresult(what: String) {
    if (this < 0) throw IOException("$what failed: HRESULT 0x%08X".format(this))
}
