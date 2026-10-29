package sh.zeron.android.accounts

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import sh.zeron.android.R
import sh.zeron.android.ZeronApplication
import sh.zeron.android.core.CloudStore
import sh.zeron.android.core.Face
import sh.zeron.android.design.AlertButton
import sh.zeron.android.design.AlertButtonRole
import sh.zeron.android.design.AlertField
import sh.zeron.android.design.CapsuleShape
import sh.zeron.android.design.GroupedAccessory
import sh.zeron.android.design.GroupedRow
import sh.zeron.android.design.GroupedRowContainer
import sh.zeron.android.design.Haptic
import sh.zeron.android.design.HarnessMark
import sh.zeron.android.design.Icon
import sh.zeron.android.design.LocalAlerts
import sh.zeron.android.design.LocalBottomChrome
import sh.zeron.android.design.scrollEndPadding
import sh.zeron.android.design.LocalFeedback
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.StatusGlyph
import sh.zeron.android.design.StatusGlyphKind
import sh.zeron.android.design.SymbolWeight
import sh.zeron.android.design.ZeronAlert
import sh.zeron.android.design.ZeronIcon
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.glass.Menu
import sh.zeron.android.design.glass.MenuIcon
import sh.zeron.android.design.glass.MenuItem
import sh.zeron.android.design.glass.rememberGlassMenuState
import sh.zeron.android.design.groupedSection
import sh.zeron.android.design.rememberPullToRefreshState
import sh.zeron.android.design.uiStyle
import sh.zeron.android.sessions.SwipeAction
import sh.zeron.android.sessions.SwipeRow
import sh.zeron.android.shell.LargeTitleScaffold
import sh.zeron.android.shell.TitleMode
import uniffi.zeron_core.LoginOptionInfo
import uniffi.zeron_core.UsageWindowInfo

/** The key icon for a Cloud API key (SF `key.fill`). */
private val KeyIcon = ZeronIcon.Vector(R.drawable.zi_key_minimalistic)

/** A sign-in under way: which option, and what to show while it waits. */
private data class SignInState(val option: String, val message: String? = null)

/**
 * One agent's accounts on one device (#50, iOS `AgentAccountsViewController`):
 * every login with its plan and usage, the one in use, switching and
 * removing (swipe, or long-press), and adding more — one row per login
 * option. A computer's sign-in runs on that computer and the phone opens
 * the page; Cloud's are signed in by the phone and kept in its vault,
 * which also takes an API key.
 */
