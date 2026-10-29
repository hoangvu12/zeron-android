package sh.zeron.android.voice

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.glass.MenuIcon
import sh.zeron.android.design.glass.MenuItem
import uniffi.zeron_core.VoiceCallPhase
import uniffi.zeron_core.VoiceCallWork
import uniffi.zeron_core.VoiceOrb
import uniffi.zeron_core.VoiceSpeaker

/** #63: G31's scripted call and the stage's audio-route menu. Plain JUnit. */
class VoicePreviewAndRoutesTest {

    @Test
    fun thePreviewWalksFourTurnsOfFourSecondsAndStreamsTheCaption() {
        val first = VoicePreviewScript.state(0, muted = false)
        assertEquals(VoiceCallPhase.ACTIVE, first.phase)
        assertEquals(VoiceOrb.LISTENING, first.orb)
        assertEquals(VoiceSpeaker.USER, first.captionSpeaker)
        assertEquals("Open", first.caption)
        assertEquals(VoicePreviewScript.CHAT_ID, first.chatId)
        assertEquals("preview-0", first.captionItem)
        assertEquals(0f, first.speaker, 0f)

        // About three words a second: 1 + step/3 words.
        assertEquals("Open a chat on", VoicePreviewScript.state(9, muted = false).caption)

        val speaking = VoicePreviewScript.state(40, muted = false)
        assertEquals(VoiceOrb.SPEAKING, speaking.orb)
        assertTrue(speaking.speaking)
        assertEquals(0f, speaking.microphone, 0f)

        val working = VoicePreviewScript.state(80, muted = true)
        assertEquals(VoiceCallWork.WORKING, working.work)
        assertTrue(working.muted)

        assertEquals(VoiceCallWork.AWAITING_INPUT, VoicePreviewScript.state(120, false).work)
        // And round again.
        assertEquals(VoiceOrb.LISTENING, VoicePreviewScript.state(160, false).orb)
    }

    @Test
    fun routeNamesUseTheRoleTheProductOrTheKind() {
        assertEquals("Phone", VoiceAudioRoute.name(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "Pixel 9", "Pixel 9"))
        assertEquals("Speaker", VoiceAudioRoute.name(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Pixel 9", "Pixel 9"))
        assertEquals("Pixel Buds Pro", VoiceAudioRoute.name(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, " Pixel Buds Pro ", "Pixel 9"))
        assertEquals("Bluetooth", VoiceAudioRoute.name(AudioDeviceInfo.TYPE_BLE_HEADSET, "", "Pixel 9"))
        // A wired jack reports the phone's own model.
        assertEquals("Headphones", VoiceAudioRoute.name(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "Pixel 9", "Pixel 9"))
        assertEquals("USB headset", VoiceAudioRoute.name(AudioDeviceInfo.TYPE_USB_HEADSET, null, "Pixel 9"))
        assertEquals("Hearing aid", VoiceAudioRoute.name(AudioDeviceInfo.TYPE_HEARING_AID, null, "Pixel 9"))
    }

    @Test
    fun theRouteMenuListsEveryOutputAndChecksTheOnePlaying() {
        val routes = listOf(
            VoiceAudioRoute(1, "Phone", VoiceRoute.Earpiece, current = false),
            VoiceAudioRoute(2, "Speaker", VoiceRoute.Speaker, current = false),
            VoiceAudioRoute(7, "Pixel Buds Pro", VoiceRoute.External, current = true),
        )
        val picked = mutableListOf<Int>()
        val menu = voiceRouteMenu(routes) { picked += it.id }
        assertEquals(VoiceStageCopy.AudioOutput, menu.title)
        val items = menu.children.map { it as MenuItem }
        assertEquals(listOf("Phone", "Speaker", "Pixel Buds Pro"), items.map { it.title })
        assertEquals(listOf(false, false, true), items.map { it.checked })
        assertEquals(ZeronIcons.Ear, (items[2].icon as MenuIcon.Symbol).icon)
        assertFalse(items[0].disabled)
        items[1].action()
        assertEquals(listOf(2), picked)
    }
}
