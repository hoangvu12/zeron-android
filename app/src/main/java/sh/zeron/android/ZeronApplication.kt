package sh.zeron.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.zeron.android.background.RunWatchController
import sh.zeron.android.composer.CoreChipMenuCore
import sh.zeron.android.composer.Drafts
import sh.zeron.android.composer.QueueChromeFiller
import sh.zeron.android.composer.SessionChipMenus
import sh.zeron.android.core.AppLifecycleHooks
import sh.zeron.android.core.AppearanceController
import sh.zeron.android.core.CoreClientWorkspace
import sh.zeron.android.core.CoreLoader
import sh.zeron.android.core.CoreLifecycleSink
import sh.zeron.android.core.CoreStatus
import sh.zeron.android.core.CredentialStore
import sh.zeron.android.core.FontGate
import sh.zeron.android.core.Prefs
import sh.zeron.android.core.SessionHolder
import sh.zeron.android.core.TextEngine
import sh.zeron.android.core.UiModeNightModeSink
import sh.zeron.android.core.WorkspaceCore
import sh.zeron.android.core.WorkspaceFeed
import sh.zeron.android.core.zeronPrefsStore
import sh.zeron.android.design.Appearance
import sh.zeron.android.design.AlertHostState
import sh.zeron.android.design.ToasterState
import sh.zeron.android.notify.NotificationCenter
import sh.zeron.android.notify.NotificationPermission
import sh.zeron.android.notify.NotificationPolicy
import sh.zeron.android.notify.NotificationPrefs
import sh.zeron.android.notify.Notifier
import sh.zeron.android.newsession.NewSessionDraftStore
import sh.zeron.android.session.ChromeFillerRegistry
import sh.zeron.android.session.CoreSessionCore
import sh.zeron.android.session.CoreSessionOpener
import sh.zeron.android.session.QuestionsChromeFiller
import sh.zeron.android.session.SessionChipsFiller
import sh.zeron.android.session.StatusBanner
import sh.zeron.android.session.installSessionRoutes
import sh.zeron.android.sessions.CoreRowActions
import sh.zeron.android.sessions.FoldState
import sh.zeron.android.sessions.FrontPageModel
import sh.zeron.android.sessions.asSessionWrites
import sh.zeron.android.shell.DeepLinkHub
import sh.zeron.android.shell.RootInput
import sh.zeron.android.shell.RouterHooks
import sh.zeron.android.shell.SearchTabState
import uniffi.zeron_core.ClientEvent
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.WorkspaceSnapshot

/**
 * The Zeron app shell: verifies the bundled fonts, loads the Rust mobile
 * core, then boots the session holder (#13) — the stored account's
 * `CoreClient` is built on IO, and the splash (ticket 35) holds on
 * `RootInput.Loading` until the root is known.
 *
 * Property names are reserved for the parity tickets (android-ui-parity):
 * `prefs`, `workspaceFeed`, `foldState`, `frontPage` (#40, all landed
 * now), `appEvents`, `appToaster`, `rowActions` (#42, landed now),
 * `deepLinks` (#36, landed), `drafts` (#84), `newSessionDrafts` (#92) —
 * do not reuse them for something else.
 */
class ZeronApplication : Application() {

    lateinit var core: CoreStatus
        private set

    /**
     * Application-wide scope for state that outlives the activity
     * (ticket 40): Main.immediate, like the iOS main queue — the front
     * page's mutators flip their StateFlows synchronously on the calling
     * (main) thread and hop to serial IO lanes for the FFI and the disk.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Ticket 71's name for the process-lifetime, main-immediate scope:
     * sibling tickets build their outliving scopes on it (83's
     * queue-edit finishes, 84's draft writes), so work a screen starts
     * still runs after its nav entry closes. Same instance as the
     * private [applicationScope] — one SupervisorJob for the process.
     */
    val appScope: CoroutineScope
        get() = applicationScope

    /**
     * The Search tab's query holder (ui-parity #29): app-scoped so the
     * query survives tab switches **and activity recreations**, but never
     * process death (it is not saveable — the query is gone on a fresh
     * process, like iOS's). `rememberSearchTabState` resolves it.
     */
    val searchTabState = SearchTabState()

