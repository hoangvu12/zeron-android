package sh.zeron.android.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import sh.zeron.android.ZeronApplication
import sh.zeron.android.core.CloudStore
import sh.zeron.android.core.Face
import sh.zeron.android.design.AlertButton
import sh.zeron.android.design.AlertButtonRole
import sh.zeron.android.design.GroupedAccessory
import sh.zeron.android.design.GroupedFooter
import sh.zeron.android.design.GroupedPosition
import sh.zeron.android.design.GroupedRow
import sh.zeron.android.design.Haptic
import sh.zeron.android.design.Icon
import sh.zeron.android.design.LocalAlerts
import sh.zeron.android.design.LocalFeedback
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.SymbolWeight
import sh.zeron.android.design.ZeronAlert
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.glass.ActivitySpinner
import sh.zeron.android.design.sheet.GlassSheet
import sh.zeron.android.design.sheet.SheetDetent
import sh.zeron.android.design.sheet.SheetHeader
import sh.zeron.android.design.sheet.SheetLeading
import sh.zeron.android.design.sheet.rememberGlassSheetState
import sh.zeron.android.design.uiStyle
import sh.zeron.android.onboarding.Capsule
import uniffi.zeron_core.GithubRepoInfo

/** The picker's copy (iOS, verbatim). */
object RepoPickerCopy {
    const val Title = "New Cloud Project"
    const val Search = "Search repositories"
    const val LoadFailed = "Couldn't load repositories"
    const val LoadFailedDetail = "Check your connection and try again."
    const val Retry = "Try again"
    const val NoRepos = "No repositories"
    const val NoReposDetail = "Cloud reaches the repositories the Zeron GitHub App is installed on."
    const val Connect = "Connect GitHub"
    const val ConnectDetail = "Cloud projects are GitHub repositories. Connect GitHub to pick one."
    const val Footer = "Each session in a Cloud project runs on its own Cloud machine."
    const val AddFailed = "Couldn't add the project"
    const val Private = "Private"
}

/** What the picker shows when no row does. */
enum class RepoPickerEmpty { Loading, Failed, NoRepos, Connect }

/** The rows: newest push first, filtered by a trimmed, lower-cased `fullName` match (iOS `reloadRepos`). Pure. */
fun repoPickerRows(repos: List<GithubRepoInfo>?, query: String): List<GithubRepoInfo> {
    val all = repos.orEmpty().sortedByDescending { it.pushedAtMs }
    val q = query.trim().lowercase()
    return if (q.isEmpty()) all else all.filter { it.fullName.lowercase().contains(q) }
}

/** The empty state: still reading or failed (no list yet), else no repositories or not connected. Pure. */
fun repoPickerEmpty(repos: List<GithubRepoInfo>?, loading: Boolean, githubConnected: Boolean): RepoPickerEmpty = when {
    repos == null -> if (loading) RepoPickerEmpty.Loading else RepoPickerEmpty.Failed
    githubConnected -> RepoPickerEmpty.NoRepos
    else -> RepoPickerEmpty.Connect
}

/** A row's line: "Private · description", either alone, or nothing. Pure. */
fun repoDetail(repo: GithubRepoInfo): String? =
    listOfNotNull(if (repo.private) RepoPickerCopy.Private else null, repo.description?.takeIf { it.isNotEmpty() })
        .joinToString(" · ")
        .ifEmpty { null }

/**
 * New Cloud Project (#52; iOS `Cloud/CloudRepoPickerViewController.swift`):
 * the GitHub repositories Cloud reaches. Picking one makes it a project
 * that lives on Cloud alone, then [onCreated] gets its id as the sheet
 * closes.
 */
