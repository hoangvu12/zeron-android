package sh.zeron.android.voice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull
import sh.zeron.android.design.ActionSheetAction
import sh.zeron.android.design.ActionSheetModel
import sh.zeron.android.design.ActionSheetPresenter
import sh.zeron.android.design.Haptic
import sh.zeron.android.design.LocalActionSheet
import sh.zeron.android.design.LocalFeedback
import sh.zeron.android.design.SymbolWeight
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.glass.GlassCircleButton
import sh.zeron.android.design.glass.GlassSurface
import sh.zeron.android.design.glass.LocalGlassMenuHost
import sh.zeron.android.design.glass.Menu
import sh.zeron.android.design.glass.MenuIcon
import sh.zeron.android.design.glass.MenuItem
import sh.zeron.android.design.glass.MenuSection
import sh.zeron.android.design.glass.rememberGlassMenuState
import sh.zeron.android.shell.LocalNavEntryOrNull
import sh.zeron.android.shell.LocalNavStack
import uniffi.zeron_core.DeviceView
import uniffi.zeron_core.OrbPreset

/**
 * The app's voice controller (#60's `ZeronApplication.voice`), provided by
 * the app root. `null` in galleries and tests: every entry point then
 * renders nothing.
 */
val LocalVoiceController = staticCompositionLocalOf<VoiceController?> { null }

/**
 * Presents the full-screen call (iOS `presentVoiceStage`): [VoiceStageHost]
 * at the app root draws it over everything while [showing]. [source] is
 * the control it grows out of (window px; iOS's zoom transition), `null`
 * for a notification tap or `zeron://voice`.
 */
@Stable
class VoiceStagePresenter {
    var showing by mutableStateOf(false)
        private set
    var source: Rect? by mutableStateOf(null)
        private set

    fun show(source: Rect?) {
        if (showing) return
        this.source = source
        showing = true
    }

    fun hide() {
        showing = false
    }
}

/** The root's [VoiceStagePresenter]; `null` outside the app. */
val LocalVoiceStage = staticCompositionLocalOf<VoiceStagePresenter?> { null }

/**
 * Opens Voice settings (iOS `presentVoiceSettings`) for an entry point
 * outside a nav entry (the phone bottom bar provides its Sessions stack's
 * push). `null`: the entry's own stack pushes [VoiceSettingsRoute].
 */
val LocalOpenVoiceSettings = staticCompositionLocalOf<(() -> Unit)?> { null }

/** [LocalOpenVoiceSettings], else a push on the composing entry's stack, else nothing. */
@Composable
internal fun rememberOpenVoiceSettings(): () -> Unit {
    LocalOpenVoiceSettings.current?.let { return it }
    val stack = if (LocalNavEntryOrNull.current != null) LocalNavStack.current else null
    return remember(stack) { { if (stack != null) openVoiceSettings(stack) } }
}

/**
 * Whether voice controls show at all (iOS `voice.live || voice.available`):
 * once a registered host can run voice, or while a call is live.
 */
@Composable
fun rememberVoiceVisible(controller: VoiceController): Boolean {
    val s by controller.snapshot.collectAsState()
    val devices by controller.devices.collectAsState()
    return s.live || controller.availableAmong(devices)
}

/**
 * A voice control was tapped (iOS `openVoice`, `VoiceStageViewController.swift:356-363`):
 * open the live call, or start one on the default host and show the
 * stage; with no host to call, offer the host picker.
 */
fun openVoice(
    controller: VoiceController,
    stage: VoiceStagePresenter?,
    sheets: ActionSheetPresenter,
    openSettings: () -> Unit,
    source: Rect?,
) {
    if (controller.open()) {
        stage?.show(source)
    } else {
        sheets.show(voiceHostPicker(controller, onStarted = { stage?.show(source) }, openSettings = openSettings))
    }
}

