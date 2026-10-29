package sh.zeron.android.accounts

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import sh.zeron.android.ZeronApplication
import sh.zeron.android.core.CloudStore
import sh.zeron.android.core.Face
import sh.zeron.android.design.CapsuleShape
import sh.zeron.android.design.GroupedAccessory
import sh.zeron.android.design.GroupedRow
import sh.zeron.android.design.Haptic
import sh.zeron.android.design.HarnessMark
import sh.zeron.android.design.LocalAlerts
import sh.zeron.android.design.LocalBottomChrome
import sh.zeron.android.design.scrollEndPadding
import sh.zeron.android.design.LocalFeedback
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.StatusGlyph
import sh.zeron.android.design.StatusGlyphKind
import sh.zeron.android.design.ZeronAlert
import sh.zeron.android.design.AlertButton
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronSwitch
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.glass.Menu
import sh.zeron.android.design.glass.MenuIcon
import sh.zeron.android.design.glass.MenuItem
import sh.zeron.android.design.glass.rememberGlassMenuState
import sh.zeron.android.design.groupedSection
import sh.zeron.android.design.rememberPullToRefreshState
import sh.zeron.android.design.uiStyle
import sh.zeron.android.shell.LargeTitleScaffold
import sh.zeron.android.shell.LaunchOptions
import sh.zeron.android.shell.LocalNavStack
import sh.zeron.android.shell.NavRoute
import sh.zeron.android.shell.TitleMode
import uniffi.zeron_core.DeviceAccountInfo
import uniffi.zeron_core.HarnessInfo

/**
 * Settings → Providers (#50, iOS `ProvidersViewController`): pick a device
 * — a computer or Cloud — then turn its agents on or off and choose the
 * account each runs on. [target] is a computer's id or [CloudTarget];
 * `null` picks as iOS does ([initialProvidersTarget]). [embedded] is
 * onboarding's Agents step (#54): the list alone, under the step's own
 * heading, with no title bar and no pull to refresh.
 */
data class ProvidersRoute(val target: String? = null, val embedded: Boolean = false) : NavRoute {
    override val paneTitle: String get() = ProvidersCopy.Title
}

/**
 * iOS `ProvidersViewController.onSummary`: what the page found — [place]
 * (the device's name, "Cloud" or "a computer"), whether it is Cloud, and
 * the agents there (null while they load). Onboarding's Agents step words
 * its heading from it.
 */
fun interface ProvidersSummary {
    fun summary(place: String, cloud: Boolean, names: List<String>?)
}

/** The summary's listener, provided by onboarding (#54); none in Settings. */
val LocalProvidersSummary = staticCompositionLocalOf<ProvidersSummary?> { null }

/**
 * Each computer's saved logins and the last failure reading them, shared by
 * Providers and an agent's accounts page (iOS hands the list across and
 * reports changes back with `onChange`). Main thread only.
 */
object DeviceAccountsStore {
    val accounts = mutableStateMapOf<String, List<DeviceAccountInfo>>()
    val failed = mutableStateMapOf<String, String>()
}

private const val ProvidersPrefs = "zeron_providers"

/** iOS `UserDefaults "providersDevice"`. */
private const val LastTargetKey = "providersDevice"

