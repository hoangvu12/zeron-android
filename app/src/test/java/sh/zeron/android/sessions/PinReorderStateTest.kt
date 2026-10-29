package sh.zeron.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PinReorderState] (android-ui-parity #47) — the Idle / Dragging /
 * Committing table: adopts replace or stash, moves resolve by key (never
 * by item index) anywhere in the list (#24 removed Roboco's one-engine
 * clamp), `end` returns the drop and adopts the stash
 * when nothing moved, `committed` adopts or snaps back, `applyStep`
 * commits an accessibility step. Plain JUnit 4, no Robolectric — the
 * snapshot state is pure Kotlin.
 */
class PinReorderStateTest {

    private fun ids(state: PinReorderState): List<String> = state.order.toList()

    private val pins = listOf(
        "a1", "a2", "a3", "b1", "b2",
    )

    @Test
    fun `adopt while idle replaces the order`() {
        val state = PinReorderState(listOf("a1"))
        state.adopt(listOf("a1", "a2"))
        assertEquals(listOf("a1", "a2"), ids(state))
    }

    @Test
    fun `adopt of an equal list is ignored`() {
        val state = PinReorderState(pins)
        val order = state.order
        state.adopt(pins.toList())
        assertSame(order, state.order) // no clear/addAll churn
        assertEquals(listOf("a1", "a2", "a3", "b1", "b2"), ids(state))
    }

    @Test
    fun `adopt while dragging is stashed, not applied`() {
        val state = PinReorderState(pins)
        state.start("a1")
        state.adopt(listOf("a1", "a2", "a3", "b1"))
        assertEquals(listOf("a1", "a2", "a3", "b1", "b2"), ids(state))
        // The stash surfaces when the drag ends without a move.
        assertNull(state.end())
        assertEquals(listOf("a1", "a2", "a3", "b1"), ids(state))
    }

    @Test
    fun `move ignores keys outside the list and moves anywhere`() {
        val state = PinReorderState(pins)
        state.start("a1")
        // 41's session keys are "s:<id>"; anything else (a spacer, a
        // header, the scaffold's large-title item) resolves to nothing.
        assertFalse(state.move("s:spacer", "s:a1"))
        assertFalse(state.move("s:a1", "s:__large_title"))
        // Zeron's pins are workspace-wide: a1 goes all the way to the end.
        assertTrue(state.move("s:a1", "s:b2"))
        assertEquals(listOf("a2", "a3", "b1", "b2", "a1"), ids(state))
        assertFalse("a move onto itself changes nothing", state.move("s:a1", "s:a1"))
    }

    @Test
    fun `end returns the drop result for the new neighbours`() {
        val state = PinReorderState(pins)
        state.start("a1")
        assertTrue(state.move("s:a1", "s:a3"))
        val move = state.end()
        assertEquals(PinMove("a1", after = "a3", before = "b1"), move)
        assertNull(state.draggedId)
    }

    @Test
    fun `a null end adopts the stash when there was no move`() {
        val state = PinReorderState(pins)
        state.start("a1")
        state.adopt(listOf("b1", "b2", "a1", "a2", "a3"))
        assertNull(state.end())
        assertEquals(listOf("b1", "b2", "a1", "a2", "a3"), ids(state))
        assertFalse(state.committing)
    }

    @Test
    fun `after a non-null end the state is committing and adopts are stashed`() {
        val state = PinReorderState(pins)
        state.start("a1")
        assertTrue(state.move("s:a1", "s:a3"))
        assertNotNull(state.end())
        assertTrue(state.committing)
        state.adopt(listOf("a2", "a3", "a1", "b1", "b2"))
        assertEquals(listOf("a2", "a3", "a1", "b1", "b2"), ids(state)) // the echo is stashed
        // A second drag cannot start while committing (the handles are
        // disabled; `start` is pure defence).
        state.start("a2")
        assertNull(state.draggedId)
    }

    @Test
    fun `committed ok adopts the stash`() {
        val state = PinReorderState(pins)
        state.start("a1")
        assertTrue(state.move("s:a1", "s:a3"))
        assertNotNull(state.end())
        // The echo of the drop plus a desktop-side swap of the B block —
        // the latest snapshot, stashed while the write was in flight.
        state.adopt(listOf("a2", "a3", "a1", "b2", "b1"))
        state.committed(ok = true)
        assertFalse(state.committing)
        assertEquals(listOf("a2", "a3", "a1", "b2", "b1"), ids(state))
    }

    @Test
    fun `committed ok keeps the order when no snapshot arrived`() {
        val state = PinReorderState(pins)
        state.start("a2")
        assertTrue(state.move("s:a2", "s:a1"))
        assertEquals(PinMove("a2", after = null, before = "a1"), state.end())
        state.committed(ok = true)
        assertEquals(listOf("a2", "a1", "a3", "b1", "b2"), ids(state))
    }

    @Test
    fun `committed not ok restores the latest stash`() {
        val state = PinReorderState(pins)
        state.start("a1")
        assertTrue(state.move("s:a1", "s:a3"))
        assertNotNull(state.end())
        state.adopt(listOf("b1", "b2", "a1", "a2", "a3"))
        state.committed(ok = false)
        assertFalse(state.committing)
        assertEquals(listOf("b1", "b2", "a1", "a2", "a3"), ids(state))
    }

    @Test
    fun `committed not ok without a stash snaps back to the last snapshot`() {
        val state = PinReorderState(pins)
        // A first successful drop whose echo never arrives, then a failed
        // one: the snap-back target is the last adopted rows, not the
        // post-drop local order.
        state.start("a1")
        assertTrue(state.move("s:a1", "s:a3"))
        assertNotNull(state.end())
        state.committed(ok = true)
        state.start("a2")
        assertTrue(state.move("s:a2", "s:a3"))
        assertNotNull(state.end())
        state.committed(ok = false)
        assertEquals(listOf("a1", "a2", "a3", "b1", "b2"), ids(state))
    }

    @Test
    fun `applyStep sets the order and enters committing`() {
        val state = PinReorderState(listOf("a1", "a2", "a3"))
        val step = MovePinNeighbours.step(state.order.toList(), 1, 1)
        assertNotNull(step)
        val (newOrder, move) = step!!
        state.applyStep(newOrder)
        assertTrue(state.committing)
        assertEquals(listOf("a1", "a3", "a2"), ids(state))
        state.committed(ok = true)
        assertEquals(PinMove("a2", after = "a3", before = null), move)
        assertEquals(listOf("a1", "a3", "a2"), ids(state))
    }
}
