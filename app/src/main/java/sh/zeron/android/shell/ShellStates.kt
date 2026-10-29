package sh.zeron.android.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import sh.zeron.android.companions.CompanionsInspectorRoute
import sh.zeron.android.session.SessionDetachFence
import sh.zeron.android.shell.tablet.SidebarState

/**
 * The shell-state scope hoisted above ticket 35's Phone ↔ Split switch
 * (android-ui-parity #108) — the Android shape of iOS keeping one
 * `SplitRootController` (with its lazy `tabs`) alive for the whole window:
 * `MainTabController`'s `setViewController(tabs, for: .compact)`
 * (`SplitRootController.swift:17-22, 49`) swaps what is on screen, never
 * the controllers or their stacks.
 *
 * Everything the two shells share is remembered **here**, inside the
 * shell group of [AppRoot]'s cross-fade, so it survives every width
 * change and is released only when the shell layer itself leaves
 * composition (Welcome, core failure, activity teardown):
 *
 * - the phone shell's [MainTabState] (the Sessions stack lives in it —
 *   the pushed session survives the collapse exactly as iOS's `tabs`
 *   stack does);
 * - 33's [TabletShellState] (`currentChatId`, `sidebarVisible`,
 *   `detailRoot`; `isCollapsed` gets its single writer here);
 * - 105's [SidebarState];
 * - 106's [PhoneRouterSlot], [SettingsPresenterSlot] and
 *   [TabletAppRouter] (with the router-owned persistent detail
 *   [NavStack]), moved here from 35's `ZeronAppContent`;
 * - a [SaveableStateHolder] the two shell slots key their
 *   `SaveableStateProvider` calls into, so each shell's saveable state
 *   (scroll positions, field text) survives a round trip;
 * - the [CarryOver] itself, wired to ticket 106's router through
 *   [TabletDetailColumn] and to the tab shell through [MainTabsColumn].
 *
 * The phone and split slots in `ZeronAppContent` read all of it through
 * [LocalShellStates]; neither remembers anything of its own.
 */

/**
 * iOS-shaped selection over ticket 33's state, as the carry-over (and
 * 106's `tabPresence`, via [TabletAppRouter.tabPresence]) needs it.
 */
fun TabletShellState.carryOverSelection(): CarryOverSelection = object : CarryOverSelection {
    override var currentChatId: String?
        get() = this@carryOverSelection.currentChatId
        set(v) {
            this@carryOverSelection.currentChatId = v
        }

    override val isCollapsed: Boolean
        get() = this@carryOverSelection.isCollapsed
}

/**
 * The hoisted shell state (one instance per shell layer). The shells read
 * it through [LocalShellStates]; nothing outside this file writes it.
 */
@Stable
class ShellStates internal constructor(
    /** The phone shell's state (the Sessions stack). */
    val tabs: MainTabState,

    /** Ticket 33: the split's state (currentChatId, sidebarVisible, detailRoot, isCollapsed). */
    val tablet: TabletShellState,

    /** Ticket 105: the sidebar's state (its own stack and search). */
    val sidebar: SidebarState,

    /** The shell slots' saveable state (`SaveableStateProvider("phone")` / `("split")`). */
    val saveable: SaveableStateHolder,

    /** Ticket 106: the phone router while collapsed. */
    val phoneSlot: PhoneRouterSlot,

    /** Ticket 106 (104's presenter registers into it inside the split slot). */
    val settingsSlot: SettingsPresenterSlot,

    /** Ticket 106: the tablet router (owns the persistent detail NavStack). */
    val tabletRouter: TabletAppRouter,

    /** This ticket's transition runner. */
    val carryOver: CarryOver,

    /** #45: the split's inspector column (a sheet over the phone shell while folded). */
    val inspector: InspectorState,
) {
    /** The tab-shell presence listener forwarded by 32's phone router — 106's `tabPresence` (= [CarryOverPresence]). */
    val presence: SessionPresenceListener
        get() = tabletRouter.tabPresence
}

/** The nearest [ShellStates]; a shell slot outside a [ShellStateScope] reads a hard error, not a silent default. */
val LocalShellStates = staticCompositionLocalOf<ShellStates> { error("No ShellStates") }

/**
 * [TabsColumn] over the hoisted state: the [MainTabState] stack
 * plus ticket 106's [PhoneRouterSlot]. Works hostless — the pops run after
 * the phone shell left composition, and the open lands on the next phone
 * router when the phone shell is not up yet.
 */
internal class MainTabsColumn(
    private val tabs: MainTabState,
    private val slot: PhoneRouterSlot,
) : TabsColumn {

    override fun openSession(chatId: String) = slot.run { it.openSession(chatId) }

    override fun popToFrontPage() {
        // A phone call queued before the widen is stale: the latest intent
        // (this pop, then the detail open) wins.
        slot.clearPending()
        tabs.sessions.popToRoot(animated = false)
    }

    override fun popAllToRoot() {
        tabs.sessions.popToRoot(animated = false)
    }

    override fun releaseSession(): NavEntry? {
        val stack = tabs.sessions
        // The session itself — the first one above the list — not a side
        // chat pushed over it (those close with the release).
        val page = stack.entries.drop(1).firstOrNull { it.route is SessionRoute } ?: return null
        slot.clearPending()
        stack.release(page)
        stack.popToRoot(animated = false)
        return page
    }

    override fun adoptSession(entry: NavEntry) {
        slot.clearPending()
        tabs.sessions.popToRoot(animated = false)
        tabs.sessions.adopt(entry)
    }
}

