package sh.zeron.android.sessions

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.zeron_core.ChatIndicator

/**
 * Activity's rules — the port of iOS `ZeronTests/ActivityTests.swift` (the
 * desktop's `screens/shell/activity.luau`): shelves, order, the woke alarm,
 * Sleep's times, and what auto-archive leaves alone.
 */
class ActivityTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val locale = Locale.US

    private fun date(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun row(
        id: String,
        status: SessionRowVM.Status = SessionRowVM.Status.Idle,
        lastS: Long = 0,
        sleepUntilMs: Long? = null,
        wokeAtMs: Long? = null,
        waiting: Boolean = false,
        pinned: Boolean = false,
    ) = SessionRowVM(
        id = id, title = id, projectName = "p", hasProject = true, colorIndex = 0, harness = null,
        branch = null, pr = null, prNumber = null, status = status, timeLabel = "1h", unseen = false,
        pinned = pinned, sendFailed = false, waiting = waiting, sleepUntilMs = sleepUntilMs,
        wokeAtMs = wokeAtMs, lastActivityMs = lastS * 1000,
    )

    /** Formatters put a narrow no-break space before AM/PM. */
    private fun plain(s: String) = s.replace(' ', ' ').replace(' ', ' ')

    private fun presets(nowMs: Long) = ActivityList.presets(nowMs, zone, locale)

    // Shelves and order

    @Test
    fun listShelvesAndOrder() {
        val now = 10_000_000L
        val rows = listOf(
            row("old", lastS = 1_000),
            row("new", lastS = 5_000),
            row("woke", lastS = 500, wokeAtMs = 9_000_000),
            row("running", SessionRowVM.Status.Working, lastS = 9_500),
            row("waiting", SessionRowVM.Status.Working, lastS = 9_400, waiting = true),
            row("later", sleepUntilMs = 50_000_000),
            row("soon", SessionRowVM.Status.Completed, sleepUntilMs = 12_000_000),
        )
        val page = ActivityList.page(rows, workingShelf = true, nowMs = now)
        // Woke came back last: it stands at the top, with the alarm.
        assertEquals(listOf("woke", "new", "old"), page.list.map { it.id })
        assertEquals(SessionRowVM.Status.Woke, page.list.first().status)
        // Under way, waiting too, fold into Working.
        assertEquals(listOf("running", "waiting"), page.working.map { it.id })
        // Asleep by how soon it wakes; its corner says when.
        assertEquals(listOf("soon", "later"), page.asleep.map { it.id })
        assertEquals(listOf("34m", "12h"), page.asleep.map { it.timeLabel })
    }

    @Test
    fun workingShelfOffKeepsWorkInTheList() {
        val rows = listOf(row("a", SessionRowVM.Status.Working, lastS = 2), row("b", lastS = 1))
        val page = ActivityList.page(rows, workingShelf = false, nowMs = System.currentTimeMillis())
        assertEquals(listOf("a", "b"), page.list.map { it.id })
        assertTrue(page.working.isEmpty())
    }

    @Test
    fun wokeYieldsToAskingAndWork() {
        val at = System.currentTimeMillis()
        assertEquals(SessionRowVM.Status.Awaiting, ActivityList.decorate(row("a", SessionRowVM.Status.Awaiting, wokeAtMs = at), at).status)
        assertEquals(SessionRowVM.Status.Working, ActivityList.decorate(row("b", SessionRowVM.Status.Working, wokeAtMs = at), at).status)
        assertEquals(SessionRowVM.Status.Woke, ActivityList.decorate(row("c", SessionRowVM.Status.Completed, wokeAtMs = at), at).status)
        assertEquals(SessionRowVM.Status.Idle, ActivityList.decorate(row("d"), at).status)
    }

    @Test
    fun wokeCornerIsTheAlarm() {
        val corner = corner(row("a", SessionRowVM.Status.Woke))
        assertEquals(RowCorner.Woke, corner)
        assertEquals("Woke", corner?.word)
    }

    @Test
    fun waitingIsDrawnAsWorking() {
        assertEquals(SessionRowVM.Status.Working, statusOf(ChatIndicator.WAITING))
        assertEquals("Working", corner(row("a", SessionRowVM.Status.Working, waiting = true))?.word)
    }

    @Test
    fun duplicatesListOnce() {
        val page = ActivityList.page(listOf(row("a"), row("a")), workingShelf = true, nowMs = 0)
        assertEquals(listOf("a"), page.list.map { it.id })
    }

    // Sleep's times

    @Test
    fun presetsOnAWednesdayMorning() {
        val now = date(2026, 11, 4, 10) // a Wednesday
        val p = presets(now)
        assertEquals(listOf("hour", "hours", "evening", "tomorrow", "week"), p.map { it.id })
        assertEquals(
            listOf(date(2026, 11, 4, 11), date(2026, 11, 4, 13), date(2026, 11, 4, 18), date(2026, 11, 5, 9), date(2026, 11, 9, 9)),
            p.map { it.untilMs },
        )
        assertEquals(listOf("11:00 AM", "1:00 PM", "6:00 PM", "9:00 AM", "Mon 9:00 AM"), p.map { plain(it.detail) })
    }

    @Test
    fun presetsKeepOneAMinute() {
        // In three hours is this evening: one row.
        val p = presets(date(2026, 11, 4, 15))
        assertEquals(1, p.count { it.untilMs == date(2026, 11, 4, 18) })
    }

    @Test
    fun eveningOnlyWhileAhead() {
        val p = presets(date(2026, 11, 4, 19))
        assertFalse(p.any { it.id == "evening" })
        // Late enough that three hours on is tomorrow: it names the day.
        val late = presets(date(2026, 11, 4, 23))
        assertEquals("Thu 2:00 AM", late.first { it.id == "hours" }.detail.let(::plain))
    }

    @Test
    fun nextWeekFromAMondayIsAWeekOn() {
        val p = presets(date(2026, 11, 9, 8))
        assertEquals(date(2026, 11, 16, 9), p.first { it.id == "week" }.untilMs)
    }

    @Test
    fun presetsAcrossDaylightSaving() {
        // Nov 1 2026: clocks go back in Los Angeles. Tomorrow at 9 is 9 on the wall.
        val p = presets(date(2026, 10, 31, 22))
        val tomorrow = p.first { it.id == "tomorrow" }.untilMs
        assertEquals(9, java.time.Instant.ofEpochMilli(tomorrow).atZone(zone).hour)
    }

    @Test
    fun customStartsTomorrowAtNine() {
        assertEquals(date(2026, 11, 5, 9), ActivityList.customStart(date(2026, 11, 4, 22), zone))
    }

    @Test
    fun wakesIn() {
        assertEquals("1m", ActivityList.wakesIn(10_000, 0))
        assertEquals("45m", ActivityList.wakesIn(45 * 60_000L, 0))
        assertEquals("3h", ActivityList.wakesIn((3 * 3600 - 60) * 1000L, 0))
        assertEquals("2d", ActivityList.wakesIn(2 * 86_400_000L, 0))
    }

    // Auto-archive

    @Test
    fun autoArchiveLeavesWhatNeedsYouAlone() {
        val now = System.currentTimeMillis()
        assertTrue(ActivityList.archivesWhenIdle(row("idle"), open = false))
        assertTrue(ActivityList.archivesWhenIdle(row("done", SessionRowVM.Status.Completed), open = false))
        assertFalse(ActivityList.archivesWhenIdle(row("open"), open = true))
        assertFalse(ActivityList.archivesWhenIdle(row("pinned", pinned = true), open = false))
        assertFalse(ActivityList.archivesWhenIdle(row("asleep", sleepUntilMs = now), open = false))
        assertFalse(ActivityList.archivesWhenIdle(row("woke", wokeAtMs = now), open = false))
        for (status in listOf(SessionRowVM.Status.Working, SessionRowVM.Status.Awaiting, SessionRowVM.Status.Errored)) {
            assertFalse(ActivityList.archivesWhenIdle(row("busy", status), open = false))
        }
    }

    @Test
    fun archiveChoices() {
        assertEquals(
            listOf("Never", "After 1 Day", "After 3 Days", "After 7 Days", "After 14 Days", "After 30 Days"),
            ActivityList.archiveAfterChoices.map(ActivityList::archiveAfterLabel),
        )
    }
}
