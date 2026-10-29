package sh.zeron.android.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The `route` extra's grammar (#18, iOS `-route`): exact, case-sensitive, untrimmed. */
class LaunchRouteTest {

    @Test
    fun chatTakesTheRestVerbatim() {
        assertEquals(DeepLink.OpenChat("abc"), LaunchRoute.parse("chat:abc"))
        // Everything after `chat:` — colons and spaces included, never decoded.
        assertEquals(DeepLink.OpenChat("engine:v1:x"), LaunchRoute.parse("chat:engine:v1:x"))
        assertEquals(DeepLink.OpenChat("a%20b"), LaunchRoute.parse("chat:a%20b"))
    }

    @Test
    fun keywords() {
        assertEquals(DeepLink.NewSession(null), LaunchRoute.parse("new"))
        assertEquals(DeepLink.Settings, LaunchRoute.parse("more"))
        assertEquals(DeepLink.Search, LaunchRoute.parse("search"))
    }

    @Test
    fun everythingElseIsNothing() {
        for (route in listOf(null, "", "chat:", "chat", "settings", "New", " new", "more ", "gallery", "pair")) {
            assertNull("route \"$route\"", LaunchRoute.parse(route))
        }
    }

    @Test
    fun deepLinksDelegatesHere() {
        assertEquals(LaunchRoute.parse("chat:x"), DeepLinks.parseRoute("chat:x"))
        assertEquals(LaunchRoute.parse("cloud", debug = true), DeepLinks.parseRoute("cloud", debug = true))
    }

    // ---------------------------------------------------- debug routes (#19)

    private fun debug(route: String): DebugRoute? = (LaunchRoute.parse(route, debug = true) as? DeepLink.Debug)?.route

    @Test
    fun debugRoutesAreNothingOutsideDebuggableBuilds() {
        for (route in listOf("cloud", "cloud:billing", "billing", "providers", "accounts:codex", "welcome")) {
            assertNull("route \"$route\"", LaunchRoute.parse(route))
        }
    }

    @Test
    fun cloudRoutes() {
        assertEquals(DebugRoute.CloudSettings, debug("cloud"))
        assertEquals(DebugRoute.CloudBilling, debug("cloud:billing"))
        assertEquals(DebugRoute.CloudAgent("codex"), debug("cloud:agent:codex"))
        assertEquals(DebugRoute.CloudRepos, debug("cloud:repos"))
        assertEquals(DebugRoute.GitHubConnect, debug("cloud:github"))
        // Like iOS: an unknown page, or `agent` without a harness, is Cloud settings alone.
        assertEquals(DebugRoute.CloudSettings, debug("cloud:agent"))
        assertEquals(DebugRoute.CloudSettings, debug("cloud:agent:"))
        assertEquals(DebugRoute.CloudSettings, debug("cloud:nope"))
        assertEquals(DebugRoute.CloudSettings, debug("cloud:"))
    }

    @Test
    fun providersAccountsBillingWelcome() {
        assertEquals(DebugRoute.Providers(null), debug("providers"))
        assertEquals(DebugRoute.Providers(null), debug("providers:"))
        assertEquals(DebugRoute.Providers("cloud"), debug("providers:cloud"))
        assertEquals(DebugRoute.Providers("dev-1"), debug("providers:dev-1"))
        assertEquals(DebugRoute.Accounts("claude-code"), debug("accounts:claude-code"))
        assertEquals(DebugRoute.Billing, debug("billing"))
        assertEquals(DebugRoute.Welcome, debug("welcome"))
    }

    @Test
    fun malformedDebugRoutesAreNothing() {
        for (route in listOf("accounts", "accounts:", "cloudy", "Cloud", " cloud", "billing:x", "welcome:x", "provider")) {
            assertNull("route \"$route\"", LaunchRoute.parse(route, debug = true))
        }
        // The plain routes are unchanged with the debug flag.
        assertEquals(DeepLink.Settings, LaunchRoute.parse("more", debug = true))
        assertNull(LaunchRoute.parse("gallery", debug = true))
    }
}
