package io.ymusic.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/** Starts the first of [intents] this device can open; false when none can (car head units, stripped ROMs). */
internal fun Context.startFirstActivity(vararg intents: Intent): Boolean = intents.any { intent ->
    try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
