package sh.zeron.android.newsession

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.zeron.android.composer.ChipIcon
import sh.zeron.android.composer.SendResult
import sh.zeron.android.composer.StagedImage
import sh.zeron.android.design.Alerts
import sh.zeron.android.design.ZeronAlert
import sh.zeron.android.design.glass.MenuItem
import sh.zeron.android.design.glass.MenuSection
import sh.zeron.android.session.SessionChrome
import sh.zeron.android.session.cloudBanner
import sh.zeron.android.shell.DraftHandoff
import uniffi.zeron_core.ChatConfig
import uniffi.zeron_core.CoreException
import uniffi.zeron_core.NewSession
import uniffi.zeron_core.SendRequest

/**
 * Cloud in New Session and in a session (#53; iOS `NewSessionViewController`
 * Cloud paths, `AppModel.createCloudSession`, `SessionViewController`'s
 * `cloudBanner`) on the plain JVM.
 */
class CloudNewSessionTest {

    private val repo = ProjectOption(
        id = "p1", name = "web", path = "/w", device = "d1", deviceName = "Studio", online = true, git = true,
        groupKey = "repo:web", groupName = "web", colorIndex = 1, githubRepo = "acme/web",
    )
    private val cloudProject = repo.copy(id = "c1", device = "cloud", deviceName = "Cloud", isCloud = true)

    private class FakeCloud(
        override val available: Boolean = true,
        override val credits: Double? = 1200.0,
        override val outOfCredits: Boolean = false,
        private val reached: Set<String> = setOf("acme/web"),
    ) : NewSessionCloud {
        override fun reaches(repo: String?) = available && repo != null && repo in reached
        override fun refreshIfUnread() {}
    }

    @Test
    fun runsOnCloudForACloudProjectOrAReachedRepoPickedForCloud() {
        val cloud = FakeCloud()
        assertTrue(runsOnCloud(cloudProject, NewSessionDraft(), cloud))
        assertFalse(runsOnCloud(repo, NewSessionDraft(), cloud))
        assertTrue(runsOnCloud(repo, NewSessionDraft(cloud = true), cloud))
        assertFalse(runsOnCloud(repo, NewSessionDraft(cloud = true), FakeCloud(reached = emptySet())))
        assertFalse(runsOnCloud(null, NewSessionDraft(cloud = true), cloud))
    }

    @Test
    fun cloudChipsAreProjectCloudAndStartFrom() {
        val labels = object : ChipLabels {
            override fun modelLabel(harness: String, model: String) = model
            override fun firstFallbackModelLabel(harness: String): String? = null
            override fun harnessLabel(harness: String) = harness
        }
        val chips = newSessionChips(NewSessionDraft(), repo, emptyList(), emptyList(), labels, runsOnCloud = true)
        assertEquals(listOf("project", "host", "branch", "model"), chips.map { it.id })
        assertEquals(ChipIcon.Project("web", 1), chips[0].icon)
        assertEquals("Cloud", chips[1].title)
        assertEquals("cloud", chips[1].symbol)
        assertEquals("Default branch", chips[2].title)
        assertEquals("From dev", newSessionChips(NewSessionDraft(branch = "dev"), repo, emptyList(), emptyList(), labels, runsOnCloud = true)[2].title)
    }

    @Test
    fun theDraftTransitions() {
        val onCloud = NewSessionDraft(projectId = "p1", branch = "dev", worktree = true, cloud = true)
        // Back to a checkout: the Cloud branch goes, and so does `cloud`.
        assertEquals(NewSessionDraft(projectId = "p1", worktree = true), onCloud.withCheckout(repo))
        // A Cloud project's checkout keeps `cloud = true`.
        assertEquals(true, NewSessionDraft().withCheckout(cloudProject).cloud)
        assertEquals(
            NewSessionDraft(projectId = "p1", cloud = true),
            NewSessionDraft(projectId = "p1", branch = "main", worktree = true).withCloud(),
        )
        assertEquals(
            NewSessionDraft(projectId = "new", cloud = true),
            NewSessionDraft(hostId = "h1", branch = "main").withCreatedCloudProject("new"),
        )
    }

