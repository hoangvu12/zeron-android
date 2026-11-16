package sh.zeron.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.zeron.android.session.SessionChrome.EmptyNotice
import uniffi.zeron_core.TranscriptLoad

/**
 * The empty-transcript notice (docs/design/relay-mobile.md "Show the new
 * state"; upstream Android `SessionNoticesTest`, iOS `CoreSessionSource`):
 * the desktop's words, a spinner only while this app is doing something,
 * the chrome's mapping from `transcriptLoad`, and the loader hold it ends.
 */
class EmptyNoticeTest {

    @Test
    fun aChatWithSomethingToShowSaysNothing() {
        assertNull(EmptyNotice.of(TranscriptLoad.READY))
        assertNull(baseChrome(composerState(), null).emptyNotice)
    }

    @Test
    fun theEmptyStatesUseTheDesktopsWords() {
        assertEquals("Syncing…", EmptyNotice.of(TranscriptLoad.SYNCING)?.text)
        assertEquals("Reconnecting…", EmptyNotice.of(TranscriptLoad.RECONNECTING)?.text)
        assertEquals("Offline — this chat loads when you're back online", EmptyNotice.of(TranscriptLoad.OFFLINE)?.text)
        assertEquals(
            "This chat lives on another device — it loads when that device is back online",
            EmptyNotice.of(TranscriptLoad.HOST_AWAY)?.text,
        )
    }

    @Test
    fun onlyStatesWhereSomethingIsHappeningGetASpinner() {
        assertTrue(EmptyNotice.Syncing.isWorking)
        assertTrue(EmptyNotice.Reconnecting.isWorking)
        assertFalse(EmptyNotice.Offline.isWorking)
        assertFalse(EmptyNotice.HostAway.isWorking)
    }

    @Test
    fun theChromeCarriesTheNotice() {
        val chrome = baseChrome(composerState().copy(transcriptLoad = TranscriptLoad.HOST_AWAY), null)
        assertEquals(EmptyNotice.HostAway, chrome.emptyNotice)
    }

    @Test
    fun aNoticeEndsTheLoadersHold() {
        val reveal = OpenReveal()
        reveal.begin(hold = true)
        reveal.onLanded()
        assertEquals(OpenReveal.Phase.Holding, reveal.phase)
        reveal.onEmptyNotice()
        assertEquals(OpenReveal.Phase.Revealing, reveal.phase)
        // Like the give-up: no OPEN probe for a screen with no rows.
        assertFalse(reveal.onFirstRows())
    }

    @Test
    fun aNoticeBeforeThePushLandsWaitsForIt() {
        val reveal = OpenReveal()
        reveal.begin(hold = true)
        reveal.onEmptyNotice()
        assertEquals(OpenReveal.Phase.Holding, reveal.phase)
        reveal.onLanded()
        assertEquals(OpenReveal.Phase.Revealing, reveal.phase)
    }

    @Test
    fun aNoticeAfterTheRevealChangesNothing() {
        val reveal = OpenReveal()
        reveal.begin(hold = false)
        reveal.onEmptyNotice()
        assertEquals(OpenReveal.Phase.Idle, reveal.phase)
        // The first rows still report the open.
        assertTrue(reveal.onFirstRows())
    }
}
