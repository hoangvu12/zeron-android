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
 * An error card's "Log in" (wave-9 #14, upstream #967): the chat's agent's
 * accounts on its computer, starting its first sign-in — or Providers for
 * that computer when the chat has no agent.
 * TODO(#55): the accounts sheet over the session instead of a push.
 */
fun errorCardLogInRoute(hostDeviceId: String, harness: String?): NavRoute =
    if (harness == null) {
        ProvidersRoute(hostDeviceId)
    } else {
        AgentAccountsRoute(AccountsTarget.Device(deviceId = hostDeviceId, harness = harness), signIn = true)
    }
