package sh.zeron.android.sessions

import uniffi.zeron_core.ChatIndicator
import uniffi.zeron_core.ConnectivityState
import uniffi.zeron_core.Connectivity
import uniffi.zeron_core.DeviceView
import uniffi.zeron_core.FrontPage
import uniffi.zeron_core.ProjectRef
import uniffi.zeron_core.ProjectView
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
    project: ProjectRef? = null,
    deviceId: String = "device",
    deviceName: String? = null,
): SessionRow = SessionRow(
    id = id,
    revision = 1uL,
    title = title,
    hasTitle = true,
    preview = null,
    project = project,
    deviceId = deviceId,
    deviceName = deviceName,
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

/** A [ProjectRef] as a row carries it. */
internal fun testRef(id: String, name: String = id) = ProjectRef(id = id, name = name, colorIndex = 0u)

/** A project in one repository group ([groupKey]/[groupName]). */
internal fun testProject(id: String, groupKey: String, groupName: String) = ProjectView(
    id = id,
    name = id,
    path = "/src/$id",
    colorIndex = 0u,
    deviceId = "device",
    deviceName = null,
    deviceOnline = true,
    isCloud = false,
    githubRepo = null,
    gitDetected = true,
    createdAtMs = 0L,
    groupKey = groupKey,
    groupName = groupName,
    indicator = ChatIndicator.IDLE,
    unseenCount = 0u,
    sessions = emptyList(),
)

internal fun testFront(
    pinned: List<SessionRow> = emptyList(),
    sections: List<SectionView> = emptyList(),
    recent: List<SessionRow> = emptyList(),
) = FrontPage(pinned = pinned, sections = sections, recent = recent)

internal fun testWorkspace(
    revision: ULong,
    recent: List<SessionRow> = emptyList(),
    sections: List<SectionView> = emptyList(),
    devices: List<DeviceView> = emptyList(),
    pinned: List<SessionRow> = emptyList(),
    projects: List<ProjectView> = emptyList(),
): WorkspaceSnapshot = WorkspaceSnapshot(
    revision = revision,
    computedAtMs = 0L,
    pinsReady = true,
    synced = true,
    front = testFront(pinned, sections, recent),
    projects = projects,
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
