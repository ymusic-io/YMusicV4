package io.ymusic.desktop

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import kmpcommon.WorkerThread
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.Reference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * The taskbar button's Jump List "Tasks", over the shell's COM interfaces via JNA. Each task
 * relaunches [exe] with an [InstanceCommand] argument, which [SingleInstance] hands to the running app.
 */
internal object WindowsJumpList {

    /** [icon]: an .ico for the task, else the exe's own icon. */
    class Task(val title: String, val command: InstanceCommand, val icon: File? = null)

    /** An .ico of PNG-compressed [images] (Vista+), rewritten only when its bytes change. */
    @WorkerThread
    fun writeIcon(file: File, images: List<BufferedImage>) {
        val pngs = images.map { img -> ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray() }
        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(6 + 16 * pngs.size).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0).putShort(1).putShort(pngs.size.toShort())
        var offset = 6 + 16 * pngs.size
        images.zip(pngs) { img, png ->
            header.put(img.width.coerceAtMost(256).toByte()).put(img.height.coerceAtMost(256).toByte())
            header.put(0).put(0).putShort(1).putShort(32).putInt(png.size).putInt(offset)
            offset += png.size
        }
        out.write(header.array())
        pngs.forEach(out::write)
        val bytes = out.toByteArray()
        file.parentFile.mkdirs()
        if (!file.exists() || !file.readBytes().contentEquals(bytes)) file.writeBytes(bytes)
    }

    /** COM wants one apartment thread; every call here runs on it. */
    val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread({
            WindowsCom.initApartment()
            r.run()
        }, "jump-list").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    @WorkerThread
    fun set(exe: String, tasks: List<Task>) {
        val list = WindowsCom.create(CLSID_DESTINATION_LIST, IID_CUSTOM_DESTINATION_LIST)
        try {
            val removed = PointerByReference()
            list.comCall(4, Memory(4), WindowsCom.guid(IID_OBJECT_ARRAY), removed).checkHresult("BeginList")
            removed.value.comRelease()
            val collection = WindowsCom.create(CLSID_ENUMERABLE_OBJECT_COLLECTION, IID_OBJECT_COLLECTION)
            try {
                for (task in tasks) {
                    val link = shellLink(exe, task)
                    try {
                        collection.comCall(5, link).checkHresult("AddObject")
                    } finally {
                        link.comRelease()
                    }
                }
                list.comCall(7, collection).checkHresult("AddUserTasks")
            } finally {
                collection.comRelease()
            }
            list.comCall(8).checkHresult("CommitList")
        } finally {
            list.comRelease()
        }
    }

    @WorkerThread
    fun clear() {
        val list = WindowsCom.create(CLSID_DESTINATION_LIST, IID_CUSTOM_DESTINATION_LIST)
        try {
            list.comCall(10, null).checkHresult("DeleteList")
        } finally {
            list.comRelease()
        }
    }

    private fun shellLink(exe: String, task: Task): Pointer {
        val link = WindowsCom.create(CLSID_SHELL_LINK, IID_SHELL_LINK_W)
        try {
            link.comCall(20, WString(exe)).checkHresult("SetPath")
            link.comCall(11, WString(task.command.arg.orEmpty())).checkHresult("SetArguments")
            link.comCall(17, WString(task.icon?.absolutePath ?: exe), 0).checkHresult("SetIconLocation")
            val store = PointerByReference()
            link.comCall(0, WindowsCom.guid(IID_PROPERTY_STORE), store).checkHresult("QueryInterface(IPropertyStore)")
            try {
                val title = Memory((task.title.length + 1) * 2L).apply { setWideString(0, task.title) }
                store.value.comCall(6, titleKey(), lpwstrVariant(title)).checkHresult("SetValue(Title)")
                Reference.reachabilityFence(title)
                store.value.comCall(7).checkHresult("Commit")
            } finally {
                store.value.comRelease()
            }
            return link
        } catch (e: Throwable) {
            link.comRelease()
            throw e
        }
    }

    /** PKEY_Title: the task's label. */
    private fun titleKey(): Memory = Memory(20).apply {
        write(0, WindowsCom.guid(PKEY_TITLE_FMTID).getByteArray(0, 16), 0, 16)
        setInt(16, 2)
    }

    /** A VT_LPWSTR PROPVARIANT (24 bytes on x64: vt, 3 reserved WORDs, then the union). */
    private fun lpwstrVariant(chars: Memory): Memory = Memory(24).apply {
        clear()
        setShort(0, VT_LPWSTR)
        setPointer(8, chars)
    }

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
