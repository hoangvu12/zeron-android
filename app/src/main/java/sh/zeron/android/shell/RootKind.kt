package sh.zeron.android.shell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowHeightSizeClass
import androidx.window.core.layout.WindowSizeClass

/**
 * The three window roots (android-ui-parity #35): which one is on screen
 * is a pure function of the app state and the window width —
 * [chooseRoot]. The swap between the two *groups* is the 350 ms
 * cross-fade (`SceneDelegate.showRoot`); Phone ↔ Split is an in-place
 * switch inside the shell group (ticket 108's collapse,
 * `SplitRootController.swift:15-16,49`).
 */
enum class RootKind { Welcome, Phone, Split }

/**
 * What the app root needs from the app model, derived in
 * `ZeronApplication` from the core load and the session holder:
 *
 * - [Loading] — nothing is drawn, the platform splash is held
 *   (`FirstFrameHold`); Android reads the stored account asynchronously,
 *   and a signed-in user must not see the welcome root flash before the
 *   state is known (iOS reads the Keychain synchronously in
 *   `AppModel.init`);
 * - [CoreFailed] — the native core could not be loaded; the diagnostic
 *   screen replaces the app;
 * - [Ready] — the stored account is known; [Ready.signedIn] is "a
 *   `CoreClient` exists" (`AppModel.isSignedIn`), and flips with sign-in,
 *   sign-out and `AuthExpired`.
 */
sealed interface RootInput {
    data object Loading : RootInput
    data class CoreFailed(val reason: String) : RootInput
    data class Ready(val signedIn: Boolean) : RootInput
}

/**
 * The single owner of the split breakpoint, measured on the real root
 * width. Inclusive; no hysteresis.
 *
 * iOS splits whenever the window is regular in both directions
 * (`SplitRootController.swift:12-18`): every iPad, and the unfolded iPhone
 * Duo, which is only 669 pt wide in portrait — hence its 280 pt sidebar
 * minimum below 700 ([SidebarMinWidthNarrow]). Android has no size
 * classes of its own, so this is ~700 dp (Zeron Android's `TabletShell`
 * uses the same): a window that wide is a tablet or an unfolded foldable.
 */
val SplitBreakpoint: Dp = 700.dp

/**
 * An unfolded foldable splits from here (WindowSizeClass's medium width):
 * the Duo's case — a book foldable's inner screen is ~670 dp wide in
 * portrait (a Galaxy Z Fold), under [SplitBreakpoint], and must still
 * split like the unfolded Duo. "Foldable" is a window that reports a
 * `FoldingFeature` ([WindowPosture.hasFold]); a plain 600 dp tablet or a
 * multi-window slice of that width keeps the phone layout.
 */
val FoldableSplitBreakpoint: Dp = 600.dp

/**
 * Below this height the window is a phone held sideways (a Pixel 7 in
 * landscape is ~915 × 400 dp), which keeps the phone layout however wide
 * it is — iOS's compact height class, Android's compact
 * `WindowHeightSizeClass` (the same 480 dp bound).
 */
val CompactHeight: Dp = 480.dp

/**
 * The root on screen. Signed out wins over the size: the welcome root
 * never splits. A compact-height window (a landscape phone) stays on the
 * phone layout; otherwise the width decides — [SplitBreakpoint], or
 * [FoldableSplitBreakpoint] when the window has a fold ([foldable]). All
 * of it is re-evaluated live (fold/unfold, multi-window resize, rotation)
 * — a change never goes through the cross-fade.
 */
fun chooseRoot(
    signedIn: Boolean,
    width: Dp,
    height: Dp = Dp.Infinity,
    foldable: Boolean = false,
): RootKind = when {
    !signedIn -> RootKind.Welcome
    isCompactHeight(width, height) -> RootKind.Phone
    width >= SplitBreakpoint -> RootKind.Split
    foldable && width >= FoldableSplitBreakpoint -> RootKind.Split
    else -> RootKind.Phone
}

/** WindowSizeClass's compact height (< 480 dp); an unbounded height never is. */
internal fun isCompactHeight(width: Dp, height: Dp): Boolean {
    if (height == Dp.Infinity) return false
    if (height < 0.dp || width < 0.dp) return true
    return WindowSizeClass.compute(width.value.coerceAtMost(1e6f), height.value).windowHeightSizeClass ==
        WindowHeightSizeClass.COMPACT
}

/**
 * Swaps animate only between these two groups (Welcome ↔ Shell); Phone ↔
 * Split happens inside the shell group, in place, with no fade
 * (`SceneDelegate.swift:24-33` runs `showRoot` only for auth changes; the
 * iPad split collapses to the tab shell inside `UISplitViewController`).
 */
internal enum class RootGroup { Welcome, Shell }

/** The fade group of [RootKind]: Welcome alone, Phone and Split together. */
internal val RootKind.group: RootGroup
    get() = if (this == RootKind.Welcome) RootGroup.Welcome else RootGroup.Shell
