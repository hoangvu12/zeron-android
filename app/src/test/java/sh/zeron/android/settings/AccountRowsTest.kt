package sh.zeron.android.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.zeron.android.core.CredentialStore.Profile
import sh.zeron.android.design.ActionSheetStyle

/** The Account row's copy and the Sign Out sheet (#18; iOS `AppModel.swift:637-649`, `MoreViewController.swift:310-315`). */
class AccountRowsTest {

    private fun signedIn(profile: Profile) = AccountIdentity(demo = false, signedIn = true, profile = profile)

    @Test
    fun theDemo() {
        val demo = AccountIdentity(demo = true, signedIn = true)
        assertEquals("Demo", accountName(demo))
        assertEquals("Offline demo workspace", accountDetail(demo))
    }

    @Test
    fun signedOut() {
        assertEquals("Signed out", accountName(AccountIdentity()))
        assertEquals("Zeron account", accountDetail(AccountIdentity()))
    }

    @Test
    fun nameThenEmailAndOrg() {
        val full = signedIn(Profile("Ada Lovelace", "ada@example.com", "Analytical"))
        assertEquals("Ada Lovelace", accountName(full))
        assertEquals("ada@example.com · Analytical", accountDetail(full))
    }

    @Test
    fun withoutANameTheEmailIsTheTitleAndNotRepeated() {
        val noName = signedIn(Profile(null, "ada@example.com", "Analytical"))
        assertEquals("ada@example.com", accountName(noName))
        assertEquals("Analytical", accountDetail(noName))
    }

    @Test
    fun anUnknownProfile() {
        val empty = signedIn(Profile())
        assertEquals("Signed in", accountName(empty))
        assertEquals("Zeron account", accountDetail(empty))
    }

    @Test
    fun theSheetIsIosVerbatim() {
        var signedOut = 0
        val sheet = signOutSheet { signedOut++ }
        assertEquals("Sign out?", sheet.title)
        assertEquals("Local drafts stay on this device.", sheet.message)
        assertEquals("Cancel", sheet.cancelTitle)
        val action = sheet.actions.single()
        assertEquals("Sign Out", action.title)
        assertEquals(ActionSheetStyle.Destructive, action.style)
        action.onSelect()
        assertEquals(1, signedOut)
    }

    @Test
    fun rowsMapToTheirSections() {
        assertEquals(SettingsSection.Account, settingsSectionFor("account"))
        assertEquals(SettingsSection.SignOut, settingsSectionFor("signout"))
    }
}
