package sh.zeron.android.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.zeron_core.CoreException

/**
 * The pill's banner (#28): [deriveBanner]'s precedence, the tap table and
 * the [StatusBanner] filler's sticky send failure and retry — the port of
 * `ios:Session/CoreSessionSource.swift:129-145` and
 * `SessionViewController.swift:173-179`. Plain JUnit over the lite inputs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusBannerTest {

    private val queuedCopy = "MacBook is offline — will send when it's back"

    @Test
    fun theSendFailureBeatsEverything() {
        val i = BannerInputs(
            sendFailure = "Couldn't send: boom",
            sendState = SendLite.Failed,
            offline = true,
            roomConnected = false,
            roomRetryAtMs = 5_000L,
        )
        assertEquals(SessionChrome.Banner.Failed("Couldn't send: boom"), deriveBanner(i))
    }

    @Test
    fun aFailedSendIsNotDeliveredEvenOffline() {
        assertEquals(
            SessionChrome.Banner.NotDelivered,
            deriveBanner(BannerInputs(sendState = SendLite.Failed, offline = true)),
        )
    }

    @Test
    fun aQueuedSendNamesTheOfflineHost() {
        assertEquals(
            SessionChrome.Banner.Failed(queuedCopy),
            deriveBanner(BannerInputs(sendState = SendLite.Queued, hostName = "MacBook", offline = true)),
        )
        assertEquals(
            SessionChrome.Banner.Failed("Host is offline — will send when it's back"),
            deriveBanner(BannerInputs(sendState = SendLite.Queued, hostName = null)),
        )
    }

    @Test
    fun aCloudChatsQueuedSendShowsNothing() {
        // The transcript's setup step says it; nothing below QUEUED is read.
        assertEquals(
            SessionChrome.Banner.None,
            deriveBanner(BannerInputs(sendState = SendLite.Queued, isCloud = true, offline = true)),
        )
    }

    @Test
    fun sendingBeatsOffline() {
        assertEquals(
            SessionChrome.Banner.Sending,
            deriveBanner(BannerInputs(sendState = SendLite.Sending, offline = true)),
        )
    }

    @Test
    fun offlineBeatsReconnecting() {
        assertEquals(
            SessionChrome.Banner.Offline,
            deriveBanner(BannerInputs(offline = true, roomConnected = false, roomRetryAtMs = 5_000L)),
        )
    }

    @Test
    fun aDownRoomWithARedialIsReconnecting() {
        assertEquals(
            SessionChrome.Banner.Reconnecting(5_000L),
            deriveBanner(BannerInputs(roomConnected = false, roomRetryAtMs = 5_000L)),
        )
        // No redial scheduled: nothing to count down.
        assertEquals(SessionChrome.Banner.None, deriveBanner(BannerInputs(roomConnected = false)))
    }

    @Test
    fun allQuietIsNone() {
        assertEquals(SessionChrome.Banner.None, deriveBanner(BannerInputs()))
    }

    /** iOS `sequence`: airplane mode while a send is in flight. */
    @Test
    fun airplaneModeWhileSendingFollowsTheIosSequence() {
        val sending = BannerInputs(sendState = SendLite.Sending, hostName = "MacBook")
        val queued = sending.copy(sendState = SendLite.Queued, offline = true)
        val back = BannerInputs(roomConnected = false, roomRetryAtMs = 9_000L)
        assertEquals(
            listOf(
                SessionChrome.Banner.Sending,
                SessionChrome.Banner.Failed(queuedCopy),
                SessionChrome.Banner.Offline,
                SessionChrome.Banner.Reconnecting(9_000L),
                SessionChrome.Banner.None,
            ),
            listOf(sending, queued, BannerInputs(offline = true), back, BannerInputs()).map(::deriveBanner),
        )
    }

    @Test
    fun editingOverridesTheShownBanner() {
        assertEquals(SessionChrome.Banner.Editing, shownBanner(SessionChrome.Banner.NotDelivered, editing = true))
        assertEquals(SessionChrome.Banner.Offline, shownBanner(SessionChrome.Banner.Offline, editing = false))
    }

    @Test
    fun onlyEditingAndNotDeliveredAreActionable() {
        assertEquals(BannerTap.CancelEdit, bannerTapAction(SessionChrome.Banner.Editing))
        assertEquals(BannerTap.RetryDelivery, bannerTapAction(SessionChrome.Banner.NotDelivered))
        listOf(
            SessionChrome.Banner.None,
            SessionChrome.Banner.Sending,
            SessionChrome.Banner.Offline,
            SessionChrome.Banner.Reconnecting(1L),
            SessionChrome.Banner.Failed("x"),
            SessionChrome.Banner.Working(null, "Working"),
        ).forEach { assertEquals(BannerTap.None, bannerTapAction(it)) }
    }

    @Test
    fun errorTextUsesTheReason() {
        assertEquals("Engine is offline", errorText(CoreException.HostUnavailable("Engine is offline")))
        assertEquals("client is shut down", errorText(CoreException.Closed()))
        assertEquals("plain", errorText(RuntimeException("plain")))
    }

    @Test
    fun aNotDeliveredTapRetriesAndInvalidates() = runTest {
        var retries = 0
        var invalidations = 0
        val banner = StatusBanner(
            retryDelivery = { retries++ },
            io = StandardTestDispatcher(testScheduler),
            logW = { _, _ -> },
        )
        banner.bind { invalidations++ }
        banner.onTap(SessionChrome.Banner.NotDelivered)
        advanceUntilIdle()
        assertEquals(1, retries)
        assertEquals(1, invalidations)
    }

    @Test
    fun aThrowingRetryIsSwallowed() = runTest {
        val logged = mutableListOf<String>()
        val banner = StatusBanner(
            retryDelivery = { throw IllegalStateException("nope") },
            io = StandardTestDispatcher(testScheduler),
            logW = { message, _ -> logged += message },
        )
        banner.onTap(SessionChrome.Banner.NotDelivered)
        advanceUntilIdle()
        assertEquals(listOf("retryDelivery failed"), logged)
    }

    @Test
    fun anEditingTapCancelsTheEdit() {
        var cancelled = false
        val banner = StatusBanner(retryDelivery = {}, logW = { _, _ -> })
        banner.onEditingTap = { cancelled = true }
        banner.onTap(SessionChrome.Banner.Editing)
        assertTrue(cancelled)
    }

    @Test
    fun theSendFailureIsStickyUntilTheNextSend() {
        var invalidations = 0
        val banner = StatusBanner(retryDelivery = {}, logW = { _, _ -> })
        banner.bind { invalidations++ }
        banner.noteSendFailed(RuntimeException("boom"))
        banner.noteSendFailed(RuntimeException("boom"))
        assertEquals(1, invalidations)
        banner.noteSendSucceeded()
        banner.noteSendSucceeded()
        assertEquals(2, invalidations)
    }
}
