package sh.zeron.android.sessions

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import sh.zeron.android.core.WorkspaceCore
import sh.zeron.android.core.WorkspaceFeed
import uniffi.zeron_core.Connectivity
import uniffi.zeron_core.ConnectivityState
import uniffi.zeron_core.WorkspaceSnapshot

/**
 * The workspace fakes for the session-list tests (android-ui-parity #40,
 * revived for #23 on Zeron's core): a [WorkspaceCore] whose snapshot and
 * connectivity a test sets directly, and a [WorkspaceFeed] over it on the
 * test scheduler. The records come from `SessionsTestRecords.kt`; they are
 * plain Kotlin data classes whose construction touches no JNA.
 */

/** The [WorkspaceCore] fake: mutable return values and a read counter. */
internal class FakeWorkspaceCore(
    var ws: WorkspaceSnapshot = testWorkspace(0uL),
    var connectivityValue: Connectivity = testConnectivity(ConnectivityState.CONNECTED),
) : WorkspaceCore {

    /** `workspace()` call count. */
    val workspaceCalls = AtomicInteger()

    override fun workspaceRevision(): ULong = ws.revision

    override fun workspace(): WorkspaceSnapshot {
        workspaceCalls.incrementAndGet()
        return ws
    }

    override fun connectivity(): Connectivity = connectivityValue
}

/** A [WorkspaceFeed] over [core], every lane on this test's scheduler. */
internal fun TestScope.testFeed(core: FakeWorkspaceCore = FakeWorkspaceCore()): WorkspaceFeed {
    val io = StandardTestDispatcher(testScheduler)
    return WorkspaceFeed(core = { core }, scope = CoroutineScope(SupervisorJob() + io), io = io)
}
