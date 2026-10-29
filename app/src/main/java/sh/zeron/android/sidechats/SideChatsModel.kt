package sh.zeron.android.sidechats

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.zeron.android.session.SessionEventHub
import sh.zeron.android.sessions.SessionRowVM
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.SessionHandle
import uniffi.zeron_core.SubagentRow
import uniffi.zeron_core.SubagentState

/** Where a subagent stands — its spawn chip's lifecycle, never `resolved` alone. */
enum class AgentState { Running, Done, Failed }

/**
 * One subagent of an open chat, as the sheet lists it (the port of
 * upstream #899's `SubagentInfo`; Zeron's `SubagentRow`). A plain mirror of the FFI record so
 * the sheet and its tests never touch the generated types.
 */
@Immutable
data class SideChatsAgent(
    /** Its transcript doc (`{chatId}--sub--{id}`, scoped): the viewer's id. */
    val docId: String,
    /** What the spawn asked for. */
    val title: String,
    val state: AgentState,
    /** One-line live tail of its output, while it runs. */
    val tail: String? = null,
    /** The model the spawn picked, when it named one. */
    val model: String? = null,
    /** When the spawning reply was written (epoch ms). */
    val spawnedAtMs: Long = 0L,
) {
    val running: Boolean get() = state == AgentState.Running

    companion object {
        /**
         * Null while the subagent has no transcript doc yet (Zeron's
         * `SubagentRow.docId` is optional): there is nothing to open.
         * TODO(#40): the companions model lists those too.
         */
        fun from(info: SubagentRow): SideChatsAgent? = info.docId?.let { docId ->
            SideChatsAgent(
                docId = docId,
                title = info.title,
                state = agentState(info.state),
                tail = info.tail,
                model = info.model,
                spawnedAtMs = info.startedAtMs,
            )
        }
    }
}

/** The FFI lifecycle → [AgentState]. Exhaustive: a new case fails to compile. */
fun agentState(state: SubagentState): AgentState = when (state) {
    SubagentState.RUNNING -> AgentState.Running
    SubagentState.DONE -> AgentState.Done
    SubagentState.FAILED -> AgentState.Failed
}

/**
 * The header button's badge (upstream #899's `syncBar`): `● N` on the
 * accent while subagents run, else a quiet count of everything hanging
 * off the chat (the default red would read as unread).
 */
@Immutable
data class SideChatsBadge(val running: Int, val total: Int) {
    val text: String get() = if (running > 0) "● $running" else total.toString()
    val accent: Boolean get() = running > 0

    /** iOS `accessibilityValue`: "N running" while anything runs. */
    val a11yValue: String? get() = if (running > 0) "$running running" else null
}

/** Everything the sheet and the header button render for one chat. */
@Immutable
data class SideChatsUi(
    /** Running subagents, newest spawn first. */
    val running: List<SideChatsAgent> = emptyList(),
    /** Side chats (and agent-spawned chats) hanging off the chat, unarchived, recency order. */
    val chats: List<SessionRowVM> = emptyList(),
    /** Finished (done or failed) subagents, newest spawn first. */
    val finished: List<SideChatsAgent> = emptyList(),
) {
    val total: Int get() = running.size + chats.size + finished.size

    /** `null` — the button hides — while nothing hangs off the chat (iOS `total > 0`). */
    val badge: SideChatsBadge? get() = if (total > 0) SideChatsBadge(running.size, total) else null

    companion object {
        /**
         * The sections from one pull: the spawn chips split by lifecycle,
         * each newest first (the chips arrive in spawn order), and the
         * children as the workspace orders them. Pure.
         */
        fun of(agents: List<SideChatsAgent>, children: List<SessionRowVM>): SideChatsUi = SideChatsUi(
            running = agents.filter { it.running }.asReversed(),
            chats = children,
            finished = agents.filter { !it.running }.asReversed(),
        )
    }
}

