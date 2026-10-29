package sh.zeron.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.zeron_core.BillingInfo
import uniffi.zeron_core.CloudInfo
import uniffi.zeron_core.CloudSessionInfo
import uniffi.zeron_core.CoreException
import uniffi.zeron_core.GithubRepoInfo

/**
 * The Cloud store's rules (#48, iOS `CloudStore`) on the plain JVM:
 * credits, out of credits, the page's problem, a chat's machine error,
 * which repos Cloud reaches, the error copy and the status retry back-off.
 */
class CloudRulesTest {

    private fun info(available: Boolean = true, state: String = "ready", credits: Double? = 100.0, error: String? = null) =
        CloudInfo(
            available = available,
            state = state,
            deviceId = null,
            credits = credits,
            awakeSessions = 0u,
            maxAwakeSessions = 2u,
            error = error,
        )

    private fun billing(total: Double) = BillingInfo(
        available = true,
        plan = null,
        planStatus = null,
        periodEndMs = null,
        cancelAtPeriodEnd = false,
        creditsTotal = total,
        creditsMonthly = 0.0,
        creditsMonthlyTotal = 0.0,
        creditsMonthlyEndsMs = null,
        creditsExtra = 0.0,
        plans = emptyList(),
        packPriceCents = 1000u,
        packCredits = 0.0,
        hasCustomer = false,
    )

    private fun session(chat: String, device: String, state: String, error: String? = null) =
        CloudSessionInfo(chatId = chat, deviceId = device, state = state, repo = null, error = error)

    private fun repo(name: String) =
        GithubRepoInfo(fullName = name, defaultBranch = "main", private = false, description = null, pushedAtMs = 0L)

    @Test
    fun creditsPreferBillingAndOutOfCreditsPrefersTheStatus() {
        assertEquals(5.0, cloudCredits(info(credits = 100.0), billing(5.0))!!, 0.0)
        assertEquals(100.0, cloudCredits(info(credits = 100.0), null)!!, 0.0)
        assertNull(cloudCredits(null, null))
        // The desktop's rule reads the status first.
        assertFalse(cloudOutOfCredits(info(credits = 10.0), billing(0.0)))
        assertTrue(cloudOutOfCredits(info(credits = 0.0), billing(50.0)))
        assertTrue(cloudOutOfCredits(info(credits = null), billing(-1.0)))
        assertFalse(cloudOutOfCredits(null, null))
    }

    @Test
    fun theProblemIsAnErrorStateElseNoCreditsAndNothingWhenNotOffered() {
        assertNull(cloudProblem(null, null))
        assertNull(cloudProblem(info(available = false, state = "error"), null))
        assertNull(cloudProblem(info(), null))

        val failed = cloudProblem(info(state = "error", error = "Machine pool down"), null)!!
        assertEquals("Cloud ran into a problem", failed.title)
        assertEquals("Machine pool down", failed.detail)
        assertFalse(failed.billing)
        assertEquals("Something went wrong with Cloud.", cloudProblem(info(state = "error"), null)!!.detail)

        val broke = cloudProblem(info(credits = 0.0), null)!!
        assertEquals("Out of credits", broke.title)
        assertEquals("Cloud sessions can't start or wake until you add credits.", broke.detail)
        assertTrue(broke.billing)
    }

    @Test
    fun aChatsMachineErrorMatchesTheChatThenTheDevice() {
        val sessions = listOf(
            session("c1", "d1", "ready"),
            session("c2", "d2", "error", "Out of memory"),
            session("c3", "d3", "error"),
        )
        assertNull(cloudSessionError(sessions, "c1", "d2"))
        assertEquals("Out of memory", cloudSessionError(sessions, "c2", null))
        assertEquals("Out of memory", cloudSessionError(sessions, "other", "d2"))
        assertEquals("Its machine failed to start.", cloudSessionError(sessions, "c3", null))
        assertNull(cloudSessionError(sessions, "other", null))
    }

    @Test
    fun cloudReachesARepoCaseInsensitivelyOnlyWhileOffered() {
        val repos = listOf(repo("zeronsh/zeron"))
        assertTrue(cloudReaches(true, repos, "ZeronSh/Zeron"))
        assertFalse(cloudReaches(false, repos, "zeronsh/zeron"))
        assertFalse(cloudReaches(true, repos, null))
        assertFalse(cloudReaches(true, null, "zeronsh/zeron"))
        assertFalse(cloudReaches(true, repos, "zeronsh/other"))
    }

    @Test
    fun errorsReadAsTheirOwnWordsAndClosedAsSignedOut() {
        assertEquals("You're signed out.", CloudStore.message(CoreException.Closed()))
        assertEquals("no such repo", CloudStore.message(CoreException.NotFound("no such repo")))
        assertEquals("Edge down", CloudStore.message(CoreException.Network("Edge down")))
        assertEquals("boom", CloudStore.message(IllegalStateException("boom")))
        assertTrue(CloudStore.isOutOfCredits(CoreException.HostException("You're out of Cloud credits")))
        assertFalse(CloudStore.isOutOfCredits(CoreException.HostException("Machine failed")))
    }

    @Test
    fun theStatusRetryDoublesToFiveMinutes() {
        assertEquals(60_000L, CloudStore.nextRetryDelay(CloudStore.RETRY_FIRST_MS))
        assertEquals(CloudStore.RETRY_MAX_MS, CloudStore.nextRetryDelay(240_000L))
        assertEquals(CloudStore.RETRY_MAX_MS, CloudStore.nextRetryDelay(CloudStore.RETRY_MAX_MS))
    }

    @Test
    fun theAgentsAndTheFormats() {
        assertEquals(listOf("claude-code", "codex"), CloudStore.harnesses)
        assertEquals(listOf("claude", "codex"), CloudStore.agents.map { it.login })
        assertEquals(listOf("anthropic-key", "openai-key"), CloudStore.agents.map { it.key })
        assertEquals("$20", CloudStore.price(2000u))
        assertEquals("$12.50", CloudStore.price(1250u))
    }
}
