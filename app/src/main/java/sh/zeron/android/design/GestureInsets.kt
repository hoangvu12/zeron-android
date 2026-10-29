package sh.zeron.android.design

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.ui.Modifier

/**
 * Navigation-mode-safe insets (#72). Android runs either gesture
 * navigation (edge swipes back, swipe up from the handle home) or 3-button
 * navigation, and the app must work in both:
 *
 * - [bottomChrome] is the bottom area interactive chrome (bars, the
 *   composer, sheets, menus, toasts with actions) must sit above: the
 *   navigation bar (the 3-button bar, or the gesture handle's strip) **and**
 *   the mandatory home-gesture area, which on gesture navigation can be
 *   taller than the handle's strip — a tap there goes to the system, not
 *   the app. On 3-button navigation it is the button bar.
 * - [inSystemGestureEdge] tells whether a touch starts in the left/right
 *   back-gesture edge (`WindowInsets.systemGestures`; 0 on 3-button
 *   navigation, wider with a high back sensitivity), where the system
 *   claims horizontal swipes as back. In-app horizontal drags that talk to
 *   a host (a visual's scrub) ignore touches starting there, so one swipe
 *   never both goes back and drives the visual.
 *
 * iOS's own edge swipe-back is the system back here (`NavStackHost`'s
 * `PredictiveBackHandler`); nothing in the app adds a custom edge pan, and
 * `systemGestureExclusion` is left for small handles only (the system caps
 * it at 200 dp per edge).
 */
val WindowInsets.Companion.bottomChrome: WindowInsets
    @Composable
    @NonRestartableComposable
    get() = navigationBars.union(mandatorySystemGestures.only(WindowInsetsSides.Bottom))

/** Bottom padding of [bottomChrome] — `navigationBarsPadding()` that also clears the home-gesture area. */
@Composable
fun Modifier.bottomChromePadding(): Modifier =
    windowInsetsPadding(WindowInsets.bottomChrome.only(WindowInsetsSides.Bottom))

/** Pure: whether window x [x] lies in the back-gesture edge of a [windowWidth]-wide window. */
fun inSystemGestureEdge(x: Float, windowWidth: Float, leftEdge: Float, rightEdge: Float): Boolean =
    x < leftEdge || x > windowWidth - rightEdge
