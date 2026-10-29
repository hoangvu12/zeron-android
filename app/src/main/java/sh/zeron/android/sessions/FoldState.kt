package sh.zeron.android.sessions

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.zeron.android.core.PrefKeys
import sh.zeron.android.design.Feedback
import sh.zeron.android.design.Haptic

/**
 * The fold state: the folded ids — Pinned, Recent, user sections and
 * project groups alike — in one phone-local set under the iOS key
 * `collapsedSections` (`SessionListController.swift:39, 202-207`). Never
 * pruned, so a fold survives its section or group reappearing.
 */
data class Fold(val local: Set<String> = emptySet())

/**
 * The one fold-state owner per process (iOS loads `collapsed` per list
 * controller; the phone and the tablet sidebar share this instance).
 *
 * iOS keeps **every** fold local and never calls the core's
 * `setSectionCollapsed`, so neither does this (docs/port/01 D6): a fold
 * on the phone doesn't fold the section on the desktop.
 *
 * A tap is synchronous — flip, persist, haptic, in `toggleSection`'s
 * order — so the chevron turns in the tap's frame; the store write runs
 * on a serial lane in call order, never on main.
 */
class FoldState(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Serial: every store read and edit, in launch order. */
    private val serial = io.limitedParallelism(1)

    private val _state = MutableStateFlow(Fold())
    val state: StateFlow<Fold> = _state.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch(serial) {
            val preferences = store.data.catch { emit(emptyPreferences()) }.first()
            val stored = preferences[PrefKeys.COLLAPSED_SECTIONS] ?: emptySet()
            // A tap before the load lands is kept: it flipped against an
            // empty set, so apply it on top of the stored one.
            _state.update { fold -> Fold(stored.toMutableSet().apply { flipAll(fold.local) }) }
            if (preferences.contains(PrefKeys.SECTION_FOLD_OVERRIDES)) {
                // Roboco/#10's synced-fold overrides: obsolete now.
                store.edit { it.remove(PrefKeys.SECTION_FOLD_OVERRIDES) }
            }
            loaded.complete(Unit)
        }
    }

    /** Suspends until the stored set has been read once. */
    suspend fun awaitLoaded() {
        loaded.await()
    }

    /** Whether [id] is folded right now. */
    fun isCollapsed(id: String): Boolean = isCollapsedIn(state.value, id)

    /** Whether [id] is folded in [fold] — pure, so the model's rebuild is race-free. */
    fun isCollapsedIn(fold: Fold, id: String): Boolean = id in fold.local

    /** A header tap (`SessionListController.toggleSection`): flip, persist, haptic. */
    fun toggle(id: String, feedback: Feedback) {
        _state.update { fold -> Fold(if (id in fold.local) fold.local - id else fold.local + id) }
        persist()
        feedback.haptic(Haptic.Select)
    }

    /**
     * Opens [id] if folded — a drop into a folded section (iOS
     * `performDropWith` removes it from `collapsed` and persists; the
     * drop's own haptic is the only one).
     */
    fun expand(id: String) {
        if (id !in _state.value.local) return
        _state.update { fold -> Fold(fold.local - id) }
        persist()
    }

    private fun persist() {
        scope.launch(serial) {
            loaded.await()
            store.edit { preferences -> preferences[PrefKeys.COLLAPSED_SECTIONS] = _state.value.local }
        }
    }

    private fun MutableSet<String>.flipAll(ids: Set<String>) {
        for (id in ids) if (!remove(id)) add(id)
    }
}
