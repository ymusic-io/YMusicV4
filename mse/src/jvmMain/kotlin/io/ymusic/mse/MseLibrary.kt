package io.ymusic.mse

/**
 * Whether the JNI library loaded. A failed load leaves [MseHostNative] and [FileAvNative] unusable for the
 * life of the process, and anything touching them throws, so the app asks here first.
 */
object MseLibrary {
    val loadFailure: LinkageError? by lazy {
        try {
            MseHostNative.vtable()
            null
        } catch (error: LinkageError) {
            error
        }
    }
}
