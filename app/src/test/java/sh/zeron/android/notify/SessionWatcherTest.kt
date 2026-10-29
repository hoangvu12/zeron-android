package sh.zeron.android.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.zeron_core.ChatIndicator
import uniffi.zeron_core.ChatIndicator.AWAITING_INPUT
import uniffi.zeron_core.ChatIndicator.COMPLETED
import uniffi.zeron_core.ChatIndicator.ERRORED
import uniffi.zeron_core.ChatIndicator.IDLE
import uniffi.zeron_core.ChatIndicator.WORKING

/**
 * The edge's rule on snapshots (#56, docs/port/05 §2.4): first sight seeds,
 * failed / input / done transitions, the coalesced next-turn case, silent
 * Working → Idle, silent side and archived chats, reset.
 */
class SessionWatcherTest {

    private val watcher = SessionWatcher()

    private fun row(
        indicator: ChatIndicator,
        id: String = "chat-1",
        title: String = "Fix the build",
        workingSinceMs: Long? = if (indicator == WORKING || indicator == AWAITING_INPUT) 1_000L else null,
        silent: Boolean = false,
    ) = WatchedRow(id, title, indicator, workingSinceMs, lastActivityMs = 5_000L, silent = silent)

    private fun step(vararg rows: WatchedRow) = watcher.update(rows.toList())

    private fun kinds(vararg rows: WatchedRow) = step(*rows).map { it.kind }

    @Test
    fun firstSightOnlySeeds() {
        assertTrue(step(row(ERRORED), row(AWAITING_INPUT, id = "chat-2"), row(COMPLETED, id = "chat-3")).isEmpty())
    }

    @Test
    fun workingToCompletedIsDone() {
        step(row(WORKING))
        assertEquals(
            listOf(SessionNotice("chat-1", "Fix the build", NotificationKind.Done, 5_000L)),
            step(row(COMPLETED)),
        )
    }

    @Test
    fun awaitingToCompletedIsDone() {
        step(row(AWAITING_INPUT))
        assertEquals(listOf(NotificationKind.Done), kinds(row(COMPLETED)))
    }

    @Test
    fun becomingAwaitingInputIsInput() {
        step(row(WORKING))
        assertEquals(listOf(NotificationKind.Input), kinds(row(AWAITING_INPUT)))
        // Still waiting: nothing new.
        assertTrue(kinds(row(AWAITING_INPUT)).isEmpty())
    }

    @Test
    fun becomingErroredIsFailedFromAnyState() {
        step(row(IDLE))
        assertEquals(listOf(NotificationKind.Failed), kinds(row(ERRORED)))
        assertTrue(kinds(row(ERRORED)).isEmpty())
    }

    @Test
    fun aNewTurnWhileStillWorkingIsDone() {
        step(row(WORKING, workingSinceMs = 1_000L))
        assertTrue(kinds(row(WORKING, workingSinceMs = 1_000L)).isEmpty())
        assertEquals(listOf(NotificationKind.Done), kinds(row(WORKING, workingSinceMs = 9_000L)))
    }

    @Test
    fun workingToIdleIsSilent() {
        step(row(WORKING))
        assertTrue(kinds(row(IDLE)).isEmpty())
    }

    @Test
    fun idleToCompletedIsSilent() {
        // A completion this phone never saw running (seeded idle) stays quiet.
        step(row(IDLE))
        assertTrue(kinds(row(COMPLETED)).isEmpty())
    }

    @Test
    fun sideAndArchivedChatsNeverNotifyButKeepTheirBaseline() {
        step(row(WORKING, silent = true))
        assertTrue(kinds(row(COMPLETED, silent = true)).isEmpty())
        // Un-archived later: compared against the baseline, not seeded again.
        assertEquals(listOf(NotificationKind.Failed), kinds(row(ERRORED)))
    }

    @Test
    fun aChatThatLeftTheSnapshotIsSeededAgain() {
        step(row(WORKING))
        step(row(IDLE, id = "other"))
        assertTrue(kinds(row(COMPLETED)).isEmpty())
    }

    @Test
    fun resetForgetsEveryChat() {
        step(row(WORKING))
        watcher.reset()
        assertTrue(kinds(row(COMPLETED)).isEmpty())
    }

    @Test
    fun aBlankTitleReadsNewSession() {
        step(row(WORKING, title = " "))
        assertEquals("New session", step(row(COMPLETED, title = " ")).single().title)
    }

    @Test
    fun eachChatIsJudgedOnItsOwn() {
        step(row(WORKING, id = "a"), row(WORKING, id = "b"), row(IDLE, id = "c"))
        val notices = step(row(COMPLETED, id = "a"), row(AWAITING_INPUT, id = "b"), row(IDLE, id = "c"))
        assertEquals(listOf("a" to NotificationKind.Done, "b" to NotificationKind.Input), notices.map { it.chatId to it.kind })
    }
}
