package sh.zeron.android.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.flow.StateFlow
import sh.zeron.android.ZeronApplication
import sh.zeron.android.accounts.agentAccountsSection
import sh.zeron.android.design.LocalActionSheet
import sh.zeron.android.design.LocalBottomChrome
import sh.zeron.android.shell.LargeTitleScaffold
import sh.zeron.android.shell.LocalNavStack
import sh.zeron.android.shell.TitleMode

/**
 * The Settings screen: the Compose port of `MoreViewController` — a thin
 * assembly. [LargeTitleScaffold]'s large "Settings" title over one
 * `LazyColumn` that stacks the section emitters in iOS's order
 * (`MoreViewController.swift:185-248`, #35):
 *
 * **Account · Agents · Devices · Voice · Notifications · Appearance ·
 * Wallpaper · Math · Sessions · (untitled) Sign Out**
 *
 * Agents and Voice are later tickets' (see the `TODO`s in
 * [SettingsScreen]); Roboco's Background ("Stay connected", D8) and
 * About/updater/Licences (D10) sections are gone, as on iOS.
 *
 * Every row's look is ticket 70's; every row's behaviour is its section's;
 * this screen owns only:
 *
 *  - the section order (the order of the emitter calls below **is** the
 *    spec — [SettingsSection] documents it);
 *  - the screen-level hoisting of the section states, so the permission
 *    and photo-picker launchers and the dialog hosts survive scrolling;
 *  - the **one** `ON_RESUME` re-read of the notification status
 *    (`willEnterForeground` / `viewWillAppear`, `MoreViewController.swift:99-111`);
 *  - the wiring of the Sessions rows to the `groupByProject` preference
 *    and `openArchived`, and of Sign Out to its confirm sheet;
 *  - the bottom scroll inset under the chrome group.
 *
 * Every source is a `StateFlow` or snapshot state, so recomposition **is**
 * iOS's `reload()`, and rows appear and disappear instantly
 * (`animatingDifferences: false`). Only the notification status cannot be
 * observed, hence the lifecycle effect.
 */

/** `MoreViewController.swift:31`: the title, verbatim (drawn by ticket 25). */
const val SettingsTitle = "Settings"

/**
 * The screen's tunables. [BottomExtra] is the trailing space **on top of**
 * ticket 30's `contentPadding` — the stand-in for the trailing space
 * UIKit's inset-grouped layout puts after the last section (the scope's
 * "bottom chrome inset + 16 dp"; 30's padding already leaves 8 dp, so the
 * total clearance above the accessory is 24 dp).
 */
object SettingsScreenTokens {

    /** Trailing space after the last card, on top of the bottom chrome padding. */
    val BottomExtra = 16.dp
}

/**
 * The sections, in display order = declaration order — iOS's
 * (`MoreViewController.swift:185-248`). The [key]s are the
 * `groupedSection` keys; they must stay unique across the screen (70
 * prefixes them `grouped:<key>:…`). Voice has no rows yet (#61), so it
 * emits nothing.
 */
enum class SettingsSection(val key: String) {
    Account("account"),

    /** iOS "Agents" (Providers / Cloud / Billing); Roboco's Agent Accounts until #49. */
    AgentAccounts("agent-accounts"),
    Devices("devices"),
    Voice("voice"),
    Notifications("notifications"),
    Appearance("appearance"),
    Wallpaper("wallpaper"),
    Math("math"),
    Sessions("sessions"),

    /** iOS's untitled last section. */
    SignOut("signout"),
}

/**
 * The engine-row id prefix 103's table names. Ticket 102 kept iOS's
 * `device:` prefix (`DevicesRowIds.device`), and 102's own KDoc hands it
 * to this map — so [SettingsDeviceIdPrefix] (taken from 102's constant,
 * never retyped) is the prefix the real rows carry, and the `engine:`
 * spelling 103's reviewer note expected is accepted alongside it: the map
 * holds either.
 */
private const val SettingsEngineIdPrefix = "engine:"

/** 102's device-row id prefix, derived from [DevicesRowIds.device] (see above). */
private val SettingsDeviceIdPrefix = DevicesRowIds.device("")

