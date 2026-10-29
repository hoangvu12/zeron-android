package sh.zeron.android.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import kotlin.math.abs

/**
 * The bottom bar's frame for one instant (android-ui-parity #30): whether it is **placed** at all, how it rides a navigation
 * transition, and how the incoming screen covers it. Pure values —
 * [ChromeVisibilityController.frame] derives them from the selected
 * [NavStack], and `Modifier.chromeVisibility` consumes them in the
 * placement / draw / layer phases only.
 *
 * The bar is **never removed from composition**: `placed = false` skips
 * its placement only, so its measured size, the search field's text and
 * the expansion spring survive. An unplaced node is not drawn, not
 * hit-tested and not in the a11y tree — the Android port of iOS
 * `hidesBottomBarWhenPushed`.
 *
 * [translationX]/[clipLeft]/[clipRight] are in **window px**: the chrome
 * layout fills the shell, so its local coordinates are the window's
 * (verified by `positionInRoot() == Offset.Zero` in [MainTabShell]).
 */
@Stable
data class ChromeFrame(
    val placed: Boolean,
    val translationX: Float = 0f,
    val alpha: Float = 1f,
    /** Window px; `±∞` = no clip. */
    val clipLeft: Float = Float.NEGATIVE_INFINITY,
    val clipRight: Float = Float.POSITIVE_INFINITY,
    /** Black overlay alpha over the chrome's own pixels (24's scrim). */
    val dim: Float = 0f,
) {
    companion object {
        val Hidden = ChromeFrame(placed = false)
        val Shown = ChromeFrame(placed = true)
    }
}

/**
 * Pure: the [ChromeFrame] of 24's [ChromeLayer] for the transition's
 * transforms [x] (`navLayerTransforms` of the current progress).
 *
 * `RidesBelow` (push to / pop from a `hidesBottomChrome` route) reproduces
 * 24's z-order "below, chrome, scrim, above" from outside the host: the
 * group travels with the below layer ([x.belowTx]), is covered by the
 * above screen's leading edge (the clip, window px: LTR `0..aboveTx`,
 * RTL `aboveTx + W..W`) and dimmed by the scrim ([x.scrimAlpha], black
 * `SrcAtop` over the chrome's own pixels). Under reduced motion the group
 * cross-fades instead (`1 − aboveAlpha` = `1 − s`), with no clip or dim.
 */
fun chromeFrame(
    layer: ChromeLayer,
    x: NavLayerTransforms,
    widthPx: Float,
    rtl: Boolean,
    reduceMotion: Boolean,
): ChromeFrame = when (layer) {
    ChromeLayer.Hidden -> ChromeFrame.Hidden
    ChromeLayer.Fixed -> ChromeFrame.Shown
    ChromeLayer.RidesAbove -> ChromeFrame(
        placed = true,
        translationX = x.aboveTx,
        alpha = x.aboveAlpha,
    )
    ChromeLayer.RidesBelow ->
        if (reduceMotion) {
            ChromeFrame(placed = true, alpha = 1f - x.aboveAlpha)
        } else {
            ChromeFrame(
                placed = true,
                translationX = x.belowTx,
                clipLeft = if (rtl) x.aboveTx + widthPx else 0f,
                clipRight = if (rtl) widthPx else x.aboveTx,
                dim = x.scrimAlpha,
            )
        }
}

/**
 * The bottom bar's visibility state holder (android-ui-parity #30): the
 * Android port of iOS `hidesBottomBarWhenPushed` on the Sessions stack as
 * one derived rule over [MainTabState.sessions] — **derived, never
 * flipped**: idle placement comes from `stack.top`, transition placement
 * from 24's `chromePlacement`, and the end of any transition re-derives it
 * (a cancelled predictive back ends hidden because the top is still the
 * pushed page, not because a flag was toggled twice).
 *
 * Every public getter is a snapshot read; call them from
 * layout/draw/layer lambdas or `snapshotFlow`, never in a composable body
 * that must not recompose per frame.
 */
@Stable
class ChromeVisibilityController internal constructor(private val tabs: MainTabState) {

    /** The bar layout's width in px (its `onSizeChanged`). */
    internal var widthPx by mutableFloatStateOf(0f)

    /** The layout direction, as seen by the shell (a `SideEffect`). */
    internal var rtl by mutableStateOf(false)

    /** Reduce Motion, as seen by the shell (a `SideEffect`). */
    internal var reduceMotion by mutableStateOf(false)

    /** The bar layout's top in root px (`onPlaced`). 0 on an edge-to-edge shell. */
    internal var layoutTopInRoot by mutableFloatStateOf(0f)

    /** Window-space bar top in dp, from the bar's `onPlaced` + [layoutTopInRoot]. NaN until first placement. */
    internal var chromeTopInRoot by mutableFloatStateOf(Float.NaN)

    /** The Sessions stack — the one visibility derives from. */
    val stack: NavStack
        get() = tabs.sessions

    /**
     * The value for ticket 14's [sh.zeron.android.design.BottomChromeTopState.top],
     * in dp; `NaN` = no bar on screen ("[Dp.Unspecified]"). The bar rides
     * on the IME through its own padding, so its placed top already
     * includes the lift.
     */
    fun publishedChromeTop(): Float =
        if (!frame().placed || chromeTopInRoot.isNaN()) Float.NaN else chromeTopInRoot

