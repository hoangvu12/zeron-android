package sh.zeron.android.newsession

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.zeron.android.ZeronApplication
import sh.zeron.android.composer.ComposerController
import sh.zeron.android.composer.DraftKey
import sh.zeron.android.composer.LocalDrafts
import sh.zeron.android.composer.StagedImage

/**
 * The New Session page's state holder (android-ui-parity #92) — the port
 * of `NewSessionViewController`'s page state (`ios:Session/
 * NewSessionViewController.swift`), data and lifecycle only: the page
 * (93), the chips (109), the menus (113, 114) and create/send (94) read
 * and mutate the draft **only through this model**.
 *
 * The page works on a **copy** of [store]'s draft (iOS `self.draft =
 * app.lastDraft`): [update] is the only mutation path, and — the Zeron
 * adaptation — it persists every change, not only the close paths. The
 * text and the images live in the composer (93/94); [rememberDraft] is
 * the port of `viewWillDisappear` + `didEnterBackground`, [markCreated]
 * of the success path in `create` (options stay, text and images go).
 *
 * State is Compose snapshot state ([draft], [models], [currentOptions],
 * [created]); [project], [deviceId], [checkouts] and [selectedModel] are
 * plain getters over it, so chip and menu readers (109/113/114) recompose
 * when any input changes. Main thread only.
 *
 * The stale-id rule (Zeron addition): iOS keeps a vanished `projectId`
 * and fails at create; [normalize] nulls ids that are not in the merged
 * workspace (offline engines keep their cached rows, so offline ≠
 * stale) and re-applies the defaults. It runs at init, on the first
 * non-null options, and on **every later options change** (the tablet
 * case: a page embedded in the detail column stays open while Settings
 * forgets an engine) — it only ever acts on unknown ids or a fully empty
 * draft, so an explicit valid pick is never overridden.
 */