/** iOS's picker copy (`VoiceStageViewController.swift:378-380`), "iPhone" → "phone". */
object VoicePickerCopy {
    const val Title = "Talk to Codex on…"
    const val NoHosts = "No online device can host Codex voice. Start Zeron with remote voice enabled on your Mac or server."
    const val SomeHosts = "Audio stays on this phone. Codex and its tools run on the device you pick."
    const val Settings = "Voice Settings"
}

/**
 * "Talk to Codex on…" (iOS `presentVoiceHostPicker`): every host that can
 * take a call (picking one calls it and remembers it), then Voice
 * Settings, then Cancel. Pure model.
 */
fun voiceHostPicker(controller: VoiceController, onStarted: () -> Unit, openSettings: () -> Unit): ActionSheetModel {
    val hosts = controller.hosts()
    return ActionSheetModel(
        title = VoicePickerCopy.Title,
        message = if (hosts.isEmpty()) VoicePickerCopy.NoHosts else VoicePickerCopy.SomeHosts,
        actions = hosts.map { device ->
            ActionSheetAction(device.name) { if (controller.start(device)) onStarted() }
        } + ActionSheetAction(VoicePickerCopy.Settings) { openSettings() },
    )
}

/**
 * The long-press menu of a voice control (iOS `voiceMenu`,
 * `VoiceStageViewController.swift:406-427`): host and style for the next
 * call — "Codex runs on" (inline, ✓ the remembered host, disabled during a
 * call), the "Voice" submenu ("Codex default" and the host's styles), and
 * Voice Settings. Built fresh on every open.
 */
fun voiceMenu(
    hosts: List<DeviceView>,
    live: Boolean,
    selectedHost: String?,
    styles: List<String>,
    selectedStyle: String?,
    onSelectHost: (String) -> Unit,
    onSelectStyle: (String?) -> Unit,
    onSettings: () -> Unit,
): Menu = Menu(
    children = buildList {
        if (hosts.isNotEmpty()) {
            add(
                MenuSection(
                    title = "Codex runs on",
                    inline = true,
                    children = hosts.map { device ->
                        MenuItem(
                            title = device.name,
                            icon = MenuIcon.Symbol(ZeronIcons.Desktopcomputer),
                            checked = device.id == selectedHost,
                            disabled = live,
                        ) { onSelectHost(device.id) }
                    },
                ),
            )
        }
        add(
            MenuSection(
                title = "Voice",
                icon = MenuIcon.Symbol(ZeronIcons.PersonWave2),
                inline = false,
                children = (listOf<String?>(null) + styles).map { style ->
                    MenuItem(title = voiceStyleLabel(style), checked = style == selectedStyle) { onSelectStyle(style) }
                },
            ),
        )
        add(MenuItem(title = VoicePickerCopy.Settings, icon = MenuIcon.Symbol(ZeronIcons.Gearshape)) { onSettings() })
    },
)

/** [voiceMenu] over the controller's state right now. */
fun VoiceController.menu(onSettings: () -> Unit): Menu = voiceMenu(
    hosts = hosts(),
    live = live,
    selectedHost = selectedHost.value,
    styles = snapshot.value.styles,
    selectedStyle = selectedStyle.value,
    onSelectHost = { selectHost(it) },
    onSelectStyle = { selectStyle(it) },
    onSettings = onSettings,
)

/** A style's label: Swift's `capitalized` (each word), or "Codex default". Pure. */
fun voiceStyleLabel(style: String?): String =
    style?.split(" ")?.joinToString(" ") { word ->
        word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
    } ?: "Codex default"

/**
 * Voice in a bar (iOS `VoiceBarItem`, `VoiceControls.swift:149-205`; the
 * phone bottom bar's item, `MainTabController.swift:54-92`): a waveform
 * glass circle to start (hold for host and style), and while a call runs
 * the live call — [liveOrb] the session header's and sidebar's live orb
 * (`AVATAR`, 32 dp), otherwise the bottom bar's accent
 * `waveform.circle.fill`. Holding the live call offers no menu (it must
 * not change the call's host). Renders nothing while voice isn't offered.
 */
