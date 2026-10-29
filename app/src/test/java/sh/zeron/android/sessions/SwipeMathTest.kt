package sh.zeron.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SwipeMath] (android-ui-parity #44), plain JVM — no Robolectric: the
 * lock, the release table and the pane spans are pure functions, and the
 * tokens are Compose value types ([dp], [Color], [IosSpring]) that touch
 * no framework. The settle spring's settle-matched stiffness (480.90 from
 * 0.35 s / ζ 0.90) is asserted through [SwipeTokens.Settle] — never a
 * pasted literal in a consumer.
 */
class SwipeMathTest {

    // ------------------------------------------------------------ the lock

    @Test
    fun travelInsideTheSlopIsUndecided() {
        assertEquals(LockDecision.Undecided, lockDecision(0f, 0f, 8f) { true })
        assertEquals(LockDecision.Undecided, lockDecision(5f, 6f, 8f) { true })
        // Exactly at the slop is still inside it (<=).
        assertEquals(LockDecision.Undecided, lockDecision(0f, 8f, 8f) { true })
        assertEquals(LockDecision.Undecided, lockDecision(8f, 0f, 8f) { true })
    }

    @Test
    fun a45DegreeDragIsRejected() {
        assertEquals(LockDecision.Reject, lockDecision(30f, 30f, 8f) { true })
        // Slightly better than 45° is still not horizontal enough.
        assertEquals(LockDecision.Reject, lockDecision(14f, 10f, 8f) { true })
    }

    @Test
    fun aClearlyHorizontalDragIsClaimed() {
        // 16 > 1.5 · 10 = 15.
        assertEquals(LockDecision.Horizontal, lockDecision(16f, 10f, 8f) { true })
        assertEquals(LockDecision.Horizontal, lockDecision(24f, 0f, 8f) { true })
    }

    @Test
    fun aDirectionWithNoActionsGivesTheGestureUp() {
        // Archived rows cannot move toward the leading side.
        val canGo: (Int) -> Boolean = { it < 0 }
        assertEquals(LockDecision.Reject, lockDecision(20f, 0f, 8f, canGo))
        assertEquals(LockDecision.Horizontal, lockDecision(-20f, 0f, 8f, canGo))
    }

    @Test
    fun aRowThatCannotSwipeAtAllNeverClaims() {
        assertEquals(LockDecision.Reject, lockDecision(40f, 0f, 8f) { false })
    }

    // ---------------------------------------------- one side at a time (#136)

    @Test
    fun aClosedRowTakesTheSideOfTheClaimingTravel() {
        assertEquals(SwipeSide.Leading, swipeSide(base = 0f, logicalDx = 30f))
        assertEquals(SwipeSide.Trailing, swipeSide(base = 0f, logicalDx = -30f))
    }

    @Test
    fun anOpenRowKeepsItsSideWhicheverWayTheDragGoes() {
        // Leading open, dragged left: still the leading side (it closes).
        assertEquals(SwipeSide.Leading, swipeSide(base = 200f, logicalDx = -500f))
        assertEquals(SwipeSide.Trailing, swipeSide(base = -400f, logicalDx = 900f))
    }

    @Test
    fun anOppositeDragClosesTheOpenSideAndStopsAtZero() {
        // Leading open at 200, dragged 500 left: 0, never −300 (no
        // trailing actions peeking in the same gesture).
        assertEquals(0f, clampToSide(200f - 500f, SwipeSide.Leading, 1000f))
        assertEquals(0f, clampToSide(-400f + 900f, SwipeSide.Trailing, 1000f))
        // Within the side the finger is followed 1:1, up to the row width.
        assertEquals(120f, clampToSide(120f, SwipeSide.Leading, 1000f))
        assertEquals(-1000f, clampToSide(-1300f, SwipeSide.Trailing, 1000f))
    }

    @Test
    fun onlyTheOpenSideLaysOutPanes() {
        // Leading open: the trailing side sees a negative reveal → no panes.
        assertTrue(paneSpans(sideReveal(148f, atLeading = false), 2, 74f, 0f).isEmpty())
        assertEquals(1, paneSpans(sideReveal(74f, atLeading = true), 1, 74f, 0f).size)
        // Trailing open: the leading side places nothing.
        assertTrue(paneSpans(sideReveal(-148f, atLeading = true), 1, 74f, 0f).isEmpty())
        assertEquals(2, paneSpans(sideReveal(-148f, atLeading = false), 2, 74f, 0f).size)
    }

    @Test
    fun aTouchOnAnotherRowWhileOneIsOpenNeverStartsASwipe() {
        assertTrue(touchStartsSwipe(openId = null, id = "a"))
        assertTrue(touchStartsSwipe(openId = "a", id = "a"))
        assertEquals(false, touchStartsSwipe(openId = "a", id = "b"))
    }

    @Test
    fun theFullSwipeThresholdIsSixtyPercentOfTheWidth() {
        assertTrue(isFullSwipe(600f, 1000f))
        assertTrue(isFullSwipe(-600f, 1000f))
        assertEquals(false, isFullSwipe(599f, 1000f))
        assertEquals(false, isFullSwipe(-599f, 1000f))
    }

    // --------------------------------------------------------- the release

    private fun release(
        x: Float,
        v: Float,
        width: Float = 1000f,
        button: Float = 200f,
        lead: Int = 1,
        trail: Int = 2,
        fling: Float = 900f,
    ): SwipeRelease = swipeRelease(x, v, width, button, lead, trail, fling)

