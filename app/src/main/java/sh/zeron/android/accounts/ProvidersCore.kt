package sh.zeron.android.accounts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.zeron_core.AgentLoginRules
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.CoreException
import uniffi.zeron_core.DeviceAccountInfo
import uniffi.zeron_core.DeviceLoginInfo
import uniffi.zeron_core.DeviceLoginPollInfo
import uniffi.zeron_core.HarnessInfo
import uniffi.zeron_core.agentLoginRules

/**
 * The FFI behind Providers and one agent's accounts on a computer (#50,
 * `core:client_ffi/providers.rs`): the agents a computer has and which
 * are on, its saved logins, and signing in on it. A seam so the pages'
 * logic tests without JNA; every call but [rules] runs on IO.
 */
interface ProvidersCore {
    /** `listHarnesses`: the computer's agents (empty when unknown; never throws). */
    suspend fun harnesses(deviceId: String): List<HarnessInfo>

    /** `setHarnessEnabled`: the computer's fresh catalog. */
    suspend fun setEnabled(deviceId: String, harness: String, enabled: Boolean): List<HarnessInfo>

    /** `deviceAgentAccounts`: every login on the computer. */
    suspend fun accounts(deviceId: String): List<DeviceAccountInfo>

    /** `deviceActivateAccount`: the login in use; the fresh list. */
    suspend fun activate(deviceId: String, harness: String, accountId: String): List<DeviceAccountInfo>

    /** `deviceForgetAccount`: the login removed; the fresh list. */
    suspend fun forget(deviceId: String, harness: String, accountId: String): List<DeviceAccountInfo>

    /** `deviceLoginStart(…, doneUrl = zeron://signed-in)`. */
    suspend fun loginStart(deviceId: String, harness: String, provider: String?): DeviceLoginInfo

    /** `deviceLoginPoll(…, doneUrl)`. */
    suspend fun loginPoll(deviceId: String, loginId: String): DeviceLoginPollInfo

    /** `deviceLoginComplete`: the pasted code; the fresh list. */
    suspend fun loginComplete(deviceId: String, loginId: String, code: String): List<DeviceAccountInfo>

    /** `deviceLoginCancel`: best effort. */
    fun loginCancel(deviceId: String, loginId: String)

    /** The static `agentLoginRules(harness)`; pure. */
    fun rules(harness: String): AgentLoginRules
}

/** The live [ProvidersCore] over the process's client. */
class CoreProvidersCore(private val client: () -> CoreClient?) : ProvidersCore {
    override suspend fun harnesses(deviceId: String): List<HarnessInfo> =
        withContext(Dispatchers.IO) { client()?.listHarnesses(deviceId).orEmpty() }

    override suspend fun setEnabled(deviceId: String, harness: String, enabled: Boolean): List<HarnessInfo> =
        withContext(Dispatchers.IO) { live().setHarnessEnabled(deviceId, harness, enabled) }

    override suspend fun accounts(deviceId: String): List<DeviceAccountInfo> =
        withContext(Dispatchers.IO) { live().deviceAgentAccounts(deviceId) }

    override suspend fun activate(deviceId: String, harness: String, accountId: String): List<DeviceAccountInfo> =
        withContext(Dispatchers.IO) { live().deviceActivateAccount(deviceId, harness, accountId) }

    override suspend fun forget(deviceId: String, harness: String, accountId: String): List<DeviceAccountInfo> =
        withContext(Dispatchers.IO) { live().deviceForgetAccount(deviceId, harness, accountId) }

    override suspend fun loginStart(deviceId: String, harness: String, provider: String?): DeviceLoginInfo =
        withContext(Dispatchers.IO) { live().deviceLoginStart(deviceId, harness, provider, DONE_URL) }

    override suspend fun loginPoll(deviceId: String, loginId: String): DeviceLoginPollInfo =
        withContext(Dispatchers.IO) { live().deviceLoginPoll(deviceId, loginId, DONE_URL) }

    override suspend fun loginComplete(deviceId: String, loginId: String, code: String): List<DeviceAccountInfo> =
        withContext(Dispatchers.IO) { live().deviceLoginComplete(deviceId, loginId, code) }

    override fun loginCancel(deviceId: String, loginId: String) {
        runCatching { client()?.deviceLoginCancel(deviceId, loginId) }
    }

    override fun rules(harness: String): AgentLoginRules = agentLoginRules(harness)

    private fun live(): CoreClient = client() ?: throw CoreException.Closed()

    companion object {
        /**
         * Where the provider's page lands when the computer's sign-in is
         * done: the core relays the browser's return through it, and the
         * Auth Tab (or `MainActivity`'s `singleTask`) closes on it.
         */
        const val DONE_URL = sh.zeron.android.core.BrowserReturns.SIGNED_IN
    }
}
