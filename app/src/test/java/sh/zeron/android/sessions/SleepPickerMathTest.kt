package sh.zeron.android.sessions

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sleep → Custom…'s picker rules (iOS `SleepPickerViewController`: the
 * system picker at 5-minute steps, from tomorrow at 9, never earlier than
 * a minute from now).
 */
class SleepPickerMathTest {

    private val zone = ZoneId.of("America/Los_Angeles")

    private fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()

    @Test
    fun opensTomorrowAtNine() {
        val now = ms(LocalDateTime.of(2026, 11, 16, 14, 7, 30))
        val state = SleepPickerState(now, zone)
        assertEquals(LocalDateTime.of(2026, 11, 17, 9, 0), state.selected)
        assertEquals(YearMonth.of(2026, 11), state.month)
        assertEquals(ms(LocalDateTime.of(2026, 11, 17, 9, 0)), state.untilMs)
    }

    @Test
    fun minimumIsAMinuteOnAtTheNextStep() {
        val now = ms(LocalDateTime.of(2026, 11, 16, 14, 7, 30))
        // 14:08:30 → 14:10.
        assertEquals(LocalDateTime.of(2026, 11, 16, 14, 10), SleepPickerMath.minimum(now, zone))
        assertEquals(
            LocalDateTime.of(2026, 11, 16, 14, 15),
            SleepPickerMath.ceilToStep(LocalDateTime.of(2026, 11, 16, 14, 15)),
        )
        assertEquals(
            LocalDateTime.of(2026, 11, 16, 15, 0),
            SleepPickerMath.ceilToStep(LocalDateTime.of(2026, 11, 16, 14, 55, 1)),
        )
    }

    @Test
    fun anEarlierChoiceLandsOnTheMinimum() {
        val now = ms(LocalDateTime.of(2026, 11, 16, 14, 7))
        val state = SleepPickerState(now, zone)
        state.selectDay(LocalDate.of(2026, 11, 16))
        // Today at 9 has passed: the minimum instead.
        assertEquals(LocalDateTime.of(2026, 11, 16, 14, 10), state.selected)
        state.setTime(20, 30)
        assertEquals(LocalDateTime.of(2026, 11, 16, 20, 30), state.selected)
        state.setTime(8, 0)
        assertEquals(LocalDateTime.of(2026, 11, 16, 14, 10), state.selected)
        assertFalse(state.isEnabled(LocalDate.of(2026, 11, 15)))
        assertTrue(state.isEnabled(LocalDate.of(2026, 11, 16)))
    }

    @Test
    fun confirmIsNeverEarlierThanAMinuteFromNow() {
        val now = ms(LocalDateTime.of(2026, 11, 16, 14, 7))
        assertEquals(now + 60_000, SleepPickerMath.confirm(LocalDateTime.of(2026, 11, 16, 14, 0), zone, now))
    }

    @Test
    fun monthsGoBackNoFurtherThanTheMinimum() {
        val now = ms(LocalDateTime.of(2026, 11, 16, 14, 7))
        val state = SleepPickerState(now, zone)
        assertFalse(state.canGoBack)
        state.previousMonth()
        assertEquals(YearMonth.of(2026, 11), state.month)
        state.nextMonth()
        assertEquals(YearMonth.of(2026, 12), state.month)
        assertTrue(state.canGoBack)
        state.selectDay(LocalDate.of(2026, 12, 24))
        assertEquals(LocalDateTime.of(2026, 12, 24, 9, 0), state.selected)
    }

    @Test
    fun monthGridPadsWholeWeeks() {
        // November 2026 starts on a Sunday.
        val sunday = SleepPickerMath.monthGrid(YearMonth.of(2026, 11), DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 11, 1), sunday.first())
        assertEquals(35, sunday.size)
        val monday = SleepPickerMath.monthGrid(YearMonth.of(2026, 11), DayOfWeek.MONDAY)
        assertEquals(6, monday.indexOf(LocalDate.of(2026, 11, 1)))
        assertNull(monday.first())
        assertEquals(0, monday.size % 7)
        assertEquals(
            listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            SleepPickerMath.weekdays(DayOfWeek.MONDAY),
        )
    }

    @Test
    fun summaryNamesTheChat() {
        assertEquals(
            "“Fix the build” moves to Asleep until Tuesday, Nov 17, 9:00 AM, on every device. " +
                "It wakes early if it asks you something, needs an approval or fails.",
            SleepPickerCopy.summary("Fix the build", "Tuesday, Nov 17, 9:00 AM"),
        )
        assertTrue(SleepPickerCopy.summary(null, "x").startsWith("This chat moves to Asleep until x"))
    }
}
