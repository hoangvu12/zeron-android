package sh.zeron.android.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import sh.zeron.android.core.Face
import sh.zeron.android.design.GroupedAccessory
import sh.zeron.android.design.GroupedRow
import sh.zeron.android.design.Haptic
import sh.zeron.android.design.LocalBottomChrome
import sh.zeron.android.design.LocalFeedback
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.ZeronIcon
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.glass.LocalGlassMenuHost
import sh.zeron.android.design.glass.Menu
import sh.zeron.android.design.glass.MenuItem
import sh.zeron.android.design.glass.rememberGlassMenuState
import sh.zeron.android.design.groupedSection
import sh.zeron.android.design.uiStyle
import sh.zeron.android.shell.LargeTitleScaffold
import sh.zeron.android.shell.NavRoute
import sh.zeron.android.shell.NavStack
import sh.zeron.android.shell.TitleMode
import uniffi.zeron_core.DeviceView
import uniffi.zeron_core.OrbPreset
import uniffi.zeron_core.VoiceOrb

/**
 * Voice settings pushed on a stack (#61, iOS `VoiceViewController`):
 * Settings' Voice row, and Voice Settings in a voice control's menu or
 * host picker. Keeps the bottom chrome, like Settings' other pushes.
 */
data object VoiceSettingsRoute : NavRoute {
    override val paneTitle: String get() = VoiceSettingsCopy.Title
}

/** Pushes [VoiceSettingsRoute] on [nav] unless it is already on top. */
fun openVoiceSettings(nav: NavStack) {
    if (nav.top.route == VoiceSettingsRoute) return
    nav.push(VoiceSettingsRoute, animated = true)
}

/** The page's copy, verbatim from iOS (`VoiceViewController.swift`), "iPhone" → "phone". */
object VoiceSettingsCopy {
    const val Title = "Voice"
    const val HeroTitle = "Talk with Codex"
    const val HeroDetail = "A voice orchestrator that starts sessions, checks on them and reports back."
    const val HostsHeader = "Codex runs on"
    const val HostsFooterLive = "End the call to change devices."
    const val HostsFooter = "Audio stays on this phone. Codex, its sign-in and its tools run on the device you choose."
    const val VoiceHeader = "Voice"
    const val VoiceFooter = "Applies to your next call."
    const val CallHeader = "During a call"
    const val NoHosts = "No Mac or server yet"
    const val NoHostsDetail = "Open Zeron on your Mac or server with remote voice enabled."
    const val Offline = "Offline"
    const val Incompatible = "Update Zeron and enable remote voice"
    const val Ready = "Ready"
}

/** One host row (iOS `Row` for `host-<id>`). Pure data. */
data class VoiceHostRow(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ZeronIcon,
    val selected: Boolean,
    val enabled: Boolean,
    /** Online and able to take a call: the green status dot. */
    val ready: Boolean,
)

/**
 * The "Codex runs on" rows (iOS `reload`, `VoiceViewController.swift:151-168`):
 * every registered execution host (phones can't host Codex), Offline /
 * "Update Zeron and enable remote voice" / Ready, ✓ the host a plain tap
 * calls ([chosen]), and only ready hosts selectable — none during a call.
 * Pure.
 */
fun voiceHostRows(
    devices: List<DeviceView>,
    live: Boolean,
    chosen: String?,
    compatible: (DeviceView) -> Boolean,
): List<VoiceHostRow> = devices.filter { it.isExecutionHost }.map { device ->
    val ok = compatible(device)
    val ready = device.online && ok
    VoiceHostRow(
        id = device.id,
        title = device.name,
        subtitle = when {
            !device.online -> VoiceSettingsCopy.Offline
            !ok -> VoiceSettingsCopy.Incompatible
            else -> VoiceSettingsCopy.Ready
        },
        icon = voiceHostIcon(device.platform),
        selected = device.id == chosen,
        enabled = ready && !live,
        ready = ready,
    )
}

/** A static tip in "During a call". */
private data class VoiceTip(val id: String, val title: String, val detail: String, val icon: ZeronIcon)

private val VoiceTips = listOf(
    VoiceTip("tip-ear", "Hold it to your ear", "The screen turns off and Codex plays through the earpiece.", ZeronIcons.Ear),
    VoiceTip("tip-lock", "Lock your phone", "The call keeps going. An incoming call ends it.", ZeronIcons.Lock),
    VoiceTip("tip-bar", "Start from the bottom bar", "Tap the waveform; hold it to switch device or voice.", ZeronIcons.Waveform),
)

/**
 * The page (iOS `VoiceViewController`, port 04 G24): the hero (the large
 * orb at rest, "Talk with Codex", one line), the hosts, the voice style
 * (a value row whose tap opens the style menu), and the call tips — in the
 * Settings list style. Every source is a flow, so the list follows the
 * call, the devices and the choices on its own.
 */
