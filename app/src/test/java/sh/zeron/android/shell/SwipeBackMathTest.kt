package sh.zeron.android.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-app swipe-back's pure rules (#135), plain JUnit: the start area
 * in both navigation modes, the claim lock (LTR and RTL), the progress
 * mapping and the commit/cancel table.
 */
class SwipeBackMathTest {

    private val slop = 16f

    // ------------------------------------------------------------ start area

    @Test
    fun threeButtonNavStartsAnywhereAboveTheBar() {
        // 3-button: no side gesture edges, the 48 dp bar at the bottom.
        assertTrue(swipeBackMayStart(0f, 100f, 1080f, 2400f, 0f, 0f, 144f))
        assertTrue(swipeBackMayStart(1079f, 100f, 1080f, 2400f, 0f, 0f, 144f))
        assertTrue(swipeBackMayStart(540f, 2255f, 1080f, 2400f, 0f, 0f, 144f))
        assertFalse(swipeBackMayStart(540f, 2300f, 1080f, 2400f, 0f, 0f, 144f))
    }

    @Test
    fun gestureNavLeavesBothBackEdgesToTheSystem() {
        val w = 1080f
        val h = 2400f
        assertFalse(swipeBackMayStart(10f, 800f, w, h, 72f, 72f, 96f))
        assertFalse(swipeBackMayStart(w - 10f, 800f, w, h, 72f, 72f, 96f))
        assertTrue(swipeBackMayStart(72f, 800f, w, h, 72f, 72f, 96f))
        assertTrue(swipeBackMayStart(540f, 800f, w, h, 72f, 72f, 96f))
        // The home-gesture strip.
        assertFalse(swipeBackMayStart(540f, h - 20f, w, h, 72f, 72f, 96f))
    }

    // ----------------------------------------------------------------- lock

    @Test
    fun insideTheSlopIsUndecided() {
        assertEquals(SwipeBackLock.Undecided, swipeBackLock(16f, 0f, slop, rtl = false))
        assertEquals(SwipeBackLock.Undecided, swipeBackLock(10f, -12f, slop, rtl = false))
    }

    @Test
    fun clearlyTowardTheTrailingEdgeClaims() {
        assertEquals(SwipeBackLock.Pop, swipeBackLock(17f, 0f, slop, rtl = false))
        assertEquals(SwipeBackLock.Pop, swipeBackLock(30f, 19f, slop, rtl = false))
    }

    @Test
    fun verticalDiagonalOrBackwardRejects() {
        assertEquals(SwipeBackLock.Reject, swipeBackLock(0f, 17f, slop, rtl = false))
        assertEquals(SwipeBackLock.Reject, swipeBackLock(30f, 20f, slop, rtl = false))
        assertEquals(SwipeBackLock.Reject, swipeBackLock(-17f, 0f, slop, rtl = false))
        assertEquals(SwipeBackLock.Reject, swipeBackLock(10f, 17f, slop, rtl = false))
    }

    @Test
    fun rtlMirrorsTheDirection() {
        assertEquals(SwipeBackLock.Pop, swipeBackLock(-17f, 0f, slop, rtl = true))
        assertEquals(SwipeBackLock.Reject, swipeBackLock(17f, 0f, slop, rtl = true))
    }

    // ------------------------------------------------------------- progress

    @Test
    fun progressFollowsTheFingerAndClamps() {
        assertEquals(0.25f, swipeBackProgress(250f, 1000f, rtl = false), 1e-6f)
        assertEquals(0f, swipeBackProgress(-50f, 1000f, rtl = false), 1e-6f)
        assertEquals(1f, swipeBackProgress(1500f, 1000f, rtl = false), 1e-6f)
        assertEquals(0.25f, swipeBackProgress(-250f, 1000f, rtl = true), 1e-6f)
        assertEquals(0f, swipeBackProgress(250f, 1000f, rtl = true), 1e-6f)
        assertEquals(0f, swipeBackProgress(250f, 0f, rtl = false), 1e-6f)
    }

    // --------------------------------------------------------------- release

    @Test
    fun aFlingDecidesByItsDirection() {
        val fling = 900f
        assertTrue(swipeBackCommits(0.1f, 900f, fling))
        assertFalse(swipeBackCommits(0.9f, -900f, fling))
    }

    @Test
    fun aSlowReleaseCommitsPastHalf() {
        val fling = 900f
        assertFalse(swipeBackCommits(0.49f, 0f, fling))
        assertTrue(swipeBackCommits(0.5f, 0f, fling))
        assertTrue(swipeBackCommits(0.7f, -899f, fling))
        assertFalse(swipeBackCommits(0.3f, 899f, fling))
    }
}