/**
 * Pure: maps a row id (the part after `settings-`) to its section; null
 * for an unknown id. The prefixes are the section tickets' row ids:
 * `agent-account:`/`agent-accounts:` (android-next#05),
 * `engine:`/`device:` rows (102), `notify:` (99),
 * `appearance:` (96), `wallpaper:` (97), `math:` (#36's rows), 100's two rows;
 * `account` and `signout` are #18's rows, `voice` #61's.
 */
fun settingsSectionFor(id: String): SettingsSection? = when {
    id == AccountRowIds.Account -> SettingsSection.Account
    id == AccountRowIds.SignOut -> SettingsSection.SignOut

    id.startsWith("agent-account:") || id.startsWith("agent-accounts:") ->
        SettingsSection.AgentAccounts

    id.startsWith(SettingsEngineIdPrefix) ||
        id.startsWith(SettingsDeviceIdPrefix) -> SettingsSection.Devices

    id == "voice" -> SettingsSection.Voice
    id.startsWith("notify:") -> SettingsSection.Notifications
    id.startsWith("appearance:") -> SettingsSection.Appearance
    id.startsWith("wallpaper:") -> SettingsSection.Wallpaper
    id.startsWith("math:") -> SettingsSection.Math
    id == SessionsSettingsRow.GroupByProject.id ||
        id == SessionsSettingsRow.Archived.id -> SettingsSection.Sessions

    else -> null
}

/**
 * Pure: the screen's bottom content padding — the chrome group's
 * `contentPadding` (30) plus [SettingsScreenTokens.BottomExtra]. Passed
 * to [LargeTitleScaffold] as `contentPadding(bottom = …)`, never as a
 * `Modifier.padding` on the list: rows must scroll under the glass bars.
 */
fun settingsBottomPadding(chromeContentPadding: Dp): Dp =
    chromeContentPadding + SettingsScreenTokens.BottomExtra

// ---------------------------------------------------------------------------
// The screen
// ---------------------------------------------------------------------------

/**
 * The Settings tab's root (and the tablet dialog's content, 104).
 *
 * The section states are **parameters with the real factories as
 * defaults**, so tests and previews inject fakes through the factories'
 * own seams without new seams here — and the launchers (97's photo
 * picker, 99's permission request) and the states' snapshots are created
 * at screen level, never inside a lazy item (a launcher created in an
 * item loses its result when the item scrolls away).
 *
 * `topInset` defaults to [Dp.Unspecified]: "use 25's own default" — the
 * status bar in a tab, and 0 inside 104's card through 25's
 * `LocalLargeTitleHostInsets`. Re-declaring the status-bar default here
 * would override that and put a status-bar gap inside the dialog.
 *
 * [groupByProject]/[onGroupByProjectChange] are 100's wiring: the shared
 * phone-local preference (40), which the Sessions ⋯ menu (46) also flips —
 * one writer, two surfaces reading the same flow.
 *
 * @param trailing the nav row's trailing slot: `{}` on the phone tab,
 *   104's "Done" capsule in the tablet dialog
 * @param topInset see above; [Dp.Unspecified] = 25's default
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    topInset: Dp = Dp.Unspecified,
    trailing: @Composable RowScope.() -> Unit = {},
    accounts: sh.zeron.android.accounts.AgentAccountsSectionState =
        sh.zeron.android.accounts.rememberAgentAccountsSectionState(),
    devices: DevicesSectionState = rememberDevicesSectionState(),
    notifications: NotificationSectionState = rememberNotificationSectionState(),
    appearance: AppearanceSectionState = rememberAppearanceSectionState(),
    wallpaper: WallpaperSectionState = rememberWallpaperSectionState(),
    math: MathSectionState = rememberMathSectionState(),
    groupByProject: StateFlow<Boolean> =
        (LocalContext.current.applicationContext as ZeronApplication).prefs.groupByProject,
    onGroupByProjectChange: (Boolean) -> Unit =
        (LocalContext.current.applicationContext as ZeronApplication).prefs::setGroupByProject,
    account: StateFlow<AccountIdentity> =
        (LocalContext.current.applicationContext as ZeronApplication).account,
    onSignOut: () -> Unit =
        (LocalContext.current.applicationContext as ZeronApplication)::signOut,
    /** #48: iOS `viewDidLoad`'s `app.cloud.refresh()` — Cloud's status for the "Agents" rows (#49). */
    refreshCloud: () -> Unit =
        (LocalContext.current.applicationContext as ZeronApplication).cloud::refresh,
) {
    val listState = rememberLazyListState()
    val identity by account.collectAsState()
    val signOut by rememberUpdatedState(onSignOut)
    val sheets = LocalActionSheet.current
    val cloudRefresh by rememberUpdatedState(refreshCloud)
    LaunchedEffect(Unit) { cloudRefresh() }
    // The ONE ON_RESUME re-read (99's contract): returning from system
    // settings, from the permission dialog, from the background, plus the
    // lifecycle catch-up when the effect enters composition in a resumed
    // activity — so a tab switch back re-reads the status too. An
    // injected hand-built NotificationSectionState gets the same re-reads.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notifications.refreshStatus() }
    val group by groupByProject.collectAsState()
    val onGroup by rememberUpdatedState(onGroupByProjectChange)
    val nav = LocalNavStack.current
    val sessionsStrings = sessionsSettingsStrings()
    SettingsScreenContent(listState, modifier, topInset, trailing) {
        accountSection(identity)
        // TODO(#49): iOS's "Agents" section — Providers, then Cloud and
        // Billing when the account is offered Cloud (`MoreViewController.swift:197-209`).
        // Roboco's Agent Accounts summary holds the slot until then.
        agentAccountsSection(accounts)
        devicesSection(devices)
        // TODO(#61): the "Voice" section, only while voice is available
        // (`MoreViewController.swift:213-216`).
        // TODO(#57): the notification settings follow-ups.
        notificationSection(notifications)
        appearanceSection(appearance)
        wallpaperSection(wallpaper)
        mathSection(math)
        sessionsSettingsSection(
            sessionsStrings,
            // The value is read inside the row item (100's rule: the lazy
            // key stays stable when the ⋯ menu flips the preference).
            groupByProject = { group },
            onGroupByProjectChange = { onGroup(it) },
            onOpenArchived = { openArchived(nav) },
        )
        signOutSection { sheets.show(signOutSheet { signOut() }) }
    }
}

