package sh.zeron.android.sessions

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An in-memory Preferences store for the fold and front-page tests: no
 * file IO (the file-backed helper stays with the quarantined Prefs tests),
 * and every `edit` is visible at once through [data] and [current].
 */
internal class MemoryPrefsStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    private val lock = Mutex()

    val current: Preferences get() = state.value

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        lock.withLock { transform(state.value).also { state.value = it } }
}