/**
 * The pull seam behind [SideChatsModel]: the generated `CoreClient` and
 * `SessionHandle` are JNA objects the JVM cannot construct, so tests fake
 * this. Blocking FFI — the model calls it on its IO dispatcher only.
 */
interface SideChatsSource {
    /** The subagents the chat's open transcript spawned, in spawn order. */
    fun agents(): List<SideChatsAgent>

    /**
     * `CoreClient.childSessions(parentId)` — the side chats hanging off
     * [parentChatId], archived ones left out, as session-row view models.
     */
    fun children(parentChatId: String): List<SessionRowVM>
}

/**
 * The live [SideChatsSource]: the spawn chips of the route's own open
 * handle (the chip IS the index — no listing RPC) and the workspace's
 * children of the chat.
 */
class CoreSideChatsSource(
    private val client: () -> CoreClient?,
    private val handle: SessionHandle,
) : SideChatsSource {
    override fun agents(): List<SideChatsAgent> = handle.subagents().mapNotNull { SideChatsAgent.from(it) }

    override fun children(parentChatId: String): List<SessionRowVM> =
        client()?.childSessions(parentChatId).orEmpty()
            .filter { !it.archived }
            .map { SessionRowVM.from(it) }
}

/**
 * One chat's side chats and subagents (android-next #06): the header
 * button's badge and the sheet's rows, as [ui].
 *
 * The refresh shape is the session controller's (the #609/#621 rules):
 * [SessionEventHub] events only `trySend` a conflated token (a
 * `SessionChanged` for the chat moves the spawn chips, a
 * `WorkspaceChanged` the children — both reach the same refresh), the
 * pulls run on [io], and [ui] is written from [scope]'s dispatcher (main
 * in production). A failed pull keeps the previous rows.
 *
 * [start] once when the screen first composes; [stop] on the route's
 * release (idempotent; a stopped model never restarts).
 */
@Stable
class SideChatsModel(
    private val parentChatId: String,
    private val source: SideChatsSource,
    private val hub: SessionEventHub,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val logW: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private val _ui = MutableStateFlow(SideChatsUi())

    /** The current sections; empty (badge hidden) until the first pull lands. */
    val ui: StateFlow<SideChatsUi> = _ui.asStateFlow()

    private var started = false
    private var stopped = false
    private var unregister: (() -> Unit)? = null

    /** Register on the hub and pull once. Main thread; idempotent. */
    fun start() {
        if (started || stopped) return
        started = true
        unregister = hub.register(parentChatId) { trigger.trySend(Unit) }
        trigger.trySend(Unit)
        scope.launch {
            for (token in trigger) refresh()
        }
    }

    /** Another pull, coalesced with any pending one (e.g. right after a fork). */
    fun invalidate() {
        if (!stopped) trigger.trySend(Unit)
    }

    /** Unregister and stop pulling. Main thread; idempotent. */
    fun stop() {
        if (stopped) return
        stopped = true
        unregister?.invoke()
        unregister = null
        trigger.close()
        scope.cancel()
    }

    private suspend fun refresh() {
        val next = try {
            withContext(io) { SideChatsUi.of(source.agents(), source.children(parentChatId)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            logW("side chats refresh failed", t)
            return
        }
        _ui.value = next
    }
}

/**
 * Whether an engine serves side chats (`ForkSideChat`, `createChat` with
 * `parentChatId`), from its reported version: they shipped in Zeron
 * 0.5.0. An unknown or unparsable version reads as supported (a fork on
 * an engine without the RPC then fails with the engine's own reason).
 * Pure.
 */
fun sideChatsSupported(engineVersion: String?): Boolean {
    // `major.minor` of a lenient semver: a leading `v`, build metadata and
    // the pre-release are ignored, a missing minor reads as 0.
    val parts = engineVersion?.trim()?.removePrefix("v")
        ?.substringBefore('+')?.substringBefore('-')?.split('.')
        ?: return true
    val major = parts[0].toLongOrNull() ?: return true
    val minor = parts.getOrElse(1) { "0" }.toLongOrNull() ?: return true
    return major > 0L || minor >= 5L
}
