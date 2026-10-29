package sh.zeron.android.sessions

import uniffi.zeron_core.ChatIndicator
import uniffi.zeron_core.ConnectivityState
import uniffi.zeron_core.Connectivity
import uniffi.zeron_core.DeviceView
import uniffi.zeron_core.FrontPage
import uniffi.zeron_core.PullRequestGroups
import uniffi.zeron_core.SectionView
import uniffi.zeron_core.SessionRow
import uniffi.zeron_core.WorkspaceSnapshot

/**
 * Zeron records for the sessions-area tests (#20), neutral everywhere the
 * tests don't look. The generated UniFFI records are plain Kotlin data
 * classes whose construction touches no JNA, so these run on the plain JVM.
 * Named `test*` so they never clash with the quarantined Roboco
 * `WorkspaceFixtures` when a later ticket revives it.
 */

internal fun testRow(
    id: String,
    title: String = id,
    timeLabel: String = "now",
    pinned: Boolean = false,
    sectionId: String? = null,
): SessionRow = SessionRow(
    id = id,
    revision = 1uL,
    title = title,
    hasTitle = true,
    preview = null,
    project = null,
    deviceId = "device",
    deviceName = null,
    deviceOnline = true,
    isCloud = false,
    harness = null,
    harnessLabel = null,
    model = null,
    modelLabel = null,
    reasoning = null,
    branch = null,
    cwd = null,
    indicator = ChatIndicator.IDLE,
    hostIndicator = ChatIndicator.IDLE,
    workingSinceMs = null,
    lastActivityMs = 0L,
    timeLabel = timeLabel,
    createdAtMs = 0L,
    unseen = false,
    archived = false,
    pinned = pinned,
    sectionId = sectionId,
    pullRequest = null,
    sendState = null,
    parentChatId = null,
    roomGen = 2u,
)

internal fun testSection(id: String, name: String = id, rows: List<SessionRow> = emptyList()) =
    SectionView(id = id, name = name, collapsed = false, sessions = rows)

internal fun testDevice(id: String, online: Boolean = true) = DeviceView(
    id = id,
    name = id,
    platform = "macos",
    online = online,
    lastSeenMs = null,
    version = null,
    capabilities = emptyList(),
    isExecutionHost = true,
    isSelf = false,
    sessionCount = 0u,
)

internal fun testWorkspace(
    revision: ULong,
    recent: List<SessionRow> = emptyList(),
    sections: List<SectionView> = emptyList(),
    devices: List<DeviceView> = emptyList(),
): WorkspaceSnapshot = WorkspaceSnapshot(
    revision = revision,
    computedAtMs = 0L,
    pinsReady = true,
    synced = true,
    front = FrontPage(pinned = emptyList(), sections = sections, recent = recent),
    projects = emptyList(),
    projectless = emptyList(),
    pullRequests = PullRequestGroups(open = emptyList(), merged = emptyList(), closed = emptyList()),
    archived = emptyList(),
    devices = devices,
)

internal fun testConnectivity(state: ConnectivityState) = Connectivity(
    state = state,
    retryAtMs = null,
    lastFailure = null,
    degradedChats = emptyList(),
)
