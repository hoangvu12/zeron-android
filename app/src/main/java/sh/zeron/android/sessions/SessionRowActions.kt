package sh.zeron.android.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import sh.zeron.android.R
import sh.zeron.android.design.ContextMenuLiftState
import sh.zeron.android.design.LiftHighlight
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.contextMenu
import sh.zeron.android.design.glass.Menu
import sh.zeron.android.design.glass.MenuIcon
import sh.zeron.android.design.glass.MenuItem
import sh.zeron.android.design.glass.MenuSection
import sh.zeron.android.design.rememberContextMenuLiftState
import sh.zeron.android.shell.LocalAppRouter
import uniffi.zeron_core.SessionRow
import uniffi.zeron_core.SectionView

/**
 * The session-row wiring (android-ui-parity #44): which swipe actions and
 * which long-press menu each row gets, and the hook that carries them into
 * 41's list. The gesture half is [SwipeRow]; the actions themselves run
 * through tickets 42/43.
 *
 * [SessionRowCommands] is the fakeable seam over both neighbours (43's
 * [SessionPrompts] is row-based and 42's [RowActions] reports outcomes
 * through callbacks); the production adapter is the same-named factory
 * [SessionRowCommands].
 */

/**
 * What a row's gestures can ask the app to do — one method per iOS call
 * site, adapted through 42 ([RowActions]) and 43 ([SessionPrompts]).
 * Main-thread API, called from release handlers, pane taps, menu items
 * and a11y custom actions.
 */
interface SessionRowCommands {

    /** Pin/unpin a chat (`AppModel.setPinned`). */
    fun setPinned(chatId: String, pinned: Boolean)

    /**
     * Archive a chat. [onAccepted] reports, on main, at most once, whether
     * the write went through (`Ok`/`Gone`) or was refused
     * (`NoClient`/`Failed`/`Skipped`) —
     * a refused destructive swipe springs the row back. 42 shows the
     * "Archived · Undo" toast only on `Ok`.
     */
    fun archive(chatId: String, onAccepted: (Boolean) -> Unit)

    /** Unarchive a chat — no toast of its own. */
    fun unarchive(chatId: String)

    /** Move a chat into a section; `null` = "No Section" (back to Recent). */
    fun move(chatId: String, sectionId: String?)

    /** The Move swipe: 43's "Move to Section" action sheet for [row]. */
    fun moveToSectionSheet(row: SessionRow)

    /** The menu's "Rename…": 43's pre-filled "Rename Session" alert. */
    fun renameSession(row: SessionRow)

    /** The menu's "Delete": 43's "Delete session?" confirm, then 42's delete (upstream #967). */
    fun deleteSession(row: SessionRow)

    /** The menu's "New Section…": creates only. */
    fun newSection()

    /** The user sections, in snapshot order, empty ones included (42's `sections`). */
    fun sections(): List<SectionView>
}

/**
 * The production adapter: one-line delegations, with `archive` mapping
 * 42's [ActionOutcome] to accepted (`Ok`/`Gone`) or refused (everything
 * else — a blank `Skipped` never archived anything either). [onDeleted]
 * hears a confirmed delete (the router leaves the session if it is the
 * one open beside the tablet sidebar).
 */
fun SessionRowCommands(
    actions: RowActions,
    prompts: SessionPrompts,
    onDeleted: (chatId: String) -> Unit = {},
): SessionRowCommands =
    object : SessionRowCommands {
        override fun setPinned(chatId: String, pinned: Boolean) {
            actions.setPinned(chatId, pinned)
        }

        override fun archive(chatId: String, onAccepted: (Boolean) -> Unit) {
            actions.archive(chatId) { outcome ->
                onAccepted(outcome == ActionOutcome.Ok || outcome == ActionOutcome.Gone)
            }
        }

        override fun unarchive(chatId: String) {
            actions.unarchive(chatId)
        }

        override fun move(chatId: String, sectionId: String?) {
            actions.move(chatId, sectionId)
        }

        override fun moveToSectionSheet(row: SessionRow) {
            prompts.moveToSection(row)
        }

        override fun renameSession(row: SessionRow) {
            prompts.renameSession(row)
        }

        override fun deleteSession(row: SessionRow) {
            prompts.confirmDeleteSession(row.id, if (row.hasTitle) row.title else "") { onDeleted(row.id) }
        }

        override fun newSection() {
            prompts.newSection()
        }

        override fun sections(): List<SectionView> = actions.sections()
    }

