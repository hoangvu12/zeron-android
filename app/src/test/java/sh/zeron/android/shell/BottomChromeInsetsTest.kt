package sh.zeron.android.shell

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bottom bar's insets math, plain JUnit: [tabBarBottomGap] in both
 * navigation modes and [oneRowBarContentPadding] = `barBottom + IME + 48 + 8`
 * over it —
 *
 * | nav bottom | tappable | barBottom | contentPadding |
 * |---|---|---|---|
 * | 24 dp (gesture) | 0 | 24 | 80 |
 * | 0 / 21 dp | 0 | 21 | 77 |
 * | 48 dp (3-button) | 48 | 60 | 116 |
 */
class BottomChromeInsetsTest {

    @Test
    fun gestureNavBarBottomIsMax21NavBottom() {
        assertEquals(21.dp, tabBarBottomGap(0.dp, 0.dp))
        assertEquals(21.dp, tabBarBottomGap(21.dp, 0.dp))
        assertEquals(24.dp, tabBarBottomGap(24.dp, 0.dp))
    }

    @Test
    fun threeButtonNavSitsTwelveAboveTheTappableBar() {
        assertEquals(60.dp, tabBarBottomGap(48.dp, 48.dp))
        for (tappable in listOf(24.dp, 36.dp, 48.dp)) {
            val bar = tabBarBottomGap(24.dp, tappable)
            assertTrue("barBottom $bar must sit ≥ 12 above a $tappable bar", bar >= tappable + 12.dp)
        }
    }

    @Test
    fun contentPaddingClearsTheRowByEight() {
        assertEquals(80.dp, oneRowBarContentPadding(tabBarBottomGap(24.dp, 0.dp)))
        assertEquals(77.dp, oneRowBarContentPadding(tabBarBottomGap(0.dp, 0.dp)))
        assertEquals(116.dp, oneRowBarContentPadding(tabBarBottomGap(48.dp, 48.dp)))
    }

    @Test
    fun contentPaddingAddsTheImeTheBarRidesOn() {
        assertEquals(380.dp, oneRowBarContentPadding(24.dp, imeAboveNav = 300.dp))
    }
}
