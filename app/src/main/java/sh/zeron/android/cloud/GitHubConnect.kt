package sh.zeron.android.cloud

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import sh.zeron.android.ZeronApplication
import sh.zeron.android.composer.openInCustomTab
import sh.zeron.android.core.CloudStore
import sh.zeron.android.core.Face
import sh.zeron.android.design.Haptic
import sh.zeron.android.design.Icon
import sh.zeron.android.design.LocalFeedback
import sh.zeron.android.design.LocalTypeScale
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
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.CoreException
import uniffi.zeron_core.GithubConnectInfo
import uniffi.zeron_core.GithubDeviceFlowInfo

/** The device-flow calls behind Connect GitHub; a seam so the flow is tested without the core. */
interface GitHubFlowApi {
    suspend fun start(): GithubDeviceFlowInfo

    suspend fun poll(flowId: String): GithubConnectInfo
}

/** The live [GitHubFlowApi] over the account's client (`null` = signed out). */
class CoreGitHubFlowApi(private val client: () -> CoreClient?) : GitHubFlowApi {
    override suspend fun start(): GithubDeviceFlowInfo = (client() ?: throw CoreException.Closed()).githubConnectStart()

    override suspend fun poll(flowId: String): GithubConnectInfo =
        (client() ?: throw CoreException.Closed()).githubConnectPoll(flowId)
}

/** The sheet's copy (iOS, verbatim). */
object GitHubConnectCopy {
    const val Title = "Connect GitHub"
    const val Body = "Enter this code on GitHub to let Cloud agents clone your repositories and push their work."
    const val NoCode = "· · · ·"
    const val GettingCode = "Getting a code…"
    const val Waiting = "Waiting for GitHub…"
    const val Failed = "GitHub didn't connect. Try again."
    const val Connected = "Connected"
    const val Open = "Copy Code & Open GitHub"

    fun signedIn(account: String?) = account?.let { "Signed in to GitHub as @$it." } ?: "GitHub is connected."
}

/**
 * GitHub's device flow for Cloud (#51; iOS `GitHubConnectViewController`):
 * a code, then a poll every `max(interval, 2)` s until GitHub says
 * connected or failed. Main thread; [run] is cancelled with its caller
 * (iOS cancels the poll on `deinit`).
 */
