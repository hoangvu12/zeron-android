package sh.zeron.android.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.zeron_core.DeviceView
import uniffi.zeron_core.VoiceCallPhase
import uniffi.zeron_core.VoiceCallState
import uniffi.zeron_core.VoiceCallWork
import uniffi.zeron_core.VoiceEndReason
import uniffi.zeron_core.VoiceOrb

/**
 * The voice call's pure model (#60): iOS `RemoteVoiceController`'s copy,
 * clock and host rules, and the call notification's status word (the Live
 * Activity's). Plain JUnit: uniffi records are plain Kotlin.
 */
class VoiceModelTest {

    private val cap = "voice-client-media-v1"

    private fun device(
        id: String,
        online: Boolean = true,
        host: Boolean = true,
        capabilities: List<String> = listOf(cap),
    ) = DeviceView(
        id = id,
        name = id,
        platform = "macos",
        online = online,
        lastSeenMs = null,
        version = null,
        capabilities = capabilities,
        isExecutionHost = host,
        isSelf = false,
        sessionCount = 0u,
    )

    private fun state(
        phase: VoiceCallPhase = VoiceCallPhase.ACTIVE,
        orb: VoiceOrb = VoiceOrb.LISTENING,
        work: VoiceCallWork = VoiceCallWork.IDLE,
    ) = VoiceCallState(
        phase = phase,
        orb = orb,
        chatId = "chat-1",
        work = work,
        muted = false,
        speaking = false,
        caption = "",
        captionSpeaker = null,
        captionItem = null,
        microphone = 0f,
        speaker = 0f,
        voices = emptyList(),
    )

    // ------------------------------------------------------------- status

    @Test
    fun statusTextFollowsIos() {
        assertEquals("Codex voice", VoiceSnapshot().statusText)
        assertEquals("Calling Fedora…", VoiceSnapshot(live = true, hostName = "Fedora").statusText)
        val live = VoiceSnapshot(live = true, hostName = "Fedora")
        assertEquals("Connecting to Fedora…", live.copy(state = state(phase = VoiceCallPhase.CONNECTING)).statusText)
        assertEquals("Ending…", live.copy(state = state(phase = VoiceCallPhase.ENDING)).statusText)
        assertEquals("Speaking", live.copy(state = state(orb = VoiceOrb.SPEAKING)).statusText)
        assertEquals("Codex needs your answer", live.copy(state = state(orb = VoiceOrb.AWAITING_INPUT)).statusText)
        assertEquals("Working on it…", live.copy(state = state(orb = VoiceOrb.WORKING)).statusText)
        assertEquals("Muted", live.copy(state = state(orb = VoiceOrb.MUTED)).statusText)
        assertEquals("Listening", live.copy(state = state(orb = VoiceOrb.LISTENING)).statusText)
        assertEquals("Listening", live.copy(state = state(orb = VoiceOrb.IDLE)).statusText)
    }

    @Test
    fun activityStatusIsTheLiveActivitysWord() {
        val live = VoiceSnapshot(live = true, hostName = "Fedora")
        // Anything not yet active is connecting.
        assertEquals("Connecting…", live.activityStatus)
        assertEquals("Connecting…", live.copy(state = state(phase = VoiceCallPhase.CONNECTING, orb = VoiceOrb.SPEAKING)).activityStatus)
        assertEquals("Connecting…", live.copy(state = state(phase = VoiceCallPhase.ENDING)).activityStatus)
        assertEquals("Speaking", live.copy(state = state(orb = VoiceOrb.SPEAKING)).activityStatus)
        assertEquals("Working on it…", live.copy(state = state(orb = VoiceOrb.WORKING)).activityStatus)
        assertEquals("Codex needs your answer", live.copy(state = state(orb = VoiceOrb.AWAITING_INPUT)).activityStatus)
        assertEquals("Listening", live.copy(state = state(orb = VoiceOrb.LISTENING)).activityStatus)
        assertEquals("Muted", live.copy(state = state(orb = VoiceOrb.MUTED)).activityStatus)
        assertEquals("Muted", live.copy(state = state(orb = VoiceOrb.IDLE)).activityStatus)
    }

    @Test
    fun orbAndFlags() {
        assertEquals(VoiceOrb.IDLE, VoiceSnapshot().orb)
        assertEquals(VoiceOrb.CONNECTING, VoiceSnapshot(live = true).orb)
        val s = VoiceSnapshot(live = true, state = state(orb = VoiceOrb.WORKING, work = VoiceCallWork.AWAITING_INPUT))
        assertEquals(VoiceOrb.WORKING, s.orb)
        assertTrue(s.active)
        assertTrue(s.awaitingInput)
        assertEquals("chat-1", s.chatId)
        assertFalse(VoiceSnapshot(live = true, state = state(phase = VoiceCallPhase.CONNECTING)).active)
    }

    // -------------------------------------------------------------- clock

