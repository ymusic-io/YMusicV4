package kmphttp

import kmpcommon.io.AsyncSource
import kmphttp.internal.runIoInterruptible
import okio.Buffer
import okio.ByteString

actual typealias ResponseBody = okhttp3.ResponseBody

actual fun ResponseBody.asyncSource(): AsyncSource {
    val source = this.source()
    return object : AsyncSource {
        override suspend fun read(sink: Buffer, byteCount: Long): Long {
            return runIoInterruptible {
                readingResponse { source.read(sink, byteCount) }
            }
        }

        override fun close() {
            source.close()
        }
    }
}

actual suspend fun ResponseBody.byteString(): ByteString {
    return runIoInterruptible {
        readingResponse { this.byteString() }
    }
}

actual suspend fun ResponseBody.utf8String(): String {
    return runIoInterruptible {
        readingResponse { this.string() }
    }
}
