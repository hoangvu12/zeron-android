package sh.zeron.android.sidechats

import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.zeron_core.CoreClient

/** The FFI a side chat's life needs (a seam: the generated client is a JNA object tests can't build). */
interface SideChatCore {
    /** `CoreClient.createSideChat`: the parent's host, folder and agent. Sync FFI; off main. */
    fun createSideChat(parentId: String): String

    /** The row's "has a message" and "created at", or `null` when the workspace doesn't know it. */
    fun row(chatId: String): SideChatRow?

    fun deleteSession(chatId: String)
}

/** What the sweep reads from a `SessionRow`. */
data class SideChatRow(val preview: String?, val createdAtMs: Long)

/** [SideChatCore] over the live client. */
class CoreSideChatCore(private val client: CoreClient) : SideChatCore {
    override fun createSideChat(parentId: String): String = client.createSideChat(parentId)

    override fun row(chatId: String): SideChatRow? =
        client.sessionRow(chatId)?.let { SideChatRow(it.preview, it.createdAtMs) }

    override fun deleteSession(chatId: String) = client.deleteSession(chatId)
}

/** The copy (iOS `CompanionsViewController.perform`, `openSideChat`). */
object SideChatCopy {
    const val StartFailed = "Couldn't start a side chat"
}

/**
 * Side chats started here that nothing has been sent in yet (#39, iOS
 * `AppModel.freshSideChats`, `ios:App/AppModel.swift:587-625`). New Side
 * Chat mints the chat at once ([create]); closed unused it goes
 * ([closeUnused], iOS `SessionViewController.teardown`), and the workspace
 * sweep ([sweep]) deletes one nobody has open that is still empty a
 * minute on, or forgets one that got a message from any device. Kept
 * across launches (`freshSideChats` in [prefs]), so one left open when
 * the app was killed goes too.
 *
 * Thread-safe; every FFI call runs on [io].
 */
class FreshSideChats(
    private val prefs: SharedPreferences,
    private val core: () -> SideChatCore?,
    private val refreshWorkspace: () -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val logW: (String, Throwable?) -> Unit = { message, error -> Log.w("ZeronSideChats", message, error) },
) {
    private val lock = Any()
    private val ids: MutableSet<String> = HashSet(prefs.getStringSet(KEY, emptySet()).orEmpty())

    /** Session screens open per chat (iOS `SessionViewController.live`). */
    private val open = HashMap<String, Int>()

    /** A copy of the fresh set. */
    val fresh: Set<String> get() = synchronized(lock) { HashSet(ids) }

    fun contains(chatId: String): Boolean = synchronized(lock) { chatId in ids }

    /** iOS `forgetFreshSideChat`. */
    fun forget(chatId: String) = synchronized(lock) {
        if (ids.remove(chatId)) persist()
    }

    /** A session screen for [chatId] appeared (main). */
    fun opened(chatId: String) = synchronized(lock) { open[chatId] = (open[chatId] ?: 0) + 1 }

    /** A session screen for [chatId] went for good (main). */
    fun closed(chatId: String) = synchronized(lock) {
        val n = (open[chatId] ?: 0) - 1
        if (n > 0) open[chatId] = n else open.remove(chatId)
    }

    /**
     * iOS `createSideChat(parentId:)`: a fresh side chat under [parentId]
     * (the root, never a side chat), recorded as fresh, the workspace
     * refreshed. `null` when it couldn't be made (logged).
     */
    suspend fun create(parentId: String): String? = withContext(io) {
        try {
            val c = core() ?: return@withContext null
            val id = c.createSideChat(parentId)
            synchronized(lock) {
                ids.add(id)
                persist()
            }
            refreshWorkspace()
            id
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            logW("side chat failed", t)
            null
        }
    }

    /**
     * A session screen for [chatId] went for good: a fresh side chat left
     * without a word — nothing sent or queued ([isUnused], read on [io]),
     * an empty draft, no preview — is deleted (iOS `teardown`).
     */
    fun closeUnused(chatId: String, draftEmpty: Boolean, isUnused: () -> Boolean) {
        if (!draftEmpty || !contains(chatId)) return
        scope.launch(io) {
            try {
                val c = core() ?: return@launch
                if (!isUnused() || c.row(chatId)?.preview != null) return@launch
                forget(chatId)
                c.deleteSession(chatId)
                refreshWorkspace()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logW("delete unused side chat failed", t)
            }
        }
    }

    /**
     * iOS `sweepFreshSideChats` (on each workspace refresh): a fresh chat
     * that got a message is just a side chat now; one nobody has open,
     * empty and over a minute old, goes.
     */
    fun sweep() {
        if (synchronized(lock) { ids.isEmpty() }) return
        scope.launch(io) {
            val c = core() ?: return@launch
            var deleted = false
            for (id in fresh) {
                try {
                    val row = c.row(id) ?: continue
                    val isOpen = synchronized(lock) { (open[id] ?: 0) > 0 }
                    if (row.preview != null) {
                        forget(id)
                    } else if (!isOpen && now() - row.createdAtMs > SWEEP_AFTER_MS) {
                        forget(id)
                        c.deleteSession(id)
                        deleted = true
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    logW("side chat sweep failed", t)
                }
            }
            if (deleted) refreshWorkspace()
        }
    }

    private fun persist() {
        prefs.edit().putStringSet(KEY, HashSet(ids)).apply()
    }

    companion object {
        /** iOS's `UserDefaults` key. */
        const val KEY = "freshSideChats"

        /** iOS: empty a minute on. */
        const val SWEEP_AFTER_MS = 60_000L
    }
}
