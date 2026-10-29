package sh.zeron.android.shell

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #26: the split's columns (`SplitRootController.swift:44-48, 89-97`) —
 * 360 preferred, the minimum 280 under 700 dp (the unfolded Duo) else 320
 * (an iPad mini upright gets 320), 400 at most, and the columns either side
 * of a separating hinge. Plain JUnit.
 */
class SidebarLayoutTest {

    @Test
    fun wideWindowsGetThePreferredWidth() {
        assertEquals(360f, sidebarLayout(1024f).sidebarWidth, 1e-3f)
        assertEquals(360f, sidebarLayout(1366f).sidebarWidth, 1e-3f)
        assertEquals(0f, sidebarLayout(1366f).detailGap, 1e-3f)
    }

    @Test
    fun anIpadMiniUprightHoldsTheMinimum() {
        assertEquals(320f, sidebarLayout(744f).sidebarWidth, 1e-3f)
    }

    @Test
    fun underSevenHundredTheMinimumIs280() {
        // The unfolded Duo (669) and a Galaxy Z Fold upright (673).
        assertEquals(280f, sidebarLayout(669f).sidebarWidth, 1e-3f)
        assertEquals(280f, sidebarLayout(673f).sidebarWidth, 1e-3f)
        assertEquals(280f, sidebarLayout(600f).sidebarWidth, 1e-3f)
        // From 700 the minimum is back to 320.
        assertEquals(320f, sidebarLayout(700f).sidebarWidth, 1e-3f)
    }

    @Test
    fun aSeparatingHingeSplitsTheColumnsAtTheHinge() {
        // Two 540 dp panes either side of a 34 dp hinge.
        val l = sidebarLayout(1114f, VerticalHinge(540.dp, 574.dp))
        assertEquals(540f, l.sidebarWidth, 1e-3f)
        assertEquals(34f, l.detailGap, 1e-3f)
    }

    @Test
    fun rtlPutsTheSidebarOnTheRightPane() {
        val l = sidebarLayout(1114f, VerticalHinge(500.dp, 534.dp), rtl = true)
        assertEquals(580f, l.sidebarWidth, 1e-3f)
        assertEquals(34f, l.detailGap, 1e-3f)
    }

    @Test
    fun aHingeLeavingAPaneTooNarrowIsIgnored() {
        val l = sidebarLayout(1024f, VerticalHinge(200.dp, 210.dp))
        assertEquals(360f, l.sidebarWidth, 1e-3f)
        assertEquals(0f, l.detailGap, 1e-3f)
    }

    @Test
    fun theDetailClearsTheHingeWhileTheSidebarShows() {
        val shown = splitGeometry(totalPx = 1114, sidebarPx = 540, fraction = 1f, targetVisible = true, gapPx = 34)
        assertEquals(574, shown.contentX)
        assertEquals(540, shown.contentWidth)
        val hidden = splitGeometry(totalPx = 1114, sidebarPx = 540, fraction = 0f, targetVisible = false, gapPx = 34)
        assertEquals(0, hidden.contentX)
        assertEquals(1114, hidden.contentWidth)
    }
}
