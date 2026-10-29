package sh.zeron.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * [pillContent] (#28): the glyph and verbatim label of every banner — the
 * copy of iOS `StatusPill.update()` (`SessionViewController.swift:1020-1053`).
 * Plain JUnit 4: the mapping is pure Kotlin over the chrome banner.
 */
class StatusPillContentTest {

    private val now = 1_000_000L

    @Test
    fun sendingIsTheTrailer() {
        assertEquals(PillContent(PillGlyph.Trailer, "Sending to host…"), pillContent(SessionChrome.Banner.Sending, now))
    }

    @Test
    fun workingSince925kAt1MIsWorkingOneMinuteFifteen() {
        assertEquals(
            PillContent(PillGlyph.Trailer, "Working · 1m 15s"),
            pillContent(SessionChrome.Banner.Working(925_000L, "Working"), now),
        )
    }

    @Test
    fun workingInTheFirstSecondIsTheBareWord() {
        assertEquals(
            PillContent(PillGlyph.Trailer, "Working…"),
            pillContent(SessionChrome.Banner.Working(999_500L, "Working"), now),
        )
    }

    @Test
    fun notDeliveredIsTheDangerDot() {
        assertEquals(
            PillContent(PillGlyph.DotDanger, "Not delivered · Tap to retry"),
            pillContent(SessionChrome.Banner.NotDelivered, now),
        )
    }

    @Test
    fun failedIsTheDangerDotWithTheMessage() {
        assertEquals(
            PillContent(PillGlyph.DotDanger, "MacBook is offline — will send when it's back"),
            pillContent(SessionChrome.Banner.Failed("MacBook is offline — will send when it's back"), now),
        )
    }

    @Test
    fun editingIsTheInputDot() {
        assertEquals(
            PillContent(PillGlyph.DotInput, "Editing queued message · Tap to cancel"),
            pillContent(SessionChrome.Banner.Editing, now),
        )
    }

    @Test
    fun offlineIsTheIdleDot() {
        assertEquals(
            PillContent(PillGlyph.DotIdle, "Offline — sends are saved"),
            pillContent(SessionChrome.Banner.Offline, now),
        )
    }

    @Test
    fun reconnectingCountsDownToTheRedial() {
        assertEquals(
            PillContent(PillGlyph.DotIdle, "Reconnecting in 4s"),
            pillContent(SessionChrome.Banner.Reconnecting(1_004_000L), now),
        )
        // Truncated like Swift's Int64 division.
        assertEquals("Reconnecting in 4s", pillContent(SessionChrome.Banner.Reconnecting(1_004_999L), now).text)
    }

    @Test
    fun reconnectingNeverReadsBelowOneSecond() {
        assertEquals("Reconnecting in 1s", pillContent(SessionChrome.Banner.Reconnecting(1_000_300L), now).text)
        assertEquals("Reconnecting in 1s", pillContent(SessionChrome.Banner.Reconnecting(990_000L), now).text)
    }

    @Test
    fun noneHasNoPillContent() {
        val thrown = try {
            pillContent(SessionChrome.Banner.None, now)
            null
        } catch (expected: IllegalStateException) {
            expected
        }
        assertNotNull("None must throw (the caller keeps the last content instead)", thrown)
    }
}
