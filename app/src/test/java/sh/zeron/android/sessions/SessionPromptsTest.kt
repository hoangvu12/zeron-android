package sh.zeron.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.zeron.android.design.ActionSheetModel
import sh.zeron.android.design.ActionSheetPresenter
import sh.zeron.android.design.Alerts
import sh.zeron.android.design.ZeronAlert
import uniffi.zeron_core.SectionView

/**
 * The prompt launchers ([DefaultSessionPrompts]) over recording hosts and
 * a recording [RowActions] (#20 rewrite of ticket 43's suite): every
 * launcher presents the right model, its buttons call the right write,
 * and the follow-ups (`onRenamed`, `onDeleted`) key off the outcome.
 *
 * Roboco's engine chooser ("Create on", the offline pre-check toast,
 * engine-scoped Move lists) is gone: Zeron's sections are workspace-wide,
 * so New Section always goes straight to the alert and the Move sheet
 * lists **every** section (docs/port/01 C2, F6, G2). The hosts' visuals
 * and timelines are the design system's own tests.
 */
class SessionPromptsTest {

    private class RecordingAlerts : Alerts {
        val shown = mutableListOf<ZeronAlert>()
        override fun show(alert: ZeronAlert) {
            shown += alert
        }
        override fun updateCurrent(alert: ZeronAlert) {}
        override fun dismissAll() {}
    }

    private class RecordingSheets : ActionSheetPresenter {
        val shown = mutableListOf<ActionSheetModel>()
        override fun show(model: ActionSheetModel) {
            shown += model
        }
    }

    /** Records every call; `onResult` fires synchronously with [outcome]. */
    private class RecordingRowActions : RowActions {
        val calls = mutableListOf<String>()
        var sections: List<SectionView> = emptyList()
        var outcome = ActionOutcome.Ok

        override fun setPinned(chatId: String, pinned: Boolean, onResult: (ActionOutcome) -> Unit) {
            calls += "setPinned($chatId, $pinned)"
            onResult(outcome)
        }
        override fun archive(chatId: String, onResult: (ActionOutcome) -> Unit) {
            calls += "archive($chatId)"
            onResult(outcome)
        }
        override fun unarchive(chatId: String, onResult: (ActionOutcome) -> Unit) {
            calls += "unarchive($chatId)"
            onResult(outcome)
        }
        override fun delete(chatId: String, onResult: (ActionOutcome) -> Unit) {
            calls += "delete($chatId)"
            onResult(outcome)
        }
        override fun move(chatId: String, toSection: String?, onResult: (ActionOutcome) -> Unit) {
            calls += "move($chatId, ${toSection ?: "null"})"
            onResult(outcome)
        }
        override fun movePin(chatId: String, after: String?, before: String?, onResult: (ActionOutcome) -> Unit) {
            calls += "movePin($chatId)"
            onResult(outcome)
        }
        override fun rename(chatId: String, title: String, onResult: (ActionOutcome) -> Unit) {
            calls += "rename($chatId, $title)"
            onResult(outcome)
        }
        override fun createSection(
            name: String,
            onResult: (ActionOutcome) -> Unit,
            onCreated: (sectionId: String) -> Unit,
        ) {
            calls += "createSection($name)"
            if (outcome == ActionOutcome.Ok) onCreated("s:$name")
            onResult(outcome)
        }
        override fun renameSection(sectionId: String, name: String, onResult: (ActionOutcome) -> Unit) {
            calls += "renameSection($sectionId, $name)"
            onResult(outcome)
        }
        override fun deleteSection(sectionId: String, onResult: (ActionOutcome) -> Unit) {
            calls += "deleteSection($sectionId)"
            onResult(outcome)
        }
        override fun sections(): List<SectionView> = sections
    }

    private val alerts = RecordingAlerts()
    private val sheets = RecordingSheets()
    private val actions = RecordingRowActions()
    private val prompts = DefaultSessionPrompts(actions, alerts, sheets)

    private val c1 = testRow("c1", title = "Fix flaky test")

    /** The alert's primary (last) button, pressed with [text] in the field. */
    private fun ZeronAlert.submit(text: String) = buttons.last().onClick(text)

    private fun ZeronAlert.cancel() = buttons.first().onClick("")

    @Test
    fun renameSessionPrefillsTheTitleAndSubmitsTheTrimOnce() {
        prompts.renameSession(c1)
        val alert = alerts.shown.single()
        assertEquals(SessionPromptCopy.RenameSessionTitle, alert.title)
        assertEquals("Fix flaky test", alert.field?.text)
        alert.submit("  Deploy  ")
        assertEquals(listOf("rename(c1, Deploy)"), actions.calls)
    }

