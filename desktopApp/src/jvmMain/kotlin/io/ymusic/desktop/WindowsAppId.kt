package io.ymusic.desktop

import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.WString
import kmpcommon.log.Logger

/**
 * The app's AppUserModelID. The installer's shortcuts carry the same one (installer/windows/ymusic.iss):
 * Windows resolves the app's name and icon (taskbar, media flyout) through the shortcut with this ID.
 */
internal object WindowsAppId {
    const val ID = "io.ymusic.windows"

    /** Must run before any window or jump list exists: they take the process's ID from then on. */
    fun claim(log: Logger) {
        try {
            val hr = NativeLibrary.getInstance("shell32")
                .getFunction("SetCurrentProcessExplicitAppUserModelID", Function.ALT_CONVENTION)
                .invokeInt(arrayOf(WString(ID)))
            if (hr < 0) log.log(Logger.Level.WARN, "SetCurrentProcessExplicitAppUserModelID failed: HRESULT 0x%08X".format(hr))
        } catch (e: Throwable) {
            log.log(Logger.Level.WARN, "SetCurrentProcessExplicitAppUserModelID unavailable", e)
        }
    }
}
