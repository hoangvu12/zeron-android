package sh.zeron.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.zeron_core.ChatIndicator
import uniffi.zeron_core.ComposerState
import uniffi.zeron_core.Connectivity
import uniffi.zeron_core.ConnectivityState
import uniffi.zeron_core.HostCapabilities
import uniffi.zeron_core.HostInfo
import uniffi.zeron_core.LiveStatus
import uniffi.zeron_core.ProjectRef
import uniffi.zeron_core.RoomState
import uniffi.zeron_core.SendState
import uniffi.zeron_core.SessionRow

/**
 * [baseChrome] and [bannerInputs] over Zeron's records: the subtitle and
 * placeholder table (`CoreSessionSource.swift:95-103`), Cloud's host name,
 * the side chat's parent (#33) and the pill's inputs (#28). The uniffi
 * records are plain Kotlin data classes; building them touches no JNA.
 */
class SessionChromeTest {

    @Test
    fun theSubtitleAndPlaceholderTable() {
        fun row(project: String?, harness: String?, host: String?): Pair<String, String> {
            val chrome = baseChrome(composer(hostName = host), sessionRow(projectName = project, harnessLabel = harness))
            return chrome.subtitle to chrome.placeholder
        }
        assertEquals("zeron @ Vu-PC" to "Message Claude Code", row("zeron", "Claude Code", "Vu-PC"))
        assertEquals("zeron" to "Message Codex", row("zeron", "Codex", null))
        assertEquals("No project @ Vu-PC" to "Message Claude Code", row(null, "Claude Code", "Vu-PC"))
        val noRow = baseChrome(composer(hostName = null), null)
        assertEquals("No project" to "Message the agent", noRow.subtitle to noRow.placeholder)
    }

    @Test
    fun aCloudChatRunsOnCloudNotItsMachine() {
        val chrome = baseChrome(
            composer(hostName = "cloud-vm-7f3a"),
            sessionRow(projectName = "zeron", isCloud = true, deviceName = "Cloud"),
        )
        assertEquals("zeron @ Cloud", chrome.subtitle)
    }

    @Test
    fun aSideChatCarriesItsParentsTitle() {
        assertNull(baseChrome(composer(), sessionRow(), parentTitle = "ignored").sideChatParent)
        assertEquals("Fix CI", baseChrome(composer(), sessionRow(parentChatId = "p"), "Fix CI").sideChatParent)
        // The parent row is not known yet: still a side chat.
        assertEquals("", baseChrome(composer(), sessionRow(parentChatId = "p"), null).sideChatParent)
        assertNotEquals(SessionChrome(), SessionChrome(sideChatParent = ""))
    }

    @Test
    fun bannerInputsReadTheComposerTheRowAndConnectivity() {
        val input = ChromeInput(
            chatId = "c",
            composer = composer(
                hostName = "MacBook",
                sendState = SendState.QUEUED,
                room = RoomState(connected = false, retryAtMs = 42L, degraded = false),
            ),
            row = sessionRow(isCloud = true),
            engine = null,
            connectivity = Connectivity(
                state = ConnectivityState.OFFLINE,
                retryAtMs = null,
                lastFailure = null,
                degradedChats = emptyList(),
            ),
        )
        assertEquals(
            BannerInputs(
                sendFailure = "x",
                sendState = SendLite.Queued,
                isCloud = true,
                hostName = "MacBook",
                offline = true,
                roomConnected = false,
                roomRetryAtMs = 42L,
            ),
            bannerInputs(input, "x"),
        )
        // Unknown connectivity is not offline.
        assertEquals(false, bannerInputs(input.copy(connectivity = null), null).offline)
    }

    private fun composer(
        hostName: String? = null,
        sendState: SendState? = null,
        room: RoomState = RoomState(connected = true, retryAtMs = null, degraded = false),
    ): ComposerState = ComposerState(
        chatId = "chat-a",
        revision = 1uL,
        title = "T",
        host = HostInfo(
            deviceId = "dev",
            name = hostName,
            online = true,
            capabilities = HostCapabilities(
                messageQueue = true,
                queueActions = true,
                queueAttachments = true,
                cleanAttachmentText = true,
                queueEditLease = true,
                queuedAttachments = true,
                midTurnSteering = null,
                providerSwitch = false,
            ),
        ),
        live = LiveStatus(
            indicator = ChatIndicator.IDLE,
            turnRunning = false,
            workingSinceMs = null,
            streaming = false,
            canInterrupt = false,
        ),
        queue = emptyList(),
        pendingSends = emptyList(),
        sendState = sendState,
        deliveryDegraded = false,
        room = room,
        transferProgress = null,
        openInput = null,
        queueError = null,
        lastSubmittedMessageId = null,
        contextUsage = null,
    )

    private fun sessionRow(
        projectName: String? = null,
        harnessLabel: String? = null,
        isCloud: Boolean = false,
        deviceName: String? = null,
        parentChatId: String? = null,
    ): SessionRow = SessionRow(
        id = "chat-a",
        revision = 1uL,
        title = "Session",
        hasTitle = true,
        preview = null,
        project = projectName?.let { ProjectRef(id = "project-1", name = it, colorIndex = 0u) },
        deviceId = "device",
        deviceName = deviceName,
        deviceOnline = true,
        isCloud = isCloud,
        harness = null,
        harnessLabel = harnessLabel,
        model = null,
        modelLabel = null,
        reasoning = null,
        branch = null,
        cwd = null,
        indicator = ChatIndicator.IDLE,
        hostIndicator = ChatIndicator.IDLE,
        workingSinceMs = null,
        lastActivityMs = 0L,
        timeLabel = "now",
        createdAtMs = 0L,
        unseen = false,
        archived = false,
        pinned = false,
        sectionId = null,
        pullRequest = null,
        sendState = null,
        parentChatId = parentChatId,
        roomGen = 2u,
    )
}
