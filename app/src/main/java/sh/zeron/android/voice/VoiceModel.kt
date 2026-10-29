package sh.zeron.android.voice

import java.util.Locale
import kotlin.math.max
import uniffi.zeron_core.DeviceView
import uniffi.zeron_core.VoiceCallPhase
import uniffi.zeron_core.VoiceCallState
import uniffi.zeron_core.VoiceCallWork
import uniffi.zeron_core.VoiceEndReason
import uniffi.zeron_core.VoiceOrb
import uniffi.zeron_core.VoiceSpeaker

/** Where the call's audio plays right now. */
enum class VoiceRoute { Speaker, Earpiece, External }

/**
 * One immutable picture of the voice call (iOS `RemoteVoiceController`'s
 * published state), for Compose (#61) and the call notification. Pure:
 * the clock is passed in, so the copy is plain-JVM-testable.
 */
data class VoiceSnapshot(
    /** A call is placed. */
    val live: Boolean = false,
    val state: VoiceCallState? = null,
    /** Why the last call ended on its own; cleared by the next start. */
    val endReason: VoiceEndReason? = null,
    val muted: Boolean = false,
    val hostName: String = "",
    /** `SystemClock.elapsedRealtime()` when the call went live. */
    val activeSince: Long? = null,
    /** Wall clock of the same moment (the notification's chronometer). */
    val activeSinceWall: Long? = null,
    /** Styles the last host offered. */
    val styles: List<String> = emptyList(),
    /** Loudspeaker while in hand (the default); off plays the earpiece. */
    val speakerOn: Boolean = true,
    val route: VoiceRoute = VoiceRoute.Speaker,
) {
    val active: Boolean get() = state?.phase == VoiceCallPhase.ACTIVE
    val orb: VoiceOrb get() = state?.orb ?: if (live) VoiceOrb.CONNECTING else VoiceOrb.IDLE
    val awaitingInput: Boolean get() = state?.work == VoiceCallWork.AWAITING_INPUT

    /** The host's orchestrator chat (the stage's transcript button, #61). */
    val chatId: String? get() = state?.chatId

    /** `m:ss`, or `h:mm:ss` past the hour (iOS `elapsedText`, desktop `format_elapsed`). */
    fun elapsedText(now: Long): String? {
        val since = activeSince ?: return null
        val seconds = max(0L, (now - since) / 1000)
        val h = seconds / 3600
        val m = seconds / 60 % 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** One line for what the call is doing now (iOS `statusText`). */
    val statusText: String
        get() {
            val state = state ?: return if (live) "Calling $hostName…" else "Codex voice"
            if (state.phase == VoiceCallPhase.CONNECTING) return "Connecting to $hostName…"
            if (state.phase == VoiceCallPhase.ENDING) return "Ending…"
            return when (state.orb) {
                VoiceOrb.SPEAKING -> "Speaking"
                VoiceOrb.AWAITING_INPUT -> "Codex needs your answer"
                VoiceOrb.WORKING -> "Working on it…"
                VoiceOrb.MUTED -> "Muted"
                else -> "Listening"
            }
        }

    /**
     * The call notification's status word — the Live Activity's
     * (`VoiceActivityController.orb` → `VoiceActivityOrb.title`): anything
     * not yet active reads "Connecting…".
     */
    val activityStatus: String
        get() {
            val state = state
            if (state == null || state.phase != VoiceCallPhase.ACTIVE) return "Connecting…"
            return when (state.orb) {
                VoiceOrb.SPEAKING -> "Speaking"
                VoiceOrb.WORKING -> "Working on it…"
                VoiceOrb.AWAITING_INPUT -> "Codex needs your answer"
                VoiceOrb.LISTENING -> "Listening"
                VoiceOrb.CONNECTING -> "Connecting…"
                VoiceOrb.MUTED, VoiceOrb.IDLE -> "Muted"
            }
        }

    fun message(reason: VoiceEndReason): String =
        VoiceCopy.message(reason, hostName.ifEmpty { "the selected device" })
}

/** A call that failed while the stage wasn't showing: a toast explains it (iOS `Toast.show`). */
data class VoiceNotice(val reason: VoiceEndReason, val message: String) {
    /** **Settings** for the microphone, **Retry** otherwise. */
    val action: String get() = if (reason == VoiceEndReason.MICROPHONE_DENIED) "Settings" else "Retry"
}

/** Copy, verbatim from iOS `RemoteVoiceController.swift:246-257`. */
object VoiceCopy {
    fun message(reason: VoiceEndReason, host: String): String = when (reason) {
        VoiceEndReason.MICROPHONE_DENIED -> "Zeron needs microphone access to talk with Codex."
        VoiceEndReason.AUDIO_UNAVAILABLE -> "The audio connection closed. Start a new call to reconnect."
        VoiceEndReason.SIGN_IN_REQUIRED -> "Sign in to Codex with ChatGPT on $host."
        VoiceEndReason.USAGE_UNAVAILABLE -> "Codex usage is currently unavailable on $host."
        VoiceEndReason.BUSY -> "$host is already on a voice call."
        VoiceEndReason.HOST_UNAVAILABLE -> "$host isn't reachable right now."
        VoiceEndReason.HOST_INCOMPATIBLE -> "Update Zeron on $host and enable remote voice there."
        VoiceEndReason.CONNECTION_LOST -> "The call dropped. Start a new one to reconnect."
    }

    /** The call notification's title (the Lock Screen activity's "Codex · host"). */
    fun notificationTitle(host: String): String = "Codex · ${host.ifEmpty { "your computer" }}"

    /** The Mute action (the activity's labelled control). */
    fun muteAction(muted: Boolean): String = if (muted) "Unmute" else "Mute"
}

/**
 * Which hosts can take a call (iOS `RemoteVoiceController` "Hosts and
 * preferences"). Pure over the device list; [capability] is the core's
 * `voiceHostCapability()`.
 */
object VoiceHosts {

    /** A registered execution host advertising client-media voice. */
    fun compatible(device: DeviceView, capability: String): Boolean =
        device.isExecutionHost && capability in device.capabilities

    /** [compatible], or any execution host under the dev override. */
    fun callable(device: DeviceView, capability: String, forced: Boolean): Boolean =
        compatible(device, capability) || (forced && device.isExecutionHost)

    /** Hosts that can take a call right now. */
    fun hosts(devices: List<DeviceView>, capability: String, forced: Boolean): List<DeviceView> =
        devices.filter { it.online && callable(it, capability, forced) }

    /** Voice is offered once any registered host can run it (or the dev override is on). */
    fun available(devices: List<DeviceView>, capability: String, forced: Boolean): Boolean =
        forced || devices.any { compatible(it, capability) }

    /** The host a plain tap calls: the remembered one if it can take a call, else the only one. */
    fun startHost(hosts: List<DeviceView>, selected: String?): DeviceView? {
        if (selected != null) hosts.firstOrNull { it.id == selected }?.let { return it }
        return hosts.singleOrNull()
    }

    /** The remembered host and style belong to one user in one organization. */
    fun preferenceKey(userId: String?, orgId: String?): String =
        "remoteVoice.${userId ?: "none"}.${orgId ?: "none"}"
}

/**
 * G31's script (iOS `RemoteVoiceController.preview`): four turns of 4 s
 * each, ticked every 100 ms — the caption streams in about three words a
 * second and the active side's level follows a sine. Pure.
 */
internal object VoicePreviewScript {
    const val CHAT_ID = "voice-orchestrator-preview"

    private data class Turn(val orb: VoiceOrb, val work: VoiceCallWork, val caption: String, val speaker: VoiceSpeaker?)

    private val turns = listOf(
        Turn(VoiceOrb.LISTENING, VoiceCallWork.IDLE, "Open a chat on the Mac and ask Claude to fix the failing sync test", VoiceSpeaker.USER),
        Turn(VoiceOrb.SPEAKING, VoiceCallWork.IDLE, "On it. I'm starting a Claude session in the comet project to look at the sync test.", null),
        Turn(VoiceOrb.WORKING, VoiceCallWork.WORKING, "On it. I'm starting a Claude session in the comet project to look at the sync test.", VoiceSpeaker.ASSISTANT),
        Turn(VoiceOrb.AWAITING_INPUT, VoiceCallWork.AWAITING_INPUT, "Claude wants to know whether to update the fixture or the parser.", VoiceSpeaker.ASSISTANT),
    )

    fun state(step: Int, muted: Boolean): VoiceCallState {
        val index = (step / 40) % turns.size
        val turn = turns[index]
        val wave = kotlin.math.abs(kotlin.math.sin(step / 2.3)).toFloat()
        val words = turn.caption.split(" ")
        val streamed = words.take(1 + (step % 40) / 3).joinToString(" ")
        return VoiceCallState(
            phase = VoiceCallPhase.ACTIVE,
            orb = turn.orb,
            chatId = CHAT_ID,
            work = turn.work,
            muted = muted,
            speaking = turn.orb == VoiceOrb.SPEAKING,
            caption = streamed,
            captionSpeaker = turn.speaker,
            captionItem = "preview-$index",
            microphone = if (turn.orb == VoiceOrb.LISTENING) wave else 0f,
            speaker = if (turn.orb == VoiceOrb.SPEAKING) wave else 0f,
            voices = emptyList(),
        )
    }
}

/** One output in the stage's audio-route menu (#63). */
data class VoiceAudioRoute(val id: Int, val name: String, val kind: VoiceRoute, val current: Boolean) {
    companion object {
        /** The menu label: the built-ins by role, a device by its product name, else its kind. Pure. */
        fun name(type: Int, productName: String?, model: String? = android.os.Build.MODEL): String = when (type) {
            android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone"
            android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            // A wired jack reports the phone's own model as its product name.
            else -> productName?.trim()?.takeIf { it.isNotEmpty() && it != model } ?: when (type) {
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"
                android.media.AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
                else -> "Headphones"
            }
        }
    }
}
