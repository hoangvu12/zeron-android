package sh.zeron.android.core

import uniffi.zeron_core.AuthTokens
import uniffi.zeron_core.Credentials

/** An in-memory [CredentialPersistence] for the session holder's tests. */
class InMemoryCredentialStore : CredentialPersistence {

    @Volatile
    private var current: Credentials.WorkOs? = null

    override fun stored(): Credentials? = current

    @Synchronized
    override fun store(credentials: Credentials.WorkOs) {
        current = credentials
    }

    @Synchronized
    override fun updateTokens(tokens: AuthTokens) {
        current = current?.copy(tokens = tokens)
    }

    @Synchronized
    override fun clear() {
        current = null
    }
}
