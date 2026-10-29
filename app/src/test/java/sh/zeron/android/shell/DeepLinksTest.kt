package sh.zeron.android.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `zeron://` return URLs, the notification tap and the `route` extra
 * grammar (#19). Plain JUnit: `DeepLinks.kt` has no `android.*`
 * dependency.
 */
class DeepLinksTest {

    // --------------------------------------------------------------- parse

    @Test
    fun parseTheThreeHosts() {
        assertEquals(ZeronUrl.Callback, DeepLinks.parse("zeron://callback?code=c&state=s"))
        assertEquals(ZeronUrl.Callback, DeepLinks.parse("zeron://callback?error=access_denied"))
        assertEquals(ZeronUrl.SignedIn, DeepLinks.parse("zeron://signed-in"))
        assertEquals(ZeronUrl.Voice, DeepLinks.parse("zeron://voice"))
    }

    @Test
    fun parseIgnoresCaseAndAnyPathQueryOrFragment() {
        assertEquals(ZeronUrl.SignedIn, DeepLinks.parse("ZERON://Signed-In/"))
        assertEquals(ZeronUrl.SignedIn, DeepLinks.parse("zeron://signed-in/x?y=1#z"))
        assertEquals(ZeronUrl.Voice, DeepLinks.parse("zeron://voice#stage"))
        assertEquals(ZeronUrl.Callback, DeepLinks.parse("  zeron://callback?code=c  "))
    }

    @Test
    fun parseDropsTheOldRoutes() {
        // D11: the Roboco-era navigation links and launcher shortcut targets.
        assertNull(DeepLinks.parse("zeron://chat/abc"))
        assertNull(DeepLinks.parse("zeron://new?prompt=Fix"))
        assertNull(DeepLinks.parse("zeron://settings"))
        assertNull(DeepLinks.parse("zeron://search"))
        assertNull(DeepLinks.parse("zeron://pair#url=x"))
        // The desktop's link and za:'s unused reserve.
        assertNull(DeepLinks.parse("zeron://open/chat/abc?workspace=0123456789abcdef"))
        assertNull(DeepLinks.parse("zeron://cloud"))
    }

    @Test
    fun parseIgnoresEverythingElse() {
        assertNull(DeepLinks.parse(null))
        assertNull(DeepLinks.parse(""))
        assertNull(DeepLinks.parse("   "))
        assertNull(DeepLinks.parse("zeron"))
        assertNull(DeepLinks.parse("zeron:callback"))
        assertNull(DeepLinks.parse("://callback"))
        assertNull(DeepLinks.parse("zeron://"))
        assertNull(DeepLinks.parse("https://callback"))
        // A host is matched whole, not by prefix.
        assertNull(DeepLinks.parse("zeron://callbacks"))
        assertNull(DeepLinks.parse("zeron://signed"))
    }

    // ----------------------------------------------------- notificationTap

    @Test
    fun notificationTapOpensTheChatVerbatim() {
        assertEquals(DeepLink.OpenChat("c1"), DeepLinks.notificationTap("c1"))
        assertEquals(DeepLink.OpenChat("Ab:C/1"), DeepLinks.notificationTap("Ab:C/1"))
    }

    @Test
    fun notificationTapWithoutAChatIsNothing() {
        assertNull(DeepLinks.notificationTap(null))
        assertNull(DeepLinks.notificationTap(""))
        assertNull(DeepLinks.notificationTap("  "))
    }

    // ---------------------------------------------------------- parseRoute

    @Test
    fun parseRouteChat() {
        assertEquals(DeepLink.OpenChat("abc"), DeepLinks.parseRoute("chat:abc"))
        // Everything after the first 5 chars, verbatim.
        assertEquals(DeepLink.OpenChat("a:b"), DeepLinks.parseRoute("chat:a:b"))
        assertEquals(DeepLink.OpenChat("a b"), DeepLinks.parseRoute("chat:a b"))
    }

    @Test
    fun parseRouteKeywords() {
        assertEquals(DeepLink.NewSession(null), DeepLinks.parseRoute("new"))
        assertEquals(DeepLink.Settings, DeepLinks.parseRoute("more"))
        assertEquals(DeepLink.Search, DeepLinks.parseRoute("search"))
    }

    @Test
    fun parseRouteRejectsEverythingElse() {
        assertNull(DeepLinks.parseRoute(null))
        assertNull(DeepLinks.parseRoute(""))
        // An empty id.
        assertNull(DeepLinks.parseRoute("chat:"))
        // Exact and case-sensitive, not trimmed: `settings` (not an iOS
        // tab id), `New`, ` new`, `chat` without a colon.
        assertNull(DeepLinks.parseRoute("settings"))
        assertNull(DeepLinks.parseRoute("New"))
        assertNull(DeepLinks.parseRoute(" new"))
        assertNull(DeepLinks.parseRoute("chat"))
        assertNull(DeepLinks.parseRoute("more "))
    }
}
