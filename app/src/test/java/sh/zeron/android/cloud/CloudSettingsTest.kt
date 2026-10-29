package sh.zeron.android.cloud

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.zeron.android.core.CloudStore
import sh.zeron.android.design.ZeronIcons
import uniffi.zeron_core.CoreException
import uniffi.zeron_core.GithubConnectInfo
import uniffi.zeron_core.GithubDeviceFlowInfo
import uniffi.zeron_core.GithubRepoInfo

/**
 * Settings → Cloud's rows and Connect GitHub's flow (#51, iOS
 * `CloudSettingsViewController.reload()` / `GitHubConnectViewController`)
 * on the plain JVM.
 */
class CloudSettingsTest {

    private fun repo(name: String, pushed: Long, private: Boolean = false) =
        GithubRepoInfo(fullName = name, defaultBranch = "main", private = private, description = null, pushedAtMs = pushed)

    private fun sections(
        infoKnown: Boolean = true,
        available: Boolean = true,
        error: String? = null,
        problem: CloudStore.Problem? = null,
        account: String? = null,
        connected: Boolean = false,
        repos: List<GithubRepoInfo>? = null,
    ) = cloudSettingsSections(infoKnown, available, error, problem, account, connected, repos)

    @Test
    fun anAccountWithoutCloudShowsOnlyTheUnavailableRow() {
        val out = sections(available = false, error = "Edge has no Cloud")
        val row = out.single().rows.single()
        assertEquals(CloudRowIds.Unavailable, row.id)
        assertEquals("Cloud isn't available", row.title)
        assertEquals("Edge has no Cloud", row.subtitle)
        assertFalse(row.disclosure)
        assertEquals("This account can't use Cloud yet.", sections(available = false).single().rows.single().subtitle)
        // Before any status read answers, the page lays out as available.
        assertEquals(listOf("github"), sections(infoKnown = false, available = false).map { it.key })
    }

    @Test
    fun theProblemRowLeadsToBillingOnlyForCredits() {
        val credits = sections(problem = CloudStore.Problem("Out of credits", "Add some.", billing = true)).first().rows.single()
        assertEquals(CloudRowIds.StatusBilling, credits.id)
        assertEquals("Add credits", credits.accessory)
        assertTrue(credits.disclosure)
        assertEquals(ZeronIcons.ExclamationmarkTriangle, credits.icon)
        assertEquals(CloudRowTint.Warning, credits.tint)

        val failed = sections(problem = CloudStore.Problem("Cloud ran into a problem", "Down.", billing = false)).first().rows.single()
        assertEquals(CloudRowIds.Status, failed.id)
        assertNull(failed.accessory)
        assertFalse(failed.disclosure)
    }

    @Test
    fun theGitHubRowAsksToConnectUntilConnected() {
        val out = sections()
        assertEquals(listOf("github"), out.map { it.key })
        val row = out.single().rows.single()
        assertEquals("GitHub", row.title)
        assertEquals("Connect so Cloud agents can clone and push", row.subtitle)
        assertEquals("Connect", row.accessory)
        assertEquals(
            "Cloud agents clone and push to the repositories the Zeron GitHub App is installed on.",
            out.single().footer,
        )

        val connected = sections(account = "octo", connected = true, repos = emptyList())
        val github = connected.first { it.key == "github" }.rows.single()
        assertEquals("@octo", github.title)
        assertEquals("Connected", github.subtitle)
        assertNull(github.accessory)
    }

    @Test
    fun repositoriesNewestFirstEightThenMoreThenChoose() {
        val repos = (1..10).map { repo("o/r$it", pushed = it.toLong(), private = it % 2 == 0) }
        val rows = sections(account = "octo", connected = true, repos = repos).last().rows
        assertEquals(listOf("o/r10", "o/r9", "o/r8"), rows.take(3).map { it.title })
        assertEquals(10, rows.size) // 8 repos + "2 more" + choose
        assertEquals("Private", rows[0].subtitle)
        assertEquals(ZeronIcons.Lock, rows[0].icon)
        assertNull(rows[1].subtitle)
        assertEquals(ZeronIcons.BookClosed, rows[1].icon)
        assertEquals("2 more", rows[8].title)
        assertEquals(CloudRowIds.ReposChoose, rows[9].id)
        assertEquals("Cloud sessions can open 10 repositories.", rows[9].subtitle)
    }

    @Test
    fun theChooseSummaryFollowsTheCount() {
        assertEquals("Checking which repositories Cloud can open…", CloudSettingsCopy.reposSummary(null))
        assertEquals("None yet: choose the repositories Cloud sessions can open.", CloudSettingsCopy.reposSummary(0))
        assertEquals("Cloud sessions can open 1 repository.", CloudSettingsCopy.reposSummary(1))
        val unknown = sections(account = "octo", connected = true, repos = null).last().rows
        assertEquals(listOf(CloudRowIds.ReposChoose), unknown.map { it.id })
    }

    @Test
    fun theConnectedActions() {
        val withUrl = githubActions("octo", openInstall = {}, reconnect = {})
        assertEquals("@octo", withUrl.title)
        assertEquals(listOf("Choose Repositories on GitHub", "Reconnect"), withUrl.actions.map { it.title })
        assertEquals(listOf("Reconnect"), githubActions(null, openInstall = null, reconnect = {}).actions.map { it.title })

    }

    // ── Connect GitHub ─────────────────────────────────────────────────

    private val flow = GithubDeviceFlowInfo(
        flowId = "f1",
        userCode = "ABCD-1234",
        verificationUri = "https://github.com/login/device",
        intervalSecs = 1u,
        expiresAtMs = 0L,
    )

    private class FakeApi(
        private val onStart: () -> GithubDeviceFlowInfo,
        private val onPolls: MutableList<() -> GithubConnectInfo>,
    ) : GitHubFlowApi {
        override suspend fun start() = onStart()
        override suspend fun poll(flowId: String) = onPolls.removeAt(0)()
    }

    @Test
    fun theFlowShowsTheCodePollsEveryTwoSecondsAtLeastAndConnects() = runBlocking {
        val sleeps = mutableListOf<Long>()
        val api = FakeApi(
            onStart = { flow },
            onPolls = mutableListOf(
                { GithubConnectInfo("pending", null, null) },
                { throw CoreException.Network("blip") },
                { GithubConnectInfo("connected", "octo", null) },
            ),
        )
        val model = GitHubConnectModel(api, sleep = { sleeps += it })
        assertEquals("· · · ·", model.code)
        assertTrue(model.run())
        assertEquals(listOf(2_000L, 2_000L, 2_000L), sleeps)
        assertTrue(model.connected)
        assertEquals("Connected", model.code)
        assertEquals("Signed in to GitHub as @octo.", model.status)
        assertFalse(model.busy)
        assertFalse(model.failed)
        assertEquals(flow, model.flow)
    }

    @Test
    fun aFailedFlowOrStartShowsTheErrorInDanger() = runBlocking {
        val failed = GitHubConnectModel(
            FakeApi({ flow }, mutableListOf({ GithubConnectInfo("failed", null, null) })),
            sleep = {},
        )
        assertFalse(failed.run())
        assertEquals("GitHub didn't connect. Try again.", failed.status)
        assertTrue(failed.failed)
        assertEquals("ABCD-1234", failed.code)

        val noStart = GitHubConnectModel(FakeApi({ throw CoreException.Closed() }, mutableListOf()), sleep = {})
        assertFalse(noStart.run())
        assertEquals("You're signed out.", noStart.status)
        assertTrue(noStart.failed)
        assertNull(noStart.flow)
    }
}
