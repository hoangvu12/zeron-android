package sh.zeron.android.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.zeron.android.session.BottomStackMetrics
import sh.zeron.android.shell.tabBarBottomGap
import androidx.compose.ui.unit.dp

/**
 * #72's pure rules for gesture and 3-button navigation: the back-gesture
 * edge test, the tablet composer rest that never sinks into the bottom
 * chrome inset, and the bar gap over the (now mandatory-gesture-aware)
 * navigation inset.
 */
class NavModeInsetsTest {

    @Test
    fun touchesInsideTheBackGestureEdgesAreTheSystems() {
        // Gesture navigation: 24 px edges on a 1080 px window.
        assertTrue(inSystemGestureEdge(10f, 1080f, 24f, 24f))
        assertTrue(inSystemGestureEdge(1070f, 1080f, 24f, 24f))
        assertFalse(inSystemGestureEdge(540f, 1080f, 24f, 24f))
        // 3-button navigation: no edges, everything is the app's.
        assertFalse(inSystemGestureEdge(0f, 1080f, 0f, 0f))
        assertFalse(inSystemGestureEdge(1080f, 1080f, 0f, 0f))
    }

    @Test
    fun theTabletComposerRestNeverSinksBelowTheBottomChrome() {
        // A toolbar mid low enough that the rest sinks to the 8 px keyboard
        // gap; a 32 px home-gesture area holds it at H - 32 instead.
        val h = 1000f
        val sunk = BottomStackMetrics.stackBottomBesideToolbar(h, ime = 0f, toolbarMid = 27f, restHeight = 50f, gap = 8f)
        assertEquals(992f, sunk)
        val held = BottomStackMetrics.stackBottomBesideToolbar(h, ime = 0f, toolbarMid = 27f, restHeight = 50f, gap = 8f, floor = 32f)
        assertEquals(968f, held)
        // The keyboard still wins when it is higher.
        val lifted = BottomStackMetrics.stackBottomBesideToolbar(h, ime = 300f, toolbarMid = 27f, restHeight = 50f, gap = 8f, floor = 32f)
        assertEquals(692f, lifted)
    }

    @Test
    fun theBarClearsTheGestureAreaOrTheButtonBar() {
        // Gesture navigation: the bar sits at max(21, the bottom chrome inset).
        assertEquals(21.dp, tabBarBottomGap(navBottom = 16.dp, tappableBottom = 0.dp))
        assertEquals(32.dp, tabBarBottomGap(navBottom = 32.dp, tappableBottom = 0.dp))
        // 3-button navigation: 12 above the button bar.
        assertEquals(60.dp, tabBarBottomGap(navBottom = 48.dp, tappableBottom = 48.dp))
    }
}
