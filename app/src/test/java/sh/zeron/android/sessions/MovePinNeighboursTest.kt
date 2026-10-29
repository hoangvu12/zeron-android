package sh.zeron.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [MovePinNeighbours] (android-ui-parity #47, #24): the `movePin`
 * neighbour rule of iOS `pinsReordered` and the accessibility step. Zeron's
 * pins are workspace-wide, so neighbours are simply the rows either side
 * (Roboco's one-engine clamp and its cases are gone, docs/port/01 I3).
 */
class MovePinNeighboursTest {

    private val pins = listOf("a", "b", "c", "d")

    @Test
    fun noMoveMakesNoCall() {
        assertNull(MovePinNeighbours.drop(pins, pins, "b"))
    }

    @Test
    fun theNeighboursAreTheFinalRowsEitherSide() {
        assertEquals(PinMove("a", after = "c", before = "d"), MovePinNeighbours.drop(pins, listOf("b", "c", "a", "d"), "a"))
        assertEquals(PinMove("d", after = null, before = "a"), MovePinNeighbours.drop(pins, listOf("d", "a", "b", "c"), "d"))
        assertEquals(PinMove("a", after = "d", before = null), MovePinNeighbours.drop(pins, listOf("b", "c", "d", "a"), "a"))
    }

    @Test
    fun aVanishedIdMakesNoCall() {
        assertNull(MovePinNeighbours.drop(pins, listOf("b", "c", "d"), "a"))
    }

    @Test
    fun aStepSwapsWithTheNeighbourAndDispatchesTheSameMove() {
        val (order, move) = MovePinNeighbours.step(pins, 1, -1)!!
        assertEquals(listOf("b", "a", "c", "d"), order)
        assertEquals(PinMove("b", after = null, before = "a"), move)
        val (down, moveDown) = MovePinNeighbours.step(pins, 2, 1)!!
        assertEquals(listOf("a", "b", "d", "c"), down)
        assertEquals(PinMove("c", after = "d", before = null), moveDown)
    }

    @Test
    fun noStepPastEitherEnd() {
        assertNull(MovePinNeighbours.step(pins, 0, -1))
        assertNull(MovePinNeighbours.step(pins, 3, 1))
        assertNull(MovePinNeighbours.step(pins, 9, 1))
    }
}
