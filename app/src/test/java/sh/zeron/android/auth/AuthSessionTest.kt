package sh.zeron.android.auth

import java.net.URI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.zeron.android.core.CredentialStore
import uniffi.zeron_core.AuthCallback
import uniffi.zeron_core.AuthExchange
import uniffi.zeron_core.AuthOrg
import uniffi.zeron_core.AuthTokens
import uniffi.zeron_core.AuthUser
import uniffi.zeron_core.CoreException
import uniffi.zeron_core.Credentials

/**
 * WorkOS sign-in (#15, `WorkOSSignIn.run` + `AppModel.signIn`) on the
 * plain JVM over a fake [AuthApi]: the state check, iOS's error copy,
 * the org rules (none / one / several, quiet cancel), the profile, and
 * the client start.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthSessionTest {

    private class FakeApi : AuthApi {
        var orgs = listOf(AuthOrg("org_row_1", "org_1", "Acme"))
        var exchangeFailure: Exception? = null
        val refreshedFor = mutableListOf<String>()

        override fun authorizeUrl(state: String) = "https://auth.example/authorize?state=$state"

        override fun parseCallback(url: String): AuthCallback? {
            val uri = URI(url)
            if (uri.scheme != "zeron" || uri.host != "callback") return null
            val query = uri.query.orEmpty().split("&").filter { it.contains("=") }
                .associate { it.substringBefore("=") to it.substringAfter("=") }
            query["error"]?.let { return AuthCallback.Error(it, query["error_description"]) }
            val code = query["code"] ?: return null
            return AuthCallback.Code(code, query["state"])
        }

        override suspend fun exchangeCode(code: String): AuthExchange {
            exchangeFailure?.let { throw it }
            return AuthExchange(
                AuthUser("user_1", "ada@example.com", " Ada ", "Lovelace"),
                AuthTokens("unscoped-access", "unscoped-refresh"),
            )
        }

        override suspend fun listOrgs(accessToken: String): List<AuthOrg> {
            assertEquals("unscoped-access", accessToken)
            return orgs
        }

        override suspend fun refresh(refreshToken: String, organizationId: String): AuthTokens {
            assertEquals("unscoped-refresh", refreshToken)
            refreshedFor += organizationId
            return AuthTokens("scoped-access-$organizationId", "scoped-refresh-$organizationId")
        }
    }

    private class MemoryState : PendingAuthState {
        override var value: String? = null
    }

    private val api = FakeApi()
    private val pending = MemoryState()
    private val profiles = mutableListOf<CredentialStore.Profile>()
    private val started = mutableListOf<Credentials.WorkOs>()
    private var startSucceeds = true
    private var nextState = 0

    private fun TestScope.session() = AuthSession(
        scope = this,
        api = api,
        pending = pending,
        saveProfile = { profiles += it },
        startClient = { started += it; startSucceeds },
        io = StandardTestDispatcher(testScheduler),
        newState = { "state-${++nextState}" },
        log = { _, _ -> },
    )

    private fun callback(state: String?, code: String = "the-code") =
        "zeron://callback?code=$code" + (state?.let { "&state=$it" } ?: "")

    @Test
    fun beginPersistsTheStateAndShowsLoggingIn() = runTest {
        val session = session()
        val url = session.begin()
        assertEquals("https://auth.example/authorize?state=state-1", url)
        assertEquals("state-1", pending.value)
        assertEquals(SignInState.LoggingIn, session.state.value)
        assertTrue(session.awaitingBrowser)
    }

    @Test
    fun closingTheBrowserResetsQuietly() = runTest {
        val session = session()
        session.begin()
        session.onCancelled()
        assertEquals(SignInState.Idle, session.state.value)
        assertNull(pending.value)
    }

    @Test
    fun oneOrganizationSignsStraightIn() = runTest {
        val session = session()
        session.begin()
        session.onCallback(callback("state-1"))
        advanceUntilIdle()

        assertEquals(listOf("org_1"), api.refreshedFor)
        assertEquals(listOf(CredentialStore.Profile("Ada Lovelace", "ada@example.com", "Acme")), profiles)
        assertEquals(
            listOf(Credentials.WorkOs("user_1", "org_1", AuthTokens("scoped-access-org_1", "scoped-refresh-org_1"))),
            started,
        )
        assertEquals(SignInState.Idle, session.state.value)
        assertNull(pending.value)
    }

    @Test
    fun aMismatchedStateIsRefused() = runTest {
        val session = session()
        session.begin()
        session.onCallback(callback("forged"))
        advanceUntilIdle()
        assertEquals(SignInState.Error(AuthSession.INTERRUPTED), session.state.value)
        assertTrue(started.isEmpty())
    }

    @Test
    fun aMissingStateOrCodeIsRefused() = runTest {
        val session = session()
        session.begin()
        session.onCallback(callback(state = null))
        assertEquals(SignInState.Error(AuthSession.INTERRUPTED), session.state.value)

        session.begin()
        session.onCallback("zeron://callback?state=state-2")
        assertEquals(SignInState.Error(AuthSession.INTERRUPTED), session.state.value)
        advanceUntilIdle()
        assertTrue(started.isEmpty())
    }

    @Test
    fun aProviderErrorShowsTheSameLine() = runTest {
        val session = session()
        session.begin()
        session.onCallback("zeron://callback?error=access_denied&state=state-1")
        assertEquals(SignInState.Error(AuthSession.INTERRUPTED), session.state.value)
    }

    @Test
    fun aCallbackNobodyAskedForIsRefused() = runTest {
        val session = session()
        session.onCallback(callback("state-1"))
        assertEquals(SignInState.Error(AuthSession.INTERRUPTED), session.state.value)
    }

    @Test
    fun theStateSurvivesTheProcess() = runTest {
        session().begin()
        // The process died while the browser was up: a fresh session, same disk.
        val restored = session()
        restored.onCallback(callback("state-1"))
        advanceUntilIdle()
        assertEquals(1, started.size)
    }

    @Test
    fun noOrganizationIsAnError() = runTest {
        api.orgs = emptyList()
        val session = session()
        session.begin()
        session.onCallback(callback("state-1"))
        advanceUntilIdle()
        assertEquals(SignInState.Error(AuthSession.NO_ORGANIZATION), session.state.value)
        assertTrue(started.isEmpty())
    }

    @Test
    fun severalOrganizationsAskAndUseTheChoice() = runTest {
        val beta = AuthOrg("org_row_2", "org_2", "Beta")
        api.orgs = listOf(AuthOrg("org_row_1", "org_1", "Acme"), beta)
        val session = session()
        session.begin()
        session.onCallback(callback("state-1"))
        advanceUntilIdle()

        val choice = session.orgChoice.value
        assertNotNull(choice)
        choice!!
        assertEquals(listOf("Acme", "Beta"), choice.orgs.map { it.name })
        assertEquals(SignInState.LoggingIn, session.state.value)

        choice.choose(beta)
        advanceUntilIdle()
        assertNull(session.orgChoice.value)
        assertEquals(listOf("org_2"), api.refreshedFor)
        assertEquals("org_2", started.single().orgId)
        assertEquals("Beta", profiles.single().orgName)
    }

    @Test
    fun cancellingTheOrgPickerStopsQuietly() = runTest {
        api.orgs = listOf(AuthOrg("a", "org_1", "Acme"), AuthOrg("b", "org_2", "Beta"))
        val session = session()
        session.begin()
        session.onCallback(callback("state-1"))
        advanceUntilIdle()

        session.orgChoice.value!!.choose(null)
        advanceUntilIdle()

        assertEquals(SignInState.Idle, session.state.value)
        assertNull(session.orgChoice.value)
        assertTrue(started.isEmpty())
        assertTrue(profiles.isEmpty())
    }

    @Test
    fun aRefusedStartIsTheWorkspaceError() = runTest {
        startSucceeds = false
        val session = session()
        session.begin()
        session.onCallback(callback("state-1"))
        advanceUntilIdle()
        assertEquals(SignInState.Error(AuthSession.COULD_NOT_OPEN), session.state.value)
    }

    @Test
    fun aCoreFailureShowsItsReason() = runTest {
        api.exchangeFailure = CoreException.Network("The edge could not be reached.")
        val session = session()
        session.begin()
        session.onCallback(callback("state-1"))
        advanceUntilIdle()
        assertEquals(SignInState.Error("The edge could not be reached."), session.state.value)
    }

    @Test
    fun clearErrorGoesBackToIdle() = runTest {
        val session = session()
        session.onCallback(callback("state-1"))
        session.clearError()
        assertEquals(SignInState.Idle, session.state.value)
    }
}
