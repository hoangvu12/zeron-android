package sh.zeron.android.core

import uniffi.zeron_core.AuthTokens
import uniffi.zeron_core.Credentials

/**
 * Where the signed-in account's credentials live between launches: the
 * port of iOS's `Credentials.stored()/store/updateTokens/clearStored`
 * (`AppModel.swift:815-865`, Keychain). The [SessionHolder] is the only
 * caller.
 *
 * Every write is **synchronous** (D5): when a call returns, the value is
 * on disk. `updateTokens` runs on the core's callback thread the moment
 * the WorkOS pair rotates; the old refresh token is already spent, so a
 * write still queued when the process dies would sign the user out.
 *
 * Only `Credentials.WorkOs` is ever stored (dev bearers come from launch
 * extras, the demo has none), like iOS. Implementations never throw: an
 * unreadable store reads as signed out, a failed write keeps the old value.
 * The app's implementation is [CredentialStore].
 */
interface CredentialPersistence {
    /** The stored account, or null when signed out (or unreadable). */
    fun stored(): Credentials?

    /** Replaces the stored account. */
    fun store(credentials: Credentials.WorkOs)

    /** The pair rotated: keep the account, swap its tokens. No-op when nothing is stored. */
    fun updateTokens(tokens: AuthTokens)

    /** Signed out: nothing stays. */
    fun clear()
}
