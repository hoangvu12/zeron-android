package sh.zeron.android.sessions

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.zeron.android.design.holdRefresh
import uniffi.zeron_core.ConnectivityState

/**
 * Pull to refresh's resync wait (#23, iOS `AppModel.refresh`): the call
 * returns on the next completed workspace read or connectivity change —
 * never on a reconnecting/offline blip — with [holdRefresh]'s 0.6 s floor
 * and 3 s backstop around it. Plain JVM, virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefresherTest {

    private fun settled(state: String) = state == "connected"

    @Test
    fun returnsOnTheNextReadAfterTheKick() = runTest {
        val reads = MutableStateFlow(0L)
        val connectivity = MutableStateFlow("connected")
        var kicked = false
        var done = false
        launch {
            awaitResync(reads, connectivity, ::settled) { kicked = true }
            done = true
        }
        runCurrent()
        assertTrue(kicked)
        assertFalse("nothing landed yet", done)
        reads.value = 1
        runCurrent()
        assertTrue(done)
    }

    @Test
    fun aReadThatLandsDuringTheKickIsNotMissed() = runTest {
        val reads = MutableStateFlow(0L)
        val connectivity = MutableStateFlow("connected")
        var done = false
        launch {
            awaitResync(reads, connectivity, ::settled) { reads.value = 1 }
            done = true
        }
        runCurrent()
        assertTrue(done)
    }

    @Test
    fun aReconnectingBlipIsNotTheResyncLanding() = runTest {
        val reads = MutableStateFlow(0L)
        val connectivity = MutableStateFlow("connected")
        var done = false
        launch {
            awaitResync(reads, connectivity, ::settled) { connectivity.value = "reconnecting" }
            done = true
        }
        runCurrent()
        assertFalse("the redial's blip", done)
        reads.value = 1
        runCurrent()
        assertFalse("a read while still reconnecting", done)
        connectivity.value = "connected"
        runCurrent()
        assertTrue(done)
    }

    @Test
    fun aConnectivityChangeAloneLands() = runTest {
        val reads = MutableStateFlow(0L)
        val connectivity = MutableStateFlow("offline-ish")
        var done = false
        launch {
            awaitResync(reads, connectivity, { true }) {}
            done = true
        }
        runCurrent()
        assertFalse(done)
        connectivity.value = "connected"
        runCurrent()
        assertTrue(done)
    }

    @Test
    fun connectivitySettledFollowsIos() {
        assertTrue(connectivitySettled(testConnectivity(ConnectivityState.CONNECTED)))
        assertTrue("no posture yet is not a blip", connectivitySettled(null))
        assertFalse(connectivitySettled(testConnectivity(ConnectivityState.RECONNECTING)))
        assertFalse(connectivitySettled(testConnectivity(ConnectivityState.OFFLINE)))
    }

    @Test
    fun theFeedRefresherReReadsAndReturnsOnTheRead() = runTest {
        val core = FakeWorkspaceCore()
        val feed = testFeed(core)
        feed.refresh()
        advanceUntilIdle() // the first read and its connectivity
        val before = core.workspaceCalls.get()

        var done = false
        launch {
            feedRefresher(feed, client = { null }, io = StandardTestDispatcher(testScheduler)).refresh()
            done = true
        }
        advanceUntilIdle()
        assertEquals("one re-read", before + 1, core.workspaceCalls.get())
        assertTrue("an unchanged snapshot still lands the refresh", done)
    }

    @Test
    fun theSpinnerHoldsAtLeastTheFloorAndAtMostTheBackstop() = runTest {
        val reads = MutableStateFlow(0L)
        val connectivity = MutableStateFlow("connected")
        // Lands at once: held to the 0.6 s floor.
        val fast = testScheduler.currentTime
        holdRefresh({ awaitResync(reads, connectivity, ::settled) { reads.value++ } })
        assertEquals(600L, testScheduler.currentTime - fast)
        // Never lands: cut at the 3 s backstop.
        val slow = testScheduler.currentTime
        holdRefresh({ awaitResync(reads, connectivity, ::settled) {} })
        assertEquals(3_000L, testScheduler.currentTime - slow)
    }
}
