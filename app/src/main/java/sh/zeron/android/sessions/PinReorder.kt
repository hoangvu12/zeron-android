package sh.zeron.android.sessions

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.unit.dp

/**
 * 41's session item key prefix — `SessionListItem.Session.key` is
 * `"s:<id>"`. [PinReorderState.move] receives the LazyColumn's raw item
 * keys (the library's `from.key` / `to.key`, which count the scaffold's
 * large-title item and any header too), so it strips this prefix and
 * matches the rest against the pin ids; every other key kind resolves to
 * nothing and the move is refused.
 */
private const val SessionItemKeyPrefix = "s:"

/**
 * One `movePin` write (the port of iOS `pinsReordered`'s per-insertion
 * call, `SessionListController.swift:477-488`): the dragged id plus its
 * new neighbours in the **final** order — `after` is the row now above
 * it, `before` the row now below it, `null` at either end. Zeron's pins
 * are workspace-wide, so any row may move anywhere in Pinned (#24 removed
 * Roboco's one-engine clamp, docs/port/01 I3).
 */
@Immutable
data class PinMove(val id: String, val after: String?, val before: String?) {
    override fun toString(): String = "move $id after=${after ?: "null"} before=${before ?: "null"}"
}

/**
 * The Edit-mode geometry and timings of the Pinned reorder (47). The
 * values marked `*` are **not in the Swift source** (UIKit renders the
 * reorder accessory itself); they are the ticket's estimates, to be
 * tuned against an iOS 26 simulator (Pinned folder, Edit tapped), then
 * frozen — a change is recorded in the ticket's Comments.
 *
 * The lift fill geometry (radius 16, insets 8 / 1) is measured: the
 * `SessionCell` background geometry, `ios:Threads/SessionCell.swift:82-83`.
 */
object EditMetrics {

    /** The drag-handle slot's width, at the row's trailing edge. `*` */
    val SlotWidth = 44.dp

    /** Between the slot's end and the row's end. `*` */
    val SlotEndPadding = 8.dp

    /** [SlotWidth] + [SlotEndPadding]: the total width taken from the content. `*` */
    val AccessoryInset = 52.dp

    /** The `line.3.horizontal` glyph's SF point size. `*` */
    const val GlyphPt = 17f

    /** The vertical separator's inset from the row's top and bottom. `*` */
    val SeparatorInsetV = 12.dp

    /**
     * `UICellAccessory.ReorderOptions.showsVerticalSeparator` defaults to
     * `true`; set to `false` if the simulator shows none, and update the
     * goldens. `*`
     */
    const val ShowVerticalSeparator = true

    /** The Edit toggle: slot width 0 ↔ 52 and alpha 0 ↔ 1, [Motion.EaseInOut]. `*` */
    const val EditToggleMs = 300

    /** Lift in (drag start), [Motion.EaseOut]. `*` */
    const val LiftInMs = 150

    /** Lift out (finger lifted), [Motion.EaseInOut], concurrent with the settle. `*` */
    const val LiftOutMs = 200

    /** The lifted row's shadow elevation, scaled by the lift progress. `*` */
    val LiftElevation = 8.dp

    /** The lifted row's fill corner radius (`SessionCell.swift:82`). */
    val LiftFillRadius = 16.dp

    /** The lifted row's fill inset, left and right (`SessionCell.swift:83`). */
    val LiftInsetHorizontal = 8.dp

    /** The lifted row's fill inset, top and bottom (`SessionCell.swift:83`). */
    val LiftInsetVertical = 1.dp
}

/**
 * The pure reorder rules (47): the `movePin` neighbour rule and the
 * accessibility step rule. No state, no Compose.
 */
object MovePinNeighbours {

    /**
     * The pure drop rule (the port of iOS `pinsReordered`): the
     * `PinMove` for dropping [draggedId] between [original] (the order at
     * drag start) and [final] (the order at release), or `null` when
     * nothing moved (no call) or the id vanished. `after` / `before` are
     * the dragged row's final neighbours.
     */
    fun drop(original: List<String>, final: List<String>, draggedId: String): PinMove? {
        if (final == original) return null
        val i = final.indexOf(draggedId)
        if (i < 0) return null
        return PinMove(draggedId, final.getOrNull(i - 1), final.getOrNull(i + 1))
    }

    /**
     * The accessibility step (TalkBack cannot drag): the swap of the row
     * at [index] one step up (`direction = -1`) or down (`+1`), as
     * `(newOrder, move)`, or `null` at either end. The returned move is
     * exactly what the equivalent drag would have dispatched.
     */
    fun step(order: List<String>, index: Int, direction: Int): Pair<List<String>, PinMove>? {
        if (index !in order.indices) return null
        val target = index + direction
        if (target !in order.indices) return null
        val newOrder = order.toMutableList()
        val moved = newOrder.removeAt(index)
        newOrder.add(target, moved)
        val move = drop(order, newOrder, moved) ?: return null
        return newOrder.toList() to move
    }
}