    /**
     * The app root's hook holder (ticket 32, provided by 35 as
     * `LocalRouterHooks` around the root): one instance for the app's
     * lifetime. Tickets 76 (`SessionRouteFactory`) and 95
     * (`HandoffAnimator`) assign their implementations at startup; the
     * fields are volatile so the assignments are visible to the router's
     * open coroutines.
     */
    val routerHooks = RouterHooks()

    /**
     * The ordered ChromeFiller factories (ticket 76 + 73/78/79/82/89/91):
     * the filler tickets register into this in `onCreate`, before the
     * first possible open. Empty until they land — 51's base chrome
     * fills the screen alone.
     */
    val chromeFillers = ChromeFillerRegistry()

    /**
     * The process-wide deep-link hub (ticket 36): holds `zeron://` links
     * and `route` extras until a shell can take them (dispatched after the
     * shell's first frame) and drops them while the welcome root is on
     * screen. App-lifetime on purpose: a link held while the
     * splash is up survives activity recreation.
     *
     * [knownChatIds] reads the workspace snapshot — an in-memory list of
     * scoped ids (`allSessionIds` below), never a blocking FFI call on the
     * main thread, and only ever for a link with a **raw** engine id.
     */
    val deepLinks: DeepLinkHub by lazy {
        DeepLinkHub(knownChatIds = { workspaceFeed.snapshot.value.allSessionIds() })
    }

    /**
     * The appearance preference (ticket 96): the DataStore-backed
     * [Prefs.appearance] StateFlow — `System` until the first disk read —
     * fed into the one root `ZeronTheme(appearance)`, the port of
     * `window.overrideUserInterfaceStyle` (`SceneDelegate.swift:14`).
     */
    val appearance: StateFlow<Appearance>
        get() = prefs.appearance

    /** Writes the appearance preference (ticket 96) — see [Prefs.setAppearance]. */
    fun setAppearance(a: Appearance) = prefs.setAppearance(a)

    /**
     * Ticket 96: pushes the stored appearance to the platform's per-app
     * night mode after the first disk read and on every change — the
     * splash of the next cold start, the bar-icon defaults and the
     * `values-night` resources follow it. Forced in `onCreate` (before
     * `sessionHolder.boot()`, order irrelevant: it waits on the DataStore
     * read, never on the client).
     */
    private val appearanceController: AppearanceController by lazy {
        AppearanceController(prefs, UiModeNightModeSink(this), applicationScope)
    }

    /**
     * The phone-local per-chat draft store (ticket 84) — unsent composer
     * text per session under `chat:<engineId>:<scoped chat id>`, plus the
     * iOS `"new-session"` key (ticket 92 reads and writes it). One
     * DataStore per process over `noBackupFilesDir/drafts.preferences_pb`
     * (drafts reference engine ids that are themselves not backed up, so
     * a restored backup would only hold orphans); the in-memory mirror
     * warms in `onCreate`, saves are blank-removing and verbatim (iOS
     * `Drafts`), and the disk writes run on [appScope] so they outlive
     * every screen. Ticket 140's forget sequence drops an engine's
     * drafts through `forgetDevice`.
     */
    val drafts by lazy {
        Drafts(
            Drafts.createDataStore(this, CoroutineScope(SupervisorJob() + Dispatchers.IO)),
            appScope,
        )
    }

    /**
     * The New Session page's sticky state (ticket 92): the options draft
     * as JSON under `newSessionDraft` (the iOS `UserDefaults` key) in one
     * DataStore per process over `noBackupFilesDir/new_session.preferences_pb`
     * (engine-scoped ids — the same no-backup rule as the drafts), the
     * typed text in [drafts] under the iOS `"new-session"` key, and the
     * staged images in memory only. Every draft change is persisted on
     * [appScope]; the mirror warms in `onCreate`, and the debug
     * `-harness`-style extra is applied once on the first read after the
     * load. Ticket 140's forget sequence resets or strips it through
     * `forgetEngine`.
     */
    val newSessionDrafts by lazy {
        NewSessionDraftStore(
            NewSessionDraftStore.createDataStore(this, CoroutineScope(SupervisorJob() + Dispatchers.IO)),
            drafts,
            appScope,
        )
    }

    /**
     * Where the signed-in account and its profile are kept between
     * launches (D5, #14): Keystore-sealed files in `noBackupFilesDir`,
     * written synchronously. Read and written off the main thread.
     */
    val credentials: CredentialStore by lazy { CredentialStore.create(this) }

