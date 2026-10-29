package sh.zeron.android.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.glass.MenuItem
import sh.zeron.android.design.glass.MenuSection
import sh.zeron.android.shell.SessionsBottomBarGeometry
import uniffi.zeron_core.DeviceView

/**
 * #61's pure pieces: the long-press menu, the host rows of Voice settings,
 * the style labels, the stage's orb size, the orb ink and the bottom bar's
 * room for the voice item. Plain JUnit.
 */
class VoiceUiTest {

    private fun device(
        id: String,
        online: Boolean = true,
        host: Boolean = true,
        platform: String = "macos",
    ) = DeviceView(
        id = id,
        name = "Name $id",
        platform = platform,
        online = online,
        lastSeenMs = null,
        version = null,
        capabilities = emptyList(),
        isExecutionHost = host,
        isSelf = false,
        sessionCount = 0u,
    )

    // ------------------------------------------------ menu

    @Test
    fun menuListsHostsThenTheVoiceSubmenuThenSettings() {
        val picked = mutableListOf<String?>()
        val menu = voiceMenu(
            hosts = listOf(device("a"), device("b")),
            live = false,
            selectedHost = "b",
            styles = listOf("alloy", "ember"),
            selectedStyle = "ember",
            onSelectHost = { picked += "host:$it" },
            onSelectStyle = { picked += "style:$it" },
            onSettings = { picked += "settings" },
        )
        val hosts = menu.children[0] as MenuSection
        assertEquals("Codex runs on", hosts.title)
        assertTrue(hosts.inline)
        val rows = hosts.children.map { it as MenuItem }
        assertEquals(listOf("Name a", "Name b"), rows.map { it.title })
        assertEquals(listOf(false, true), rows.map { it.checked })
        assertSame(ZeronIcons.Desktopcomputer, (rows[0].icon as sh.zeron.android.design.glass.MenuIcon.Symbol).icon)

        val voice = menu.children[1] as MenuSection
        assertEquals("Voice", voice.title)
        assertFalse("a drill-in submenu", voice.inline)
        assertEquals("the current style as its subtitle", "Ember", voice.subtitle)
        val styles = voice.children.map { it as MenuItem }
        assertEquals(listOf("Codex default", "Alloy", "Ember"), styles.map { it.title })
        assertEquals(listOf(false, false, true), styles.map { it.checked })

        val settings = menu.children[2] as MenuItem
        assertEquals("Voice Settings", settings.title)

        rows[0].action()
        styles[0].action()
        settings.action()
        assertEquals(listOf("host:a", "style:null", "settings"), picked)
    }

    @Test
    fun duringACallHostsAreDisabledAndWithoutHostsTheSectionIsGone() {
        val live = voiceMenu(listOf(device("a")), live = true, null, emptyList(), null, {}, {}, {})
        assertTrue(((live.children[0] as MenuSection).children[0] as MenuItem).disabled)

        val none = voiceMenu(emptyList(), live = false, null, emptyList(), null, {}, {}, {})
        assertEquals(2, none.children.size)
        assertEquals("Voice", (none.children[0] as MenuSection).title)
        assertEquals("Codex default", (none.children[0] as MenuSection).subtitle)
    }

    @Test
    fun styleLabelsAreCapitalizedLikeSwift() {
        assertEquals("Codex default", voiceStyleLabel(null))
        assertEquals("Alloy", voiceStyleLabel("alloy"))
        assertEquals("Deep Blue", voiceStyleLabel("deep BLUE"))
        assertEquals("Codex voice · Alloy", settingsVoiceSubtitle("alloy"))
        assertEquals("Codex voice device and style", settingsVoiceSubtitle(null))
    }

    @Test
    fun styleMenuChecksTheCurrentVoice() {
        val menu = voiceStyleMenu(listOf("alloy"), selected = null) {}
        val rows = menu.children.map { it as MenuItem }
        assertEquals(listOf("Codex default", "Alloy"), rows.map { it.title })
        assertEquals(listOf(true, false), rows.map { it.checked })
    }

    // ------------------------------------------------ settings rows

    @Test
    fun hostRowsFollowIosReadiness() {
        val devices = listOf(
            device("ready"),
            device("offline", online = false),
            device("old"),
            device("phone", host = false),
            device("linux", platform = "linux"),
        )
        val rows = voiceHostRows(devices, live = false, chosen = "ready") { it.id != "old" }
        assertEquals(listOf("ready", "offline", "old", "linux"), rows.map { it.id })
        assertEquals(
            listOf("Ready", "Offline", "Update Zeron and enable remote voice", "Ready"),
            rows.map { it.subtitle },
        )
        assertEquals(listOf(true, false, false, true), rows.map { it.enabled })
        assertEquals(listOf(true, false, false, true), rows.map { it.ready })
        assertEquals(listOf(true, false, false, false), rows.map { it.selected })
        assertSame(ZeronIcons.Laptopcomputer, rows[0].icon)
        assertSame(ZeronIcons.ServerRack, rows[3].icon)

        val during = voiceHostRows(devices, live = true, chosen = null) { true }
        assertTrue("no host is selectable during a call", during.none { it.enabled })
    }

    @Test
    fun hostIconsByPlatform() {
        assertSame(ZeronIcons.Laptopcomputer, voiceHostIcon("macOS"))
        assertSame(ZeronIcons.Laptopcomputer, voiceHostIcon("darwin"))
        assertSame(ZeronIcons.Laptopcomputer, voiceHostIcon("windows"))
        assertSame(ZeronIcons.ServerRack, voiceHostIcon("linux"))
    }

    // ------------------------------------------------ stage, orb, bar

    @Test
    fun stageOrbIsAtMost340AndFitsTheScreen() {
        assertEquals(340f, stageOrbSide(430f, 900f), 0f)
        assertEquals(328f, stageOrbSide(360f, 900f), 0f)
        assertEquals(240f, stageOrbSide(430f, 500f), 0f)
        assertEquals(96f, stageOrbSide(100f, 200f), 0f)
    }

    @Test
    fun orbInkMirrorsInDark() {
        val light = orbInk(0.2f, 0.5f, dark = false)
        val dark = orbInk(0.2f, 0.5f, dark = true)
        assertEquals(0.2f, light.red, 1e-3f)
        assertEquals(0.8f, dark.red, 1e-3f)
        assertEquals(0.5f, dark.alpha, 1e-2f)
        assertEquals(1f, orbInk(2f, 3f, dark = false).alpha, 0f)
    }

    @Test
    fun bottomBarMakesRoomForTheVoiceItemAtRestOnly() {
        val g = SessionsBottomBarGeometry
        val row = 370f
        assertEquals(g.fieldWidth(row, 0f) - 56f, g.fieldWidth(row, 0f, voice = true), 1e-4f)
        assertEquals(g.fieldWidth(row, 1f), g.fieldWidth(row, 1f, voice = true), 1e-4f)
        assertEquals(g.trailingX(row) - 56f, g.voiceX(row), 1e-4f)
    }

    @Test
    fun presenterShowsOnceWithItsSource() {
        val p = VoiceStagePresenter()
        assertFalse(p.showing)
        p.show(null)
        assertTrue(p.showing)
        assertNull(p.source)
        p.show(androidx.compose.ui.geometry.Rect(1f, 2f, 3f, 4f))
        assertNull("a second show while showing keeps the first source", p.source)
        p.hide()
        assertFalse(p.showing)
    }
}
