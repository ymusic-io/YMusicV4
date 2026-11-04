package io.ymusic.desktop.media

import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import io.ymusic.desktop.WindowsAppId
import io.ymusic.desktop.WindowsCom
import io.ymusic.desktop.checkHresult
import io.ymusic.desktop.comCall
import io.ymusic.desktop.comQuery
import io.ymusic.desktop.comRelease
import kmpcommon.log.Logger
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Windows backend: SystemMediaTransportControls, the OS media session behind the keyboard media
 * keys, the media flyout (volume overlay / Quick Settings), the lock screen and headset buttons.
 * Pure JNA over the WinRT ABI — no bundled native code.
 *
 * A Win32 app reaches SMTC through `ISystemMediaTransportControlsInterop::GetForWindow`, bound here
 * to a hidden top-level window of our own (the Compose window may be hidden to the tray or not yet
 * realized). One MTA thread owns that window, pumps its messages and makes every SMTC call.
 * Button presses arrive on a WinRT pool thread and only forward to [MediaSessionCommands], which
 * the wiring re-dispatches.
 */
internal class WindowsSmtcSession(
    private val commands: MediaSessionCommands,
    private val log: Logger? = null,
) : DesktopMediaSession {

    private val user32 = NativeLibrary.getInstance("user32")
    private val kernel32 = NativeLibrary.getInstance("kernel32")
    private val combase = NativeLibrary.getInstance("combase")
    private val shell32 = NativeLibrary.getInstance("shell32")
    private val ole32 = NativeLibrary.getInstance("ole32")
    private val shcore = NativeLibrary.getInstance("shcore")

    private val jobs = ConcurrentLinkedQueue<() -> Unit>()
    private val stopped = CompletableFuture<Unit>()

    @Volatile
    private var threadId = 0

    private val buttonHandler = WinRtDelegate(IID_BUTTON_PRESSED_HANDLER, log, ::onButton)
    private val seekHandler = WinRtDelegate(IID_POSITION_CHANGE_HANDLER, log, ::onSeek)

    private val wndProc = WndProc(::onWindowMessage)

    // SMTC thread only.
    private var hInstance: Pointer? = null
    private var windowClass: WString? = null
    private var window: Pointer? = null
    private val owned = ArrayDeque<Pointer>()
    private lateinit var smtc: Pointer
    private lateinit var smtc2: Pointer
    private lateinit var updater: Pointer
    private lateinit var timeline: Pointer
    private var buttonToken: Long? = null
    private var seekToken: Long? = null
    private var uriFactory: Pointer? = null
    private var streamReferences: Pointer? = null
    private var publishedArtwork: String? = null

    init {
        val ready = CompletableFuture<Unit>()
        Thread({ run(ready) }, "smtc").apply { isDaemon = true }.start()
        try {
            ready.get(THREAD_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    override fun update(state: MediaSessionState) = post("publish") { publish(state) }

    override fun release() {
        post("release") { user32.fn("PostQuitMessage").invokeVoid(arrayOf(0)) }
        runCatching { stopped.get(THREAD_TIMEOUT_S, TimeUnit.SECONDS) }
            .onFailure { log?.log(Logger.Level.WARN, "SMTC thread did not stop", it) }
    }

    private fun post(what: String, job: () -> Unit) {
        if (stopped.isDone) return
        jobs += {
            try {
                job()
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "SMTC $what failed", error)
            }
        }
        val posted = user32.fn("PostThreadMessageW").invokeInt(arrayOf(threadId, WM_RUN_JOBS, null, null))
        if (posted == 0 && !stopped.isDone) log?.log(Logger.Level.WARN, "SMTC $what not delivered: PostThreadMessage failed")
    }

    // --- the SMTC thread ---

    private fun run(ready: CompletableFuture<Unit>) {
        threadId = kernel32.fn("GetCurrentThreadId").invokeInt(arrayOf())
        val initHr = combase.fn("RoInitialize").invokeInt(arrayOf(RO_INIT_MULTITHREADED))
        try {
            try {
                initHr.checkHresult("RoInitialize")
                setUp()
            } catch (error: Throwable) {
                ready.completeExceptionally(error)
                return
            }
            ready.complete(Unit)
            pump()
        } finally {
            runCatching { tearDown() }.onFailure { log?.log(Logger.Level.WARN, "SMTC teardown failed", it) }
            if (initHr >= 0) combase.fn("RoUninitialize").invokeVoid(arrayOf())
            stopped.complete(Unit)
        }
    }

    private fun setUp() {
        val module = kernel32.fn("GetModuleHandleW").invokePointer(arrayOf<Any?>(null))
        hInstance = module
        val className = WString("YMusicMediaSession${System.nanoTime()}")
        registerWindowClass(className, module)
        windowClass = className
        val hwnd = user32.fn("CreateWindowExW").invokePointer(
            arrayOf(WS_EX_TOOLWINDOW, className, WString("YMusic"), WS_POPUP, 0, 0, 0, 0, null, null, module, null),
        ) ?: error("CreateWindowExW failed")
        window = hwnd
        setWindowAppId(hwnd, WindowsAppId.ID)
        val interop = activationFactory(CLASS_SMTC, IID_SMTC_INTEROP)
        try {
            smtc = own(interop.callForObject(INTEROP_GET_FOR_WINDOW, "GetForWindow", hwnd, WindowsCom.guid(IID_SMTC)))
        } finally {
            interop.comRelease()
        }
        smtc2 = own(smtc.comQuery(IID_SMTC2))
        updater = own(smtc.callForObject(SMTC_GET_DISPLAY_UPDATER, "get_DisplayUpdater"))
        timeline = own(own(activateInstance(CLASS_TIMELINE_PROPERTIES)).comQuery(IID_TIMELINE_PROPERTIES))
        for (slot in listOf(SMTC_PUT_IS_PLAY_ENABLED, SMTC_PUT_IS_PAUSE_ENABLED, SMTC_PUT_IS_NEXT_ENABLED, SMTC_PUT_IS_PREVIOUS_ENABLED)) {
            smtc.comCall(slot, TRUE).checkHresult("put_Is*Enabled[$slot]")
        }
        val token = LongByReference()
        smtc.comCall(SMTC_ADD_BUTTON_PRESSED, buttonHandler.pointer, token).checkHresult("add_ButtonPressed")
        buttonToken = token.value
        smtc2.comCall(SMTC2_ADD_POSITION_CHANGE_REQUESTED, seekHandler.pointer, token)
            .checkHresult("add_PlaybackPositionChangeRequested")
        seekToken = token.value
    }

    private fun pump() {
        val msg = Memory(MSG_SIZE)
        val getMessage = user32.fn("GetMessageW")
        val translate = user32.fn("TranslateMessage")
        val dispatch = user32.fn("DispatchMessageW")
        while (getMessage.invokeInt(arrayOf(msg, null, 0, 0)) > 0) {
            if (msg.getPointer(0) == null && msg.getInt(Native.POINTER_SIZE.toLong()) == WM_RUN_JOBS) {
                while (true) jobs.poll()?.invoke() ?: break
            } else {
                translate.invokeInt(arrayOf(msg))
                dispatch.invokePointer(arrayOf(msg))
            }
        }
    }

    private fun tearDown() {
        buttonToken?.let { smtc.comCall(SMTC_REMOVE_BUTTON_PRESSED, it) }
        seekToken?.let { smtc2.comCall(SMTC2_REMOVE_POSITION_CHANGE_REQUESTED, it) }
        if (::smtc.isInitialized) smtc.comCall(SMTC_PUT_IS_ENABLED, FALSE)
        while (owned.isNotEmpty()) owned.removeLast().comRelease()
        window?.let {
            setWindowAppId(it, null)
            user32.fn("DestroyWindow").invokeInt(arrayOf(it))
        }
        windowClass?.let { user32.fn("UnregisterClassW").invokeInt(arrayOf(it, hInstance)) }
    }

    /**
     * Clicking the media flyout card activates the window the session was created for — ours is
     * hidden, so the activation is turned into [MediaSessionCommands.raise].
     */
    private fun onWindowMessage(hwnd: Pointer?, msg: Int, wParam: Pointer?, lParam: Pointer?): Pointer? {
        if (msg == WM_ACTIVATE && (Pointer.nativeValue(wParam) and 0xFFFF) != WA_INACTIVE) {
            try {
                commands.raise()
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "SMTC raise failed", error)
            }
        }
        return user32.fn("DefWindowProcW").invokePointer(arrayOf(hwnd, msg, wParam, lParam))
    }

    private fun registerWindowClass(className: WString, hInstance: Pointer?) {
        val p = Native.POINTER_SIZE
        val hInstanceAt = (16 + p + p - 1) / p * p // WNDCLASSEXW: after cbSize, style, lpfnWndProc, two ints
        val size = hInstanceAt + 7 * p
        val name = Memory((className.length + 1) * 2L).apply { setWideString(0, className.toString()) }
        val wc = Memory(size.toLong()).apply {
            clear()
            setInt(0, size)
            setPointer(8, CallbackReference.getFunctionPointer(wndProc))
            setPointer(hInstanceAt.toLong(), hInstance)
            setPointer(hInstanceAt + 5L * p, name) // lpszClassName
        }
        if (user32.fn("RegisterClassExW").invokeInt(arrayOf(wc)) and 0xFFFF == 0) error("RegisterClassExW failed")
    }

    private fun publish(state: MediaSessionState) {
        if (!state.hasItem) {
            updater.comCall(UPDATER_CLEAR_ALL).checkHresult("ClearAll")
            updater.comCall(UPDATER_UPDATE).checkHresult("Update")
            publishedArtwork = null
            smtc.comCall(SMTC_PUT_PLAYBACK_STATUS, STATUS_CLOSED).checkHresult("put_PlaybackStatus")
            smtc.comCall(SMTC_PUT_IS_ENABLED, FALSE).checkHresult("put_IsEnabled")
            return
        }
        smtc.comCall(SMTC_PUT_IS_ENABLED, TRUE).checkHresult("put_IsEnabled")
        smtc.comCall(SMTC_PUT_PLAYBACK_STATUS, if (state.playing) STATUS_PLAYING else STATUS_PAUSED)
            .checkHresult("put_PlaybackStatus")

        updater.comCall(UPDATER_PUT_TYPE, TYPE_MUSIC).checkHresult("put_Type")
        val music = updater.callForObject(UPDATER_GET_MUSIC_PROPERTIES, "get_MusicProperties")
        try {
            withHString(state.title) { music.comCall(MUSIC_PUT_TITLE, it).checkHresult("put_Title") }
            withHString(state.artist) { music.comCall(MUSIC_PUT_ARTIST, it).checkHresult("put_Artist") }
        } finally {
            music.comRelease()
        }
        if (state.artworkUrl != publishedArtwork) {
            publishedArtwork = state.artworkUrl
            // Cosmetic: a thumbnail that cannot be built must not hold back title and state.
            runCatching { publishThumbnail(state.artworkUrl) }
                .onFailure { log?.log(Logger.Level.WARN, "SMTC thumbnail failed: ${state.artworkUrl}", it) }
        }
        updater.comCall(UPDATER_UPDATE).checkHresult("Update")

        val endTicks = (state.durationMs ?: 0) * TICKS_PER_MS
        val positionTicks = state.positionMs.coerceAtLeast(0) * TICKS_PER_MS
        timeline.comCall(TIMELINE_PUT_START_TIME, 0L).checkHresult("put_StartTime")
        timeline.comCall(TIMELINE_PUT_END_TIME, endTicks).checkHresult("put_EndTime")
        timeline.comCall(TIMELINE_PUT_MIN_SEEK_TIME, 0L).checkHresult("put_MinSeekTime")
        timeline.comCall(TIMELINE_PUT_MAX_SEEK_TIME, endTicks).checkHresult("put_MaxSeekTime")
        timeline.comCall(TIMELINE_PUT_POSITION, if (endTicks > 0) positionTicks.coerceAtMost(endTicks) else positionTicks)
            .checkHresult("put_Position")
        smtc2.comCall(SMTC2_UPDATE_TIMELINE_PROPERTIES, timeline).checkHresult("UpdateTimelineProperties")
        smtc2.comCall(SMTC2_PUT_PLAYBACK_RATE, state.speed.toDouble()).checkHresult("put_PlaybackRate")
    }

    /** http(s) goes as a uri Windows fetches itself; `CreateFromUri` takes no `file:`, so a local file goes as a stream. */
    private fun publishThumbnail(url: String?) {
        val reference = when {
            url == null -> null
            url.startsWith("https://") || url.startsWith("http://") -> streamReferenceFromUri(url)
            url.startsWith("file:") -> streamReferenceFromFile(Path.of(URI(url)))
            else -> null
        }
        try {
            updater.comCall(UPDATER_PUT_THUMBNAIL, reference).checkHresult("put_Thumbnail")
        } finally {
            reference?.comRelease()
        }
    }

    private fun streamReferenceStatics(): Pointer = streamReferences
        ?: own(activationFactory(CLASS_STREAM_REFERENCE, IID_STREAM_REFERENCE_STATICS)).also { streamReferences = it }

    private fun streamReferenceFromUri(url: String): Pointer {
        val factory = uriFactory ?: own(activationFactory(CLASS_URI, IID_URI_FACTORY)).also { uriFactory = it }
        val uri = withHString(url) { factory.callForObject(URI_FACTORY_CREATE_URI, "CreateUri", it) }
        try {
            return streamReferenceStatics().callForObject(STREAM_REFERENCE_CREATE_FROM_URI, "CreateFromUri", uri)
        } finally {
            uri.comRelease()
        }
    }

    /** Copied into memory rather than opened: the file is a cache entry that may be evicted while Windows still reads it. */
    private fun streamReferenceFromFile(file: Path): Pointer {
        val out = PointerByReference()
        ole32.fn("CreateStreamOnHGlobal").invokeInt(arrayOf(null, 1, out)).checkHresult("CreateStreamOnHGlobal")
        val stream = out.value
        try {
            val png = encodePng(file)
            stream.comCall(ISTREAM_WRITE, png, png.size, null).checkHresult("IStream.Write")
            stream.comCall(ISTREAM_SEEK, 0L, STREAM_SEEK_SET, null).checkHresult("IStream.Seek")
            shcore.fn("CreateRandomAccessStreamOverStream")
                .invokeInt(arrayOf(stream, BSOS_DEFAULT, WindowsCom.guid(IID_RANDOM_ACCESS_STREAM), out))
                .checkHresult("CreateRandomAccessStreamOverStream")
            val randomAccess = out.value
            try {
                return streamReferenceStatics().callForObject(STREAM_REFERENCE_CREATE_FROM_STREAM, "CreateFromStream", randomAccess)
            } finally {
                randomAccess.comRelease()
            }
        } finally {
            stream.comRelease()
        }
    }

    /**
     * Windows silently drops a thumbnail it cannot decode — WebP, the image cache's format among them —
     * so the art goes over re-encoded as PNG. ByteArray: Skia's codec decodes and encodes bytes only.
     */
    private fun encodePng(file: Path): ByteArray {
        val image = Image.makeFromEncoded(Files.readAllBytes(file))
        try {
            return image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encode failed: $file")
        } finally {
            image.close()
        }
    }

    // --- events, on a WinRT pool thread ---

    private fun onButton(args: Pointer) {
        val button = IntByReference()
        args.comCall(BUTTON_ARGS_GET_BUTTON, button).checkHresult("get_Button")
        when (button.value) {
            BUTTON_PLAY -> commands.play()
            BUTTON_PAUSE -> commands.pause()
            BUTTON_NEXT -> commands.next()
            BUTTON_PREVIOUS -> commands.previous()
        }
    }

    private fun onSeek(args: Pointer) {
        val ticks = LongByReference()
        args.comCall(POSITION_ARGS_GET_REQUESTED_POSITION, ticks).checkHresult("get_RequestedPlaybackPosition")
        commands.seekTo(ticks.value / TICKS_PER_MS)
    }

    // --- WinRT plumbing ---

    /**
     * The window's `System.AppUserModel.ID`, which SMTC names the session by; null clears it before destroy.
     * The media flyout finds the app's name and icon only through a Start Menu shortcut carrying the same ID.
     */
    private fun setWindowAppId(hwnd: Pointer, appId: String?) {
        val out = PointerByReference()
        shell32.fn("SHGetPropertyStoreForWindow").invokeInt(arrayOf(hwnd, WindowsCom.guid(IID_PROPERTY_STORE), out))
            .checkHresult("SHGetPropertyStoreForWindow")
        val store = out.value
        try {
            val key = Memory(20).apply {
                write(0, WindowsCom.guid(FMTID_APP_USER_MODEL).getByteArray(0, 16), 0, 16)
                setInt(16, PID_APP_USER_MODEL_ID)
            }
            val text = appId?.let { Memory((it.length + 1) * 2L).apply { setWideString(0, it) } }
            val value = Memory(PROPVARIANT_SIZE).apply {
                clear()
                if (text != null) {
                    setShort(0, VT_LPWSTR)
                    setPointer(8, text)
                }
            }
            store.comCall(PROPERTY_STORE_SET_VALUE, key, value).checkHresult("IPropertyStore.SetValue(AppUserModel.ID)")
        } finally {
            store.comRelease()
        }
    }

    private fun own(pointer: Pointer): Pointer = pointer.also { owned.addLast(it) }

    private fun activationFactory(classId: String, iid: String): Pointer = withHString(classId) { id ->
        val out = PointerByReference()
        combase.fn("RoGetActivationFactory").invokeInt(arrayOf(id, WindowsCom.guid(iid), out))
            .checkHresult("RoGetActivationFactory($classId)")
        out.value
    }

    private fun activateInstance(classId: String): Pointer = withHString(classId) { id ->
        val out = PointerByReference()
        combase.fn("RoActivateInstance").invokeInt(arrayOf(id, out)).checkHresult("RoActivateInstance($classId)")
        out.value
    }

    /** [block] gets an HSTRING borrowing [value] (null = the empty string), deleted afterwards. */
    private fun <T> withHString(value: String?, block: (Pointer?) -> T): T {
        if (value.isNullOrEmpty()) return block(null)
        val out = PointerByReference()
        combase.fn("WindowsCreateString").invokeInt(arrayOf(WString(value), value.length, out))
            .checkHresult("WindowsCreateString")
        try {
            return block(out.value)
        } finally {
            combase.fn("WindowsDeleteString").invokeInt(arrayOf(out.value))
        }
    }

    /** A vtable call whose trailing `[out, retval]` is an interface pointer; the caller releases it. */
    private fun Pointer.callForObject(slot: Int, what: String, vararg args: Any?): Pointer {
        val out = PointerByReference()
        comCall(slot, *args, out).checkHresult(what)
        return out.value ?: error("$what returned null")
    }

    private fun NativeLibrary.fn(name: String): Function = getFunction(name, Function.ALT_CONVENTION)

    private companion object {
        const val THREAD_TIMEOUT_S = 5L
        const val RO_INIT_MULTITHREADED = 1
        const val WS_POPUP = 0x80000000.toInt()
        const val WS_EX_TOOLWINDOW = 0x80 // never in the taskbar or Alt+Tab
        const val WM_ACTIVATE = 0x0006
        const val WA_INACTIVE = 0L
        const val WM_RUN_JOBS = 0x8000 + 1 // WM_APP + 1
        const val MSG_SIZE = 64L // ≥ sizeof(MSG) on every ABI
        const val TICKS_PER_MS = 10_000L // Windows.Foundation.TimeSpan is 100 ns ticks
        const val TRUE: Byte = 1
        const val FALSE: Byte = 0

        const val IID_PROPERTY_STORE = "886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"
        const val PROPERTY_STORE_SET_VALUE = 6
        const val FMTID_APP_USER_MODEL = "9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3"
        const val PID_APP_USER_MODEL_ID = 5
        const val PROPVARIANT_SIZE = 24L // ≥ sizeof(PROPVARIANT) on every ABI
        const val VT_LPWSTR: Short = 31

        const val CLASS_SMTC = "Windows.Media.SystemMediaTransportControls"
        const val CLASS_TIMELINE_PROPERTIES = "Windows.Media.SystemMediaTransportControlsTimelineProperties"
        const val CLASS_URI = "Windows.Foundation.Uri"
        const val CLASS_STREAM_REFERENCE = "Windows.Storage.Streams.RandomAccessStreamReference"

        const val IID_SMTC_INTEROP = "DDB0472D-C911-4A1F-86D9-DC3D71A95F5A"
        const val IID_SMTC = "99FA3FF4-1742-42A6-902E-087D41F965EC"
        const val IID_SMTC2 = "EA98D2F6-7F3C-4AF2-A586-72889808EFB1"
        const val IID_TIMELINE_PROPERTIES = "5125316A-C3A2-475B-8507-93534DC88F15"
        const val IID_URI_FACTORY = "44A9796F-723E-4FDF-A218-033E75B0C084"
        const val IID_STREAM_REFERENCE_STATICS = "857309DC-3FBF-4E7D-986F-EF3B1A07A964"

        // TypedEventHandler<SystemMediaTransportControls, …EventArgs> — parameterized-interface IIDs.
        const val IID_BUTTON_PRESSED_HANDLER = "0557E996-7B23-5BAE-AA81-EA0D671143A4"
        const val IID_POSITION_CHANGE_HANDLER = "44E34F15-BDC0-50A7-ACE4-39E91FB753F1"

        // vtable slots: IInspectable takes 0–5, so every interface's first method is slot 6.
        const val INTEROP_GET_FOR_WINDOW = 6

        const val SMTC_PUT_PLAYBACK_STATUS = 7
        const val SMTC_GET_DISPLAY_UPDATER = 8
        const val SMTC_PUT_IS_ENABLED = 11
        const val SMTC_PUT_IS_PLAY_ENABLED = 13
        const val SMTC_PUT_IS_PAUSE_ENABLED = 17
        const val SMTC_PUT_IS_PREVIOUS_ENABLED = 25
        const val SMTC_PUT_IS_NEXT_ENABLED = 27
        const val SMTC_ADD_BUTTON_PRESSED = 32
        const val SMTC_REMOVE_BUTTON_PRESSED = 33

        const val SMTC2_PUT_PLAYBACK_RATE = 11
        const val SMTC2_UPDATE_TIMELINE_PROPERTIES = 12
        const val SMTC2_ADD_POSITION_CHANGE_REQUESTED = 13
        const val SMTC2_REMOVE_POSITION_CHANGE_REQUESTED = 14

        const val UPDATER_PUT_TYPE = 7
        const val UPDATER_PUT_THUMBNAIL = 11
        const val UPDATER_GET_MUSIC_PROPERTIES = 12
        const val UPDATER_CLEAR_ALL = 16
        const val UPDATER_UPDATE = 17

        const val MUSIC_PUT_TITLE = 7
        const val MUSIC_PUT_ARTIST = 11

        const val TIMELINE_PUT_START_TIME = 7
        const val TIMELINE_PUT_END_TIME = 9
        const val TIMELINE_PUT_MIN_SEEK_TIME = 11
        const val TIMELINE_PUT_MAX_SEEK_TIME = 13
        const val TIMELINE_PUT_POSITION = 15

        const val BUTTON_ARGS_GET_BUTTON = 6
        const val POSITION_ARGS_GET_REQUESTED_POSITION = 6
        const val URI_FACTORY_CREATE_URI = 6
        const val STREAM_REFERENCE_CREATE_FROM_URI = 7
        const val STREAM_REFERENCE_CREATE_FROM_STREAM = 8
        const val IID_RANDOM_ACCESS_STREAM = "905A0FE1-BC53-11DF-8C49-001E4FC686DA"
        const val BSOS_DEFAULT = 0
        const val ISTREAM_WRITE = 4 // IUnknown, then ISequentialStream Read/Write, then IStream
        const val ISTREAM_SEEK = 5
        const val STREAM_SEEK_SET = 0

        // Windows.Media.MediaPlaybackStatus / MediaPlaybackType / SystemMediaTransportControlsButton
        const val STATUS_CLOSED = 0
        const val STATUS_PLAYING = 3
        const val STATUS_PAUSED = 4
        const val TYPE_MUSIC = 1
        const val BUTTON_PLAY = 0
        const val BUTTON_PAUSE = 1
        const val BUTTON_NEXT = 6
        const val BUTTON_PREVIOUS = 7
    }
}

internal fun interface WndProc : StdCallLibrary.StdCallCallback {
    fun invoke(hwnd: Pointer?, msg: Int, wParam: Pointer?, lParam: Pointer?): Pointer?
}

/**
 * A WinRT delegate (IUnknown + `Invoke(sender, args)`) built from JNA callbacks. It answers to
 * IAgileObject, so SMTC invokes it straight from its own thread with no marshaling. It lives as long
 * as its session, so AddRef/Release are no-ops; SMTC drops its reference at `remove_*`.
 */
internal class WinRtDelegate(iid: String, private val log: Logger?, private val onInvoke: (args: Pointer) -> Unit) {

    internal fun interface QueryInterface : StdCallLibrary.StdCallCallback {
        fun invoke(self: Pointer?, riid: Pointer?, out: Pointer?): Int
    }

    internal fun interface RefCount : StdCallLibrary.StdCallCallback {
        fun invoke(self: Pointer?): Int
    }

    internal fun interface Invoke : StdCallLibrary.StdCallCallback {
        fun invoke(self: Pointer?, sender: Pointer?, args: Pointer?): Int
    }

    private val accepted = listOf(IID_IUNKNOWN, IID_IAGILE_OBJECT, iid).map { WindowsCom.guid(it).getByteArray(0, 16) }

    private val queryInterface = QueryInterface { self, riid, out ->
        val wanted = riid?.getByteArray(0, 16)
        val match = wanted != null && accepted.any { it.contentEquals(wanted) }
        out?.setPointer(0, if (match) self else null)
        if (match) S_OK else E_NOINTERFACE
    }

    private val refCount = RefCount { 1 }

    private val invoke = Invoke { _, _, args ->
        // A media-key press that throws must not vanish — the key "did nothing".
        try {
            if (args != null) onInvoke(args)
        } catch (error: Throwable) {
            log?.log(Logger.Level.WARN, "SMTC event handler failed", error)
        }
        S_OK
    }

    private val vtable = Memory(4L * Native.POINTER_SIZE).apply {
        listOf(queryInterface, refCount, refCount, invoke).forEachIndexed { slot, callback ->
            setPointer(slot.toLong() * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(callback))
        }
    }

    val pointer: Pointer = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0, vtable) }

    private companion object {
        const val S_OK = 0
        const val E_NOINTERFACE = 0x80004002.toInt()
        const val IID_IUNKNOWN = "00000000-0000-0000-C000-000000000046"
        const val IID_IAGILE_OBJECT = "94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90"
    }
}
