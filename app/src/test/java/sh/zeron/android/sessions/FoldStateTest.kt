package sh.zeron.android.sessions

import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.zeron.android.core.PrefKeys
import sh.zeron.android.design.Cue
import sh.zeron.android.design.Feedback
import sh.zeron.android.design.Haptic

/**
 * [FoldState] (#21): every fold — Pinned, Recent, user sections, project
 * groups — is one phone-local set under `collapsedSections`, as on iOS
 * (`SessionListController.toggleSection`). Roboco's engine-synced folds,
 * pending writes and offline overrides are gone with their tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FoldStateTest {

    private class RecordingFeedback : Feedback {
        val fired = mutableListOf<Haptic>()
        override fun haptic(haptic: Haptic) {
            fired += haptic
        }
        override fun cue(cue: Cue) {}
    }

    private class Harness(val store: MemoryPrefsStore, val fold: FoldState, val scope: CoroutineScope)

    private fun TestScope.harness(store: MemoryPrefsStore = MemoryPrefsStore()): Harness {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        return Harness(store, FoldState(store, scope, io = StandardTestDispatcher(testScheduler)), scope)
    }

    @Test
    fun aToggleFlipsAtOncePersistsAndFiresOneSelectHaptic() = runTest {
        val h = harness()
        advanceUntilIdle()
        val feedback = RecordingFeedback()
        h.fold.toggle("backend", feedback)
        assertTrue("flips synchronously, before any IO", h.fold.isCollapsed("backend"))
        assertEquals(listOf(Haptic.Select), feedback.fired)
        advanceUntilIdle()
        assertEquals(setOf("backend"), h.store.current[PrefKeys.COLLAPSED_SECTIONS])
        h.fold.toggle("backend", feedback)
        advanceUntilIdle()
        assertFalse(h.fold.isCollapsed("backend"))
        assertEquals(emptySet<String>(), h.store.current[PrefKeys.COLLAPSED_SECTIONS])
        h.scope.cancel()
    }

    @Test
    fun everyKindOfIdFoldsTheSameLocalWay() = runTest {
        val h = harness()
        advanceUntilIdle()
        val ids = listOf("pinned", "recent", "backend", "project:github.com/acme/api", "home:mac")
        ids.forEach { h.fold.toggle(it, RecordingFeedback()) }
        advanceUntilIdle()
        assertEquals(ids.toSet(), h.fold.state.value.local)
        assertEquals(ids.toSet(), h.store.current[PrefKeys.COLLAPSED_SECTIONS])
        h.scope.cancel()
    }

    @Test
    fun theStoredSetLoadsAndAwaitLoadedReturns() = runTest {
        val h = harness(MemoryPrefsStore(mutablePreferencesOf(PrefKeys.COLLAPSED_SECTIONS to setOf("pinned", "gone"))))
        h.fold.awaitLoaded()
        // Never pruned: a fold survives its section or group coming back.
        assertEquals(setOf("pinned", "gone"), h.fold.state.value.local)
        assertTrue(h.fold.isCollapsedIn(h.fold.state.value, "gone"))
        h.scope.cancel()
    }

    @Test
    fun aTapBeforeTheLoadIsKeptOnTopOfTheStoredSet() = runTest {
        val h = harness(MemoryPrefsStore(mutablePreferencesOf(PrefKeys.COLLAPSED_SECTIONS to setOf("pinned"))))
        h.fold.toggle("backend", RecordingFeedback())
        advanceUntilIdle()
        assertEquals(setOf("pinned", "backend"), h.fold.state.value.local)
        assertEquals(setOf("pinned", "backend"), h.store.current[PrefKeys.COLLAPSED_SECTIONS])
        h.scope.cancel()
    }

    @Test
    fun theObsoleteSyncedFoldOverridesKeyIsRemoved() = runTest {
        val h = harness(MemoryPrefsStore(mutablePreferencesOf(PrefKeys.SECTION_FOLD_OVERRIDES to setOf("1|backend"))))
        advanceUntilIdle()
        assertNull(h.store.current[PrefKeys.SECTION_FOLD_OVERRIDES])
        assertEquals(emptySet<String>(), h.fold.state.value.local)
        h.scope.cancel()
    }
}
