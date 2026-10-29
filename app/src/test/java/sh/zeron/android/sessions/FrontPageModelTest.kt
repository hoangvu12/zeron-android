package sh.zeron.android.sessions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import sh.zeron.android.core.Prefs
import sh.zeron.android.core.SessionGrouping
import sh.zeron.android.core.WorkspaceCore
import sh.zeron.android.core.WorkspaceFeed
import sh.zeron.android.design.Cue
import sh.zeron.android.design.Feedback
import sh.zeron.android.design.Haptic
import uniffi.zeron_core.Connectivity
import uniffi.zeron_core.ConnectivityState
import uniffi.zeron_core.WorkspaceSnapshot

/**
 * [FrontPageModel] (#21) over a real [WorkspaceFeed] (fake core), real
 * [Prefs] and a real [FoldState] on an in-memory store: the null-until-
 * loaded rule, Group by Project and by Device (#131), local folds,
 * Collapse All, and the
 * rebuild on a same-revision snapshot whose time labels moved (#20's
 * 30 s clock).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FrontPageModelTest {

    private class FakeCore(var ws: WorkspaceSnapshot) : WorkspaceCore {
        override fun workspaceRevision(): ULong = ws.revision
        override fun workspace(): WorkspaceSnapshot = ws
        override fun connectivity(): Connectivity = testConnectivity(ConnectivityState.CONNECTED)
    }

    private object NoFeedback : Feedback {
        override fun haptic(haptic: Haptic) {}
        override fun cue(cue: Cue) {}
    }

    private class Harness(
        val core: FakeCore,
        val feed: WorkspaceFeed,
        val prefs: Prefs,
        val fold: FoldState,
        val model: FrontPageModel,
        val scope: CoroutineScope,
    )

    private val backend = testSection("backend", "Backend", listOf(testRow("s1", sectionId = "backend")))
    private val api = testRow("a1", project = testRef("api-mac", "api"))
    private val home = testRow("h1", deviceId = "mac", deviceName = "MacBook")

    private fun workspace(revision: ULong = 1uL, label: String = "now") = testWorkspace(
        revision,
        pinned = listOf(testRow("p1", pinned = true)),
        sections = listOf(backend),
        recent = listOf(api.copy(timeLabel = label), home),
        projects = listOf(testProject("api-mac", groupKey = "github.com/acme/api", groupName = "api")),
        devices = listOf(testDevice("mac", online = false, name = "MacBook")),
    )

    private fun TestScope.harness(): Harness {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val store = MemoryPrefsStore()
        val core = FakeCore(workspace())
        val feed = WorkspaceFeed(core = { core }, scope = scope, io = dispatcher)
        val prefs = Prefs(store, scope, io = dispatcher)
        val fold = FoldState(store, scope, io = dispatcher)
        val model = FrontPageModel(feed, prefs, fold, scope, compute = dispatcher)
        return Harness(core, feed, prefs, fold, model, scope)
    }

    private fun Harness.ids() = model.ui.value!!.sections.map { it.id }

    @Test
    fun uiStaysNullUntilASnapshotArrives() = runTest {
        val h = harness()
        advanceUntilIdle()
        assertNull(h.model.ui.value)
        h.feed.refresh()
        advanceUntilIdle()
        assertNotNull(h.model.ui.value)
        assertEquals(listOf("pinned", "backend", "recent"), h.ids())
        h.scope.cancel()
    }

    @Test
    fun groupByProjectReplacesRecentWithTheGroups() = runTest {
        val h = harness()
        h.feed.refresh()
        advanceUntilIdle()
        h.prefs.setGroupByProject(true)
        advanceUntilIdle()
        assertEquals(listOf("pinned", "backend", "project:github.com/acme/api", "home:mac"), h.ids())
        assertEquals(listOf("Pinned", "Backend", "api", "Home · MacBook"), h.model.ui.value!!.sections.map { it.header })
        h.scope.cancel()
    }

    @Test
    fun groupByDeviceReplacesRecentWithTheDeviceGroups() = runTest {
        val h = harness()
        h.feed.refresh()
        advanceUntilIdle()
        h.prefs.setGrouping(SessionGrouping.Device)
        advanceUntilIdle()
        // The api row's device ("device") is unnamed and unlisted → Other.
        assertEquals(listOf("pinned", "backend", "other", "device:mac"), h.ids())
        assertEquals(
            listOf("Pinned", "Backend", "Other", "MacBook · Offline"),
            h.model.ui.value!!.sections.map { it.header },
        )
        assertEquals(FrontSectionKind.DeviceGroup, h.model.ui.value!!.meta["device:mac"]?.kind)
        h.scope.cancel()
    }

    @Test
    fun theTwoGroupingsAreExclusiveAndPickingTheCurrentOneTurnsGroupingOff() = runTest {
        val h = harness()
        h.feed.refresh()
        advanceUntilIdle()
        h.prefs.toggleGrouping(SessionGrouping.Project)
        advanceUntilIdle()
        assertEquals("project:github.com/acme/api", h.ids()[2])
        h.prefs.toggleGrouping(SessionGrouping.Device)
        advanceUntilIdle()
        assertEquals("other", h.ids()[2])
        assertEquals(false, h.prefs.groupByProject.value)
        h.prefs.toggleGrouping(SessionGrouping.Device)
        advanceUntilIdle()
        assertEquals(listOf("pinned", "backend", "recent"), h.ids())
        h.scope.cancel()
    }

    @Test
    fun collapseAllFoldsTheDeviceGroupsWhileGroupingByDevice() = runTest {
        val h = harness()
        h.feed.refresh()
        h.prefs.setGrouping(SessionGrouping.Device)
        advanceUntilIdle()
        h.model.collapseAll(NoFeedback)
        advanceUntilIdle()
        assertEquals(setOf("pinned", "backend", "other", "device:mac"), h.model.ui.value!!.collapsedIds)
        h.scope.cancel()
    }

    @Test
    fun aHeaderTapFoldsLocallyForEveryKind() = runTest {
        val h = harness()
        h.feed.refresh()
        h.prefs.setGroupByProject(true)
        advanceUntilIdle()
        h.model.toggleSection("backend", NoFeedback)
        h.model.toggleSection("home:mac", NoFeedback)
        advanceUntilIdle()
        assertEquals(setOf("backend", "home:mac"), h.model.ui.value!!.collapsedIds)
        h.scope.cancel()
    }

    @Test
    fun collapseAllFoldsFoldersAndGroupsButNeverRecent() = runTest {
        val h = harness()
        h.feed.refresh()
        advanceUntilIdle()
        h.model.collapseAll(NoFeedback)
        advanceUntilIdle()
        assertEquals(setOf("pinned", "backend"), h.fold.state.value.local)
        h.prefs.setGroupByProject(true)
        advanceUntilIdle()
        h.model.collapseAll(NoFeedback)
        advanceUntilIdle()
        assertEquals(
            setOf("pinned", "backend", "project:github.com/acme/api", "home:mac"),
            h.model.ui.value!!.collapsedIds,
        )
        h.scope.cancel()
    }

    @Test
    fun aSameRevisionSnapshotWithNewTimeLabelsRebuilds() = runTest {
        val h = harness()
        h.feed.refresh()
        advanceUntilIdle()
        h.core.ws = workspace(revision = 1uL, label = "4m")
        h.feed.refresh()
        advanceUntilIdle()
        val recent = h.model.ui.value!!.sections.first { it.id == "recent" }
        assertEquals("4m", recent.rows.first().timeLabel)
        h.scope.cancel()
    }
}
