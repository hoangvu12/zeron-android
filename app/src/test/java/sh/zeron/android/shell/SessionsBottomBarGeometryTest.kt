package sh.zeron.android.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one-row bar's search expansion, plain JUnit, on a 370 dp row (a
 * 402 dp phone minus the 16 dp side margins): at rest Settings · field ·
 * New session, active field · Close, the field's trailing edge fixed.
 */
class SessionsBottomBarGeometryTest {

    private val g = SessionsBottomBarGeometry
    private val row = 370f

    @Test
    fun restLayoutIsSettingsFieldNewSession() {
        assertEquals(0f, g.leadingX(0f), 1e-4f)
        assertEquals(56f, g.fieldX(0f), 1e-4f)
        assertEquals(258f, g.fieldWidth(row, 0f), 1e-4f)
        assertEquals(322f, g.trailingX(row), 1e-4f)
        assertEquals(1f, g.restAlpha(0f), 1e-4f)
        assertEquals(0f, g.closeAlpha(0f), 1e-4f)
    }

    @Test
    fun activeLayoutIsFieldThenClose() {
        assertEquals(-56f, g.leadingX(1f), 1e-4f)
        assertEquals(0f, g.fieldX(1f), 1e-4f)
        assertEquals(314f, g.fieldWidth(row, 1f), 1e-4f)
        assertEquals(0f, g.restAlpha(1f), 1e-4f)
        assertEquals(1f, g.closeAlpha(1f), 1e-4f)
    }

    @Test
    fun theFieldsTrailingEdgeNeverMoves() {
        for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f, 1.006f)) {
            assertEquals("p = $p", row - 56f, g.fieldX(p) + g.fieldWidth(row, p), 1e-3f)
        }
    }

    @Test
    fun newSessionHandsOverToCloseAtTheMidpoint() {
        assertEquals(0.5f, g.restAlpha(0.25f), 1e-4f)
        assertEquals(0f, g.restAlpha(0.5f), 1e-4f)
        assertEquals(0f, g.closeAlpha(0.5f), 1e-4f)
        assertEquals(0.5f, g.closeAlpha(0.75f), 1e-4f)
    }

    @Test
    fun aNarrowRowKeepsTheFieldAtLeastACircle() {
        assertEquals(48f, g.fieldWidth(100f, 0f), 1e-4f)
    }
}