    @Test
    fun runOnHidesCloudProjectsAndAddsTheCloudRow() {
        val menu = buildRunOnMenu(
            checkouts = listOf(repo, cloudProject),
            hosts = emptyList(),
            draft = NewSessionDraft(projectId = "p1", cloud = true),
            onPickCheckout = {},
            onPickHost = {},
            hasProject = true,
            runsOnCloud = true,
            cloudSubtitle = cloudRowSubtitle(1200.0),
        )
        val rows = menu.children
        assertEquals("Studio", (rows[0] as MenuItem).title)
        assertFalse("on Cloud, no computer is checked", (rows[0] as MenuItem).checked)
        val cloud = (rows[1] as MenuSection).children.single() as MenuItem
        assertEquals("Cloud", cloud.title)
        assertTrue(cloud.subtitle!!.endsWith(" credits left"))
        assertTrue(cloud.checked)
        assertEquals("Its own machine", cloudRowSubtitle(null))

        // A Cloud project alone: just the Cloud row, never the host list.
        val only = buildRunOnMenu(
            checkouts = listOf(cloudProject), hosts = listOf(HostOption("h1", "Laptop", true)),
            draft = NewSessionDraft(projectId = "c1"), onPickCheckout = {}, onPickHost = {},
            hasProject = true, runsOnCloud = true, cloudSubtitle = "Its own machine",
        )
        assertEquals(1, only.children.size)
        assertTrue(only.children.single() is MenuSection)
    }

    @Test
    fun newCloudProjectShowsOnlyWhenOffered() {
        fun titles(open: (() -> Unit)?) = buildProjectMenu(emptyList(), NewSessionDraft(), {}, {}, {}, onNewCloudProject = open)
            .children.flatMap { (it as MenuSection).children }.map { (it as MenuItem).title }
        assertEquals(listOf("No Project…", "New Cloud Project…", "New Project…"), titles {})
        assertEquals(listOf("No Project…", "New Project…"), titles(null))
    }

    @Test
    fun startFromIsTheDefaultBranchThenGitHubs() {
        val picks = mutableListOf<String?>()
        val rows = startFromRows(listOf("main", "dev"), branch = "dev") { picks += it }.map { it as MenuItem }
        assertEquals(listOf("Default branch", "main", "dev"), rows.map { it.title })
        assertEquals(listOf(false, false, true), rows.map { it.checked })
        rows[0].action()
        rows[1].action()
        assertEquals(listOf(null, "main"), picks)
        assertTrue((startFromRows(emptyList(), null) {}.single() as MenuItem).checked)
    }

    // ── createCloudSession ─────────────────────────────────────────────

    private class FakeCore : NewSessionCore {
        val calls = mutableListOf<String>()
        var createError: Throwable? = null
        var sendError: Throwable? = null
        val alive = mutableSetOf<String>()
        private var n = 0

        override suspend fun createSession(newSession: NewSession): String = error("not on Cloud")

        override suspend fun sendFirst(chatId: String, request: SendRequest) {
            calls += "send:$chatId"
            sendError?.let { throw it }
        }

        override suspend fun createCloudSession(spaceId: String, config: ChatConfig, branch: String?): String {
            createError?.let { throw it }
            val id = "cloud-${++n}"
            calls += "create:$spaceId:${branch ?: "-"}:${config.harness}"
            alive += id
            return id
        }

        override fun hasSession(chatId: String) = chatId in alive

        override fun refreshWorkspace() {
            calls += "refresh"
        }
    }

    @Before
    fun clearUnsent() {
        CloudSessionCreator.unsentCloudChat = null
    }

    @Test
    fun aFailedFirstSendIsRetriedInTheSameChatWhileTheDraftIsTheSame() = runBlocking {
        val core = FakeCore()
        val creator = CloudSessionCreator(core, Dispatchers.Unconfined)
        val d = NewSessionDraft(projectId = "p1", branch = "dev", harness = "codex", model = "gpt-5.5", cloud = true)
        core.sendError = CoreException.Network("down")
        runCatching { creator.createAndSend(d, "hi", emptyList()) }
        core.sendError = null
        assertEquals("cloud-1", creator.createAndSend(d, "hi", emptyList()))
        assertEquals(listOf("create:p1:dev:codex", "send:cloud-1", "send:cloud-1", "refresh"), core.calls)
        assertNull("sent: nothing left to reuse", CloudSessionCreator.unsentCloudChat)

        // Another model is another chat.
        core.sendError = CoreException.Network("down")
        runCatching { creator.createAndSend(d, "hi", emptyList()) }
        core.sendError = null
        assertEquals("cloud-3", creator.createAndSend(d.copy(model = "gpt-5.4"), "hi", emptyList()))
    }