@Composable
fun AgentAccountsPage(
    route: AgentAccountsRoute,
    modifier: Modifier = Modifier,
    /**
     * #55: shown on its own in a sheet (an error card's "Log in"): no back,
     * no status-bar inset, and a Done circle that calls this (iOS's Done
     * when the page is the root of a presented sheet).
     */
    onDone: (() -> Unit)? = null,
    /** The bottom inset the host reserves (the sheet's); default: the bottom chrome's. */
    bottomInset: androidx.compose.ui.unit.Dp? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as? ZeronApplication ?: return
    val core = remember(app) { CoreProvidersCore { app.clientOrNull() } }
    val alerts = LocalAlerts.current
    val feedback = LocalFeedback.current
    val scope = rememberCoroutineScope()
    val cloud = app.cloud
    val target = route.target
    val harness = target.harness
    val rules = remember(harness) { core.rules(harness) }
    val devices by app.workspaceFeed.devices.collectAsState()
    val computers = devices.orEmpty().filter { it.isExecutionHost }
    val cloudAgent = (target as? AccountsTarget.Cloud)?.let { t -> CloudStore.agents.firstOrNull { it.harness == t.harness } }
    val deviceId = (target as? AccountsTarget.Device)?.let { it.deviceId ?: computers.firstOrNull()?.id }
    val deviceName = when (target) {
        is AccountsTarget.Cloud -> "Cloud"
        is AccountsTarget.Device -> target.name ?: computers.firstOrNull { it.id == deviceId }?.name ?: "your computer"
    }
    val title = when (target) {
        is AccountsTarget.Cloud -> cloudAgent?.name ?: harness
        is AccountsTarget.Device -> rules.name.ifEmpty { target.label ?: harness }
    }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var signIn by remember { mutableStateOf<SignInState?>(null) }

    fun showAlert(title: String, message: String) {
        alerts.show(ZeronAlert(title, message, buttons = listOf(AlertButton("OK"))))
    }

    suspend fun load() {
        if (target is AccountsTarget.Cloud) {
            cloud.refreshAccounts()
            return
        }
        val id = deviceId ?: return
        try {
            DeviceAccountsStore.accounts[id] = core.accounts(id)
            loadError = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            loadError = CloudStore.message(t)
        }
    }
    LaunchedEffect(deviceId) { load() }

    val latestContext by rememberUpdatedState(context)
    val deviceSignIn = remember(app) {
        DeviceSignIn(
            core = core,
            open = { url -> scope.launch { app.browserReturns.browse(url) } },
            askForCode = { askForCode(alerts) },
            copy = { code ->
                // Marked sensitive, so the system's clipboard preview never shows it.
                val clip = ClipData.newPlainText("Code", code).apply {
                    description.extras = android.os.PersistableBundle().apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                    }
                }
                latestContext.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
            },
        )
    }
    // Leaving the page ends a computer's sign-in rather than polling for
    // ten minutes behind it (iOS `viewWillDisappear`).
    DisposableEffect(deviceSignIn) { onDispose { deviceSignIn.cancel() } }

    val loaded = deviceId != null && DeviceAccountsStore.accounts.containsKey(deviceId)
    val accounts = when (target) {
        is AccountsTarget.Cloud -> cloudAgent?.let { cloudAccountItems(it, cloud.accounts, cloud.usage, cloud.usageErrors) }.orEmpty()
        is AccountsTarget.Device -> deviceId?.let { DeviceAccountsStore.accounts[it] }?.let { deviceAccountItems(it, harness, rules) }.orEmpty()
    }
    val adds = addRows(accounts.isEmpty(), rules, cloudAgent?.keyName)
    val items = accountsPageItems(
        device = target is AccountsTarget.Device,
        loaded = loaded,
        loadError = loadError,
        accounts = accounts,
        adds = adds,
        signingIn = signIn != null,
    )

    fun run(key: String, work: suspend () -> Unit) {
        if (busy != null) return
        busy = key
        scope.launch {
            try {
                work()
                feedback.haptic(Haptic.Select)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                showAlert(AccountsCopy.UpdateFailed, CloudStore.message(t))
            } finally {
                busy = null
            }
        }
    }

    fun cloudAccount(item: AccountItem) = item.cloudProvider?.let { p ->
        val slot = item.id.removePrefix("$p:")
        cloud.accounts[p]?.firstOrNull { it.slot == slot }
    }

    fun activate(item: AccountItem) {
        if (item.active || !item.switchable) return
        run(item.id) {
            when (target) {
                is AccountsTarget.Cloud -> {
                    val info = cloudAccount(item) ?: return@run
                    cloud.activate(info, item.cloudProvider ?: return@run)
                }
                is AccountsTarget.Device -> {
                    val id = deviceId ?: return@run
                    DeviceAccountsStore.accounts[id] = core.activate(id, harness, item.id)
                }
            }
        }
    }

    fun remove(item: AccountItem) {
        run(item.id) {
            when (target) {
                is AccountsTarget.Cloud -> {
                    val info = cloudAccount(item) ?: return@run
                    cloud.remove(info, item.cloudProvider ?: return@run)
                }
                is AccountsTarget.Device -> {
                    val id = deviceId ?: return@run
                    DeviceAccountsStore.accounts[id] = core.forget(id, harness, item.id)
                }
            }
        }
    }

    fun startSignIn(option: LoginOptionInfo) {
        if (signIn != null) return
        signIn = SignInState(option.provider.orEmpty())
        scope.launch {
            try {
                when (target) {
                    is AccountsTarget.Cloud -> cloudAgent?.let { cloud.signIn(it, app.browserReturns) }
                    is AccountsTarget.Device -> {
                        val id = deviceId ?: return@launch
                        val list = deviceSignIn.run(id, harness, option.provider) { message ->
                            signIn = signIn?.copy(message = message)
                        }
                        if (list != null) DeviceAccountsStore.accounts[id] = list else load()
                    }
                }
                feedback.haptic(Haptic.Success)
            } catch (cancelled: CancellationException) {
                // Called off, or the page was closed.
            } catch (t: Throwable) {
                showAlert(AccountsCopy.SignInFailed, CloudStore.message(t))
            } finally {
                signIn = null
            }
        }
    }

    fun askForKey() {
        val agent = cloudAgent ?: return
        alerts.show(
            ZeronAlert(
                title = agent.keyName,
                message = AccountsCopy.KeyMessage,
                field = AlertField(
                    placeholder = AccountsCopy.keyPlaceholder(agent.key),
                    capitalization = KeyboardCapitalization.None,
                    autocorrect = false,
                    secure = true,
                ),
                buttons = listOf(
                    AlertButton(AccountsCopy.Cancel, role = AlertButtonRole.Cancel),
                    AlertButton(AccountsCopy.SaveKey, requiresText = true) { key ->
                        if (key.isNotEmpty()) run("api-key") { cloud.saveKey(key, agent) }
                    },
                ),
            ),
        )
    }

    // An error card's "Log in": the first option starts once, on appear.
    var signInStarted by remember { mutableStateOf(false) }
    LaunchedEffect(route.signIn, deviceId) {
        val option = rules.options.firstOrNull()
        if (route.signIn && !signInStarted && option != null && (target is AccountsTarget.Cloud || deviceId != null)) {
            signInStarted = true
            startSignIn(option)
        }
    }

    val chrome = LocalBottomChrome.current
    LargeTitleScaffold(
        title = title,
        modifier = modifier.testTag("agent-accounts"),
        mode = TitleMode.InlineOnly,
        listState = rememberLazyListState(),
        topInset = if (onDone != null) 0.dp else sh.zeron.android.shell.largeTitleDefaultTopInset(),
        contentPadding = PaddingValues(bottom = (bottomInset ?: scrollEndPadding(chrome)) + 16.dp),
        showBack = if (onDone != null) false else sh.zeron.android.shell.LocalNavStack.current.entries.firstOrNull()?.id != sh.zeron.android.shell.LocalNavEntry.current.id,
        trailing = {
            if (onDone != null) {
                sh.zeron.android.design.glass.GlassCircleButton(
                    icon = ZeronIcons.Checkmark,
                    contentDescription = "Done",
                    onClick = onDone,
                    modifier = Modifier.testTag("accounts-done"),
                    filled = ZeronTheme.palette.text,
                    ink = ZeronTheme.palette.background,
                )
            }
        },
        pullToRefresh = rememberPullToRefreshState(onRefresh = { load() }),
    ) {
        groupedSection(
            key = "accounts",
            header = AccountsCopy.header(deviceName),
            items = items,
            itemKey = { it.key },
            footer = AccountsCopy.footer(rules.note, target is AccountsTarget.Cloud, deviceName),
        ) { item, position ->
            when (item) {
                is AccountsPageItem.Placeholder -> GroupedRow(
                    title = item.text,
                    position = position,
                    subtitle = item.detail,
                    icon = if (item.detail == null) null else ZeronIcons.ExclamationmarkTriangle,
                    accessories = emptyList(),
                    testTag = "accounts-placeholder",
                )
                is AccountsPageItem.Account -> AccountRow(
                    item = item.item,
                    harness = harness,
                    busy = busy == item.item.id,
                    canAct = busy == null,
                    position = position,
                    onSwitch = { activate(item.item) },
                    onRemove = { remove(item.item) },
                )
                is AccountsPageItem.Add -> {
                    val disabled = signIn != null || busy != null
                    GroupedRow(
                        title = item.row.title,
                        position = position,
                        icon = ZeronIcons.Plus,
                        iconTint = if (disabled) ZeronTheme.palette.tertiary else ZeronTheme.palette.secondary,
                        titleStyle = uiStyle(
                            Face.SansMedium,
                            LocalTypeScale.current.fixed(15f),
                            if (disabled) ZeronTheme.palette.tertiary else ZeronTheme.palette.text,
                        ),
                        accessories = emptyList(),
                        testTag = item.row.id,
                        onClick = if (disabled) null else ({
                            val option = item.row.option
                            if (option == null) askForKey() else startSignIn(option)
                        }),
                    )
                }
                AccountsPageItem.Waiting -> {
                    // A computer's sign-in can be called off from here; a
                    // Cloud one ends with its browser page.
                    val detail = listOfNotNull(
                        signIn?.message,
                        if (target is AccountsTarget.Device) AccountsCopy.TapToCancel else null,
                    ).joinToString(" · ").ifEmpty { null }
                    GroupedRow(
                        title = AccountsCopy.Waiting,
                        position = position,
                        subtitle = detail,
                        accessories = listOf(GroupedAccessory.Custom { BusyGlyph() }),
                        testTag = "sign-in-waiting",
                        onClick = if (target is AccountsTarget.Device) ({ deviceSignIn.cancel() }) else null,
                    )
                }
            }
        }
    }
}

