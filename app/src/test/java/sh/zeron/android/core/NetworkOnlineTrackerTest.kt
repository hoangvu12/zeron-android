package sh.zeron.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The default-network dedupe logic (android-ui-parity #35, plain JVM):
 * `setNetworkOnline` is deduplicated on `(defaultNetworkHandle, online)` —
 * the iOS `NWPathMonitor` behaviour (an interface change re-sends `true`,
 * a signal-strength-only change sends nothing) — and `networkPathChanged`
 * follows iOS's path shape (`AppModel.swift:104-122`).
 */
class NetworkOnlineTrackerTest {

    // ---------------------------------------------------- setNetworkOnline

    @Test
    fun initialOfflineThenAvailableSendsTrue() {
        val tracker = NetworkOnlineTracker(initialNetwork = null, initialOnline = false)
        assertEquals(true, tracker.available(1).online)
    }

    @Test
    fun theSameNetworkAgainSendsNothing() {
        val tracker = NetworkOnlineTracker(initialNetwork = null, initialOnline = false)
        tracker.available(1)
        assertEquals(NetworkCalls.None, tracker.available(1))
    }

    @Test
    fun anInterfaceHandoverSendsTrueAgain() {
        val tracker = NetworkOnlineTracker(initialNetwork = null, initialOnline = false)
        tracker.available(1)
        assertEquals(true, tracker.available(2).online)
    }

    @Test
    fun anUnchangedCapabilitySendsNothing() {
        val tracker = NetworkOnlineTracker(initialNetwork = null, initialOnline = false)
        tracker.available(2)
        assertEquals(NetworkCalls.None, tracker.capabilities(2, hasInternet = true))
    }

    @Test
    fun anInternetCapabilityFlipSendsFalse() {
        val tracker = NetworkOnlineTracker(initialNetwork = null, initialOnline = false)
        tracker.available(2)
        assertEquals(false, tracker.capabilities(2, hasInternet = false).online)
    }

    @Test
    fun losingANonCurrentNetworkSendsNothing() {
        val tracker = NetworkOnlineTracker(initialNetwork = null, initialOnline = false)
        tracker.available(2)
        assertEquals(NetworkCalls.None, tracker.lost(1))
    }

    @Test
    fun losingTheCurrentNetworkWhileAlreadyOfflineSendsNothing() {
        val tracker = NetworkOnlineTracker(initialNetwork = null, initialOnline = false)
        tracker.available(2)
        tracker.capabilities(2, hasInternet = false)
        assertEquals(NetworkCalls.None, tracker.lost(2))
    }

    @Test
    fun losingTheCurrentNetworkWhileOnlineSendsFalse() {
        val tracker = NetworkOnlineTracker(initialNetwork = 1, initialOnline = true)
        assertEquals(false, tracker.lost(1).online)
    }

    @Test
    fun aSignalStrengthOnlyChangeSendsNothing() {
        // Bandwidth/signal changes arrive as onCapabilitiesChanged with
        // the same INTERNET state: no call, as on iOS.
        val tracker = NetworkOnlineTracker(initialNetwork = 2, initialOnline = true)
        assertEquals(NetworkCalls.None, tracker.capabilities(2, hasInternet = true))
        assertEquals(NetworkCalls.None, tracker.available(2))
    }

    @Test
    fun theOnlineFlagTracksTheLastState() {
        val tracker = NetworkOnlineTracker(initialNetwork = null, initialOnline = false)
        assertEquals(false, tracker.online)
        tracker.available(1)
        assertEquals(true, tracker.online)
        tracker.capabilities(1, hasInternet = false)
        assertEquals(false, tracker.online)
    }

    // -------------------------------------------------- networkPathChanged

    @Test
    fun theSeedIsTheFirstReadingSoItsOwnNetworkIsNoChange() {
        // registerDefaultNetworkCallback re-delivers the current default
        // right away: same shape, nothing to re-pair.
        val tracker = NetworkOnlineTracker(initialNetwork = 1, initialOnline = true)
        assertFalse(tracker.available(1).pathChanged)
    }

    @Test
    fun aHandoverToAnotherNetworkChangesThePath() {
        val tracker = NetworkOnlineTracker(initialNetwork = 1, initialOnline = true)
        val calls = tracker.available(2)
        assertEquals(NetworkCalls(online = true, pathChanged = true), calls)
    }

    @Test
    fun goingOfflineIsNotAPathChange() {
        val tracker = NetworkOnlineTracker(initialNetwork = 1, initialOnline = true)
        assertEquals(NetworkCalls(online = false, pathChanged = false), tracker.lost(1))
        val other = NetworkOnlineTracker(initialNetwork = 1, initialOnline = true)
        assertEquals(
            NetworkCalls(online = false, pathChanged = false),
            other.capabilities(1, hasInternet = false),
        )
    }

    @Test
    fun comingBackOnlineChangesThePath() {
        // iOS: "" → a satisfied shape differs from the last one seen.
        val tracker = NetworkOnlineTracker(initialNetwork = 1, initialOnline = true)
        tracker.lost(1)
        assertTrue(tracker.available(1).pathChanged)
        tracker.capabilities(1, hasInternet = false)
        assertTrue(tracker.capabilities(1, hasInternet = true).pathChanged)
    }

    @Test
    fun anOfflineSeedThenTheFirstNetworkChangesThePath() {
        // The seed read "unsatisfied" (shape ""); the first network differs.
        val tracker = NetworkOnlineTracker(initialNetwork = null, initialOnline = false)
        assertTrue(tracker.available(1).pathChanged)
    }

    @Test
    fun ignoredCallbacksNeverChangeThePath() {
        val tracker = NetworkOnlineTracker(initialNetwork = 1, initialOnline = true)
        assertFalse(tracker.lost(2).pathChanged)
        assertFalse(tracker.capabilities(2, hasInternet = false).pathChanged)
        assertNull(tracker.capabilities(1, hasInternet = true).online)
    }
}