@Composable
fun VoiceBarItem(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconPt: Float = 17f,
    liveOrb: Boolean = true,
    testTag: String = "voice-bar",
) {
    val controller = LocalVoiceController.current ?: return
    if (!rememberVoiceVisible(controller)) return
    val s by controller.snapshot.collectAsState()
    val stage = LocalVoiceStage.current
    val sheets = LocalActionSheet.current
    val openSettings = rememberOpenVoiceSettings()
    val feedback = LocalFeedback.current
    val menus = if (LocalGlassMenuHost.current != null) rememberGlassMenuState() else null
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val positioned = modifier.onGloballyPositioned { bounds = it.boundsInWindow() }
    val open = { openVoice(controller, stage, sheets, openSettings, bounds) }
    // The hold gesture outlives recompositions; it reads the latest action.
    val hold by rememberUpdatedState {
        bounds?.let { anchor ->
            feedback.haptic(Haptic.LongPress)
            menus?.open(anchor, controller.menu(openSettings))
        }
        Unit
    }

    when {
        !s.live -> GlassCircleButton(
            icon = ZeronIcons.Waveform,
            contentDescription = VoiceBarCopy.Start,
            onClick = open,
            modifier = positioned
                .voiceHold(enabled = menus != null, label = VoiceBarCopy.Hold) { hold() }
                .testTag(testTag),
            size = size,
            iconPt = iconPt,
            weight = SymbolWeight.Semibold,
        )
        liveOrb -> {
            val source = remember { MutableInteractionSource() }
            GlassSurface(
                positioned
                    .size(size)
                    .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = open)
                    .semantics { contentDescription = VoiceBarCopy.live(s.statusText) }
                    .testTag(testTag),
                shape = CircleShape,
                interactive = true,
                interactionSource = source,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    VoiceOrbView(
                        s.orb,
                        OrbPreset.AVATAR,
                        Modifier.size(32.dp),
                        microphone = s.state?.microphone ?: 0f,
                        speaker = s.state?.speaker ?: 0f,
                    )
                }
            }
        }
        else -> GlassCircleButton(
            icon = ZeronIcons.Waveform,
            contentDescription = VoiceBarCopy.LiveShort,
            onClick = open,
            modifier = positioned.testTag("voice-live"),
            size = size,
            iconPt = iconPt,
            weight = SymbolWeight.Semibold,
            filled = ZeronTheme.palette.accent,
        )
    }
}

/** The bar items' labels, verbatim from iOS. */
object VoiceBarCopy {
    const val Start = "Talk to Codex"
    const val Hold = "Choose the device and voice"
    const val LiveShort = "Voice call"
    fun live(status: String): String = "Voice call, $status"
}

/**
 * A long press that wins over the control's own tap (iOS `UIButton.menu`
 * on hold): after the system timeout with the finger still and down,
 * [onHold] runs and the rest of the touch is swallowed on the Initial pass,
 * so the button's `clickable` never sees its release. Observes only until
 * then. TalkBack gets the same action as a long-click labelled [label].
 */
private fun Modifier.voiceHold(enabled: Boolean, label: String, onHold: () -> Unit): Modifier {
    if (!enabled) return this
    return this
        .semantics { onLongClick(label = label) { onHold(); true } }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val cancelled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                        if (!change.pressed) return@withTimeoutOrNull true
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            return@withTimeoutOrNull true
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    true
                }
                if (cancelled != null) return@awaitEachGesture
                onHold()
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                    if (event.changes.none { it.pressed }) break
                }
            }
        }
}

/** Laptop for macOS and Windows hosts, a server rack otherwise (iOS `symbol(for:)`). Pure. */
fun voiceHostIcon(platform: String) = platform.lowercase(Locale.ROOT).let { p ->
    when {
        "mac" in p || "darwin" in p || "win" in p -> ZeronIcons.Laptopcomputer
        else -> ZeronIcons.ServerRack
    }
}