/** iOS `askForCode`: "Paste the code" — the trimmed code, or `null` when cancelled or empty. */
private suspend fun askForCode(alerts: sh.zeron.android.design.Alerts): String? = suspendCancellableCoroutine { cont ->
    alerts.show(
        ZeronAlert(
            title = AccountsCopy.PasteTitle,
            message = AccountsCopy.PasteMessage,
            field = AlertField(
                placeholder = AccountsCopy.PastePlaceholder,
                capitalization = KeyboardCapitalization.None,
                autocorrect = false,
            ),
            buttons = listOf(
                AlertButton(AccountsCopy.Cancel, role = AlertButtonRole.Cancel) { if (cont.isActive) cont.resumeWith(Result.success(null)) },
                AlertButton(AccountsCopy.Continue) { raw ->
                    if (cont.isActive) cont.resumeWith(Result.success(raw.trim().ifEmpty { null }))
                },
            ),
        ),
    )
    cont.invokeOnCancellation { alerts.dismissAll() }
}

/**
 * One account (iOS `AccountCell`): a 22 dp brand mark (or a key), the name
 * (middle-truncated; dimmed when it can't be switched), the plan, up to
 * three usage meters and a warning note; "In use", a Switch capsule or a
 * spinner on the right. A trailing swipe removes it; a long press offers
 * Switch / Remove.
 */
