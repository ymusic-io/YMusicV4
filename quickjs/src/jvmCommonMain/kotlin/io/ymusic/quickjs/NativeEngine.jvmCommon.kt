package io.ymusic.quickjs

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/** JS bytes as a direct ByteBuffer over the typed array's memory (valid only during onWrite).
 *  position/get instead of the absolute bulk get, which Android only got in API 34. */
internal actual class NativeChunk(private val bb: ByteBuffer) {
    actual val size: Int = bb.remaining()

    actual fun copyInto(dst: ByteArray, dstOffset: Int, srcOffset: Int, count: Int) {
        bb.position(srcOffset)
        bb.get(dst, dstOffset, count)
    }
}

internal actual class NativeEngine private constructor(private val handle: Long) {
    actual fun evalSync(script: String, filename: String): String? =
        QuickJsNative.evalSync(handle, script, filename)

    actual fun evalEngine(script: String) = QuickJsNative.evalEngine(handle, script)

    actual fun evalEngineBytecode(bytecode: ByteArray) = QuickJsNative.evalEngineBytecode(handle, bytecode)

    actual fun open(id: Int, command: String): Int = QuickJsNative.open(handle, id, command)

    actual fun write(id: Int, buffer: ByteArray, offset: Int, len: Int): Int =
        QuickJsNative.write(handle, id, buffer, offset, len)

    actual fun closeWrite(id: Int, reason: String?) = QuickJsNative.closeWrite(handle, id, reason)

    actual fun closeRead(id: Int) = QuickJsNative.closeRead(handle, id)

    actual fun drain(id: Int) = QuickJsNative.drain(handle, id)

    actual fun setMseHost(mseHandle: Long, vtable: Long) =
        QuickJsNative.setMseHost(handle, mseHandle, vtable)

    actual fun destroy() {
        QuickJsNative.unregister(handle)
        QuickJsNative.close(handle)
    }

    actual companion object {
        actual fun create(appVersion: Int, versionsJson: String?, callbacks: EngineCallbacks): NativeEngine {
            val handle = QuickJsNative.create(appVersion, versionsJson)
            // Registered before any script runs, so opens from the engine script's own top-level
            // code already route.
            QuickJsNative.register(handle, callbacks)
            return NativeEngine(handle)
        }

        actual fun compile(script: String, filename: String): ByteArray =
            QuickJsNative.compile(script, filename)
    }
}

internal actual fun newEngineDispatcher(): CloseableCoroutineDispatcher =
    Executors.newSingleThreadExecutor { r -> Thread(r, "quickjs-engine").apply { isDaemon = true } }
        .asCoroutineDispatcher()
