package sh.zeron.android.accounts

import sh.zeron.android.shell.NavRoute

/**
 * The Accounts & Usage page's route (android-next#05). Pushed by an
 * error card's "Log in" and, until the Providers page lands (#50), by
 * Settings → Agents → Providers (#49). Roboco's usage chip and ⋯ "Account &
 * Usage" entry points are gone (05 I1/I2).
 *
 * [deviceId] is the computer whose accounts the page shows — the engine
 * device id the opener holds (`null` = the primary device: the page picks
 * the Settings section's rule). [focusHarness] (the session opener's
 * agent) scrolls the page to that harness's section. [signIn] (an error
 * card's "Log in", wave-10 35) starts [focusHarness]'s first login as
 * soon as the page has its accounts — the iPhone's `signIn: true`, the
 * desktop's `start_first_login`.
 *
 * `hidesBottomChrome` stays false: Settings pushes keep the tab bar
 * (only the Session screen hides it).
 */
data class AgentAccountsRoute(
    val deviceId: String?,
    val focusHarness: String? = null,
    val signIn: Boolean = false,
) : NavRoute {
    override val paneTitle: String = AccountsCopy.PageTitle
}

/**
 * Where an error card's "Log in" goes (wave-10 35, upstream #967's
 * `SessionViewController.swift:89-99`): the chat's agent on its computer,
 * its first login started at once. Pure.
 */
fun errorCardLogInRoute(hostDeviceId: String, harness: String?): AgentAccountsRoute =
    AgentAccountsRoute(
        deviceId = hostDeviceId.ifEmpty { null },
        focusHarness = harness,
        signIn = true,
    )
