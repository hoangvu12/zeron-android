package sh.zeron.android.design.glass

import androidx.compose.runtime.Stable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key

/** One keyboard move inside an open menu (#62). */
enum class MenuMove { Next, Previous, First, Last }

/**
 * The row a [move] lands on among [count] focusable rows, from [focused]
 * (`-1`: focus is outside the panel). Next/Previous stop at the ends — no
 * wrap, like `UnstyledDropdownMenu` — and from outside they enter at the
 * first / last row. `null` when there is nothing to focus.
 */
fun menuMoveTarget(move: MenuMove, focused: Int, count: Int): Int? {
    if (count <= 0) return null
    val inside = focused in 0 until count
    return when (move) {
        MenuMove.First -> 0
        MenuMove.Last -> count - 1
        MenuMove.Next -> if (inside) (focused + 1).coerceAtMost(count - 1) else 0
        MenuMove.Previous -> if (inside) (focused - 1).coerceAtLeast(0) else count - 1
    }
}

/** The arrow / Home / End keys of a menu, or `null` for any other key. */
fun Key.menuMove(): MenuMove? = when (this) {
    Key.DirectionDown -> MenuMove.Next
    Key.DirectionUp -> MenuMove.Previous
    Key.MoveHome -> MenuMove.First
    Key.MoveEnd -> MenuMove.Last
    else -> null
}

/**
 * The open menu's keyboard model (#62, decision D7): Compose Unstyled's
 * `UnstyledDropdownMenu` contract on our glass panel. The level on screen
 * [bind]s one [FocusRequester] per focusable line (the back row, enabled
 * rows, submenu rows), in order, and reports which one holds focus;
 * ↓ / ↑ move between them, Home / End jump to the ends. From outside the
 * panel ([enter], the host seeing the key on its way to the composer's
 * field) ↓ / ↑ enter at the first / last row.
 *
 * Plain fields, not snapshot state: nothing draws from them.
 */
@Stable
class MenuKeyboard {
    private var requesters: List<FocusRequester> = emptyList()
    private var focused = -1

    internal fun bind(requesters: List<FocusRequester>) {
        if (this.requesters !== requesters) {
            this.requesters = requesters
            focused = -1
        }
    }

    internal fun focusChanged(slot: Int, hasFocus: Boolean) {
        if (hasFocus) {
            focused = slot
        } else if (focused == slot) {
            focused = -1
        }
    }

    /** Moves focus; true when a row took it. */
    fun move(move: MenuMove): Boolean {
        val target = menuMoveTarget(move, focused, requesters.size) ?: return false
        return runCatching { requesters[target].requestFocus() }.isSuccess
    }

    /** A key-down while a row holds focus: arrows, Home, End. True when consumed. */
    fun onKey(key: Key): Boolean {
        val move = key.menuMove() ?: return false
        move(move)
        return true
    }

    /** A key-down from outside the panel: ↓ / ↑ enter it. True when consumed. */
    fun enter(key: Key): Boolean = when (key) {
        Key.DirectionDown -> move(MenuMove.First)
        Key.DirectionUp -> move(MenuMove.Last)
        else -> false
    }
}
