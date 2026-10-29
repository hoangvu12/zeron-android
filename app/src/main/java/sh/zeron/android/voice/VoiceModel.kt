package sh.zeron.android.voice

import java.util.Locale
import kotlin.math.max
import uniffi.zeron_core.DeviceView
import uniffi.zeron_core.VoiceCallPhase
import uniffi.zeron_core.VoiceCallState
import uniffi.zeron_core.VoiceCallWork
import uniffi.zeron_core.VoiceEndReason
import uniffi.zeron_core.VoiceOrb

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
