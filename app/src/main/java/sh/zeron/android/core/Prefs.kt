package sh.zeron.android.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.zeron.android.design.Appearance
import sh.zeron.android.transcript.MathSettings

/**
 * The keys of the phone-local `zeron_prefs` store (ticket 40). The names
 * are the iOS `UserDefaults` keys, so a user switching platforms finds the
 * same semantics: `"groupByProject"` is the Sessions-tab grouping flag
 * (`ios:App/AppModel.swift:18-31`), `"collapsedSections"` the phone-local
 * folds of Pinned/Recent/groups (`ios:Threads/SessionListController.swift:34-37`),
 * and `"sectionFoldOverrides"` the offline overrides of engine-synced user
 * sections (Zeron's addition). Tickets 96 and 100 extend this object
 * rather than adding a second file.
 */
object PrefKeys {
    /**
     * `UserDefaults` "appearance" — 0 = System, 1 = Light, 2 = Dark
     * (`SceneDelegate.swift:12-16`). Phone-local, never sent to an engine.
     */
    val APPEARANCE = intPreferencesKey("appearance")

    /**
     * `AppModel.groupByProject` — phone-local, never sent to an engine.
     * Superseded by [SESSION_GROUPING] (#131): read once to migrate, then removed.
     */
    val GROUP_BY_PROJECT = booleanPreferencesKey("groupByProject")

    /** The Sessions-tab grouping mode (#131): a [SessionGrouping.stored] key; absent = migrate or None. */
    val SESSION_GROUPING = stringPreferencesKey("sessionGrouping")

    /** Phone-local fold set: Pinned, Recent and `project:*`/`home:*` groups. */
    val COLLAPSED_SECTIONS = stringSetPreferencesKey("collapsedSections")

    /** Obsolete synced-fold overrides; [sh.zeron.android.sessions.FoldState] removes the key on load. */
    val SECTION_FOLD_OVERRIDES = stringSetPreferencesKey("sectionFoldOverrides")

    /** iOS `MathSettings.render` (`mathRender`): absent = on. */
    val MATH_RENDER = booleanPreferencesKey("mathRender")

    /** iOS `MathSettings.face` (`mathFont`): the face's key; absent or unknown = Fira Math. */
    val MATH_FONT = stringPreferencesKey("mathFont")

    /** iOS `MathSettings.size` (`mathSize`): percent of the surrounding text; absent = 100. */
    val MATH_SIZE = intPreferencesKey("mathSize")
}

/**
 * How the Sessions tab groups Recent (#131): not at all, by project
 * (iOS's Group by Project, #21) or by device (Zeron's addition, modelled
 * on it). The two groupings are mutually exclusive, so one mode replaces
 * iOS's boolean.
 */
enum class SessionGrouping(val stored: String) {
    None("none"),
    Project("project"),
    Device("device"),
    ;

    companion object {
        /** The stored key's mode; null for an absent or unknown key (then the old flag decides). */
        fun fromStored(key: String?): SessionGrouping? = entries.firstOrNull { it.stored == key }

        /** The pre-#131 `groupByProject` flag's mode. */
        fun fromLegacy(groupByProject: Boolean?): SessionGrouping = if (groupByProject == true) Project else None
    }
}

/**
 * The one `zeron_prefs` [DataStore] per process (a second instance on the
 * same file throws). Every phone-local preference of the parity set reads
 * and writes through this single delegate.
 */
