package sh.zeron.android.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember

/**
 * The phone shell's state: the port of `ios:Shell/MainTabController.swift`,
 * which (despite its name) is a plain container for **one** Sessions
 * navigation stack — no tab bar at all (upstream #967 + #971: a hidden tab
 * bar came back whenever a screen that hides the bottom bar was popped).
 *
 * The stack lives outside composition, so it survives every recomposition
 * and (hoisted in [ShellStateScope]) the Phone ↔ Split switch. Its bottom
 * chrome — the one-row [SessionsBottomBar] — shows on the list root only
 * ([NavStack.chromeOnRootOnly], iOS `hidesBottomBarWhenPushed` on every
 * pushed page).
 *
 * [NavStack] has no `Saver` (ticket 24 drops process-death restoration, as
 * iOS has none), so after process death the stack restarts at its root; the
 * configuration changes that matter do not recreate the activity.
 */
@Stable
class MainTabState internal constructor() {

    /** The Sessions stack: the list at its root, every other page pushed over it. */
    val sessions: NavStack = NavStack(SessionsRootRoute).apply { chromeOnRootOnly = true }
}

/** [MainTabState] in composition (production hoists it in [ShellStateScope]). */
@Composable
fun rememberMainTabState(): MainTabState = remember { MainTabState() }
