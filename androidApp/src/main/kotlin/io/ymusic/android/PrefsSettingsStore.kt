package io.ymusic.android

import android.content.Context
import android.content.SharedPreferences
import io.ymusic.settings.StringBackedSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [io.ymusic.settings.SettingsStore] persisted in a SharedPreferences file. One per process: see [open]. */
class PrefsSettingsStore private constructor(private val prefs: SharedPreferences) : StringBackedSettingsStore() {
    init {
        seed(prefs.all.mapValues { (_, v) -> v.toString() })
    }

    override suspend fun persist(key: String, value: String) {
        withContext(Dispatchers.IO) { prefs.edit().putString(key, value).commit() }
    }

    companion object {
        @Volatile private var instance: PrefsSettingsStore? = null

        /** The process's store: a second one over the same file would hold its own stale snapshot. */
        fun open(context: Context): PrefsSettingsStore = instance ?: synchronized(this) {
            instance ?: PrefsSettingsStore(
                context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}