val Context.zeronPrefsStore: DataStore<Preferences> by preferencesDataStore(
    name = "zeron_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The phone-local preferences (ticket 40) — the `AppModel.groupByProject`
 * property (`ios:App/AppModel.swift:16-33`) over DataStore instead of
 * `UserDefaults`:
 *
 *  - the absent key reads `false` (iOS `bool(forKey:)`'s default);
 *  - `setGroupByProject` is a no-op on an equal value (the iOS
 *    `guard newValue != groupByProject`), and a real change notifies every
 *    observer at once — here one [StateFlow] whose collectors rebuild the
 *    Sessions list;
 *  - the StateFlow flips immediately and persists in the background, so
 *    the list never waits on the disk;
 *  - DataStore reads asynchronously, so the first read is awaited via
 *    [awaitLoaded] instead of iOS's synchronous lookup.
 *
 * Ticket 96 adds the appearance preference — the `UserDefaults` integer
 * `"appearance"` of `SceneDelegate.swift:12-16` — on the same store: the
 * absent key reads `Appearance.System` (iOS `integer(forKey:)` returns 0),
 * an unknown stored value normalises to `System` (`?? .unspecified`), and
 * [setAppearance] mirrors `setGroupByProject`'s no-op/flip/persist shape.
 *
 * Ticket 131 replaces the flag with a [SessionGrouping] mode under
 * `"sessionGrouping"`: the first load migrates a stored `"groupByProject"`
 * (true = Project, else None) and removes the old key. [groupByProject]
 * stays as the Project half of the mode, so the Settings switch keeps
 * working: on picks Project, off picks None.
 */
class Prefs(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _grouping = MutableStateFlow(SessionGrouping.None)

    /** The Sessions-tab grouping mode (#131) — None until the disk read lands. */
    val grouping: StateFlow<SessionGrouping> = _grouping.asStateFlow()

    private val _group = MutableStateFlow(false)

    /** Whether [grouping] is Project — the Settings switch and the iOS flag's reading. */
    val groupByProject: StateFlow<Boolean> = _group.asStateFlow()

    private val _appearance = MutableStateFlow(Appearance.System)

    /** The user's appearance choice — `System` until the disk read lands. */
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    private val _math = MutableStateFlow(MathSettings.Values())

    /**
     * How transcripts show math (#36, iOS `MathSettings`) — the defaults
     * until the disk read lands. The app publishes every value to
     * [MathSettings.update], so open transcripts reflow at once.
     */
    val math: StateFlow<MathSettings.Values> = _math.asStateFlow()

    /** Same rule as [setAppearance]: a change before the load wins. */
    @Volatile
    private var mathTouched = false

    private val loaded = CompletableDeferred<Unit>()

    /** Set by the first [setGrouping]: the user's choice beats a later-arriving first read. */
    private val written = AtomicBoolean(false)

    /** Same rule for [setAppearance] (ticket 96): a tap before the load wins. */
    @Volatile
    private var appearanceTouched = false

    init {
        scope.launch(io) {
            val preferences = store.data.catch { emit(emptyPreferences()) }.first()
            val stored = SessionGrouping.fromStored(preferences[PrefKeys.SESSION_GROUPING])
            if (!written.get()) {
                publish(stored ?: SessionGrouping.fromLegacy(preferences[PrefKeys.GROUP_BY_PROJECT]))
            }
            // Only when untouched: a setAppearance before the read finishes
            // wins over whatever the disk holds.
            if (!appearanceTouched) {
                _appearance.value = Appearance.fromStored(preferences[PrefKeys.APPEARANCE] ?: 0)
            }
            if (!mathTouched) {
                _math.value = MathSettings.Values(
                    render = preferences[PrefKeys.MATH_RENDER] ?: true,
                    face = MathSettings.faceOf(preferences[PrefKeys.MATH_FONT]),
                    sizePercent = preferences[PrefKeys.MATH_SIZE] ?: 100,
                )
            }
            loaded.complete(Unit)
            if (preferences.contains(PrefKeys.GROUP_BY_PROJECT)) {
                // #131: the old flag becomes the mode once, then goes away
                // (after the load, so a failed write never blocks the list).
                store.edit {
                    if (it[PrefKeys.SESSION_GROUPING] == null) it[PrefKeys.SESSION_GROUPING] = _grouping.value.stored
                    it.remove(PrefKeys.GROUP_BY_PROJECT)
                }
            }
        }
    }

    /** Suspends until the stored value has been read once. */
    suspend fun awaitLoaded() {
        loaded.await()
    }

    /**
     * Pick the grouping mode (#131): no-op when unchanged; otherwise the
     * StateFlows are set immediately and the write lands in the background.
     * The edit writes the **current** `_grouping.value`, so out-of-order
     * writes are harmless (the last value wins whatever the interleaving).
     */
    fun setGrouping(mode: SessionGrouping) {
        if (mode == _grouping.value) return
        written.set(true)
        publish(mode)
        scope.launch(io) {
            store.edit {
                it[PrefKeys.SESSION_GROUPING] = _grouping.value.stored
                it.remove(PrefKeys.GROUP_BY_PROJECT)
            }
        }
    }

    /**
     * The ⋯ menu's Group by Project / Group by Device: picking a mode turns
     * the other off, and picking the current one turns grouping off.
     */
    fun toggleGrouping(mode: SessionGrouping) =
        setGrouping(if (_grouping.value == mode) SessionGrouping.None else mode)

    /**
     * The Settings switch (iOS `AppModel.groupByProject`): on picks Project,
     * off picks None; no-op when the switch already reads [on], so turning
     * it off while grouping by device changes nothing.
     */
    fun setGroupByProject(on: Boolean) {
        if (on == _group.value) return
        setGrouping(if (on) SessionGrouping.Project else SessionGrouping.None)
    }

    fun toggleGroupByProject() = setGroupByProject(!_group.value)

    private fun publish(mode: SessionGrouping) {
        _grouping.value = mode
        _group.value = mode == SessionGrouping.Project
    }

    /**
     * Pick the appearance (ticket 96): no-op when unchanged (iOS rewrites
     * the same `UserDefaults` value, which also changes nothing); otherwise
     * the StateFlow is set immediately — the whole app re-themes on the
     * next frame — and the write lands in the background. As in
     * [setGroupByProject], the edit writes the **current**
     * `_appearance.value`, so out-of-order writes are harmless.
     */
    fun setAppearance(a: Appearance) {
        if (a == _appearance.value) return
        appearanceTouched = true
        _appearance.value = a
        scope.launch(io) {
            store.edit { it[PrefKeys.APPEARANCE] = _appearance.value.storedValue }
        }
    }

    /**
     * Set the math settings (#36), the [setGroupByProject] shape: no-op when
     * unchanged, the StateFlow at once, all three keys written in the
     * background from the current value.
     */
    fun setMath(values: MathSettings.Values) {
        if (values == _math.value) return
        mathTouched = true
        _math.value = values
        scope.launch(io) {
            store.edit {
                val v = _math.value
                it[PrefKeys.MATH_RENDER] = v.render
                it[PrefKeys.MATH_FONT] = MathSettings.key(v.face)
                it[PrefKeys.MATH_SIZE] = v.sizePercent
            }
        }
    }
}
