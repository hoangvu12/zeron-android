package sh.zeron.android.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import sh.zeron.android.companions.CompanionSection

/** iOS `preferredInspectorColumnWidth` (340 … 480). */
internal val InspectorWidth = 400.dp

/** Below this the sidebar steps aside while the inspector shows (iOS `revealInspector`). */
internal const val InspectorRoomyWidthDp = 1100f

/**
 * What the inspector column's root shows: one session's companions panel
 * (iOS `CompanionsViewController(inSheet: false)`). Built by the companions
 * package ([inspectorRootFactory]); identity equality, so `setRoutes` keeps
 * the root's entry while it stays the same chat.
 */
interface CompanionsRootRoute : NavRoute {
    val chatId: String

    /** The section to scroll to once loaded (a strip chip's); the panel clears it. */
    var initialSection: CompanionSection?

    @Composable
    fun Content()
}

/** The inspector's root before any panel was shown. */
data object EmptyInspectorRoute : NavRoute

/**
 * The split's inspector column (#45, iOS `SplitRootController`'s
 * `.inspector` column, `:20-25, 196-278`): the open session's companions
 * beside it, with its own navigation stack — subagents, side chats and
 * terminals push over the panel and Back returns to it. One instance per
 * window, hoisted with the shell state so it survives a fold
 * ([onCollapse]: it then shows as a sheet over the phone shell).
 *
 * Main thread only.
 *
 * @param rootFor builds the panel route for a chat (the companions
 *   package; tests pass a fake)
 */
@Stable
class InspectorState(private val rootFor: (chatId: String) -> CompanionsRootRoute) {

    /** The session whose companions the column shows (iOS `inspectorChatId`). */
    var chatId: String? by mutableStateOf(null)
        private set

    /** The column's show/hide target (iOS `isShowing(.inspector)`). */
    var shown: Boolean by mutableStateOf(false)
        private set

    /** The column's own stack: the panel, then what was opened from it. */
    val stack = NavStack(EmptyInspectorRoute)

    /** The animated position, 0 hidden … 1 shown. */
    internal val progress = Animatable(0f)

    /** The sidebar's visibility before the inspector made room for itself (iOS `modeBeforeInspector`). */
    private var sidebarBefore: Boolean? = null

    /**
     * iOS `toggleCompanions(for:section:)`: the panel for [chatId] beside
     * it, or put away when it is already showing that panel (and nothing
     * over it).
     */
    fun toggle(chatId: String, section: CompanionSection?, shell: TabletShellState) {
        if (shown && this.chatId == chatId && stack.entries.size == 1) return hide(shell)
        val root = root(chatId)
        root.initialSection = section
        stack.setRoutes(listOf(root))
        reveal(shell)
    }

    /**
     * iOS `showCompanion(_:for:)`: a companion screen (subagent, side chat,
     * terminal) over [chatId]'s panel, so Back returns to it.
     */
    fun showCompanion(route: NavRoute, chatId: String, shell: TabletShellState) {
        val root = root(chatId)
        if (shown && stack.entries.firstOrNull()?.route === root) {
            stack.push(route, animated = true)
        } else {
            stack.setRoutes(listOf(root, route))
            reveal(shell)
        }
    }

    /**
     * iOS `closeCompanion(_:)`: a companion closed itself (its side chat
     * archived): pop it, or put the column away when it is the root.
     * Returns whether [route] was the inspector's top.
     */
    fun closeCompanion(route: NavRoute, shell: TabletShellState): Boolean {
        if (stack.top.route !== route) return false
        if (stack.entries.size > 1) stack.pop(animated = true) else hide(shell)
        return true
    }

    /** iOS `hideCompanions()`: the column goes; the sidebar it displaced comes back. */
    fun hide(shell: TabletShellState) {
        shown = false
        sidebarBefore?.let { shell.sidebarVisible = it }
        sidebarBefore = null
    }

