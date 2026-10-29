package sh.zeron.android.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.zeron.android.background.DeliveryHold
import sh.zeron.android.composer.DeliveryMode
import sh.zeron.android.composer.StagedImage
import uniffi.zeron_core.BusyPolicy
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.FileMatch
import uniffi.zeron_core.OutgoingAttachment
import uniffi.zeron_core.SendRequest
import uniffi.zeron_core.SessionHandle

/**
 * The FFI the composer needs (android-ui-parity #78): send, interrupt
 * and the `@file` search — the port of `CoreSessionSource.swift:110-131,
 * 247-249`. All suspend and fakeable in tests and the gallery; the live
 * implementation runs Zeron's synchronous `send`/`interrupt` on IO.
 *
 * iOS's `send` returns `Bool`; this one reports failures by throwing (the
 * binding turns that into the send-failure pill and a kept draft).
 */
interface SessionCommands {
    /**
     * One `SessionHandle.send` per call (see [busyPolicyFor]), preceded
     * by `interrupt()` for "Stop and send" while a turn runs; the
     * `SendOutcome` is ignored, as on iOS.
     */
    suspend fun send(text: String, images: List<StagedImage>, mode: DeliveryMode)

    /** `handle.interrupt()` — the running turn's stop. */
    suspend fun interrupt()

    /** `client.searchFiles` over the chat's workspace, `spaceId = nil`. */
    suspend fun searchFiles(query: String): List<FileMatch>
}

/**
 * The delivery mode → `BusyPolicy` table (`CoreSessionSource.swift:173`):
 * Zeron has no interrupting policy, so "Stop and send" interrupts the
 * running turn first and then queues, as iOS does.
 */
internal fun busyPolicyFor(mode: DeliveryMode): BusyPolicy = when (mode) {
    DeliveryMode.Queue -> BusyPolicy.QUEUE
    DeliveryMode.Steer -> BusyPolicy.STEER
    DeliveryMode.Interrupt -> BusyPolicy.QUEUE
}

/**
 * The live [SessionCommands] over one open session's client and handle
 * (`CoreSessionSource`'s `handle` / `client`). Built once per route by
 * ticket 76's `SessionScreenRoute` and handed to 78's
 * `rememberSessionComposer`.
 */
class CoreSessionCommands(
    private val client: CoreClient,
    private val handle: SessionHandle,
    private val chatId: String,
    /**
     * `composer.host.deviceId` as of the last chrome refresh (ticket 51's
     * `SessionController.hostDeviceId`) — read at call time, like iOS's
     * cached `hostDevice` (`CoreSessionSource.swift:42`).
     */
    private val hostDeviceId: () -> String,
) : SessionCommands {

    override suspend fun send(text: String, images: List<StagedImage>, mode: DeliveryMode) = withContext(Dispatchers.IO) {
        // iOS `CoreSessionSource.swift:172`.
        if (mode == DeliveryMode.Interrupt && handle.composer().live.turnRunning) handle.interrupt()
        handle.send(
            SendRequest(
                text = text,
                attachments = images.map { image ->
                    OutgoingAttachment(
                        name = image.name,
                        // iOS `AppModel.swift:647`: staged images are JPEG.
                        mimeType = "image/jpeg",
                        data = image.bytes,
                    )
                },
                worktree = null,
                busy = busyPolicyFor(mode),
            ),
        )
        // #58: iOS `app?.noteDeliveryActivity()` after an accepted send.
        DeliveryHold.noteActivity()
        Unit
    }

    override suspend fun interrupt() = withContext(Dispatchers.IO) { handle.interrupt() }

    override suspend fun searchFiles(query: String): List<FileMatch> =
        client.searchFiles(deviceId = hostDeviceId(), chatId = chatId, spaceId = null, query = query)
}
