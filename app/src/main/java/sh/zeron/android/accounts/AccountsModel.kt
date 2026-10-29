package sh.zeron.android.accounts

import androidx.compose.runtime.Immutable
import kotlin.math.roundToInt
import sh.zeron.android.core.CloudStore
import uniffi.zeron_core.AgentLoginRules
import uniffi.zeron_core.CloudAccountInfo
import uniffi.zeron_core.CloudUsageInfo
import uniffi.zeron_core.DeviceAccountInfo
import uniffi.zeron_core.DeviceView
import uniffi.zeron_core.HarnessInfo
import uniffi.zeron_core.LoginOptionInfo
import uniffi.zeron_core.UsageWindowInfo

/**
 * The pure half of Settings → Providers and one agent's accounts (#50, iOS
 * `ProvidersViewController` and `AgentAccountsViewController`): which rows
 * show and what they say. No Android, no FFI calls — the pages hold the
 * state and draw these.
 */

/** The Providers target that means Cloud (iOS `ProvidersViewController.cloud`). */
const val CloudTarget = "cloud"

/** The copy, iOS's. */
object ProvidersCopy {
    const val Title = "Providers"
    const val Device = "Device"
    const val Devices = "Devices"
    const val Agents = "Agents"
    const val Cloud = "Cloud"
    const val Offline = "Offline"
    const val Loading = "Loading…"
    const val NoComputers = "No computers yet"
    const val NoComputersDetail = "Install Zeron on a computer to run agents and manage their accounts here."
    const val NotInstalled = "Not installed"
    const val Installed = "Installed"
    const val FinishingSignIn = "Finishing sign-in…"
    const val SignIn = "Sign in"
    const val SignInFailed = "Couldn't sign in"

    fun toggleFailed(on: Boolean) = if (on) "Couldn't turn it on" else "Couldn't turn it off"

    /** The Agents footer (iOS `footerText`). */
    fun footer(onCloud: Boolean, online: Boolean, name: String): String = when {
        onCloud -> "Cloud agents use the account marked in use. Signing in here stores the login in Cloud, not on this phone."
        !online -> "$name is offline — showing what it last reported."
        else -> "Agents run with the logins on $name. Switch accounts here; to add one, sign in from Zeron on that computer."
    }
}

/** One agent's accounts page copy, iOS's. */
object AccountsCopy {
    /** #63: TalkBack's label for an account row's long press (its Switch / Remove menu). */
    const val MoreActions = "Show actions"
    const val Loading = "Loading accounts…"
    const val LoadFailed = "Couldn't load accounts"
    const val Waiting = "Finish signing in in the browser…"
    const val TapToCancel = "Tap to cancel"
    const val InUse = "In use"
    const val Switch = "Switch"
    const val Remove = "Remove"
    const val SwitchToThis = "Switch to This Account"
    const val RemoveAccount = "Remove Account"
    const val UpdateFailed = "Couldn't update the account"
    const val SignInFailed = "Couldn't sign in"
    const val SignInAgain = "Sign in again"
    const val PasteTitle = "Paste the code"
    const val PasteMessage = "Copy the code the sign-in page shows, then paste it here."
    const val PastePlaceholder = "Authorization code"
    const val KeyMessage = "Cloud sessions use this key instead of a subscription sign-in."
    const val SaveKey = "Save Key"
    const val Cancel = "Cancel"
    const val Continue = "Continue"
    const val SignInFailedReason = "The sign-in failed."
    const val TimedOut = "The sign-in timed out."

    fun header(deviceName: String) = "Accounts on $deviceName"

    /** The page's footer: the agent's own note, then where its logins live. */
    fun footer(note: String?, cloud: Boolean, deviceName: String): String {
        val where = if (cloud) {
            "Cloud sessions use the account in use. Sign-ins and keys are kept in Cloud, never on this phone."
        } else {
            "Account changes affect new sessions on $deviceName. Signing in runs on $deviceName; you finish in the browser here."
        }
        return listOfNotNull(note, where).joinToString("\n\n")
    }

    fun keyPlaceholder(key: String) = if (key == "anthropic-key") "sk-ant-…" else "sk-…"

    fun addKey(keyName: String) = "Add $keyName"
}

/**
 * The computer Providers opens on (iOS `init`): an explicit target, then
 * the remembered one if it is still a computer (or Cloud), then the first
 * online computer, then the first computer, then Cloud.
 */
fun initialProvidersTarget(explicit: String?, remembered: String?, computers: List<DeviceView>): String =
    explicit
        ?: remembered?.takeIf { r -> r == CloudTarget || computers.any { it.id == r } }
        ?: computers.firstOrNull { it.online }?.id
        ?: computers.firstOrNull()?.id
        ?: CloudTarget

/** An account's name (iOS `accountTitle`): email, display name, "API key" or "Account". */
fun accountTitle(a: DeviceAccountInfo): String = a.email ?: a.displayName ?: if (a.apiKey) "API key" else "Account"

