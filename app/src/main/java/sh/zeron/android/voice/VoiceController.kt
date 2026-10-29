package sh.zeron.android.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import sh.zeron.android.R
import sh.zeron.android.design.Haptic
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.DeviceView
import uniffi.zeron_core.VoiceCall
import uniffi.zeron_core.VoiceCallPhase
import uniffi.zeron_core.VoiceCallState
import uniffi.zeron_core.VoiceEndReason
import uniffi.zeron_core.VoiceSessionListener
import uniffi.zeron_core.defaultVoiceStyles
import uniffi.zeron_core.voiceHostCapability

/**
 * The app-wide voice orchestrator call (#60, iOS `RemoteVoiceController`;
 * wiring lifted from Zeron Android's `voice/VoiceController.kt`). Audio
 * runs on this phone; Codex, its credentials and the orchestrator chat run
 * on a registered execution host (any device advertising the core's
 * `voiceHostCapability()`). One call at a time, no automatic reconnect: a
 * dropped call explains itself and waits for a tap.
 *
 * Main-thread only. Owned by `ZeronApplication` (`voice`); the UI (#61)
 * reads [snapshot] and calls [open] / [start] / [toggleMute] / [stop].
 */
class VoiceController(
    context: Context,
    private val client: StateFlow<CoreClient?>,
    /** The workspace's registered devices — in memory, main-safe. */
    val devices: StateFlow<List<DeviceView>>,
    scope: CoroutineScope,
) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("voice", Context.MODE_PRIVATE)
    private val sounds by lazy { CallSounds(this.context) }

    private var call: VoiceCall? = null
    private var media: VoiceMedia? = null
    private var generation = 0L
    private var stageViews = 0
    private var proximity: Proximity? = null

    /**
     * The haptic engine is composition-scoped (`LocalFeedback`):
     * [VoiceHost] installs it here while the app's root is composed.
     */
    var haptic: (Haptic) -> Unit = {}

    /** The toast for a failure while the stage isn't showing; [VoiceHost] installs it. */
    var onNotice: (VoiceNotice) -> Unit = {}

    /** Development override (`--ez remote-voice true`, iOS `-remote-voice`): offer voice before any host advertises it. */
    var forced: Boolean = false

    private val _snapshot = MutableStateFlow(VoiceSnapshot(styles = runCatching { defaultVoiceStyles() }.getOrDefault(emptyList())))
    val snapshot: StateFlow<VoiceSnapshot> = _snapshot.asStateFlow()
    val live: Boolean get() = _snapshot.value.live

    private val _selectedHost = MutableStateFlow<String?>(null)

    /** The remembered host (per user and organization). */
    val selectedHost: StateFlow<String?> = _selectedHost.asStateFlow()
    private val _selectedStyle = MutableStateFlow<String?>(null)

    /** The remembered voice style; null is the host's default. */
    val selectedStyle: StateFlow<String?> = _selectedStyle.asStateFlow()

    private val _stageRequests = Channel<Unit>(Channel.CONFLATED)

    /**
     * Navigate to the call stage (the call notification's tap,
     * `zeron://voice`, a Retry). Held until the stage's host collects it.
     * #61's [VoiceStageHost] collects it.
     */
    val stageRequests: Flow<Unit> = _stageRequests.receiveAsFlow()

    private val _permissionRequest = MutableStateFlow<CompletableDeferred<Boolean>?>(null)

    /** A pending microphone prompt for [VoiceHost] to show. */
    val permissionRequest: StateFlow<CompletableDeferred<Boolean>?> = _permissionRequest.asStateFlow()

    init {
        // Remembered host and style belong to one user in one organization.
        scope.launch { client.collect { loadPreferences() } }
    }

    // ── Hosts and preferences ──────────────────────────────────────────────

    private val capability: String by lazy { runCatching { voiceHostCapability() }.getOrDefault("") }

    /** Registered hosts that can take a call right now. */
    fun hosts(): List<DeviceView> = VoiceHosts.hosts(devices.value, capability, forced)

    /** Whether [device] can take a call (compatible, or any execution host under [forced]). */
    fun callable(device: DeviceView): Boolean = VoiceHosts.callable(device, capability, forced)

    /** Voice controls appear once any registered host can run them (or a call is live). */
    fun availableAmong(devices: List<DeviceView>): Boolean = live || VoiceHosts.available(devices, capability, forced)

    /** The host a plain tap calls: the chosen one, or the only one online. */
    fun startHost(): DeviceView? = VoiceHosts.startHost(hosts(), _selectedHost.value)

    private val preferenceKey: String
        get() {
            val c = client.value
            return VoiceHosts.preferenceKey(runCatching { c?.userId() }.getOrNull(), runCatching { c?.orgId() }.getOrNull())
        }

    private fun loadPreferences() {
        val key = preferenceKey
        _selectedHost.value = prefs.getString("$key.host", null)
        _selectedStyle.value = prefs.getString("$key.style", null)
    }

    /** Remember the host for the next call (not while one runs). */
    fun selectHost(id: String?) {
        if (live) return
        prefs.edit().putString("$preferenceKey.host", id).apply()
        _selectedHost.value = id
    }

    fun selectStyle(style: String?) {
        prefs.edit().putString("$preferenceKey.style", style).apply()
        _selectedStyle.value = style
    }

    // ── Call ───────────────────────────────────────────────────────────────

    /**
     * Opens the live call, or starts one on the default host. False when
     * there is no host to call; the caller offers the picker (iOS `openVoice`).
     */
    fun open(): Boolean = live || start()

    /**
     * Calls [host] (remembered for next time) or the default host. False
     * when there is no host to call; the caller offers the picker.
     */
    fun start(host: DeviceView? = null): Boolean {
        if (live) return false
        val client = client.value ?: return false
        val device = host ?: startHost() ?: return false
        if (host != null) selectHost(device.id)
        generation++
        val generation = generation
        val previous = _snapshot.value
        val media = VoiceMedia(context, this, speakerOn = previous.speakerOn)
        media.onFailure = { if (this.generation == generation) finish(VoiceEndReason.AUDIO_UNAVAILABLE) }
        media.onRoute = { route -> if (this.generation == generation) _snapshot.update { it.copy(route = route) } }
        val call = try {
            client.startVoice(device.id, _selectedStyle.value, media, Bridge(this, generation))
        } catch (e: Exception) {
            Log.w(TAG, "startVoice failed", e)
            media.close()
            return false
        }
        media.attach(call)
        this.call = call
        this.media = media
        _snapshot.value = VoiceSnapshot(
            live = true,
            hostName = device.name,
            styles = previous.styles,
            speakerOn = previous.speakerOn,
            route = if (previous.speakerOn) VoiceRoute.Speaker else VoiceRoute.Earpiece,
        )
        haptic(Haptic.Confirm)
        // The call sounds as it is placed, well before the microphone opens.
        sounds.play(CallSounds.Sound.Start)
        return true
    }

    /** Mute: the local track at once, then the core (iOS `toggleMute`). */
    fun toggleMute() {
        val s = _snapshot.value
        if (!s.live) return
        val muted = !s.muted
        media?.muteLocally(muted)
        call?.let { c -> runCatching { c.setMuted(muted) } }
        haptic(Haptic.Light)
        _snapshot.update { it.copy(muted = muted) }
        VoiceCallService.refresh()
    }

    /** Loudspeaker ↔ earpiece. A headset or car always wins while connected. */
    fun toggleSpeaker() {
        val on = !_snapshot.value.speakerOn
        media?.setSpeaker(on)
        _snapshot.update {
            it.copy(
                speakerOn = on,
                route = if (it.route == VoiceRoute.External && media != null) it.route else if (on) VoiceRoute.Speaker else VoiceRoute.Earpiece,
            )
        }
    }

    // ── G31: the debug preview ─────────────────────────────────────────────

    private var preview: Runnable? = null

    /**
     * The debug `voice-preview` extra (iOS `-voice-preview`, G31): a scripted
     * call for screenshots and UI work — the orb walks through listening,
     * speaking, working and waiting with synthetic levels while the caption
     * streams in. No audio, host or relay is involved; End stops it.
     */
    fun preview() {
        if (live) return
        generation++
        val generation = generation
        val now = SystemClock.elapsedRealtime()
        _snapshot.value = VoiceSnapshot(
            live = true,
            hostName = "Fedora",
            activeSince = now - 83_000,
            activeSinceWall = System.currentTimeMillis() - 83_000,
            styles = _snapshot.value.styles,
        )
        val main = Handler(Looper.getMainLooper())
        var step = 0
        val tick = object : Runnable {
            override fun run() {
                if (this@VoiceController.generation != generation || !live) return
                _snapshot.update { it.copy(state = VoicePreviewScript.state(step, it.muted)) }
                step++
                main.postDelayed(this, 100)
            }
        }
        preview = tick
        main.post(tick)
    }

    /** The outputs the call can use right now (the stage's route menu, #63); empty without call audio. */
    fun audioRoutes(): List<VoiceAudioRoute> = media?.routes().orEmpty()

    /** Plays the call on [route] (the route menu's pick). */
    fun selectRoute(route: VoiceAudioRoute) {
        val media = media ?: return
        media.pick(route)
        if (route.kind != VoiceRoute.External) _snapshot.update { it.copy(speakerOn = route.kind == VoiceRoute.Speaker) }
        haptic(Haptic.Select)
    }

    /** The user hangs up. Local capture stops before any remote control. */
    fun stop() {
        if (!live) return
        haptic(Haptic.Stop)
        finish(null)
    }

    fun dismissEndReason() {
        if (_snapshot.value.endReason == null) return
        _snapshot.update { it.copy(endReason = null) }
    }

    /** The notice's action: open Settings for the microphone, or call again and show the stage. */
    fun noticeAction(notice: VoiceNotice, openSettings: () -> Unit) {
        if (notice.reason == VoiceEndReason.MICROPHONE_DENIED) openSettings() else if (start()) requestStage()
    }

    /** Return to the stage — only while a call is live (iOS `zeron://voice`). */
    fun requestStage() {
        if (live) _stageRequests.trySend(Unit)
    }

    /** The stage (#61) reports itself so failures explain themselves there, not in a toast. */
    fun stageShown(shown: Boolean) {
        stageViews = max(0, stageViews + if (shown) 1 else -1)
    }

    private fun finish(reason: VoiceEndReason?) {
        generation++
        preview?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        preview = null
        val s = _snapshot.value
        // A hang-up or a connected call ending sounds; a failure to connect only explains itself.
        val sounds = call != null && (s.activeSince != null || reason == null)
        media?.close()
        call?.let { c ->
            runCatching { c.stop() }
            runCatching { c.close() }
        }
        // After close: the call's audio no longer holds the communication mode.
        if (sounds) this.sounds.play(CallSounds.Sound.End)
        call = null
        media = null
        setProximityMonitoring(false)
        VoiceCallService.stop()
        var shown = reason
        if (reason != null) {
            haptic(Haptic.Error)
            // The stage explains a failure itself; elsewhere a toast does.
            if (stageViews == 0) {
                onNotice(VoiceNotice(reason, s.message(reason)))
                shown = null
            }
        }
        _snapshot.update {
            it.copy(live = false, state = null, activeSince = null, activeSinceWall = null, endReason = shown)
        }
    }

    internal fun receive(state: VoiceCallState, generation: Long) {
        if (generation != this.generation || !live) return
        val s = _snapshot.value
        val becameActive = state.phase == VoiceCallPhase.ACTIVE && s.state?.phase != VoiceCallPhase.ACTIVE
        _snapshot.update {
            it.copy(
                state = state,
                styles = state.voices.ifEmpty { it.styles },
                activeSince = if (becameActive) SystemClock.elapsedRealtime() else it.activeSince,
                activeSinceWall = if (becameActive) System.currentTimeMillis() else it.activeSinceWall,
            )
        }
        if (becameActive) {
            setProximityMonitoring(true)
            haptic(Haptic.Success)
        }
        VoiceCallService.refresh()
    }

    internal fun closed(reason: VoiceEndReason?, generation: Long) {
        if (generation != this.generation || !live) return
        finish(reason)
    }

    // ── Microphone permission ──────────────────────────────────────────────

    fun hasMicrophone(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Asks for the microphone through [VoiceHost] (the call's Prepare step,
     * like iOS asking inside the call). False when denied or nobody can ask.
     */
    internal suspend fun requestMicrophone(): Boolean {
        if (hasMicrophone()) return true
        val request = CompletableDeferred<Boolean>()
        _permissionRequest.value?.complete(false)
        _permissionRequest.value = request
        // The core gives a media step 30 s; answer before it gives up.
        val granted = withTimeoutOrNull(28_000) { request.await() } ?: false
        if (_permissionRequest.value === request) _permissionRequest.value = null
        return granted && hasMicrophone()
    }

    internal fun completePermission(granted: Boolean) {
        _permissionRequest.value?.complete(granted)
        _permissionRequest.value = null
    }

    // ── Proximity ──────────────────────────────────────────────────────────

    /**
     * Once the call is active: held to the ear the screen goes dark and
     * audio moves to the earpiece; in hand it plays on the loudspeaker.
     * No-op on devices without a sensor.
     */
    private fun setProximityMonitoring(on: Boolean) {
        if (on && proximity == null) {
            proximity = Proximity(context) { near -> media?.setNearEar(near) }.also { it.start() }
        } else if (!on) {
            proximity?.stop()
            proximity = null
        }
    }

    /** Core callbacks arrive on Rust threads; hop to the main thread. */
    private class Bridge(controller: VoiceController, private val generation: Long) : VoiceSessionListener {
        private val controller = WeakReference(controller)
        private val main = Handler(Looper.getMainLooper())

        override fun onVoiceState(state: VoiceCallState) {
            main.post { controller.get()?.receive(state, generation) }
        }

        override fun onVoiceClosed(reason: VoiceEndReason?) {
            main.post { controller.get()?.closed(reason, generation) }
        }
    }

    private companion object {
        const val TAG = "ZeronVoice"
    }
}

