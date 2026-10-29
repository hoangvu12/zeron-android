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
    }
}
