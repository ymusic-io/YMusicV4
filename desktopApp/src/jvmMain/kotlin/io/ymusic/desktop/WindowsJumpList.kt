package io.ymusic.desktop

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import kmpcommon.WorkerThread
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.IOException
import java.lang.ref.Reference
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The taskbar button's Jump List "Tasks", over the shell's COM interfaces via JNA. Each task
 * relaunches [exe] with an [InstanceCommand] argument, which [SingleInstance] hands to the running app.
 */
internal object WindowsJumpList {

    class Task(val title: String, val command: InstanceCommand)

    /** COM wants one apartment thread; every call here runs on it. */
    val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread({
            ole32.getFunction("CoInitializeEx").invokeInt(arrayOf(null, COINIT_APARTMENTTHREADED))
            r.run()
        }, "jump-list").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val ole32 = NativeLibrary.getInstance("ole32")

    @WorkerThread
    fun set(exe: String, tasks: List<Task>) {
        val list = create(CLSID_DESTINATION_LIST, IID_CUSTOM_DESTINATION_LIST)
        try {
            val removed = PointerByReference()
            list.call(4, Memory(4), guid(IID_OBJECT_ARRAY), removed).check("BeginList")
            removed.value.release()
            val collection = create(CLSID_ENUMERABLE_OBJECT_COLLECTION, IID_OBJECT_COLLECTION)
            try {
                for (task in tasks) {
                    val link = shellLink(exe, task)
                    try {
                        collection.call(5, link).check("AddObject")
                    } finally {
                        link.release()
                    }
                }
                list.call(7, collection).check("AddUserTasks")
            } finally {
                collection.release()
            }
            list.call(8).check("CommitList")
        } finally {
            list.release()
        }
    }

    @WorkerThread
    fun clear() {
        val list = create(CLSID_DESTINATION_LIST, IID_CUSTOM_DESTINATION_LIST)
        try {
            list.call(10, null).check("DeleteList")
        } finally {
            list.release()
        }
    }

    private fun shellLink(exe: String, task: Task): Pointer {
        val link = create(CLSID_SHELL_LINK, IID_SHELL_LINK_W)
        try {
            link.call(20, WString(exe)).check("SetPath")
            link.call(11, WString(task.command.arg.orEmpty())).check("SetArguments")
            link.call(17, WString(exe), 0).check("SetIconLocation")
            val store = PointerByReference()
            link.call(0, guid(IID_PROPERTY_STORE), store).check("QueryInterface(IPropertyStore)")
            try {
                val title = Memory((task.title.length + 1) * 2L).apply { setWideString(0, task.title) }
                store.value.call(6, titleKey(), lpwstrVariant(title)).check("SetValue(Title)")
                Reference.reachabilityFence(title)
                store.value.call(7).check("Commit")
            } finally {
                store.value.release()
            }
            return link
        } catch (e: Throwable) {
            link.release()
            throw e
        }
    }

    private fun create(clsid: String, iid: String): Pointer {
        val out = PointerByReference()
        ole32.getFunction("CoCreateInstance")
            .invokeInt(arrayOf(guid(clsid), null, CLSCTX_INPROC_SERVER, guid(iid), out))
            .check("CoCreateInstance($clsid)")
        return out.value
    }

    private fun Pointer.call(index: Int, vararg args: Any?): Int {
        val fn = Function.getFunction(getPointer(0).getPointer(index.toLong() * Native.POINTER_SIZE))
        return fn.invokeInt(arrayOf(this, *args))
    }

    private fun Pointer.release() {
        call(2)
    }

    private fun Int.check(what: String) {
        if (this < 0) throw IOException("$what failed: HRESULT 0x%08x".format(this))
    }

    private fun guid(text: String): Memory {
        val u = UUID.fromString(text)
        val hi = u.mostSignificantBits
        return Memory(16).apply {
            setInt(0, (hi ushr 32).toInt())
            setShort(4, (hi ushr 16).toShort())
            setShort(6, hi.toShort())
            for (i in 0 until 8) setByte(8L + i, (u.leastSignificantBits ushr (56 - 8 * i)).toByte())
        }
    }

    /** PKEY_Title: the task's label. */
    private fun titleKey(): Memory = Memory(20).apply {
        write(0, guid(PKEY_TITLE_FMTID).getByteArray(0, 16), 0, 16)
        setInt(16, 2)
    }

    /** A VT_LPWSTR PROPVARIANT (24 bytes on x64: vt, 3 reserved WORDs, then the union). */
    private fun lpwstrVariant(chars: Memory): Memory = Memory(24).apply {
        clear()
        setShort(0, VT_LPWSTR)
        setPointer(8, chars)
    }

    private const val COINIT_APARTMENTTHREADED = 0x2
    private const val CLSCTX_INPROC_SERVER = 0x1
    private const val VT_LPWSTR: Short = 31
    private const val CLSID_DESTINATION_LIST = "77f10cf0-3db5-4966-b520-b7c54fd35ed6"
    private const val IID_CUSTOM_DESTINATION_LIST = "6332debf-87b5-4670-90c0-5e57b408a49e"
    private const val CLSID_ENUMERABLE_OBJECT_COLLECTION = "2d3468c1-36a7-43b6-ac24-d3f02fd9607a"
    private const val IID_OBJECT_COLLECTION = "5632b1a4-e38a-400a-928a-d4cd63230295"
    private const val IID_OBJECT_ARRAY = "92ca9dcd-5622-4bba-a805-5e9f541bd8c9"
    private const val CLSID_SHELL_LINK = "00021401-0000-0000-c000-000000000046"
    private const val IID_SHELL_LINK_W = "000214f9-0000-0000-c000-000000000046"
    private const val IID_PROPERTY_STORE = "886d8eeb-8cf2-4446-8d02-cdba1dbdcf99"
    private const val PKEY_TITLE_FMTID = "f29f85e0-4ff9-1068-ab91-08002b27b3d9"
}
