package io.ymusic.ui

import java.text.Normalizer

internal actual fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
