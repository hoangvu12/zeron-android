package sh.zeron.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upstream's `NetworkPathTrackerTest` (apps/android, v0.3.7), on this
 * port's [NetworkCalls]-returning tracker: the launch seed, re-delivered
 * capabilities, moves between networks or into metered as path changes,
 * and Doze / restricted background data (blocked) as no path.
 */
class NetworkPathTrackerTest {
    private val calls = mutableListOf<String>()
    private val tracker = NetworkPathTracker()

    private fun record(c: NetworkCalls) {
        c.online?.let { calls += if (it) "online" else "offline" }
        if (c.pathChanged) calls += "moved"
    }

    private fun update(shape: String?) = record(tracker.update(shape))

    private fun setBlocked(blocked: Boolean) = record(tracker.setBlocked(blocked))

    @Test
    fun launchingWithNoNetworkReadsOfflineThoughTheOsSaysNothing() {
        tracker.seed(null)
        assertFalse(tracker.online)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun theFirstCallbackForTheNetworkAlreadyKnownChangesNothing() {
        tracker.seed("100/1")
        update("100/1")
        assertTrue(tracker.online)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun reDeliveredCapabilitiesLeaveAHealthyLinkAlone() {
        tracker.seed("100/1")
        repeat(5) { update("100/1") }
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun movingBetweenNetworksWithoutLosingOneIsAPathChange() {
        tracker.seed("100/1")
        update("101/0!")
        assertEquals(listOf("moved"), calls)
    }

    @Test
    fun aNetworkTurningMeteredIsAPathChange() {
        tracker.seed("100/1")
        update("100/1!")
        assertEquals(listOf("moved"), calls)
    }

    @Test
    fun anotherTransportOnTheSameNetworkIsAPathChange() {
        tracker.seed("100/1")
        update("100/0,1")
        assertEquals(listOf("moved"), calls)
    }

    @Test
    fun losingTheNetworkThenGettingAnotherReportsOnceEachWay() {
        tracker.seed("100/1")
        update(null)
        update("101/0!")
        // The core treats coming back online as a path change itself.
        assertEquals(listOf("offline", "online"), calls)
    }

    @Test
    fun beingCutOffWithTheNetworkStillUpCountsAsNoPath() {
        tracker.seed("100/1")
        setBlocked(true)
        assertFalse(tracker.online)
        update("100/1!")
        setBlocked(false)
        assertTrue(tracker.online)
        // Nothing can be torn down twice: moving while cut off is covered by coming back.
        assertEquals(listOf("offline", "online"), calls)
    }

    @Test
    fun repeatedBlockedCallbacksReportOnce() {
        tracker.seed("100/1")
        setBlocked(false)
        setBlocked(true)
        setBlocked(true)
        assertEquals(listOf("offline"), calls)
    }

    @Test
    fun losingABlockedNetworkDoesNotLeaveTheNextOneBlocked() {
        tracker.seed("100/1")
        setBlocked(true)
        update(null)
        update("101/1")
        assertTrue(tracker.online)
        assertEquals(listOf("offline", "online"), calls)
    }

    @Test
    fun aNewClientGetsTheStateReadAtLaunch() {
        tracker.seed("100/1")
        setBlocked(true)
        // clientReady() passes this straight to a client that defaults to online.
        assertFalse(tracker.online)
    }
}