    /** Snapshot read of the current frame. Call from layout/draw lambdas or `snapshotFlow`. */
    fun frame(): ChromeFrame {
        val s = stack
        val t = s.transition
            ?: return if (s.hidesChrome(s.top)) ChromeFrame.Hidden else ChromeFrame.Shown
        val push = t.kind == NavKind.Push
        val below = if (push) t.from else t.to
        val above = if (push) t.to else t.from
        val layer = chromePlacement(
            showBelow = !s.hidesChrome(below),
            showAbove = !s.hidesChrome(above),
        )
        val x = navLayerTransforms(t.kind, t.progress, widthPx, rtl, reduceMotion)
        return chromeFrame(layer, x, widthPx, rtl, reduceMotion)
    }

    /**
     * From the bar's `onPlaced` (dp, shell-relative): writes
     * `layoutTopInRoot.toDp() + chromeTopDp` into [chromeTopInRoot] when it
     * moved ≥ 0.5 px — the IME ride moves it every frame, and the
     * publication (`snapshotFlow`) re-emits from the state write alone.
     */
    internal fun updateChromeTop(chromeTopDp: Float, density: Density) {
        val topDp = with(density) { layoutTopInRoot.toDp() }.value + chromeTopDp
        if (chromeTopInRoot.isNaN() || abs(topDp - chromeTopInRoot) * density.density >= 0.5f) {
            chromeTopInRoot = topDp
        }
    }
}

/** [ChromeVisibilityController] in composition, over the shell's [MainTabState]. */
@Composable
fun rememberChromeVisibilityController(tabs: MainTabState): ChromeVisibilityController =
    remember(tabs) { ChromeVisibilityController(tabs) }

/**
 * Placement + ride + cover + dim for the bottom bar (apply to the shell's
 * bar layout, which fills the shell so the local coordinates are the
 * window's). The chain, outermost first:
 *
 * 1. `onSizeChanged` — feeds the controller the window width;
 * 2. `layout` — the group is **always measured** (composition, measured
 *    size and inner state, the field's text and focus included, survive)
 *    and placed only when
 *    [ChromeFrame.placed]: unplaced = not drawn, not hit-tested, not in
 *    the a11y tree;
 * 3. `drawWithContent` — the cover by the incoming screen, a window-px
 *    clip at above's leading edge;
 * 4. `graphicsLayer` — the ride (`translationX`), the reduced-motion group
 *    alpha, and an offscreen layer while dimmed;
 * 5. `drawWithContent` — 24's scrim, black `SrcAtop` over the chrome's own
 *    pixels only (inside the offscreen layer, so it never tints the
 *    content below);
 * 6. `testTag("bottom-chrome")`.
 *
 * Every state read happens in placement/draw/layer lambdas: no
 * recomposition per frame.
 */
fun Modifier.chromeVisibility(controller: ChromeVisibilityController): Modifier = this
    .onSizeChanged { controller.widthPx = it.width.toFloat() }
    .layout { measurable, constraints ->
        // ALWAYS measured: the bar stays composed and sized.
        val p = measurable.measure(constraints)
        layout(p.width, p.height) {
            // Not placed = not drawn, not hit-tested, not in semantics.
            if (controller.frame().placed) p.place(0, 0)
        }
    }
    .drawWithContent {
        // The cover by "above", in window px (this node's coordinates are
        // the window's: the layout fills the edge-to-edge shell).
        val f = controller.frame()
        if (f.clipLeft.isInfinite() && f.clipRight.isInfinite()) {
            drawContent()
        } else {
            clipRect(
                left = f.clipLeft.coerceAtLeast(0f),
                top = 0f,
                right = f.clipRight.coerceAtMost(size.width),
                bottom = size.height,
            ) {
                this@drawWithContent.drawContent()
            }
        }
    }
    .graphicsLayer {
        val f = controller.frame()
        translationX = f.translationX
        alpha = f.alpha
        // The SrcAtop dim below must not punch through to the window, so
        // the layer is offscreen exactly while a dim is drawn (as iOS sets
        // no layer otherwise).
        compositingStrategy = if (f.dim > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }
    .drawWithContent {
        drawContent()
        val dim = controller.frame().dim
        if (dim > 0f) {
            drawRect(Color.Black.copy(alpha = dim), blendMode = BlendMode.SrcAtop)
        }
    }
    .testTag("bottom-chrome")

/**
 * Consumes all pointer input over the window while the Sessions stack is
 * transitioning — UIKit disables interaction during transitions, and 24
 * blocks its own host the same way. Reads `transition` in composition, so
 * it recomposes only at start and end. Back gestures are not pointer
 * events, so a predictive back drives the transition underneath
 * unimpeded. [modifier] should make it cover the window
 * (`Modifier.matchParentSize()` in the shell's `Box`).
 */
@Composable
fun ChromeTransitionBlocker(controller: ChromeVisibilityController, modifier: Modifier = Modifier) {
    if (controller.stack.transition != null) {
        Box(
            modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        for (change in event.changes) change.consume()
                    }
                }
            },
        )
    }
}
