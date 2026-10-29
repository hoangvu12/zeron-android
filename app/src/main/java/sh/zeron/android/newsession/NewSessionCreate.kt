package sh.zeron.android.newsession

import android.util.Log
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.zeron.android.background.DeliveryHold
import sh.zeron.android.composer.StagedImage
import sh.zeron.android.design.AlertButton
import sh.zeron.android.design.AlertButtonRole
import sh.zeron.android.design.ZeronAlert
import uniffi.zeron_core.BusyPolicy
import uniffi.zeron_core.ChatConfig
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.CoreException
import uniffi.zeron_core.NewSession
import uniffi.zeron_core.OutgoingAttachment
import uniffi.zeron_core.SandboxLevel
import uniffi.zeron_core.SendRequest
import uniffi.zeron_core.SessionTarget
import uniffi.zeron_core.WorktreeSpec

/**
 * The core calls behind a new session's first send (android-ui-parity
 * #94) — the port of `AppModel.createSession`'s three steps
 * (`ios:App/AppModel.swift:618-643`). [CoreClient] and
 * [uniffi.zeron_core.SessionHandle] are JNA-backed objects the JVM
 * cannot construct, so everything the send handler drives goes through
 * this seam and tests fake it.
 *
 * Both members are FFI calls over the engine's WebSocket:
 * [createSession] is a `suspend` engine round trip, [sendFirst] opens
 * the warm handle (never blocking on main — the open reads the per-chat
 * cache seed from disk, android-app#08) and sends the first message.
 * Never call either on the main thread; [NewSessionCreator] owns the
 * `withContext(Dispatchers.IO)` hop.
 */
interface NewSessionCore {

    /** `CoreClient.createSession(newSession)` — returns the **scoped** chat id. */
    suspend fun createSession(newSession: NewSession): String

    /** `CoreClient.openSession(chatId).send(request)` — open, then the first message. */
    suspend fun sendFirst(chatId: String, request: SendRequest)

    /** #53: `CoreClient.createCloudSession(spaceId, config, branch, null)` — the edge mints the machine. */
    suspend fun createCloudSession(spaceId: String, config: ChatConfig, branch: String?): String =
        throw UnsupportedOperationException("no Cloud")

    /** #53: the chat still exists (`CoreClient.sessionRow(chatId) != null`). */
    fun hasSession(chatId: String): Boolean = false

    /** #53: iOS `refreshWorkspace()` after a Cloud start. */
    fun refreshWorkspace() {}
}

/**
 * The live [NewSessionCore] over the app's client holder
 * (`ZeronApplication.clientOrNull()`, ticket 71): `null` before the
 * startup load finished or after a failed start — exactly iOS's
 * `guard let client` (`AppModel.swift:621`) — becomes an error here, so
 * the creator's catch turns it into the failure alert.
 */
class CoreNewSessionCore(
    private val client: () -> CoreClient?,
    private val workspaceChanged: () -> Unit = {},
) : NewSessionCore {

    override suspend fun createCloudSession(spaceId: String, config: ChatConfig, branch: String?): String =
        (client() ?: throw CoreException.Closed()).createCloudSession(spaceId, config, branch, null)

    override fun hasSession(chatId: String): Boolean = client()?.sessionRow(chatId) != null

    override fun refreshWorkspace() = workspaceChanged()

    override suspend fun createSession(newSession: NewSession): String =
        (client() ?: error("no core client")).createSession(newSession)

    override suspend fun sendFirst(chatId: String, request: SendRequest) {
        val c = client() ?: error("no core client")
        // Do NOT close the handle: the core's warm cache keeps it (with
        // the pending echo of the first message) for the router's open
        // (ticket 76); a `closeSession` here would drop the pending row.
        c.openSession(chatId).send(request)
        // #58: iOS `createSession`'s `noteDeliveryActivity()`.
        DeliveryHold.noteActivity()
    }
}

/**
 * The request mapping (94's Request mapping table) — the exact port of
 * `AppModel.createSession` (`AppModel.swift:623-638`) plus
 * `StagedImage.outgoing` (`:646-648`). Pure; every value is taken from
 * the draft snapshot the send handler captured before the IO call.
 */
internal object NewSessionRequests {

    /**
     * The target's precedence (`:622-628`): a project wins over a host;
     * neither → `null`, and the caller fails **without** a core call (the
     * alert explains). Ids are the scoped ids the options carry.
     */
    fun target(d: NewSessionDraft): SessionTarget? {
        val projectId = d.projectId
        if (projectId != null) return SessionTarget.Project(spaceId = projectId)
        val hostId = d.hostId
        if (hostId != null) return SessionTarget.Projectless(deviceId = hostId)
        return null
    }

    /**
     * `NewSession(target:config:branch:cwd:title:)` (`:629-630`):
     * `reasoning = effort` (nil ⇒ the engine chooses), `sandbox` is
     * always workspace-write on this canvas, `branch` only without a
     * worktree (with one, the branch becomes the worktree's base).
     */
    fun newSession(d: NewSessionDraft, target: SessionTarget): NewSession = NewSession(
        target = target,
        config = ChatConfig(
            harness = d.harness,
            model = d.model,
            reasoning = d.effort,
            modelOptions = emptyMap(),
            sandbox = SandboxLevel.WORKSPACE_WRITE,
        ),
        branch = if (d.worktree) null else d.branch,
        cwd = null,
        title = null,
    )