    /** iOS `followInspector(to:)`: another session came up — the open panel follows it. */
    fun follow(chatId: String) {
        if (!shown || this.chatId == chatId) return
        stack.setRoutes(listOf(root(chatId)))
    }

    /**
     * The split collapsed (a fold): the column rides along as a sheet over
     * the phone shell, and the sidebar comes back with the width (iOS
     * `:299-300`).
     */
    fun onCollapse(shell: TabletShellState) {
        sidebarBefore?.let { shell.sidebarVisible = it }
        sidebarBefore = null
    }

    /** The panel route for [chatId]: the current root while it is the same chat. */
    private fun root(chatId: String): CompanionsRootRoute {
        val current = stack.entries.firstOrNull()?.route as? CompanionsRootRoute
        if (current != null && current.chatId == chatId && this.chatId == chatId) return current
        this.chatId = chatId
        return rootFor(chatId)
    }

    /** iOS `revealInspector()`: narrow windows can't fit all three, so the sidebar steps aside. */
    private fun reveal(shell: TabletShellState) {
        if (!shell.isCollapsed && shell.windowWidthDp > 0f && shell.windowWidthDp < InspectorRoomyWidthDp && shell.sidebarVisible && sidebarBefore == null) {
            sidebarBefore = true
            shell.sidebarVisible = false
        }
        shown = true
    }
}

/**
 * How a session beside the split's sidebar presents its companions (#45,
 * iOS `showCompanions(section:)` / `presentCompanion(_:)`): in the
 * inspector column. Provided only around the split's detail column; a
 * session elsewhere (the phone, the inspector itself) reads `null` and
 * uses its own sheet and stack.
 */
interface CompanionsPresenter {
    /** The companions item / a strip chip / Ctrl+Alt+0. */
    fun toggle(chatId: String, section: CompanionSection?)

    /** A companion screen for [chatId]'s panel (iOS `presentCompanion`). */
    fun show(route: NavRoute, chatId: String)
}

val LocalCompanionsPresenter = staticCompositionLocalOf<CompanionsPresenter?> { null }

/** The inspector's Close, while it shows as a sheet (folded); `null` in the column. */
val LocalInspectorClose = staticCompositionLocalOf<(() -> Unit)?> { null }

/** The route table inside the inspector: the panel, then the shared detail table. */
@Composable
internal fun InspectorContent(route: NavRoute) = when (route) {
    EmptyInspectorRoute -> Unit
    is CompanionsRootRoute -> route.Content()
    else -> TabletDetailContent(route)
}

/**
 * The inspector's stack (the column, or the sheet when folded). Sessions
 * inside it are companions (iOS `isCompanion`): they never become the
 * split's current session, never present into the inspector again, and
 * one that leaves (archived, deleted) pops off the inspector.
 */
@Composable
fun InspectorHost(inspector: InspectorState, shell: TabletShellState, router: AppRouter, modifier: Modifier = Modifier) {
    val companionRouter = androidx.compose.runtime.remember(inspector, shell, router) {
        object : AppRouter by router {
            override fun sessionDidAppear(chatId: String) {}
            override fun sessionDidClose(chatId: String) {}
            override fun leaveArchivedSession(chatId: String): Boolean = leave(chatId)
            override fun leaveDeletedSession(chatId: String): Boolean = leave(chatId)

            private fun leave(chatId: String): Boolean {
                val top = inspector.stack.top.route
                return (top as? SessionRoute)?.chatId == chatId && inspector.closeCompanion(top, shell)
            }
        }
    }
    CompositionLocalProvider(
        LocalAppRouter provides companionRouter,
        LocalCompanionsPresenter provides null,
        LocalPrefersLargeTitles provides false,
    ) {
        NavStackHost(stack = inspector.stack, active = inspector.shown, modifier = modifier) { entry ->
            InspectorContent(entry.route)
        }
    }
}
