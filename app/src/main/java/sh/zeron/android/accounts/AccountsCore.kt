package sh.zeron.android.accounts

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.zeron_core.CoreClient

/**
 * The FFI seam of the agent-account surfaces (android-next#05) — the four
 * `CoreClient` calls behind the usage chip, the Settings section and the
 * Accounts & Usage page. Every member is FFI: call only from an IO lane,
 * never on main. A seam so the plain-JVM tests fake it (the generated
 * records construct without JNA, the calls do not); failures cross as
 * the generated `CoreException` (see [describeAccountsError]).
 */
interface AccountsCore {

    /**
     * `CoreClient.deviceAgentAccounts(deviceId)` — the host's last usage
     * probe. Zeron has no forced re-probe, so [forceUsage] is ignored
     * (TODO(#50): drop it with the iOS single-read load).
     */
    suspend fun list(deviceId: String, forceUsage: Boolean): AgentAccountsSnapshot

    /**
     * `CoreClient.deviceActivateAccount(deviceId, harness, accountId)` —
     * make the login the one in use; replies with the updated accounts.
     */
    suspend fun activate(deviceId: String, harness: String, accountId: String): AgentAccountsSnapshot

    /**
     * `CoreClient.deviceForgetAccount(deviceId, harness, accountId)` —
     * remove a saved login; replies with the updated accounts.
     */
    suspend fun forget(deviceId: String, harness: String, accountId: String): AgentAccountsSnapshot

    /**
     * The newest snapshot this process saw for [deviceId] (every
     * successful list/activate/forget refreshes it), so a screen paints at
     * once and refreshes behind it. `null` before the first success. Zeron's
     * core keeps no account cache, so this one is Kotlin-side, as iOS
     * Providers keeps `accounts[deviceId]`.
     */
    fun cached(deviceId: String): AgentAccountsSnapshot?
}

/**
 * The live [AccountsCore] over the process's single [CoreClient].
 * `deviceId` is the id shape the platform holds (the session's host
 * device id, a workspace `DeviceView` id).
 */
class CoreAccountsCore(
    private val client: () -> CoreClient?,
) : AccountsCore {
    private val cache = ConcurrentHashMap<String, AgentAccountsSnapshot>()

    override suspend fun list(deviceId: String, forceUsage: Boolean): AgentAccountsSnapshot =
        remember(deviceId, withContext(Dispatchers.IO) { live().deviceAgentAccounts(deviceId) })

    override suspend fun activate(
        deviceId: String,
        harness: String,
        accountId: String,
    ): AgentAccountsSnapshot =
        remember(deviceId, withContext(Dispatchers.IO) { live().deviceActivateAccount(deviceId, harness, accountId) })

    override suspend fun forget(
        deviceId: String,
        harness: String,
        accountId: String,
    ): AgentAccountsSnapshot =
        remember(deviceId, withContext(Dispatchers.IO) { live().deviceForgetAccount(deviceId, harness, accountId) })

    override fun cached(deviceId: String): AgentAccountsSnapshot? = cache[deviceId]

    private fun remember(deviceId: String, accounts: List<AgentAccount>): AgentAccountsSnapshot =
        AgentAccountsSnapshot(accounts).also { cache[deviceId] = it }

    /** The live client, or a `Closed` error when the core is not ready. */
    private fun live(): CoreClient = client() ?: throw uniffi.zeron_core.CoreException.Closed()
}
