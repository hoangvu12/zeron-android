package sh.zeron.android.sidechats

import android.content.ClipboardManager
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.zeron.android.ZeronApplication
import sh.zeron.android.core.TextEngine
import sh.zeron.android.design.LocalToaster
import sh.zeron.android.design.Motion
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.StatusGlyph
import sh.zeron.android.design.StatusGlyphKind
import sh.zeron.android.design.Toaster
import sh.zeron.android.design.glass.LocalHazeState
import sh.zeron.android.design.glass.ProvideHazeState
import sh.zeron.android.image.ImageViewerRequest
import sh.zeron.android.image.LocalImageViewer
import sh.zeron.android.session.SessionEventHub
import sh.zeron.android.session.SessionHeader
import sh.zeron.android.session.SessionHeaderDefaults
import sh.zeron.android.session.copyTranscript
import sh.zeron.android.shell.LocalNavEntry
import sh.zeron.android.shell.LocalNavStack
import sh.zeron.android.shell.NavEntry
import sh.zeron.android.shell.NavRoute
import sh.zeron.android.shell.NavStack
import sh.zeron.android.transcript.AttachmentReader
import sh.zeron.android.transcript.TranscriptActions
import sh.zeron.android.transcript.TranscriptHost
import sh.zeron.android.transcript.TranscriptImageBinding
import sh.zeron.android.transcript.TranscriptImageLoader
import sh.zeron.android.transcript.TranscriptState
import sh.zeron.android.transcript.rememberLinkRouter
import sh.zeron.android.transcript.transcriptBottomInset
import sh.zeron.android.transcript.widgets.imageTileRadius
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.TextSystem

/** The viewer's copy (iOS `SubagentViewController`). */
object SubagentCopy {
    const val Subagent = "Subagent"
    const val OpenFailed = "Couldn't open this subagent."
    const val CopyTranscript = "Copy transcript"
    const val TranscriptCopied = "Transcript copied"
}

/**
 * The spawn's record on the root chat (`SessionHandle.subagents()`),
 * reduced to what the screen shows: its state, the "model · kind" detail
 * and when it started.
 */
data class SpawnStatus(val state: AgentState, val detail: String, val startedAtMs: Long)

/**
 * A subagent's transcript, read-only (android-next #06, the port of
 * upstream #899's `SubagentViewController`): the doc a spawn card links
 * to. Zeron's engine serves that doc itself — the same
 * `WatchDocMessages` stream desktop's subagent tab subscribes to
 * (`crates/ui` `watch_subagent_doc`), never #899's edge blob — so the
 * transcript is live while the subagent runs, including the claude-code
 * `run_in_background` shape whose spawn call resolved long before the
 * subagent finished, stops changing once it is done, and streams again if
 * it resumes. No composer: the parent agent drives the subagent.
 *
 * [spawn] follows the spawn's record on the root chat ([rootChatId]),
 * re-read on that chat's `SessionChanged` (iOS `observeSession(rootChatId)`
 * → `syncStatus`); [state] is its state, or the card's until one is read.
 *
 * Built off main by [buildSubagentRoute]; the transcript state is made on
 * the first main-thread read. [start] runs on first composition and
 * [release] once, through the entry's close safety net (a committed pop).
 */