    /**
     * `SendRequest` (`:632-637`): the encoded body verbatim, staged
     * images as JPEG attachments, the worktree spec only when the draft
     * asks for one **and** the picked project exists (`project.map` — a
     * worktree without a project is nil), base `branch ?: "HEAD"`,
     * delivery always `.queue` (a new chat is idle).
     */
    fun sendRequest(
        d: NewSessionDraft,
        text: String,
        images: List<StagedImage>,
        projects: List<ProjectOption>,
    ): SendRequest = SendRequest(
        text = text,
        attachments = images.map { image ->
            OutgoingAttachment(
                name = image.name,
                // iOS `AppModel.swift:647`: staged images are JPEG.
                mimeType = "image/jpeg",
                data = image.bytes,
            )
        },
        worktree = if (d.worktree) {
            projects.firstOrNull { it.id == d.projectId }?.let { project ->
                WorktreeSpec(
                    repoPath = project.path,
                    base = d.branch ?: "HEAD",
                    spaceId = project.id,
                )
            }
        } else {
            null
        },
        busy = BusyPolicy.QUEUE,
    )
}

/**
 * Create the chat, open it, send the first message
 * (`NewSessionViewController.swift:406-423` → `AppModel.swift:618-643`),
 * all inside one `withContext(io)` hop: **never** on the main thread
 * (the #609 trap — its port ran the FFI on main).
 *
 * Returns the scoped chat id, or `null` on any failure: no target
 * (no core call), no client, or `createSession` / `openSession` /
 * `send` threw. An offline host is not a failure: the chat row is a
 * workspace write and the core queues the first message for the host, as
 * on iOS (no Send gate, #37). A failure after the create can leave an empty chat
 * in the list; that partial failure is iOS parity (see the ticket's
 * Comments).
 */
class NewSessionCreator(
    private val core: NewSessionCore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Production logs `Log.w("NewSession", …)`; tests swap this (the stub `android.util.Log` throws). */
    private val logW: (String, Throwable?) -> Unit = { message, error ->
        Log.w("NewSession", message, error)
    },
) {

    /** The scoped chat id, or null on any failure (see the class docs). */
    suspend fun createAndSend(
        d: NewSessionDraft,
        text: String,
        images: List<StagedImage>,
        projects: List<ProjectOption>,
    ): String? {
        // No target: fail without a core call and without a log — the
        // alert explains it, and there is no error to log.
        val target = NewSessionRequests.target(d) ?: return null
        return withContext(io) {
            try {
                // Side chats never come here: New Side Chat creates one at
                // once from the session (`FreshSideChats.create`, #39).
                val chatId = core.createSession(NewSessionRequests.newSession(d, target))
                core.sendFirst(chatId, NewSessionRequests.sendRequest(d, text, images, projects))
                chatId
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logW("create session failed", t)
                null
            }
        }
    }
}

/**
 * The failure alert (`NewSessionViewController.swift:410-413`, copy
 * verbatim): no field, one default `OK` with no handler. Shown for a
 * missing target, a missing client, or any core failure (an unknown
 * target, RPC error) — the composer stays `Kept`.
 */
val StartFailedAlert = ZeronAlert(
    title = "Couldn't start the session",
    message = "Choose a project or a host that can run it.",
    buttons = listOf(AlertButton("OK", AlertButtonRole.Default)),
)

/**
 * A session on its own Cloud machine (#53; iOS `AppModel.createCloudSession`):
 * the edge mints the machine, then the first message is sent as usual — it
 * waits in the chat until the machine is up. A retry after a failed first
 * send reuses that chat (and machine) while nothing it was made with changed
 * (its project, branch, provider, model). Throws on failure (the caller
 * tells out of credits from the rest); runs the FFI on [io].
 */
class CloudSessionCreator(
    private val core: NewSessionCore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun createAndSend(d: NewSessionDraft, text: String, images: List<StagedImage>): String = withContext(io) {
        val spaceId = d.projectId ?: throw CoreException.Closed()
        val config = ChatConfig(
            harness = d.harness,
            model = d.model,
            reasoning = d.effort,
            modelOptions = emptyMap(),
            sandbox = SandboxLevel.WORKSPACE_WRITE,
        )
        val key = cloudChatKey(d)
        val unsent = unsentCloudChat
        val chatId = if (unsent != null && unsent.first == key && core.hasSession(unsent.second)) {
            unsent.second
        } else {
            core.createCloudSession(spaceId, config, d.branch).also { unsentCloudChat = key to it }
        }
        core.sendFirst(
            chatId,
            SendRequest(
                text = text,
                attachments = images.map { OutgoingAttachment(name = it.name, mimeType = "image/jpeg", data = it.bytes) },
                worktree = null,
                busy = BusyPolicy.QUEUE,
            ),
        )
        unsentCloudChat = null
        core.refreshWorkspace()
        chatId
    }

    companion object {
        /** iOS `AppModel.unsentCloudChat`: process-wide, like the app model. */
        @Volatile
        internal var unsentCloudChat: Pair<String, String>? = null

        /** The chat's identity for reuse: project, branch, provider, model. Pure. */
        fun cloudChatKey(d: NewSessionDraft): String = "${d.projectId}|${d.branch ?: ""}|${d.harness}|${d.model ?: ""}"
    }
}

/** #53: iOS's "Couldn't start on Cloud" alert, with the core's message. */
fun cloudStartFailedAlert(message: String) = ZeronAlert(
    title = "Couldn't start on Cloud",
    message = message,
    buttons = listOf(AlertButton("OK", AlertButtonRole.Default)),
)
