package sh.zeron.android.session

import uniffi.zeron_core.ChatConfig
import uniffi.zeron_core.ChatIndicator
import uniffi.zeron_core.ComposerState
import uniffi.zeron_core.Connectivity
import uniffi.zeron_core.ConnectivityState
import uniffi.zeron_core.ContextUsage
import uniffi.zeron_core.DeviceView
import uniffi.zeron_core.HostCapabilities
import uniffi.zeron_core.HostInfo
import uniffi.zeron_core.LiveStatus
import uniffi.zeron_core.NoHandle
import uniffi.zeron_core.ProjectRef
import uniffi.zeron_core.PullRequest
import uniffi.zeron_core.PullRequestState
import uniffi.zeron_core.RoomState
import uniffi.zeron_core.SessionRow
import uniffi.zeron_core.TranscriptView

/**
 * Fixture builders for the session tests (android-ui-parity #51, revived
 * for Zeron's records in #63): the generated UniFFI records with neutral
 * defaults for every field the tests do not exercise. The records are
 * plain Kotlin data classes whose initialisation touches no JNA, so
 * these tests run on the plain JVM.
 */

/** A composer state with only the fields [baseChrome] reads set apart. */
internal fun composerState(
    title: String = "T",
    hostName: String? = null,
    deviceId: String = "dev",
    turnRunning: Boolean = false,
    midTurnSteering: Boolean? = null,
    transferProgress: Double? = null,
    queueError: String? = null,
    chatId: String = "chat-a",
    contextUsage: ContextUsage? = null,
): ComposerState = ComposerState(
    chatId = chatId,
    revision = 1uL,
    title = title,
    host = HostInfo(
        deviceId = deviceId,
        name = hostName,
        online = true,
        capabilities = HostCapabilities(
            messageQueue = true,
            queueActions = true,
            queueAttachments = true,
            cleanAttachmentText = true,
            queueEditLease = true,
            queuedAttachments = true,
            midTurnSteering = midTurnSteering,
            providerSwitch = false,
        ),
    ),
    live = LiveStatus(
        indicator = ChatIndicator.IDLE,
        turnRunning = turnRunning,
        workingSinceMs = null,
        streaming = false,
        canInterrupt = false,
    ),
    queue = emptyList(),
    pendingSends = emptyList(),
    sendState = null,
    deliveryDegraded = false,
    room = RoomState(connected = true, retryAtMs = null, degraded = false),
    transferProgress = transferProgress,
    openInput = null,
    queueError = queueError,
    lastSubmittedMessageId = null,
    contextUsage = contextUsage,
)

/** A session row with only the fields [baseChrome] and the chips read set apart. */
internal fun sessionRow(
    projectName: String? = null,
    harnessLabel: String? = null,
    id: String = "chat-a",
    pinned: Boolean = false,
    harness: String? = null,
    modelLabel: String? = null,
    reasoning: String? = null,
    branch: String? = null,
    pullRequest: PullRequest? = null,
    parentChatId: String? = null,
): SessionRow = SessionRow(
    id = id,
    revision = 1uL,
    title = "Session",
    hasTitle = true,
    preview = null,
    project = projectName?.let { ProjectRef(id = "project-1", name = it, colorIndex = 0u) },
    deviceId = "device",
    deviceName = null,
    deviceOnline = true,
    isCloud = false,
    harness = harness,
    harnessLabel = harnessLabel,
    model = null,
    modelLabel = modelLabel,
    reasoning = reasoning,
    branch = branch,
    cwd = null,
    indicator = ChatIndicator.IDLE,
    hostIndicator = ChatIndicator.IDLE,
    workingSinceMs = null,
    lastActivityMs = 0L,
    timeLabel = "now",
    createdAtMs = 0L,
    unseen = false,
    archived = false,
    pinned = pinned,
    sectionId = null,
    pullRequest = pullRequest,
    sendState = null,
    parentChatId = parentChatId,
    roomGen = 2u,
)

