package sh.zeron.android.accounts

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.zeron_core.AgentLoginRules
import uniffi.zeron_core.CoreException
import uniffi.zeron_core.DeviceAccountInfo
import uniffi.zeron_core.DeviceLoginInfo
import uniffi.zeron_core.DeviceLoginPollInfo
import uniffi.zeron_core.HarnessInfo
import kotlin.coroutines.cancellation.CancellationException

/**
 * A computer's sign-in from the phone (#50, iOS `DeviceSignIn`): the page
 * opened, the poll until done or error, a device code copied, the paste
 * flow, calling it off, and the ten-minute deadline. Plain JVM; the
 * poll's delay runs on the test scheduler.
 */
class DeviceSignInTest {

    private class FakeCore(val start: DeviceLoginInfo, val polls: ArrayDeque<DeviceLoginPollInfo>) : ProvidersCore {
        val cancelled = mutableListOf<String>()
        var completedWith: String? = null

        override suspend fun harnesses(deviceId: String): List<HarnessInfo> = emptyList()
        override suspend fun setEnabled(deviceId: String, harness: String, enabled: Boolean): List<HarnessInfo> = emptyList()
        override suspend fun accounts(deviceId: String): List<DeviceAccountInfo> = emptyList()
        override suspend fun activate(deviceId: String, harness: String, accountId: String): List<DeviceAccountInfo> = emptyList()
        override suspend fun forget(deviceId: String, harness: String, accountId: String): List<DeviceAccountInfo> = emptyList()
        override suspend fun loginStart(deviceId: String, harness: String, provider: String?): DeviceLoginInfo = start
        override suspend fun loginPoll(deviceId: String, loginId: String): DeviceLoginPollInfo =
            polls.removeFirstOrNull() ?: DeviceLoginPollInfo(status = "pending", message = null, url = null)
        override suspend fun loginComplete(deviceId: String, loginId: String, code: String): List<DeviceAccountInfo> {
            completedWith = code
            return emptyList()
        }
        override fun loginCancel(deviceId: String, loginId: String) {
            cancelled += loginId
        }
        override fun rules(harness: String): AgentLoginRules = error("unused")
    }

    private fun poll(status: String, message: String? = null, url: String? = null) = DeviceLoginPollInfo(status, message, url)

    @Test
    fun theDoneUrlPollFinishesAndCopiesTheDeviceCode() = runTest {
        val core = FakeCore(
            DeviceLoginInfo(loginId = "l1", url = "", pasteCode = false),
            ArrayDeque(listOf(poll("pending", "Enter the code ABCD-1234 when asked", "https://login"), poll("done"))),
        )
        val opened = mutableListOf<String>()
        val copied = mutableListOf<String>()
        val messages = mutableListOf<String?>()
        val signIn = DeviceSignIn(core, open = { opened += it }, askForCode = { null }, copy = { copied += it }, nowMs = { 0L })
        assertNull("done: re-read the accounts", signIn.run("m", "codex", null) { messages += it })
        assertEquals("the page from a later poll, once", listOf("https://login"), opened)
        assertEquals(listOf("ABCD-1234"), copied)
        assertEquals(listOf<String?>("Enter the code ABCD-1234 when asked"), messages)
    }

    @Test
    fun anErrorPollThrowsItsMessage() = runTest {
        val core = FakeCore(DeviceLoginInfo("l1", "https://p", false), ArrayDeque(listOf(poll("error", "Nope"))))
        val signIn = DeviceSignIn(core, open = {}, askForCode = { null }, copy = {}, nowMs = { 0L })
        try {
            signIn.run("m", "codex", null) {}
            fail("expected a failure")
        } catch (e: CoreException.HostException) {
            assertEquals("Nope", e.reason)
        }
    }

    @Test
    fun thePasteFlowCompletesWithTheCodeOrCancels() = runTest {
        val core = FakeCore(DeviceLoginInfo("l1", "https://p", pasteCode = true), ArrayDeque())
        DeviceSignIn(core, open = {}, askForCode = { "abc" }, copy = {}).run("m", "claude-code", "claude") {}
        assertEquals("abc", core.completedWith)

        val none = FakeCore(DeviceLoginInfo("l2", "https://p", pasteCode = true), ArrayDeque())
        try {
            DeviceSignIn(none, open = {}, askForCode = { null }, copy = {}).run("m", "claude-code", "claude") {}
            fail("expected a cancellation")
        } catch (e: CancellationException) {
            assertEquals(listOf("l2"), none.cancelled)
        }
    }

    @Test
    fun callingItOffCancelsOnTheComputer() = runTest {
        val core = FakeCore(DeviceLoginInfo("l1", "https://p", false), ArrayDeque())
        lateinit var signIn: DeviceSignIn
        signIn = DeviceSignIn(core, open = { signIn.cancel() }, askForCode = { null }, copy = {}, nowMs = { 0L })
        try {
            signIn.run("m", "codex", null) {}
            fail("expected a cancellation")
        } catch (e: CancellationException) {
            assertEquals(listOf("l1"), core.cancelled)
        }
    }

    @Test
    fun tenMinutesWithoutAnAnswerTimesOut() = runTest {
        val core = FakeCore(DeviceLoginInfo("l1", "https://p", false), ArrayDeque())
        var now = 0L
        val signIn = DeviceSignIn(core, open = {}, askForCode = { null }, copy = {}, nowMs = { now.also { now += 60_000L } })
        try {
            signIn.run("m", "codex", null) {}
            fail("expected a timeout")
        } catch (e: CoreException.HostException) {
            assertEquals("The sign-in timed out.", e.reason)
            assertTrue(core.cancelled.contains("l1"))
        }
    }
}
