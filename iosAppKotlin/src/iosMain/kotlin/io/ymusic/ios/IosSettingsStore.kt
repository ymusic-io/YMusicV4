package io.ymusic.ios

import io.ymusic.settings.StringBackedSettingsStore
import platform.Foundation.NSUserDefaults

/** [io.ymusic.settings.SettingsStore] persisted in NSUserDefaults. One per process: [settingsStore]. */
internal class IosSettingsStore private constructor(
    private val defaults: NSUserDefaults,
) : StringBackedSettingsStore() {
    init {
        // Every string the store ever wrote; the domain also carries system entries, which nothing reads.
        seed(
            defaults.dictionaryRepresentation().entries.mapNotNull { (key, value) ->
                (key as? String)?.let { k -> (value as? String)?.let { k to it } }
            }.toMap(),
        )
    }

    override suspend fun persist(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    companion object {
        val settingsStore: IosSettingsStore by lazy { IosSettingsStore(NSUserDefaults.standardUserDefaults) }
    }
}