@Stable
class NewSessionModel(
    prompt: String?,
    private val store: NewSessionDraftStore,
    /** The options, mapped from ticket 40's `WorkspaceFeed.snapshot`; null until the first read. */
    private val options: StateFlow<NewSessionOptions?>,
    private val catalog: ModelCatalogSource,
    /** Main; cancelled on dispose — the options collection (never the fetches). */
    private val pageScope: CoroutineScope,
    /** The fetches run here (`ZeronApplication.appScope`), so they outlive the page. */
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    /** #53: what the page reads of Cloud (the app's [sh.zeron.android.core.CloudStore]). */
    val cloud: NewSessionCloud = NewSessionCloud.None,
) {

    /** The epoch the page opened with; a full reset while open freezes this page. */
    private val openedEpoch: Int = store.resetEpoch

    /** The page's copy of the sticky draft (iOS `self.draft`, `:75`). */
    var draft: NewSessionDraft by mutableStateOf(store.lastDraft)
        private set

    /** Models for the draft's device: cached or built-in at once, then the engine's own. */
    var models: List<ModelChoice> by mutableStateOf(emptyList())
        private set

    /** `prompt ?: store.text` — a non-null prompt (even `""`) wins over the saved text (`:78`). */
    val initialText: String = prompt ?: store.text

    /** The images staged when the page last closed unsent (`:79`). */
    val initialImages: List<StagedImage> = store.images

    /** Snapshot state; assigned by the options collector (109/113 read it in `derivedStateOf`). */
    var currentOptions: NewSessionOptions? by mutableStateOf(options.value)
        private set

    /** #53: a Cloud start is minting its machine ("Starting on Cloud…", the composer locked). */
    var cloudStarting: Boolean by mutableStateOf(false)

    /** True after a successful create: later saves keep nothing (`:81,415-418`). */
    var created: Boolean by mutableStateOf(false)
        private set

    /** The device whose models are in [models] (iOS `modelsDevice`, `:189`). */
    private var modelsDevice: String? = null

    /** Set by [dispose]: fetch results stop applying (the cache still fills). */
    private var disposed = false

    /**
     * True once the **user** picked something while the store's load was
     * still pending (normalize-driven changes do not count): the late
     * load then keeps the pick instead of replacing the draft.
     */
    private var touched = false

    init {
        // The defaults run once, here (iOS `viewDidLoad` `:177-181`) —
        // without a coroutine, so the first frame already has them.
        val initial = options.value
        if (initial != null) {
            val n = normalize(draft, initial)
            if (n != draft) updateInternal({ n }, userEdit = false)
        }
        loadModels()
        // #53: Cloud's status arrives later than the page (iOS observes the
        // store): a change in where the draft runs reloads the catalog.
        cloud.refreshIfUnread()
        pageScope.launch {
            snapshotFlow { runsOnCloud }.collect { loadModels() }
        }
        // Every options change re-runs the stale rule; the else branch
        // covers a project whose device changed.
        pageScope.launch {
            options.collect { o ->
                currentOptions = o
                if (o != null) {
                    val n = normalize(draft, o)
                    if (n != draft) updateInternal({ n }, userEdit = false) else loadModels()
                }
            }
        }
        // The late store load (the tablet launch page, 106, and a
        // `route new` cold start can open the page before `warm()`
        // finished): once it lands, an untouched page takes over the
        // stored draft; a touched one keeps the user's pick.
        if (!store.isLoaded) {
            pageScope.launch {
                store.awaitLoaded()
                if (!touched) {
                    draft = store.lastDraft
                    currentOptions?.let { o ->
                        val n = normalize(draft, o)
                        if (n != draft) updateInternal({ n }, userEdit = false)
                    }
                    loadModels()
                }
            }
        }
    }

    /** The picked project (iOS `:236`); null while the options are still null. */
    val project: ProjectOption?
        get() = currentOptions?.projects?.firstOrNull { it.id == draft.projectId }

    /** The engine the session runs on (iOS `:237`). */
    val deviceId: String
        get() = project?.device ?: draft.hostId ?: ""

    /**
     * The session will run on its own Cloud machine (iOS `runsOnCloud`): a
     * Cloud project, or Cloud picked for a project whose repository Cloud
     * reaches.
     */
    val runsOnCloud: Boolean
        get() = runsOnCloud(project, draft, cloud)

    /** Cloud can run this project's sessions (iOS `cloudOffered`): the Run on menu's Cloud row. */
    val cloudOffered: Boolean
        get() = project?.let { p -> cloud.available && (p.isCloud || cloud.reaches(p.githubRepo)) } == true

    /**
     * Checkouts of the picked project, one per row of the host menu
     * (iOS `:240-245`): every option sharing its `groupKey`, sorted by
     * (lowercased device name, path) — invariant `lowercase()`, matching
     * Swift's `lowercased()`.
     */
    val checkouts: List<ProjectOption>
        get() {
            val p = project ?: return emptyList()
            val o = currentOptions ?: return emptyList()
            return o.projects
                .filter { it.groupKey == p.groupKey }
                .sortedWith(compareBy({ it.deviceName.lowercase() }, { it.path }))
        }

    /**
     * The model the model chip names and whose ladder the effort chip
     * offers (iOS `draftModel`, #37): see [draftModel].
     */
    val selectedModel: ModelChoice?
        get() = draftModel(models, draft)

    /**
     * Every menu pick (113/114) goes through here: `draft = transform(draft)`,
     * persisted at once (the Zeron adaptation — every iOS exit path saved
     * the same draft anyway), then [loadModels]. A no-op transform
     * changes nothing.
     */
    fun update(transform: (NewSessionDraft) -> NewSessionDraft) =
        updateInternal(transform, userEdit = true)

    /**
     * Closed unsent (iOS `rememberDraft`, `:87-92`): keep what was typed
     * and picked for next time. No-op after [markCreated] or a full
     * reset while the page was open (epoch mismatch).
     */
    fun rememberDraft(text: String, images: List<StagedImage>) {
        if (created || store.resetEpoch != openedEpoch) return
        if (store.isLoaded || touched) store.lastDraft = draft
        store.text = text
        store.images = images
    }

    /**
     * The first line of `create` (ticket 94 calls it before the attempt,
     * so the options are saved even when the create fails): the page's
     * draft, unconditionally.
     */
    fun persistBeforeCreate() {
        store.lastDraft = draft
    }

    /**
     * A session was created from this page (`:415-418`): nothing further
     * to keep — later [rememberDraft] calls write nothing. The options
     * stay sticky; only the text and the images go.
     */
    fun markCreated() {
        created = true
        store.text = ""
        store.images = emptyList()
    }

    /** The page's composition is gone (every close path): stop applying fetch results. */
    fun dispose() {
        disposed = true
    }

    /**
     * The model list for the draft's device (iOS `loadModels`, `:188-204`,
     * the #609 traps avoided): cached (or built-in) **synchronously** —
     * the chip names a real model in the same frame — then a fetch on
     * the **app** scope (never the page scope: it completes and fills
     * the cache even if the page closed), applied on the main thread
     * only when the page still shows this device. An empty result
     * changes nothing.
     */
    internal fun loadModels() {
        val onCloud = runsOnCloud
        val device = if (onCloud) CLOUD_MODELS_KEY else deviceId
        if (device == modelsDevice) return // Compose recomposes the chips by itself
        modelsDevice = device
        if (onCloud) {
            // Cloud runs the agents it has accounts for (iOS `:197-205`):
            // its catalog, and the draft moved onto one of them.
            models = ModelCatalogCache[device] ?: catalog.builtIn().filter { it.harness in CLOUD_HARNESSES }
            val first = models.firstOrNull()
            if (first != null && models.none { it.harness == draft.harness }) {
                updateInternal({ it.copy(harness = first.harness, model = null, effort = null) }, userEdit = false)
            }
        } else {
            models = ModelCatalogCache[device] ?: catalog.builtIn()
        }
        if (device.isEmpty()) return // no engine to ask; the built-in list is the answer
        appScope.launch(io) {
            val fresh = try {
                if (onCloud) catalog.fetchCloud() else catalog.fetch(device)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                emptyList() // contract-break guard: never throw into the app's scope
            }
            if (fresh.isEmpty()) return@launch // no cache write and no apply
            ModelCatalogCache[device] = fresh // fills even if the page closed
            withContext(main) {
                if (!disposed && modelsDevice == device) models = fresh
            }
        }
    }

    /**
     * [update]'s body. `userEdit` separates the user's picks (they set
     * [touched], freezing a pending late load) from normalize-driven
     * changes. Before the store's load, a change mutates the page draft
     * only — the store's "assignment before load wins" rule must not let
     * launch defaults overwrite the sticky draft on disk.
     */
    private fun updateInternal(transform: (NewSessionDraft) -> NewSessionDraft, userEdit: Boolean) {
        val n = transform(draft)
        if (n == draft) return
        draft = n
        if (userEdit) touched = true
        if (!created && store.isLoaded && store.resetEpoch == openedEpoch) store.lastDraft = n
        loadModels()
    }
}

