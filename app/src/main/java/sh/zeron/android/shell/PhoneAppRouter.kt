package sh.zeron.android.shell

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import sh.zeron.android.cloud.CloudSettingsRoute

/**
 * The shell operations the phone router needs (android-ui-parity #32): the
 * port of `ios:Shell/MainTabController.swift:115-216`'s UIKit surface,
 * minus everything UIKit owns outright. The real implementation is
 * [rememberPhoneShell]'s adapter over the Sessions [NavStack], the sheet
 * host, alerts, toasts and the IME; tests use a recording fake. Main thread.
 */
interface PhoneShell {

    /** True while a sheet is presented, hidden-for-handoff included (31's `SheetHostState.isPresenting`). */
    val isSheetPresented: Boolean

    /** Presents the New Session page sheet (31); `onCreated(chatId, handoff)` routes through the router. */
    fun presentNewSession(prompt: String?, onCreated: (chatId: String, handoff: DraftHandoff?) -> Unit)

    /** The presenter's `dismiss(animated:)` — or the pending handoff's `SheetHandoff.complete()`. */
    fun dismissSheet(animated: Boolean)

    /** 31's `hideForHandoff()`: the sheet vanishes, stays composed, inert. */
    fun hideSheetForHandoff()

    /** 18's `Alerts.dismissAll()` (+ 19's action sheet clear). */
    fun dismissAlerts()

    /** 24's `popToRootViewController` on the Sessions stack. */
    fun popSessionsToRoot(animated: Boolean)

    /** 24's `pushViewController` on the Sessions stack. */
    fun pushSession(route: SessionRoute, animated: Boolean)

    /**
     * `showSettings`'s stack half: nothing when Settings is already on top,
     * else pop to the list without animation and push Settings animated.
     */
    fun pushSettings()

    /**
     * #51: Settings, then [route] pushed over it (a debug `route` to a
     * Settings page).
     */
    fun pushSettingsPage(route: NavRoute) = pushSettings()

    /** `sessionsList.activateSearch()`: the bar's field active and focused. */
    fun activateSearch()

    /** IME down + focus cleared (iOS `view.endEditing(true)`). */
    fun hideIme()

    /** Ticket 14's toast. */
    fun toast(text: String)
}

/**
 * The phone [AppRouter] (android-ui-parity #32): the port of
 * `ios:Shell/MainTabController.swift:115-216`, driving the shell through
 * [PhoneShell]. Every order and every `animated` flag is kept from the
 * Swift; the one Android addition is the generation counter — iOS opens
 * synchronously inside a failable init, Android opens off main through
 * [RouterHooks.openSessionRoute] and applies the result back on the
 * router's main-confined [scope], so a later router call supersedes a
 * pending open (its route is discarded, never pushed).
 *
 * `sessionDidAppear`/`sessionDidClose` are phone no-ops that forward to
 * [presence] — on iPhone `splitViewController` is nil, so the split-only
 * callbacks never fire (the carry-over listens across the switch).
 */