/** "<active> · N accounts", "<active>", "No account", "1 account", "N accounts" (iOS `summary`). */
fun accountsSummary(active: String?, count: Int, showsNone: Boolean = true): String? = when {
    active != null && count > 1 -> "$active · $count accounts"
    active != null -> active
    count == 0 -> if (showsNone) "No account" else null
    count == 1 -> "1 account"
    else -> "$count accounts"
}

/** One Providers row (iOS `ProvidersViewController.Row`). */
@Immutable
data class ProviderRow(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    /** The agent (its brand mark), when the row is one. */
    val harness: String? = null,
    val toggle: Boolean? = null,
    val toggleEnabled: Boolean = true,
    val dimmed: Boolean = false,
    val disclosure: Boolean = false,
    val busy: Boolean = false,
    /** Cloud, nothing signed in: a "Sign in" capsule. */
    val signIn: Boolean = false,
    /** A placeholder's kind (`loading`, `no-computers`). */
    val placeholder: Boolean = false,
)

/**
 * A computer's agent rows (iOS `computerRows`): installed first, then by
 * name. The switch is locked while the computer is offline, for an agent
 * that isn't installed, and for the last agent on (new sessions need
 * one). Every installed agent that signs in, or has logins, opens.
 */
fun computerRows(
    target: String,
    harnesses: List<HarnessInfo>?,
    accounts: List<DeviceAccountInfo>?,
    failed: String?,
    online: Boolean,
    toggling: Set<String>,
    rules: (harness: String) -> AgentLoginRules,
): List<ProviderRow> {
    val list = harnesses ?: return listOf(ProviderRow("loading", ProvidersCopy.Loading, placeholder = true))
    val enabledCount = list.count { it.offered }
    val logins = accounts.orEmpty()
    return list.sortedWith(compareBy({ if (it.installed) 0 else 1 }, { it.label })).map { h ->
        val mine = logins.filter { it.harness == h.id }
        val signsIn = rules(h.id).signsIn
        val subtitle = when {
            !h.installed -> ProvidersCopy.NotInstalled
            accounts == null -> failed ?: if (online) ProvidersCopy.Installed else ProvidersCopy.Offline
            else -> accountsSummary(mine.firstOrNull { it.active }?.let(::accountTitle), mine.size, showsNone = signsIn)
        }
        ProviderRow(
            id = "$target:${h.id}",
            title = h.label,
            subtitle = subtitle,
            harness = h.id,
            toggle = if (h.installed) h.offered else false,
            toggleEnabled = online && h.installed && !(h.offered && enabledCount <= 1),
            dimmed = !h.installed,
            disclosure = h.installed && (signsIn || mine.isNotEmpty()),
            busy = h.id in toggling,
        )
    }
}

/**
 * Cloud's agent rows (iOS `cloudRows`): one per Cloud agent with its
 * active account and count, "Finishing sign-in…" while one signs in, and
 * a Sign in capsule when it has none. Not offered: "Loading…" until Cloud
 * is known, then "No computers yet".
 */
fun cloudRows(
    offered: Boolean,
    demo: Boolean,
    infoKnown: Boolean,
    accounts: Map<String, List<CloudAccountInfo>>,
    signingIn: String?,
    agents: List<CloudStore.Agent> = CloudStore.agents,
): List<ProviderRow> {
    if (!offered && !demo) {
        return if (!infoKnown) {
            listOf(ProviderRow("loading", ProvidersCopy.Loading, placeholder = true))
        } else {
            listOf(ProviderRow("no-computers", ProvidersCopy.NoComputers, ProvidersCopy.NoComputersDetail, placeholder = true))
        }
    }
    return agents.map { agent ->
        val logins = accounts[agent.login].orEmpty()
        val keys = accounts[agent.key].orEmpty()
        val count = logins.size + keys.size
        val active = (logins.firstOrNull { it.active } ?: keys.firstOrNull { it.active })?.let { a ->
            listOfNotNull(a.email ?: if (a.kind == "key") agent.keyName else null, a.plan).joinToString(" · ")
        }
        val busy = signingIn == agent.login
        ProviderRow(
            id = "cloud:${agent.harness}",
            title = agent.name,
            subtitle = if (busy) ProvidersCopy.FinishingSignIn else accountsSummary(active, count),
            harness = agent.harness,
            disclosure = true,
            busy = busy,
            signIn = count == 0,
        )
    }
}

/** One account, whichever device keeps it (iOS `Account`). */
@Immutable
data class AccountItem(
    val id: String,
    val title: String,
    val detail: String?,
    val usage: List<UsageWindowInfo>,
    val usageNote: String?,
    val active: Boolean,
    val switchable: Boolean,
    val isKey: Boolean,
    /** Cloud's vault provider (`claude`, `anthropic-key`, …); null on a computer. */
    val cloudProvider: String? = null,
)

