package sh.zeron.android.shell

import android.util.Log

/**
 * Test seam over `android.util.Log.i` — the plain-JVM tests replace it
 * because `android.util.Log` throws "not mocked" there. The hub is the
 * only `DeepLinks`-tagged logger in this file; the ignored-link log
 * lives at the `MainActivity` call site.
 */
internal var deepLinksLog: (String) -> Unit = { message -> Log.i("DeepLinks", message) }

/**
 * The deep-link hold-and-dispatch hub (android-ui-parity #36): one slot
 * that holds links until the shell can take them — the shape of iOS's
 * single `pendingChat` slot (`PushNotifications.swift:27-30,184-193`),
 * generalised from notifications to every inbound link.
 *
 * Main thread only (Compose callbacks, `onCreate`/`onNewIntent` already
 * run there). Process-wide — it lives on `ZeronApplication`, so a link
 * held at cold start survives activity recreation:
 *
 * - [submit] drops a link while the welcome (signed-out) root is on
 *   screen (iOS ignores `-route` on the SignIn root), dispatches it at
 *   once when a router is attached, otherwise holds it in the one
 *   `pending` slot — the **latest submit wins**.
 * - [showingWelcomeRoot] keys on the root the cross-fade is heading
 *   **to**, not on composition lifetime: during the 350 ms fade the old
 *   shell is still composed and attached, but links are already dropped.
 *   `welcomeRoot` beats an attached router.
 * - [attachRouter] attaches after the shell's first frame (see
 *   `DeepLinkRouterEffect`) and dispatches a held link then, unless the
 *   welcome root took over in between.
 * - [detachRouter] is identity-checked: a stale detach from an old
 *   composition must not clear a newer router (the one-frame overlap of
 *   the phone/split switch).
 *
 * Fed by notification taps (#19, the `chatId` extra) and the `route`
 * extra; Zeron's chat ids are plain, so an `OpenChat` id goes to the
 * router as it came.
 */
class DeepLinkHub {

    private var router: AppRouter? = null
    private var welcomeRoot: Boolean = false
    private var pending: DeepLink? = null

    /** One inbound link: dispatch, hold or drop, per the state machine. */
    fun submit(link: DeepLink) {
        if (welcomeRoot) {
            deepLinksLog("dropped $link: welcome root")
            return
        }
        val attached = router
        if (attached != null) dispatch(attached, link) else pending = link
    }

    /**
     * The root the cross-fade is heading to: `true` for the welcome root,
     * `false` for the shells and while `Loading` (no router attached
     * then, so cold-start links stay held).
     */
    fun showingWelcomeRoot(shown: Boolean) {
        welcomeRoot = shown
        if (shown) pending = null
    }

    /**
     * The shell reports its [AppRouter] (after its first frame). A held
     * link is dispatched now — unless the welcome root took over first.
     */
    fun attachRouter(router: AppRouter) {
        this.router = router
        val held = pending
        if (held != null && !welcomeRoot) {
            pending = null
            dispatch(router, held)
        }
    }

    /** Identity-checked: a stale detach must not clear a newer router. */
    fun detachRouter(router: AppRouter) {
        if (this.router === router) this.router = null
    }

    /** `DeepLink` → `AppRouter` call, per the ticket's dispatch table. */
    private fun dispatch(router: AppRouter, link: DeepLink) {
        when (link) {
            is DeepLink.OpenChat -> router.openSession(link.chatId)

            is DeepLink.NewSession -> router.presentNewSession(link.prompt)

            DeepLink.Settings -> router.showSettings()

            DeepLink.Search -> router.showSearch()
        }
    }
}