@Composable
fun VoiceSettingsScreen(modifier: Modifier = Modifier) {
    val controller = LocalVoiceController.current ?: return
    val s by controller.snapshot.collectAsState()
    val devices by controller.devices.collectAsState()
    val selectedHost by controller.selectedHost.collectAsState()
    val selectedStyle by controller.selectedStyle.collectAsState()
    val feedback = LocalFeedback.current
    val chrome = LocalBottomChrome.current
    val menus = if (LocalGlassMenuHost.current != null) rememberGlassMenuState() else null
    var styleAnchor by remember { mutableStateOf(Rect.Zero) }

    // `selectedHost` is read so a choice re-renders the ✓ (startHost reads it).
    val chosen = remember(devices, selectedHost) { controller.startHost()?.id }
    val rows = voiceHostRows(devices, s.live, chosen, controller::callable)

    LargeTitleScaffold(
        title = VoiceSettingsCopy.Title,
        modifier = modifier.testTag("voice-settings"),
        mode = TitleMode.InlineOnly,
        contentPadding = PaddingValues(bottom = chrome.contentPadding + 16.dp),
    ) {
        item(key = "voice-hero") { VoiceHero() }
        groupedSection(
            key = "voice-hosts",
            header = VoiceSettingsCopy.HostsHeader,
            items = rows.ifEmpty { listOf(null) },
            itemKey = { it?.id ?: "no-hosts" },
            footer = if (s.live) VoiceSettingsCopy.HostsFooterLive else VoiceSettingsCopy.HostsFooter,
        ) { row, position ->
            if (row == null) {
                GroupedRow(
                    title = VoiceSettingsCopy.NoHosts,
                    position = position,
                    subtitle = VoiceSettingsCopy.NoHostsDetail,
                    icon = ZeronIcons.Desktopcomputer,
                    iconTint = ZeronTheme.palette.tertiary,
                    accessories = emptyList(),
                    testTag = "voice-no-hosts",
                )
            } else {
                GroupedRow(
                    title = row.title,
                    position = position,
                    subtitle = row.subtitle,
                    icon = row.icon,
                    iconTint = if (row.enabled) ZeronTheme.palette.secondary else ZeronTheme.palette.tertiary,
                    accessories = buildList {
                        add(GroupedAccessory.Dot(row.ready))
                        if (row.selected) add(GroupedAccessory.Check(true))
                    },
                    testTag = "voice-host-${row.id}",
                    onClick = if (row.enabled) {
                        {
                            controller.selectHost(row.id)
                            feedback.haptic(Haptic.Select)
                        }
                    } else {
                        null
                    },
                )
            }
        }
        groupedSection(
            key = "voice-style",
            header = VoiceSettingsCopy.VoiceHeader,
            items = listOf("style"),
            itemKey = { it },
            footer = VoiceSettingsCopy.VoiceFooter,
        ) { _, position ->
            GroupedRow(
                title = VoiceSettingsCopy.Title,
                position = position,
                modifier = Modifier.onGloballyPositioned { styleAnchor = it.boundsInWindow() },
                icon = ZeronIcons.PersonWave2,
                accessories = listOf(GroupedAccessory.Value(voiceStyleLabel(selectedStyle))),
                testTag = "voice-style",
                onClick = {
                    menus?.open(styleAnchor, voiceStyleMenu(s.styles, selectedStyle) { style ->
                        controller.selectStyle(style)
                        feedback.haptic(Haptic.Select)
                    })
                },
            )
        }
        voiceTips()
    }
}

/** Every voice the host offers, the current one checked (iOS `styleMenu`). Pure. */
fun voiceStyleMenu(styles: List<String>, selected: String?, onSelect: (String?) -> Unit): Menu = Menu(
    children = (listOf<String?>(null) + styles).map { style ->
        MenuItem(title = voiceStyleLabel(style), checked = style == selected) { onSelect(style) }
    },
)

private fun LazyListScope.voiceTips() = groupedSection(
    key = "voice-call",
    header = VoiceSettingsCopy.CallHeader,
    items = VoiceTips,
    itemKey = { it.id },
) { tip, position ->
    GroupedRow(
        title = tip.title,
        position = position,
        subtitle = tip.detail,
        icon = tip.icon,
        accessories = emptyList(),
        testTag = "voice-${tip.id}",
    )
}

/** The hero (iOS `VoiceHeroCell`): the desktop orb at rest over a one-line explanation. */
@Composable
private fun VoiceHero() {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp)
            .clearAndSetSemantics { contentDescription = "${VoiceSettingsCopy.HeroTitle}. ${VoiceSettingsCopy.HeroDetail}" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        VoiceOrbView(VoiceOrb.IDLE, OrbPreset.LARGE, Modifier.size(112.dp))
        Spacer(Modifier.height(4.dp))
        BasicText(
            VoiceSettingsCopy.HeroTitle,
            style = uiStyle(Face.SansSemibold, ts.fixed(20f), palette.text).copy(textAlign = TextAlign.Center),
        )
        BasicText(
            VoiceSettingsCopy.HeroDetail,
            modifier = Modifier.widthIn(max = 320.dp),
            style = uiStyle(Face.Sans, ts.fixed(14f), palette.secondary).copy(textAlign = TextAlign.Center),
        )
    }
}

/**
 * Settings' Voice row (iOS `MoreViewController.swift:213-216`): only while
 * voice is offered; the subtitle names the chosen style.
 */
fun settingsVoiceSubtitle(style: String?): String =
    style?.let { "Codex voice · ${voiceStyleLabel(it)}" } ?: "Codex voice device and style"
