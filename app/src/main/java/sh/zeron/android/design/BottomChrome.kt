package sh.zeron.android.design

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * The top edge of the bottom bar actually on screen (ticket 14, consumer
 * of ticket 30): the Android port of iOS `chromeTop`
 * (`ios:Design/Toast.swift:29-45`, fed by `bottomBarFrame`) — the bar's
 * top, or the safe-area bottom, minus the pushed screen's own bottom bars.
 *
 * A **holder** rather than a plain `Dp` composition local because
 * [ToastHost] sits *above* the shell in the tree and CompositionLocals only
 * flow down: the host creates the state and provides it, and the shell
 * writes [top] from the bar's placed position (px → dp), and resets it to
 * [Dp.Unspecified] when the bar hides or leaves composition.
 *
 * [Dp.Unspecified] means "no bottom bar on screen" (a pushed Session screen,
 * a hidden bar); readers fall back to the safe-area bottom
 * ([rememberSafeBottom]).
 */
@Stable
class BottomChromeTopState {
    /** The chrome's top edge in dp from the window top; `Dp.Unspecified` when no chrome is on screen. */
    var top: Dp by mutableStateOf(Dp.Unspecified)
}

/** The shell's [BottomChromeTopState]; outside [ToastHost] a throwaway default. */
val LocalBottomChromeTop = staticCompositionLocalOf { BottomChromeTopState() }

/**
 * The bottom chrome's content insets (android-ui-parity #30): what the
 * screens under the phone's one-row bottom bar pad their lists with.
 * [barBottom] is `tabBarBottomGap` (the bar's bottom margin), the one value
 * both the bar's placement and the padding derive from; [contentPadding]
 * is `barBottom + 48 + 8` plus the IME the bar rides on
 * (`oneRowBarContentPadding`, in the shell package).
 *
 * `BottomChromeInsets.None` is the default outside a phone shell (tablet
 * sidebar, gallery, previews); tablet screens (105/107) provide their own.
 */
@Immutable
data class BottomChromeInsets(val barBottom: Dp, val contentPadding: Dp) {
    companion object {
        val None = BottomChromeInsets(0.dp, 0.dp)
    }
}

/** The shell's [BottomChromeInsets]; `BottomChromeInsets.None` outside [MainTabShell][sh.zeron.android.shell.MainTabShell]. */
val LocalBottomChrome = staticCompositionLocalOf { BottomChromeInsets.None }

/**
 * The bottom edge the toast must keep clear of: the window height minus
 * `max(systemBars ∪ displayCutout, ime)` bottom insets, in dp.
 *
 * iOS uses `window.safeAreaInsets.bottom` only, but its own comment says
 * "keep clear of the keyboard / home indicator either way" (`Toast.swift:42-43`),
 * and Zeron routes engine notices to the toast while the composer may be
 * focused (ticket 42) — so the IME inset counts. Do **not** use
 * `WindowInsets.safeDrawing.exclude(ime)`: it subtracts and collapses to 0
 * when the IME is up; the union-then-max here never does.
 *
 * Read in composition: the `WindowInsets` getters subscribe, so an IME
 * appearing recomposes the caller (the toast's anchor follows it with the
 * rise spring).
 */
@Composable
fun rememberSafeBottom(windowHeight: Dp): Dp {
    val density = LocalDensity.current
    // #72: plus the mandatory home-gesture area (a toast's Undo is tappable).
    val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout).union(WindowInsets.bottomChrome).getBottom(density)
    val ime = WindowInsets.ime.getBottom(density)
    val insetPx = max(bars, ime)
    return windowHeight - with(density) { insetPx.toDp() }
}
