package sh.zeron.android.newsession

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.zeron.android.composer.ModelPickerRow

/**
 * The New Session page's model rules (#37, iOS `draftModel` and
 * `modelPicker()`) on the plain JVM: the tab order, the rows and their
 * detail line, the effort rule on a pick, and the host line.
 */
class NewSessionModelPickerTest {

    private val claudeLadder = listOf("low", "medium", "high")

    private val models = listOf(
        ModelChoice("codex", "Codex", "gpt-5.5", "GPT-5.5", listOf("low", "high"), detail = "Frontier coding"),
        ModelChoice("claude-code", "Claude Code", "claude-fable-5-1", "Fable 5.1", claudeLadder),
        ModelChoice("claude-code", "Claude Code", "claude-haiku-4-5", "Haiku 4.5", emptyList(), detail = "Fast"),
        ModelChoice("opencode", "OpenCode", "glm-5", "GLM 5", emptyList()),
    )

    private val project = ProjectOption(
        id = "p1",
        name = "zeron",
        path = "/srv/zeron",
        device = "d1",
        deviceName = "Workstation",
        online = false,
        git = true,
        groupKey = "repo:r",
        groupName = "zeron",
        colorIndex = 0,
    )

    @Test
    fun theDraftsProviderComesFirstThenTheCatalogOrder() {
        assertEquals(
            listOf("claude-code", "codex", "opencode"),
            newSessionPickerTabs(models, "claude-code").map { it.harness },
        )
        assertEquals(
            listOf("opencode", "codex", "claude-code"),
            newSessionPickerTabs(models, "opencode").map { it.harness },
        )
        assertEquals(
            listOf("Claude Code", "Codex", "OpenCode"),
            newSessionPickerTabs(models, "claude-code").map { it.label },
        )
    }

    @Test
    fun aProviderWithoutModelsHasNoTab() {
        assertEquals(listOf("codex", "claude-code", "opencode"), newSessionPickerTabs(models, "pi").map { it.harness })
        assertEquals(emptyList<String>(), newSessionPickerTabs(emptyList(), "claude-code").map { it.harness })
    }

    @Test
    fun aProvidersRowsCarryTheDescriptionAsTheDetail() {
        assertEquals(
            listOf(
                ModelPickerRow("claude-fable-5-1", "Fable 5.1", null),
                ModelPickerRow("claude-haiku-4-5", "Haiku 4.5", "Fast"),
            ),
            newSessionPickerRows(models, "claude-code"),
        )
        assertEquals("Frontier coding", newSessionPickerRows(models, "codex").single().detail)
    }

    @Test
    fun theEffortIsClearedOnlyWhenTheHarnessChanges() {
        val d = NewSessionDraft(projectId = "p1", branch = "main", worktree = true, harness = "claude-code", model = "claude-fable-5-1", effort = "high")

        val sameHarness = applyNewSessionModelPick(d, "claude-code", "claude-haiku-4-5")
        assertEquals(d.copy(model = "claude-haiku-4-5"), sameHarness)
        assertEquals("high", sameHarness.effort)

        val otherHarness = applyNewSessionModelPick(d, "codex", "gpt-5.5")
        assertEquals(d.copy(harness = "codex", model = "gpt-5.5", effort = null), otherHarness)
    }

    @Test
    fun draftModelIsTheDraftsOwnModelOrTheProvidersFirstWhenItNamesNone() {
        assertEquals("claude-fable-5-1", draftModel(models, NewSessionDraft(harness = "claude-code"))?.id)
        assertEquals(
            "claude-haiku-4-5",
            draftModel(models, NewSessionDraft(harness = "claude-code", model = "claude-haiku-4-5"))?.id,
        )
        assertNull("an unlisted model is not swapped for another", draftModel(models, NewSessionDraft(harness = "claude-code", model = "claude-opus-9")))
        assertNull("a model of another harness does not count", draftModel(models, NewSessionDraft(harness = "codex", model = "claude-fable-5-1")))
    }

    @Test
    fun theRequestOpensOnTheDraftAndAppliesThePickToTheLiveDraft() {
        var draft = NewSessionDraft(harness = "claude-code", model = null, effort = "low")
        val request = newSessionModelPicker(models, draft, "Workstation") { t -> draft = t(draft) }
        assertEquals("claude-code", request.harness)
        assertNull("nothing picked, nothing ticked", request.model)
        assertEquals("Workstation", request.hostName)
        assertNull(request.note)
        assertEquals(listOf("claude-code", "codex", "opencode"), request.tabs.map { it.harness })
        assertEquals(listOf("GPT-5.5"), runBlocking { request.load("codex") }.map { it.label })

        // A change since the sheet opened is kept: the pick transforms the draft as it is then.
        draft = draft.copy(projectId = "p2")
        request.onPick("claude-code", ModelPickerRow("claude-haiku-4-5", "Haiku 4.5", "Fast"))
        assertEquals(NewSessionDraft(projectId = "p2", harness = "claude-code", model = "claude-haiku-4-5", effort = "low"), draft)

        request.onPick("codex", ModelPickerRow("gpt-5.5", "GPT-5.5", null))
        assertEquals(NewSessionDraft(projectId = "p2", harness = "codex", model = "gpt-5.5", effort = null), draft)
    }

    @Test
    fun theHostLineIsTheCheckoutsDeviceElseTheProjectlessHost() {
        val hosts = listOf(HostOption("h1", "Laptop", online = true), HostOption("h2", "Old", online = false))
        assertEquals("Workstation", newSessionPickerHostName(project, hosts, "h1"))
        assertEquals("Old", newSessionPickerHostName(null, hosts, "h2"))
        assertEquals("Unknown device", newSessionPickerHostName(null, hosts, null))
    }
}
