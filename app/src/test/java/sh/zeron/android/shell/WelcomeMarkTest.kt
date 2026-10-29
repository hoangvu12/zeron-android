package sh.zeron.android.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Zeron mark's cells match `zeron-logo.svg`: 34 distinct squares inside the view box. */
class WelcomeMarkTest {

    @Test
    fun thirtyFourDistinctCells() {
        assertEquals(34, ZeronMarkGeometry.cells.size)
        assertEquals(34, ZeronMarkGeometry.cells.toSet().size)
    }

    @Test
    fun everyCellFitsTheViewBox() {
        for ((x, y) in ZeronMarkGeometry.cells) {
            assertTrue("$x,$y", x >= 0f && x + ZeronMarkGeometry.CELL <= ZeronMarkGeometry.WIDTH)
            assertTrue("$x,$y", y >= 0f && y + ZeronMarkGeometry.CELL <= ZeronMarkGeometry.HEIGHT)
        }
    }

    @Test
    fun cellsReachEveryEdge() {
        val xs = ZeronMarkGeometry.cells.map { it.first }
        val ys = ZeronMarkGeometry.cells.map { it.second }
        assertEquals(0f, xs.min())
        assertEquals(ZeronMarkGeometry.WIDTH, xs.max() + ZeronMarkGeometry.CELL)
        assertEquals(0f, ys.min())
        assertEquals(ZeronMarkGeometry.HEIGHT, ys.max() + ZeronMarkGeometry.CELL)
    }
}
