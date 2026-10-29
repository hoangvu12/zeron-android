package sh.zeron.android.cloud

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import sh.zeron.android.ZeronApplication
import sh.zeron.android.composer.openInCustomTab
import sh.zeron.android.core.CloudStore
import sh.zeron.android.core.Face
import sh.zeron.android.design.ActionSheetAction
import sh.zeron.android.design.ActionSheetModel
import sh.zeron.android.design.GroupedAccessory
import sh.zeron.android.design.GroupedRow
import sh.zeron.android.design.LocalActionSheet
import sh.zeron.android.design.LocalBottomChrome
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.ZeronIcon
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.groupedSection
import sh.zeron.android.design.rememberPullToRefreshState
import sh.zeron.android.design.uiStyle
import sh.zeron.android.shell.LargeTitleScaffold
import sh.zeron.android.shell.NavRoute
import sh.zeron.android.shell.TitleMode
import uniffi.zeron_core.GithubRepoInfo

/**
 * Settings → Cloud (#51; iOS `Cloud/CloudSettingsViewController.swift`):
 * GitHub and the repositories Cloud sessions can open. Agents' accounts
 * are under Providers (#50); plans and credits under Billing (#52).
 *
 * [connectGitHub] opens the page with Connect GitHub already up (the
 * `cloud:github` debug route).
 */
data class CloudSettingsRoute(val connectGitHub: Boolean = false) : NavRoute {
    override val hidesBottomChrome: Boolean get() = true
    override val paneTitle: String get() = CloudSettingsCopy.Title
}

/** The page's copy (iOS, verbatim). */
object CloudSettingsCopy {
    const val Title = "Cloud"
    const val Unavailable = "Cloud isn't available"
    const val UnavailableDetail = "This account can't use Cloud yet."
    const val AddCredits = "Add credits"
    const val GitHub = "GitHub"
    const val Connected = "Connected"
    const val ConnectDetail = "Connect so Cloud agents can clone and push"
    const val Connect = "Connect"
    const val Private = "Private"
    const val ChooseOnGitHub = "Choose on GitHub"
    const val Footer = "Cloud agents clone and push to the repositories the Zeron GitHub App is installed on."
    const val ActionsMessage = "Cloud reaches the repositories the Zeron GitHub App is installed on."
    const val ChooseRepositories = "Choose Repositories on GitHub"
    const val Reconnect = "Reconnect"

    fun more(n: Int) = "$n more"

    /** The "Choose on GitHub" row's summary, by what's known of the repos. */
    fun reposSummary(count: Int?): String = when (count) {
        null -> "Checking which repositories Cloud can open…"
        0 -> "None yet: choose the repositories Cloud sessions can open."
        1 -> "Cloud sessions can open 1 repository."
        else -> "Cloud sessions can open $count repositories."
    }
}

/** A row's ink: its glyph's tint. */
enum class CloudRowTint { Text, Secondary, Warning }

/** One row of the page (iOS `CloudSettingsViewController.Row`). */
data class CloudRow(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val icon: ZeronIcon? = null,
    val tint: CloudRowTint = CloudRowTint.Text,
    /** Accent text before the chevron ("Connect", "Add credits"). */
    val accessory: String? = null,
    val disclosure: Boolean = true,
)

/** One section; `status` has no header. */
data class CloudSection(val key: String, val header: String?, val rows: List<CloudRow>, val footer: String? = null)

/** The row ids the page acts on. */
object CloudRowIds {
    const val Unavailable = "unavailable"
    const val Status = "status"
    const val StatusBilling = "status:billing"
    const val GitHub = "github"
    const val ReposMore = "repos:more"
    const val ReposChoose = "repos:choose"

    fun repo(fullName: String) = "repo:$fullName"
}

/** How many repositories the page lists before "N more". */
const val CLOUD_REPOS_SHOWN = 8

/**
 * iOS `reload()` as a pure function: the unavailable row alone while the
 * account can't use Cloud (once a status read answered); otherwise the
 * problem row, GitHub, and — connected — the repositories, newest push
 * first, eight of them, then "N more" and "Choose on GitHub".
 */
fun cloudSettingsSections(
    infoKnown: Boolean,
    available: Boolean,
    error: String?,
    problem: CloudStore.Problem?,
    githubAccount: String?,
    githubConnected: Boolean,
    repos: List<GithubRepoInfo>?,
): List<CloudSection> {
    if (infoKnown && !available) {
        return listOf(
            CloudSection(
                CloudRowIds.Status,
                header = null,
                rows = listOf(
                    CloudRow(
                        CloudRowIds.Unavailable,
                        CloudSettingsCopy.Unavailable,
                        subtitle = error ?: CloudSettingsCopy.UnavailableDetail,
                        icon = ZeronIcons.Cloud,
                        tint = CloudRowTint.Secondary,
                        disclosure = false,
                    ),
                ),
            ),
        )
    }
    val sections = ArrayList<CloudSection>(3)
    if (problem != null) {
        sections += CloudSection(
            CloudRowIds.Status,
            header = null,
            rows = listOf(
                CloudRow(
                    if (problem.billing) CloudRowIds.StatusBilling else CloudRowIds.Status,
                    problem.title,
                    subtitle = problem.detail,
                    icon = ZeronIcons.ExclamationmarkTriangle,
                    tint = CloudRowTint.Warning,
                    accessory = if (problem.billing) CloudSettingsCopy.AddCredits else null,
                    disclosure = problem.billing,
                ),
            ),
        )
    }
    sections += CloudSection(
        "github",
        header = CloudSettingsCopy.GitHub,
        rows = listOf(
            CloudRow(
                CloudRowIds.GitHub,
                githubAccount?.let { "@$it" } ?: CloudSettingsCopy.GitHub,
                subtitle = if (githubConnected) CloudSettingsCopy.Connected else CloudSettingsCopy.ConnectDetail,
                icon = ZeronIcons.GithubMark,
                accessory = if (githubConnected) null else CloudSettingsCopy.Connect,
            ),
        ),
        footer = CloudSettingsCopy.Footer,
    )
    if (githubConnected) {
        val sorted = repos.orEmpty().sortedByDescending { it.pushedAtMs }
        val rows = sorted.take(CLOUD_REPOS_SHOWN).map { repo ->
            CloudRow(
                CloudRowIds.repo(repo.fullName),
                repo.fullName,
                subtitle = if (repo.private) CloudSettingsCopy.Private else null,
                icon = if (repo.private) ZeronIcons.Lock else ZeronIcons.BookClosed,
                tint = CloudRowTint.Secondary,
                disclosure = false,
            )
        }.toMutableList()
        if (sorted.size > CLOUD_REPOS_SHOWN) {
            rows += CloudRow(
                CloudRowIds.ReposMore,
                CloudSettingsCopy.more(sorted.size - CLOUD_REPOS_SHOWN),
                icon = ZeronIcons.Ellipsis,
                tint = CloudRowTint.Secondary,
                disclosure = false,
            )
        }
        rows += CloudRow(
            CloudRowIds.ReposChoose,
            CloudSettingsCopy.ChooseOnGitHub,
            subtitle = CloudSettingsCopy.reposSummary(repos?.size),
            icon = ZeronIcons.ArrowUpRightSquare,
        )
        sections += CloudSection("repositories", header = "Repositories", rows = rows)
    }
    return sections
}

