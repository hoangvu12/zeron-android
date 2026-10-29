package sh.zeron.android.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #26 (iOS #981): a page moves between two stacks as it is — [NavStack.release]
 * hands the entry over without closing it, [NavStack.adopt] puts the same
 * entry on the other stack. Plain JVM, hostless stacks (every op completes
 * synchronously).
 */
class NavStackMoveTest {

    private data class Page(val name: String) : NavRoute

    /** Counts closes of the entry's retained state (the live session's stand-in). */
    private class Closeable : AutoCloseable {
        var closed = 0
        override fun close() {
            closed++
        }
    }

    @Test
    fun aReleasedPageIsNotClosedAndIsAdoptedAsItIs() {
        val phone = NavStack(Page("list"))
        val detail = NavStack(Page("empty"))
        val session = phone.push(Page("session"), animated = false)
        val live = session.retain("live") { Closeable() }

        assertTrue(phone.release(session))
        assertEquals(listOf(Page("list")), phone.entries.map { it.route })
        assertFalse(session.isClosed)

        detail.adopt(session, asRoot = true)
        assertSame(session, detail.top)
        assertEquals(1, detail.entries.size)
        assertFalse(session.isClosed)
        assertEquals(0, live.closed)
        // The same retained state travels with it.
        assertSame(live, session.retain("live") { Closeable() })
    }

    @Test
    fun releasingClosesThePagesAboveIt() {
        val phone = NavStack(Page("list"))
        val session = phone.push(Page("session"), animated = false)
        val sideChat = phone.push(Page("side chat"), animated = false)
        phone.release(session)
        assertTrue(sideChat.isClosed)
        assertFalse(session.isClosed)
        assertEquals(1, phone.entries.size)
    }

    @Test
    fun releasingTheRootLeavesTheReplacement() {
        val detail = NavStack(Page("session"))
        val root = detail.top
        assertTrue(detail.release(root, replacement = Page("empty")))
        assertEquals(listOf(Page("empty")), detail.entries.map { it.route })
        assertFalse(root.isClosed)
    }

    @Test
    fun adoptOnTopKeepsTheListUnder() {
        val detail = NavStack(Page("session"))
        val phone = NavStack(Page("list"))
        val page = detail.top
        detail.release(page, replacement = Page("empty"))
        phone.adopt(page)
        assertEquals(listOf(Page("list"), Page("session")), phone.entries.map { it.route })
        assertSame(page, phone.top)
    }

    @Test
    fun adoptAsRootClosesTheOldRoot() {
        val detail = NavStack(Page("empty"))
        val old = detail.top
        val phone = NavStack(Page("list"))
        val page = phone.push(Page("session"), animated = false)
        phone.release(page)
        detail.adopt(page, asRoot = true)
        assertTrue(old.isClosed)
    }

    @Test
    fun anEntryNotOnTheStackIsNotReleased() {
        val a = NavStack(Page("a"))
        val b = NavStack(Page("b"))
        assertFalse(a.release(b.top, replacement = Page("x")))
    }
}
