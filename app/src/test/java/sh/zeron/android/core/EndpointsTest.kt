package sh.zeron.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The debug endpoint overrides' pure parts (#18): stickiness and the authorize URL. */
class EndpointsTest {

    @Test
    fun prodForgetsAnythingElseReplacesNullLeaves() {
        assertNull(Endpoints.sticky(null))
        assertEquals(Endpoints.Sticky.Clear, Endpoints.sticky("prod"))
        assertEquals(Endpoints.Sticky.Set("http://10.0.2.2:8787"), Endpoints.sticky("http://10.0.2.2:8787"))
    }

    @Test
    fun authorizeUrlMatchesTheCoresShape() {
        // `zeron/crates/client/src/auth.rs` `workos_authorize_url`, with
        // another client id.
        assertEquals(
            "https://api.workos.com/user_management/authorize?response_type=code" +
                "&client_id=client_staging" +
                "&redirect_uri=zeron%3A%2F%2Fcallback" +
                "&provider=authkit" +
                "&state=4f1c-9a_~.x",
            Endpoints.authorizeUrl("client_staging", "zeron://callback", "4f1c-9a_~.x"),
        )
    }

    @Test
    fun authorizeUrlEncodesUtf8AsUpperHex() {
        val url = Endpoints.authorizeUrl("c d", "zeron://callback", "é&")
        assertEquals(true, url.contains("client_id=c%20d&"))
        assertEquals(true, url.endsWith("state=%C3%A9%26"))
    }
}
