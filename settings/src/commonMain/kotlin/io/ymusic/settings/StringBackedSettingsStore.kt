package io.ymusic.settings

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All values live in one string map; typed views decode per key, unparsable = default. */
abstract class StringBackedSettingsStore : SettingsStore {
    private val data = MutableStateFlow<Map<String, String>>(emptyMap())
    private val writeMutex = Mutex()

    final override var startedEmpty = true
        private set

    protected fun seed(values: Map<String, String>) {
        data.value = values
        startedEmpty = values.isEmpty()
    }

    override fun string(key: String, default: String): StateFlow<String> = MappedStateFlow(data) { it[key] ?: default }

    override suspend fun putString(key: String, value: String) = put(key, value)

    override fun boolean(key: String, default: Boolean): StateFlow<Boolean> =
        MappedStateFlow(data) { it[key]?.toBooleanStrictOrNull() ?: default }

    override suspend fun putBoolean(key: String, value: Boolean) = put(key, value.toString())

    override fun int(key: String, default: Int): StateFlow<Int> =
        MappedStateFlow(data) { it[key]?.toIntOrNull() ?: default }

    override suspend fun putInt(key: String, value: Int) = put(key, value.toString())

    private suspend fun put(key: String, value: String) {
        data.update { it + (key to value) }
        commit(key)
    }

    override suspend fun remove(key: String) {
        data.update { it - key }
        commit(key)
    }

    /** Writes [key]'s current state under the lock, so whichever put or remove commits last leaves the latest one on disk. */
    private suspend fun commit(key: String) = writeMutex.withLock {
        val current = data.value[key]
        if (current != null) persist(key, current) else erase(key)
    }

    /** Stores the one [key] a write changed, on the writer's coroutine. In-memory stores keep the no-op. */
    protected open suspend fun persist(key: String, value: String) {}

    /** Deletes the one [key] [remove] dropped, on the caller's coroutine. In-memory stores keep the no-op. */
    protected open suspend fun erase(key: String) {}
}

class InMemorySettingsStore : StringBackedSettingsStore()

/** A read-only view of this flow through [transform]; emits only when the transformed value changes. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
fun <S, T> StateFlow<S>.mapState(transform: (S) -> T): StateFlow<T> = MappedStateFlow(this, transform)

internal class MappedStateFlow<S, T>(
    private val source: StateFlow<S>,
    private val transform: (S) -> T,
) : StateFlow<T> {
    override val value: T get() = transform(source.value)

    override val replayCache: List<T> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        // distinctUntilChanged keeps the StateFlow contract: a write to an unrelated key
        // must not re-emit an equal value to this view's collectors.
        source.map(transform).distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}
