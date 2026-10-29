package sh.zeron.android.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * An error card's "Log in" (#55, iOS `CoreSessionSource.signInTarget`):
 * whose accounts the sheet opens, and the sheet's open/close state.
 * Plain JVM.
 */
class SignInTargetTest {

    private val names: (String) -> String? = { id -> if (id == "mac") "MacBook Pro" else null }

    @Test
    fun aComputersChatOpensItsAgentOnThatComputer() {
        assertEquals(
            AccountsTarget.Device(deviceId = "mac", harness = "codex", name = "MacBook Pro", label = "Codex"),
            signInTarget(isCloud = false, harness = "codex", harnessLabel = "Codex", hostDeviceId = "mac", deviceName = names),
        )
        // No agent named: Claude Code, labelled by its id until the row has a label.
        assertEquals(
            AccountsTarget.Device(deviceId = "mac", harness = "claude-code", name = "MacBook Pro", label = "claude-code"),
            signInTarget(false, null, null, "mac", names),
        )
        assertNull("no computer to sign in on", signInTarget(false, "codex", null, "", names))
    }

    @Test
    fun aCloudChatOpensTheCloudAgentWhenCloudRunsIt() {
        val cloud = listOf("claude-code", "codex")
        assertEquals(AccountsTarget.Cloud("codex"), signInTarget(true, "codex", null, "", names, cloud))
        assertEquals(AccountsTarget.Cloud("claude-code"), signInTarget(true, null, null, "", names, cloud))
        assertNull("Cloud doesn't run it", signInTarget(true, "amp", null, "", names, cloud))
    }

    @Test
    fun noRowYetNoTarget() {
        assertNull(signInTarget(null, "codex", null, "mac", names))
    }

    @Test
    fun theControllerHoldsOneTargetUntilDismissed() {
        val c = AccountsSheetController()
        assertNull(c.target)
        c.open(AccountsTarget.Cloud("codex"))
        assertEquals(AccountsTarget.Cloud("codex"), c.target)
        c.onDismissed()
        assertNull(c.target)
    }
}
