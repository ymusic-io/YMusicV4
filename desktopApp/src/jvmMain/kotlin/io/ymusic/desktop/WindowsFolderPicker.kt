package io.ymusic.desktop

import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CompletableDeferred
import java.io.File

/**
 * The Explorer folder picker (`IFileOpenDialog` + `FOS_PICKFOLDERS`): AWT's FileDialog only ever
 * picks files on Windows. Owned by [owner] (an HWND), so it opens centred on the app and blocks it.
 * Null when canceled.
 */
internal object WindowsFolderPicker {
    private const val FOS_PICKFOLDERS = 0x20
    private const val FOS_FORCEFILESYSTEM = 0x40
    private const val SIGDN_FILESYSPATH = 0x80058000.toInt()
    private const val HRESULT_CANCELLED = 0x800704C7.toInt()

    // IFileOpenDialog vtable slots (IUnknown, IModalWindow, IFileDialog order).
    private const val SHOW = 3
    private const val SET_OPTIONS = 9
    private const val GET_OPTIONS = 10
    private const val SET_TITLE = 17
    private const val GET_RESULT = 20

    // IShellItem vtable slot.
    private const val GET_DISPLAY_NAME = 5

    private const val CLSID_FILE_OPEN_DIALOG = "DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7"
    private const val IID_IFILE_OPEN_DIALOG = "D57C7288-D4AD-4768-BE02-9D969532D960"

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
        WindowsCom.initApartment().checkHresult("CoInitializeEx")
        try {
            val dialog = WindowsCom.create(CLSID_FILE_OPEN_DIALOG, IID_IFILE_OPEN_DIALOG)
            try {
                val options = IntByReference()
                dialog.comCall(GET_OPTIONS, options).checkHresult("GetOptions")
                dialog.comCall(SET_OPTIONS, options.value or FOS_PICKFOLDERS or FOS_FORCEFILESYSTEM).checkHresult("SetOptions")
                dialog.comCall(SET_TITLE, WString(title))
                val shown = dialog.comCall(SHOW, owner)
                if (shown == HRESULT_CANCELLED) return null
                shown.checkHresult("Show")
                val itemRef = PointerByReference()
                dialog.comCall(GET_RESULT, itemRef).checkHresult("GetResult")
                val item = itemRef.value
                try {
                    val pathRef = PointerByReference()
                    item.comCall(GET_DISPLAY_NAME, SIGDN_FILESYSPATH, pathRef).checkHresult("GetDisplayName")
                    val path = pathRef.value
                    try {
                        return File(path.getWideString(0))
                    } finally {
                        WindowsCom.taskMemFree(path)
                    }
                } finally {
                    item.comRelease()
                }
            } finally {
                dialog.comRelease()
            }
        } finally {
            WindowsCom.uninitialize()
        }
    }
}