/** The page over [CloudStore]: pull to refresh re-reads everything (iOS `refreshControl`). */
@Composable
fun CloudSettingsScreen(route: CloudSettingsRoute, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as ZeronApplication
    val cloud = app.cloud
    val dark = ZeronTheme.isDark
    val sheets = LocalActionSheet.current
    val chrome = LocalBottomChrome.current
    var connecting by remember { mutableStateOf(route.connectGitHub) }
    LaunchedEffect(cloud) { cloud.refresh() }

    val sections = cloudSettingsSections(
        infoKnown = cloud.info != null,
        available = cloud.available,
        error = cloud.error,
        problem = cloud.problem,
        githubAccount = cloud.githubAccount,
        githubConnected = cloud.githubConnected,
        repos = cloud.repos,
    )
    val installUrl = cloud.vault?.githubInstallUrl
    val openInstall: (() -> Unit)? = installUrl?.let { url -> { openInCustomTab(context, url, dark) } }

    val onRow: (CloudRow) -> Unit = { row ->
        when (row.id) {
            CloudRowIds.GitHub -> if (cloud.githubConnected) {
                sheets.show(githubActions(cloud.githubAccount, openInstall) { connecting = true })
            } else {
                connecting = true
            }
            // TODO(#52): push Billing.
            CloudRowIds.StatusBilling -> Unit
            CloudRowIds.ReposChoose -> openInstall?.invoke()
            else -> Unit
        }
    }

    LargeTitleScaffold(
        title = CloudSettingsCopy.Title,
        modifier = modifier.testTag("cloud-settings"),
        mode = TitleMode.InlineOnly,
        listState = rememberLazyListState(),
        contentPadding = PaddingValues(bottom = chrome.contentPadding + 16.dp),
        pullToRefresh = rememberPullToRefreshState(onRefresh = {
            cloud.refresh()
            snapshotFlow { cloud.loading }.first { !it }
        }),
        content = {
            for (section in sections) {
                groupedSection(
                    key = section.key,
                    header = section.header ?: "",
                    items = section.rows,
                    itemKey = { it.id },
                    footer = section.footer,
                ) { row, position ->
                    GroupedRow(
                        title = row.title,
                        position = position,
                        subtitle = row.subtitle,
                        icon = row.icon,
                        iconTint = row.tint.color(),
                        accessories = cloudRowAccessories(row),
                        testTag = "cloud-${row.id}",
                        onClick = if (row.disclosure || row.id == CloudRowIds.GitHub) {
                            { onRow(row) }
                        } else {
                            null
                        },
                    )
                }
            }
        },
    )
    if (connecting) {
        GitHubConnectSheet(onDismissed = { connecting = false })
    }
}

/** iOS `githubActions(from:)`: choose repositories, reconnect, cancel. */
internal fun githubActions(account: String?, openInstall: (() -> Unit)?, reconnect: () -> Unit) = ActionSheetModel(
    title = account?.let { "@$it" },
    message = CloudSettingsCopy.ActionsMessage,
    actions = listOfNotNull(
        openInstall?.let { ActionSheetAction(CloudSettingsCopy.ChooseRepositories, onSelect = it) },
        ActionSheetAction(CloudSettingsCopy.Reconnect, onSelect = reconnect),
    ),
)

@Composable
private fun CloudRowTint.color(): Color = when (this) {
    CloudRowTint.Text -> ZeronTheme.palette.text
    CloudRowTint.Secondary -> ZeronTheme.palette.secondary
    CloudRowTint.Warning -> ZeronTheme.palette.warning
}

/** The accent accessory label, then the chevron when the row leads somewhere. */
private fun cloudRowAccessories(row: CloudRow): List<GroupedAccessory> = buildList {
    row.accessory?.let { text -> add(GroupedAccessory.Custom { AccentAccessory(text) }) }
    if (row.disclosure) add(GroupedAccessory.Disclosure)
}

@Composable
private fun AccentAccessory(text: String) {
    BasicText(
        text,
        modifier = Modifier.padding(end = 8.dp),
        style = uiStyle(Face.SansMedium, LocalTypeScale.current.fixed(14f), ZeronTheme.palette.accent),
    )
}
