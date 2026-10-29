package sh.zeron.android.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.zeron_core.ChatIndicator
import uniffi.zeron_core.ChatIndicator.AWAITING_INPUT
import uniffi.zeron_core.ChatIndicator.COMPLETED
import uniffi.zeron_core.ChatIndicator.IDLE
import uniffi.zeron_core.ChatIndicator.WORKING

/**
 * The RunWatch inputs from snapshots (#57, docs/port/05 §2.4): the
 * top-level Working chats with their device and run start, the awaiting
 * count, and which runs this phone started (a send from here, kept until
 * the run leaves Working).
 */
class RunActivityTrackerTest {

    private val tracker = RunActivityTracker()

    private fun row(
        id: String,
        indicator: ChatIndicator,
        sending: Boolean = false,
        silent: Boolean = false,
    ) = WatchedRow(
        id = id,
        title = "Chat $id",
        indicator = indicator,
        workingSinceMs = if (indicator == WORKING) 7_000L else null,
        lastActivityMs = 9_000L,
        silent = silent,
        deviceName = "Mac",
        sending = sending,
    )

    @Test
    fun workingChatsBecomeRunsAndAwaitingOnesAreCounted() {
        val activity = tracker.update(
            listOf(row("a", WORKING), row("b", AWAITING_INPUT), row("c", AWAITING_INPUT), row("d", IDLE)),
        )
        assertEquals(listOf(WorkingRun("a", "Chat a", "Mac", 7_000L, fromThisDevice = false)), activity.working)
        assertEquals(2, activity.awaiting)
    }

    @Test
    fun sideAndArchivedChatsAreLeftOut() {
        val activity = tracker.update(
            listOf(row("a", WORKING, silent = true), row("b", AWAITING_INPUT, silent = true)),
        )
        assertEquals(RunActivity.Idle, activity)
    }

    @Test
    fun aRunThisPhoneSentIntoIsFromThisDeviceUntilItEnds() {
        // The send is in flight, then the host picks it up.
        assertTrue(tracker.update(listOf(row("a", IDLE, sending = true))).working.isEmpty())
        assertTrue(tracker.update(listOf(row("a", WORKING))).working.single().fromThisDevice)
        assertTrue(tracker.update(listOf(row("a", WORKING))).working.single().fromThisDevice)
        // The run ends; the next one, started elsewhere, is not from here.
        tracker.update(listOf(row("a", COMPLETED)))
        assertFalse(tracker.update(listOf(row("a", WORKING))).working.single().fromThisDevice)
    }

    @Test
    fun aRunStartedElsewhereIsNotFromThisDevice() {
        assertFalse(tracker.update(listOf(row("a", WORKING))).working.single().fromThisDevice)
    }

    @Test
    fun resetForgetsTheRunsSentFromHere() {
        tracker.update(listOf(row("a", WORKING, sending = true)))
        tracker.reset()
        assertFalse(tracker.update(listOf(row("a", WORKING))).working.single().fromThisDevice)
    }
}
