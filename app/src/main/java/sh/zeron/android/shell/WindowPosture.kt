package sh.zeron.android.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import sh.zeron.android.notify.findActivity

/**
 * A vertical hinge across the window, in dp from the window's left edge
 * ([start] … [end]; equal for a seamless fold). Only kept when it splits
 * the window into two panes (`isSeparating`) — the Android form of the
 * iPhone Duo's uneven safe areas (#942): the split puts its sidebar on the
 * left pane and starts the session column past the hinge.
 */
@Immutable
data class VerticalHinge(val start: Dp, val end: Dp)

/**
 * The window's folding features, as the split needs them.
 *
 * - [hasFold]: the window lies on a foldable's inner screen — it reports a
 *   `FoldingFeature` in any state (a book foldable flat or half-opened).
 *   The outer screen of a folded device reports none. Feeds
 *   [chooseRoot]'s [FoldableSplitBreakpoint].
 * - [verticalHinge]: a separating vertical hinge, or null (flat seamless
 *   folds, horizontal hinges — tabletop — and devices without one).
 */
@Immutable
data class WindowPosture(val hasFold: Boolean, val verticalHinge: VerticalHinge?) {
    companion object {
        val None = WindowPosture(hasFold = false, verticalHinge = null)
    }
}

/** The window's [WindowPosture]; [WindowPosture.None] outside the app root (galleries, previews, tests). */
val LocalWindowPosture = staticCompositionLocalOf { WindowPosture.None }

/**
 * Pure: the posture of one [WindowLayoutInfo]'s folding features, their
 * window-pixel bounds converted with [density]. A vertical hinge is kept
 * only when the platform says it separates the window.
 */
internal fun windowPosture(folds: List<FoldingFeature>, density: Density): WindowPosture {
    if (folds.isEmpty()) return WindowPosture.None
    val hinge = folds.firstOrNull { it.isSeparating && it.orientation == FoldingFeature.Orientation.VERTICAL }
        ?.let { fold ->
            with(density) { VerticalHinge(fold.bounds.left.toDp(), fold.bounds.right.toDp()) }
        }
    return WindowPosture(hasFold = true, verticalHinge = hinge)
}

/**
 * The live [WindowPosture] of the hosting activity's window, from
 * Jetpack WindowManager's `WindowInfoTracker` — updated on fold, unfold,
 * hinge-angle and multi-window changes. [WindowPosture.None] without an
 * activity (previews) and until the first layout info arrives.
 */
@Composable
fun rememberWindowPosture(): WindowPosture {
    val context = LocalContext.current
    val density = LocalDensity.current
    var posture by remember { mutableStateOf(WindowPosture.None) }
    LaunchedEffect(context, density) {
        val activity = context.findActivity() ?: return@LaunchedEffect
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
            posture = windowPosture(info.displayFeatures.filterIsInstance<FoldingFeature>(), density)
        }
    }
    return posture
}
