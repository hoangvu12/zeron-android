package sh.zeron.android.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bottom bar's [SearchTabState]: iOS `isActive` (focus or
 * `activateSearch()` turns it on, only Close turns it off and clears the
 * query), the activation token, and `query` mirroring `text` raw. Plain JVM
 * — every piece is snapshot state.
 */
class SearchTabStateTest {

    @Test
    fun aFreshStateIsInactiveAndEmpty() {
        val s = SearchTabState()
        assertFalse(s.active)
        assertEquals("", s.query)
        assertEquals(0, s.activationToken)
    }

    @Test
    fun activateTurnsTheSearchOnAndBumpsTheToken() {
        val s = SearchTabState()
        s.activate()
        assertTrue(s.active)
        assertEquals(1, s.activationToken)
        s.activate()
        assertEquals(2, s.activationToken)
    }

    @Test
    fun focusTurnsTheSearchOnWithoutAToken() {
        val s = SearchTabState()
        s.onFieldFocused()
        assertTrue(s.active)
        assertEquals(0, s.activationToken)
    }

    @Test
    fun closeClearsTheQueryAndEndsTheSearch() {
        val s = SearchTabState()
        s.onFieldFocused()
        s.text.edit { append("tool") }
        s.close()
        assertFalse(s.active)
        assertEquals("", s.query)
    }

    @Test
    fun aConsumedTokenDoesNotRefocus() {
        val s = SearchTabState()
        s.activate()
        // The field acted on it (a recreated composition must not refocus).
        s.consumedToken = s.activationToken
        assertEquals(1, s.consumedToken)
        s.activate()
        assertEquals(2, s.activationToken)
        assertEquals(1, s.consumedToken)
    }

    @Test
    fun queryMirrorsTheRawText() {
        val s = SearchTabState()
        s.text.edit { append("ze") }
        assertEquals("ze", s.query)
        // Raw, never trimmed: the spaces belong to the list's search.
        s.text.edit { append(" ") }
        assertEquals("ze ", s.query)
    }
}