/**
 * The call's two sounds (iOS `CallSound`, assets from `Voice/Sounds/`).
 * They play as call signalling beside the call's own audio and stay quiet
 * when the ringer is silenced, like the phone's.
 */
internal class CallSounds(private val context: Context) {
    enum class Sound { Start, End }

    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val ids = mapOf(
        Sound.Start to pool.load(context, R.raw.voice_start, 1),
        Sound.End to pool.load(context, R.raw.voice_end, 1),
    )
    private val loaded = mutableSetOf<Int>()
    private var pending: Int? = null

    init {
        pool.setOnLoadCompleteListener { _, id, status ->
            if (status != 0) return@setOnLoadCompleteListener
            loaded += id
            if (pending == id) {
                pending = null
                pool.play(id, 1f, 1f, 1, 0, 1f)
            }
        }
    }

    fun play(sound: Sound) {
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio != null && audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val id = ids[sound] ?: return
        if (id in loaded) pool.play(id, 1f, 1f, 1, 0, 1f) else pending = id
    }
}

/**
 * The proximity sensor and its screen-off wake lock, like a phone call
 * (iOS `isProximityMonitoringEnabled`): at the ear the display sleeps and
 * [onChange] moves audio to the earpiece.
 */
private class Proximity(context: Context, private val onChange: (Boolean) -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor = sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val lock: PowerManager.WakeLock? = context.getSystemService(PowerManager::class.java)?.let { power ->
        if (power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "zeron:voice-proximity")
        } else {
            null
        }
    }
    private var near = false

    fun start() {
        sensor?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        runCatching { if (lock?.isHeld == false) lock.acquire(4 * 60 * 60 * 1000L) }
    }

    fun stop() {
        sensors?.unregisterListener(this)
        runCatching { if (lock?.isHeld == true) lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }
        if (near) onChange(false)
        near = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val range = sensor?.maximumRange ?: return
        val value = event.values.firstOrNull() ?: return
        val now = value < range && value < 5f
        if (now != near) {
            near = now
            onChange(now)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