@Composable
fun CloudRepoPickerSheet(onDismissed: () -> Unit, onCreated: (String) -> Unit = {}) {
    val app = LocalContext.current.applicationContext as ZeronApplication
    val cloud = app.cloud
    val alerts = LocalAlerts.current
    val feedback = LocalFeedback.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf<String?>(null) }
    // The repository read is in flight (`cloud.repos` stays null on failure).
    var loading by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<String?>(null) }
    val load: () -> Unit = {
        loading = true
        scope.launch {
            try {
                cloud.refreshGitHub()
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) { load() }

    GlassSheet(
        onDismiss = {
            onDismissed()
            created?.let(onCreated)
        },
        modifier = Modifier.testTag("cloud-repo-picker"),
        state = rememberGlassSheetState(remember { listOf(SheetDetent.Large) }),
        grabber = true,
        title = RepoPickerCopy.Title,
        header = { SheetHeader(RepoPickerCopy.Title, leading = SheetLeading.Close) },
    ) { bottomInset ->
        val sheet = this
        val rows = repoPickerRows(cloud.repos, query)
        Column(Modifier.fillMaxSize()) {
            SearchField(query) { query = it }
            if (rows.isEmpty()) {
                EmptyState(
                    repoPickerEmpty(cloud.repos, loading, cloud.githubConnected),
                    onRetry = load,
                    onConnect = { connecting = true },
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = bottomInset + 24.dp),
                ) {
                    items(rows.size, key = { rows[it].fullName }) { ix ->
                        val repo = rows[ix]
                        GroupedRow(
                            title = repo.fullName,
                            position = GroupedPosition.of(ix, rows.size),
                            subtitle = repoDetail(repo),
                            icon = ZeronIcons.GithubMark,
                            iconTint = ZeronTheme.palette.text,
                            accessories = if (creating == repo.fullName) {
                                listOf(GroupedAccessory.Custom { RowSpinner() })
                            } else {
                                emptyList()
                            },
                            testTag = "repo-${repo.fullName}",
                            onClick = {
                                if (creating == null) {
                                    creating = repo.fullName
                                    scope.launch {
                                        try {
                                            created = cloud.createProject(repo.fullName)
                                            feedback.haptic(Haptic.Success)
                                            sheet.dismiss()
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            creating = null
                                            alerts.show(
                                                ZeronAlert(
                                                    title = RepoPickerCopy.AddFailed,
                                                    message = CloudStore.message(e),
                                                    buttons = listOf(AlertButton("OK", AlertButtonRole.Default)),
                                                ),
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    }
                    item(key = "footer") { GroupedFooter(RepoPickerCopy.Footer) }
                }
            }
        }
    }
    if (connecting) GitHubConnectSheet(onDismissed = { connecting = false })
}

@Composable
private fun RowSpinner() {
    Box(Modifier.padding(end = 16.dp)) { ActivitySpinner(ZeronTheme.palette.secondary) }
}

/** The pinned search field ("Search repositories"). */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.controlFill)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ZeronIcons.Magnifyingglass, palette.secondary, 14f, SymbolWeight.Medium)
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = uiStyle(Face.Sans, ts.fixed(16f), palette.text),
            cursorBrush = SolidColor(palette.accent),
            modifier = Modifier.weight(1f).testTag("repo-search"),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        BasicText(RepoPickerCopy.Search, style = uiStyle(Face.Sans, ts.fixed(16f), palette.tertiary), maxLines = 1)
                    }
                    inner()
                }
            },
        )
    }
}

/** The empty page: a spinner; the failed read with Try again; or the tile and copy (and Connect GitHub). */
@Composable
private fun EmptyState(state: RepoPickerEmpty, onRetry: () -> Unit, onConnect: () -> Unit) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(bottom = 80.dp).testTag("repo-picker-empty"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state == RepoPickerEmpty.Loading) {
                ActivitySpinner(palette.secondary)
                return@Column
            }
            if (state != RepoPickerEmpty.Failed) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(52.dp * 0.28f))
                        .background(palette.controlFill),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(ZeronIcons.GithubMark, tint = palette.text, sizePt = 26f)
                }
            }
            val (title, body) = when (state) {
                RepoPickerEmpty.Failed -> RepoPickerCopy.LoadFailed to RepoPickerCopy.LoadFailedDetail
                RepoPickerEmpty.NoRepos -> RepoPickerCopy.NoRepos to RepoPickerCopy.NoReposDetail
                else -> RepoPickerCopy.Connect to RepoPickerCopy.ConnectDetail
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicText(
                    title,
                    Modifier.fillMaxWidth(),
                    style = uiStyle(Face.SansSemibold, ts.fixed(28f), palette.text).copy(textAlign = TextAlign.Center),
                )
                BasicText(
                    body,
                    Modifier.fillMaxWidth(),
                    style = uiStyle(Face.Sans, ts.fixed(16f), palette.secondary).copy(textAlign = TextAlign.Center),
                )
            }
            when (state) {
                RepoPickerEmpty.Failed -> Capsule(
                    title = RepoPickerCopy.Retry,
                    fill = palette.controlFill,
                    ink = palette.text,
                    face = Face.SansMedium,
                    size = 16f,
                    horizontal = 20.dp,
                    vertical = 14.dp,
                    tag = "repo-retry",
                    leading = ZeronIcons.Refresh,
                    onClick = onRetry,
                )
                RepoPickerEmpty.Connect -> Capsule(
                    title = RepoPickerCopy.Connect,
                    fill = palette.text,
                    ink = palette.background,
                    face = Face.SansSemibold,
                    size = 17f,
                    horizontal = 20.dp,
                    vertical = 15.dp,
                    tag = "repo-connect",
                    onClick = onConnect,
                )
                else -> Unit
            }
        }
    }
}
