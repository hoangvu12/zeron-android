package sh.zeron.android.session

import uniffi.zeron_core.ChatConfig
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.ComposerState
import uniffi.zeron_core.DeviceView
import uniffi.zeron_core.SessionHandle
import uniffi.zeron_core.SessionRow
import uniffi.zeron_core.TranscriptView

/**
 * The narrow FFI surface [SessionController] needs (android-ui-parity
 * #51) — fakeable, because the generated `CoreClient` /
 * `SessionHandle` are JNA-backed objects that cannot be constructed on
 * the JVM. Every method blocks in FFI and must run on the controller's
 * serial IO lane, never on main.
 *
 * Names follow the UniFFI output of `crates/mobile/src/client_ffi`
 * (`chatId()`, `composer()`, `sessionRow(chatId)`, `devices()`,
 * `markSeen(chatId)`, `setViewAttached(attached)`,
 * `TranscriptView.attach(client, chatId)`).
 */
interface SessionCore {
    /** The session's scoped chat id (`SessionHandle.chatId()`). */
    val chatId: String

    /**
     * The host device's id (`ComposerState.host.deviceId`; Roboco's
     * `SessionHandle.engineId()`), read once. The device half of the
     * per-chat draft key (ticket 84: `DraftKey.Chat(engineId, chatId)`).
     */
    val engineId: String

    /** `SessionHandle.composer()` — the composer-facing pull. */
    fun composer(): ComposerState

    /** `CoreClient.sessionRow(chatId)` — the cached workspace row, any chat. */
    fun sessionRow(): SessionRow?

    /**
     * `CoreClient.sessionConfig(chatId)` — the chat's local run config
     * (android-app#07). Ticket 89's chip menus read it on the controller's
     * IO lane so their provider never makes an FFI call on main.
     */
    fun sessionConfig(): ChatConfig?

    /** The host's [DeviceView] from `CoreClient.devices()`, or null when unknown. */
    fun engine(): DeviceView?

    /**
     * `TranscriptView.attach(client, chatId)` (android-app#08's layout
     * seam). `false` means the chat has no open session; the controller
     * logs it and carries on (iOS ignores the Bool too,
     * `CoreSessionSource.swift:26-29`).
     */
    fun attachTranscript(view: TranscriptView): Boolean

    /** `SessionHandle.setViewAttached(attached)`. */
    fun setViewAttached(attached: Boolean)

    /** `CoreClient.markSeen(chatId)` — through the client, not the handle (`AppModel.swift:519`). */
    fun markSeen()
}

/**
 * The live [SessionCore] over one open session. Ticket 76 builds this
 * after `CoreClient.openSession(chatId)` succeeded, off main; sibling
 * adapters (78, 82, 83, 84, 86, 89) use [client]/[handle] for their own
 * calls. [engineId] is read once in the constructor — it never changes
 * — so the first `engine()` pull and every later one cost one FFI call
 * less, and 84's draft key reads it without another call.
 */
class CoreSessionCore(
    val client: CoreClient,
    val handle: SessionHandle,
) : SessionCore {
    override val chatId: String = handle.chatId()

    override val engineId: String = handle.composer().host.deviceId

    override fun composer(): ComposerState = handle.composer()

    override fun sessionRow(): SessionRow? = client.sessionRow(chatId)

    override fun sessionConfig(): ChatConfig? = client.sessionConfig(chatId)

    override fun engine(): DeviceView? = client.devices().firstOrNull { it.id == engineId }

    override fun attachTranscript(view: TranscriptView): Boolean = view.attach(client, chatId)

    override fun setViewAttached(attached: Boolean) = handle.setViewAttached(attached)

    override fun markSeen() = client.markSeen(chatId)
}
