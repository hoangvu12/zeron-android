package sh.zeron.android.accounts

import sh.zeron.android.shell.NavRoute

/** Whose accounts one agent's page shows (iOS `AgentAccountsViewController.Target`). */
sealed interface AccountsTarget {
    val harness: String

    /** A Cloud agent's logins and keys, kept in Cloud's vault. */
    data class Cloud(override val harness: String) : AccountsTarget

    /**
     * A computer's logins for one agent. [deviceId] `null` means the first
     * computer (a debug route); [name] / [label] are the device's and the
     * agent's names as Providers showed them, filled in on the page when
     * missing.
     */
    data class Device(
        val deviceId: String?,
        override val harness: String,
        val name: String? = null,
        val label: String? = null,
    ) : AccountsTarget
}

/**
 * One agent's accounts on one device (#50, iOS `AgentAccountsViewController`):
 * pushed from Providers. [signIn] starts the agent's first login option on
 * appear (an error card's "Log in").
 */
data class AgentAccountsRoute(val target: AccountsTarget, val signIn: Boolean = false) : NavRoute {
    override val paneTitle: String get() = "Accounts"
}

/**
 * Whose accounts an error card's "Log in" opens (#55, iOS
 * `CoreSessionSource.signInTarget`, CSS:339-347): the chat's agent
 * (Claude Code when it names none) — in Cloud for a Cloud chat (only the
 * agents Cloud runs), else on the chat's computer. `null` when there is
 * no row yet, or no computer to sign in on. Pure.
 */
fun signInTarget(
    isCloud: Boolean?,
    harness: String?,
    harnessLabel: String?,
    hostDeviceId: String,
    deviceName: (String) -> String?,
    cloudHarnesses: List<String> = sh.zeron.android.core.CloudStore.harnesses,
): AccountsTarget? {
    if (isCloud == null) return null
    val h = harness ?: "claude-code"
    if (isCloud) return if (h in cloudHarnesses) AccountsTarget.Cloud(h) else null
    if (hostDeviceId.isEmpty()) return null
    return AccountsTarget.Device(deviceId = hostDeviceId, harness = h, name = deviceName(hostDeviceId), label = harnessLabel ?: h)
}
