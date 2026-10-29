package sh.zeron.android.newsession

import android.app.Application
import androidx.compose.ui.graphics.ImageBitmap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.zeron.android.composer.StagedImage
import uniffi.zeron_core.BusyPolicy
import uniffi.zeron_core.SandboxLevel
import uniffi.zeron_core.SessionTarget

/**
 * [NewSessionRequests] (android-ui-parity #94) — the Request mapping
 * table, field by field. Runs under Robolectric for the `StagedImage`
 * thumb (a platform `ImageBitmap`), on a plain [Application] so the core
 * never loads. (#37 dropped the offline Send gate, `newSessionCanWrite`,
 * and its table with it: the core queues the first message for an offline
 * host, as on iOS.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NewSessionRequestsTest {

    // ------------------------------------------------------------- fixtures

    /** One project row; [online] and [path] are the fields the mapping reads. */
    private fun project(id: String, path: String = "/repo", online: Boolean = true) = ProjectOption(
        id = id,
        name = "Project $id",
        path = path,
        device = "dev-$id",
        deviceName = "dev-$id",
        online = online,
        git = true,
        groupKey = "g-$id",
        groupName = "g-$id",
        colorIndex = 0,
    )

    private fun image(id: String, vararg bytes: Int) =
        StagedImage(id, "$id.jpg", bytes.map { it.toByte() }.toByteArray(), ImageBitmap(1, 1))

    // ── target precedence (`AppModel.swift:622-628`) ─────────────────────

    @Test
    fun projectWinsOverHostWhenBothAreSet() {
        val d = NewSessionDraft(projectId = "p1", hostId = "h1")
        when (val t = NewSessionRequests.target(d)) {
            is SessionTarget.Project -> assertEquals("p1", t.spaceId)
            else -> fail("expected Project, was $t")
        }
    }

    @Test
    fun hostOnlyTargetsProjectless() {
        val d = NewSessionDraft(hostId = "h1")
        when (val t = NewSessionRequests.target(d)) {
            is SessionTarget.Projectless -> assertEquals("h1", t.deviceId)
            else -> fail("expected Projectless, was $t")
        }
    }

    @Test
    fun neitherTargetIsNullSoNoCoreCallHappens() {
        assertNull(NewSessionRequests.target(NewSessionDraft()))
    }

    // ── NewSession (`:629-630`) ──────────────────────────────────────────

    @Test
    fun configCarriesTheDraftWithWorkspaceWriteAndNilEffort() {
        val d = NewSessionDraft(
            projectId = "p1",
            harness = "mock",
            model = "mock-1",
            effort = null,
        )
        val r = NewSessionRequests.newSession(d, SessionTarget.Project(spaceId = "p1"))
        val config = checkNotNull(r.config)
        assertEquals("mock", config.harness)
        assertEquals("mock-1", config.model)
        assertNull(config.reasoning) // nil ⇒ the engine chooses
        assertTrue(config.modelOptions.isEmpty())
        assertEquals(SandboxLevel.WORKSPACE_WRITE, config.sandbox)
        assertEquals("p1", (r.target as SessionTarget.Project).spaceId)
    }

    @Test
    fun effortMapsToReasoning() {
        val d = NewSessionDraft(projectId = "p1", effort = "ultrathink")
        val r = NewSessionRequests.newSession(d, SessionTarget.Projectless(deviceId = "h1"))
        assertEquals("ultrathink", checkNotNull(r.config).reasoning)
    }

    @Test
    fun branchIsSentOnlyWithoutAWorktree() {
        val noWorktree = NewSessionRequests.newSession(
            NewSessionDraft(projectId = "p1", branch = "main"),
            SessionTarget.Project(spaceId = "p1"),
        )
        assertEquals("main", noWorktree.branch)

        val worktree = NewSessionRequests.newSession(
            NewSessionDraft(projectId = "p1", branch = "main", worktree = true),
            SessionTarget.Project(spaceId = "p1"),
        )
        assertNull(worktree.branch) // the branch becomes the worktree's base
    }

    @Test
    fun cwdAndTitleAreAlwaysNull() {
        val r = NewSessionRequests.newSession(
            NewSessionDraft(projectId = "p1"),
            SessionTarget.Project(spaceId = "p1"),
        )
        assertNull(r.cwd)
        assertNull(r.title)
    }

    // ── SendRequest (`:632-637`, `:646-648`) ────────────────────────────

    @Test
    fun imagesBecomeJpegAttachmentsWithTheStagedBytes() {
        val d = NewSessionDraft(projectId = "p1")
        val images = listOf(image("i1", 1, 2, 3), image("i2", 9))
        val r = NewSessionRequests.sendRequest(d, "body", images, listOf(project("p1")))
        assertEquals("body", r.text)
        assertEquals(2, r.attachments.size)
        assertEquals("i1.jpg", r.attachments[0].name)
        assertEquals("image/jpeg", r.attachments[0].mimeType)
        assertArrayEquals(byteArrayOf(1, 2, 3), r.attachments[0].data)
        assertEquals("i2.jpg", r.attachments[1].name)
        assertArrayEquals(byteArrayOf(9), r.attachments[1].data)
        assertEquals(BusyPolicy.QUEUE, r.busy) // a new chat is idle
    }

    @Test
    fun worktreeSpecUsesTheProjectPathAndTheBranchAsBase() {
        val d = NewSessionDraft(projectId = "p1", branch = "main", worktree = true)
        val r = NewSessionRequests.sendRequest(d, "body", emptyList(), listOf(project("p1", path = "C:\\repo")))
        val w = checkNotNull(r.worktree)
        assertEquals("C:\\repo", w.repoPath)
        assertEquals("main", w.base)
        assertEquals("p1", w.spaceId)
    }

    @Test
    fun worktreeBaseFallsBackToHeadWithoutABranch() {
        val d = NewSessionDraft(projectId = "p1", branch = null, worktree = true)
        val r = NewSessionRequests.sendRequest(d, "body", emptyList(), listOf(project("p1")))
        assertEquals("HEAD", checkNotNull(r.worktree).base)
    }

    @Test
    fun worktreeWithoutAProjectIsNullEvenWhenTheDraftAsksForOne() {
        val d = NewSessionDraft(hostId = "h1", worktree = true)
        val r = NewSessionRequests.sendRequest(d, "body", emptyList(), emptyList())
        assertNull(r.worktree) // iOS `project.map` — no project, no spec
    }

    @Test
    fun noWorktreeMeansNoSpec() {
        val d = NewSessionDraft(projectId = "p1", worktree = false)
        val r = NewSessionRequests.sendRequest(d, "body", emptyList(), listOf(project("p1")))
        assertNull(r.worktree)
    }

    @Test
    fun aProjectMissingFromTheOptionsGivesNoWorktreeSpec() {
        val d = NewSessionDraft(projectId = "gone", worktree = true)
        val r = NewSessionRequests.sendRequest(d, "body", emptyList(), listOf(project("p1")))
        assertNull(r.worktree)
    }
}