/**
 * The stale-id rule and the defaults (ticket 92's Normalize table, the
 * iOS `viewDidLoad` defaults `:177-181` plus the Zeron stale rule), in
 * order: stale project, stale host, first project, first online host /
 * first host. Pure, so it is unit-tested directly; returns the draft
 * unchanged when nothing applies.
 */
fun normalize(d: NewSessionDraft, o: NewSessionOptions): NewSessionDraft {
    var n = d
    // 1. A vanished project is stale (offline ≠ stale: cached rows stay).
    val projectId = n.projectId
    if (projectId != null && o.projects.none { it.id == projectId }) {
        n = n.copy(projectId = null, branch = null)
    }
    // 2. A vanished host is stale.
    val hostId = n.hostId
    if (hostId != null && o.hosts.none { it.id == hostId }) {
        n = n.copy(hostId = null)
    }
    // 3. Nothing picked: the first project, online or not (iOS `first`).
    if (n.projectId == null && n.hostId == null) {
        n = n.copy(projectId = o.projects.firstOrNull()?.id)
    }
    // 4. No projects at all: the first reachable host, else the first.
    if (n.projectId == null && n.hostId == null) {
        n = n.copy(hostId = (o.hosts.firstOrNull { it.online } ?: o.hosts.firstOrNull())?.id)
    }
    return n
}

/**
 * The New Session page's model (ticket 93's screen calls this): built
 * over the app's single [NewSessionDraftStore], the single workspace
 * feed mapped into options (**initial value = the current snapshot**,
 * never null, so the first frame has the defaults), and the live
 * [CoreModelCatalogSource] over `clientOrNull()`. Disposed with the
 * composition; the fetches outlive it on the app scope.
 */
@Composable
fun rememberNewSessionModel(prompt: String?): NewSessionModel {
    val app = LocalContext.current.applicationContext as ZeronApplication
    val pageScope = rememberCoroutineScope()
    val model = remember(prompt) {
        val feed = app.workspaceFeed
        val cloud = StoreNewSessionCloud(app.cloud)
        // #53 (iOS `projectOptions`): Cloud projects only while the account
        // is offered Cloud (always in the demo).
        val offered = snapshotFlow { app.sessionHolder.isDemo || app.cloud.available }
        NewSessionModel(
            prompt = prompt,
            store = app.newSessionDrafts,
            options = combine(feed.snapshot, offered) { s, o -> s?.toNewSessionOptions(cloudOffered = o) }
                .stateIn(
                    pageScope,
                    SharingStarted.Eagerly,
                    feed.snapshot.value?.toNewSessionOptions(app.sessionHolder.isDemo || app.cloud.available),
                ),
            catalog = CoreModelCatalogSource { app.clientOrNull() },
            pageScope = pageScope,
            appScope = app.appScope,
            cloud = cloud,
        )
    }
    DisposableEffect(model) {
        onDispose { model.dispose() }
    }
    return model
}

