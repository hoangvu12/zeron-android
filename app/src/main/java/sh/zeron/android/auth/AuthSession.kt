package sh.zeron.android.auth

import android.content.Context
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.zeron.android.core.CredentialStore
import sh.zeron.android.core.Endpoints
import uniffi.zeron_core.AuthCallback
import uniffi.zeron_core.AuthExchange
import uniffi.zeron_core.AuthOrg
import uniffi.zeron_core.AuthTokens
import uniffi.zeron_core.CoreException
import uniffi.zeron_core.Credentials
import uniffi.zeron_core.authExchangeCode
import uniffi.zeron_core.authListOrgs
import uniffi.zeron_core.authRefresh
import uniffi.zeron_core.parseAuthCallback

/** Where sign-in is: the Sync step's "Log in" button and status label read it. */
sealed interface SignInState {
    data object Idle : SignInState

    /** The browser is up or the code is being exchanged ("Logging in…"). */
    data object LoggingIn : SignInState

    data class Error(val message: String) : SignInState
}

/**
 * Several organizations: the app shows "Choose an organization"
 * (`SignInViewController.swift:155-166`) and answers with [choose];
 * `null` is Cancel, which stops sign-in quietly.
 */
class OrgChoice internal constructor(
    val orgs: List<AuthOrg>,
    private val answer: CompletableDeferred<AuthOrg?>,
) {
    fun choose(org: AuthOrg?) {
        answer.complete(org)
    }
}

/** The WorkOS calls sign-in makes (the core's top-level auth functions), as a seam for tests. */
interface AuthApi {
    fun authorizeUrl(state: String): String

    fun parseCallback(url: String): AuthCallback?

    suspend fun exchangeCode(code: String): AuthExchange

    suspend fun listOrgs(accessToken: String): List<AuthOrg>

    suspend fun refresh(refreshToken: String, organizationId: String): AuthTokens
}

/** [AuthApi] over `uniffi.zeron_core` against the production edge, or the debug overrides (#18). */
object CoreAuthApi : AuthApi {
    private val edge: String get() = Endpoints.edgeUrl

    override fun authorizeUrl(state: String): String = Endpoints.authorizeUrl(state)

    override fun parseCallback(url: String): AuthCallback? = parseAuthCallback(url)

    override suspend fun exchangeCode(code: String): AuthExchange = authExchangeCode(edge, code)

    override suspend fun listOrgs(accessToken: String): List<AuthOrg> = authListOrgs(edge, accessToken)

    override suspend fun refresh(refreshToken: String, organizationId: String): AuthTokens =
        authRefresh(edge, refreshToken, organizationId)
}

/**
 * The pending sign-in's OAuth `state`, kept on disk: the process may die
 * while the browser is up, and the callback must still match.
 */
interface PendingAuthState {
    var value: String?
}

/** [PendingAuthState] in its own prefs file, committed synchronously. */
class PrefsPendingAuthState(context: Context) : PendingAuthState {
    private val prefs = context.applicationContext.getSharedPreferences("auth", Context.MODE_PRIVATE)

    override var value: String?
        get() = prefs.getString(KEY, null)
        set(value) {
            prefs.edit().putString(KEY, value).commit()
        }

    private companion object {
        const val KEY = "state"
    }
}

/**
 * WorkOS sign-in (D4, #15): the port of iOS's `WorkOSSignIn.run` and
 * `AppModel.signIn` (`SignInViewController.swift:117-166`,
 * `AppModel.swift:223-237`). App-scoped, so a screen recreated mid-flow
 * (or a process restarted while the browser was up) picks it back up.
 *
 * [begin] mints a `state`, persists it and returns the authorize URL the
 * browser opens ([SignInBrowser]). The browser's answer arrives through
 * [onCallback] (Auth Tab result or the `zeron://callback` intent) or
 * [onCancelled]. A good callback then runs: exchange the code → list the
 * organizations (none: an error; one: it; several: [orgChoice]) →
 * refresh scoped to the org → save the profile → start the client. The
 * session holder publishing the client is what swaps the root to the
 * shell.
 *
 * Errors use iOS's copy; a core failure shows its `reason`. Cancelling
 * (the browser or the org sheet) returns quietly to [SignInState.Idle].
 */
