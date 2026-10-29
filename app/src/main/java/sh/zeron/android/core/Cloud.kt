package sh.zeron.android.core

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.zeron_core.BillingInfo
import uniffi.zeron_core.CloudAccountInfo
import uniffi.zeron_core.CloudInfo
import uniffi.zeron_core.CloudSessionInfo
import uniffi.zeron_core.CloudUsageInfo
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.CoreException
import uniffi.zeron_core.GithubRepoInfo
import uniffi.zeron_core.VaultInfo

/**
 * Cloud for this account (#48; iOS `Cloud/CloudStore.swift`, wired as Zeron
 * Android's `core/Cloud.kt`): status and credits, GitHub, the agents'
 * logins, billing. Every screen reads it; reads are cached so a page paints
 * at once and revalidates. Snapshot state, main thread only (the core's
 * Cloud calls are `suspend` and never block).
 *
 * Every read re-checks `client() === c` before it writes, so a sign-out or
 * an account switch never clobbers the new account's state; [reset] runs on
 * sign-out (iOS `AppModel.signOutLocally`).
 */
@Stable
class CloudStore(
    private val scope: CoroutineScope,
    private val client: () -> CoreClient?,
    /** iOS `app.workspaceDidChange()`: rebuild the workspace now. */
    private val workspaceChanged: () -> Unit,
) {
    /** The agents Cloud runs, with the vault providers behind them. */
    data class Agent(
        val harness: String,
        val name: String,
        /** Subscription sign-in provider (`claude` · `codex`). */
        val login: String,
        /** API key provider (`anthropic-key` · `openai-key`). */
        val key: String,
        /** What signing in is called ("Sign in with Claude"). */
        val signInTitle: String,
        val keyName: String,
    )

    /** What's wrong with Cloud as a whole, for the top of its page. */
    data class Problem(
        val title: String,
        val detail: String,
        /** Credits fix it: the row leads to Billing. */
        val billing: Boolean,
    )

    var info by mutableStateOf<CloudInfo?>(null); private set
    var vault by mutableStateOf<VaultInfo?>(null); private set
    var repos by mutableStateOf<List<GithubRepoInfo>?>(null); private set
    var accounts by mutableStateOf<Map<String, List<CloudAccountInfo>>>(emptyMap()); private set

    /** Each Cloud login's plan usage by `provider:slot` (read by the vault), and why it couldn't be read. */
    var usage by mutableStateOf<Map<String, CloudUsageInfo>>(emptyMap()); private set
    var usageErrors by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var billing by mutableStateOf<BillingInfo?>(null); private set

    /** Why the last billing read failed (shown with Retry until one succeeds). */
    var billingError by mutableStateOf<String?>(null); private set

    /** The account's session machines (read when a Cloud chat needs them). */
    var sessions by mutableStateOf<List<CloudSessionInfo>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    // ── reading ───────────────────────────────────────────────────────────

    val available: Boolean get() = info?.available == true
    val credits: Double? get() = cloudCredits(info, billing)

    /** Cloud can't start or wake a machine (the desktop's rule). */
    val outOfCredits: Boolean get() = cloudOutOfCredits(info, billing)

    val problem: Problem? get() = cloudProblem(info, billing)

    /** Why a Cloud chat's machine isn't running, when the edge said. */
    fun sessionError(chatId: String, device: String?): String? = cloudSessionError(sessions, chatId, device)

    val githubAccount: String?
        get() = vault?.connections?.firstOrNull { it.provider == "github" && !it.needsReconnect }?.account
    val githubConnected: Boolean
        get() = vault?.connections?.any { it.provider == "github" && !it.needsReconnect } == true

    /** The active login (or key) Cloud uses for `agent`. */
    fun activeAccount(agent: Agent): CloudAccountInfo? =
        accounts[agent.login]?.firstOrNull { it.active } ?: accounts[agent.key]?.firstOrNull { it.active }

    fun hasAccount(agent: Agent): Boolean =
        !accounts[agent.login].isNullOrEmpty() || !accounts[agent.key].isNullOrEmpty() ||
            vault?.connections?.any { (it.provider == agent.login || it.provider == agent.key) && it.accounts > 0u } == true

    /** Cloud reaches `repo` (`owner/name`): its sessions can run there. */
    fun reaches(repo: String?): Boolean = cloudReaches(available, repos, repo)

    // ── loading ───────────────────────────────────────────────────────────

    private var refreshJob: Job? = null

    /** How long until a failed status read is tried again (30 s, doubling to 5 min). */
    private var retryDelayMs = RETRY_FIRST_MS
    private var sessionsReadAt = 0L

    /** Re-read everything (status first: the rest needs Cloud set up). One read at a time. */
    fun refresh() {
        val c = client() ?: return
        if (refreshJob?.isActive == true) return
        loading = true
        refreshJob = scope.launch {
            try {
                var status = c.cloudStatus()
                // Cloud is part of every signed-in account: set it up.
                if (status.available && status.state == "off") {
                    status = try {
                        c.cloudEnable()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        status
                    }
                }
                if (client() !== c) return@launch
                info = status
                error = null
                if (!status.available) return@launch
                coroutineScope {
                    val v = async { read { c.vaultStatus() } }
                    val r = async { read { c.githubRepos(null) } }
                    val b = async { read { c.billingStatus() } }
                    val lists = readAccounts(c)
                    if (client() !== c) return@coroutineScope
                    vault = v.await()
                    repos = r.await() ?: emptyList()
                    billing = b.await()
                    accounts = lists
                }
                retryDelayMs = RETRY_FIRST_MS
                refreshUsage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (client() !== c) return@launch
                error = message(e)
                // Until a read answers, Cloud reads as not offered: ask
                // again rather than hide it for good.
                val wait = retryDelayMs
                retryDelayMs = nextRetryDelay(wait)
                scope.launch {
                    delay(wait)
                    if (client() === c && (info == null || error != null)) refresh()
                }
            } finally {
                if (client() === c) loading = false
            }
        }
    }

    private suspend fun readAccounts(c: CoreClient): Map<String, List<CloudAccountInfo>> = coroutineScope {
        agents.flatMap { listOf(it.login, it.key) }
            .map { provider -> async { provider to (read { c.vaultAccounts(provider) } ?: emptyList()) } }
            .awaitAll()
            .toMap()
    }

    /** Re-read the session machines (at most every 8 s); status too. */
    fun refreshSessions() {
        val c = client() ?: return
        if (!available && info != null) return
        val now = System.currentTimeMillis()
        if (now - sessionsReadAt < SESSIONS_THROTTLE_MS) return
        sessionsReadAt = now
        scope.launch {
            val list = read { c.cloudSessions() }
            val status = read { c.cloudStatus() }
            if (client() !== c) return@launch
            list?.let { sessions = it }
            status?.let { info = it }
        }
    }

    /** Re-read the four providers' lists and the vault, then usage. */
    fun refreshAccounts() {
        val c = client() ?: return
        scope.launch {
            val lists = readAccounts(c)
            val v = read { c.vaultStatus() }
            if (client() !== c) return@launch
            accounts = lists
            v?.let { vault = it }
            refreshUsage()
        }
    }

    /** Re-read every Cloud login's plan usage (keys have none). */
    fun refreshUsage() {
        val c = client() ?: return
        val logins = agents.flatMap { agent -> accounts[agent.login].orEmpty().map { agent.login to it.slot } }
        scope.launch {
            val results = logins.map { (provider, slot) ->
                async { "$provider:$slot" to attempt { c.vaultUsage(provider, slot) } }
            }.awaitAll()
            if (client() !== c) return@launch
            val u = usage.toMutableMap()
            val errors = usageErrors.toMutableMap()
            for ((key, result) in results) {
                result.onSuccess {
                    u[key] = it
                    errors.remove(key)
                }.onFailure { errors[key] = message(it) }
            }
            usage = u
            usageErrors = errors
        }
    }

    suspend fun refreshGitHub() {
        val c = client() ?: return
        val v = read { c.vaultStatus() }
        val r = read { c.githubRepos(null) }
        if (client() !== c) return
        v?.let { vault = it }
        r?.let { repos = it }
    }

    suspend fun refreshBilling() {
        val c = client() ?: return
        val result = attempt { c.billingStatus() }
        val status = read { c.cloudStatus() }
        if (client() !== c) return
        result.onSuccess {
            billing = it
            billingError = null
        }.onFailure { billingError = message(it) }
        status?.let { info = it }
    }

    /** Forget everything (sign-out). */
    fun reset() {
        refreshJob?.cancel()
        refreshJob = null
        info = null
        vault = null
        repos = null
        accounts = emptyMap()
        usage = emptyMap()
        usageErrors = emptyMap()
        billing = null
        billingError = null
        retryDelayMs = RETRY_FIRST_MS
        sessions = emptyList()
        sessionsReadAt = 0L
        error = null
        loading = false
    }

    // ── accounts ──────────────────────────────────────────────────────────

    suspend fun activate(account: CloudAccountInfo, provider: String) {
        val c = client() ?: return
        accounts = accounts + (provider to c.vaultActivate(provider, account.slot))
    }

    suspend fun remove(account: CloudAccountInfo, provider: String) {
        val c = client() ?: return
        accounts = accounts + (provider to c.vaultForget(provider, account.slot))
        refreshAccounts()
    }

    suspend fun saveKey(key: String, agent: Agent) {
        val c = client() ?: return
        c.vaultPutApiKey(agent.key, key)
        refreshAccounts()
    }

    /**
     * Signs in to Claude or ChatGPT for Cloud (iOS `ProviderSignIn`): the
     * provider's own page in an Auth Tab (or a Custom Tab), which hands back
     * through `zeron://signed-in`; the login then lands in Cloud's vault (no
     * token stays on the phone). Throws [CancellationException] when the page
     * was closed (after `providerLoginCancel`).
     */
    suspend fun signIn(agent: Agent, browser: BrowserReturns) {
        val c = client() ?: throw CoreException.Closed()
        val start = c.providerLoginStart(agent.login, BrowserReturns.SIGNED_IN)
        val done = browser.browseUntil(start.url) {
            c.providerLoginFinish(start.loginId)
            true
        }
        if (done == null) {
            c.providerLoginCancel(start.loginId)
            throw CancellationException("The sign-in page was closed")
        }
        refreshAccounts()
    }

    // ── projects ──────────────────────────────────────────────────────────

    /** Make `repo` (`owner/name`) a project on Cloud alone; returns its id. */
    suspend fun createProject(repo: String): String {
        val c = client() ?: throw CoreException.Closed()
        return c.createCloudProject(repo).also { workspaceChanged() }
    }

    companion object {
        val agents = listOf(
            Agent("claude-code", "Claude Code", "claude", "anthropic-key", "Sign in with Claude", "Anthropic API key"),
            Agent("codex", "Codex", "codex", "openai-key", "Sign in with ChatGPT", "OpenAI API key"),
        )

        /** The agents Cloud runs. */
        val harnesses = agents.map { it.harness }

        /** iOS `CloudRoutes.outOfCreditsMessage`: short enough for a toast beside its button. */
        const val OUT_OF_CREDITS = "Out of Cloud credits"

        internal const val RETRY_FIRST_MS = 30_000L
        internal const val RETRY_MAX_MS = 300_000L
        internal const val SESSIONS_THROTTLE_MS = 8_000L

        /** The status retry's back-off: doubling, capped at 5 min. */
        internal fun nextRetryDelay(ms: Long): Long = minOf(ms * 2, RETRY_MAX_MS)

        /** iOS `CloudStore.message`: a core error's own words; `Closed` is "You're signed out." */
        fun message(e: Throwable): String = when (e) {
            is CoreException.Closed -> "You're signed out."
            is CoreException.NotFound -> e.reason
            is CoreException.InvalidArgument -> e.reason
            is CoreException.HostUnavailable -> e.reason
            is CoreException.Unsupported -> e.reason
            is CoreException.HostException -> e.reason
            is CoreException.Network -> e.reason
            is CoreException.Auth -> e.reason
            is CoreException.Storage -> e.reason
            is CoreException.NotImplemented -> e.reason
            is CoreException.Internal -> e.reason
            else -> e.message ?: "Something went wrong."
        }

        /** iOS `CloudRoutes.isOutOfCredits`: a Cloud error worth "Add credits". */
        fun isOutOfCredits(e: Throwable) = message(e).contains("out of Cloud credits", ignoreCase = true)

        /** "41,250". */
        fun credits(value: Double): String = NumberFormat.getIntegerInstance().format(floor(value).toLong())

        /** "$20" / "$12.50". */
        fun price(cents: UInt): String =
            if (cents % 100u == 0u) "$${cents / 100u}" else String.format(Locale.US, "$%.2f", cents.toDouble() / 100)

        fun shortDate(ms: Long): String = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))
    }
}

