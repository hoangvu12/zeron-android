package sh.zeron.android.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.zeron.android.sessions.testConnectivity
import sh.zeron.android.sessions.testDevice
import sh.zeron.android.sessions.testRow
import sh.zeron.android.sessions.testWorkspace
import uniffi.zeron_core.ClientEvent
import uniffi.zeron_core.Connectivity
import uniffi.zeron_core.ConnectivityState
import uniffi.zeron_core.CoreException
import uniffi.zeron_core.WorkspaceSnapshot

/**
 * [WorkspaceFeed] (#20): the event triggers, the publish-on-any-change
 * rule (so a clock tick with the same revision still ages "4m"), the 30 s
 * clock, devices from the snapshot, connectivity from the event, the
 * reset on a client change and the reconnect edges image tiles retry on.
 * Plain JVM: the core is a fake, the IO lane a test dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceFeedTest {

    private class FakeCore(var ws: WorkspaceSnapshot) : WorkspaceCore {
        var reads = 0
        var connectivityReads = 0
        var conn: Connectivity = testConnectivity(ConnectivityState.CONNECTED)
        var refuse = false

        override fun workspaceRevision(): ULong = ws.revision
        override fun workspace(): WorkspaceSnapshot {
            if (refuse) throw CoreException.HostUnavailable("signed out")
            reads++
            return ws
        }
        override fun connectivity(): Connectivity {
            connectivityReads++
            return conn
        }
    }

    private class Harness(val core: FakeCore, val feed: WorkspaceFeed, val scope: CoroutineScope)

    private fun TestScope.harness(): Harness {
        val core = FakeCore(testWorkspace(1uL))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val feed = WorkspaceFeed(core = { core }, scope = scope, io = StandardTestDispatcher(testScheduler))
        return Harness(core, feed, scope)
    }

    @Test
    fun refreshPublishesTheSnapshotItsDevicesAndAFirstConnectivity() = runTest {
        val h = harness()
        h.core.ws = testWorkspace(1uL, devices = listOf(testDevice("mac")))
        h.feed.refresh()
        advanceUntilIdle()
        assertEquals(1uL, h.feed.snapshot.value?.revision)
        assertEquals(listOf("mac"), h.feed.devices.value.map { it.id })
        assertEquals(ConnectivityState.CONNECTED, h.feed.connectivity.value?.state)
        h.feed.refresh()
        advanceUntilIdle()
        assertEquals("connectivity is read once, then follows the events", 1, h.core.connectivityReads)
        h.scope.cancel()
    }

    @Test
    fun aWorkspaceChangedAtThePublishedRevisionCostsNoRead() = runTest {
        val h = harness()
        h.feed.refresh()
        advanceUntilIdle()
        assertEquals(1, h.core.reads)
        h.feed.onEvent(ClientEvent.WorkspaceChanged(revision = 1uL))
        advanceUntilIdle()
        assertEquals(1, h.core.reads)
        h.core.ws = testWorkspace(2uL)
        h.feed.onEvent(ClientEvent.WorkspaceChanged(revision = 2uL))
        advanceUntilIdle()
        assertEquals(2, h.core.reads)
        assertEquals(2uL, h.feed.snapshot.value?.revision)
        h.scope.cancel()
    }

    @Test
    fun aBurstOfEventsConflatesToFewReads() = runTest {
        val h = harness()
        repeat(50) { h.feed.onEvent(ClientEvent.WorkspaceChanged(revision = 9uL)) }
        advanceUntilIdle()
        assertEquals(1, h.core.reads)
        h.scope.cancel()
    }

    @Test
    fun aSameRevisionReadWithNewTimeLabelsStillPublishes() = runTest {
        val h = harness()
        h.core.ws = testWorkspace(1uL, recent = listOf(testRow("a", timeLabel = "3m")))
        h.feed.refresh()
        advanceUntilIdle()
        h.core.ws = testWorkspace(1uL, recent = listOf(testRow("a", timeLabel = "4m")))
        h.feed.refresh()
        advanceUntilIdle()
        assertEquals("4m", h.feed.snapshot.value?.front?.recent?.single()?.timeLabel)
        h.scope.cancel()
    }

    @Test
    fun theClockReReadsEvery30Seconds() = runTest {
        val h = harness()
        val clock = h.scope.launch { h.feed.runClock() }
        runCurrent()
        assertEquals(0, h.core.reads)
        advanceTimeBy(WorkspaceFeed.CLOCK_PERIOD.inWholeMilliseconds + 1)
        runCurrent()
        assertEquals(1, h.core.reads)
        advanceTimeBy(WorkspaceFeed.CLOCK_PERIOD.inWholeMilliseconds)
        runCurrent()
        assertEquals(2, h.core.reads)
        clock.cancel()
        advanceTimeBy(WorkspaceFeed.CLOCK_PERIOD.inWholeMilliseconds * 3)
        runCurrent()
        assertEquals("a stopped clock (backgrounded) reads nothing", 2, h.core.reads)
        h.scope.cancel()
    }

    @Test
    fun connectivityFollowsTheEventWithoutAnFfiCall() = runTest {
        val h = harness()
        h.feed.onEvent(ClientEvent.ConnectivityChanged(connectivity = testConnectivity(ConnectivityState.OFFLINE)))
        assertEquals(ConnectivityState.OFFLINE, h.feed.connectivity.value?.state)
        assertEquals(0, h.core.connectivityReads)
        h.scope.cancel()
    }

    @Test
    fun reconnectsFireOnlyWhenConnectedComesBack() = runTest {
        val h = harness()
        val fired = mutableListOf<Unit>()
        val job = h.scope.launch { h.feed.reconnects.collect { fired += it } }
        runCurrent()
        fun post(state: ConnectivityState) {
            h.feed.onEvent(ClientEvent.ConnectivityChanged(connectivity = testConnectivity(state)))
            runCurrent()
        }
        post(ConnectivityState.CONNECTED)
        assertEquals(0, fired.size)
        post(ConnectivityState.RECONNECTING)
        post(ConnectivityState.OFFLINE)
        post(ConnectivityState.CONNECTED)
        assertEquals(1, fired.size)
        post(ConnectivityState.CONNECTED)
        assertEquals(1, fired.size)
        job.cancel()
        h.scope.cancel()
    }

    @Test
    fun resetDropsTheOldAccountAndReadsTheNewOne() = runTest {
        val h = harness()
        h.core.ws = testWorkspace(5uL, devices = listOf(testDevice("old")))
        h.feed.refresh()
        advanceUntilIdle()
        h.core.refuse = true // signed out: the live seam refuses
        h.feed.reset()
        advanceUntilIdle()
        assertNull(h.feed.snapshot.value)
        assertEquals(emptyList<Any>(), h.feed.devices.value)
        assertNull(h.feed.connectivity.value)
        h.core.refuse = false
        h.core.ws = testWorkspace(1uL)
        h.feed.reset()
        advanceUntilIdle()
        assertEquals(1uL, h.feed.snapshot.value?.revision)
        h.scope.cancel()
    }

    @Test
    fun noClientYetReadsNothingAndKeepsTheLaneAlive() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var core: WorkspaceCore? = null
        val feed = WorkspaceFeed(core = { core }, scope = scope, io = StandardTestDispatcher(testScheduler))
        feed.refresh()
        advanceUntilIdle()
        assertNull(feed.snapshot.value)
        core = FakeCore(testWorkspace(3uL))
        feed.refresh()
        advanceUntilIdle()
        assertEquals(3uL, feed.snapshot.value?.revision)
        scope.cancel()
    }
}
