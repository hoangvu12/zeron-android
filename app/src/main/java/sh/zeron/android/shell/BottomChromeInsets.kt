package sh.zeron.android.shell

import sh.zeron.android.design.bottomChrome
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.zeron.android.design.BottomChromeInsets

/** The bottom bar's margins above the navigation bar. */
object BottomBarInsets {
    /** The bar's bottom margin on gesture nav (and with no nav inset): iOS's 21 pt. */
    val MinBottomGap = 21.dp

    /** The extra clearance above a 3-/2-button navigation bar. */
    val ButtonNavGap = 12.dp

    /** The last list row's clearance above the bar. */
    val ContentGap = 8.dp
}

/**
 * The bar's bottom margin: 12 dp above a 3-/2-button navigation bar
 * (`tappableBottom > 0`), otherwise `max(21, navBottom)` — the iOS 21 pt
 * margin that also never puts the bar over the gesture handle.
 * `tappableBottom` reads `WindowInsets.tappableElement`, which is 0 on
 * gesture navigation and the button bar's height otherwise.
 */
fun tabBarBottomGap(navBottom: Dp, tappableBottom: Dp): Dp =
    if (tappableBottom > 0.dp) {
        tappableBottom + BottomBarInsets.ButtonNavGap
    } else {
        maxOf(BottomBarInsets.MinBottomGap, navBottom)
    }

/**
 * The list padding under the one-row bar: `barBottom + 48 (the row) + 8`,
 * plus the IME above the navigation bar (`imeAboveNav`) the bar rides on
 * while its field is focused, so the filtered list clears the keyboard too.
 */
fun oneRowBarContentPadding(barBottom: Dp, imeAboveNav: Dp = 0.dp): Dp =
    barBottom + imeAboveNav + SessionsBottomBarMetrics.Height.dp + BottomBarInsets.ContentGap

/**
 * The shell's [BottomChromeInsets] from the live system insets:
 * `barBottom = tabBarBottomGap(navBottom, tappableBottom)` and
 * [oneRowBarContentPadding] of it. The bar is placed at the same
 * [BottomChromeInsets.barBottom], so the bar and the padding agree on both
 * navigation modes.
 */
@Composable
fun rememberBottomChromeInsets(): BottomChromeInsets {
    val density = LocalDensity.current
    // #72: the navigation bar and the mandatory home-gesture area, so the
    // bar never has a strip inside the home gesture on gesture navigation.
    val nav = with(density) { WindowInsets.bottomChrome.getBottom(density).toDp() }
    val tap = with(density) { WindowInsets.tappableElement.getBottom(density).toDp() }
    val ime = with(density) { WindowInsets.ime.exclude(WindowInsets.navigationBars).getBottom(density).toDp() }
    return remember(nav, tap, ime) {
        val bar = tabBarBottomGap(nav, tap)
        BottomChromeInsets(bar, oneRowBarContentPadding(bar, ime))
    }
}
