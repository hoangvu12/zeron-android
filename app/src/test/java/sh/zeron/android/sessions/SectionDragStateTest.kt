package sh.zeron.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SectionDragState] (#24, upstream #909's iPhone drag): which places a
 * dragged row may pass into, the section it lands in, the slot and write
 * of each drop (iOS `dropSlot` / `performDropWith`), the folded
 * destination to open, and the Dragging / Committing state machine.
 */
class SectionDragStateTest {

    private fun h(id: String) = DragEntry("h:$id", id, isHeader = true)
    private fun s(id: String, section: String) = DragEntry("s:$id", section, isHeader = false)

    private val target: (String) -> SessionDropTarget? = { id ->
        when {
            id == FrontPageSections.PINNED -> SessionDropTarget.Pinned
            id == FrontPageSections.RECENT -> SessionDropTarget.Recent
            id.startsWith("project:") || id.startsWith("home:") -> null
            else -> SessionDropTarget.Section(id)
        }
    }
    private val noneFolded: (String) -> Boolean = { false }

    /** Pinned (p1), Backend (b1), Recent (r1, r2). */
    private val page = listOf(
        h("pinned"), s("p1", "pinned"),
        h("backend"), s("b1", "backend"),
        h("recent"), s("r1", "recent"), s("r2", "recent"),
    )

    private fun keys(list: List<DragEntry>) = list.map { it.key }

    // ------------------------------------------------------------ moves

    @Test
    fun aRowMovedAboveAHeaderJoinsTheSectionAbove() {
        val moved = SectionDragState.moveEntry(page, from = 5, to = 4, dropTarget = target)!!
        assertEquals(listOf("h:pinned", "s:p1", "h:backend", "s:b1", "s:r1", "h:recent", "s:r2"), keys(moved))
        assertEquals("backend", moved[4].sectionId)
    }

    @Test
    fun nothingGoesAboveTheFirstHeader() {
        assertNull(SectionDragState.moveEntry(page, from = 3, to = 0, dropTarget = target))
    }

    @Test
    fun aProjectGroupTakesNoSessions() {
        val grouped = listOf(h("backend"), s("b1", "backend"), h("project:x"), s("x1", "project:x"))
        assertNull(SectionDragState.moveEntry(grouped, from = 1, to = 3, dropTarget = target))
    }

    @Test
    fun aHeaderlessRecentAcceptsTheTopSlot() {
        val recentOnly = listOf(s("r1", "recent"), s("r2", "recent"))
        assertEquals("recent", SectionDragState.moveEntry(recentOnly, 1, 0, target)!![0].sectionId)
    }

    // ------------------------------------------------------------ drops

    @Test
    fun recentIntoASectionPlacesAfterItsLastRow() {
        val final = SectionDragState.moveEntry(page, 5, 4, target)!!
        val drop = SectionDragState.dropFor(page, final, "r1", noneFolded, target)
        assertEquals(SectionDragDrop("r1", SessionDropWrite.Place("backend", "b1", null), unfold = null), drop)
    }

    @Test
    fun pinnedIntoRecentUnpins() {
        val final = SectionDragState.moveEntry(page, 1, 4, target)!! // p1 below the Recent header
        assertEquals("recent", final.first { it.key == "s:p1" }.sectionId)
        assertEquals(SessionDropWrite.Unpin, SectionDragState.dropFor(page, final, "p1", noneFolded, target)?.write)
    }

    @Test
    fun aSectionRowIntoPinnedPinsBetweenItsNeighbours() {
        val final = SectionDragState.moveEntry(page, 3, 1, target)!! // b1 above p1
        assertEquals(
            SessionDropWrite.PinAt(after = null, before = "p1"),
            SectionDragState.dropFor(page, final, "b1", noneFolded, target)?.write,
        )
    }

    @Test
    fun aFoldedDestinationOpensAndTakesTheRowAtItsTop() {
        val folded = listOf(h("backend"), h("recent"), s("r1", "recent"))
        val final = SectionDragState.moveEntry(folded, 2, 1, target)!!
        assertEquals(
            SectionDragDrop("r1", SessionDropWrite.Place("backend", null, null), unfold = "backend"),
            SectionDragState.dropFor(folded, final, "r1", { it == "backend" }, target),
        )
    }

    @Test
    fun anUnmovedRowMakesNoDrop() {
        assertNull(SectionDragState.dropFor(page, page, "r1", noneFolded, target))
    }

    // ------------------------------------------------------------ the state machine

    @Test
    fun aDragWithinRecentSnapsBackWithNoWrite() {
        val state = SectionDragState()
        state.start("r2", page)
        assertTrue(state.move("s:r2", "s:r1", target))
        assertNull("Recent sorts by activity", state.end(noneFolded, target))
        assertNull(state.order)
        assertFalse(state.committing)
    }

    @Test
    fun aRefusedMoveKeepsTheOrder() {
        val state = SectionDragState()
        state.start("b1", page)
        assertFalse(state.move("s:b1", "h:pinned", target))
        assertFalse("headers never move themselves", state.move("h:backend", "s:r1", target))
        assertEquals(keys(page), keys(state.order!!))
    }

    @Test
    fun aDropCommitsUntilTheModelShowsTheRowInItsNewSection() {
        val state = SectionDragState()
        state.start("r1", page)
        assertTrue(state.move("s:r1", "h:recent", target))
        val drop = state.end(noneFolded, target)
        assertEquals(SessionDropWrite.Place("backend", "b1", null), drop?.write)
        assertTrue(state.committing)
        // A model update that doesn't show the move yet keeps the local order.
        state.adopt(page)
        assertTrue(state.committing)
        // The echo: r1 now in Backend.
        state.adopt(listOf(h("pinned"), s("p1", "pinned"), h("backend"), s("b1", "backend"), s("r1", "backend"), h("recent"), s("r2", "recent")))
        assertFalse(state.committing)
        assertNull(state.order)
    }

    @Test
    fun aRefusedWriteSnapsBackAndAnOkWithoutEchoEndsOnTheNextUpdate() {
        val state = SectionDragState()
        state.start("r1", page)
        state.move("s:r1", "h:recent", target)
        state.end(noneFolded, target)
        state.committed(ok = false)
        assertNull(state.order)

        state.start("r1", page)
        state.move("s:r1", "h:recent", target)
        state.end(noneFolded, target)
        state.committed(ok = true)
        assertTrue(state.committing)
        state.adopt(page)
        assertFalse(state.committing)
    }

    @Test
    fun noNewDragStartsWhileCommitting() {
        val state = SectionDragState()
        state.start("r1", page)
        state.move("s:r1", "h:recent", target)
        state.end(noneFolded, target)
        state.start("r2", page)
        assertNull(state.draggedId)
    }

    @Test
    fun theEntriesOfAPageAreItsHeadersAndRows() {
        val header = SessionListItem.Header(SectionHeaderState("recent", "Recent", 0, collapsed = false, live = null))
        val plain = SessionListItem.PlainHeader("pinned", "Pinned")
        assertEquals(listOf(h("recent"), h("pinned")), dragEntries(listOf(header, plain)))
    }
}
