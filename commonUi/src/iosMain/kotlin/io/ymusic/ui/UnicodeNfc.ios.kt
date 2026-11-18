package io.ymusic.ui

import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCanonicalMapping

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun nfc(text: String): String = (text as NSString).precomposedStringWithCanonicalMapping
