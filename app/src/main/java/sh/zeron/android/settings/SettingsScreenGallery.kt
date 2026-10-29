package sh.zeron.android.settings

import android.content.res.Configuration
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import sh.zeron.android.core.WallpaperStore
import sh.zeron.android.design.ActionSheetHost
import sh.zeron.android.design.Appearance
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.groupedSection
import sh.zeron.android.notify.NotificationPrefs
import sh.zeron.android.notify.NotificationStatus
import sh.zeron.android.shell.NavRoute
import sh.zeron.android.shell.NavStack
import sh.zeron.android.shell.NavStackHost
import uniffi.zeron_core.DeviceView
import uniffi.zeron_core.UsageWindowInfo
import uniffi.zeron_core.WallpaperEffect

/**
 * The "Settings screen" page of the debug design gallery
 * (android-ui-parity #103): the whole tab root — the large "Settings"
 * title (25) over the six sections in order, each built through its own
 * ticket's seam (102's `engines` flow, 96's `appearance` flow, 97's
 * [WallpaperSectionActions], 101's factory parameters), so the rows are
 * the real emitters over fixture state, not screenshots of a fixture:
 *
 *  - **Devices** — one paired engine ("MacBook Pro", `Connected`) plus
 *    the "Pair an engine" row;
 *  - **Notifications** — 99's *off* fixture (the `2-settings.png` state:
 *    `Snapshot(true, true, true, true)`, `NotDetermined`, switch off, so
 *    the master row only);
 *  - **Appearance** — System stored (the System row's checkmark);
 *  - **Wallpaper** — nothing set (the "Choose Wallpaper…" row); the
 *    picker, the "Wallpaper Effect" sheet and removal run through
 *    [rememberWallpaperSectionState]'s real wiring over a gallery-local
 *    fake store;
 *  - **Sessions** — Group by Project **on**, fixed (the real preference
 *    would fight the app's own state; 100's own gallery page has the
 *    live toggle);
 *
 * Account, Voice, Math and Sign Out are left out of the fixture (their
 * rows need the app's live account, or belong to #36/#61).
 *
 * [live] drops the wallpaper state's launcher (which needs an
 * `ActivityResultRegistryOwner` the IDE preview has no host for): the
 * section then emits the same unset rows statically, so the light/dark
 * previews still render the screen. The gallery registration owns the
 * [ActionSheetHost] the "Effect" row's sheet needs (19's bounded-height
 * rule), like "Wallpaper rows" and "Session Prompts".
 *
 * Registered in ticket 22's `design/Gallery.kt` (debug source set) as
 * `GallerySection("Settings screen", GalleryLayout.Fill,
 * screenshotHeightDp = 874) { ActionSheetHost {
 * SettingsScreenGallerySection() } }` — Fill because the screen owns its
 * `LazyColumn` (22's rule: an unbounded lazy list in a `Scroll` section
 * crashes). The Roborazzi goldens of
 * [SettingsScreenScreenshotTest] host this section directly at the
 * reference phone's 402 × 874 dp.
 */
@Composable
fun SettingsScreenGallerySection(
    listState: LazyListState = rememberLazyListState(),
    live: Boolean = true,
) {
    val stack = remember { NavStack(SettingsGalleryRoot) }
    NavStackHost(stack = stack, active = true) { entry ->
        when (val route = entry.route) {
            SettingsGalleryRoot -> SettingsScreenGalleryContent(listState, live)
            else -> Unit
        }
    }
}

/** The gallery's root route (the Settings screen's own stack slot). */
private data object SettingsGalleryRoot : NavRoute {
    override val paneTitle: String get() = "Settings"
}

/**
 * The fixture: the seven section emitters in 103's order (Agent Accounts
 * first, android-next#05), inside `SettingsScreenContent(topInset =
 * 24.dp)` (Robolectric and the preview have no real status bar). The
 * notification fixture is 99's pure builder (`notificationRows` over the
 * fixed snapshot), like 99's own gallery; the accounts fixture is the
 * same shape (the pure row builder over a fixed snapshot).
 */
@Composable
private fun SettingsScreenGalleryContent(listState: LazyListState, live: Boolean) {
    val devices = rememberDevicesSectionState(
        devices = remember { MutableStateFlow(listOf(galleryDevice())) },
    )
    val appearance = rememberAppearanceSectionState(
        appearance = remember { MutableStateFlow(Appearance.System) },
        onPick = {},
    )
    // Composable reads happen here, not inside the LazyList DSL lambda.
    val wallpaper = if (live) rememberGalleryWallpaperSectionState() else null
    val sessionsStrings = sessionsSettingsStrings()
    SettingsScreenContent(listState, topInset = 24.dp) {
        galleryAgentAccountsSection()
        devicesSection(devices)
        notificationRowsSection(
            rows = notificationRows(
                NotificationPrefs.Snapshot(enabled = true, done = true, input = true, failed = true),
                NotificationStatus.NotDetermined,
                pendingOn = false,
            ),
            onToggle = { _, _ -> },
        )
        appearanceSection(appearance)
        if (wallpaper != null) {
            wallpaperSection(wallpaper)
        } else {
            galleryStaticWallpaperSection()
        }
        sessionsSettingsSection(sessionsStrings, { true }, {}, {})
    }
}

/**
 * The fixture's Agent Accounts section (android-next#05): the pure row
 * builder over a fixed snapshot — Claude Code with two logins (the
 * Session 42 % / Week 8 % meters) and Codex at 91 % (the amber mini
 * meter). Static like the wallpaper stand-in: no core, no loads.
 */
