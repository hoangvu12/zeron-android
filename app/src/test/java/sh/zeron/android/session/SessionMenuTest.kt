package sh.zeron.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.zeron.android.design.glass.MenuItem
import sh.zeron.android.design.glass.MenuSection

/**
 * The ⋯ menu's content (#33): the pure [sessionMenu] builder against iOS
 * `sessionMenu(title:subtitle:)` (`SessionViewController.swift:800-833`) —
 * the header, the inline companion group, then Pin, Copy Transcript,
 * Archive and Delete — and [sessionMenuHeader].
 */
class SessionMenuTest {

    private val log = mutableListOf<String>()

    private fun menu(pinned: Boolean = false, title: String = "Fix CI", subtitle: String = "zeron @ MacBook") =
        sessionMenu(
            pinned = pinned,
            onSetPinned = { log += "pin $it" },
            onCopyTranscript = { log += "copy" },
            onArchive = { log += "archive" },
            onDelete = { log += "delete" },
            companions = CompanionMenuRows(
                onNewSideChat = { log += "new" },
                onForkSideChat = { log += "fork" },
                onTerminal = { log += "terminal" },
            ),
            title = title,
            subtitle = subtitle,
        )

    @Test
    fun theOrderMatchesIos() {
        val children = menu().children
        val group = children[0] as MenuSection
        assertTrue(group.inline)
        assertNull(group.title)
        assertEquals(
            listOf("New Side Chat", "Fork Into Side Chat", "Terminal"),
            group.children.map { (it as MenuItem).title },
        )
        assertEquals(
            listOf("Pin", "Copy Transcript", "Archive", "Delete"),
            children.drop(1).map { (it as MenuItem).title },
        )
    }

    @Test
    fun noRobocoRows() {
        val titles = menu().children.flatMap { element ->
            when (element) {
                is MenuSection -> element.children.map { (it as MenuItem).title }
                is MenuItem -> listOf(element.title)
                else -> emptyList()
            }
        }
        assertFalse("Account & Usage" in titles)
        assertFalse("Previews" in titles)
    }

    @Test
    fun onlyDeleteIsDestructiveAndNothingIsDisabled() {
        val items = menu().children.drop(1).map { it as MenuItem }
        assertEquals(listOf(false, false, false, true), items.map { it.destructive })
        val group = (menu().children[0] as MenuSection).children.map { it as MenuItem }
        assertTrue((items + group).none { it.disabled })
    }

    @Test
    fun pinTogglesByState() {
        (menu(pinned = false).children[1] as MenuItem).action()
        val unpin = menu(pinned = true).children[1] as MenuItem
        assertEquals("Unpin", unpin.title)
        unpin.action()
        assertEquals(listOf("pin true", "pin false"), log)
    }

    @Test
    fun everyRowRunsItsAction() {
        val children = menu().children
        (children[0] as MenuSection).children.forEach { (it as MenuItem).action() }
        children.drop(2).forEach { (it as MenuItem).action() }
        assertEquals(listOf("new", "fork", "terminal", "copy", "archive", "delete"), log)
    }

    @Test
    fun theHeaderIsTitleOverWhereItRuns() {
        assertEquals("Fix CI\nzeron @ MacBook", menu().title)
        assertEquals("Fix CI", sessionMenuHeader("Fix CI", ""))
        assertEquals("zeron", sessionMenuHeader("", "zeron"))
        assertNull(sessionMenuHeader(" ", ""))
    }
}