@Composable
fun ProvidersScreen(route: ProvidersRoute, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as? ZeronApplication ?: return
    val core = remember(app) { CoreProvidersCore { app.clientOrNull() } }
    val nav = LocalNavStack.current
    val alerts = LocalAlerts.current
    val feedback = LocalFeedback.current
    val scope = rememberCoroutineScope()
    val devices by app.workspaceFeed.devices.collectAsState()
    val computers = devices.orEmpty().filter { it.isExecutionHost }
    val cloud = app.cloud
    val demo = app.sessionHolder.isDemo
    // This account is offered Cloud (the edge says so; demos opt in with -cloud).
    val offered = if (demo) LaunchOptions.current.cloud else cloud.available
    val prefs = remember(app) { app.getSharedPreferences(ProvidersPrefs, Context.MODE_PRIVATE) }
    var target by rememberSaveable {
        mutableStateOf(initialProvidersTarget(route.target, prefs.getString(LastTargetKey, null), computers))
    }
    val harnesses = remember { mutableStateMapOf<String, List<HarnessInfo>>() }
    var toggling by remember { mutableStateOf(emptySet<String>()) }
    var signingIn by remember { mutableStateOf<String?>(null) }
    val onCloud = target == CloudTarget
    val computer = computers.firstOrNull { it.id == target }
    val targetName = if (onCloud) ProvidersCopy.Cloud else computer?.name ?: "your computer"
    val online = onCloud || computer?.online == true

    suspend fun loadAccounts(device: String) {
        if (device == CloudTarget || computers.firstOrNull { it.id == device }?.online != true) return
        try {
            DeviceAccountsStore.accounts[device] = core.accounts(device)
            DeviceAccountsStore.failed.remove(device)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            DeviceAccountsStore.failed[device] = CloudStore.message(t)
        }
    }

    suspend fun load() {
        val device = target
        if (device == CloudTarget) {
            if (cloud.info == null) cloud.refresh()
            cloud.refreshAccounts()
            return
        }
        harnesses[device] = core.harnesses(device)
        loadAccounts(device)
    }

    LaunchedEffect(target, computers.any { it.id == target && it.online }) { load() }
    // Remembered Cloud, but this account isn't offered it: a computer.
    LaunchedEffect(onCloud, cloud.info, cloud.available) {
        if (onCloud && !demo && cloud.info != null && !cloud.available) computers.firstOrNull()?.let { target = it.id }
    }

    val select: (String) -> Unit = { device ->
        if (device != target) {
            target = device
            prefs.edit().putString(LastTargetKey, device).apply()
        }
    }
    val setEnabled: (String, Boolean) -> Unit = { harness, on ->
        val device = target
        toggling = toggling + harness
        scope.launch {
            try {
                harnesses[device] = core.setEnabled(device, harness, on)
                feedback.haptic(Haptic.Select)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                alerts.show(ZeronAlert(ProvidersCopy.toggleFailed(on), CloudStore.message(t), buttons = listOf(AlertButton("OK"))))
            } finally {
                toggling = toggling - harness
            }
        }
    }
    val startCloudSignIn: (String) -> Unit = { harness ->
        val agent = CloudStore.agents.firstOrNull { it.harness == harness }
        if (agent != null && signingIn == null) {
            signingIn = agent.login
            scope.launch {
                try {
                    cloud.signIn(agent, app.browserReturns)
                    feedback.haptic(Haptic.Success)
                } catch (cancelled: CancellationException) {
                    // Closed the page: nothing to say.
                } catch (t: Throwable) {
                    alerts.show(ZeronAlert(ProvidersCopy.SignInFailed, CloudStore.message(t), buttons = listOf(AlertButton("OK"))))
                } finally {
                    signingIn = null
                    cloud.refreshAccounts()
                }
            }
        }
    }
    val open: (ProviderRow) -> Unit = { row ->
        val harness = row.harness
        if (row.disclosure && harness != null) {
            val t = if (onCloud) {
                AccountsTarget.Cloud(harness)
            } else {
                AccountsTarget.Device(deviceId = target, harness = harness, name = targetName, label = row.title)
            }
            nav.push(AgentAccountsRoute(t), animated = true)
        }
    }

    val rows = if (onCloud) {
        cloudRows(offered, demo, cloud.info != null, cloud.accounts, signingIn)
    } else {
        computerRows(
            target,
            harnesses[target],
            DeviceAccountsStore.accounts[target],
            DeviceAccountsStore.failed[target],
            online,
            toggling,
            core::rules,
        )
    }
    // iOS `reload()` → `onSummary` (only onboarding listens).
    val summary = LocalProvidersSummary.current
    if (summary != null) {
        val names: List<String>? = if (onCloud) {
            CloudStore.agents.map { it.name }
        } else {
            harnesses[target]?.filter { it.installed }?.map { it.label }
        }
        LaunchedEffect(summary, targetName, onCloud, offered, names) {
            if (onCloud && !offered) summary.summary("a computer", false, emptyList())
            else summary.summary(targetName, onCloud, names)
        }
    }
    val menuState = rememberGlassMenuState()
    var deviceBounds by remember { mutableStateOf(Rect.Zero) }
    val deviceMenu = Menu(
        title = ProvidersCopy.Devices,
        children = computers.map { host ->
            MenuItem(
                title = host.name,
                subtitle = if (host.online) null else ProvidersCopy.Offline,
                icon = MenuIcon.Symbol(ZeronIcons.Laptop),
                checked = host.id == target,
                testTag = "providers-device-${host.id}",
                action = { select(host.id) },
            )
        } + if (offered) {
            listOf(
                MenuItem(
                    title = ProvidersCopy.Cloud,
                    icon = MenuIcon.Symbol(ZeronIcons.Cloud),
                    checked = onCloud,
                    testTag = "providers-device-cloud",
                    action = { select(CloudTarget) },
                ),
            )
        } else {
            emptyList()
        },
    )
    val chrome = LocalBottomChrome.current
    val sections: LazyListScope.() -> Unit = {
        groupedSection(key = "device", header = null, items = listOf(target), itemKey = { "device" }) { _, position ->
            GroupedRow(
                title = ProvidersCopy.Device,
                position = position,
                modifier = Modifier.onGloballyPositioned { deviceBounds = it.boundsInWindow() },
                icon = if (onCloud) ZeronIcons.Cloud else ZeronIcons.Laptop,
                accessories = listOf(GroupedAccessory.Value(targetName)),
                testTag = "providers-device",
                // No menu at all when it would be empty (iOS avoids UIKit's abort).
                onClick = if (deviceMenu.children.isEmpty()) null else ({ menuState.open(deviceBounds, deviceMenu); Unit }),
            )
        }
        groupedSection(
            key = "agents",
            header = ProvidersCopy.Agents,
            items = rows,
            itemKey = { it.id },
            footer = ProvidersCopy.footer(onCloud, online, targetName),
        ) { row, position ->
            ProviderRowView(
                row = row,
                position = position,
                onToggle = { on -> row.harness?.let { setEnabled(it, on) } },
                onSignIn = { row.harness?.let(startCloudSignIn) },
                onOpen = { open(row) },
            )
        }
    }
    if (route.embedded) {
        // Onboarding's Agents step: the page's backdrop shows through, the
        // step's heading stands above, and the list fades out over its
        // bottom edge (the host draws the fade).
        LazyColumn(
            modifier = modifier.testTag("providers"),
            contentPadding = PaddingValues(bottom = 28.dp),
            content = sections,
        )
    } else {
        LargeTitleScaffold(
            title = ProvidersCopy.Title,
            modifier = modifier.testTag("providers"),
            mode = TitleMode.InlineOnly,
            listState = rememberLazyListState(),
            contentPadding = PaddingValues(bottom = scrollEndPadding(chrome) + 16.dp),
            pullToRefresh = rememberPullToRefreshState(onRefresh = { load() }),
            content = sections,
        )
    }
}