class AuthSession(
    private val scope: CoroutineScope,
    private val api: AuthApi,
    private val pending: PendingAuthState,
    /** Saves the account's display identity (blocking; runs on [io]). */
    private val saveProfile: (CredentialStore.Profile) -> Unit,
    /** `SessionHolder.start` (blocking; runs on [io]): false when the core refused. */
    private val startClient: (Credentials.WorkOs) -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newState: () -> String = { UUID.randomUUID().toString() },
    private val log: (String, Throwable?) -> Unit = { message, error -> Log.w(TAG, message, error) },
) {

    private val _state = MutableStateFlow<SignInState>(SignInState.Idle)
    val state: StateFlow<SignInState> = _state.asStateFlow()

    private val _orgChoice = MutableStateFlow<OrgChoice?>(null)

    /** Non-null while the user must pick an organization. */
    val orgChoice: StateFlow<OrgChoice?> = _orgChoice.asStateFlow()

    /** The browser is up and nothing has come back from it yet. */
    @Volatile
    var awaitingBrowser: Boolean = false
        private set

    private var job: Job? = null

    /** Starts a sign-in: "Logging in…", and the URL the browser must open. */
    fun begin(): String {
        job?.cancel()
        _orgChoice.value?.choose(null)
        val state = newState()
        pending.value = state
        awaitingBrowser = true
        _state.value = SignInState.LoggingIn
        return api.authorizeUrl(state)
    }

    /** The browser couldn't be opened, or came back with neither a callback nor a cancel. */
    fun onBrowserFailed(why: String, error: Throwable?) {
        log("sign-in browser failed: $why", error)
        awaitingBrowser = false
        pending.value = null
        _state.value = SignInState.Error(INTERRUPTED)
    }

    /** The user closed the browser: no error, the button goes back to "Log in". */
    fun onCancelled() {
        if (!awaitingBrowser) return
        awaitingBrowser = false
        pending.value = null
        _state.value = SignInState.Idle
    }

    /** The browser came back with [url] (`zeron://callback?…`). */
    fun onCallback(url: String) {
        awaitingBrowser = false
        val expected = pending.value
        if (expected == null) {
            // A duplicate delivery of a callback already being handled.
            if (job?.isActive == true) return
            _state.value = SignInState.Error(INTERRUPTED)
            return
        }
        pending.value = null
        val callback = api.parseCallback(url)
        if (callback !is AuthCallback.Code || callback.state != expected) {
            // A provider error, a missing code or a forged state: iOS shows one line.
            if (callback is AuthCallback.Error) log("sign-in callback error: ${callback.error}", null)
            _state.value = SignInState.Error(INTERRUPTED)
            return
        }
        _state.value = SignInState.LoggingIn
        job = scope.launch { signIn(callback.code) }
    }

    /** The error has been seen (the screen showing it went away). */
    fun clearError() {
        if (_state.value is SignInState.Error) _state.value = SignInState.Idle
    }

    private suspend fun signIn(code: String) {
        try {
            val exchange = api.exchangeCode(code)
            val orgs = api.listOrgs(exchange.tokens.accessToken)
            if (orgs.isEmpty()) {
                _state.value = SignInState.Error(NO_ORGANIZATION)
                return
            }
            val org = orgs.singleOrNull() ?: chooseOrg(orgs) ?: run {
                _state.value = SignInState.Idle
                return
            }
            val tokens = api.refresh(exchange.tokens.refreshToken, org.organizationId)
            val user = exchange.user
            val name = listOfNotNull(user.firstName, user.lastName)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            val started = withContext(io) {
                saveProfile(CredentialStore.Profile(name.ifEmpty { null }, user.email, org.name))
                startClient(Credentials.WorkOs(user.id, org.organizationId, tokens))
            }
            _state.value = if (started) SignInState.Idle else SignInState.Error(COULD_NOT_OPEN)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log("sign-in failed", error)
            _state.value = SignInState.Error(reason(error))
        } finally {
            _orgChoice.value = null
        }
    }

    private suspend fun chooseOrg(orgs: List<AuthOrg>): AuthOrg? {
        val answer = CompletableDeferred<AuthOrg?>()
        _orgChoice.value = OrgChoice(orgs, answer)
        return try {
            answer.await()
        } finally {
            _orgChoice.value = null
        }
    }

    companion object {
        private const val TAG = "Zeron"
        const val INTERRUPTED = "Sign-in didn't complete. Try again."
        const val NO_ORGANIZATION = "This account isn't in an organization yet."
        const val COULD_NOT_OPEN = "Couldn't open your workspace. Try signing in again."

        /** A core failure's own words (`reason`), not the binding's `toString`. */
        fun reason(error: Throwable): String = when (error) {
            is CoreException.NotFound -> error.reason
            is CoreException.InvalidArgument -> error.reason
            is CoreException.HostUnavailable -> error.reason
            is CoreException.Unsupported -> error.reason
            is CoreException.HostException -> error.reason
            is CoreException.Network -> error.reason
            is CoreException.Auth -> error.reason
            is CoreException.Storage -> error.reason
            is CoreException.NotImplemented -> error.reason
            is CoreException.Internal -> error.reason
            else -> error.message ?: "Sign-in failed."
        }
    }
}
