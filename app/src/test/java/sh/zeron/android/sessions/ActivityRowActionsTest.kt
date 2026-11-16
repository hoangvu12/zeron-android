package sh.zeron.android.sessions

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.zeron.android.design.glass.MenuElement
import sh.zeron.android.design.glass.MenuItem
import sh.zeron.android.design.glass.MenuSection
import uniffi.zeron_core.ChatIndicator
import uniffi.zeron_core.SectionView
import uniffi.zeron_core.SessionRow

/**
 * Activity's row gestures (iOS 1.0.2 `SessionListController`): Sleep or
 * Wake where Move stands, no Archive while under way, and the menu's
 * Sleep ▸ / Stop Waiting.
 */
class ActivityRowActionsTest {

    private val calls = mutableListOf<String>()

    private val cmd = object : SessionRowCommands {
        override fun setPinned(chatId: String, pinned: Boolean) {}
        override fun archive(chatId: String, onAccepted: (Boolean) -> Unit) { calls += "archive $chatId" }
        override fun unarchive(chatId: String) {}
        override fun move(chatId: String, sectionId: String?) {}
        override fun moveToSectionSheet(row: SessionRow) {}
        override fun renameSession(row: SessionRow) {}
        override fun deleteSession(row: SessionRow) {}
        override fun newSection() {}
        override fun sections(): List<SectionView> = emptyList()
        override fun sleep(chatId: String, untilMs: Long) { calls += "sleep $chatId $untilMs" }
        override fun wake(chatId: String) { calls += "wake $chatId" }
        override fun stopWaiting(chatId: String) { calls += "stop $chatId" }
        override fun sleepSheet(row: SessionRow) { calls += "sheet ${row.id}" }
        override fun customSleep(row: SessionRow) { calls += "custom ${row.id}" }
    }

    private val strings: (Int) -> String = { "s$it" }

    private fun trailing(row: SessionRow): List<String> {
        val vm = SessionRowVM.from(row)
        return sessionSwipeActions(
            { row }, { vm }, archivedList = false, cmd = cmd,
            accent = Color.Blue, secondary = Color.Gray, strings = strings, activity = true,
        ).second.map { it.testTag }
    }

    @Test
    fun swipeTradesMoveForSleepOrWake() {
        assertEquals(listOf("swipe-action-archive", "swipe-action-sleep"), trailing(testRow("a")))
        assertEquals(
            listOf("swipe-action-archive", "swipe-action-wake"),
            trailing(testRow("a").copy(sleepUntilMs = 1L)),
        )
        // Under way: no Archive.
        assertEquals(listOf("swipe-action-sleep"), trailing(testRow("a").copy(indicator = ChatIndicator.WORKING)))
    }

    @Test
    fun sleepPaneOpensTheSheet() {
        val row = testRow("a")
        val vm = SessionRowVM.from(row)
        val sleep = sessionSwipeActions({ row }, { vm }, false, cmd, Color.Blue, Color.Gray, strings, activity = true)
            .second.last()
        sleep.perform {}
        assertEquals(listOf("sheet a"), calls)
    }

    private fun flatten(elements: List<MenuElement>): List<MenuElement> = elements.flatMap {
        if (it is MenuSection) listOf(it) + flatten(it.children) else listOf(it)
    }

    private fun menuTags(row: SessionRow, presets: List<ActivityList.Preset> = emptyList()): List<String?> {
        val menu = sessionRowMenu(
            row, SessionRowVM.from(row), archivedList = false, sections = emptyList(), cmd = cmd,
            strings = strings, activity = true, presets = { presets },
        )
        return flatten(menu.children).map { (it as? MenuItem)?.testTag ?: (it as MenuSection).title }
    }

    @Test
    fun menuHasSleepAndNoMove() {
        val presets = listOf(ActivityList.Preset("hour", "In 1 Hour", "3:00 PM", 42L))
        val tags = menuTags(testRow("a"), presets)
        assertEquals(
            listOf(
                "row-menu-pin", "row-menu-rename", null, "Sleep", "row-menu-sleep-hour", null,
                "row-menu-sleep-custom", "row-menu-archive", "row-menu-delete",
            ),
            tags,
        )
    }

    @Test
    fun menuWakesAndStopsWaiting() {
        val tags = menuTags(testRow("a").copy(sleepUntilMs = 1L, waitsOnProcess = true))
        assertTrue("row-menu-wake" in tags)
        assertTrue("row-menu-stop-waiting" in tags)
        assertTrue("Sleep" !in tags)
    }

    @Test
    fun archiveIsOutOfReachWhileWorking() {
        val row = testRow("a").copy(indicator = ChatIndicator.WORKING)
        val menu = sessionRowMenu(
            row, SessionRowVM.from(row), false, emptyList(), cmd, strings, activity = true,
        )
        val archive = flatten(menu.children).filterIsInstance<MenuItem>().single { it.testTag == "row-menu-archive" }
        assertTrue(archive.disabled)
        assertEquals("Stop it first: its agent is still working", archive.subtitle)
        archive.action()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun presetSleepsAtItsTime() {
        val presets = listOf(ActivityList.Preset("hour", "In 1 Hour", "3:00 PM", 42L))
        val row = testRow("a")
        val menu = sessionRowMenu(
            row, SessionRowVM.from(row), false, emptyList(), cmd, strings, activity = true, presets = { presets },
        )
        flatten(menu.children).filterIsInstance<MenuItem>().single { it.testTag == "row-menu-sleep-hour" }.action()
        assertEquals(listOf("sleep a 42"), calls)
    }
}
