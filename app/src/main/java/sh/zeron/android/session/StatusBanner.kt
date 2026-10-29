package sh.zeron.android.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uniffi.zeron_core.ConnectivityState
import uniffi.zeron_core.CoreException
import uniffi.zeron_core.SendState

/** The lite copy of the FFI `SendState`: no UniFFI types in the pure derivation. */
enum class SendLite { Sending, Queued, Failed }

/** Everything [deriveBanner] reads (a lite copy of a [ChromeInput] plus the filler's sticky failure). */
data class BannerInputs(
    /** The last send threw; set until the next successful send. */
    val sendFailure: String? = null,
    /** `ComposerState.sendState` (the oldest pending send's headline state). */
    val sendState: SendLite? = null,
    /** `SessionRow.isCloud`: a Cloud chat's queued send waits for its machine, silently. */
    val isCloud: Boolean = false,
    /** `ComposerState.host.name`. */
    val hostName: String? = null,
    /** `CoreClient.connectivity().state == OFFLINE`. */
    val offline: Boolean = false,
    /** `ComposerState.room.connected`. */
    val roomConnected: Boolean = true,
    /** `ComposerState.room.retryAtMs`: the next redial, epoch ms. */
    val roomRetryAtMs: Long? = null,
)

/**
 * The one chrome banner per refresh (#28) — `CoreSessionSource.refresh`'s
 * precedence (`ios:Session/CoreSessionSource.swift:129-145`), first match
 * wins:
 *
 * 1. `Failed(sendFailure)` — the last send threw, sticky until the next send;
 * 2. `NotDelivered` — `sendState == FAILED`;
 * 3. `QUEUED` — `"<host> is offline — will send when it's back"`; a Cloud
 *    chat shows nothing (the transcript's setup step says it);
 * 4. `Sending` — `sendState == SENDING`;
 * 5. `Offline` — the device has no network (sends are saved);
 * 6. `Reconnecting(retryAtMs)` — the chat's room is down with a redial due;
 * 7. `None`.
 *
 * Pure. Never returns `Editing` (a display overlay, [shownBanner]) and
 * never `Working` (the transcript tail owns it).
 */
fun deriveBanner(i: BannerInputs): SessionChrome.Banner = when {
    i.sendFailure != null -> SessionChrome.Banner.Failed(i.sendFailure)
    i.sendState == SendLite.Failed -> SessionChrome.Banner.NotDelivered
    i.sendState == SendLite.Queued ->
        if (i.isCloud) {
            SessionChrome.Banner.None
        } else {
            SessionChrome.Banner.Failed("${i.hostName ?: "Host"} is offline — will send when it's back")
        }
    i.sendState == SendLite.Sending -> SessionChrome.Banner.Sending
    i.offline -> SessionChrome.Banner.Offline
    !i.roomConnected && i.roomRetryAtMs != null -> SessionChrome.Banner.Reconnecting(i.roomRetryAtMs)
    else -> SessionChrome.Banner.None
}

/**
 * The banner the pill **displays** — iOS `render`
 * (`SessionViewController.swift:765`): `editingQueueId != nil ? .editing : c.banner`.
 * The chrome banner itself never holds `Editing`, so editing toggles stay
 * non-structural (they snap).
 */
fun shownBanner(chromeBanner: SessionChrome.Banner, editing: Boolean): SessionChrome.Banner =
    if (editing) SessionChrome.Banner.Editing else chromeBanner

/** What a tap on the shown banner does (pure; [StatusBanner.onTap] dispatches it). */
sealed interface BannerTap {
    /** Nothing runs (the tap is still consumed at the hit-test level). */
    data object None : BannerTap

    /** Cancel the queued-message edit (iOS `endEdit(commit: nil)`). */
    data object CancelEdit : BannerTap

    /** `retryDelivery()` on the IO scope, errors logged and swallowed (iOS `try?`). */
    data object RetryDelivery : BannerTap
}

/**
 * The tap dispatch table (iOS `pill.onTap`, `SessionViewController.swift:173-179`):
 * `Editing` cancels the edit, `NotDelivered` retries. Every other banner
 * consumes the tap without doing anything, like the iOS `UIControl`.
 */
fun bannerTapAction(banner: SessionChrome.Banner): BannerTap =
    when (banner) {
        SessionChrome.Banner.Editing -> BannerTap.CancelEdit
        SessionChrome.Banner.NotDelivered -> BannerTap.RetryDelivery
        is SessionChrome.Banner.Failed,
        SessionChrome.Banner.Offline,
        is SessionChrome.Banner.Reconnecting,
        SessionChrome.Banner.Sending,
        is SessionChrome.Banner.Working,
        SessionChrome.Banner.None,
        -> BannerTap.None
    }

/**
 * The `<error>` text of a failed send — iOS prints Swift reflection;
 * Zeron prints the exception's `reason` (the Rust `#[error]` text).
 *
 * **Never read `CoreException.message`**: depending on the pinned UniFFI
 * version the generated override returns `reason` or `"reason=<value>"`.
 * `Closed` has no payload and maps to the Rust `#[error]` text. Any other
 * `Throwable` yields its message, or its class name when blank/null.
 */
