package sh.zeron.android.newsession

import sh.zeron.android.design.modalLayerBackground
import sh.zeron.android.design.ModalLayer
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import sh.zeron.android.cloud.CloudRepoPickerSheet
import sh.zeron.android.cloud.CloudRoutes
import sh.zeron.android.cloud.rememberBillingOpener
import sh.zeron.android.composer.HOST_SYMBOL_COMPUTER
import sh.zeron.android.core.Face
import sh.zeron.android.design.CapsuleShape
import sh.zeron.android.design.LocalFeedback
import sh.zeron.android.design.LocalToaster
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.glass.ActivitySpinner
import sh.zeron.android.design.uiStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import sh.zeron.android.ZeronApplication
import sh.zeron.android.composer.ComposerBar
import sh.zeron.android.composer.ComposerController
import sh.zeron.android.composer.ModelPickerController
import sh.zeron.android.composer.ModelPickerOverlay
import sh.zeron.android.composer.SendResult
import sh.zeron.android.composer.StagedImage
import sh.zeron.android.composer.rememberComposerController
import sh.zeron.android.composer.rememberComposerMorphState
import sh.zeron.android.design.LocalAlerts
import sh.zeron.android.notify.NotificationPermission
import sh.zeron.android.notify.findActivity
import sh.zeron.android.shell.DraftHandoff
import sh.zeron.android.shell.LocalPageSheetScope

/**
 * The New Session page (android-ui-parity #93) — the port of
 * `NewSessionViewController` (`ios:Session/NewSessionViewController.swift`),
 * a composer-first canvas: "tap, type, send".
 *
 * Phone: the content of 31's large-detent page sheet (32 presents it — the
 * ✕ is the sheet bar's). Tablet (106): embedded in the detail column with
 * `embedded = true`, no ✕, and the caller's one-shot [focusOnAppear].
 *
 * This entry builds 92's page model over the app's single graph; everything
 * after that lives in [NewSessionScreenBody] so tests can drive the real
 * page over a fake model (the built-in catalog is FFI — a fake catalog
 * keeps the JVM honest, as in 92's model tests).
 *
 * [onCreated] is 94's: it fires after the create + first send, with the
 * captured handoff (or null when the page left composition mid-flight).
 */
@Composable
fun NewSessionScreen(
    prompt: String?,
    embedded: Boolean,
    onCreated: (chatId: String, handoff: DraftHandoff?) -> Unit,
    onClose: () -> Unit = {},
    /** iOS `!embedded`; 106 passes its one-shot flag (launch false, ⌘N true). */
    focusOnAppear: Boolean = !embedded,
    /** The `@file` search seam; tests pass a fake. */
    fileSearch: NewSessionFileSearch = rememberCoreFileSearch(),
    modifier: Modifier = Modifier,
) {
    val ns = rememberNewSessionModel(prompt)
    NewSessionScreenBody(ns, embedded, onCreated, onClose, focusOnAppear, fileSearch, modifier)
}

/**
 * The page over a built model: the composer (created with 92's
 * `initialText` and seeded with its `initialImages` **before the first
 * frame**, like iOS's `init`), 92's remember-on-close/background effect,
 * the morph pinned to the card (`alwaysCard`), the focus-on-appear wait,
 * the canvas — and 94's create + first send: the handoff capture state
 * on the page and composer roots and the `onSend` handler that creates the chat,
 * captures the handoff and calls `onCreated`.
 */
