package sh.zeron.android.shell

/**
 * The `route` launch extra's grammar — iOS's `-route`
 * (`SceneDelegate.swift:70-80`), the one place it is parsed (#18; #19
 * adds the debug Cloud / providers / accounts routes).
 *
 * Exact, case-sensitive, not trimmed. `chat:<id>` takes everything after
 * the first 5 chars verbatim (no percent-decoding, like iOS
 * `dropFirst(5)`); `new` / `more` / `search` are exact matches.
 * Everything else — `chat:` (an empty id), `settings`, `New`, `""`, the
 * debug gallery's `gallery` — is null.
 *
 * With [parse]'s `debug` (debuggable builds only — iOS's `#if DEBUG`),
 * the [DebugRoute]s parse too (`SceneDelegate.swift:106-152`).
 *
 * Pure Kotlin, plain-JVM-testable. A route that parses also starts the
 * demo when nothing is stored (debuggable builds, `LaunchOptions`).
 */
object LaunchRoute {

    fun parse(route: String?, debug: Boolean = false): DeepLink? {
        if (route == null) return null
        if (route.startsWith("chat:")) {
            val id = route.substring(5)
            if (id.isEmpty()) return null
            return DeepLink.OpenChat(id)
        }
        return when (route) {
            "new" -> DeepLink.NewSession(null)
            "more" -> DeepLink.Settings
            "search" -> DeepLink.Search
            else -> if (debug) debugRoute(route)?.let(DeepLink::Debug) else null
        }
    }

    /**
     * iOS `openCloudRoute`: `cloud` · `cloud:billing` ·
     * `cloud:agent:<harness>` · `cloud:repos` · `cloud:github` ·
     * `billing` · `providers[:cloud|:<device id>]` · `accounts:<harness>`
     * · `welcome`, split on `:`. Like iOS, any other `cloud:…` (and
     * `cloud:agent` without a harness) opens Cloud settings alone. Unlike
     * iOS, the first part must match exactly (`cloudy` is nothing) and
     * `accounts` without a harness is nothing (iOS falls back to Cloud
     * settings).
     */
    private fun debugRoute(route: String): DebugRoute? {
        val parts = route.split(':')
        val second = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
        return when (parts[0]) {
            "welcome" -> if (parts.size == 1) DebugRoute.Welcome else null
            "billing" -> if (parts.size == 1) DebugRoute.Billing else null
            "providers" -> DebugRoute.Providers(target = second)
            "accounts" -> second?.let(DebugRoute::Accounts)
            "cloud" -> when (second) {
                "billing" -> DebugRoute.CloudBilling
                "agent" -> parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
                    ?.let(DebugRoute::CloudAgent) ?: DebugRoute.CloudSettings
                "repos" -> DebugRoute.CloudRepos
                "github" -> DebugRoute.GitHubConnect
                else -> DebugRoute.CloudSettings
            }
            else -> null
        }
    }
}

/**
 * A debug `route` screen (#19, iOS `SceneDelegate.openCloudRoute`, DEBUG
 * only): screenshots and tests open them straight away. Dispatched as
 * [AppRouter.showDebugRoute].
 */
sealed interface DebugRoute {

    /** `cloud` (and any other `cloud:…`): Cloud settings. */
    data object CloudSettings : DebugRoute

    /** `cloud:billing`: Cloud settings with Billing pushed. */
    data object CloudBilling : DebugRoute

    /** `cloud:agent:<harness>`: Cloud settings with that Cloud agent's accounts pushed. */
    data class CloudAgent(val harness: String) : DebugRoute

    /** `cloud:repos`: the Cloud repo picker. */
    data object CloudRepos : DebugRoute

    /** `cloud:github`: the GitHub connect sheet. */
    data object GitHubConnect : DebugRoute

    /** `billing`: Billing on its own. */
    data object Billing : DebugRoute

    /** `providers[:cloud|:<device id>]`: the Providers page, optionally for one target. */
    data class Providers(val target: String?) : DebugRoute

    /** `accounts:<harness>`: the first computer's accounts for one agent, pushed from Providers. */
    data class Accounts(val harness: String) : DebugRoute

    /** `welcome`: the signed-out Welcome page over the shell, without signing out. */
    data object Welcome : DebugRoute
}
