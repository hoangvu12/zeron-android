package sh.zeron.android.shell

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The hub's state machine (android-ui-parity #36, #19) against a
 * recording [AppRouter]: held until attach, latest wins, dropped on the
 * welcome root, identity-checked detach — including a notification tap
 * from a cold start. Plain JUnit: the seam over `android.util.Log`
 * ("not mocked" on the plain JVM) is replaced in [setUp] and restored in
 * [tearDown].
 */
class DeepLinkHubTest {

    private val logs = mutableListOf<String>()
    private val originalLog = deepLinksLog

    @Before
    fun setUp() {
        deepLinksLog = { message -> logs.add(message) }
    }

    @After
    fun tearDown() {
        deepLinksLog = originalLog
    }

    // --------------------------------------------------------------- held

    @Test
    fun heldUntilAttachThenExactlyOneCall() {
        val router = FakeRouter()
        val hub = DeepLinkHub()
        hub.submit(DeepLink.Settings)
        // Nothing is attached yet: the link is held, nothing called.
        assertTrue(router.calls.isEmpty())
        hub.attachRouter(router)
        assertEquals(listOf("settings"), router.calls)
    }

    @Test
    fun aColdStartNotificationTapOpensTheChatOnceTheShellIsUp() {
        val router = FakeRouter()
        val hub = DeepLinkHub()
        // onCreate: the tap's `chatId` extra, before any shell exists.
        DeepLinks.notificationTap("c1")?.let(hub::submit)
        // Loading → shell: not the welcome root.
        hub.showingWelcomeRoot(false)
        assertTrue(router.calls.isEmpty())
        hub.attachRouter(router)
        assertEquals(listOf("open(c1)"), router.calls)
    }

    @Test
    fun twoSubmitsBeforeAttachDispatchOnlyTheLatest() {
        val router = FakeRouter()
        val hub = DeepLinkHub()
        hub.submit(DeepLink.Settings)
        hub.submit(DeepLink.OpenChat("c2"))
        hub.attachRouter(router)
        assertEquals(listOf("open(c2)"), router.calls)
    }

    // -------------------------------------------------------- welcome root

    @Test
    fun welcomeRootDropsPendingAndLaterSubmits() {
        val router = FakeRouter()
        val hub = DeepLinkHub()
        hub.submit(DeepLink.Search)
        // The welcome root takes over (the cross-fade target): the pending
        // link is dropped, and the attach below must not dispatch it.
        hub.showingWelcomeRoot(true)
        hub.attachRouter(router)
        assertTrue(router.calls.isEmpty())
        // Even with a router attached, a submit on the welcome root is
        // dropped (welcomeRoot beats an attached router).
        hub.submit(DeepLink.Settings)
        assertTrue(router.calls.isEmpty())
        assertTrue(logs.contains("dropped Settings: welcome root"))
        // Leaving the welcome root re-enables dispatch.
        hub.showingWelcomeRoot(false)
        hub.submit(DeepLink.Settings)
        assertEquals(listOf("settings"), router.calls)
    }

    @Test
    fun dispatchesAtOnceWhenAttached() {
        val router = FakeRouter()
        val hub = DeepLinkHub()
        hub.attachRouter(router)
        hub.submit(DeepLink.Search)
        assertEquals(listOf("search"), router.calls)
    }

    // -------------------------------------------------------------- routers

    @Test
    fun aStaleDetachDoesNotClearTheNewerRouter() {
        val oldRouter = FakeRouter()
        val newRouter = FakeRouter()
        val hub = DeepLinkHub()
        hub.attachRouter(oldRouter)
        hub.attachRouter(newRouter)
        hub.detachRouter(oldRouter) // stale: from an old composition
        hub.submit(DeepLink.Settings)
        assertTrue(oldRouter.calls.isEmpty())
        assertEquals(listOf("settings"), newRouter.calls)
    }

    @Test
    fun detachHoldsLinksAgainUntilReattach() {
        val router = FakeRouter()
        val hub = DeepLinkHub()
        hub.attachRouter(router)
        hub.detachRouter(router)
        hub.submit(DeepLink.Settings)
        assertTrue(router.calls.isEmpty())
        hub.attachRouter(router)
        assertEquals(listOf("settings"), router.calls)
    }

    // ------------------------------------------------------------ dispatch

    @Test
    fun chatIdsGoToTheRouterAsTheyCame() {
        val router = FakeRouter()
        val hub = DeepLinkHub()
        hub.attachRouter(router)
        hub.submit(DeepLink.OpenChat("raw-1"))
        assertEquals(listOf("open(raw-1)"), router.calls)
    }

    @Test
    fun newSessionPromptIsForwarded() {
        val router = FakeRouter()
        val hub = DeepLinkHub()
        hub.attachRouter(router)
        hub.submit(DeepLink.NewSession("Fix tests"))
        hub.submit(DeepLink.NewSession(null))
        assertEquals(listOf("new(Fix tests)", "new(null)"), router.calls)
    }

    @Test
    fun debugRoutesGoToTheRouter() {
        val router = FakeRouter()
        val hub = DeepLinkHub()
        hub.attachRouter(router)
        hub.submit(DeepLink.Debug(DebugRoute.Accounts("codex")))
        assertEquals(listOf("debug(Accounts(harness=codex))"), router.calls)
    }

    @Test
    fun debugScreensTheAppShowsNeverReachTheRouter() {
        val router = FakeRouter()
        val shown = mutableListOf<DebugRoute>()
        val hub = DeepLinkHub(debugScreens = { route -> (route == DebugRoute.Welcome).also { if (it) shown += route } })
        hub.attachRouter(router)
        hub.submit(DeepLink.Debug(DebugRoute.Welcome))
        hub.submit(DeepLink.Debug(DebugRoute.Billing))
        assertEquals(listOf<DebugRoute>(DebugRoute.Welcome), shown)
        assertEquals(listOf("debug(Billing)"), router.calls)
    }

    @Test
    fun theDefaultDebugRouteOnlyLogs() {
        val hub = DeepLinkHub()
        hub.attachRouter(AppRouter.Noop)
        hub.submit(DeepLink.Debug(DebugRoute.Welcome))
        assertTrue(logs.contains("debug route Welcome: no screen yet"))
    }

    // ---------------------------------------------------------------- rig

    /** Records every call as a readable string. */
    private class FakeRouter : AppRouter {
        val calls = mutableListOf<String>()

        override fun openSession(chatId: String) {
            calls.add("open($chatId)")
        }

        override fun openSession(chatId: String, handoff: DraftHandoff?) {
            calls.add("open($chatId, handoff)")
        }

        override fun presentNewSession(prompt: String?) {
            calls.add("new($prompt)")
        }

        override fun showSettings() {
            calls.add("settings")
        }

        override fun showSearch() {
            calls.add("search")
        }

        override fun showSessionOpenError() {
            calls.add("error")
        }

        override fun popToFrontPage() {
            calls.add("pop")
        }

        override fun showDebugRoute(route: DebugRoute) {
            calls.add("debug($route)")
        }
    }
}
