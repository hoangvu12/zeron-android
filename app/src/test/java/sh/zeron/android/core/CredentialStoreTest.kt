package sh.zeron.android.core

import android.app.Application
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.zeron.android.security.FakeSecretCipher
import uniffi.zeron_core.AuthTokens
import uniffi.zeron_core.Credentials

/**
 * The Keystore-sealed credential store (#14, D5) over the JVM fake cipher
 * (same blob format and AAD handling as the Keystore one): round-trips,
 * synchronous rotation, key loss and corrupted blobs reading as signed
 * out, failed writes keeping the old value, and no plaintext at rest.
 *
 * Robolectric only for `org.json`; a plain [Application], so
 * `ZeronApplication.onCreate` never runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CredentialStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val cipher = FakeSecretCipher()
    private val dir by lazy { File(tmp.root, "credentials") }
    private val logged = mutableListOf<String>()

    private fun newStore() = CredentialStore(dir, cipher, log = { message, _ -> logged += message })

    private val account = Credentials.WorkOs("user_01", "org_01", AuthTokens("access-secret-1", "refresh-secret-1"))

    @Test
    fun nothingStoredIsSignedOut() {
        assertNull(newStore().stored())
        assertEquals(CredentialStore.Profile(), newStore().profile)
    }

    @Test
    fun anAccountRoundTripsAcrossInstances() {
        newStore().store(account)
        // A fresh instance: what the next launch reads.
        assertEquals(account, newStore().stored())
    }

    @Test
    fun aRotationIsOnDiskWhenUpdateTokensReturns() {
        val store = newStore()
        store.store(account)
        val rotated = AuthTokens("access-secret-2", "refresh-secret-2")

        store.updateTokens(rotated)

        // The process dies here: the next launch must see the new pair.
        assertEquals(account.copy(tokens = rotated), newStore().stored())
    }

    @Test
    fun aRotationWithNothingStoredStoresNothing() {
        val store = newStore()
        store.updateTokens(AuthTokens("a", "r"))
        assertNull(store.stored())
    }

    @Test
    fun theProfileRoundTripsIncludingMissingFields() {
        newStore().profile = CredentialStore.Profile(name = "Ada Lovelace", email = "ada@example.com", orgName = null)
        assertEquals(
            CredentialStore.Profile(name = "Ada Lovelace", email = "ada@example.com", orgName = null),
            newStore().profile,
        )
    }

    @Test
    fun clearDropsTheAccountAndItsProfile() {
        val store = newStore()
        store.store(account)
        store.profile = CredentialStore.Profile(email = "ada@example.com")

        store.clear()

        assertNull(newStore().stored())
        assertEquals(CredentialStore.Profile(), newStore().profile)
    }

    @Test
    fun aLostKeyReadsAsSignedOutAndTheNextSignInWorks() {
        newStore().store(account)
        cipher.rotateKey() // a Keystore invalidation

        assertNull(newStore().stored())
        assertTrue(logged.isNotEmpty())

        val next = account.copy(userId = "user_02")
        newStore().store(next)
        assertEquals(next, newStore().stored())
    }

    @Test
    fun aCorruptedBlobReadsAsSignedOut() {
        newStore().store(account)
        val file = File(dir, CredentialStore.ACCOUNT_FILE)
        val bytes = file.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        assertNull(newStore().stored())
    }

    @Test
    fun aTruncatedBlobReadsAsSignedOut() {
        newStore().store(account)
        File(dir, CredentialStore.ACCOUNT_FILE).writeBytes(ByteArray(5))
        assertNull(newStore().stored())
    }

    @Test
    fun theAccountBlobIsBoundToItsOwnFile() {
        // The AAD differs: a profile blob copied over the account won't open.
        newStore().profile = CredentialStore.Profile(name = "x")
        File(dir, CredentialStore.PROFILE_FILE).copyTo(File(dir, CredentialStore.ACCOUNT_FILE), overwrite = true)
        assertNull(newStore().stored())
    }

    @Test
    fun aFailedWriteKeepsThePreviousAccount() {
        newStore().store(account)
        cipher.failEncrypt = true

        newStore().updateTokens(AuthTokens("access-secret-2", "refresh-secret-2"))

        cipher.failEncrypt = false
        assertEquals(account, newStore().stored())
        assertFalse(File(dir, "${CredentialStore.ACCOUNT_FILE}.tmp").exists())
    }

    @Test
    fun noSecretIsWrittenInPlaintext() {
        val store = newStore()
        store.store(account)
        store.profile = CredentialStore.Profile(name = "Ada Lovelace", email = "ada@example.com")

        val onDisk = dir.listFiles().orEmpty().joinToString("\n") { String(it.readBytes(), Charsets.ISO_8859_1) }
        for (secret in listOf("access-secret-1", "refresh-secret-1", "user_01", "ada@example.com")) {
            assertFalse("$secret is on disk in plaintext", onDisk.contains(secret))
        }
    }
}
