package sh.zeron.android.accounts

import kotlinx.coroutines.delay
import uniffi.zeron_core.CoreException
import uniffi.zeron_core.DeviceAccountInfo
import kotlin.coroutines.cancellation.CancellationException

/**
 * Signs a computer's agent in (#50, iOS `DeviceSignIn`): the computer
 * starts the sign-in, the page opens here, and the browser's return
 * reaches the computer through the phone (the core relays it via
 * `zeron://signed-in`). Device-code sign-ins finish on their own once the
 * code is entered; Claude's paste fallback takes the code back.
 *
 * @param open opens a provider page (an Auth Tab that closes itself on the
 *   relayed return); closing it by hand doesn't end the sign-in
 * @param askForCode the "Paste the code" prompt; `null` = cancelled
 * @param copy puts a device code ("ABCD-1234") on the clipboard
 */
class DeviceSignIn(
    private val core: ProvidersCore,
    private val open: (url: String) -> Unit,
    private val askForCode: suspend () -> String?,
    private val copy: (code: String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val pollMs: Long = POLL_MS,
) {
    @Volatile
    private var cancelled = false

    /**
     * Runs to completion: the device's fresh accounts when the computer sent
     * them back (the paste flow), `null` to re-read them. Throws
     * [CancellationException] when called off, a [CoreException] on failure.
     */
    suspend fun run(device: String, harness: String, provider: String?, message: (String?) -> Unit): List<DeviceAccountInfo>? {
        cancelled = false
        val start = core.loginStart(device, harness, provider)
        if (start.pasteCode) {
            open(start.url)
            val code = askForCode()
            if (code == null) {
                core.loginCancel(device, start.loginId)
                throw CancellationException("No code")
            }
            return core.loginComplete(device, start.loginId, code)
        }
        var opened = false
        if (start.url.isNotEmpty()) {
            open(start.url)
            opened = true
        }
        val deadline = nowMs() + DEADLINE_MS
        while (nowMs() < deadline) {
            if (cancelled) {
                core.loginCancel(device, start.loginId)
                throw CancellationException("Cancelled")
            }
            delay(pollMs)
            val poll = core.loginPoll(device, start.loginId)
            when (poll.status) {
                "done" -> return null
                "error" -> throw CoreException.HostException(poll.message ?: AccountsCopy.SignInFailedReason)
                else -> {
                    val url = poll.url
                    if (url != null && !opened) {
                        open(url)
                        opened = true
                    }
                    poll.message?.let { note ->
                        message(note)
                        // "Enter the code ABCD-1234 when asked": have it ready.
                        deviceCodeIn(note)?.let(copy)
                    }
                }
            }
        }
        core.loginCancel(device, start.loginId)
        throw CoreException.HostException(AccountsCopy.TimedOut)
    }

    /** Calls it off (the poll loop notices and cancels on the computer). */
    fun cancel() {
        cancelled = true
    }

    companion object {
        const val POLL_MS = 1_500L
        const val DEADLINE_MS = 10 * 60 * 1_000L
    }
}