// ── the rules, pure (unit-tested) ────────────────────────────────────────

/** Billing's total, else the status's credits (iOS `credits`). */
internal fun cloudCredits(info: CloudInfo?, billing: BillingInfo?): Double? = billing?.creditsTotal ?: info?.credits

/** Credits ≤ 0, the status's figure first (the desktop's rule; iOS `outOfCredits`). */
internal fun cloudOutOfCredits(info: CloudInfo?, billing: BillingInfo?): Boolean =
    (info?.credits ?: billing?.creditsTotal)?.let { it <= 0 } == true

/** iOS `problem`: an `error` state, else out of credits; nothing while Cloud isn't offered. */
internal fun cloudProblem(info: CloudInfo?, billing: BillingInfo?): CloudStore.Problem? {
    val i = info ?: return null
    if (!i.available) return null
    if (i.state == "error") {
        return CloudStore.Problem("Cloud ran into a problem", i.error ?: "Something went wrong with Cloud.", billing = false)
    }
    if (cloudOutOfCredits(i, billing)) {
        return CloudStore.Problem("Out of credits", "Cloud sessions can't start or wake until you add credits.", billing = true)
    }
    return null
}

/** iOS `sessionError(chat:device:)`: the chat's machine (else its device's), when it failed. */
internal fun cloudSessionError(sessions: List<CloudSessionInfo>, chatId: String, device: String?): String? {
    val s = sessions.firstOrNull { it.chatId == chatId } ?: sessions.firstOrNull { it.deviceId == device } ?: return null
    return if (s.state == "error") s.error ?: "Its machine failed to start." else null
}

/** iOS `reaches(_:)`: a case-insensitive `owner/name` match, only while Cloud is offered. */
internal fun cloudReaches(available: Boolean, repos: List<GithubRepoInfo>?, repo: String?): Boolean {
    if (!available || repo == null) return false
    return repos?.any { it.fullName.equals(repo, ignoreCase = true) } == true
}

/** Swift's `try?`: the value, or null on any failure but cancellation. */
private suspend fun <T> read(block: suspend () -> T): T? = attempt(block).getOrNull()

/** A `Result` that never swallows cancellation (unlike `runCatching`). */
private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
