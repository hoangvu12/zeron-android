package sh.zeron.android.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.zeron.android.companions.PanelItem
import sh.zeron.android.companions.PanelTerminal
import sh.zeron.android.companions.TerminalRowState
import sh.zeron.android.companions.panelItems
import sh.zeron.android.sidechats.SideChatsUi
import uniffi.zeron_core.KeyModifiers
import uniffi.zeron_core.TerminalPhase

/**
 * #44's pure pieces (plain JUnit): the key bar's sticky Ctrl/Alt (iOS
 * `TerminalKeyBar.Latch`), Add Output to Message's fenced block, the exit
 * and status copy, and the panel's terminal rows.
 */
class TerminalScreenPureTest {

    @Test
    fun latchCyclesOffOnceLockedOff() {
        assertEquals(Latch.Once, Latch.Off.next())
        assertEquals(Latch.Locked, Latch.Once.next())
        assertEquals(Latch.Off, Latch.Locked.next())
    }

    @Test
    fun oneShotModifiersLetGoAfterAKeyAndLockedOnesStay() {
        val m = TerminalModifiers()
        assertEquals(KeyModifiers(ctrl = false, alt = false, shift = false), m.modifiers)
        m.ctrl = Latch.Once
        m.alt = Latch.Locked
        assertEquals(KeyModifiers(ctrl = true, alt = true, shift = false), m.modifiers)
        m.consume()
        assertEquals(Latch.Off, m.ctrl)
        assertEquals(Latch.Locked, m.alt)
        assertEquals(KeyModifiers(ctrl = false, alt = true, shift = false), m.modifiers)
    }

    @Test
    fun outputBecomesAFencedBlockTrimmedOfNewlines() {
        assertEquals("```\n$ ls\nREADME.md\n```\n", terminalOutputBlock("\n\n$ ls\nREADME.md\n\n"))
        assertNull(terminalOutputBlock("\n\n"))
        assertNull(terminalOutputBlock(""))
    }

    @Test
    fun aBlockGoesAfterWhateverIsThere() {
        assertEquals("```\nx\n```\n", appendBlock("", "```\nx\n```\n"))
        assertEquals("Why does this fail?\n```\nx\n```\n", appendBlock("Why does this fail?", "```\nx\n```\n"))
    }

    @Test
    fun exitsReadLikeIos() {
        assertEquals("Process exited", TerminalCopy.exited(TerminalPhase.Exited(0, null)))
        assertEquals("Exited with code 2", TerminalCopy.exited(TerminalPhase.Exited(2, null)))
        assertEquals("Ended by SIGKILL", TerminalCopy.exited(TerminalPhase.Exited(0, "SIGKILL")))
    }

    @Test
    fun theStatusPillShowsTheOneWayForward() {
        assertNull(terminalStatus(null, TerminalPhase.Live))
        assertNull(terminalStatus(null, TerminalPhase.Connecting))
        assertNull(terminalStatus(null, null))
        val reconnecting = terminalStatus(null, TerminalPhase.Reconnecting(1_000uL))!!
        assertEquals(TerminalCopy.Reconnecting, reconnecting.text)
        assertNull(reconnecting.action)
        val exited = terminalStatus(null, TerminalPhase.Exited(1, null))!!
        assertEquals("Exited with code 1, New Terminal", exited.a11y)
        assertEquals(TerminalCopy.EndedOnHost, terminalStatus(null, TerminalPhase.Gone)!!.text)
        // A failed open wins over whatever the old tab says.
        val failed = terminalStatus("Couldn't open a terminal", TerminalPhase.Live)!!
        assertEquals(TerminalStatus.Kind.Failed, failed.kind)
        assertEquals(TerminalCopy.TryAgain, failed.action)
    }

    @Test
    fun terminalsReplaceTheEmptyLineAndNewTerminal() {
        val rows = listOf(
            PanelTerminal("a", "zsh", "~/code · Mac Studio", TerminalRowState.Live),
            PanelTerminal("b", "zsh 2", "~/code · Mac Studio", TerminalRowState.Ended),
        )
        assertEquals(
            listOf(
                "header:SideChats", "empty:SideChats", "action:NewSideChat", "action:Fork",
                "header:Terminals", "terminal:a", "terminal:b",
            ),
            panelItems(SideChatsUi(), rows).map { it.key },
        )
        assertEquals(rows[0], (panelItems(SideChatsUi(), rows)[5] as PanelItem.Terminal).terminal)
    }

    @Test
    fun terminalRowsSayTheirState() {
        val live = PanelTerminal("a", "zsh", "~ · Mac", TerminalRowState.Live)
        assertEquals("Live", live.trailing)
        assertEquals("zsh, Live", live.a11y)
        assertEquals("Reconnecting", live.copy(state = TerminalRowState.Reconnecting).trailing)
        assertEquals("Ended", live.copy(state = TerminalRowState.Ended).trailing)
    }
}
