package sh.zeron.android.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.ProviderException
import org.json.JSONException
import org.json.JSONObject
import sh.zeron.android.security.KeystoreSecretCipher
import sh.zeron.android.security.SecretCipher
import uniffi.zeron_core.AuthTokens
import uniffi.zeron_core.Credentials

/**
 * The signed-in account on this phone (D5, #14): the port of iOS's
 * Keychain-backed `Credentials.stored/store/updateTokens/clearStored` and
 * `AccountProfile` (`AppModel.swift:815-927`).
 *
 * Two sealed files in [dir] (`noBackupFilesDir/credentials`: a backup
 * could never decrypt them anyway — the Keystore key doesn't leave the
 * phone):
 *
 * - `account` — `{kind: "workos", userId, orgId, access, refresh}`
 *   (Zeron Android's JSON shape), AAD `workos:v1`;
 * - `profile` — `{name, email, orgName}`, AAD `profile:v1`.
 *
 * Every write is **synchronous and durable** before it returns: the
 * sealed bytes go to a temp file, are `fsync`ed, then renamed over the old
 * file, so a process killed right after `AuthRefreshed` still has the
 * rotated refresh token on the next launch, and a crash mid-write leaves
 * the previous file whole. (Zeron Android's `SharedPreferences.apply()`
 * can lose the rotated token — the reason for D5.)
 *
 * Nothing here throws: an unreadable file (key lost after a restore or a
 * lock-screen reset, a corrupted blob) reads as signed out, and the next
 * sign-in overwrites it; a failed write is logged and leaves the previous
 * file. Calls block on disk and the Keystore — off the main thread.
 */
class CredentialStore(
    private val dir: File,
    private val cipher: SecretCipher,
    private val log: (String, Throwable?) -> Unit = { message, error -> Log.w(TAG, message, error) },
) : CredentialPersistence {

    /** The signed-in account's display identity (Settings' Account row). */
    data class Profile(val name: String? = null, val email: String? = null, val orgName: String? = null)

    private val account = File(dir, ACCOUNT_FILE)
    private val profileFile = File(dir, PROFILE_FILE)

    @Synchronized
    override fun stored(): Credentials? {
        val json = read(account, ACCOUNT_AAD) ?: return null
        return try {
            when (json.optString("kind")) {
                KIND_WORKOS -> Credentials.WorkOs(
                    json.getString("userId"),
                    json.getString("orgId"),
                    AuthTokens(json.getString("access"), json.getString("refresh")),
                )
                else -> null
            }
        } catch (error: JSONException) {
            log("stored credentials unreadable", error)
            null
        }
    }

    @Synchronized
    override fun store(credentials: Credentials.WorkOs) {
        val json = JSONObject()
            .put("kind", KIND_WORKOS)
            .put("userId", credentials.userId)
            .put("orgId", credentials.orgId)
            .put("access", credentials.tokens.accessToken)
            .put("refresh", credentials.tokens.refreshToken)
        write(account, ACCOUNT_AAD, json)
    }

    @Synchronized
    override fun updateTokens(tokens: AuthTokens) {
        val current = stored() as? Credentials.WorkOs ?: return
        store(current.copy(tokens = tokens))
    }

    /** Signed out: the account and its profile both go (iOS `signOutLocally`). */
    @Synchronized
    override fun clear() {
        account.delete()
        profileFile.delete()
    }

    /** Written by sign-in (#15) next to the credentials; empty when unknown. */
    var profile: Profile
        @Synchronized get() {
            val json = read(profileFile, PROFILE_AAD) ?: return Profile()
            return Profile(json.stringOrNull("name"), json.stringOrNull("email"), json.stringOrNull("orgName"))
        }

        @Synchronized set(value) {
            val json = JSONObject()
                .put("name", value.name ?: JSONObject.NULL)
                .put("email", value.email ?: JSONObject.NULL)
                .put("orgName", value.orgName ?: JSONObject.NULL)
            write(profileFile, PROFILE_AAD, json)
        }

    private fun read(file: File, aad: ByteArray): JSONObject? {
        if (!file.exists()) return null
        return try {
            JSONObject(String(cipher.decrypt(file.readBytes(), aad), Charsets.UTF_8))
        } catch (error: GeneralSecurityException) {
            // Key loss or a tampered/corrupted blob: signed out.
            log("${file.name} could not be opened", error)
            null
        } catch (error: ProviderException) {
            log("${file.name} could not be opened", error)
            null
        } catch (error: IOException) {
            log("${file.name} could not be read", error)
            null
        } catch (error: JSONException) {
            log("${file.name} is not valid", error)
            null
        }
    }

    private fun write(file: File, aad: ByteArray, json: JSONObject) {
        val temp = File(dir, "${file.name}.tmp")
        try {
            dir.mkdirs()
            val sealed = cipher.encrypt(json.toString().toByteArray(Charsets.UTF_8), aad)
            FileOutputStream(temp).use { out ->
                out.write(sealed)
                out.fd.sync()
            }
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: GeneralSecurityException) {
            temp.delete()
            log("${file.name} could not be sealed", error)
        } catch (error: ProviderException) {
            temp.delete()
            log("${file.name} could not be sealed", error)
        } catch (error: IOException) {
            temp.delete()
            log("${file.name} could not be written", error)
        }
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key)

    companion object {
        private const val TAG = "Zeron"
        internal const val ACCOUNT_FILE = "account"
        internal const val PROFILE_FILE = "profile"
        private const val KIND_WORKOS = "workos"
        private val ACCOUNT_AAD = "workos:v1".toByteArray(Charsets.UTF_8)
        private val PROFILE_AAD = "profile:v1".toByteArray(Charsets.UTF_8)

        /** The app's store: `noBackupFilesDir/credentials`, sealed by the Keystore key. */
        fun create(context: Context): CredentialStore {
            val app = context.applicationContext
            return CredentialStore(File(app.noBackupFilesDir, "credentials"), KeystoreSecretCipher(app))
        }
    }
}
