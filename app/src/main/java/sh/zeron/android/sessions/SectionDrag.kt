package sh.zeron.android.sessions

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlinx.coroutines.withTimeoutOrNull
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState
import sh.zeron.android.design.Haptic
import sh.zeron.android.design.LocalContextMenuHost
import sh.zeron.android.design.LocalFeedback

/**
 * Dragging a session between the front page's sections — the gesture half
 * of upstream #909 (`Threads/SessionListController.swift:353-462`); the
 * slot and write rules are [SessionDrop]'s.
 *
 * While a drag (or its write) is in flight the list follows a local
 * [SectionDragState.order] of `(key, section)` entries instead of the
 * model: the dragged row moves through it as the reorderable library
 * reports overlaps, and its section is whatever sits above it (a header,
 * or a row of that section). A move that would land it above the first
 * header, or in a place that takes no sessions (a project group), is
 * refused — iOS cancels those proposals.
 */

/** One list entry as the drag sees it: its LazyColumn key, its section, whether it is a header. */
@Immutable
data class DragEntry(val key: String, val sectionId: String, val isHeader: Boolean) {
    val sessionId: String? get() = if (isHeader) null else key.removePrefix(SESSION_KEY_PREFIX)
}

private const val SESSION_KEY_PREFIX = "s:"

/** The front page's entries in display order: headers and sessions (folders never appear there). */
fun dragEntries(items: List<SessionListItem>): List<DragEntry> = items.mapNotNull { item ->
    when (item) {
        is SessionListItem.Header -> DragEntry(item.key, item.state.id, isHeader = true)
        is SessionListItem.PlainHeader -> DragEntry(item.key, item.sectionId, isHeader = true)
        is SessionListItem.Session -> DragEntry(item.key, item.sectionId, isHeader = false)
        is SessionListItem.Folder -> null
    }
}

/** A finished drag: the session, the write, and the folded destination to open (iOS unfolds it). */
@Immutable
data class SectionDragDrop(val chatId: String, val write: SessionDropWrite, val unfold: String?)

/**
 * The drag's state machine, pure over [DragEntry] lists (no Compose
 * besides snapshot state):
 *
 * | State | Model update | `start` | `end` | `committed` |
 * |---|---|---|---|---|
 * | Idle | (followed) | → Dragging, order = entries | — | — |
 * | Dragging | kept local | — | no move → Idle; a drop → Committing | — |
 * | Committing | Idle once it shows the row in its new section, or the first update after an Ok | — | — | not Ok → Idle |
 */
@Stable
class SectionDragState {

    /** The local order while dragging or committing; null = follow the model. */
    var order: List<DragEntry>? by mutableStateOf(null)
        private set

    /** The dragged session id, or null. */
    var draggedId: String? by mutableStateOf(null)
        private set

    /** A drop's write is in flight (or awaiting its echo). */
    var committing: Boolean by mutableStateOf(false)
        private set

    private var original: List<DragEntry> = emptyList()
    private var committedId: String? = null
    private var targetSection: String? = null
    private var writeReturned = false

    /** A long press lifted [id] and the finger moved: follow [entries] locally from now. */
    fun start(id: String, entries: List<DragEntry>) {
        if (committing) return
        original = entries
        order = entries
        draggedId = id
    }

    /**
     * The library's `onMove` by keys: moves the dragged entry to [toKey]'s
     * place when the section it would land in takes sessions
     * ([dropTarget] non-null). Returns whether the order changed.
     */
    fun move(fromKey: Any, toKey: Any, dropTarget: (sectionId: String) -> SessionDropTarget?): Boolean {
        val current = order ?: return false
        val from = current.indexOfFirst { it.key == fromKey }
        val to = current.indexOfFirst { it.key == toKey }
        if (from < 0 || to < 0 || from == to || current[from].isHeader) return false
        val moved = moveEntry(current, from, to, dropTarget) ?: return false
        order = moved
        return true
    }

    /**
     * The finger lifted: the drop to perform, or null (nothing moved, a
     * drag within Recent, or no drag) → Idle at once, snapping back.
     */
    fun end(collapsed: (sectionId: String) -> Boolean, dropTarget: (sectionId: String) -> SessionDropTarget?): SectionDragDrop? {
        val id = draggedId
        val final = order
        draggedId = null
        if (id == null || final == null) return idle()
        val drop = dropFor(original, final, id, collapsed, dropTarget)
        if (drop == null || drop.write == SessionDropWrite.NoMove) return idle()
        committedId = id
        targetSection = final.first { it.sessionId == id }.sectionId
        writeReturned = false
        committing = true
        return drop
    }