/**
 * The ambient commands: ticket 42's [LocalRowActions] adapted with
 * [rememberSessionPrompts]. Call once per screen, inside the host stack
 * 35/43 expect (`ContextMenuHost` outermost, then alerts, toasts, sheets).
 */
@Composable
fun rememberSessionRowCommands(): SessionRowCommands {
    val actions = LocalRowActions.current
    val prompts = rememberSessionPrompts()
    val router = LocalAppRouter.current
    return remember(actions, prompts, router) {
        SessionRowCommands(actions, prompts) { chatId -> router.leaveDeletedSession(chatId) }
    }
}

// ---------------------------------------------------------------------------
// The swipe actions
// ---------------------------------------------------------------------------

/**
 * The swipe actions of one row — `leadingSwipe`/`trailingSwipe`
 * (`SessionListController.swift:241-276`) as a `(leading?, trailing)` pair,
 * trailing **outermost first** (Archive at the edge, Move next to the
 * content). The Archived list has no leading action and one trailing
 * (Unarchive).
 *
 * Pure, but **not** snapshot-reactive: the wrapper rebuilds it when the
 * row's `pinned` flips (the Pin/Unpin title and icon), and every
 * `perform` re-reads the row and vm at call time so a commit always uses
 * the latest state — iOS rebuilds the configuration per swipe.
 *
 * @param row `() -> row` and `vm: () -> vm` are read at **call** time
 * @param accent palette `accent` (Pin/Unpin, Unarchive panes)
 * @param secondary palette `secondary` (the Archive pane)
 * @param strings `resources::getString`
 */
fun sessionSwipeActions(
    row: () -> SessionRow,
    vm: () -> SessionRowVM,
    archivedList: Boolean,
    cmd: SessionRowCommands,
    accent: Color,
    secondary: Color,
    strings: (Int) -> String,
): Pair<SwipeAction?, List<SwipeAction>> {
    if (archivedList) {
        return null to listOf(
            SwipeAction(
                title = strings(R.string.swipe_unarchive),
                icon = ZeronIcons.TrayAndArrowUpFill,
                background = accent,
                testTag = "swipe-action-unarchive",
                perform = { cmd.unarchive(row().id) },
            ),
        )
    }
    val pinned = vm().pinned
    val pin = SwipeAction(
        title = strings(if (pinned) R.string.swipe_unpin else R.string.swipe_pin),
        icon = if (pinned) ZeronIcons.PinSlash else ZeronIcons.PinFill,
        background = accent,
        testTag = if (pinned) "swipe-action-unpin" else "swipe-action-pin",
        perform = { cmd.setPinned(row().id, !vm().pinned) },
    )
    val archive = SwipeAction(
        title = strings(R.string.swipe_archive),
        icon = ZeronIcons.ArchiveboxFill,
        background = secondary,
        destructive = true,
        testTag = "swipe-action-archive",
        perform = { onAccepted -> cmd.archive(row().id, onAccepted) },
    )
    val move = SwipeAction(
        title = strings(R.string.swipe_move),
        icon = ZeronIcons.FolderFill,
        background = SwipeTokens.MoveTint,
        testTag = "swipe-action-move",
        perform = { cmd.moveToSectionSheet(row()) },
    )
    return pin to listOf(archive, move)
}

// ---------------------------------------------------------------------------
// The long-press context menu
// ---------------------------------------------------------------------------

