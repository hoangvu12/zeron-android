package sh.zeron.android.pairing

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one Robolectric pin of [PairingLinks.encodedFragment]
 * (android-ui-parity #139): it must agree with
 * `android.net.Uri.parse(...).getEncodedFragment()` on every deep-link
 * table input — the hand-written `substring(indexOf('#') + 1)` is what
 * keeps `PairingLinks` plain-JVM (no `android.*` import, so
 * [PairingLinksTest] runs without Robolectric).
 *
 * Note the `Uri` side is the **encoded** fragment (`getEncodedFragment`
 * / Kotlin `encodedFragment`): `getFragment()` would decode, and the
 * ticket's `#url=` value must be decoded exactly once, later.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PairingLinksUriTest {

    @Test
    fun encodedFragmentMatchesAndroidUriForEveryDeepLinkTableInput() {
        val inputs = TABLE_LINKS
        inputs.forEachIndexed { index, link ->
            // The failure message names the row, never the link: a
            // pairing link carries a token.
            assertEquals(
                "encodedFragment divergence at row $index",
                Uri.parse(link).encodedFragment,
                PairingLinks.encodedFragment(link),
            )
        }
    }

    @Test
    fun encodedFragmentIsNullWithoutAHashAndEmptyForABareOne() {
        assertEquals(null, Uri.parse("zeron://pair").encodedFragment)
        assertEquals(null, PairingLinks.encodedFragment("zeron://pair"))
        assertEquals("", Uri.parse("zeron://pair#").encodedFragment)
        assertEquals("", PairingLinks.encodedFragment("zeron://pair#"))
    }

    private companion object {
        /** Every input of the ticket's deep-link table, both result kinds. */
        val TABLE_LINKS = listOf(
            "zeron://pair#url=http%3A%2F%2F192.168.1.10%3A27655%2Fpair%23token%3DAbC-123_x",
            "ZERON://Pair#url=https%3A%2F%2Fe.example.ts.net%2Fpair%23token%3Dz",
            "zeron://pair#url=http://h:1/pair#token=x",
            "zeron://pair#v=2&url=http%3A%2F%2Fh%2Fpair%23token%3Dx",
            "zeron://pair#url=a&url=b",
            "zeron://pair#url=a+b",
            "zeron://pair#url=%20http%3A%2F%2Fh%2Fpair%23token%3Dx%0A",
            "zeron://pair#url=http%253A%252F%252Fh%252Fpair%2523token%253Dx",
            "zeron://pair",
            "zeron://pair#",
            "zeron://pair#url=",
            "zeron://pair#URL=x",
            "zeron://pair?url=x",
            "zeron://pair#url=%GG",
            "zeron://pair#url=%2",
            "zeron://pair#url=%20%0A",
            "zeron://pair#url=http%3A%2F%2Fh%2Fpa%0Air%23token%3Dx",
        )
    }
}