class PhoneAppRouter(
    private val shell: PhoneShell,
    private val hooks: RouterHooks,
    /** Main-confined; dies with the phone shell (the Phone ↔ Split switch). */
    private val scope: CoroutineScope,
    /** The factory runs here (reads the cache seed, synchronous FFI pulls — ticket 76), never on main. */
    private val openDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val presence: SessionPresenceListener? = null,
    private val log: (String, Throwable?) -> Unit = { m, t -> Log.w("AppRouter", m, t) },
) : AppRouter {

    /**
     * Monotonically increasing. Each open captures it; every router call
     * except `sessionDidAppear/Close` bumps it. The latest intent wins —
     * a pending open that resolves afterwards calls
     * [SessionRoute.discard] and does nothing else.
     */
    private var generation = 0L

    /** The shared open-and-apply: captures the generation, opens off main, applies on main. */
    private fun resolve(chatId: String, apply: (SessionRoute?) -> Unit) {
        val gen = ++generation
        scope.launch {
            val route = hooks.openSessionRoute(chatId, openDispatcher, log)
            if (gen != generation) {
                route?.discard()
                return@launch
            }
            apply(route)
        }
    }

    override fun openSession(chatId: String) {
        // `MainTabController.swift:209-216`: the failable open, and only on
        // success: dismiss any presented sheet animated, dismiss alerts,
        // pop to root without animation, push animated — in this one
        // main-thread turn, no frame between the pop and the push.
        resolve(chatId) { route ->
            if (route == null) return@resolve showSessionOpenError()
            if (shell.isSheetPresented) shell.dismissSheet(animated = true)
            shell.dismissAlerts()
            shell.popSessionsToRoot(animated = false)
            shell.pushSession(route, animated = true)
        }
    }

    override fun openSession(chatId: String, handoff: DraftHandoff?) {
        // `MainTabController.swift:147-172`: a nil handoff is the plain open.
        if (handoff == null) return openSession(chatId)
        resolve(chatId) { route ->
            if (route == null) return@resolve showSessionOpenError()
            // `performWithoutAnimation`, in one main-thread turn: prepare
            // the arrival (Compose may draw the pushed route before a
            // later callback, so the route hides its content first —
            // prepareArrival is plain state writes), pop, push, IME down
            // (sending put the keyboard away; it slides down with the
            // handoff), sheet hidden — torn down only once the motion is
            // done, because a mid-animation dismissal is a heavy
            // compositor frame that swallowed it.
            route.prepareArrival()
            shell.popSessionsToRoot(animated = false)
            shell.pushSession(route, animated = false)
            shell.hideIme()
            shell.hideSheetForHandoff()
            var finished = false
            hooks.handoffAnimator.run(handoff, route) {
                // The completion dismisses the (hidden) sheet without
                // animation if it is still presented; first call only.
                if (finished) return@run
                finished = true
                if (shell.isSheetPresented) shell.dismissSheet(animated = false)
            }
        }
    }

    override fun presentNewSession(prompt: String?) {
        // `MainTabController.swift:115-127`: the New Session page sheet;
        // its `onCreated(chatId, handoff)` routes back through the router.
        // Any pending open is superseded, and a sheet already presented is
        // the UIKit no-op (it logs "already presenting" and refuses).
        generation++
        if (shell.isSheetPresented) return
        shell.presentNewSession(prompt) { id, handoff -> openSession(id, handoff) }
    }

    override fun showSettings() {
        // `MainTabController.swift:130-137`: dismiss anything presented
        // without animation, then Settings over the list (the bar steps
        // aside); a second call with Settings on top does nothing.
        generation++
        if (shell.isSheetPresented) shell.dismissSheet(animated = false)
        shell.pushSettings()
    }

    override fun showDebugRoute(route: DebugRoute) {
        // #51: Settings → Cloud (`cloud:github` with Connect GitHub up),
        // over whatever is presented, like `showSettings`.
        when (route) {
            DebugRoute.CloudSettings, DebugRoute.GitHubConnect -> {
                generation++
                if (shell.isSheetPresented) shell.dismissSheet(animated = false)
                shell.pushSettingsPage(CloudSettingsRoute(connectGitHub = route == DebugRoute.GitHubConnect))
            }
            else -> super.showDebugRoute(route)
        }
    }

    override fun showSearch() {
        // `MainTabController.swift:139-142`: back to the list, then the
        // bar's field active with the keyboard up.
        generation++
        shell.popSessionsToRoot(animated = false)
        shell.activateSearch()
    }

    override fun popToFrontPage() {
        // `MainTabController.swift:176-178`: pops the Sessions stack
        // without animation.
        generation++
        shell.popSessionsToRoot(animated = false)
    }

    override fun showSessionOpenError() {
        // `MainTabController.swift:200-206`: dismiss any presented
        // sheet/alert instantly, IME down, pop to the list (not animated),
        // then the toast — it sits 12 dp above the bar now back on screen.
        generation++
        if (shell.isSheetPresented) shell.dismissSheet(animated = false)
        shell.dismissAlerts()
        shell.hideIme()
        shell.popSessionsToRoot(animated = false)
        shell.toast(OPEN_ERROR_TOAST)
    }

    override fun sessionDidAppear(chatId: String) {
        presence?.sessionDidAppear(chatId)
    }

    override fun sessionDidClose(chatId: String) {
        presence?.sessionDidClose(chatId)
    }
}

/**
 * The phone router in composition: results apply on the composition's
 * main dispatcher and the router's opens are cancelled with the shell (the
 * swap to the split), each open's route discarded by
 * [RouterHooks.openSessionRoute]'s cancellation path.
 */
@Composable
fun rememberPhoneAppRouter(
    shell: PhoneShell,
    hooks: RouterHooks = LocalRouterHooks.current,
    presence: SessionPresenceListener? = null,
): AppRouter {
    val scope = rememberCoroutineScope() // AndroidUiDispatcher.Main; cancelled when the shell leaves
    return remember(shell, hooks, presence) { PhoneAppRouter(shell, hooks, scope, presence = presence) }
}
