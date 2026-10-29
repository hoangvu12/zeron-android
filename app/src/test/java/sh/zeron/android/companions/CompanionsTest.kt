package sh.zeron.android.companions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.zeron.android.session.BottomStackModel
import sh.zeron.android.session.isStructural
import sh.zeron.android.sidechats.AgentState
import sh.zeron.android.sidechats.SideChatsAgent
import sh.zeron.android.sidechats.SideChatsUi

/**
 * The companions model behind the strip and the nav badge (#40, iOS
 * `CompanionStrip.apply` and `syncCompanions`), and the pill row's
 * strip-only visibility. Plain JVM.
 */
class CompanionsTest {

    private fun agent(id: String, state: AgentState, docId: String? = "doc-$id") =
        SideChatsAgent(id = id, docId = docId, title = "t$id", state = state)

    @Test
    fun theAgentsChipSaysWorkingWhileAnyRunElseTheTotal() {
        val running = CompanionCounts(agents = 3, runningAgents = 2)
        assertEquals("2 working", running.agentsText)
        assertEquals("2 subagents working", running.agentsLabel)
        val idle = CompanionCounts(agents = 3)
        assertEquals("3", idle.agentsText)
        assertEquals("3 subagents", idle.agentsLabel)
        assertEquals("4 side chats", CompanionCounts(sideChats = 4).sideChatsLabel)
        assertEquals("1 terminals", CompanionCounts(terminals = 1).terminalsLabel)
    }

    @Test
    fun emptyMeansNoChip() {
        assertTrue(CompanionCounts().isEmpty)
        assertFalse(CompanionCounts(agents = 1).isEmpty)
        assertFalse(CompanionCounts(sideChats = 1).isEmpty)
        assertFalse(CompanionCounts(terminals = 1).isEmpty)
        // Live terminals alone (no tabs) can't happen; running alone isn't a chip.
        assertTrue(CompanionCounts(runningAgents = 1).isEmpty)
    }

    @Test
    fun theModelCountsEverySpawnIncludingThoseWithoutADoc() {
        val ui = SideChatsUi.of(
            agents = listOf(
                agent("1", AgentState.Done),
                agent("2", AgentState.Running, docId = null),
                agent("3", AgentState.Failed),
            ),
            children = emptyList(),
        )
        assertEquals(CompanionCounts(agents = 3, runningAgents = 1), ui.companions)
    }

    @Test
    fun aSideChatCountsNoSideChatsOfItsOwn() {
        val parent = SideChatsUi(chats = emptyList(), isSideChat = false)
        assertEquals(0, parent.companions.sideChats)
        val side = SideChatsUi.of(emptyList(), emptyList(), isSideChat = true)
        assertTrue(side.companions.isEmpty)
    }

    @Test
    fun theStripAloneShowsThePillRowAndItsArrivalReflows() {
        val none = BottomStackModel()
        val strip = BottomStackModel(stripShown = true)
        assertFalse(none.pillShown)
        assertTrue(strip.pillShown)
        assertTrue("the row appearing for the strip is structural", isStructural(none, strip))
        // With a banner already up, the strip coming and going doesn't move the row.
        assertFalse(isStructural(BottomStackModel(bannerShown = true), BottomStackModel(bannerShown = true, stripShown = true)))
    }
}