    // ── the send handler's Cloud path ──────────────────────────────────

    private class CloudPage(override val outOfCredits: Boolean = false) : NewSessionPage {
        val starting = mutableListOf<Boolean>()
        var created = false
        override val draft = NewSessionDraft(projectId = "p1", cloud = true)
        override val projects: List<ProjectOption> = emptyList()
        override val runsOnCloud = true
        override fun persistBeforeCreate() {}
        override fun markCreated() {
            created = true
        }
        override fun setCloudStarting(starting: Boolean) {
            this.starting += starting
        }
    }

    private class RecordingAlerts : Alerts {
        val shown = mutableListOf<ZeronAlert>()
        override fun show(alert: ZeronAlert) {
            shown += alert
        }
        override fun updateCurrent(alert: ZeronAlert) {}
        override fun dismissAll() {}
    }

    private object Handoff : DraftHandoff

    private fun handler(page: NewSessionPage, core: FakeCore, alerts: Alerts, outOfCredits: MutableList<Unit>, created: MutableList<String>) =
        NewSessionSendHandler(
            page = page,
            creator = NewSessionCreator(core, Dispatchers.Unconfined, logW = { _, _ -> }),
            alerts = alerts,
            askAfterFirstSession = {},
            capture = { Handoff },
            onCreated = { id, _ -> created += id },
            cloudCreator = CloudSessionCreator(core, Dispatchers.Unconfined),
            onOutOfCredits = { outOfCredits += Unit },
        )

    @Test
    fun outOfCreditsIsRefusedUpFrontWithTheToast() = runBlocking {
        val core = FakeCore()
        val toasts = mutableListOf<Unit>()
        val created = mutableListOf<String>()
        val result = handler(CloudPage(outOfCredits = true), core, RecordingAlerts(), toasts, created).send("hi", emptyList<StagedImage>())
        assertEquals(SendResult.Kept, result)
        assertEquals(1, toasts.size)
        assertTrue(core.calls.isEmpty())
    }

    @Test
    fun aCloudStartShowsTheHudThenOpensTheChat() = runBlocking {
        val core = FakeCore()
        val page = CloudPage()
        val created = mutableListOf<String>()
        val result = handler(page, core, RecordingAlerts(), mutableListOf(), created).send("hi", emptyList<StagedImage>())
        assertEquals(SendResult.Sent, result)
        assertEquals(listOf(true, false), page.starting)
        assertTrue(page.created)
        assertEquals(listOf("cloud-1"), created)
    }

    @Test
    fun aStartErrorIsTheToastForCreditsElseTheAlert() = runBlocking {
        val core = FakeCore()
        val alerts = RecordingAlerts()
        val toasts = mutableListOf<Unit>()
        core.createError = CoreException.HostException("You're out of Cloud credits")
        assertEquals(SendResult.Kept, handler(CloudPage(), core, alerts, toasts, mutableListOf()).send("hi", emptyList<StagedImage>()))
        assertEquals(1, toasts.size)
        assertTrue(alerts.shown.isEmpty())

        core.createError = CoreException.Network("Edge down")
        assertEquals(SendResult.Kept, handler(CloudPage(), core, alerts, toasts, mutableListOf()).send("hi", emptyList<StagedImage>()))
        assertEquals("Couldn't start on Cloud", alerts.shown.single().title)
        assertEquals("Edge down", alerts.shown.single().message)
    }

    // ── a session's Cloud banner ───────────────────────────────────────

    @Test
    fun aCloudChatsNotDeliveredSaysWhy() {
        val nd = SessionChrome.Banner.NotDelivered
        assertEquals(nd, cloudBanner(nd, isCloud = false, outOfCredits = true, machineError = "x"))
        assertEquals(SessionChrome.Banner.None, cloudBanner(nd, isCloud = true, outOfCredits = true, machineError = "x"))
        assertEquals(
            SessionChrome.Banner.Failed("Cloud machine: Out of memory · Tap to retry"),
            cloudBanner(nd, isCloud = true, outOfCredits = false, machineError = "Out of memory"),
        )
        assertEquals(nd, cloudBanner(nd, isCloud = true, outOfCredits = false, machineError = null))
        assertEquals(SessionChrome.Banner.Sending, cloudBanner(SessionChrome.Banner.Sending, true, true, "x"))
    }
}
