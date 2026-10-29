package sh.zeron.android.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import sh.zeron.android.accounts.AgentAccountsRoute
import sh.zeron.android.accounts.AgentAccountsScreen
import sh.zeron.android.cloud.CloudSettingsRoute
import sh.zeron.android.cloud.CloudSettingsScreen
import sh.zeron.android.session.SessionScreenRoute
import sh.zeron.android.settings.SettingsScreen
import sh.zeron.android.sessions.FolderRoute
import sh.zeron.android.sessions.FolderRouteContent
import sh.zeron.android.sessions.SessionsScreen
import sh.zeron.android.sidechats.SubagentRoute
import sh.zeron.android.terminal.TerminalRoute

/**
 * The phone shell's root route: the Sessions list, the first entry of
 * [MainTabState.sessions] and what `popToRoot` returns to (iOS
 * `MainTabController`'s `nav` root, `SessionsViewController`).
 */
data object SessionsRootRoute : NavRoute

/**
 * Settings pushed over the Sessions list (upstream #967's iPhone
 * `showSettings`): the one-row bar's gear. The bar steps aside
 * (`hidesBottomBarWhenPushed`), the page gets the back chevron.
 */
data object SettingsRoute : NavRoute {
    override val hidesBottomChrome: Boolean get() = true
    override val paneTitle: String get() = "Settings"
}

/**
 * The shell's route table: `when (route)` over every route the Sessions
 * stack can hold. 45 registers the folder route; 76 registers the Session
 * route.
 */
@Composable
fun ShellRouteContent(route: NavRoute) {
    when (route) {
        // The bar's field filters this list in place (the sidebar's query
        // path); "" when no bar is around (galleries, tests).
        is SessionsRootRoute -> LocalBottomBarSearch.current.let { search ->
            SessionsScreen(query = search?.query ?: "", filters = search != null)
        }
        // SettingsScreen tags its own node `settings-screen`, so the
        // shell's `tab-root-more` tag goes on a wrapper Box: two testTags on
        // one node would leave only one.
        is SettingsRoute -> Box(Modifier.fillMaxSize().testTag("tab-root-more")) {
            SettingsScreen()
        }
        // Ticket 45: one folder's sessions (Pinned, a user section,
        // Archived). The tablet sidebar (105) renders pushed routes
        // through this same table, so one branch serves both hosts.
        is FolderRoute -> FolderRouteContent(route)
        // Ticket 76: the Session screen — the route the factory built and
        // 32/41/106 pushed (its own Content: the NavEntry close safety
        // net + 75's SessionScreen). Ticket 106's detail column falls
        // through to this same table.
        is SessionScreenRoute -> route.Content()
        // android-next #06: a subagent's read-only transcript, pushed from
        // a spawn card or the Side Chats & Agents sheet.
        is SubagentRoute -> route.Content()
        // #44: a chat's terminal, pushed from the session (the panel's
        // terminal rows and New terminal, the ⋯ Terminal row, Ctrl+J).
        is TerminalRoute -> route.Content()
        // android-next #05: the Accounts & Usage page — an error card's
        // "Log in" and (until #50) Settings → Agents → Providers push it.
        is AgentAccountsRoute -> AgentAccountsScreen(route)
        // #51: Settings → Cloud.
        is CloudSettingsRoute -> CloudSettingsScreen(route)
        else -> Unit
    }
}
