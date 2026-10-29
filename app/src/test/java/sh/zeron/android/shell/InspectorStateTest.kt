package sh.zeron.android.shell

import androidx.compose.runtime.Composable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.zeron.android.companions.CompanionSection

/**
 * The inspector column's rules (#45, iOS `SplitRootController`
 * `toggleCompanions` / `showCompanion` / `closeCompanion` /
 * `hideCompanions` / `revealInspector` / `followInspector` and the
 * collapse hand-off), over a hostless stack.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InspectorStateTest {

    private class Panel(override val chatId: String) : CompanionsRootRoute {
        override var initialSection: CompanionSection? = null

        @Composable
        override fun Content() {}
    }

    private class Page(val name: String) : NavRoute

    private fun shell(width: Float = 1366f, sidebar: Boolean = true) = TabletShellState(
        initialChatId = null,
        initialSidebarVisible = sidebar,
        initialDetailRoot = DetailRoot.Empty,
    ).apply {
        isCollapsed = false
        windowWidthDp = width
    }

    private fun inspector() = InspectorState(rootFor = { Panel(it) })

    @Test
    fun toggleShowsThePanelAtItsSectionThenPutsItAway() {
        val shell = shell()
        val inspector = inspector()
        inspector.toggle("chat-a", CompanionSection.Terminals, shell)
        assertTrue(inspector.shown)
        assertEquals("chat-a", inspector.chatId)
        val root = inspector.stack.top.route as Panel
        assertEquals(CompanionSection.Terminals, root.initialSection)
        inspector.toggle("chat-a", null, shell)
        assertFalse("the same chat's panel alone: put away", inspector.shown)
    }

    @Test
    fun aRoomyWindowKeepsTheSidebarANarrowOneStepsItAsideAndGetsItBack() {
        val roomy = shell(width = 1366f)
        inspector().toggle("a", null, roomy)
        assertTrue(roomy.sidebarVisible)

        val narrow = shell(width = 1024f)
        val inspector = inspector()
        inspector.toggle("a", null, narrow)
        assertFalse("under 1100 dp the sidebar steps aside", narrow.sidebarVisible)
        inspector.hide(narrow)
        assertTrue("and comes back with the inspector's hide", narrow.sidebarVisible)

        // A sidebar the user had hidden stays hidden.
        val hidden = shell(width = 1024f, sidebar = false)
        val other = inspector()
        other.toggle("a", null, hidden)
        other.hide(hidden)
        assertFalse(hidden.sidebarVisible)
    }

    @Test
    fun aCompanionPushesOverThePanelAndClosesBackToIt() {
        val shell = shell()
        val inspector = inspector()
        val page = Page("subagent")
        // Not shown yet: the panel and the page together, revealed.
        inspector.showCompanion(page, "a", shell)
        assertTrue(inspector.shown)
        assertEquals(2, inspector.stack.entries.size)
        assertSame(page, inspector.stack.top.route)
        // Toggling with something over the panel re-roots instead of hiding.
        inspector.toggle("a", null, shell)
        assertTrue(inspector.shown)
        assertEquals(1, inspector.stack.entries.size)

        val side = Page("side chat")
        inspector.showCompanion(side, "a", shell)
        assertEquals(2, inspector.stack.entries.size)
        assertFalse("not the top", inspector.closeCompanion(Page("other"), shell))
        assertTrue(inspector.closeCompanion(side, shell))
        assertEquals(1, inspector.stack.entries.size)
        assertTrue(inspector.shown)
        // The panel itself closing puts the column away.
        assertTrue(inspector.closeCompanion(inspector.stack.top.route, shell))
        assertFalse(inspector.shown)
    }

    @Test
    fun theOpenPanelFollowsAnotherSessionAndAHiddenOneDoesNot() {
        val shell = shell()
        val inspector = inspector()
        inspector.toggle("a", null, shell)
        val first = inspector.stack.top.route
        inspector.follow("a")
        assertSame("same chat: nothing changes", first, inspector.stack.top.route)
        inspector.follow("b")
        assertEquals("b", inspector.chatId)
        assertNotSame(first, inspector.stack.top.route)
        assertEquals("b", (inspector.stack.top.route as Panel).chatId)

        inspector.hide(shell)
        inspector.follow("c")
        assertEquals("hidden: no follow", "b", inspector.chatId)
    }

    @Test
    fun foldingGivesTheSidebarBackAndKeepsThePanelForTheSheet() {
        val shell = shell(width = 1024f)
        val inspector = inspector()
        inspector.toggle("a", null, shell)
        assertFalse(shell.sidebarVisible)
        shell.isCollapsed = true
        inspector.onCollapse(shell)
        assertTrue(shell.sidebarVisible)
        assertTrue("it rides along (as a sheet)", inspector.shown)
        // Hiding later doesn't touch the sidebar again.
        shell.sidebarVisible = false
        inspector.hide(shell)
        assertFalse(shell.sidebarVisible)
    }
}