fun errorText(t: Throwable): String = when (t) {
    is CoreException.NotFound -> t.reason
    is CoreException.InvalidArgument -> t.reason
    is CoreException.HostUnavailable -> t.reason
    is CoreException.Unsupported -> t.reason
    is CoreException.HostException -> t.reason
    is CoreException.Network -> t.reason
    is CoreException.Auth -> t.reason
    is CoreException.Storage -> t.reason
    is CoreException.NotImplemented -> t.reason
    is CoreException.Closed -> "client is shut down"
    is CoreException.Internal -> t.reason
    else -> t.message?.takeIf { it.isNotBlank() } ?: (t::class.simpleName ?: "error")
}

/**
 * The banner `ChromeFiller`: owns the sticky `sendFailure` and the tap
 * dispatch that the pill drives. Registered once per route in the
 * registry's BANNER slot (`ZeronApplication`).
 *
 * **Threading:** [fill] runs on the controller's IO lane (it touches no
 * FFI, only the lite copy); the `note*` setters and [onTap] run on main.
 * Every shared field is `@Volatile`.
 *
 * - **Send:** after `handle.send(...)` returns → `noteSendSucceeded()`; when it
 *   throws (not `CancellationException`) → `noteSendFailed(t)`, through [SendReporter].
 * - **Queue edit:** `banner.onEditingTap = { queueEdit.cancelEdit() }`.
 */
class StatusBanner(
    /** The chat's `retryDelivery` (runs on [io], never main — upstream #609's trap). */
    private val retryDelivery: suspend () -> Unit,
    io: CoroutineDispatcher = Dispatchers.IO,
    private val logW: (String, Throwable) -> Unit = { message, error ->
        android.util.Log.w("ZeronSession", message, error)
    },
) : ChromeFiller, SendReporter {
    /** Cancels the queued-message edit. Main thread. */
    @Volatile
    var onEditingTap: (() -> Unit)? = null

    /** The last send's failure text, or null. Sticky until the next successful send. */
    @Volatile
    private var sendFailure: String? = null

    /** The invalidate handle [bind] stores; the controller's coalescing trigger. */
    @Volatile
    private var invalidate: () -> Unit = {}

    /**
     * The retry lane. Never cancelled: a retry may finish (and invalidate)
     * after the screen closed, exactly like iOS's synchronous call.
     */
    private val scope = CoroutineScope(SupervisorJob() + io)

    /** A send returned normally — clears the sticky send failure. */
    override fun noteSendSucceeded() {
        if (sendFailure != null) {
            sendFailure = null
            invalidate()
        }
    }

    /** `handle.send` threw — holds `Couldn't send: <errorText>` until the next success. */
    override fun noteSendFailed(error: Throwable) {
        replaceSendFailure("Couldn't send: " + errorText(error))
    }

    /** The queue edit's recovery copy into the sticky send-failure slot, **verbatim**. */
    fun noteFailureMessage(message: String) {
        replaceSendFailure(message)
    }

    /**
     * Clears the send-failure slot only if it still holds exactly
     * [message] — so a later genuine `Couldn't send: …` is never wiped.
     */
    fun clearFailureMessage(message: String) {
        if (sendFailure == message) {
            sendFailure = null
            invalidate()
        }
    }

    /** The pill's tap. Main thread. */
    fun onTap(banner: SessionChrome.Banner) {
        when (bannerTapAction(banner)) {
            BannerTap.None -> Unit
            BannerTap.CancelEdit -> onEditingTap?.invoke()
            BannerTap.RetryDelivery -> scope.launch {
                try {
                    retryDelivery()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    // iOS swallows the error (`try?`); the log is all it gets.
                    logW("retryDelivery failed", t)
                }
                invalidate()
            }
        }
    }

    override fun bind(invalidate: () -> Unit) {
        this.invalidate = invalidate
    }

    /** Derives the banner from the lite inputs and the sticky failure; no FFI. */
    override fun fill(chrome: SessionChrome, input: ChromeInput): SessionChrome =
        chrome.copy(banner = deriveBanner(bannerInputs(input, sendFailure)))

    /** Sets the sticky send failure; invalidates only when the text changed. */
    private fun replaceSendFailure(next: String?) {
        if (sendFailure != next) {
            sendFailure = next
            invalidate()
        }
    }
}

/** The FFI `SendState` → [SendLite]. */
internal fun SendState.toSendLite(): SendLite = when (this) {
    SendState.SENDING -> SendLite.Sending
    SendState.QUEUED -> SendLite.Queued
    SendState.FAILED -> SendLite.Failed
}

/**
 * Maps a [ChromeInput] (uniffi records) plus the filler's sticky failure
 * into the pure [BannerInputs] (internal so the tests can drive it).
 *
 * TODO(#53): a Cloud chat's "Not delivered" becomes nothing when out of
 * credits, or "Cloud machine: … · Tap to retry" (iOS `cloudBanner`).
 */
internal fun bannerInputs(input: ChromeInput, sendFailure: String?): BannerInputs {
    val composer = input.composer
    return BannerInputs(
        sendFailure = sendFailure,
        sendState = composer.sendState?.toSendLite(),
        isCloud = input.row?.isCloud == true,
        hostName = composer.host.name,
        offline = input.connectivity?.state == ConnectivityState.OFFLINE,
        roomConnected = composer.room.connected,
        roomRetryAtMs = composer.room.retryAtMs,
    )
}