/** A computer's logins for [harness] (iOS `accounts`, `.device`). */
fun deviceAccountItems(list: List<DeviceAccountInfo>, harness: String, rules: AgentLoginRules): List<AccountItem> =
    list.filter { it.harness == harness }.map { a ->
        val option = a.provider?.let { p ->
            if (rules.options.size > 1) rules.options.firstOrNull { it.provider == p }?.label else null
        }
        AccountItem(
            id = a.id,
            title = accountTitle(a),
            detail = listOfNotNull(a.planLabel, a.organization, option).joinToString(" · ").ifEmpty { null },
            usage = if (rules.reportsUsage) a.usage else emptyList(),
            usageNote = a.usageError,
            active = a.active,
            switchable = a.switchable && rules.switches,
            isKey = a.apiKey,
        )
    }

/** Cloud's logins then keys for [agent] (iOS `accounts`, `.cloud`). */
fun cloudAccountItems(
    agent: CloudStore.Agent,
    accounts: Map<String, List<CloudAccountInfo>>,
    usage: Map<String, CloudUsageInfo>,
    usageErrors: Map<String, String>,
): List<AccountItem> {
    val logins = accounts[agent.login].orEmpty().map { a ->
        val key = "${agent.login}:${a.slot}"
        val live = usage[key]
        AccountItem(
            id = key,
            title = a.email ?: a.displayName ?: "${agent.name} account",
            // The live plan from the usage reply beats the sign-in's.
            detail = listOfNotNull(live?.plan ?: a.plan, a.organization).joinToString(" · ").ifEmpty { null },
            usage = live?.windows.orEmpty(),
            usageNote = when {
                a.needsReconnect -> AccountsCopy.SignInAgain
                live?.windows?.isNotEmpty() == true -> null
                else -> usageErrors[key]
            },
            active = a.active,
            switchable = true,
            isKey = false,
            cloudProvider = agent.login,
        )
    }
    val keys = accounts[agent.key].orEmpty().map { a ->
        AccountItem("${agent.key}:${a.slot}", agent.keyName, null, emptyList(), null, a.active, true, true, agent.key)
    }
    return logins + keys
}

/** One "add" row: a login option, or Cloud's API key ([option] null). */
@Immutable
data class AddRow(val id: String, val title: String, val option: LoginOptionInfo?)

/**
 * The list's last rows (iOS `addRows`): one per login option — the first
 * account's title when empty, "another" otherwise; none when the agent
 * keeps one login and has it. Cloud also takes an API key.
 */
fun addRows(empty: Boolean, rules: AgentLoginRules, cloudKeyName: String?): List<AddRow> = buildList {
    if (empty || !rules.keepsOneLogin) {
        rules.options.forEach { o -> add(AddRow("add:${o.provider.orEmpty()}", if (empty) o.firstTitle else o.moreTitle, o)) }
    }
    if (cloudKeyName != null) add(AddRow("add-key", AccountsCopy.addKey(cloudKeyName), null))
}

/** The card's rows, in order (iOS `Item`). */
sealed interface AccountsPageItem {
    val key: String

    data class Account(val item: AccountItem) : AccountsPageItem {
        override val key: String get() = "account:${item.id}"
    }

    data class Placeholder(val text: String, val detail: String?) : AccountsPageItem {
        override val key: String get() = "placeholder"
    }

    data class Add(val row: AddRow) : AccountsPageItem {
        override val key: String get() = row.id
    }

    data object Waiting : AccountsPageItem {
        override val key: String get() = "waiting"
    }
}

/**
 * iOS `items`: a computer's placeholder until its list lands (or the
 * failure), the accounts, the add rows, then the sign-in under way.
 */
fun accountsPageItems(
    device: Boolean,
    loaded: Boolean,
    loadError: String?,
    accounts: List<AccountItem>,
    adds: List<AddRow>,
    signingIn: Boolean,
): List<AccountsPageItem> = buildList {
    when {
        device && !loaded -> add(AccountsPageItem.Placeholder(if (loadError == null) AccountsCopy.Loading else AccountsCopy.LoadFailed, loadError))
        loadError != null && accounts.isEmpty() -> add(AccountsPageItem.Placeholder(AccountsCopy.LoadFailed, loadError))
        else -> accounts.forEach { add(AccountsPageItem.Account(it)) }
    }
    adds.forEach { add(AccountsPageItem.Add(it)) }
    if (signingIn) add(AccountsPageItem.Waiting)
}

/** "34% · 3h" — the used share and the time until the window resets (iOS `UsageMeter`). */
fun usageMeterText(window: UsageWindowInfo, nowMs: Long): String {
    val percent = (window.usedFraction.coerceIn(0f, 1f) * 100f).roundToInt()
    val resets = window.resetsAtMs ?: return "$percent%"
    val left = ((resets - nowMs) / 1000).coerceAtLeast(0)
    val span = when {
        left < 3_600 -> "${left / 60}m"
        left < 86_400 -> "${left / 3_600}h"
        else -> "${left / 86_400}d"
    }
    return "$percent% · $span"
}

/** "Enter the code ABCD-1234 when asked" → "ABCD-1234" (iOS: the first word with a dash, 7+ long). */
fun deviceCodeIn(message: String): String? = message.split(' ').firstOrNull { it.contains('-') && it.length >= 7 }