@Composable
internal fun NewSessionScreenBody(
    ns: NewSessionModel,
    embedded: Boolean,
    onCreated: (chatId: String, handoff: DraftHandoff?) -> Unit = { _, _ -> },
    onClose: () -> Unit,
    focusOnAppear: Boolean,
    fileSearch: NewSessionFileSearch,
    modifier: Modifier = Modifier,
    // The chip title chain's labels: the core's catalog in the app; tests and
    // previews pass GalleryLabels to stay FFI-free (FakeCatalog's intent).
    labels: ChipLabels = CoreChipLabels,
) {
    val composer = rememberComposerController(initialText = ns.initialText)
    val morph = rememberComposerMorphState(
        alwaysCard = true,
        hasImages = { composer.images.isNotEmpty() },
        hideSuggestions = { composer.hideSuggestions() },
    )

    // ── 94: the handoff capture and the send handler ───────────────────

    // The capture state: the page root records the scene, the composer
    // root records the composer. The sheet's own shape (31's 38 dp top
    // corners) rides into the record — 95 clips the scene picture with
    // it; the embedded column has no sheet scope, so a rectangle. A
    // plain field, set in composition, before the first draw.
    val capture = rememberHandoffCaptureState()
    capture.sceneShape = LocalPageSheetScope.current?.pageShape ?: RectangleShape

    // The send handler: 92's page model, the core client (71's holder —
    // `null` before the startup load finishes fails exactly like iOS's
    // `guard let client`), the ambient alerts, the activity for the
    // one-time notification ask (android-app#28) and the capture.
    val alerts = LocalAlerts.current
    val context = LocalContext.current
    val app = context.applicationContext as ZeronApplication
    // Unwrap the ContextWrapper chain to the activity (the ask needs a
    // ComponentActivity; a preview context unwraps to null and the ask
    // is skipped).
    val activity = remember(context) { context.findActivity() as? ComponentActivity }
    val latestOnCreated by rememberUpdatedState(onCreated)
    // #53: the out-of-credits toast's "Add credits" opens Billing — #63: in
    // a sheet stacked on this one, so the draft stays where it is.
    val toaster = LocalToaster.current
    val feedback = LocalFeedback.current
    val openBilling by rememberUpdatedState(rememberBillingOpener())
    val handler = remember {
        val core = CoreNewSessionCore(client = { app.clientOrNull() }, workspaceChanged = { app.workspaceFeed.refresh() })
        NewSessionSendHandler(
            page = ModelNewSessionPage(ns),
            creator = NewSessionCreator(core),
            alerts = alerts,
            askAfterFirstSession = {
                activity?.let { target -> NotificationPermission.askAfterFirstSession(target) }
            },
            capture = capture::capture,
            onCreated = { chatId, handoff -> latestOnCreated(chatId, handoff) },
            cloudCreator = CloudSessionCreator(core),
            onOutOfCredits = { CloudRoutes.showOutOfCredits(toaster, feedback) { openBilling() } },
        )
    }

    // #37: the shared tabbed picker the model chip opens (iOS
    // `composer.onChipTap` → `modelPicker()`), hosted over the canvas.
    val modelPicker = remember { ModelPickerController() }
    remember(composer) {
        // Runs once, before the first frame (iOS sets these in `init` and
        // `viewDidLoad`).
        bindNewSessionComposer(composer, project = { ns.project }, ns.initialImages, fileSearch)
        // The model chip has no menu, so its tap lands here.
        composer.onChipTap = { id ->
            if (id == NewSessionChipIds.Model) {
                modelPicker.present(
                    newSessionModelPicker(
                        models = ns.models,
                        draft = ns.draft,
                        hostName = if (ns.runsOnCloud) {
                            CLOUD_HOST_NAME
                        } else {
                            newSessionPickerHostName(
                                ns.project,
                                ns.currentOptions?.hosts.orEmpty(),
                                ns.draft.hostId,
                            )
                        },
                        hostSymbol = if (ns.runsOnCloud) CLOUD_HOST_SYMBOL else HOST_SYMBOL_COMPUTER,
                        update = ns::update,
                    ),
                )
            }
        }
        // 94: the real send handler replaces the `Kept` stub the bind
        // installs for direct-bind tests.
        composer.onSend = { text, images, _ -> handler.send(text, images) }
        true
    }
    NewSessionDraftEffect(ns, composer)
    // 109: the chip strip's data. 113: the project / run-on menu map —
    // the page's liveness guard (the iOS [weak self] of the browser's
    // onCreated), 110's presenter seam, and the menu glue over 92's
    // model, whose providers read the live model at tap time. 114: the
    // checkout / model / effort map (refs through 71's client).
    val alive = remember { AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { alive.set(false) } }
    val newProjectPresenter = rememberNewProjectPresenter()
    // #53: "New Cloud Project…" opens the repo picker over the page; its
    // `onCreated(id)` picks the new project on Cloud.
    var cloudRepoPicker by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val projectMenus = remember(ns, newProjectPresenter) {
        ProjectRunOnMenus(ns, newProjectPresenter, alive::get) { onCreated ->
            morph.blur()
            cloudRepoPicker = onCreated
        }
    }
    val refSource = remember { CoreRefSource(client = { app.clientOrNull() }) }
    val checkoutMenus = remember(ns) {
        CheckoutModelEffortMenus(
            draft = { ns.draft },
            project = { ns.project },
            selectedModel = { ns.selectedModel },
            update = ns::update,
            refSource = refSource,
            runsOnCloud = { ns.runsOnCloud },
            cloudBranches = { repo -> app.clientOrNull()?.githubBranches(repo) ?: emptyList() },
        )
    }
    val chipMenus = remember(projectMenus, checkoutMenus) {
        newSessionChipMenus(projectMenus.providers(), checkoutMenus.providers())
    }
    NewSessionChipsEffect(ns, composer, chipMenus, labels)
    val keyboard = LocalSoftwareKeyboardController.current
    val canvasState = remember { NewSessionCanvasState() }
    NewSessionFocusOnAppear(focusOnAppear, embedded, canvasState) { morph.focus() }
    // #63: the model picker over the page hides it from TalkBack while up.
    ModalLayer {
    Box(modifier) {
        NewSessionCanvas(
            harness = ns.draft.harness,
            embedded = embedded,
            onClose = onClose,
            onBackgroundTap = { morph.blur(); keyboard?.hide() },
            composer = {
                // #53: while a Cloud machine is minted the composer is
                // locked and "Starting on Cloud…" sits above it.
                Box {
                    ComposerBar(
                        composer,
                        morph,
                        Modifier
                            .testTag("new-session-composer")
                            .handoffComposer(capture)
                            .then(if (ns.cloudStarting) Modifier.lockedForCloud() else Modifier),
                        textHidden = capture.textHidden,
                        onTextRect = capture::onTextRect,
                    )
                    if (ns.cloudStarting) {
                        CloudStartingHud(
                            Modifier
                                .align(Alignment.TopCenter)
                                .layout { m, c ->
                                    val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
                                    // Above the composer, 10 dp clear of its top.
                                    layout(c.maxWidth, 0) { p.place((c.maxWidth - p.width) / 2, -p.height - 10.dp.roundToPx()) }
                                },
                        )
                    }
                }
            },
            state = canvasState,
            // 94: the page root records the scene (its Layout holds the
            // backdrop, the nav bar and the composer; 31's grabber stays
            // outside). The caller's modifier sits on the Box around it, which
            // also hosts the model picker over the page (#37).
            modifier = Modifier.handoffScene(capture).then(modalLayerBackground()),
        )
        ModelPickerOverlay(modelPicker)
        cloudRepoPicker?.let { onCreated ->
            CloudRepoPickerSheet(onDismissed = { cloudRepoPicker = null }, onCreated = onCreated)
        }
    }
    }
}

