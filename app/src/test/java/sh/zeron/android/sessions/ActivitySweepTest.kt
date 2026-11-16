package sh.zeron.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.zeron.android.core.ActivitySettings
import uniffi.zeron_core.SessionRow

/**
 * Activity's auto-archive (iOS 1.0.2 `AppModel.sweepIdle`): once a minute,
 * after a first look, never the pinned, open, asleep or recently restored.
 */
class ActivitySweepTest {

    private val day = 86_400_000L
    private val now0 = 100 * day

    private var settings = ActivitySettings(on = true, archiveAfterDays = 1)
    private var back: Map<String, Long> = emptyMap()
    private val archived = mutableListOf<String>()
    private val open = OpenChats()

    private val sweep = ActivitySweep(
        settings = { settings },
        unarchived = { back },
        setUnarchived = { back = it },
        open = open,
        archive = { archived += it },
    )

    private fun idle(id: String, ageMs: Long = 2 * day, pinned: Boolean = false): SessionRow =
        testRow(id, pinned = pinned).copy(lastActivityMs = now0 - ageMs)

    private fun run(
        recent: List<SessionRow>,
        at: Long,
        pinned: List<SessionRow> = emptyList(),
        archivedRows: List<SessionRow> = emptyList(),
        pinsReady: Boolean = true,
    ) = sweep.sweep(testFront(pinned = pinned, recent = recent), archivedRows, pinsReady, at)

    @Test
    fun firstSweepOnlyLooksThenArchivesOncePerMinute() {
        val rows = listOf(idle("old"), idle("fresh", ageMs = 60_000))
        run(rows, now0)
        assertTrue(archived.isEmpty())
        // Not again within the minute.
        run(rows, now0 + 30_000)
        assertTrue(archived.isEmpty())
        run(rows, now0 + 61_000)
        assertEquals(listOf("old"), archived)
    }

    @Test
    fun nothingUntilPinsAreKnown() {
        run(listOf(idle("old")), now0, pinsReady = false)
        run(listOf(idle("old")), now0 + 1, pinsReady = false)
        assertTrue(archived.isEmpty())
        // The first ready sweep still only looks.
        run(listOf(idle("old")), now0 + 2)
        assertTrue(archived.isEmpty())
    }

    @Test
    fun keepsPinnedOpenAsleepAndWorking() {
        val asleep = idle("asleep").copy(sleepUntilMs = now0 + day)
        val working = idle("working").copy(indicator = uniffi.zeron_core.ChatIndicator.WORKING)
        val pinned = idle("pinned", pinned = true)
        open.open(this, "open")
        val rows = listOf(asleep, working, idle("open"), idle("plain"))
        run(rows, now0, pinned = listOf(pinned))
        run(rows, now0 + 61_000, pinned = listOf(pinned))
        assertEquals(listOf("plain"), archived)
    }

    @Test
    fun offOrNeverArchivesNothing() {
        settings = ActivitySettings(on = false, archiveAfterDays = 1)
        run(listOf(idle("old")), now0)
        run(listOf(idle("old")), now0 + 61_000)
        settings = ActivitySettings(on = true, archiveAfterDays = 0)
        sweep.settingsChanged()
        run(listOf(idle("old")), now0 + 62_000)
        assertTrue(archived.isEmpty())
    }

    @Test
    fun settingsChangedSweepsAtOnce() {
        settings = ActivitySettings(on = false, archiveAfterDays = 1)
        run(listOf(idle("old")), now0)
        settings = ActivitySettings(on = true, archiveAfterDays = 1)
        sweep.settingsChanged()
        run(listOf(idle("old")), now0 + 1_000)
        assertEquals(listOf("old"), archived)
    }

    @Test
    fun broughtBackFromArchivedCountsAsJustMoved() {
        val x = idle("x", ageMs = 30 * day)
        // Archived when the first sweep looked…
        run(emptyList(), now0, archivedRows = listOf(x))
        // …and back now: remembered, not sent straight back.
        run(listOf(x), now0 + 61_000)
        assertTrue(archived.isEmpty())
        assertEquals(mapOf("x" to (now0 + 61_000) / 1000), back)
        // A day after it came back, it is idle long enough again.
        run(listOf(x), now0 + 61_000 + day + 61_000)
        assertEquals(listOf("x"), archived)
    }

    @Test
    fun forgetsChatsThatLeft() {
        back = mapOf("gone" to 1L)
        run(listOf(idle("here")), now0)
        assertEquals(emptyMap<String, Long>(), back)
    }

    @Test
    fun openChatsCountPerScreen() {
        val a = Any()
        val b = Any()
        open.open(a, "c")
        open.open(b, "c")
        open.close(a)
        assertTrue("c" in open)
        open.close(b)
        assertTrue("c" !in open)
    }
}