    /** The write returned: a refusal snaps back now; an Ok waits for the echo. */
    fun committed(ok: Boolean) {
        if (!committing) return
        if (ok) writeReturned = true else idle()
    }

    /** The model's entries changed: while committing, drop the local order once they show the move (or after an Ok). */
    fun adopt(entries: List<DragEntry>) {
        if (!committing) return
        val dragged = entries.firstOrNull { it.sessionId == committedId }
        if (writeReturned || dragged?.sectionId == targetSection) idle()
    }

    private fun idle(): SectionDragDrop? {
        order = null
        committing = false
        committedId = null
        targetSection = null
        writeReturned = false
        return null
    }

    companion object {
        /**
         * [entries] with the entry at [from] moved to [to]'s place, its
         * section re-read from its new neighbours — or null when that
         * place takes no sessions (above every header, a project group).
         */
        fun moveEntry(
            entries: List<DragEntry>,
            from: Int,
            to: Int,
            dropTarget: (sectionId: String) -> SessionDropTarget?,
        ): List<DragEntry>? {
            val list = entries.toMutableList()
            val entry = list.removeAt(from)
            list.add(to, entry)
            val section = sectionAt(list, to) ?: return null
            if (dropTarget(section) == null) return null
            list[to] = entry.copy(sectionId = section)
            return list
        }

        /**
         * The section a row at [index] belongs to: the header above it, or
         * the section of the row above it; at the very top, the following
         * row's section when that section has no header (a header-less
         * Recent), else none (iOS: "a drop above a section's header has
         * no slot").
         */
        fun sectionAt(list: List<DragEntry>, index: Int): String? {
            val above = list.getOrNull(index - 1)
            if (above != null) return above.sectionId
            val below = list.getOrNull(index + 1) ?: return null
            return if (below.isHeader) null else below.sectionId
        }

        /**
         * The drop for [id] going from [original] to [final] (iOS
         * `performDropWith`): its destination, its index among that
         * section's visible rows and their neighbours ([sessionDropSlot]),
         * the write ([sessionDropWrite]) and the folded destination to
         * open. Null when the row did not move.
         */
        fun dropFor(
            original: List<DragEntry>,
            final: List<DragEntry>,
            id: String,
            collapsed: (sectionId: String) -> Boolean,
            dropTarget: (sectionId: String) -> SessionDropTarget?,
        ): SectionDragDrop? {
            val key = SESSION_KEY_PREFIX + id
            if (final.map { it.key } == original.map { it.key }) return null
            val before = original.firstOrNull { it.key == key } ?: return null
            val after = final.firstOrNull { it.key == key } ?: return null
            val target = dropTarget(after.sectionId) ?: return null
            val rows = final.filter { !it.isHeader && it.sectionId == after.sectionId }.mapNotNull { it.sessionId }
            val slot = sessionDropSlot(target, rows, id, rows.indexOf(id))
            val write = sessionDropWrite(dropTarget(before.sectionId), slot)
            return SectionDragDrop(id, write, unfold = after.sectionId.takeIf(collapsed))
        }
    }
}

/**
 * iOS's lift-then-drag for a row (UIKit's drag interaction on top of the
 * context menu): the long press itself is the context menu's (it lifts
 * the row and opens the menu); only when the finger then **moves** does
 * the drag start — [onLiftedMove] closes the menu first — and the row
 * follows the finger. A move before the long press is a scroll or a
 * swipe and cancels; a release without moving leaves the menu open.
 *
 * Observes on the Initial pass, so it must sit on a **parent** of the
 * row's `contextMenu` modifier (which swallows the rest of the touch
 * after its long press on the same pass, child-side).
 */