/**
 * The canvas's composer configuration — the `viewDidLoad` block
 * (`NewSessionViewController.swift:145-165`) as one idempotent bind, so
 * the screen's `remember(composer)` and the tests share the exact rules:
 *
 * - `chipsAlwaysVisible` (always a card here), the placeholder, the seed
 *   images;
 * - `onChipTap = { _, _ in }`: chips open their own menus (109/113/114);
 *   the screen then routes the menu-less `model` chip to the picker (#37);
 * - `attachMenu` stays **null**: 87's `ComposerAttachMenuButton` inside the
 *   shared `ComposerBar` already builds the menu per open with
 *   `limit = 8 − images.size`;
 * - `mentionSearch` is **always** set — `AppModel.searchFiles` with the
 *   project read **at call time** (`self.project`, `:236`), `chatId = null`,
 *   and errors read as no matches (the `try?` of `AppModel.swift:607-610`).
 *   Cancellation still propagates: 86 uses it to drop stale queries;
 * - the `Kept` send stub, which the screen overwrites with 94's real
 *   handler (direct-bind tests keep the stub).
 */
internal fun bindNewSessionComposer(
    composer: ComposerController,
    project: () -> ProjectOption?,
    initialImages: List<StagedImage>,
    fileSearch: NewSessionFileSearch,
) {
    composer.alwaysCard = true
    composer.placeholder = "Describe the task"
    composer.images = initialImages
    composer.onChipTap = {}
    composer.mentionSearch = { q ->
        try {
            project()?.let { p -> fileSearch.search(p.device, p.id, q) } ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
    }
    if (composer.onSend == null) composer.onSend = { _, _, _ -> SendResult.Kept }
}

/**
 * Focus on appear (`viewDidAppear`, `:215-217`): the sheet waits for the
 * present motion to settle (Compose has no appear callback tied to the
 * transition — 31 slides the page with `Modifier.offset`, so
 * `positionInWindow` stops moving), the embedded column takes the keyboard
 * on the first frame. One-shot per screen instance — a plain
 * `LaunchedEffect(Unit)`, never re-run on recomposition, harness change or
 * a stacked sheet's return.
 */
@Composable
internal fun NewSessionFocusOnAppear(
    focusOnAppear: Boolean,
    embedded: Boolean,
    state: NewSessionCanvasState,
    focus: () -> Unit,
) {
    LaunchedEffect(Unit) {
        if (!focusOnAppear) return@LaunchedEffect
        if (embedded) withFrameNanos { } else awaitPlacementSettled { state.pageCoordinates }
        focus()
    }
}

/**
 * Returns once the page's window position is non-null and unchanged for
 * two consecutive frames, or after [SettleTimeoutNanos] (a never-settling
 * host). `withFrameNanos` ticks on every vsync, so the two still frames
 * arrive after the sheet's spring rests.
 */
internal suspend fun awaitPlacementSettled(coords: () -> LayoutCoordinates?) {
    var last: Float? = null
    var stable = 0
    var started = -1L
    while (true) {
        val now = withFrameNanos { it }
        if (started < 0L) started = now
        val y = coords()?.takeIf { it.isAttached }?.positionInWindow()?.y
        if (y != null && y == last && ++stable >= 2) return
        if (y != last) stable = 0
        last = y
        if (now - started > SettleTimeoutNanos) return
    }
}

/** The settle cap: 1000 ms of frames, against a never-settling host. */
private const val SettleTimeoutNanos = 1_000_000_000L

/** The Model picker's host line for a Cloud draft (iOS `(name: "Cloud", symbol: "cloud")`). */
const val CLOUD_HOST_NAME = "Cloud"

/** The Cloud host's SF symbol. */
const val CLOUD_HOST_SYMBOL = "cloud"

/** #53: the composer takes no touches while its Cloud start runs (iOS `isUserInteractionEnabled = false`). */
private fun Modifier.lockedForCloud(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/** "Starting on Cloud…" above the composer while the machine is minted (iOS `CloudStartingHUD`). */
@Composable
internal fun CloudStartingHud(modifier: Modifier = Modifier) {
    val palette = ZeronTheme.palette
    Row(
        modifier
            .clip(CapsuleShape)
            .background(palette.elevated)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("cloud-starting"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActivitySpinner(palette.secondary)
        BasicText("Starting on Cloud…", style = uiStyle(Face.SansMedium, LocalTypeScale.current.fixed(14f), palette.text))
    }
}
