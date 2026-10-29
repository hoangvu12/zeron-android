package sh.zeron.android.design.glass

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The glass menu's keyboard model (#62, `UnstyledDropdownMenu`'s
 * contract): ↓ / ↑ step without wrapping, Home / End jump, and from outside
 * the panel the arrows enter at the first / last row.
 */
class MenuKeyboardTest {

    @Test
    fun arrowsStepAndStopAtTheEnds() {
        assertEquals(1, menuMoveTarget(MenuMove.Next, focused = 0, count = 3))
        assertEquals(2, menuMoveTarget(MenuMove.Next, focused = 2, count = 3))
        assertEquals(1, menuMoveTarget(MenuMove.Previous, focused = 2, count = 3))
        assertEquals(0, menuMoveTarget(MenuMove.Previous, focused = 0, count = 3))
    }

    @Test
    fun fromOutsideTheArrowsEnterAtTheEnds() {
        assertEquals(0, menuMoveTarget(MenuMove.Next, focused = -1, count = 3))
        assertEquals(2, menuMoveTarget(MenuMove.Previous, focused = -1, count = 3))
        // A stale index (the level shrank) counts as outside.
        assertEquals(0, menuMoveTarget(MenuMove.Next, focused = 7, count = 3))
    }

    @Test
    fun homeAndEndJump() {
        assertEquals(0, menuMoveTarget(MenuMove.First, focused = 2, count = 3))
        assertEquals(2, menuMoveTarget(MenuMove.Last, focused = 0, count = 3))
    }

    @Test
    fun aLevelWithNothingFocusableHasNoTarget() {
        assertNull(menuMoveTarget(MenuMove.Next, focused = -1, count = 0))
        assertNull(menuMoveTarget(MenuMove.First, focused = -1, count = 0))
    }

    @Test
    fun theKeysMapToMoves() {
        assertEquals(MenuMove.Next, Key.DirectionDown.menuMove())
        assertEquals(MenuMove.Previous, Key.DirectionUp.menuMove())
        assertEquals(MenuMove.First, Key.MoveHome.menuMove())
        assertEquals(MenuMove.Last, Key.MoveEnd.menuMove())
        assertNull(Key.Tab.menuMove())
    }
}
