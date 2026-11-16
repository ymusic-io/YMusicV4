package io.ymusic.desktop

import io.ymusic.settings.StringBackedSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The desktop [io.ymusic.settings.SettingsStore], one [AppStore] row per key. */
internal class AppStoreSettings private constructor(private val store: AppStore) : StringBackedSettingsStore() {
    override suspend fun persist(key: String, value: String) {
        withContext(Dispatchers.IO) { store.putSetting(key, value) }
    }

    override suspend fun erase(key: String) {
        withContext(Dispatchers.IO) { store.removeSetting(key) }
    }

    companion object {
        suspend fun open(store: AppStore): AppStoreSettings = withContext(Dispatchers.IO) {
            AppStoreSettings(store).apply { seed(store.settings()) }
        }
    }
}