@Stable
class GitHubConnectModel(
    private val api: GitHubFlowApi,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    var code by mutableStateOf(GitHubConnectCopy.NoCode); private set
    var status by mutableStateOf<String?>(null); private set

    /** The status reads in the danger colour (an error). */
    var failed by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var flow by mutableStateOf<GithubDeviceFlowInfo?>(null); private set
    var connected by mutableStateOf(false); private set

    /** Gets a code and follows the approval; returns once connected (true) or failed (false). */
    suspend fun run(): Boolean {
        setStatus(GitHubConnectCopy.GettingCode, busy = true)
        val f = try {
            api.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setStatus(CloudStore.message(e), busy = false, failed = true)
            return false
        }
        flow = f
        code = f.userCode
        setStatus(GitHubConnectCopy.Waiting, busy = true)
        val interval = maxOf(f.intervalSecs.toLong(), 2L) * 1_000L
        while (true) {
            sleep(interval)
            val progress = try {
                api.poll(f.flowId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            when (progress.state) {
                "connected" -> {
                    connected = true
                    code = GitHubConnectCopy.Connected
                    setStatus(GitHubConnectCopy.signedIn(progress.account), busy = false)
                    return true
                }
                "failed" -> {
                    setStatus(progress.error ?: GitHubConnectCopy.Failed, busy = false, failed = true)
                    return false
                }
            }
        }
    }

    private fun setStatus(text: String, busy: Boolean, failed: Boolean = false) {
        status = text
        this.busy = busy
        this.failed = failed
    }
}

/**
 * The Connect GitHub sheet (medium/large, grabber, Close): the GitHub
 * tile and header, the code big in a card, the status line, "Copy Code &
 * Open GitHub". Once connected: a success haptic, `refreshGitHub()`,
 * 0.9 s to read it, then the sheet closes and [onConnected] runs.
 */
@Composable
fun GitHubConnectSheet(onDismissed: () -> Unit, onConnected: (String?) -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as ZeronApplication
    val dark = ZeronTheme.isDark
    val feedback = LocalFeedback.current
    val model = remember { GitHubConnectModel(CoreGitHubFlowApi(app::clientOrNull)) }
    val connectedCallback by rememberUpdatedState(onConnected)
    GlassSheet(
        onDismiss = onDismissed,
        modifier = Modifier.testTag("github-connect"),
        state = rememberGlassSheetState(remember { listOf(SheetDetent.Medium, SheetDetent.Large) }),
        grabber = true,
        title = GitHubConnectCopy.Title,
        header = { SheetHeader("", leading = SheetLeading.Close) },
    ) { bottomInset ->
        val sheet = this
        LaunchedEffect(model) {
            if (model.run()) {
                feedback.haptic(Haptic.Success)
                app.cloud.refreshGitHub()
                delay(900)
                sheet.dismiss()
                connectedCallback(app.cloud.githubAccount)
            }
        }
        GitHubConnectContent(
            model = model,
            onOpen = {
                model.flow?.let { f ->
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Code", f.userCode))
                    openInCustomTab(context, f.verificationUri, dark)
                }
            },
            modifier = Modifier.padding(bottom = bottomInset + 24.dp),
        )
    }
}

@Composable
private fun GitHubConnectContent(model: GitHubConnectModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(56.dp * 0.28f))
                .background(palette.controlFill)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Icon(ZeronIcons.GithubMark, tint = palette.text, sizePt = 28f)
        }
        Spacer(Modifier.height(16.dp))
        BasicText(
            GitHubConnectCopy.Title,
            Modifier.fillMaxWidth().semantics { heading() },
            style = uiStyle(Face.SansSemibold, typeScale.fixed(28f), palette.text).copy(textAlign = TextAlign.Center),
        )
        Spacer(Modifier.height(10.dp))
        BasicText(
            GitHubConnectCopy.Body,
            Modifier.fillMaxWidth(),
            style = uiStyle(Face.Sans, typeScale.fixed(16f), palette.secondary).copy(textAlign = TextAlign.Center),
        )
        Spacer(Modifier.height(22.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(palette.elevated)
                .padding(horizontal = 16.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            val style = if (model.connected) {
                uiStyle(Face.SansSemibold, typeScale.fixed(26f), palette.success)
            } else {
                uiStyle(Face.MonoSemibold, typeScale.fixed(34f), palette.text)
            }
            BasicText(model.code, Modifier.testTag("github-code"), style = style.copy(textAlign = TextAlign.Center))
        }
        Spacer(Modifier.height(22.dp))
        val status = model.status
        if (status != null) {
            // #63: the status is announced as it changes ("Waiting…", "Connected").
            Row(
                Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (model.busy) ActivitySpinner(palette.secondary)
                BasicText(
                    status,
                    Modifier.testTag("github-status"),
                    style = uiStyle(Face.Sans, typeScale.fixed(14f), if (model.failed) palette.danger else palette.secondary)
                        .copy(textAlign = TextAlign.Center),
                )
            }
            Spacer(Modifier.height(22.dp))
        }
        if (!model.connected) {
            Capsule(
                title = GitHubConnectCopy.Open,
                fill = palette.text,
                ink = palette.background,
                face = Face.SansSemibold,
                size = 17f,
                horizontal = 20.dp,
                vertical = 15.dp,
                modifier = Modifier.fillMaxWidth(),
                enabled = model.flow != null,
                tag = "github-open",
                leading = ZeronIcons.ArrowUpRight,
                onClick = onOpen,
            )
        }
    }
}