@Stable
class SubagentRoute internal constructor(
    /** The subagent's doc id (scoped): the read-only session's key. */
    val docId: String,
    /** The spawn's description — the bar title. */
    val title: String,
    /** The transcript whose spawn chip this is (its chat, or the subagent above). */
    val spawnerId: String,
    /**
     * The chat at the root of the spawn chain ([spawnerId] itself unless
     * this is a nested spawn): relative image references resolve against
     * its cwd, which a subagent doc id has none of.
     */
    val rootChatId: String,
    /** The spawner's display title, for the subtitle. */
    val parentTitle: String,
    /** The engine device that serves attachments (`composer.host.deviceId`). */
    val hostDeviceId: String,
    private val client: CoreClient,
    private val hub: SessionEventHub,
    /** The app-lifetime scope teardown and state reads ride on (never the entry's). */
    private val appScope: CoroutineScope,
    private val text: TextSystem,
    initialState: AgentState,
    /** One serial lane per viewer: attach, the state reads and detach keep their order. */
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : NavRoute {

    override val hidesBottomChrome: Boolean get() = true

    override val paneTitle: String get() = title

    /** The subagent's lifecycle, as its spawn record says. Snapshot state (main). */
    var state: AgentState by mutableStateOf(initialState)
        private set

    /** The spawn's record on the root chat; `null` until read (or when it has none). Main. */
    var spawn: SpawnStatus? by mutableStateOf(null)
        private set

    /** The attach returned false (the doc can't be followed): the bar says so. Main. */
    var attachFailed: Boolean by mutableStateOf(false)
        private set

    private var transcriptImpl: TranscriptState? = null

    /** The layout engine. MAIN THREAD ONLY: created on first read. */
    val transcript: TranscriptState
        get() = transcriptImpl ?: TranscriptState(text).also { transcriptImpl = it }

    private var started = false
    private var released = false
    private var unregister: (() -> Unit)? = null

    /**
     * First composition (main): bind the layout engine to the subagent's
     * doc read-only (`TranscriptView.attachSubagent`, iOS
     * `SubagentViewController.swift:86`) on the serial lane, then follow
     * the spawn's record. An attach that returns false (or throws) shows
     * the unavailable bar (iOS `showUnavailable`).
     */
    internal fun start() {
        if (started || released) return
        started = true
        val view = transcript.engine
        val frozen = state != AgentState.Running
        appScope.launch(io) {
            val attached = try {
                view.attachSubagent(client, spawnerId, docId, frozen).also {
                    if (!it) Log.w(TAG, "subagent transcript attach returned false")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "subagent attach failed", t)
                false
            }
            if (!attached) withContext(Dispatchers.Main) { if (!released) attachFailed = true }
        }
        unregister = hub.register(rootChatId) { refreshState() }
        refreshState()
    }

    /** Re-read the spawn's record on the root chat off main; publish on main. */
    private fun refreshState() {
        if (released) return
        appScope.launch(io) {
            val next = try {
                // A fresh wrapper per read: close it here (the core keeps the
                // session itself warm), never leave it to the GC.
                client.session(rootChatId)?.let { root ->
                    try {
                        root.subagents().firstOrNull { it.docId == docId }?.let {
                            SpawnStatus(agentState(it.state), SubagentFormat.detail(it.model, it.kind), it.startedAtMs)
                        }
                    } finally {
                        root.close()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "subagent state read failed", t)
                null
            } ?: return@launch
            withContext(Dispatchers.Main) {
                if (!released) {
                    spawn = next
                    state = next.state
                }
            }
        }
    }

    /**
     * The committed pop (the entry's close safety net): stop following the
     * chip and close the layout engine (main), which drops the subagent
     * follow. Idempotent.
     */
    fun release() {
        if (released) return
        released = true
        unregister?.invoke()
        unregister = null
        transcriptImpl?.close()
    }

    /** The image reader for the transcript (the host's `readAttachment`). */
    internal val attachments: AttachmentReader = object : AttachmentReader {
        override suspend fun read(engineDeviceId: String, reference: String): ByteArray =
            client.readAttachment(engineDeviceId, reference)
    }

    @Composable
    fun Content() {
        val entry = LocalNavEntry.current
        // The close safety net (the NavEntry contract): any removal path
        // releases exactly once.
        remember(entry) {
            entry.retain(CloseKey) {
                object : AutoCloseable {
                    override fun close() = release()
                }
            }
        }
        remember(entry) {
            start()
            true
        }
        SubagentScreen(this)
    }

    private object CloseKey

    private companion object {
        const val TAG = "ZeronSubagent"
    }
}

/** What a spawn card (or a sheet row) asks to open. */
data class SubagentOpenRequest(
    val docId: String,
    val title: String,
    /** The card said it had finished (done or failed). */
    val frozen: Boolean,
    /** The transcript the card sits in: the chat, or the subagent above. */
    val spawnerId: String,
    val parentTitle: String,
    /** The chat at the root of the spawn chain (relative images resolve in its cwd). */
    val rootChatId: String = spawnerId,
)

/**
 * Off main: build the subagent's route (the transcript attaches to the
 * doc on first composition). `null` when there is no client.
 */
suspend fun buildSubagentRoute(
    app: ZeronApplication,
    request: SubagentOpenRequest,
    io: CoroutineDispatcher = Dispatchers.IO,
): SubagentRoute? = withContext(io) {
    try {
        val client = app.clientOrNull() ?: return@withContext null
        val host = try {
            client.sessionRow(request.rootChatId)?.deviceId.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            ""
        }
        SubagentRoute(
            docId = request.docId,
            title = request.title,
            spawnerId = request.spawnerId,
            rootChatId = request.rootChatId,
            parentTitle = request.parentTitle,
            hostDeviceId = host,
            client = client,
            hub = app.sessionHolder.sessionEvents,
            appScope = app.appScope,
            text = TextEngine.shared,
            initialState = if (request.frozen) AgentState.Done else AgentState.Running,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        Log.w("ZeronSubagent", "open subagent failed", t)
        null
    }
}

/**
 * Opens subagent viewers over one screen (the session, or a viewer for a
 * nested spawn): the doc opens off main, then the route is pushed — only
 * if this screen is still on top and idle (a late open never pushes over
 * a screen the user already left, the `leaveAfterArchive` rule). One open
 * at a time; a failed open toasts "Couldn't open this subagent."
 */
class SubagentOpener(
    private val app: ZeronApplication,
    private val nav: NavStack,
    private val entry: NavEntry,
    private val scope: CoroutineScope,
    private val toaster: Toaster,
) {
    private var opening = false

    /** Main thread. */
    fun open(request: SubagentOpenRequest) {
        if (opening) return
        opening = true
        scope.launch {
            try {
                val route = buildSubagentRoute(app, request)
                when {
                    route == null -> toaster.show(SubagentCopy.OpenFailed)
                    nav.top !== entry || nav.transition != null -> route.release()
                    else -> nav.push(route, animated = true)
                }
            } finally {
                opening = false
            }
        }
    }
}

/**
 * The opener for the screen composing this (the [LocalNavStack] /
 * [LocalNavEntry] it lives in); `null` outside the app (previews, tests).
 */
@Composable
fun rememberSubagentOpener(): SubagentOpener? {
    val app = LocalContext.current.applicationContext as? ZeronApplication ?: return null
    val nav = LocalNavStack.current
    val entry = LocalNavEntry.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    return remember(app, nav, entry, toaster, scope) { SubagentOpener(app, nav, entry, scope, toaster) }
}

/**
 * The viewer screen (iOS `SubagentViewController`): the read-only
 * transcript under the session header (back, the spawn's title over
 * "Subagent · <model · kind>", a Copy circle), the 1.75× spinner until the
 * first row lands and then a 0.2 s fade-in, and the glass status bar at the
 * bottom with the transcript inset above it. Nothing within 10 s (or an
 * attach that failed) turns the bar into "Waiting for its computer…".
 * Nested spawn cards open their own viewer on top.
 */
@Composable
internal fun SubagentScreen(route: SubagentRoute, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val palette = ZeronTheme.palette
    val toaster = LocalToaster.current
    val transcript = route.transcript
    val scope = rememberCoroutineScope()
    val opener = rememberSubagentOpener()

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    SideEffect { transcript.topInset = SessionHeaderDefaults.contentTopInset(statusBarTop).value }
    val edgeEngaged by remember(transcript) { derivedStateOf { transcript.viewport.scrollY > 0.5f } }
    val revealed by remember(transcript) { derivedStateOf { (transcript.frame?.rowCount() ?: 0u) > 0u } }
    var gaveUp by remember(route) { mutableStateOf(false) }
    LaunchedEffect(route) {
        delay(GiveUpMs)
        gaveUp = true
    }
    // iOS `UIView.animate(withDuration: 0.2)`: the transcript in, the
    // spinner out, together.
    val shown by animateFloatAsState(
        if (revealed) 1f else 0f,
        tween(RevealMs, easing = Motion.EaseInOut),
        label = "subagentReveal",
    )

    val spawn = route.spawn
    val unavailable = !revealed && (gaveUp || route.attachFailed)
    val barState = if (unavailable) {
        SubagentBarState.Unavailable
    } else {
        when (spawn?.state ?: route.state) {
            AgentState.Running -> SubagentBarState.Working
            AgentState.Done -> SubagentBarState.Done
            AgentState.Failed -> SubagentBarState.Failed
        }
    }
    val detail = spawn?.detail.orEmpty()

    // The transcript's actions — SessionScreen's image and link wiring
    // over this doc's host device, plus nested subagent cards.
    val viewer = LocalImageViewer.current
    val density = LocalDensity.current
    val binding = remember(route) {
        TranscriptImageBinding(
            loader = TranscriptImageLoader.get(context),
            engineDeviceId = { route.hostDeviceId },
            reader = route.attachments,
            retries = (context.applicationContext as? ZeronApplication)
                ?.workspaceFeed?.reconnects ?: emptyFlow(),
            chatId = route.rootChatId,
        )
    }
    val router = rememberLinkRouter(
        loadFull = { reference -> binding.loadFull(reference) },
        showImage = { reference, source, bitmap ->
            viewer?.show(
                ImageViewerRequest(
                    reference = reference,
                    preview = bitmap,
                    sourceBounds = source,
                    sourceCornerRadius = 0.dp,
                    label = null,
                    loadFull = null,
                ),
            )
        },
    )
    val actions = remember(route, viewer, router, opener) {
        val openImage: (String, Rect, ImageBitmap?, String?) -> Unit = { reference, source, bitmap, label ->
            viewer?.show(
                ImageViewerRequest(
                    reference = reference,
                    preview = bitmap,
                    sourceBounds = source,
                    sourceCornerRadius = with(density) {
                        imageTileRadius(source.width.toDp().value, source.height.toDp().value)
                    },
                    label = label,
                    loadFull = { binding.loadFull(reference) },
                ),
            )
        }
        TranscriptActions(
            openLink = router::open,
            openImage = openImage,
            loadImage = binding::load,
            cachedImage = binding::cached,
            imageRetries = binding.retries,
            openSubagent = { docId, title, frozen ->
                opener?.open(
                    SubagentOpenRequest(
                        docId = docId,
                        title = title,
                        frozen = frozen,
                        spawnerId = route.docId,
                        parentTitle = route.title,
                        rootChatId = route.rootChatId,
                    ),
                )
            },
        )
    }

    Box(
        modifier
            .fillMaxSize()
            .background(palette.background)
            .testTag("subagent-viewer"),
    ) {
        ProvideHazeState {
            val haze = LocalHazeState.current
            // The transcript is always composed (rows lay out and the tail
            // is placed while hidden); it fades in with its first row.
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (haze != null) Modifier.hazeSource(haze) else Modifier)
                    .graphicsLayer { alpha = shown },
            ) {
                TranscriptHost(
                    transcript,
                    actions,
                    Modifier
                        .fillMaxSize()
                        // The bar covers 8 + 50 + 8 dp above the navigation bar.
                        .transcriptBottomInset(transcript.viewport, navBarBottom + BarCovered)
                        .testTag("subagent-transcript"),
                )
            }
            if (!unavailable && shown < 1f) {
                // Centred in the safe area, 40 dp up (iOS).
                StatusGlyph(
                    StatusGlyphKind.Spinner,
                    Modifier
                        .align(Alignment.Center)
                        .offset(y = (statusBarTop - navBarBottom) / 2 - 40.dp)
                        .graphicsLayer { alpha = 1f - shown }
                        .testTag("subagent-loading"),
                    scale = 1.75f,
                )
            }
            SubagentStatusBar(
                state = barState,
                detail = detail,
                startedAtMs = spawn?.startedAtMs,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = navBarBottom + 8.dp),
            )
            SessionHeader(
                title = route.title,
                subtitle = SubagentFormat.subtitle(detail),
                edgeEngaged = edgeEngaged,
                onMore = {
                    scope.launch {
                        val clipboard = context.applicationContext
                            .getSystemService(ClipboardManager::class.java) ?: return@launch
                        copyTranscript(transcript, clipboard, toaster, copiedMessage = SubagentCopy.TranscriptCopied)
                    }
                },
                statusBarTop = statusBarTop,
                moreIcon = ZeronIcons.DocOnDoc,
                moreLabel = SubagentCopy.CopyTranscript,
            )
        }
    }
}

/** iOS `asyncAfter(deadline: .now() + 10)`: the bar says it's waiting for its computer. */
private const val GiveUpMs = 10_000L

/** iOS's reveal: `UIView.animate(withDuration: 0.2)`. */
private const val RevealMs = 200

/** What the status bar covers above the navigation bar: 8 dp gap, 50 dp bar, 8 dp gap (iOS `covered`). */
private val BarCovered = 66.dp