/** One agent row (iOS's list cell): brand mark, name, summary, then a switch, spinner, Sign in or disclosure. */
@Composable
private fun ProviderRowView(
    row: ProviderRow,
    position: sh.zeron.android.design.GroupedPosition,
    onToggle: (Boolean) -> Unit,
    onSignIn: () -> Unit,
    onOpen: () -> Unit,
) {
    val palette = ZeronTheme.palette
    val accessories = buildList {
        when {
            row.signIn && !row.busy -> add(GroupedAccessory.Custom { SignInCapsule(row, onSignIn) })
            // #63: the spinner reads as "in progress" in the row's announcement.
            row.busy -> add(GroupedAccessory.Custom { Box(Modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) { StatusGlyph(StatusGlyphKind.Spinner) } })
            row.toggle != null -> add(
                GroupedAccessory.Custom {
                    ZeronSwitch(
                        checked = row.toggle,
                        onCheckedChange = onToggle,
                        enabled = row.toggleEnabled,
                        testTag = "toggle-${row.id}",
                    )
                },
            )
        }
        if (row.disclosure) add(GroupedAccessory.Disclosure)
    }
    GroupedRow(
        title = row.title,
        position = position,
        subtitle = row.subtitle,
        icon = if (row.placeholder) (if (row.id == "loading") ZeronIcons.Ellipsis else ZeronIcons.Desktopcomputer) else null,
        titleStyle = uiStyle(Face.SansMedium, LocalTypeScale.current.fixed(16f), if (row.dimmed) palette.tertiary else palette.text),
        accessories = accessories,
        testTag = "providers-${row.id}",
        onClick = if (row.disclosure) onOpen else null,
        leading = row.harness?.let { h ->
            { HarnessMark(h, 20.dp, palette.text, Modifier.alpha(if (row.dimmed) 0.45f else 1f)) }
        },
    )
}

/** Cloud with nothing signed in: a quiet "Sign in" capsule on the row (iOS `CloudRow.rowButton`). */
@Composable
private fun SignInCapsule(row: ProviderRow, onClick: () -> Unit) {
    val palette = ZeronTheme.palette
    Box(
        Modifier
            .background(palette.controlFill, CapsuleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .testTag("providers-sign-in-${row.harness.orEmpty()}"),
    ) {
        BasicText(ProvidersCopy.SignIn, style = uiStyle(Face.SansMedium, LocalTypeScale.current.fixed(13f), palette.text))
    }
}