    /**
     * The session holder (#13, SPEC §4.3 — the port of iOS's `AppModel`
     * client lifecycle): `client` is the account's `CoreClient`, null while
     * signed out. #15's sign-in calls `start(Credentials.WorkOs)` off main;
     * sign-out and `AuthExpired` go through `signOutLocally`. Every
     * app-lifetime consumer resolves the client at call time
     * ([clientOrNull]) or follows `client` (see `onCreate`).
     */
    val sessionHolder: SessionHolder<CoreClient> by lazy {
        SessionHolder.create(this, credentials)
    }

    /**
     * The process lifecycle / network hooks (ticket 35): foreground and
     * background `onForeground()`/`onBackground()`, the default-network
     * `setNetworkOnline(Boolean)` and `networkPathChanged()` — installed
     * **once** in `onCreate`, before the stored account starts, and fed
     * the network state again for every client the holder publishes.
     * Never registered from an activity (#609/#621).
     */
    lateinit var lifecycleHooks: AppLifecycleHooks
        private set

    /**
     * The app root's input (ticket 35): `Loading` until the holder has
     * started the stored account (or found none), so the splash
     * (FirstFrameHold) holds; then `Ready(signedIn)`, following the
     * client through sign-in, sign-out and expiry. `CoreFailed` when the
     * native core did not load.
     */
    lateinit var rootInput: StateFlow<RootInput>
        private set

    /**
     * The phone-local preference store (ticket 40): the Sessions tab's
     * Group by Project flag, the phone-local section folds and the
     * offline fold overrides — engine-local data (ADR 0004), never sent
     * to an engine.
     */
    val prefs: Prefs by lazy { Prefs(zeronPrefsStore, applicationScope) }

    /**
     * The workspace FFI seam, resolved at call time: whatever client
     * the session holder holds right now (null while signed out).
     */
    private val liveCore: WorkspaceCore by lazy {
        CoreClientWorkspace(client = { sessionHolder.clientOrNull() })
    }

    /**
     * The single Kotlin `StateFlow<WorkspaceSnapshot?>` owner (#20 — read
     * as `app.workspaceFeed.snapshot` / `.devices` / `.connectivity`). Its event sink is
     * registered lazily, but `onCreate` forces this property above
     * `sessionHolder.boot()` so no event is ever missed; `onCreate` also
     * resets it whenever the client changes, so one account's workspace
     * never outlives its sign-out.
     */
    val workspaceFeed: WorkspaceFeed by lazy {
        val feed = WorkspaceFeed(core = { liveCore }, scope = applicationScope)
        sessionHolder.addEventSink { event -> feed.onEvent(event) }
        feed
    }

    /**
     * The fold state (ticket 40): phone-local folds for Pinned/Recent/
     * groups, engine-synced folds for user sections, offline overrides
     * pushed on reconnect. One instance per process — the tablet sidebar
     * (105) shares it.
     */
    val foldState: FoldState by lazy { FoldState(zeronPrefsStore, liveCore, applicationScope) }

    /** The Sessions tab's front page model (tickets 40 and 50); 46 hosts it. */
    val frontPage: FrontPageModel by lazy {
        FrontPageModel(workspaceFeed, prefs, foldState, applicationScope)
    }