    @Test
    fun elapsedTextIsMinutesThenHours() {
        assertNull(VoiceSnapshot().elapsedText(now = 10_000))
        val s = VoiceSnapshot(activeSince = 1_000)
        assertEquals("0:00", s.elapsedText(now = 1_999))
        assertEquals("1:23", s.elapsedText(now = 1_000 + 83_000))
        assertEquals("59:59", s.elapsedText(now = 1_000 + 3_599_000))
        assertEquals("1:00:00", s.elapsedText(now = 1_000 + 3_600_000))
        assertEquals("2:03:04", s.elapsedText(now = 1_000 + (2 * 3600 + 3 * 60 + 4) * 1000L))
        // A clock that steps back never shows negative time.
        assertEquals("0:00", s.elapsedText(now = 0))
    }

    // --------------------------------------------------------------- copy

    @Test
    fun endReasonCopyIsVerbatim() {
        val host = "Fedora"
        assertEquals("Zeron needs microphone access to talk with Codex.", VoiceCopy.message(VoiceEndReason.MICROPHONE_DENIED, host))
        assertEquals("The audio connection closed. Start a new call to reconnect.", VoiceCopy.message(VoiceEndReason.AUDIO_UNAVAILABLE, host))
        assertEquals("Sign in to Codex with ChatGPT on Fedora.", VoiceCopy.message(VoiceEndReason.SIGN_IN_REQUIRED, host))
        assertEquals("Codex usage is currently unavailable on Fedora.", VoiceCopy.message(VoiceEndReason.USAGE_UNAVAILABLE, host))
        assertEquals("Fedora is already on a voice call.", VoiceCopy.message(VoiceEndReason.BUSY, host))
        assertEquals("Fedora isn't reachable right now.", VoiceCopy.message(VoiceEndReason.HOST_UNAVAILABLE, host))
        assertEquals("Update Zeron on Fedora and enable remote voice there.", VoiceCopy.message(VoiceEndReason.HOST_INCOMPATIBLE, host))
        assertEquals("The call dropped. Start a new one to reconnect.", VoiceCopy.message(VoiceEndReason.CONNECTION_LOST, host))
    }

    @Test
    fun anUnnamedHostIsTheSelectedDevice() {
        assertEquals("the selected device is already on a voice call.", VoiceSnapshot().message(VoiceEndReason.BUSY))
        assertEquals("Fedora is already on a voice call.", VoiceSnapshot(hostName = "Fedora").message(VoiceEndReason.BUSY))
    }

    @Test
    fun noticeActionAndNotificationCopy() {
        assertEquals("Settings", VoiceNotice(VoiceEndReason.MICROPHONE_DENIED, "m").action)
        for (reason in VoiceEndReason.entries.filter { it != VoiceEndReason.MICROPHONE_DENIED }) {
            assertEquals("Retry", VoiceNotice(reason, "m").action)
        }
        assertEquals("Codex · Fedora", VoiceCopy.notificationTitle("Fedora"))
        assertEquals("Codex · your computer", VoiceCopy.notificationTitle(""))
        assertEquals("Mute", VoiceCopy.muteAction(muted = false))
        assertEquals("Unmute", VoiceCopy.muteAction(muted = true))
    }

    // -------------------------------------------------------------- hosts

    @Test
    fun hostsAreOnlineCompatibleExecutionHosts() {
        val devices = listOf(
            device("mac"),
            device("offline", online = false),
            device("phone", host = false),
            device("old", capabilities = emptyList()),
        )
        assertEquals(listOf("mac"), VoiceHosts.hosts(devices, cap, forced = false).map { it.id })
        // The dev override also offers execution hosts without the capability.
        assertEquals(listOf("mac", "old"), VoiceHosts.hosts(devices, cap, forced = true).map { it.id })
    }

    @Test
    fun voiceIsAvailableOnceAnyHostAdvertisesIt() {
        assertTrue(VoiceHosts.available(listOf(device("mac", online = false)), cap, forced = false))
        assertFalse(VoiceHosts.available(listOf(device("old", capabilities = emptyList())), cap, forced = false))
        assertFalse(VoiceHosts.available(listOf(device("phone", host = false)), cap, forced = false))
        assertFalse(VoiceHosts.available(emptyList(), cap, forced = false))
        assertTrue(VoiceHosts.available(emptyList(), cap, forced = true))
    }

    @Test
    fun startHostIsTheRememberedOneOrTheOnlyOne() {
        val a = device("a")
        val b = device("b")
        assertEquals("b", VoiceHosts.startHost(listOf(a, b), selected = "b")?.id)
        // Two hosts and no (or a gone) choice: the picker decides.
        assertNull(VoiceHosts.startHost(listOf(a, b), selected = null))
        assertNull(VoiceHosts.startHost(listOf(a, b), selected = "gone"))
        // One host: it, whatever was remembered.
        assertEquals("a", VoiceHosts.startHost(listOf(a), selected = null)?.id)
        assertEquals("a", VoiceHosts.startHost(listOf(a), selected = "gone")?.id)
        assertNull(VoiceHosts.startHost(emptyList(), selected = "a"))
    }

    @Test
    fun preferencesArePerUserAndOrganization() {
        assertEquals("remoteVoice.u1.o1", VoiceHosts.preferenceKey("u1", "o1"))
        assertEquals("remoteVoice.none.none", VoiceHosts.preferenceKey(null, null))
    }
}
