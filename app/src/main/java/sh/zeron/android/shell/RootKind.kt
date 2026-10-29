package sh.zeron.android.shell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
 * width. Tickets 33, 104, 105 and 108 reference this constant. Inclusive;
 * no hysteresis.
 *
 * Wave-10 42 (user decision 2026-10-28): a tablet held upright follows the
 * iPad — split when wide enough. The narrowest iPad that splits in
 * portrait is the iPad mini at 744 pt, so the breakpoint is 744 dp (it was
 * Material's 840 dp "expanded" class, which kept every portrait tablet on
 * the phone layout).
 */
val SplitBreakpoint: Dp = 744.dp

/**
 * Wave-10 42 (I1): below this height the window is a phone held sideways
 * (a Pixel 7 in landscape is ~915 × 400 dp), which keeps the phone layout
 * however wide it is — iOS's compact height class.
 */
val CompactHeight: Dp = 480.dp

/**
 * The root on screen. Signed out wins over the size: the welcome root
 * never splits. A compact-height window (a landscape
 * phone) stays on the phone layout; otherwise the width decides. Both are
 * re-evaluated live (fold, multi-window resize, rotation) — a change never
 * goes through the cross-fade.
 */
fun chooseRoot(signedIn: Boolean, width: Dp, height: Dp = Dp.Infinity): RootKind = when {
    !signedIn -> RootKind.Welcome
    height < CompactHeight -> RootKind.Phone
    width >= SplitBreakpoint -> RootKind.Split
    else -> RootKind.Phone
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
