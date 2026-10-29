package sh.zeron.android.core

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
import org.junit.Test
import sh.zeron.android.sessions.MemoryPrefsStore

/**
 * [Prefs.grouping] (#131): the None/Project/Device mode under
 * `"sessionGrouping"`, the one-time migration of iOS's `"groupByProject"`
 * flag, the menu's exclusive toggles and the Settings switch's
 * [Prefs.setGroupByProject] reading of the mode.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrefsGroupingTest {

    private fun TestScope.prefs(store: MemoryPrefsStore): Pair<Prefs, CoroutineScope> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return Prefs(store, scope, io = dispatcher) to scope
    }

    @Test
    fun absentKeysReadAsNone() = runTest {
        val (prefs, scope) = prefs(MemoryPrefsStore())
        prefs.awaitLoaded()
        assertEquals(SessionGrouping.None, prefs.grouping.value)
        assertFalse(prefs.groupByProject.value)
        scope.cancel()
    }

    @Test
    fun theOldProjectFlagMigratesToProjectAndIsRemoved() = runTest {
        val store = MemoryPrefsStore(mutablePreferencesOf(PrefKeys.GROUP_BY_PROJECT to true))
        val (prefs, scope) = prefs(store)
        prefs.awaitLoaded()
        advanceUntilIdle()
        assertEquals(SessionGrouping.Project, prefs.grouping.value)
        assertEquals(true, prefs.groupByProject.value)
        assertEquals("project", store.current[PrefKeys.SESSION_GROUPING])
        assertNull(store.current[PrefKeys.GROUP_BY_PROJECT])
        scope.cancel()
    }

    @Test
    fun anOffFlagMigratesToNone() = runTest {
        val store = MemoryPrefsStore(mutablePreferencesOf(PrefKeys.GROUP_BY_PROJECT to false))
        val (prefs, scope) = prefs(store)
        prefs.awaitLoaded()
        advanceUntilIdle()
        assertEquals(SessionGrouping.None, prefs.grouping.value)
        assertEquals("none", store.current[PrefKeys.SESSION_GROUPING])
        assertNull(store.current[PrefKeys.GROUP_BY_PROJECT])
        scope.cancel()
    }

    @Test
    fun aStoredModeLoadsAndBeatsALeftoverFlag() = runTest {
        val store = MemoryPrefsStore(
            mutablePreferencesOf(PrefKeys.SESSION_GROUPING to "device", PrefKeys.GROUP_BY_PROJECT to true),
        )
        val (prefs, scope) = prefs(store)
        prefs.awaitLoaded()
        advanceUntilIdle()
        assertEquals(SessionGrouping.Device, prefs.grouping.value)
        assertFalse(prefs.groupByProject.value)
        assertEquals("device", store.current[PrefKeys.SESSION_GROUPING])
        assertNull(store.current[PrefKeys.GROUP_BY_PROJECT])
        scope.cancel()
    }

    @Test
    fun theTogglesAreExclusiveAndPersist() = runTest {
        val store = MemoryPrefsStore()
        val (prefs, scope) = prefs(store)
        prefs.awaitLoaded()
        prefs.toggleGrouping(SessionGrouping.Project)
        assertEquals(SessionGrouping.Project, prefs.grouping.value)
        prefs.toggleGrouping(SessionGrouping.Device)
        assertEquals(SessionGrouping.Device, prefs.grouping.value)
        advanceUntilIdle()
        assertEquals("device", store.current[PrefKeys.SESSION_GROUPING])
        prefs.toggleGrouping(SessionGrouping.Device)
        assertEquals(SessionGrouping.None, prefs.grouping.value)
        advanceUntilIdle()
        assertEquals("none", store.current[PrefKeys.SESSION_GROUPING])
        scope.cancel()
    }

    @Test
    fun theSettingsSwitchReadsAndWritesTheProjectHalf() = runTest {
        val (prefs, scope) = prefs(MemoryPrefsStore())
        prefs.awaitLoaded()
        prefs.setGrouping(SessionGrouping.Device)
        // The switch reads off while grouping by device; turning it off changes nothing.
        prefs.setGroupByProject(false)
        assertEquals(SessionGrouping.Device, prefs.grouping.value)
        prefs.setGroupByProject(true)
        assertEquals(SessionGrouping.Project, prefs.grouping.value)
        prefs.setGroupByProject(false)
        assertEquals(SessionGrouping.None, prefs.grouping.value)
        scope.cancel()
    }

    @Test
    fun aChoiceBeforeTheLoadWins() = runTest {
        val store = MemoryPrefsStore(mutablePreferencesOf(PrefKeys.SESSION_GROUPING to "project"))
        val (prefs, scope) = prefs(store)
        prefs.setGrouping(SessionGrouping.Device)
        advanceUntilIdle()
        assertEquals(SessionGrouping.Device, prefs.grouping.value)
        assertEquals("device", store.current[PrefKeys.SESSION_GROUPING])
        scope.cancel()
    }
}
