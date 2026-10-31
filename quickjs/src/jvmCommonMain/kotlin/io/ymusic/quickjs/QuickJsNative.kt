package io.ymusic.quickjs

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/** Load the `quickjsjni` shared library (from a dir on the JVM, from the APK on Android). */
internal expect fun loadQuickJsLibrary()

/**
 * The JNI boundary (jvm + android) — maps to `quickjs_jni.c`. C invokes the `on*` statics when JS
 * reaches the host; they route to the per-engine [EngineCallbacks] registered in [register].
 * [onWrite]'s ByteBuffer is a direct view over the JS typed array's memory, valid only during the
 * call — the mux consumes it synchronously.
 */
internal object QuickJsNative {
    private val sinks = ConcurrentHashMap<Long, EngineCallbacks>()

    init {
        loadQuickJsLibrary()
    }

    fun register(handle: Long, sink: EngineCallbacks) {
        sinks[handle] = sink
    }

    fun unregister(handle: Long) {
        sinks.remove(handle)
    }

    @JvmStatic
    fun onOpen(handle: Long, id: Int, command: String): Int =
        sinks[handle]?.onOpen(id, command) ?: -1

    @JvmStatic
    fun onWrite(handle: Long, id: Int, bytes: ByteBuffer): Int =
        sinks[handle]?.onWrite(id, NativeChunk(bytes)) ?: -1

    @JvmStatic
    fun onCloseWrite(handle: Long, id: Int, reason: String?) {
        sinks[handle]?.onCloseWrite(id, reason)
    }

    @JvmStatic
    fun onCloseRead(handle: Long, id: Int) {
        sinks[handle]?.onCloseRead(id)
    }

    @JvmStatic
    fun onDrain(handle: Long, id: Int) {
        sinks[handle]?.onDrain(id)
    }

    @JvmStatic
    fun onLog(handle: Long, level: Int, message: String) {
        sinks[handle]?.onLog(level, message)
    }

    external fun create(appVersion: Int, versionsJson: String?): Long
    external fun evalEngine(handle: Long, script: String)
    external fun evalEngineBytecode(handle: Long, bytecode: ByteArray)
    external fun compile(script: String, filename: String): ByteArray
    external fun evalSync(handle: Long, script: String, filename: String): String?
    external fun open(handle: Long, id: Int, command: String): Int
    external fun write(handle: Long, id: Int, bytes: ByteArray, off: Int, len: Int): Int
    external fun closeWrite(handle: Long, id: Int, reason: String?)
    external fun closeRead(handle: Long, id: Int)
    external fun drain(handle: Long, id: Int)
    external fun setMseHost(handle: Long, mseHandle: Long, vtable: Long)
    external fun close(handle: Long)
}