    @Test
    fun aBlankRenameWritesNothing() {
        prompts.renameSession(c1)
        alerts.shown.single().submit("   ")
        alerts.shown.single().cancel()
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun newSectionGoesStraightToTheAlertAndCreatesOnly() {
        prompts.newSection()
        assertTrue("no chooser sheet: sections are workspace-wide", sheets.shown.isEmpty())
        val alert = alerts.shown.single()
        assertEquals(SessionPromptCopy.NewSectionTitle, alert.title)
        assertEquals(SessionPromptCopy.NewSectionPlaceholder, alert.field?.placeholder)
        alert.submit(" Infra 2 ")
        assertEquals(listOf("createSection(Infra 2)"), actions.calls)
    }

    @Test
    fun moveToSectionListsEverySectionThenNoSection() {
        actions.sections = listOf(
            testSection("a", "Backend"),
            testSection("b", "Design review"),
            testSection("c", "Infra"),
        )
        prompts.moveToSection(c1)
        val sheet = sheets.shown.single()
        assertEquals(SessionPromptCopy.MoveToSectionTitle, sheet.title)
        assertEquals(
            listOf("Backend", "Design review", "Infra", SessionPromptCopy.NoSection),
            sheet.actions.map { it.title },
        )
        sheet.actions[1].onSelect()
        sheet.actions[3].onSelect()
        assertEquals(listOf("move(c1, b)", "move(c1, null)"), actions.calls)
    }

    @Test
    fun moveToSectionReadsTheSectionsAtLaunchTime() {
        prompts.moveToSection(c1)
        actions.sections = listOf(testSection("late", "Late"))
        prompts.moveToSection(c1)
        assertEquals(listOf(SessionPromptCopy.NoSection), sheets.shown[0].actions.map { it.title })
        assertEquals(listOf("Late", SessionPromptCopy.NoSection), sheets.shown[1].actions.map { it.title })
    }

    @Test
    fun renameSectionRunsOnRenamedWithTheTrimOnlyOnOk() {
        val renamed = mutableListOf<String>()
        prompts.renameSection("s1", "Backend") { renamed += it }
        val alert = alerts.shown.single()
        assertEquals(SessionPromptCopy.RenameSectionTitle, alert.title)
        assertEquals("Backend", alert.field?.text)
        alert.submit("  API ")
        assertEquals(listOf("renameSection(s1, API)"), actions.calls)
        assertEquals(listOf("API"), renamed)

        actions.outcome = ActionOutcome.Failed
        prompts.renameSection("s1", "API") { renamed += it }
        alerts.shown.last().submit("Other")
        assertEquals(listOf("API"), renamed)
    }

    @Test
    fun confirmDeleteSessionDeletesOnlyOnDeleteAndRunsOnDeletedOnOkOrGone() {
        var deleted = 0
        prompts.confirmDeleteSession("c1", "Fix flaky test") { deleted++ }
        val alert = alerts.shown.single()
        assertEquals(SessionPromptCopy.DeleteSessionTitle, alert.title)
        alert.cancel()
        assertTrue(actions.calls.isEmpty())
        alert.submit("")
        assertEquals(listOf("delete(c1)"), actions.calls)
        assertEquals(1, deleted)

        actions.outcome = ActionOutcome.Gone
        prompts.confirmDeleteSession("c2", "") { deleted++ }
        alerts.shown.last().submit("")
        assertEquals(2, deleted)

        actions.outcome = ActionOutcome.Failed
        prompts.confirmDeleteSession("c3", "") { deleted++ }
        alerts.shown.last().submit("")
        assertEquals("a refused delete stays", 2, deleted)
    }

    @Test
    fun confirmDeleteSectionDeletesAndPopsOnOkOrGoneOnly() {
        var popped = 0
        prompts.confirmDeleteSection("s1", "Backend") { popped++ }
        val sheet = sheets.shown.single()
        assertEquals(SessionPromptCopy.deleteSectionTitle("Backend"), sheet.title)
        sheet.actions.single().onSelect()
        assertEquals(listOf("deleteSection(s1)"), actions.calls)
        assertEquals(1, popped)

        actions.outcome = ActionOutcome.Gone
        prompts.confirmDeleteSection("s2", "Gone") { popped++ }
        sheets.shown.last().actions.single().onSelect()
        assertEquals(2, popped)

        actions.outcome = ActionOutcome.Failed
        prompts.confirmDeleteSection("s3", "Kept") { popped++ }
        sheets.shown.last().actions.single().onSelect()
        assertEquals(2, popped)
    }
}
