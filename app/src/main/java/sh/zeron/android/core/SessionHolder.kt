package sh.zeron.android.core

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sh.zeron.android.BuildConfig
import sh.zeron.android.session.SessionEventHub
import uniffi.zeron_core.ClientEvent
import uniffi.zeron_core.ClientListener
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.CoreConfig
import uniffi.zeron_core.Credentials

/**
 * One consumer of the core's event stream. Called on a Rust runtime
 * thread — hop threads yourself and return fast (the #621 self-repost
 * trap is why the method is *not* named `onEvent`).
 */
fun interface ClientEventSink {
    fun onClientEvent(event: ClientEvent)
}

/** Told after every local sign-out; [forget] is a sign-out the user asked for. */
fun interface SignOutListener {
    fun onSignedOut(forget: Boolean)
}

/**
 * The session holder (SPEC §4.3), the port of iOS's `AppModel` client
 * lifecycle (`AppModel.swift:103-276`, `363-377`): the account's
 * [CoreClient] comes and goes with sign-in, and "signed in" is exactly
 * "[client] is non-null" (`isSignedIn { client != null }`).
 *
 * - [boot] — launch: the stored account starts offline-first (the core
 *   hydrates from its data dir and dials in the background); nothing
 *   stored means signed out. [booted] flips once the answer is known, so
 *   the splash never shows the Welcome root to a signed-in user.
 * - [start] — a client for one account. Real accounts get the claimed
 *   `core/` dir (another identity starts from an empty store), the demo
 *   its own `demo/`; only `Credentials.WorkOs` is persisted.
 * - [signOutLocally] — shut the client down and drop the credentials;
 *   only a sign-out the user asked for ([forget]) deletes the store (an
 *   expired session keeps it: the outbox may hold unsent messages).
 *
 * The [ClientListener] (one per client): `AuthRefreshed` is persisted
 * **synchronously on the core's callback thread**, before anything hops
 * (D5: the old refresh token is already spent); `AuthExpired` signs out
 * locally on [hop]. Events from a client that is no longer current are
 * dropped, so a late refresh can never bring a signed-out account back.
 * Every other event fans out to the session hub and the registered
 * [ClientEventSink]s without blocking.
 *
 * [start] and [signOutLocally] block (disk, `CoreClient.new`,
 * `shutdown()`): call them off the main thread. They are serialized.
 *
 * Generic over the client so the lifecycle is testable on the plain JVM;
 * the app's holder is [SessionHolder.create]'s `SessionHolder<CoreClient>`.
 */
