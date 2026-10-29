package sh.zeron.android.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #43's pure pieces (plain JUnit): the folder shown for a terminal, home
 * abbreviated as iOS `TerminalTab.folder` does (E3), and the view's colour
 * math — the core's `0xRRGGBBAA` runs into Android ARGB, and the alpha
 * scaling behind the selection wash and the predicted-echo underline.
 */
class TerminalPureTest {

    @Test
    fun homeFoldersAbbreviateToTilde() {
        assertEquals("~", abbreviateHome("/Users/alice"))
        assertEquals("~/code/zeron", abbreviateHome("/Users/alice/code/zeron"))
        assertEquals("~", abbreviateHome("/home/dev"))
        assertEquals("~/src", abbreviateHome("/home/dev/src"))
        assertEquals("~", abbreviateHome("/root"))
    }

    @Test
    fun otherFoldersStayAsTheyAre() {
        assertEquals("/srv/app", abbreviateHome("/srv/app"))
        assertEquals("/root/src", abbreviateHome("/root/src"))
        assertEquals("C:\\Users\\alice", abbreviateHome("C:\\Users\\alice"))
    }

    @Test
    fun rgbaBecomesArgb() {
        assertEquals(0xFF112233.toInt(), TerminalColors.argb(0x112233FFu))
        assertEquals(0x80FF0000.toInt(), TerminalColors.argb(0xFF000080u))
        assertEquals(0, TerminalColors.argb(0u))
    }

    @Test
    fun withAlphaScalesOnlyTheAlpha() {
        assertEquals(0x80123456.toInt(), TerminalColors.withAlpha(0xFF123456.toInt(), 128f / 255f))
        assertEquals(0x00123456, TerminalColors.withAlpha(0xFF123456.toInt(), 0f))
        assertEquals(0xFF123456.toInt(), TerminalColors.withAlpha(0xFF123456.toInt(), 2f))
    }
}
