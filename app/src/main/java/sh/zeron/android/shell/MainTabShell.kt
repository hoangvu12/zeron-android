package sh.zeron.android.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import sh.zeron.android.design.LocalBottomChrome
import sh.zeron.android.design.LocalBottomChromeTop
import sh.zeron.android.design.LocalReduceMotion
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.glass.LocalHazeState

/**
 * The phone shell: the app root on phones, the port of
 * `ios:Shell/MainTabController.swift` — a plain container around **one**
 * Sessions [NavStack] over a `palette.background` view, with the one-row
 * [SessionsBottomBar] (Settings · Search · New session) floating over the
 * list as a bottom overlay. No tab bar at all (upstream #967 + #971).
 *
 * - The stack's content is the **only** `hazeSource` of the bar's
 *   `HazeState`; the bar samples it through [LocalHazeState].
 * - The bar shows on the list root only ([NavStack.chromeOnRootOnly], iOS
 *   `hidesBottomBarWhenPushed`): it is hidden while a page is pushed,
 *   rides and is covered like the UIKit toolbar during push/pop, and is
 *   **never removed from composition** — all derived per frame by
 *   [ChromeVisibilityController] and applied by [Modifier.chromeVisibility].
 * - The bar sits [BottomChromeInsets.barBottom] above the window bottom
 *   (`tabBarBottomGap`: 12 dp above a 3-button bar, `max(21, nav)` on
 *   gesture nav) and rides on the IME while its field is focused; the list
 *   pads by [LocalBottomChrome] so its last row clears the bar either way.
 * - Back on the front page while the search is active ends it (iOS Close:
 *   the query clears, the list unfilters) before the activity's own back.
 * - The shell provides [LocalBottomChromeTop]'s live bar top for the toast
 *   anchor (toasts float 12 dp above the bar, `bottomBarFrame`), and wraps
 *   each entry with [ProvideTransitionProgress].
 *
 * **The router (#32):** the shell builds the phone [AppRouter] over its own
 * state (the adapter implements the [PhoneShell] port) and provides it as
 * [LocalAppRouter] around everything below — every screen, deep link and
 * notification calls it without knowing which shell is on screen. New
 * session defaults to the router's `presentNewSession()`, and the sheet
 * host is mounted here when the app root has none.
 *
 * @param state the shell state; defaults to [rememberMainTabState].
 * @param searchState the bar's search; defaults to [rememberSearchTabState]
 *   (the app-scoped instance).
 * @param hooks the app root's [RouterHooks]. Defaults to [LocalRouterHooks].
 * @param sheetHost the host the shell presents its page sheets (New
 *   Session) on. `null` (production): the app root's host ([RootHosts],
 *   read through [LocalSheetHost]) — above the Phone ↔ Split switch, so a
 *   presented sheet survives it. Without a root host (galleries, previews,
 *   tests mounting the shell alone) the shell mounts its own over itself.
 * @param presence forwarded [AppRouter.sessionDidAppear]/[Close] events
 *   (the carry-over across the Phone ↔ Split switch); `null` by default.
 * @param onNewSession the New session tap; `null` (production) presents the
 *   New Session sheet through the phone router.
 * @param onRouter invoked once with the shell's [AppRouter] — the
 *   equivalent of iOS `window.rootViewController as? AppRouter`, so deep
 *   links and launch extras route into the shell on screen.
 * @param routeContent renders one stack entry; `null` (production) uses
 *   [ShellRouteContent].
 */