/** A PR fixture for the chip tests (78). */
internal fun pullRequest(
    number: Long,
    state: PullRequestState,
): PullRequest = PullRequest(
    provider = "github",
    number = number.toULong(),
    title = "Fixture PR",
    url = "https://github.com/zeron/zeron/pull/$number",
    state = state,
    baseRef = "main",
    headRef = "feature",
)

/** The host device (`CoreClient.devices()`'s entry for the chat's host). */
internal fun deviceView(
    id: String = "dev",
    name: String = "Vu-PC",
    version: String? = "1.0.0",
): DeviceView = DeviceView(
    id = id,
    name = name,
    platform = "macos",
    online = true,
    lastSeenMs = null,
    version = version,
    capabilities = emptyList(),
    isExecutionHost = true,
    isSelf = false,
    sessionCount = 0u,
)

/** The payload of a `ConnectivityChanged` event. */
internal fun connectivity(state: ConnectivityState = ConnectivityState.CONNECTED): Connectivity =
    Connectivity(state = state, retryAtMs = null, lastFailure = null, degradedChats = emptyList())

/**
 * The fake FFI seam: mutable return values, a call log and a
 * `composerCalls` counter. `composerFailures` makes the next N
 * `composer()` calls throw *after being counted*, so the failure paths
 * are observable. `attachTranscriptResult` exercises the logged
 * `false` branch of [SessionController.attach]. #46's
 * `setVisualSource` calls go to [visualSources] (not [callLog]), so the
 * lifecycle-order assertions stay about the lifecycle.
 */
internal class FakeSessionCore(
    override val chatId: String = "chat-a",
    /** 84's draft key reads this: the host device id. */
    override val engineId: String = "dev",
) : SessionCore {
    var composerResult: ComposerState = composerState(chatId = chatId)
    var sessionRowResult: SessionRow? = null

    /** Ticket 89: the chat's local config; `null` = no config yet. */
    var sessionConfigResult: ChatConfig? = null
    var engineResult: DeviceView? = null
    var attachTranscriptResult: Boolean = true
    var composerFailures: Int = 0

    /** When > 0, the next that many attachTranscript() calls throw. */
    var attachTranscriptFailures: Int = 0

    /** When > 0, the next that many setVisualSource() calls throw. */
    var visualSourceFailures: Int = 0

    val callLog = mutableListOf<String>()

    /** Every `setVisualSource(view, deviceId, pixelScale)`, in call order. */
    val visualSources = mutableListOf<Triple<TranscriptView, String, Float>>()

    var composerCalls: Int = 0
        private set

    override fun composer(): ComposerState {
        callLog += "composer"
        composerCalls++
        if (composerFailures > 0) {
            composerFailures--
            throw RuntimeException("composer boom")
        }
        return composerResult
    }

    override fun sessionRow(): SessionRow? {
        callLog += "sessionRow"
        return sessionRowResult
    }

    override fun sessionConfig(): ChatConfig? {
        callLog += "sessionConfig"
        return sessionConfigResult
    }

    override fun engine(): DeviceView? {
        callLog += "engine"
        return engineResult
    }

    override fun attachTranscript(view: TranscriptView): Boolean {
        callLog += "attachTranscript"
        if (attachTranscriptFailures > 0) {
            attachTranscriptFailures--
            throw RuntimeException("attach boom")
        }
        return attachTranscriptResult
    }

    override fun setVisualSource(view: TranscriptView, deviceId: String, pixelScale: Float) {
        if (visualSourceFailures > 0) {
            visualSourceFailures--
            throw RuntimeException("visual source boom")
        }
        visualSources += Triple(view, deviceId, pixelScale)
    }

    override fun setViewAttached(attached: Boolean) {
        callLog += "setViewAttached($attached)"
    }

    override fun markSeen() {
        callLog += "markSeen"
    }
}

/** Identity-distinct transcript views without a Rust engine behind them. */
internal fun fakeTranscriptView(): TranscriptView = TranscriptView(NoHandle)
