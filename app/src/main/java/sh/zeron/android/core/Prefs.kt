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

    /** `AppModel.groupByProject` — phone-local, never sent to an engine. */
    val GROUP_BY_PROJECT = booleanPreferencesKey("groupByProject")

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
 */
class Prefs(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _group = MutableStateFlow(false)
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

    /** Set by the first [setGroupByProject]: the user's choice beats a later-arriving first read. */
    private val written = AtomicBoolean(false)

    /** Same rule for [setAppearance] (ticket 96): a tap before the load wins. */
    @Volatile
    private var appearanceTouched = false

    init {
        scope.launch(io) {
            val preferences = store.data.catch { emit(emptyPreferences()) }.first()
            if (!written.get()) _group.value = preferences[PrefKeys.GROUP_BY_PROJECT] ?: false
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
        }
    }

    /** Suspends until the stored value has been read once. */
    suspend fun awaitLoaded() {
        loaded.await()
    }

    /**
     * Flip the grouping flag: no-op when unchanged; otherwise the StateFlow
     * is set immediately and the write lands in the background. The edit
     * writes the **current** `_group.value`, so out-of-order writes are
     * harmless (the last value wins whatever the interleaving).
     */
    fun setGroupByProject(on: Boolean) {
        if (on == _group.value) return
        written.set(true)
        _group.value = on
        scope.launch(io) {
            store.edit { it[PrefKeys.GROUP_BY_PROJECT] = _group.value }
        }
    }

    fun toggleGroupByProject() = setGroupByProject(!_group.value)

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