@Composable
private fun AccountRow(
    item: AccountItem,
    harness: String,
    busy: Boolean,
    canAct: Boolean,
    position: sh.zeron.android.design.GroupedPosition,
    onSwitch: () -> Unit,
    onRemove: () -> Unit,
) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    val menu = rememberGlassMenuState()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val actionable = canAct && item.switchable
    val content: @Composable () -> Unit = {
        GroupedRowContainer(
            position = position,
            modifier = Modifier.onGloballyPositioned { bounds = it.boundsInWindow() },
            testTag = "account-${item.id}",
            onLongClickLabel = AccountsCopy.MoreActions,
            onLongClick = if (actionable) ({
                val children = buildList {
                    if (!item.active) {
                        add(MenuItem(AccountsCopy.SwitchToThis, icon = MenuIcon.Symbol(ZeronIcons.ArrowLeftArrowRight), action = onSwitch))
                    }
                    add(MenuItem(AccountsCopy.RemoveAccount, icon = MenuIcon.Symbol(ZeronIcons.Trash), destructive = true, action = onRemove))
                }
                menu.open(bounds, Menu(title = null, children = children))
                Unit
            }) else null,
        ) {
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).padding(vertical = 13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                        if (item.isKey) {
                            Icon(KeyIcon, palette.text, 15f, SymbolWeight.Semibold)
                        } else {
                            HarnessMark(harness, 20.dp, palette.text)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        BasicText(
                            item.title,
                            style = uiStyle(Face.SansMedium, ts.fixed(16f), if (!item.active && !item.switchable) palette.tertiary else palette.text),
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                        item.detail?.let {
                            BasicText(it, style = uiStyle(Face.Sans, ts.fixed(13f), palette.secondary), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    AccountTrailing(item, busy, onSwitch)
                }
                if (item.usage.isNotEmpty() || item.usageNote != null) {
                    Column(
                        Modifier.padding(start = 36.dp, top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item.usage.take(3).forEach { UsageMeter(it) }
                        item.usageNote?.let { BasicText(it, style = uiStyle(Face.Sans, ts.fixed(12f), palette.warning)) }
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
        }
    }
    if (actionable) {
        FittedSwipeRow(
            id = item.id,
            trailing = listOf(
                SwipeAction(
                    title = AccountsCopy.Remove,
                    icon = ZeronIcons.Trash,
                    background = palette.danger,
                    destructive = true,
                    testTag = "account-remove",
                    perform = { done ->
                        onRemove()
                        done(true)
                    },
                ),
            ),
        ) { content() }
    } else {
        content()
    }
}

/**
 * A [SwipeRow] at its content's natural height (#133). SwipeRow fills its
 * parent's height and lays its content out `matchParentSize`, so it has
 * no intrinsic height of its own: the `height(IntrinsicSize.Min)` this
 * replaced resolved to 0, and every switchable account (Claude Code's)
 * vanished while Providers still counted it. The content measures itself
 * unbounded and the row takes that height (one frame at 0 first).
 */
@Composable
internal fun FittedSwipeRow(
    id: String,
    trailing: List<SwipeAction>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var naturalPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    SwipeRow(
        id = id,
        leading = null,
        trailing = trailing,
        modifier = modifier.height(with(density) { naturalPx.toDp() }),
    ) {
        Box(
            Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                if (placeable.height != naturalPx) naturalPx = placeable.height
                layout(placeable.width, constraints.constrainHeight(placeable.height)) { placeable.place(0, 0) }
            },
        ) { content() }
    }
}

@Composable
private fun AccountTrailing(item: AccountItem, busy: Boolean, onSwitch: () -> Unit) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    when {
        busy -> BusyGlyph()
        item.active -> BasicText(
            AccountsCopy.InUse,
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(palette.success.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .testTag("account-in-use"),
            style = uiStyle(Face.SansMedium, ts.fixed(12f), palette.success),
        )
        item.switchable -> BasicText(
            AccountsCopy.Switch,
            Modifier
                .clip(CapsuleShape)
                .background(palette.controlFill)
                .clickable(role = Role.Button, onClick = onSwitch)
                .padding(horizontal = 12.dp, vertical = 5.dp)
                .testTag("account-switch"),
            style = uiStyle(Face.SansMedium, ts.fixed(13f), palette.text),
        )
        else -> Unit
    }
}

/** "Session  ▓▓▓░░░  34% · 3h" (iOS `UsageMeter`): 56 dp label, 4 dp track, danger above 90 %. */
@Composable
private fun UsageMeter(window: UsageWindowInfo) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    val fraction = window.usedFraction.coerceIn(0f, 1f)
    val fill: Color = if (fraction > 0.9f) palette.danger else palette.text.copy(alpha = 0.55f)
    // #63: one stop — "Session, 34% · 3h" — with the share as its progress.
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(window.label, Modifier.width(56.dp), style = uiStyle(Face.Sans, ts.fixed(12f), palette.secondary), maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(palette.controlFill),
        ) {
            Box(Modifier.fillMaxWidth(fraction.coerceAtLeast(0.001f)).height(4.dp).background(fill))
        }
        Spacer(Modifier.width(10.dp))
        BasicText(
            usageMeterText(window, System.currentTimeMillis()),
            Modifier.width(76.dp),
            style = uiStyle(Face.Sans, ts.fixed(12f), palette.secondary).copy(textAlign = androidx.compose.ui.text.style.TextAlign.End),
            maxLines = 1,
        )
    }
}

/** A row's spinner that TalkBack reads as "in progress" (#63). */
@Composable
private fun BusyGlyph() {
    Box(Modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) { StatusGlyph(StatusGlyphKind.Spinner) }
}