/**
 * The Pinned reorder's local state machine (47) — the Zeron port of
 * pr621's `PinnedRows` logic (not its visuals): a local [order] that
 * mirrors the snapshot **only while no drag or commit is active**, and a
 * third state, [committing], while ticket 42's asynchronous `movePin` is
 * in flight.
 *
 * | State | Snapshot arrives | Drag starts | Drag ends | `onResult` |
 * |---|---|---|---|---|
 * | Idle | `order = rows` | → Dragging; remember `original`, `draggedId` | — | — |
 * | Dragging | stashed, not applied | — | null → Idle (+ stash); non-null → Committing, dispatch | — |
 * | Committing | stashed, not applied | blocked (handles disabled) | — | ok → Idle (adopt stash); else → Idle, snap back |
 *
 * Never replace [order] while a drag or a commit is active: the dragged
 * item's key can vanish mid-gesture (the library throws or jumps), and a
 * mid-flight echo would snap the list back for a frame.
 */
@Stable
class PinReorderState(initial: List<String>) {

    /**
     * The live local order. The drag mutates it in place (the library's
     * `onMove` → [move]); a snapshot replaces it only while idle
     * ([adopt]). The core's stored order is the truth — every drop is
     * committed when it happens, as on iOS.
     */
    val order: SnapshotStateList<String> = mutableStateListOf<String>().apply { addAll(initial) }

    /** The row being dragged, or null (Idle / Committing). */
    var draggedId: String? by mutableStateOf(null)
        private set

    /** A dropped move is in flight (handles disabled, snapshots stashed). */
    var committing: Boolean by mutableStateOf(false)
        private set

    /** The order at drag start — [MovePinNeighbours.drop]'s `original`. */
    private var original: List<String>? = null

    /** A snapshot that arrived while Dragging or Committing (not yet applied). */
    private var stash: List<String>? = null

    /** The most recent snapshot rows seen — the snap-back target on failure. */
    private var lastRows: List<String> = initial.toList()

    /**
     * A workspace snapshot arrived. Idle (and not equal to the current
     * order — equal lists are ignored): `order = rows`. Dragging or
     * Committing: stash as the latest snapshot instead; it is adopted by
     * [end] (no move), by [committed] (a successful write's echo), or
     * never (a failed write snaps back to it).
     */
    fun adopt(rows: List<String>) {
        if (ids(rows) == ids(order)) {
            // Equal: no order change — but still the latest rows seen, so a
            // later failed write snaps back to them, not to an older frame.
            lastRows = rows
            return
        }
        lastRows = rows
        if (draggedId != null || committing) {
            stash = rows
        } else {
            order.clear()
            order.addAll(rows)
        }
    }

    /**
     * A drag started on `id` (`draggableHandle`'s `onDragStarted`):
     * remembers `original` (the order at start) and `draggedId`. No-op
     * while committing — the handles are disabled there, so this is pure
     * defence.
     */
    fun start(id: String) {
        if (committing) return
        original = order.toList()
        draggedId = id
    }

    /**
     * The library's `onMove`, by **key** — never by the LazyColumn's
     * item index (the list may have non-session items before the rows,
     * such as the scaffold's large-title item): resolves both keys
     * against [order] (41's session keys are `"s:<id>"`; anything else —
     * a header, a folder, a spacer — is refused) and moves the entry in place.
     *
     * @return true if the order changed
     */
    fun move(fromKey: Any, toKey: Any): Boolean {
        val from = indexOfKey(fromKey)
        val to = indexOfKey(toKey)
        if (from < 0 || to < 0) return false
        if (to == from) return false
        order.add(to, order.removeAt(from))
        return true
    }

    /**
     * The drag ended (`draggableHandle`'s `onDragStopped`): the
     * `PinMove` to dispatch, or null when nothing moved (→ Idle; a
     * stashed snapshot is adopted now). Non-null → Committing. The drag
     * fields are reset either way.
     */
    fun end(): PinMove? {
        val id = draggedId
        val originalAtStart = original
        draggedId = null
        original = null
        if (id == null || originalAtStart == null) return null
        val move = MovePinNeighbours.drop(originalAtStart, order.toList(), id)
        if (move == null) {
            adoptStash()
            return null
        }
        committing = true
        return move
    }

    /**
     * Begin a commit without a drag (an accessibility step): sets the
     * order to [newOrder] and enters Committing — the dispatch's
     * `onResult` lands in [committed] like any drop.
     */
    fun applyStep(newOrder: List<String>) {
        order.clear()
        order.addAll(newOrder)
        committing = true
    }

    /**
     * `onResult` of the dispatched [PinMove]. Ok → Idle; adopt the stash
     * if one arrived (the engine echo), else keep the local order until
     * the next snapshot. Any other outcome → Idle and snap back to the
     * latest snapshot (the stash, or the last adopted rows) — ticket 42
     * shows the toast.
     */
    fun committed(ok: Boolean) {
        committing = false
        val stashed = stash
        stash = null
        if (ok) {
            if (stashed != null) {
                lastRows = stashed
                order.clear()
                order.addAll(stashed)
            }
        } else {
            val restore = stashed ?: lastRows
            lastRows = restore
            order.clear()
            order.addAll(restore)
        }
    }

    /** Applies and clears the stash, if one exists. */
    private fun adoptStash() {
        stash?.let { rows ->
            lastRows = rows
            order.clear()
            order.addAll(rows)
        }
        stash = null
    }

    /** The key → index in [order] (41's `"s:<id>"` keys and bare ids both resolve). */
    private fun indexOfKey(key: Any): Int {
        if (key !is String) return -1
        val id = key.removePrefix(SessionItemKeyPrefix)
        return order.indexOf(id)
    }

    private fun ids(rows: List<String>): List<String> = rows
}
