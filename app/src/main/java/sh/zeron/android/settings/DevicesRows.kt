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
import uniffi.zeron_core.EngineState
import uniffi.zeron_core.EngineView
import uniffi.zeron_core.ParkReason

/**
 * The Settings "Devices" section (android-ui-parity #102): the port of
 * `MoreViewController.swift:189-190` — one informational row per device
 * (iOS lists cloud execution hosts; this still lists `CoreClient.engines()`
 * until the core swap). Rows don't open anything, as on iOS.
 *
 * Every visual — geometry, fonts, colours, the 8 dp dot, the chevron,
 * separators, the pressed highlight — comes from ticket 70's
 * [GroupedRow]; this file sets no dp, font or colour of its own. It adds
 * the row data, the connection-label mapping (the web's
 * `settings-engine.ts:71-87` vocabulary plus the typed `ParkReason`s)
 * and nothing else.
 *
 * Rows follow the live engine registry flow (ticket 71's
 * [ZeronApplication.coreStartup] `engines`) with **no extra delay**: a
 * rename or connection change shows up on the next frame, with no
 * debounce and no grace — iOS reloads `apply(s, animatingDifferences:
 * false)` at once (`MoreViewController.swift:219`).
 */

/** The section's copy, verbatim from the ticket's table. The ellipses are U+2026. */
object DevicesCopy {
    /** `MoreViewController.swift:189`. */
    const val Header = "Devices"

    /** `settings-engine.ts:73` (the registry entry is still pending). */
    const val Starting = "Starting\u2026"

    /** `settings-engine.ts:77`. */
    const val Connected = "Connected"

    /** `settings-engine.ts:79`. */
    const val Reconnecting = "Reconnecting\u2026"

    /** `MoreViewController.swift:190` (the phone has no network). */
    const val Offline = "Offline"

    /** `settings-engine.ts:83` (`Parked(IDENTITY_CHANGED)`). */
    const val EngineChanged = "Engine changed"

    /** `settings-engine.ts:83` (`Parked(REVOKED)`). */
    const val SessionRevoked = "Session revoked"

    /** `Parked(INCOMPATIBLE)` with no `lastError`. */
    const val UpdateRequired = "Update required"
}

/**
 * The row ids: the iOS `device:` prefix is kept on engine rows
 * (`Row(id: "device:\($0.id)")`, `MoreViewController.swift:189`) so the
 * tag is `settings-device:<engineId>` — ticket 103's `settingsSectionFor`
 * matches the prefix. The tags are [settingsTag] of these.
 */
object DevicesRowIds {
    /** `device:<engineId>` — the iOS id prefix over Zeron's engine id. */
    fun engine(engineId: String) = "device:$engineId"
}

/**
 * One row of the section, pure data: the [GroupedRow] inputs plus the
 * click's subject. Built only by [devicesRows]; the gallery builds them
 * from a fixed fixture instead of `EngineView`s.
 */
@Immutable
sealed interface DevicesRowSpec {
    val id: String

    /** One device: name, connection label and the dot's state. */
    data class Engine(
        val engineId: String,
        val title: String,
        val label: String,
        val connected: Boolean,
    ) : DevicesRowSpec {
        override val id: String get() = DevicesRowIds.engine(engineId)
    }
}

/**
 * The connection label of [state] — `settings-engine.ts:71-87` (the web
 * engine row vocabulary) plus the typed states the web lacks:
 * [EngineState.Offline] (the OS reports no network) and the
 * [ParkReason]s behind the web's `lastError.includes("identity")` string
 * test. Every non-connected state is a plain word; only
 * [ParkReason.INCOMPATIBLE] shows `lastError` (the engine-too-old /
 * app-too-old sentence) — transport errors belong in logs, never in the
 * row. The `when` is exhaustive
 * with no `else`, so a new Rust state is a compile error, not a silent
 * label.
 */
fun engineConnectionLabel(state: EngineState, lastError: String?): String = when (state) {
    is EngineState.Connecting -> DevicesCopy.Starting
    is EngineState.Connected -> DevicesCopy.Connected
    is EngineState.Reconnecting -> DevicesCopy.Reconnecting
    is EngineState.Offline -> DevicesCopy.Offline
    is EngineState.Parked -> when (state.reason) {
        ParkReason.IDENTITY_CHANGED -> DevicesCopy.EngineChanged
        ParkReason.REVOKED -> DevicesCopy.SessionRevoked
        ParkReason.INCOMPATIBLE ->
            lastError?.trim().takeUnless { it.isNullOrEmpty() } ?: DevicesCopy.UpdateRequired
    }
}

/**
 * The section's rows, `MoreViewController.swift:189-190` adapted: one
 * [DevicesRowSpec.Engine] per engine **in `engines()` order** (never
 * sorted). `null` (the core not `Ready` yet) is an empty list. The title guard
 * `name.trim().ifEmpty { host }` only stops an empty title line if
 * Rust's non-blank name guarantee ever breaks.
 */
fun devicesRows(engines: List<EngineView>?): List<DevicesRowSpec> =
    engines.orEmpty().map { e ->
        DevicesRowSpec.Engine(
            engineId = e.engineId,
            title = e.name.trim().ifEmpty { e.host },
            label = engineConnectionLabel(e.state, e.lastError),
            connected = e.state is EngineState.Connected,
        )
    }

/**
 * The section's rows as a pure function of the collected engine list
 * ([rows] is a snapshot read, so the list re-derives on the next frame
 * with no animation). Built only by [rememberDevicesSectionState].
 */
@Stable
class DevicesSectionState internal constructor(
    private val current: State<List<EngineView>?>,
) {
    /** The rows for the current engine list — a snapshot read. */
    val rows: List<DevicesRowSpec> by derivedStateOf { devicesRows(current.value) }
}

/**
 * Remembers the Devices section state. Call at **screen** level (ticket
 * 103 calls this once at the top of `SettingsScreen`), never inside a
 * `LazyColumn` item.
 *
 * `collectAsState()` on the [StateFlow] starts from its current value, so
 * the first frame already shows the engines (no empty flash). Never call
 * `CoreClient.engines()` here or in composition: ticket 71's collector
 * already reads it off the main thread.
 */
@Composable
fun rememberDevicesSectionState(
    engines: StateFlow<List<EngineView>?> =
        (LocalContext.current.applicationContext as ZeronApplication).coreStartup.engines,
): DevicesSectionState {
    val current = engines.collectAsState()
    return remember(current) { DevicesSectionState(current) }
}

/**
 * One row of the section: ticket 70's [GroupedRow] with the spec's data.
 * Engine rows carry the `desktopcomputer` icon (ticket 06), the
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
        is DevicesRowSpec.Engine -> GroupedRow(
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
 * per row, keyed `grouped:devices:row:device:<engineId>`, so a state
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