/**
 * The row's context menu (`SessionListController.swift:278-300`), built
 * at long-press time from the row, vm and sections the caller holds then
 * — never stale. Pure: invokes nothing until a menu item is picked.
 *
 *  - normal: Pin/Unpin, "Move to Section ▸" (drill-in; every section of
 *    stored order, empty ones included, then "No
 *    Section", then "New Section…"), "Rename…", "Archive", "Delete"
 *    (destructive — upstream #967 moved the red from Archive to Delete);
 *  - archived: "Unarchive", "Rename…", "Delete".
 */
fun sessionRowMenu(
    row: SessionRow,
    vm: SessionRowVM,
    archivedList: Boolean,
    sections: List<SectionView>,
    cmd: SessionRowCommands,
    strings: (Int) -> String,
): Menu {
    if (archivedList) {
        return Menu(
            title = null,
            children = listOf(
                MenuItem(
                    title = strings(R.string.swipe_unarchive),
                    icon = MenuIcon.Symbol(ZeronIcons.TrayAndArrowUp),
                    testTag = "row-menu-unarchive",
                ) { cmd.unarchive(row.id) },
                MenuItem(
                    title = strings(R.string.menu_rename),
                    icon = MenuIcon.Symbol(ZeronIcons.Pencil),
                    testTag = "row-menu-rename",
                ) { cmd.renameSession(row) },
                deleteRowItem(row, cmd, strings),
            ),
        )
    }
    val pinned = vm.pinned
    val pinItem = MenuItem(
        title = strings(if (pinned) R.string.swipe_unpin else R.string.swipe_pin),
        icon = MenuIcon.Symbol(if (pinned) ZeronIcons.PinSlash else ZeronIcons.Pin),
        testTag = if (pinned) "row-menu-unpin" else "row-menu-pin",
    ) { cmd.setPinned(row.id, !pinned) }
    val moveSection = MenuSection(
        title = strings(R.string.menu_move_to_section),
        icon = MenuIcon.Symbol(ZeronIcons.Folder),
        inline = false,
        children = sections.map { section ->
            MenuItem(
                title = section.name,
                icon = MenuIcon.Symbol(ZeronIcons.Folder),
                testTag = "row-menu-section-${section.id}",
            ) { cmd.move(row.id, section.id) }
        } + listOf(
            MenuItem(
                title = strings(R.string.menu_no_section),
                icon = MenuIcon.Symbol(ZeronIcons.Tray),
                testTag = "row-menu-no-section",
            ) { cmd.move(row.id, null) },
            // Creates the section only — never moves the chat (`:292-294`).
            MenuItem(
                title = strings(R.string.menu_new_section),
                icon = MenuIcon.Symbol(ZeronIcons.FolderBadgePlus),
                testTag = "row-menu-new-section",
            ) { cmd.newSection() },
        ),
    )
    return Menu(
        title = null,
        children = listOf(
            pinItem,
            moveSection,
            MenuItem(
                title = strings(R.string.menu_rename),
                icon = MenuIcon.Symbol(ZeronIcons.Pencil),
                testTag = "row-menu-rename",
            ) { cmd.renameSession(row) },
            MenuItem(
                title = strings(R.string.swipe_archive),
                icon = MenuIcon.Symbol(ZeronIcons.Archivebox),
                testTag = "row-menu-archive",
                // No slide: the row just disappears when the snapshot drops it.
            ) { cmd.archive(row.id) { } },
            deleteRowItem(row, cmd, strings),
        ),
    )
}

/** "Delete" (trash, destructive): the confirm first, never an immediate write. */
private fun deleteRowItem(row: SessionRow, cmd: SessionRowCommands, strings: (Int) -> String): MenuItem =
    MenuItem(
        title = strings(R.string.menu_delete),
        icon = MenuIcon.Symbol(ZeronIcons.Trash),
        destructive = true,
        testTag = "row-menu-delete",
    ) { cmd.deleteSession(row) }

// ---------------------------------------------------------------------------
// The row wrapper and the list hookup
// ---------------------------------------------------------------------------

/**
 * Provided around 41's row content by 44's wrapper: 41's pressed
 * highlight snaps to 0 while this row is lifted by the context menu (the
 * "double pressed fill" rule, ticket 16).
 */
