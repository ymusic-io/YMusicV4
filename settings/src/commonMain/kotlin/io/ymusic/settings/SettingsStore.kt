package io.ymusic.settings

import kotlinx.coroutines.flow.StateFlow

/** A tiny typed key-value store. Reads are non-suspending StateFlow views; writes suspend. */
interface SettingsStore {
    fun string(key: String, default: String): StateFlow<String>

    suspend fun putString(key: String, value: String)

    fun boolean(key: String, default: Boolean): StateFlow<Boolean>

    suspend fun putBoolean(key: String, value: Boolean)

    fun int(key: String, default: Int): StateFlow<Int>

    suspend fun putInt(key: String, value: Int)

    /** Deletes [key]; reads fall back to their default. */
    suspend fun remove(key: String)

    /** The store held nothing when it was opened: nothing was ever saved here (a fresh install). */
    val startedEmpty: Boolean
}
