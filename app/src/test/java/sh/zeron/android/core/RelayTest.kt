package sh.zeron.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Relay setting's pure parts (#130): the URL rules and the health reply. */
class RelayTest {

    private fun valid(url: String) = Relay.Input.Valid(url)

    @Test
    fun httpsIsAddedAndTheUrlNormalised() {
        assertEquals(valid("https://zeron-v2.embedez.com"), Relay.parse("  zeron-v2.embedez.com/ ", debuggable = false))
        assertEquals(valid("https://relay.example.com:8443/edge"), Relay.parse("HTTPS://Relay.Example.com:8443/edge//", false))
    }

    @Test
    fun httpOnlyForLocalAddressesInDebug() {
        assertEquals(Relay.Input.Invalid(Relay.HTTPS_ONLY), Relay.parse("http://relay.example.com", debuggable = true))
        assertEquals(Relay.Input.Invalid(Relay.HTTPS_ONLY), Relay.parse("http://10.0.2.2:8787", debuggable = false))
        assertEquals(valid("http://10.0.2.2:8787"), Relay.parse("http://10.0.2.2:8787", debuggable = true))
        assertEquals(valid("http://localhost:8787"), Relay.parse("http://localhost:8787", debuggable = true))
        assertEquals(valid("http://192.168.1.20"), Relay.parse("http://192.168.1.20", debuggable = true))
    }

    @Test
    fun notAUrl() {
        for (text in listOf("", "   ", "not a url", "https://", "https://u:p@relay.example.com", "https://r.example.com/?a=1", "https://r.example.com/#x", "ftp://r.example.com")) {
            assertEquals(text, Relay.Input.Invalid(Relay.NOT_A_URL), Relay.parse(text, debuggable = true))
        }
        assertEquals(Relay.Input.Invalid(Relay.HTTPS_ONLY), Relay.parse("wss://r.example.com", false))
    }

    @Test
    fun localHosts() {
        for (host in listOf("localhost", "127.0.0.1", "10.0.2.2", "172.16.0.1", "172.31.255.1", "192.168.0.1", "mac.local")) {
            assertTrue(host, Relay.isLocal(host))
        }
        for (host in listOf("172.32.0.1", "8.8.8.8", "relay.example.com", "10.example.com")) {
            assertFalse(host, Relay.isLocal(host))
        }
    }

    @Test
    fun hostForDisplay() {
        assertEquals("edge.zeron.sh", Relay.host("https://edge.zeron.sh"))
        assertEquals("10.0.2.2:8787", Relay.host("http://10.0.2.2:8787"))
    }

    @Test
    fun healthReplies() {
        assertEquals(Relay.Health.Ok("workos"), Relay.health(200, """{"ok":true,"auth":"workos"}"""))
        assertEquals(Relay.Health.Ok("dev"), Relay.health(200, """{ "ok" : true, "auth" : "dev" }"""))
        assertEquals(Relay.Health.Ok(null), Relay.health(200, """{"ok":true}"""))
        assertEquals(Relay.Health.NotRelay, Relay.health(401, """{"error":"unauthenticated"}"""))
        assertEquals(Relay.Health.NotRelay, Relay.health(200, "<html>hello</html>"))
    }

    @Test
    fun clientIdOfAnAuthorizeUrl() {
        val url = Endpoints.authorizeUrl("client_01ABC", "zeron://callback", "s")
        assertEquals("client_01ABC", Endpoints.clientIdOf(url))
    }
}
