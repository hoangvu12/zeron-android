package sh.zeron.android.newsession

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The New Project nav-bar title placement ([navTitleX], UIKit nav-bar
 * behaviour): centred on the sheet when it fits, otherwise slid just
 * enough to clear the button it would overlap. Plain JUnit (kept from the
 * retired screenshot suite).
 */
class NavTitleXTest {

    @Test
    fun centresOnTheSheetWhenItFits() {
        assertEquals(400, navTitleX(width = 1000, titleWidth = 200, regionStart = 68, regionEnd = 800))
    }

    @Test
    fun slidesRightWhenTheCloseButtonWouldOverlap() {
        assertEquals(68, navTitleX(width = 1000, titleWidth = 900, regionStart = 68, regionEnd = 990))
    }

    @Test
    fun slidesLeftWhenTheDeviceButtonWouldOverlap() {
        assertEquals(300, navTitleX(width = 1000, titleWidth = 300, regionStart = 68, regionEnd = 600))
    }
}
