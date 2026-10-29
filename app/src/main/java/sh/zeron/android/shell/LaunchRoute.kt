package sh.zeron.android.shell

/**
 * The `route` launch extra's grammar — iOS's `-route`
 * (`SceneDelegate.swift:70-80`), the one place it is parsed (#18; #19
 * adds the debug Cloud / providers / accounts routes here).
 *
 * Exact, case-sensitive, not trimmed. `chat:<id>` takes everything after
 * the first 5 chars verbatim (no percent-decoding, like iOS
 * `dropFirst(5)`); `new` / `more` / `search` are exact matches.
 * Everything else — `chat:` (an empty id), `settings`, `New`, `""`, the
 * debug gallery's `gallery` — is null.
 *
 * Pure Kotlin, plain-JVM-testable. A route that parses also starts the
 * demo when nothing is stored (debuggable builds, `LaunchOptions`).
 */
object LaunchRoute {

    fun parse(route: String?): DeepLink? {
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
            else -> null
        }
    }
}