    /**
     * The app's core-event fan-out (ticket 42): one [MutableSharedFlow]
     * every part of the UI can collect, re-emitted from the single client
     * listener with a non-blocking `tryEmit` on the Rust callback thread
     * — 64 slots of slack, and a full buffer drops rather than blocks
     * the callback. The sink is registered in [rowActions]'s lazy init,
     * which `onCreate` forces before [sessionHolder] boots, so no event
     * is ever missed.
     */
    val appEvents = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 64)

    /**
     * The app-scoped toast state (ticket 42's wiring of ticket 14):
     * created once next to the client, **not** inside a composable — an
     * app-lifetime [CoreRowActions] holding an Activity-scoped host's
     * `remember { ToasterState() }` would toast into a dead host after a
     * rotation. Ticket 35 passes it to the root
     * `ToastHost(state = appToaster)`; until then only the gallery and
     * the row actions write toasts through it.
     */
    val appToaster: ToasterState by lazy { ToasterState() }

    /**
     * The app-scoped alert state (the [appToaster] precedent): an
     * app-lifetime service, not a screen, can show its alerts through it,
     * so a prompt raised while no activity is composed (or after a
     * rotation) still lands in the live root host. `ZeronAppContent` passes it to `RootHosts` (the alert
     * host is the root's own; this is only the shared state behind it).
     */
    val appAlerts: AlertHostState by lazy { AlertHostState() }

    /**
     * The one object every session/section write goes through (ticket
     * 42) — the [sh.zeron.android.sessions.RowActions] instance ticket
     * 35 provides as `LocalRowActions` inside the root `ToastHost`.
     * Writes run on the serial IO lane, failures are only logged (iOS
     * `attempt`), a successful archive shows "Archived · Undo". App lifetime: a toast's Undo must
     * outlive the screen that raised it (iOS's `[weak self]` on
     * `AppModel`).
     */
    val rowActions: CoreRowActions by lazy {
        sessionHolder.addEventSink { event -> appEvents.tryEmit(event) }
        CoreRowActions(
            writes = { clientOrNull()?.asSessionWrites() },
            sections = { workspaceFeed.snapshot.value?.front?.sections.orEmpty() },
            toaster = appToaster,
            scope = applicationScope,
        )
    }

    /**
     * The app's notification centre (android-app#28): the `NotificationSink`
     * the core calls, the delivery gates, and the posting. Its scope hops
     * the core-thread callbacks onto `Dispatchers.Default`
     * (`NotificationManagerCompat.notify` is thread-safe; the main thread is
     * never blocked). Forcing the lazy init runs `migrateChannels()` +
     * `ensureChannels()` in `onCreate` — cheap and idempotent.
     */
    val notifications: NotificationCenter by lazy {
        val center = NotificationCenter(
            context = this,
            // One lane: the core delivers callbacks in order (an older
            // RunActivity must never land after a newer one, a Resolved
            // never before its event); parallel Default hops could reorder.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)),
            controller = runWatch,
            prefs = notificationPrefs,
            notifier = notifier,
        )
        center.setEngineCountSource { workspaceFeed.devices.value.size }
        center
    }

    /** The phone-local notification choices (android-app#28; #99's rows). */
    val notificationPrefs: NotificationPrefs by lazy { NotificationPrefs.get(this) }

    /** The notification poster (channels, migration, copy, cancels). */
    val notifier: Notifier by lazy {
        Notifier(this).also {
            it.migrateChannels()
            it.ensureChannels()
        }
    }

    /**
     * The run-watching foreground service's controller (android-app#28):
     * starts `RunWatchService` while any run is Working and notifications
     * are on, stops it 2 min after the last run leaves Working, and keeps
     * it up while "Stay connected in background" is on (ADR 0010). Never owns
     * sockets — the core client does; the service keeps the process out
     * of the cached/frozen set.
     */
    val runWatch: RunWatchController by lazy {
        RunWatchController(this, applicationScope, notifier = notifier)
    }

    override fun onCreate() {
        super.onCreate()
        // #609's Fonts.init swallowed missing faces (review bug 8); a
        // missing font must fail startup loudly, naming the files. Debug
        // builds crash here; release builds store FontStatus.Missing and
        // MainActivity shows FontsMissingScreen instead of the app.
        FontGate.check(assets, BuildConfig.DEBUG)
        // Ticket 60: the shared text engine (one Rust TextSystem over the
        // same font bytes) is built off the main thread — the prewarm
        // daemon races the first transcript open. A late load failure
        // reports itself through FontGate.fail.
        TextEngine.init(this)
        TextEngine.prewarm()
        core = CoreLoader.load()
        // Ticket 84: warm the drafts mirror now — Application.onCreate
        // precedes every activity's setContent, so `peek` is nearly
        // always loaded by the time a session opens (one opened before
        // the read finishes falls back to the effect's late load).
        drafts.warm()
        // Ticket 92: the same for the new-session options mirror — a
        // page opened before the read finishes takes the late-load path.
        newSessionDrafts.warm()
        // Ticket 35: the process lifecycle hooks and the default-network
        // monitor, installed exactly once per process — never from an
        // Activity (#609 leaked one observer per onCreate) and never from
        // onStart/onStop (#621 redialed on every rotation). Before
        // `sessionHolder.boot()`: the sink resolves the live client, and
        // `clientReady()` (the client follower below) re-sends the
        // network state to every client the holder publishes. Right after
        // onForeground() the workspace is re-read (iOS
        // `willEnterForeground` → `refreshWorkspace`), so relative times
        // are fresh on return.
        lifecycleHooks = AppLifecycleHooks.install(
            app = this,
            lifecycle = ProcessLifecycleOwner.get().lifecycle,
            sink = { clientOrNull()?.let(::CoreLifecycleSink) },
            afterForeground = { workspaceFeed.refresh() },
        )
        // #20: iOS's 30 s workspace clock (docs/port/01 A13), while the
        // app is in the foreground — iOS timers don't fire suspended.
        applicationScope.launch {
            ProcessLifecycleOwner.get().lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                workspaceFeed.runClock()
            }
        }
        // #13: Loading holds the splash until the stored account is known,
        // so a signed-in user never sees the welcome root flash; then the
        // root follows the client (`SceneDelegate.showRoot`: signed out →
        // Welcome, signed in → the shell), cross-fading on every change.
        rootInput = when (val c = core) {
            is CoreStatus.Failed -> MutableStateFlow(RootInput.CoreFailed(c.reason))
            is CoreStatus.Loaded -> combine(sessionHolder.booted, sessionHolder.client) { booted, client ->
                if (!booted) RootInput.Loading else RootInput.Ready(signedIn = client != null)
            }.distinctUntilChanged()
                .stateIn(applicationScope, SharingStarted.Eagerly, RootInput.Loading)
        }
        // Ticket 78 (chips): the session composer's chip derivation,
        // registered at slot 20 — before 73's BANNER (30), the iOS
        // `refresh()` order. 73 owns the send-failure
        // state, so this registry never gets a second send-failure filler.
        chromeFillers.register(ChromeFillerRegistry.CHIPS) { SessionChipsFiller() }
        // Ticket 73 (status banner): the pill's chrome derivation and its
        // sticky send-failure slot, registered in 76's BANNER slot (30) —
        // the exact line of 73's wiring contract (`StatusBanner(
        // retryDelivery = { (core as CoreSessionCore).handle.retryDelivery() })`),
        // never a second send-failure filler (78 registers no fallback).
        // 83's queue-edit recovery reports through this filler's
        // `noteFailureMessage`, and the pill's tap cancels an open edit
        // through its `onEditingTap`.
        chromeFillers.register(ChromeFillerRegistry.BANNER) { core ->
            StatusBanner(retryDelivery = { (core as CoreSessionCore).handle.retryDelivery() })
        }
        // Ticket 89 (chip menus): the content and actions behind the
        // model / effort / PR chips — slot 21, right after the chips it
        // decorates. The filler stores the controller-refresh snapshot
        // and keeps the deferred cache; the loads and writes run on IO
        // inside it (never on main, the #609 trap).
        chromeFillers.register(ChromeFillerRegistry.CHIP_MENUS) { sessionCore ->
            SessionChipMenus(CoreChipMenuCore.of(sessionCore))
        }
        // Ticket 82 (queue panel): the queue mapping into
        // `SessionChrome.queue` — gate labels, `visibleText`, order —
        // registered in 76's QUEUE slot (50), so every opened route fills
        // it after the banner and chips. The registry hands each open a
        // fresh instance; the per-route `QueueActions` over the live
        // handle is built by the factory below (never a shared filler).
        chromeFillers.register(ChromeFillerRegistry.QUEUE) { _ -> QueueChromeFiller() }
        // Ticket 91 (question panel): the open input request mapped into
        // `SessionChrome.questions` (request id + one Question per
        // UserInputQuestion, field for field, entry_id dropped) —
        // registered in 76's QUESTIONS slot (40), after the banner and
        // chips, before the queue, the iOS `refresh()` order. The filler
        // makes no FFI call; the panel's per-route QuestionActions over
        // the live handle is built by 75's screen from 76's route core.
        chromeFillers.register(ChromeFillerRegistry.QUESTIONS) { _ -> QuestionsChromeFiller() }
        // Ticket 76: the real session route factory. Every open runs off
        // main through 32's `openSessionRoute`, over `sessionHolder.sessionEvents`
        // (51's hub — the same instance the client's events already fan out
        // into) and the filler registry above. A refused id returns null and
        // the shell shows the open-error toast: no screen, no placeholder.
        // android-app#28's cancel-on-open seam rides along: opening a chat
        // (the session screen's first composition) or closing it (a committed
        // pop) cancels its notification through `onSeen`.
        installSessionRoutes(
            routerHooks,
            CoreSessionOpener(::clientOrNull),
            sessionHolder.sessionEvents,
            chromeFillers,
            onSeen = { chatId -> notifications.cancelChatFor(chatId) },
            queueScope = applicationScope,
        )
        // Tickets 40/42/51 register their event sinks here, before boot().
        // #40: forcing `workspaceFeed`'s lazy init registers its sink; the
        // client follower below resets and re-reads it on every client
        // change (its drains skip while signed out).
        // #96: arming the appearance controller pushes the stored choice
        // to the platform's per-app night mode (async: it first waits for
        // the DataStore read, so the platform's own persisted mode already
        // covered the splash of this launch).
        appearanceController.start()
        // android-app#28: the notification pipeline — channels migrated and
        // created, the foreground gates fed from the process lifecycle (a
        // process-wide observer, exactly once: never per activity — #609's
        // leak), the run-watching service controller fed with the engine
        // count and the notifications-on gate (master pref + permission;
        // the permission can be revoked from system settings, so it is
        // re-read on every change and foreground edge). Forcing
        // `notifications` also installs the sink on the client the moment
        // 71's pipeline reports it ready.
        notifications.foreground = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    notifications.foreground = true
                    runWatch.onForeground(true)
                    refreshNotificationsOn()
                }

                override fun onStop(owner: LifecycleOwner) {
                    notifications.foreground = false
                    runWatch.onForeground(false)
                }
            },
        )
        applicationScope.launch {
            workspaceFeed.devices.collect { devices ->
                runWatch.onEngineCount(devices.size)
            }
        }
        applicationScope.launch {
            notificationPrefs.state.collect { refreshNotificationsOn() }
        }
        // ADR 0010: Settings → "Stay connected in background" holds the
        // service (false until the DataStore read lands; the flip from
        // Settings arrives while the app is visible, so the start is legal).
        applicationScope.launch {
            prefs.stayConnected.collect { runWatch.onStayConnected(it) }
        }
        if (core is CoreStatus.Loaded) {
            // #13: everything that follows the account's client. A new
            // client gets the network state and the notification driver; any
            // change (sign-in, sign-out, AuthExpired) drops the previous
            // account's workspace lists first.
            applicationScope.launch {
                sessionHolder.client.collect { client ->
                    workspaceFeed.reset()
                    if (client != null) {
                        lifecycleHooks.clientReady()
                        notifications.attachTo(client)
                    }
                }
            }
            // Stored credentials start the client offline-first; off main:
            // the store read and `CoreClient.new` touch the disk.
            applicationScope.launch(Dispatchers.IO) { sessionHolder.boot() }
        }
    }

    /**
     * Re-derive the run-watching service's `notificationsOn` gate (the
     * master pref + the live permission status). Also called by
     * MainActivity when the permission dialog returns.
     */
    internal fun refreshNotificationsOn() {
        runWatch.onNotificationsOn(
            NotificationPolicy.masterOn(
                notificationPrefs.state.value,
                NotificationPermission.status(this, notificationPrefs),
            ),
        )
    }

    /** The live client; null while signed out (or before the stored account started). */
    fun clientOrNull(): CoreClient? = sessionHolder.clientOrNull()
}

/**
 * Every session id the workspace snapshot carries (ticket 36's
 * `knownChatIds`): pinned, sections, recent, projects,
 * projectless, pull requests and archived — all **scoped** ids
 * (`engine:v1:…`), all already in memory (`emptyList()` while the
 * snapshot is null). Deduplicated: `recent`, `projects` and
 * `projectless` deliberately overlap, and the hub's raw-id resolution
 * counts **distinct** matching scoped ids.
 */
private fun WorkspaceSnapshot?.allSessionIds(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        front.pinned.forEach { add(it.id) }
        front.sections.forEach { section -> section.sessions.forEach { add(it.id) } }
        front.recent.forEach { add(it.id) }
        projects.forEach { project -> project.sessions.forEach { add(it.id) } }
        addAll(projectless.map { it.id })
        addAll(pullRequests.open.map { it.id })
        addAll(pullRequests.merged.map { it.id })
        addAll(pullRequests.closed.map { it.id })
        addAll(archived.map { it.id })
    }.distinct()
}