// ---------------------------------------------------------------------------
// The assembly (no behaviour)
// ---------------------------------------------------------------------------

/**
 * The scaffold and padding only; [sections] emits the grouped sections.
 * Used by [SettingsScreen], the design gallery and the screenshot tests,
 * which build their section states through each ticket's seam instead of
 * the app singletons.
 *
 * - the page fill is 25's root `Palette.background` (never painted twice);
 * - the bottom padding is [settingsBottomPadding] over
 *   [LocalBottomChrome] (never a constant, never `navigationBars` alone —
 *   that misses the pill and the accessory);
 * - the `settings-screen` root tag sits on the scaffold's root modifier;
 * - `topInset.isSpecified` never flips for a given host, so the branch is
 *   a one-time choice: when specified it is forwarded, otherwise 25's
 *   default (and 104's `LocalLargeTitleHostInsets` override of it) applies.
 */
@Composable
internal fun SettingsScreenContent(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    topInset: Dp = Dp.Unspecified,
    trailing: @Composable RowScope.() -> Unit = {},
    sections: LazyListScope.() -> Unit,
) {
    val chrome = LocalBottomChrome.current
    val padding = PaddingValues(bottom = settingsBottomPadding(chrome.contentPadding))
    val tagged = modifier.testTag("settings-screen")
    if (topInset.isSpecified) {
        LargeTitleScaffold(
            title = SettingsTitle,
            modifier = tagged,
            mode = TitleMode.Always,
            listState = listState,
            topInset = topInset,
            contentPadding = padding,
            trailing = trailing,
            content = sections,
        )
    } else {
        // Omit topInset entirely: forwarding even Dp.Unspecified would
        // override 25's default (104's card resolves it to 0).
        LargeTitleScaffold(
            title = SettingsTitle,
            modifier = tagged,
            mode = TitleMode.Always,
            listState = listState,
            contentPadding = padding,
            trailing = trailing,
            content = sections,
        )
    }
}
