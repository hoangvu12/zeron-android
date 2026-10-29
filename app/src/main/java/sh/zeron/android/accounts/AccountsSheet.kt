package sh.zeron.android.accounts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import sh.zeron.android.design.sheet.GlassSheet
import sh.zeron.android.design.sheet.SheetDetent
import sh.zeron.android.design.sheet.rememberGlassSheetState

/**
 * Whether an error card's accounts sheet shows, and for whom (#55). Main
 * thread only; the session screen holds one.
 */
class AccountsSheetController {
    var target: AccountsTarget? by mutableStateOf(null)
        private set

    /** iOS `present(nav)`: the agent's accounts, starting its sign-in at once. */
    fun open(target: AccountsTarget) {
        this.target = target
    }

    internal fun onDismissed() {
        target = null
    }
}

/**
 * An error card's "Log in" (#55, iOS `SessionViewController.swift:90-99`):
 * the chat's agent's accounts in a sheet over the session — medium and
 * large, with a grabber — starting the agent's first sign-in at once, a
 * Done circle to put it away. Leaving the sheet ends a computer's sign-in
 * (the page's own rule). Host it as the session screen's last child.
 */
@Composable
fun AccountsSheet(controller: AccountsSheetController) {
    val target = controller.target ?: return
    val detents = remember { listOf(SheetDetent.Medium, SheetDetent.Large) }
    // One route per opening: the sign-in starts once for it.
    val route = remember(target) { AgentAccountsRoute(target, signIn = true) }
    GlassSheet(
        onDismiss = { controller.onDismissed() },
        modifier = Modifier.testTag("accounts-sheet"),
        state = rememberGlassSheetState(detents),
        grabber = true,
    ) { bottomInset ->
        AgentAccountsPage(route, onDone = { dismiss() }, bottomInset = bottomInset)
    }
}