class LiftThenDragDetector(private val onLiftedMove: () -> Unit) : DragGestureDetector {
    override suspend fun PointerInputScope.detect(
        onDragStart: (Offset) -> Unit,
        onDragEnd: () -> Unit,
        onDragCancel: () -> Unit,
        onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
    ) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val slop = viewConfiguration.touchSlop
            // 1. The long press: still, one finger, held for the system timeout.
            val broken = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size > 1) return@withTimeoutOrNull true
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                    if (!change.pressed) return@withTimeoutOrNull true
                    if ((change.position - down.position).getDistance() > slop) return@withTimeoutOrNull true
                }
                @Suppress("UNREACHABLE_CODE")
                true
            }
            if (broken != null) return@awaitEachGesture
            // 2. Lifted: wait for the finger to move (a release keeps the menu).
            var moved: PointerInputChange? = null
            while (moved == null) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed) return@awaitEachGesture
                if ((change.position - down.position).getDistance() > slop) moved = change
            }
            val lifted = checkNotNull(moved)
            onLiftedMove()
            onDragStart(down.position)
            var last = lifted.position
            lifted.consume()
            onDrag(lifted, last - down.position)
            // 3. Dragging until release.
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null) {
                    onDragCancel()
                    return@awaitEachGesture
                }
                if (!change.pressed) {
                    change.consume()
                    onDragEnd()
                    return@awaitEachGesture
                }
                val delta = change.position - last
                last = change.position
                change.consume()
                onDrag(change, delta)
            }
        }
    }
}

/**
 * Everything `sessionListItems` needs for the front page's drag (#24):
 * the library state over the list's `LazyListState`, the local order,
 * the detector and the per-row gate ([canDrag]: rows of Pinned, a user
 * section or Recent — iOS `itemsForBeginning` needs a drop target).
 */
@Stable
class SectionDragHookup(
    val lazyState: ReorderableLazyListState,
    val state: SectionDragState,
    val detector: DragGestureDetector,
    val canDrag: (sectionId: String) -> Boolean,
    /** Called with the dragged id when the drag starts (the host snapshots the entries). */
    val onDragStarted: (chatId: String) -> Unit,
    /** Called when the finger lifts (the host runs the drop). */
    val onDragStopped: () -> Unit,
)

/**
 * What front-page section [sectionId] means as a destination (iOS
 * `SessionsViewController.dropTarget`): Pinned, Recent or a user section;
 * a project group or anything else takes no sessions.
 */
internal fun frontDropTarget(sectionId: String, actions: SessionsScreenActions): SessionDropTarget? = when (sectionId) {
    FrontPageSections.PINNED -> SessionDropTarget.Pinned
    FrontPageSections.RECENT -> SessionDropTarget.Recent
    else -> if (actions.userSectionName(sectionId) != null) SessionDropTarget.Section(sectionId) else null
}

/**
 * The front page's drag hookup (#24) over the list's own [listState]:
 * the library state (auto-scroll starts at the visible edges,
 * [scrollThresholdPadding]), the local order, the detector that closes
 * the context menu as the drag starts, and the drop — the Select haptic,
 * the folded destination opened, then the write on ticket 42's serial
 * lane (`applyDrop`); a refused write snaps the row back.
 */
@Composable
internal fun rememberSectionDrag(
    listState: LazyListState,
    items: List<SessionListItem>,
    actions: SessionsScreenActions,
    scrollThresholdPadding: PaddingValues,
): SectionDragHookup {
    val state = remember { SectionDragState() }
    val latestItems = rememberUpdatedState(items)
    val latestActions = rememberUpdatedState(actions)
    val entries = remember(items) { dragEntries(items) }
    LaunchedEffect(entries) { state.adopt(entries) }
    val rowActions = LocalRowActions.current
    val feedback = LocalFeedback.current
    val menuHost = LocalContextMenuHost.current
    val dropTarget: (String) -> SessionDropTarget? = remember {
        { sectionId -> frontDropTarget(sectionId, latestActions.value) }
    }
    val lazyState = rememberReorderableLazyListState(
        lazyListState = listState,
        scrollThresholdPadding = scrollThresholdPadding,
    ) { from, to -> state.move(from.key, to.key, dropTarget) }
    val detector = remember(menuHost) { LiftThenDragDetector { menuHost?.dismiss(animated = false) } }
    return remember(lazyState, state, detector, rowActions, feedback) {
        SectionDragHookup(
            lazyState = lazyState,
            state = state,
            detector = detector,
            canDrag = { sectionId -> dropTarget(sectionId) != null },
            onDragStarted = { chatId -> state.start(chatId, dragEntries(latestItems.value)) },
            onDragStopped = {
                val folded = latestItems.value
                    .mapNotNullTo(HashSet()) { (it as? SessionListItem.Header)?.state?.takeIf { s -> s.collapsed }?.id }
                state.end(collapsed = { it in folded }, dropTarget = dropTarget)?.let { drop ->
                    feedback.haptic(Haptic.Select)
                    drop.unfold?.let { latestActions.value.unfoldSection(it) }
                    rowActions.applyDrop(drop.chatId, drop.write) { outcome ->
                        state.committed(outcome == ActionOutcome.Ok)
                    }
                }
            },
        )
    }
}