private fun LazyListScope.galleryAgentAccountsSection() {
    val rows = sh.zeron.android.accounts.agentAccountsRows(
        deviceId = "device-e1",
        deviceName = "MacBook Pro",
        snapshot = galleryAccountsSnapshot(),
        loadMessage = null,
        unsupported = false,
        // No core call: the IDE preview has no JNA host (the wallpaper
        // stand-in's rule).
        harnessLabel = { harness ->
            mapOf("claude-code" to "Claude Code", "codex" to "Codex")[harness] ?: harness
        },
    ) ?: return
    groupedSection(
        key = "agent-accounts",
        header = sh.zeron.android.accounts.AgentAccountsSectionCopy.Header,
        items = rows,
        itemKey = { it.id },
        footer = sh.zeron.android.accounts.AccountsCopy.planUsageOn("MacBook Pro"),
    ) { spec, position ->
        sh.zeron.android.accounts.AgentAccountsRow(spec, position, onClick = {})
    }
}

/** The section's fixture snapshot — pure records, no FFI. */
private fun galleryAccountsSnapshot(): sh.zeron.android.accounts.AgentAccountsSnapshot =
    sh.zeron.android.accounts.AgentAccountsSnapshot(
        accounts = listOf(
            galleryAccount(
                id = "alice-slot",
                harness = "claude-code",
                email = "alice@example.com",
                planLabel = "Max 20\u00d7",
                active = true,
                organization = "Test Org",
                usage = listOf(
                    UsageWindowInfo(label = "Session", usedFraction = 0.42f, resetsAtMs = null),
                    UsageWindowInfo(label = "Week", usedFraction = 0.08f, resetsAtMs = null),
                ),
            ),
            galleryAccount(
                id = "bob-slot",
                harness = "claude-code",
                email = "bob@example.com",
                planLabel = "Pro",
                active = false,
            ),
            galleryAccount(
                id = "codex-slot",
                harness = "codex",
                email = "dev@example.com",
                planLabel = "Plus",
                active = true,
                usage = listOf(
                    UsageWindowInfo(label = "Session", usedFraction = 0.91f, resetsAtMs = null),
                ),
            ),
        ),
    )

/** One fixture login; every field the section doesn't read is neutral. */
private fun galleryAccount(
    id: String,
    harness: String,
    email: String,
    planLabel: String,
    active: Boolean,
    organization: String? = null,
    usage: List<UsageWindowInfo> = emptyList(),
): sh.zeron.android.accounts.AgentAccount = sh.zeron.android.accounts.AgentAccount(
    id = id,
    harness = harness,
    email = email,
    displayName = null,
    planLabel = planLabel,
    active = active,
    switchable = true,
    apiKey = false,
    organization = organization,
    provider = null,
    usage = usage,
    usageError = null,
)

/**
 * The wallpaper section over a gallery-local fake store (like 97's live
 * block): `set` flips to "Picked photo", `setEffect` moves the effect,
 * `remove` clears it — the rows change exactly as they do on the real
 * screen, instantly, and the picker / effect sheet / removal run through
 * [rememberWallpaperSectionState]'s real wiring.
 */
@Composable
private fun rememberGalleryWallpaperSectionState(): WallpaperSectionState {
    val flow = remember {
        MutableStateFlow(WallpaperStore.State(set = false, name = null, effect = WallpaperEffect.NONE, generation = 0))
    }
    val actions = remember(flow) {
        WallpaperSectionActions(
            state = flow,
            // The picked Uri is not kept (ticket 20 copies the image into
            // filesDir); the fake just flips the state like the store would.
            set = { _ ->
                flow.update { it.copy(set = true, name = "Picked photo", generation = it.generation + 1) }
            },
            setEffect = { effect ->
                flow.update { it.copy(effect = effect, generation = it.generation + 1) }
            },
            remove = {
                flow.update { it.copy(set = false, name = null, generation = it.generation + 1) }
            },
        )
    }
    return rememberWallpaperSectionState(actions)
}

/**
 * The preview stand-in for the wallpaper section: the same unset rows
 * (`wallpaperRows` of the same state), with no state holder — no
 * launcher, no sheet host, so [SettingsScreenGallerySection] with
 * `live = false` renders anywhere.
 */
private fun LazyListScope.galleryStaticWallpaperSection() {
    val rows = wallpaperRows(
        WallpaperStore.State(set = false, name = null, effect = WallpaperEffect.NONE, generation = 0),
    )
    groupedSection(
        key = "wallpaper",
        header = WallpaperSectionTitle,
        items = rows,
        itemKey = { it.id },
    ) { spec, position ->
        WallpaperRow(spec, position, onClick = {})
    }
}

/**
 * The fixture's one device ("MacBook Pro", online): the fields 102's
 * section reads, every other field a neutral value — pure data, no FFI.
 */
private fun galleryDevice(): DeviceView = DeviceView(
    id = "e1",
    name = "MacBook Pro",
    platform = "windows",
    online = true,
    lastSeenMs = null,
    version = "1.2.3",
    capabilities = emptyList(),
    isExecutionHost = true,
    isSelf = false,
    sessionCount = 0u,
)

// ---------------------------------------------------------------------------
// Previews: the section's own ActionSheetHost (the gallery registration
// mounts the same one around it); live = false so the IDE has a host-free
// composition.
// ---------------------------------------------------------------------------

@Preview(name = "light", widthDp = 402, heightDp = 874)
@Composable
private fun SettingsScreenGalleryLightPreview() {
    ZeronTheme(Appearance.Light) {
        ActionSheetHost { SettingsScreenGallerySection(live = false) }
    }
}

@Preview(name = "dark", widthDp = 402, heightDp = 874, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsScreenGalleryDarkPreview() {
    ZeronTheme(Appearance.Dark) {
        ActionSheetHost { SettingsScreenGallerySection(live = false) }
    }
}