/**
 * [SplitDetailColumn] over ticket 106's router (its `clearDetail` /
 * `showSessionInDetail` / `showDraft`) and 33's state. Works hostless —
 * the split left composition one frame earlier, so the `setRoutes` under
 * `clear()` completes synchronously and closes the outgoing entry.
 */
internal class TabletDetailColumn(
    private val router: TabletAppRouter,
    private val tablet: TabletShellState,
) : SplitDetailColumn {

    override val shownChatId: String?
        get() = (tablet.detailRoot as? DetailRoot.Session)?.chatId

    override val isEmpty: Boolean
        get() = tablet.detailRoot == DetailRoot.Empty

    override fun clear() = router.clearDetail()

    override fun showSession(chatId: String) = router.showSessionInDetail(chatId)

    override fun showDraft() = router.showDraft(prompt = null, focus = false)

    override fun releaseSession(): NavEntry? = router.releaseDetailSession()

    override fun adoptSession(entry: NavEntry) = router.adoptDetailSession(entry)
}

/**
 * The shell-state scope itself (see the file KDoc). Composed inside
 * [AppRoot]'s shell group, wrapped by 35's `shellScope` parameter.
 *
 * @param kind the shell kind on screen (re-read live by [AppRoot]); the
 *   scope survives every change of it.
 * @param content the shell slots (phone / split branches of the root).
 */
@Composable
fun ShellStateScope(kind: RootKind, content: @Composable () -> Unit) {
    ShellStateScope(kind, detailOverride = null, tabsOverride = null, content = content)
}

/**
 * The test seam: [detailOverride] / [tabsOverride] replace
 * [TabletDetailColumn] / [MainTabsColumn] in the [CarryOver] when
 * non-null, so `ShellStateScopeTest` can record the transition without
 * 106's real router driving the column.
 */
@Composable
internal fun ShellStateScope(
    kind: RootKind,
    detailOverride: SplitDetailColumn?,
    tabsOverride: TabsColumn?,
    content: @Composable () -> Unit,
) {
    // The phone shell's Sessions stack (not saved, as iOS).
    val tabs = rememberMainTabState()
    // 33 (rememberSaveable: currentChatId, sidebarVisible, detailRoot).
    val tablet = rememberTabletShellState()
    // 105: not saved, as iOS.
    val sidebar = remember { SidebarState() }
    val saveable = rememberSaveableStateHolder()
    // 106 step 7's block, moved here from ZeronAppContent (created each
    // ONCE, here): the slots and the router must outlive both shells.
    val phoneSlot = remember { PhoneRouterSlot() }
    val settingsSlot = remember { SettingsPresenterSlot() }
    val tabletRouter = rememberTabletAppRouter(tablet, phoneSlot, sidebar, settingsSlot)
    // #45: one inspector per window, like the split's (not saved, as iOS).
    val inspector = remember { InspectorState(rootFor = { CompanionsInspectorRoute(it) }) }
    val states = remember(tabletRouter) {
        val carryOver = CarryOver(
            selection = tablet.carryOverSelection(),
            detail = detailOverride ?: TabletDetailColumn(tabletRouter, tablet),
            tabs = tabsOverride ?: MainTabsColumn(tabs, phoneSlot),
            awaitFrame = { withFrameNanos { } },
            awaitDetached = { id ->
                if (!SessionDetachFence.Default.await(id)) {
                    android.util.Log.w("CarryOver", "detach fence timeout $id")
                }
            },
            onCollapse = { inspector.onCollapse(tablet) },
        )
        ShellStates(tabs, tablet, sidebar, saveable, phoneSlot, settingsSlot, tabletRouter, carryOver, inspector)
    }
    // Declared BEFORE content(): forgotten AFTER the shells, so their
    // NavStack hosts are gone when it runs and the pops are hostless —
    // the entries close synchronously (24), which is what releases the
    // open sessions' controllers (85's retained lifecycle.release()).
    DisposableEffect(states) {
        onDispose { states.carryOver.releaseAll() }
    }
    // The single writer of `TabletShellState.isCollapsed` (33): one
    // SideEffect per composition, derived from the kind.
    SideEffect { tablet.isCollapsed = kind != RootKind.Split }
    // The transition runner: `from` is null on first composition (nothing
    // to carry), a restart (kind change) cancels the previous run — which
    // is the only cancellation the carry-over ever gets, and every step
    // tolerates being cut off (see the ticket's Comments).
    val last = remember { arrayOfNulls<RootKind>(1) }
    LaunchedEffect(kind) {
        val from = last[0]
        last[0] = kind
        states.carryOver.onShellKindChanged(from, kind)
    }
    CompositionLocalProvider(LocalShellStates provides states) {
        content()
    }
}
