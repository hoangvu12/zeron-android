package sh.zeron.android.shell

import androidx.compose.runtime.staticCompositionLocalOf
import sh.zeron.android.cloud.BillingRoute
import sh.zeron.android.cloud.CloudSettingsRoute
import sh.zeron.android.cloud.CloudSheet

/** `Couldn't open session. Try again.` — the open-error toast, verbatim (`MainTabController.swift:206`). */
const val OPEN_ERROR_TOAST = "Couldn't open session. Try again."

/**
 * Navigation entry points the scene and screens use without knowing which
 * shell is on screen — phone tabs or the tablet split (android-ui-parity
 * #32). The port of `ios:Shell/SplitRootController.swift:3-10`'s `AppRouter`
 * protocol, plus the members iOS reaches by casting to `MainTabController` /
 * `SplitRootController` (`openSession(_:handoff:)`, `showSessionOpenError`,
 * `popToFrontPage`, `sessionDidAppear/Close`), folded into one interface.
 *
 * Main thread only: Compose callbacks, deep-link handlers and notification
 * intents already run there. Android replaces the iOS window-root cast
 * (`view.window?.rootViewController as? AppRouter`) with [LocalAppRouter].
 */
interface AppRouter {

    /**
     * A debug `route` screen (#19, iOS `SceneDelegate.openCloudRoute`).
     * The Cloud and accounts routes push their pages ([showDebugPages]);
     * the rest log and stay put until each owner wires its destination.
     */
    fun showDebugRoute(route: DebugRoute) {
        when (route) {
            // #51/#52: pushed in the phone's Settings tab; on the split, in a
            // fresh Settings card (#63).
            DebugRoute.CloudSettings, DebugRoute.GitHubConnect, DebugRoute.CloudBilling,
            DebugRoute.Billing, DebugRoute.CloudRepos,
            -> {
                showDebugPages(debugCloudPages(route))
                return
            }
            // #50 (iOS `openCloudRoute`): Providers; a computer's accounts
            // pushed from it; a Cloud agent's accounts.
            is DebugRoute.Providers, is DebugRoute.Accounts, is DebugRoute.CloudAgent -> {
                showDebugPages(debugAccountsPages(route))
                return
            }
            // #63: the app shows it itself (`DeepLinkHub`'s debug screens,
            // `ZeronApplication.debugWelcome`); never reaches a router.
            DebugRoute.Welcome -> Unit
        }
        deepLinksLog("debug route $route: no screen yet")
    }

    /**
     * #50: debug pages pushed over the shell's main stack, the last one
     * animated (iOS presents them; Android pushes). No-op by default.
     */
    fun showDebugPages(routes: List<NavRoute>) {}

    /** Push a session on the Sessions tab (deep links, notifications, search, new session without handoff). */
    fun openSession(chatId: String)

    /** From the new-session sheet. A null handoff is exactly `openSession(chatId)`. */
    fun openSession(chatId: String, handoff: DraftHandoff?)

    /** Presents the New Session page sheet (a null prompt opens it empty). */
    fun presentNewSession(prompt: String? = null)

    /** Selects the Settings tab. */
    fun showSettings()

    /** Selects the Search tab (field activation is tickets 29/49). */
    fun showSearch()

    /** Back to the Sessions root with the [OPEN_ERROR_TOAST] toast. */
    fun showSessionOpenError()

    /** Pop the Sessions stack to its root without animation. */
    fun popToFrontPage()

    /** The Session screen landed on screen (call after its push settles). No-op on phone except [SessionPresenceListener]. */
    fun sessionDidAppear(chatId: String) {}

    /** The Session screen really left its stack (pop committed, not a cancelled back gesture). No-op on phone except [SessionPresenceListener]. */
    fun sessionDidClose(chatId: String) {}

    /**
     * Ticket 80: the open session was archived from its ⋯ menu.
     * Returns true when the router moved away itself (tablet split
     * expanded → embedded draft, iOS `split.showDraft(prompt: nil,
     * focus: false)`); false = the caller pops its own stack. The phone
     * router and [Noop] keep this default; ticket 106's tablet router
     * overrides it.
     */
    fun leaveArchivedSession(chatId: String): Boolean = false

    /**
     * Wave-10 33: [chatId] was deleted from a list (the tablet sidebar's
     * row menu) — if it is the session open beside the sidebar, the
     * column leaves it for the draft, as after the ⋯ menu's Delete. True
     * when the router moved. The phone's list never shows its open
     * session, so the phone router and [Noop] keep this default.
     */
    fun leaveDeletedSession(chatId: String): Boolean = false

    companion object {
        /** Previews / tests without a shell: does nothing. */
        val Noop: AppRouter = object : AppRouter {
            override fun openSession(chatId: String) {}
            override fun openSession(chatId: String, handoff: DraftHandoff?) {}
            override fun presentNewSession(prompt: String?) {}
            override fun showSettings() {}
            override fun showSearch() {}
            override fun showSessionOpenError() {}
            override fun popToFrontPage() {}
        }
    }
}

/**
 * Ticket 108 listens to keep the open session across the 840 dp
 * phone/split switch. The phone router forwards [AppRouter.sessionDidAppear]
 * / [AppRouter.sessionDidClose] here — on iPhone `splitViewController` is
 * nil, so iOS's split-only callbacks are phone no-ops.
 */
interface SessionPresenceListener {
    fun sessionDidAppear(chatId: String)
    fun sessionDidClose(chatId: String)
}

/** The nearest shell's router; [AppRouter.Noop] outside one (the iOS window-root cast). */
val LocalAppRouter = staticCompositionLocalOf<AppRouter> { AppRouter.Noop }

/** #50: the pages a Providers / accounts debug route opens (iOS `openCloudRoute`). */
internal fun debugAccountsPages(route: DebugRoute): List<NavRoute> = when (route) {
    is DebugRoute.Providers -> listOf(sh.zeron.android.accounts.ProvidersRoute(route.target))
    is DebugRoute.Accounts -> listOf(
        sh.zeron.android.accounts.ProvidersRoute(),
        // The first computer's accounts for one agent, reached as the app does.
        sh.zeron.android.accounts.AgentAccountsRoute(sh.zeron.android.accounts.AccountsTarget.Device(deviceId = null, harness = route.harness)),
    )
    is DebugRoute.CloudAgent -> listOf(
        sh.zeron.android.accounts.AgentAccountsRoute(sh.zeron.android.accounts.AccountsTarget.Cloud(route.harness)),
    )
    else -> emptyList()
}

/**
 * #51/#52: a Cloud debug route's pages (iOS `openCloudRoute`): Cloud
 * settings as the root (`cloud:github` / `cloud:repos` with that sheet up),
 * `cloud:billing` with Billing over it, `billing` alone.
 */
internal fun debugCloudPages(route: DebugRoute): List<NavRoute> = when (route) {
    DebugRoute.GitHubConnect -> listOf(CloudSettingsRoute(open = CloudSheet.GitHub))
    DebugRoute.CloudRepos -> listOf(CloudSettingsRoute(open = CloudSheet.Repos))
    DebugRoute.CloudBilling -> listOf(CloudSettingsRoute(), BillingRoute)
    DebugRoute.Billing -> listOf(BillingRoute)
    else -> listOf(CloudSettingsRoute())
}