/**
 * The draft's save moments (ticket 92) — the port of
 * `viewWillDisappear` (`:94-97`) + the `didEnterBackground` observer
 * (`:101-106`), both idempotent:
 *
 * - **Composition disposal** (✕ close, drag-dismiss, back press, stacked
 *   dismiss, the tablet column replacing the page, the 840dp
 *   carry-over) → [NewSessionModel.rememberDraft]; ticket 93 may also
 *   call it from its ✕ handler — same result, it is idempotent.
 * - **[Lifecycle.Event.ON_STOP]** (backgrounded, or covered by the photo
 *   picker or camera — the app can be killed next) → the same call.
 * - After a successful create, [NewSessionModel.rememberDraft] writes
 *   nothing.
 *
 * Late text: if [Drafts] had not finished its first read when the page
 * opened, the saved text is loaded and seeded into an empty composer
 * (93 seeds `initialText`; both empty means the user typed nothing yet).
 * Ticket 93 creates the composer with `initialText = model.initialText`,
 * sets `composer.images = model.initialImages`, and calls this effect
 * right after.
 */
@Composable
fun NewSessionDraftEffect(model: NewSessionModel, composer: ComposerController) {
    val drafts = LocalDrafts.current
    val loadPending = remember { !drafts.isLoaded }
    if (loadPending) {
        LaunchedEffect(Unit) {
            val t = drafts.load(DraftKey.NewSession)
            if (composer.text.isEmpty() && model.initialText.isEmpty()) composer.text = t
        }
    }
    // MainActivity declares configChanges, so rotation and fold do not
    // fire ON_STOP; a rare recreation or an extra stop (photo picker,
    // camera, share sheet) only causes a harmless re-save.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        model.rememberDraft(composer.text, composer.images)
    }
    DisposableEffect(model, composer) {
        onDispose { model.rememberDraft(composer.text, composer.images) }
    }
}

/** The catalog key of Cloud's models (iOS `cloudModelsKey`): never a device id. */
internal const val CLOUD_MODELS_KEY = "\u0000cloud"

/** The agents Cloud runs (iOS `AppModel.cloudHarnesses`). */
val CLOUD_HARNESSES = listOf("claude-code", "codex")

/** iOS `runsOnCloud`, pure. */
fun runsOnCloud(project: ProjectOption?, draft: NewSessionDraft, cloud: NewSessionCloud): Boolean {
    val p = project ?: return false
    return p.isCloud || (draft.cloud == true && cloud.reaches(p.githubRepo))
}

/**
 * What the New Session page reads of Cloud (#53): a seam over the app's
 * [sh.zeron.android.core.CloudStore] (snapshot state, so readers
 * recompose), so the page's rules stay plain-JVM testable.
 */
interface NewSessionCloud {
    val available: Boolean
    val credits: Double?
    val outOfCredits: Boolean

    /** Cloud reaches `owner/name`: its sessions can run there. */
    fun reaches(repo: String?): Boolean

    /** iOS `if app.cloud.info == nil { app.cloud.refresh() }`. */
    fun refreshIfUnread()

    companion object {
        /** No Cloud (tests, previews). */
        val None: NewSessionCloud = object : NewSessionCloud {
            override val available = false
            override val credits: Double? = null
            override val outOfCredits = false
            override fun reaches(repo: String?) = false
            override fun refreshIfUnread() {}
        }
    }
}

/** The live [NewSessionCloud] over the app's store. */
class StoreNewSessionCloud(private val store: sh.zeron.android.core.CloudStore) : NewSessionCloud {
    override val available: Boolean get() = store.available
    override val credits: Double? get() = store.credits
    override val outOfCredits: Boolean get() = store.outOfCredits
    override fun reaches(repo: String?): Boolean = store.reaches(repo)
    override fun refreshIfUnread() {
        if (store.info == null) store.refresh()
    }
}