    @Test
    fun pastTheFullSwipeFractionCommits() {
        val full = SwipeTokens.FullSwipeFraction * 1000f
        // The exact 0.6 boundary is a commit, both sides.
        assertEquals(SwipeRelease.Commit(leading = true), release(full, 0f))
        assertEquals(SwipeRelease.Commit(leading = false), release(-full, 0f))
    }

    @Test
    fun leadingReleasesByPositionAndFling() {
        // Open = 200, half = 100.
        assertEquals(SwipeRelease.Settle(200f), release(250f, 0f))
        assertEquals(SwipeRelease.Settle(0f), release(90f, 0f))
        // Fling: toward opening opens, toward closing closes.
        assertEquals(SwipeRelease.Settle(200f), release(90f, 900f))
        assertEquals(SwipeRelease.Settle(200f), release(90f, 1500f))
        assertEquals(SwipeRelease.Settle(0f), release(250f, -900f))
    }

    @Test
    fun trailingMirrorsWithItsOwnOpenWidth() {
        // Open = 400 (two buttons), half = 200.
        assertEquals(SwipeRelease.Settle(-400f), release(-300f, 0f))
        assertEquals(SwipeRelease.Settle(0f), release(-150f, 0f))
        assertEquals(SwipeRelease.Settle(-400f), release(-150f, -900f))
        assertEquals(SwipeRelease.Settle(0f), release(-300f, 900f))
    }

    @Test
    fun aZeroOffsetCloses() {
        assertEquals(SwipeRelease.Settle(0f), release(0f, 0f))
        assertEquals(SwipeRelease.Settle(0f), release(0f, 1200f))
        assertEquals(SwipeRelease.Settle(0f), release(0f, -1200f))
    }

    @Test
    fun aSideWithNoActionsNeverCommitsOrOpens() {
        // 0.6 of the width, but nothing to commit: close.
        assertEquals(SwipeRelease.Settle(0f), release(800f, 0f, lead = 0))
        assertEquals(SwipeRelease.Settle(0f), release(-800f, 0f, trail = 0))
        // Nor a fling toward it.
        assertEquals(SwipeRelease.Settle(0f), release(300f, 900f, lead = 0))
    }

    @Test
    fun oneActionPerSideScalesTheOpenWidth() {
        // Trailing with one action: open = 200, half = 100.
        assertEquals(SwipeRelease.Settle(-200f), release(-150f, 0f, trail = 1))
        assertEquals(SwipeRelease.Settle(0f), release(-90f, 0f, trail = 1))
    }

    // ---------------------------------------------------------- the spans

    @Test
    fun noActionsMeansNoPanes() {
        assertTrue(paneSpans(200f, 0, 74f, 0f).isEmpty())
    }

    @Test
    fun onePaneSpansTheWholeGap() {
        val spans = paneSpans(100f, 1, 74f, 0f)
        assertEquals(listOf(PaneSpan(0, 0f, 100f, 26f)), spans)
    }

    @Test
    fun twoPanesSplitTheOpenWidthEqually() {
        // The natural open: r = 148 = 2 × 74.
        val spans = paneSpans(148f, 2, 74f, 0f)
        assertEquals(listOf(PaneSpan(0, 0f, 74f, 0f), PaneSpan(1, 74f, 148f, 74f)), spans)
    }

    @Test
    fun aWiderRevealKeepsTheIconSlotsAtTheInnerEdges() {
        // r = 300 unarmed: 150/150, each slot 74 wide at the pane's inner
        // edge — pane 0's slot [76, 150], pane 1's [226, 300].
        val spans = paneSpans(300f, 2, 74f, 0f)
        assertEquals(PaneSpan(0, 0f, 150f, 76f), spans[0])
        assertEquals(PaneSpan(1, 150f, 300f, 226f), spans[1])
    }

    @Test
    fun armingGrowsPaneZeroToTheWholeGap() {
        val spans = paneSpans(300f, 2, 74f, 1f)
        assertEquals(PaneSpan(0, 0f, 300f, 226f), spans[0])
        // Pane 1 collapses; its slot collapses with it.
        assertEquals(PaneSpan(1, 300f, 300f, 300f), spans[1])
    }

    @Test
    fun armingHalfwaySplitsTheDifference() {
        val spans = paneSpans(300f, 2, 74f, 0.5f)
        assertEquals(PaneSpan(0, 0f, 225f, 151f), spans[0])
        assertEquals(PaneSpan(1, 225f, 300f, 226f), spans[1])
    }

    @Test
    fun aNarrowPaneCentresItsIconInTheWholePane() {
        // Pane 1 unarmed with r = 100: 50 wide, slot = 50.
        val spans = paneSpans(100f, 2, 74f, 0f)
        assertEquals(PaneSpan(1, 50f, 100f, 50f), spans[1])
    }

    // ---------------------------------------------------------- the token

    @Test
    fun theSettleSpringIsTheTicketToken() {
        // 0.35 s / ζ 0.90 → ω = 9.2334/0.35·… settle-matched stiffness
        // 480.90 (damping ≥ 1 branch). The value is computed by IosSpring;
        // consumers read it through the token, never as a literal.
        assertEquals(480.90f, SwipeTokens.Settle.stiffness, 0.02f)
        assertEquals(0.90f, SwipeTokens.Settle.damping)
        assertEquals(0.35, SwipeTokens.Settle.durationSec, 1e-9)
    }
}