class SessionHolder<C : Any>(
    /** The parent of `core/` and `demo/` (`noBackupFilesDir`: the store is per install). */
    private val root: File,
    private val credentials: CredentialPersistence,
    private val newClient: (dataDir: File, credentials: Credentials, listener: ClientListener) -> C,
    private val shutdown: (C) -> Unit,
    /** Runs `AuthExpired`'s sign-out off the core's callback thread. */
    private val hop: (Runnable) -> Unit,
    /**
     * The app-scoped session event hub (ticket 51): one line of wiring
     * per event, fan-out on main inside the hub. Tests inject a hub with
     * their own poster.
     */
    val sessionEvents: SessionEventHub = SessionEventHub(),
    private val log: (String, Throwable?) -> Unit = { message, error -> Log.w(TAG, message, error) },
) {

    private val _client = MutableStateFlow<C?>(null)

    /** The live client; null while signed out. */
    val client: StateFlow<C?> = _client.asStateFlow()

    private val _booted = MutableStateFlow(false)

    /** True once [boot] has started the stored account (or found none). */
    val booted: StateFlow<Boolean> = _booted.asStateFlow()

    /** [boot] (or [relaunch], which stands in for it) has run (guarded by [lifecycle]). */
    private var bootDone = false
    private val sinks = CopyOnWriteArrayList<ClientEventSink>()
    private val signOutListeners = CopyOnWriteArrayList<SignOutListener>()

    /** Serializes [start] and [signOutLocally]. */
    private val lifecycle = Any()

    /**
     * Guards [generation] and every credential write, so a refresh can't
     * land between a sign-out's clear and its client going away. Never
     * held across a call into the core.
     */
    private val auth = Any()

    /** Bumped by every start and sign-out; a listener only speaks for its own. */
    @Volatile
    private var generation = 0L

    /** The live client runs the demo (guarded by [lifecycle]). */
    @Volatile
    var isDemo: Boolean = false
        private set

    private val coreDir get() = File(root, CORE_DIR)

    fun clientOrNull(): C? = _client.value

    /** Registers an event sink; do this before [boot] to see every event. */
    fun addEventSink(sink: ClientEventSink) {
        sinks.add(sink)
    }

    fun addSignOutListener(listener: SignOutListener) {
        signOutListeners.add(listener)
    }

    /** Launch (`AppModel.init`): start the stored account, if any. Idempotent; blocks. */
    fun boot() {
        synchronized(lifecycle) {
            if (bootDone) return
            bootDone = true
            try {
                credentials.stored()?.let(::startLocked)
            } finally {
                _booted.value = true
            }
        }
    }

    /**
     * The debug launch extras (#18; iOS's DEBUG hooks in `AppModel.init`),
     * applied once per fresh launch. Runs after [boot], waiting for one in
     * progress, so the stored account never wins over them; arriving first,
     * it boots in [boot]'s place.
     *
     * [clearStored] (`signedout`) forgets the stored account first, signing
     * a running one out locally (its store is kept). [choose] then picks the
     * account to start, given whether one is still stored; null keeps the
     * stored one, which [restart] (the edge changed) starts again. Blocks.
     */
    fun relaunch(
        clearStored: Boolean,
        restart: Boolean,
        choose: (hasStored: Boolean) -> Credentials?,
    ) {
        synchronized(lifecycle) {
            val booting = !bootDone
            bootDone = true
            try {
                if (clearStored) {
                    if (_client.value != null && !isDemo) {
                        signOutLocally(forget = false)
                    } else {
                        synchronized(auth) { credentials.clear() }
                    }
                }
                val stored = credentials.stored()
                val runsStored = _client.value != null && !isDemo
                val next = choose(stored != null)
                    ?: stored?.takeIf { booting || (restart && runsStored) }
                next?.let(::startLocked)
            } finally {
                _booted.value = true
            }
        }
    }

    /**
     * Builds and publishes the client for [credentials] (`AppModel.start`);
     * false when the core refused (the caller shows its error, the app
     * stays signed out). A live client is shut down first. Blocks.
     */
    fun start(credentials: Credentials): Boolean {
        synchronized(lifecycle) {
            return startLocked(credentials)
        }
    }

    private fun startLocked(credentials: Credentials): Boolean {
        _client.value?.let(::retire)
        val demo = credentials is Credentials.Demo
        val dir = if (demo) File(root, DEMO_DIR) else coreDir
        if (!demo) claimCoreDir(credentials)
        dir.mkdirs()
        // Persisted before the client exists, so a refresh racing the
        // constructor updates these tokens instead of being overwritten.
        val (gen, previous) = synchronized(auth) {
            val stored = if (credentials is Credentials.WorkOs) this.credentials.stored() else null
            if (credentials is Credentials.WorkOs) this.credentials.store(credentials)
            ++generation to stored
        }
        val client = try {
            newClient(dir, credentials, listenerFor(gen))
        } catch (error: Exception) {
            log("core start failed", error)
            synchronized(auth) {
                generation++
                if (credentials is Credentials.WorkOs && previous != credentials) {
                    if (previous is Credentials.WorkOs) this.credentials.store(previous) else this.credentials.clear()
                }
            }
            return false
        }
        isDemo = demo
        _client.value = client
        return true
    }

    /**
     * Shut the client down and forget the account (`AppModel.signOutLocally`).
     * [forget] — the user asked — also deletes the local store. As on iOS,
     * leaving the demo does the same: the stored real account (credentials
     * and profile) goes too, and a forgetting sign-out deletes its `core/`
     * store; the demo's own `demo/` dir is never touched. Blocks.
     */
    fun signOutLocally(forget: Boolean) {
        synchronized(lifecycle) {
            synchronized(auth) {
                generation++
                credentials.clear()
            }
            _client.value?.let(::retire)
            if (forget) coreDir.deleteRecursively()
        }
        for (listener in signOutListeners) {
            try {
                listener.onSignedOut(forget)
            } catch (error: Exception) {
                log("sign-out listener failed", error)
            }
        }
    }

    /** Unpublish, then stop the client's background work (its snapshots stay readable). */
    private fun retire(client: C) {
        _client.value = null
        isDemo = false
        try {
            shutdown(client)
        } catch (error: Exception) {
            log("core shutdown failed", error)
        }
    }

    /** Local docs belong to one identity: another account or org starts empty. */
    private fun claimCoreDir(credentials: Credentials) {
        val owner = when (credentials) {
            is Credentials.WorkOs -> "${credentials.userId}/${credentials.orgId}"
            is Credentials.Dev -> "${credentials.userId}/${credentials.orgId}"
            is Credentials.Demo -> return
        }
        val marker = File(coreDir, OWNER_MARKER)
        if (runCatching { marker.readText() }.getOrNull() != owner) coreDir.deleteRecursively()
        coreDir.mkdirs()
        marker.writeText(owner)
    }

    private fun listenerFor(gen: Long): ClientListener = object : ClientListener {
        override fun onEvent(event: ClientEvent) = routeClientEvent(gen, event)
    }

    private fun routeClientEvent(gen: Long, event: ClientEvent) {
        when (event) {
            is ClientEvent.AuthRefreshed -> synchronized(auth) {
                if (gen != generation) return
                credentials.updateTokens(event.tokens)
            }
            is ClientEvent.AuthExpired -> {
                if (gen != generation) return
                log("session expired: ${event.reason}", null)
                hop(Runnable { expire(gen) })
            }
            else -> if (gen != generation) return
        }
        // Ticket 51: one hop to main per event for the session
        // controllers. Qualified on purpose — a bare `onEvent(event)`
        // here would re-post every event forever (#621).
        sessionEvents.onClientEvent(event)
        for (sink in sinks) {
            try {
                sink.onClientEvent(event)
            } catch (error: Throwable) {
                log("client event sink failed", error)
            }
        }
    }

    /** `AuthExpired` → back to Welcome, keeping the store; a newer sign-in wins. */
    private fun expire(gen: Long) {
        synchronized(lifecycle) {
            if (gen != generation) return
            signOutLocally(forget = false)
        }
    }

    companion object {
        private const val TAG = "Zeron"
        internal const val CORE_DIR = "core"
        internal const val DEMO_DIR = "demo"
        internal const val OWNER_MARKER = ".owner"

        /**
         * The app's holder over the real core: `CoreConfig(platform =
         * "android")` with this install's device id and label, read when
         * each client is built. Sign-outs from `AuthExpired` run on their
         * own thread.
         */
        fun create(
            context: Context,
            credentials: CredentialPersistence,
            sessionEvents: SessionEventHub = SessionEventHub(),
        ): SessionHolder<CoreClient> {
            val app = context.applicationContext
            val lane = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "zeron-session").apply { isDaemon = true }
            }
            return SessionHolder(
                root = app.noBackupFilesDir,
                credentials = credentials,
                newClient = { dir, creds, listener ->
                    val config = CoreConfig(
                        // Production, or the debug `edge` override (#18).
                        edgeUrl = Endpoints.edgeUrl,
                        dataDir = dir.path,
                        deviceId = DeviceId.read(app),
                        deviceName = DeviceLabel.read(app),
                        platform = "android",
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                    // The constructor never dials; then warm the likeliest chats (iOS start).
                    CoreClient(config, creds, listener).also { it.preloadSessions() }
                },
                shutdown = { it.shutdown() },
                hop = lane::execute,
                sessionEvents = sessionEvents,
            )
        }
    }
}
