package sh.zeron.android.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.StateFlow
import sh.zeron.android.ZeronApplication
import sh.zeron.android.design.GroupedAccessory
import sh.zeron.android.design.GroupedPosition
import sh.zeron.android.design.GroupedRow
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.groupedSection
import sh.zeron.android.design.settingsTag
import uniffi.zeron_core.DeviceView

/**
 * The Settings "Devices" section (android-ui-parity #102): the port of
 * `MoreViewController.swift:189-190` — one informational row per device
 * in Zeron's `devices()`. Rows don't open anything, as on iOS.
 * TODO(#35): the iOS Settings restructure (which devices it lists).
 *
 * Every visual — geometry, fonts, colours, the 8 dp dot, the chevron,
 * separators, the pressed highlight — comes from ticket 70's
 * [GroupedRow]; this file sets no dp, font or colour of its own. It adds
 * the row data, the online/offline label and nothing else.
 *
 * Rows follow the live device flow ([ZeronApplication.workspaceFeed]
 * `devices`) with **no extra delay**: a
 * rename or connection change shows up on the next frame, with no
 * debounce and no grace — iOS reloads `apply(s, animatingDifferences:
 * false)` at once (`MoreViewController.swift:219`).
 */

/** The section's copy, verbatim from the ticket's table. */
object DevicesCopy {
    /** `MoreViewController.swift:189`. */
    const val Header = "Devices"

    /** `settings-engine.ts:77`. */
    const val Connected = "Connected"

    /** `MoreViewController.swift:190`. */
    const val Offline = "Offline"
}

/**
 * The row ids: the iOS `device:` prefix is kept on engine rows
 * (`Row(id: "device:\($0.id)")`, `MoreViewController.swift:189`) so the
 * tag is `settings-device:<deviceId>` — ticket 103's `settingsSectionFor`
 * matches the prefix. The tags are [settingsTag] of these.
 */
object DevicesRowIds {
    /** `device:<deviceId>` — the iOS id prefix over Zeron's device id. */
    fun device(deviceId: String) = "device:$deviceId"
}

/**
 * One row of the section, pure data: the [GroupedRow] inputs plus the
 * click's subject. Built only by [devicesRows]; the gallery builds them
 * from a fixed fixture instead of `DeviceView`s.
 */
@Immutable
sealed interface DevicesRowSpec {
    val id: String

    /** One device: name, connection label and the dot's state. */
    data class Device(
        val deviceId: String,
        val title: String,
        val label: String,
        val connected: Boolean,
    ) : DevicesRowSpec {
        override val id: String get() = DevicesRowIds.device(deviceId)
    }
}

/** The row's label: Zeron reports presence only (`DeviceView.online`). */
fun deviceConnectionLabel(online: Boolean): String =
    if (online) DevicesCopy.Connected else DevicesCopy.Offline

/**
 * The section's rows, `MoreViewController.swift:189-190` adapted: one
 * [DevicesRowSpec.Device] per device **in `devices()` order** (never
 * sorted). `null` (no client yet) is an empty list. The title guard
 * `name.trim().ifEmpty { id }` only stops an empty title line if
 * Rust's non-blank name guarantee ever breaks.
 */
fun devicesRows(devices: List<DeviceView>?): List<DevicesRowSpec> =
    devices.orEmpty().map { d ->
        DevicesRowSpec.Device(
            deviceId = d.id,
            title = d.name.trim().ifEmpty { d.id },
            label = deviceConnectionLabel(d.online),
            connected = d.online,
        )
    }

/**
 * The section's rows as a pure function of the collected device list
 * ([rows] is a snapshot read, so the list re-derives on the next frame
 * with no animation). Built only by [rememberDevicesSectionState].
 */
@Stable
class DevicesSectionState internal constructor(
    private val current: State<List<DeviceView>?>,
) {
    /** The rows for the current device list — a snapshot read. */
    val rows: List<DevicesRowSpec> by derivedStateOf { devicesRows(current.value) }
}

/**
 * Remembers the Devices section state. Call at **screen** level (ticket
 * 103 calls this once at the top of `SettingsScreen`), never inside a
 * `LazyColumn` item.
 *
 * `collectAsState()` on the [StateFlow] starts from its current value, so
 * the first frame already shows the devices (no empty flash). Never call
 * `CoreClient.devices()` here or in composition: the workspace feed
 * already reads it off the main thread.
 */
@Composable
fun rememberDevicesSectionState(
    devices: StateFlow<List<DeviceView>?> =
        (LocalContext.current.applicationContext as ZeronApplication).workspaceFeed.devices,
): DevicesSectionState {
    val current = devices.collectAsState()
    return remember(current) { DevicesSectionState(current) }
}

/**
 * One row of the section: ticket 70's [GroupedRow] with the spec's data.
 * Device rows carry the `desktopcomputer` icon (ticket 06), the
 * connection label as the subtitle, then the `Dot` accessory. No tap and
 * no chevron: iOS device rows don't open anything. `iconTint` keeps its
 * `secondary` default and `destructive` is false. The dot is decorative
 * (the label carries the state in words).
 */
@Composable
fun DevicesRow(
    spec: DevicesRowSpec,
    position: GroupedPosition,
    modifier: Modifier = Modifier,
) {
    when (spec) {
        is DevicesRowSpec.Device -> GroupedRow(
            title = spec.title,
            position = position,
            modifier = modifier,
            subtitle = spec.label,
            icon = ZeronIcons.Desktopcomputer,
            accessories = listOf(GroupedAccessory.Dot(spec.connected)),
            testTag = settingsTag(spec.id),
        )
    }
}

/**
 * Emits the section into ticket 103's `LargeTitleScaffold` content (or
 * any `LazyColumn`): the "Devices" header and the rows, one lazy item
 * per row, keyed `grouped:devices:row:device:<deviceId>`, so a state
 * change keeps the item identity. [DevicesSectionState.rows] is read
 * inside this builder, so the items re-derive on the next frame when the
 * flow changes. **No `Modifier.animateItem()`**: iOS reloads with
 * `animatingDifferences: false` (`MoreViewController.swift:219`).
 */
fun LazyListScope.devicesSection(state: DevicesSectionState) {
    groupedSection(
        key = "devices",
        header = DevicesCopy.Header,
        items = state.rows,
        itemKey = { it.id },
    ) { row, position ->
        DevicesRow(row, position)
    }
}