val LocalRowLiftState = staticCompositionLocalOf<ContextMenuLiftState?> { null }

/**
 * Everything a list needs from this ticket: the one-open coordinator and
 * the row wrapper 41's `SessionList`/`sessionListItems` take. Put
 * [Modifier.sessionRowActions] on the element that contains the list.
 */
@Stable
class SessionRowActionsHookup(
    val coordinator: SwipeCoordinator,
    val rowWrapper: SessionRowWrapper,
)

/**
 * The hookup: the coordinator, the close-on-scroll hooks and 41's row
 * wrapper over [cmd]. Screens inside a `LargeTitleScaffold` pass the
 * scaffold's `listState` (their own LazyColumn state) and put
 * [Modifier.sessionRowActions] on the scaffold's modifier with
 * `swallowTopPx = { (topInset + 44.dp).toPx() }`; 41's standalone
 * `SessionList` host does the same on its own modifier.
 */
@Composable
fun rememberSessionRowActions(
    listState: LazyListState,
    cmd: SessionRowCommands = rememberSessionRowCommands(),
): SessionRowActionsHookup {
    val coordinator = rememberSwipeCoordinator()
    CloseSwipesOnScroll(listState, coordinator)
    val wrapper = remember(coordinator, cmd) { sessionRowWrapper(coordinator, cmd) }
    return remember(coordinator, cmd, wrapper) { SessionRowActionsHookup(coordinator, wrapper) }
}

/**
 * `swipeDismissOnTouch` over the hookup's coordinator — see
 * [Modifier.swipeDismissOnTouch].
 */
fun Modifier.sessionRowActions(
    hookup: SessionRowActionsHookup,
    swallowTopPx: () -> Float = { 0f },
): Modifier = swipeDismissOnTouch(hookup.coordinator, swallowTopPx)

/**
 * 41's row wrapper: `SwipeRow` + ticket 16's `Modifier.contextMenu`, with
 * the actions from [sessionSwipeActions] and the menu from
 * [sessionRowMenu] built at long-press time. The wrapper must not add
 * `Modifier.animateItem` or a fixed height (41 owns both, on the outer
 * item box).
 *
 * Also usable outside a LazyColumn (the gallery's plain `Column`, the
 * tests) — hosts there call it through a no-op
 * `androidx.compose.foundation.lazy.LazyItemScope`.
 */
internal fun sessionRowWrapper(coordinator: SwipeCoordinator, cmd: SessionRowCommands): SessionRowWrapper =
    { ctx, row ->
        // Latest VM and FFI record for every lambda — a commit uses the
        // row as it is when it fires, not when the wrapper was composed.
        val vm by rememberUpdatedState(ctx.vm)
        val rec by rememberUpdatedState(ctx.row)
        val palette = ZeronTheme.palette
        val res = LocalContext.current.resources
        val strings: (Int) -> String = { id -> res.getString(id) }
        val (lead, trail) = remember(ctx.archived, ctx.vm.pinned, palette) {
            sessionSwipeActions({ rec }, { vm }, ctx.archived, cmd, palette.accent, palette.secondary, strings)
        }
        val liftState = rememberContextMenuLiftState()
        CompositionLocalProvider(LocalSwipeCoordinator provides coordinator) {
            SwipeRow(id = ctx.vm.id, leading = lead, trailing = trail, enabled = !ctx.editing) { isOpen ->
                Box(
                    // Outside the row content's `clickable`, as ticket 16
                    // requires; no menu while editing (47) or open.
                    Modifier.contextMenu(
                        highlight = LiftHighlight.SessionRow,
                        enabled = !ctx.editing && !isOpen,
                        liftState = liftState,
                    ) {
                        // At long-press time: close any swipe, then build
                        // the menu from the current row/vm/sections.
                        coordinator.closeAll()
                        sessionRowMenu(rec, vm, ctx.archived, cmd.sections(), cmd, strings)
                    },
                ) {
                    CompositionLocalProvider(LocalRowLiftState provides liftState) {
                        row(Modifier)
                    }
                }
            }
        }
    }
