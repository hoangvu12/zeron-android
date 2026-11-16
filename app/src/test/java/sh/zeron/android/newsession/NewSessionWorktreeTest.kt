package sh.zeron.android.newsession

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A new session only asks its host for a worktree in a git project: the
 * host refuses one anywhere else, leaving an empty chat behind. The port
 * of iOS `ZeronTests/NewSessionWorktreeTests.swift` (upstream 922aea4cc)
 * onto [NewSessionRequests.worktreeSpec].
 */
class NewSessionWorktreeTest {

    private fun project(git: Boolean) = ProjectOption(
        id = "project",
        name = "notes",
        path = "/code/notes",
        device = "mac",
        deviceName = "mac",
        online = true,
        git = git,
        groupKey = "notes",
        groupName = "notes",
        colorIndex = 0,
    )

    private fun draft(worktree: Boolean, branch: String? = null) =
        NewSessionDraft(worktree = worktree, branch = branch)

    @Test
    fun aGitProjectGetsTheWorktreeItWasAsked() {
        val spec = checkNotNull(NewSessionRequests.worktreeSpec(draft(worktree = true), project(git = true)))
        assertEquals("/code/notes", spec.repoPath)
        assertEquals("HEAD", spec.base)
        assertEquals("project", spec.spaceId)
    }

    @Test
    fun aWorktreeStartsOffThePickedBranch() {
        val spec = NewSessionRequests.worktreeSpec(draft(worktree = true, branch = "release"), project(git = true))
        assertEquals("release", spec?.base)
    }

    @Test
    fun aPickKeptFromAGitProjectIsNotSentForAPlainFolder() {
        assertNull(NewSessionRequests.worktreeSpec(draft(worktree = true), project(git = false)))
    }

    @Test
    fun noPickNoWorktree() {
        assertNull(NewSessionRequests.worktreeSpec(draft(worktree = false), project(git = true)))
    }

    @Test
    fun noProjectNoWorktree() {
        assertNull(NewSessionRequests.worktreeSpec(draft(worktree = true), project = null))
    }
}