@Composable
fun MainTabShell(
    state: MainTabState = rememberMainTabState(),
    modifier: Modifier = Modifier,
    searchState: SearchTabState = rememberSearchTabState(),
    hooks: RouterHooks = LocalRouterHooks.current,
    sheetHost: SheetHostState? = null,
    presence: SessionPresenceListener? = null,
    onNewSession: (() -> Unit)? = null,
    onRouter: (AppRouter) -> Unit = {},
    routeContent: (@Composable (NavEntry) -> Unit)? = null,
) {
    val palette = ZeronTheme.palette
    val barHaze = rememberHazeState()
    val stack = state.sessions

    // The bar's visibility, the list insets and the toast anchor — all
    // derived, never flipped, so a cancelled predictive back re-derives
    // from the real top screen.
    val chrome = rememberChromeVisibilityController(state)
    val insets = rememberBottomChromeInsets()
    val chromeTopState = LocalBottomChromeTop.current
    val reduceMotion = LocalReduceMotion.current
    val layoutDirection = LocalLayoutDirection.current
    SideEffect {
        chrome.reduceMotion = reduceMotion
        chrome.rtl = layoutDirection == LayoutDirection.Rtl
    }

    // Publish the bar top for the toast anchor — every frame (the IME
    // ride, a reveal), written from a collector, never from placement. NaN
    // becomes Dp.Unspecified ("no bar on screen"), the reader's safe-area
    // fallback.
    LaunchedEffect(chrome, chromeTopState) {
        snapshotFlow {
            chrome.publishedChromeTop().let { if (it.isNaN()) Dp.Unspecified else it.dp }
        }.collect { chromeTopState.top = it }
    }
    DisposableEffect(chromeTopState) {
        onDispose { chromeTopState.top = Dp.Unspecified }
    }

    // Composed BEFORE the stack, so NavStackHost's own back handler
    // (enabled while canPop, predictive back) wins over this one: back on
    // the front page with the search active closes it.
    val closeSearch = rememberCloseSearch(searchState)
    BackHandler(enabled = searchState.active && !stack.canPop) { closeSearch() }

    // #33: the phone router (#32) built before the root modifier — the
    // hardware-shortcut modifier needs the real router. The sheet host is
    // passed explicitly: the root's when the app root provides one, else
    // this shell's own (mounted further down).
    val rootSheetHost = LocalSheetHost.current
    val ownSheetHost = rememberSheetHostState()
    val host = sheetHost ?: rootSheetHost ?: ownSheetHost
    val mountsOwnHost = host !== rootSheetHost
    val shell = rememberPhoneShell(state, searchState, host)
    val router = rememberPhoneAppRouter(shell, hooks, presence)

    Box(
        modifier
            .fillMaxSize()
            .background(palette.background)
            // #33: Ctrl+N / Ctrl+Shift+F from any focused view in the shell
            // (iOS ⌘N / ⇧⌘F). shell = null — Ctrl+B is not consumed on the
            // phone shell (no sidebar to toggle).
            .then(rememberShellShortcuts(router, shell = null)),
    ) {
        val density = LocalDensity.current
        // The row's start/end insets (the navigation bar on a landscape
        // 3-button device, a cutout), so the bar stays inside the safe area.
        val horizontalInsets = WindowInsets.navigationBars.union(WindowInsets.displayCutout)
        val insetLeft = with(density) { horizontalInsets.getLeft(density, layoutDirection).toDp() }
        val insetRight = with(density) { horizontalInsets.getRight(density, layoutDirection).toDp() }
        val insetStart = if (layoutDirection == LayoutDirection.Rtl) insetRight else insetLeft
        val insetEnd = if (layoutDirection == LayoutDirection.Rtl) insetLeft else insetRight

        ShellSheetHost(host, mount = mountsOwnHost) {
            val onRouterLatest = rememberUpdatedState(onRouter)
            LaunchedEffect(router) { onRouterLatest.value(router) }

            val newSessionTap = remember(onNewSession, router) {
                onNewSession ?: { router.presentNewSession() }
            }

            CompositionLocalProvider(
                LocalAppRouter provides router,
                LocalBottomChrome provides insets,
                LocalBottomBarSearch provides searchState,
            ) {
                // The scrolling content: the only haze source the bar samples.
                Box(Modifier.fillMaxSize().hazeSource(barHaze)) {
                    NavStackHost(stack = stack, active = true, modifier = Modifier.fillMaxSize()) { entry ->
                        // Every entry gets its live transition progress
                        // (85's composer cross-fade).
                        ProvideTransitionProgress(stack, entry) {
                            val rc = routeContent
                            if (rc != null) rc(entry) else ShellRouteContent(entry.route)
                        }
                    }
                }

                // The bar above the content, sampling it; placed / covered /
                // dimmed by the visibility controller and riding on the IME
                // while the field is focused. Its top is the toasts' anchor.
                CompositionLocalProvider(LocalHazeState provides barHaze) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .onPlaced { chrome.layoutTopInRoot = it.positionInRoot().y }
                            .chromeVisibility(chrome),
                    ) {
                        SessionsBottomBar(
                            searchState = searchState,
                            onSettings = { router.showSettings() },
                            onNewSession = newSessionTap,
                            onFrontPage = !stack.hidesChrome(stack.top),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))
                                .padding(
                                    start = insetStart + SessionsBottomBarMetrics.SideMargin.dp,
                                    end = insetEnd + SessionsBottomBarMetrics.SideMargin.dp,
                                    bottom = insets.barBottom,
                                )
                                .onPlaced { coords ->
                                    chrome.updateChromeTop(
                                        with(density) { coords.positionInParent().y.toDp() }.value,
                                        density,
                                    )
                                },
                        )
                    }
                }

                // All pointer input over the shell is consumed while the
                // stack transitions (UIKit disables interaction during
                // transitions); back gestures are not pointer events, so a
                // predictive back drives the reveal underneath it.
                ChromeTransitionBlocker(chrome, Modifier.matchParentSize())
            }
        }
    }
}

/**
 * Mounts [SheetHost] for [state] only when the shell owns it ([mount]);
 * on the app root's host the content composes as is (the root's
 * [SheetHost] already provides [LocalSheetHost] and presents above it).
 */
@Composable
private fun ShellSheetHost(state: SheetHostState, mount: Boolean, content: @Composable () -> Unit) {
    if (mount) SheetHost(state, content) else content()
}
