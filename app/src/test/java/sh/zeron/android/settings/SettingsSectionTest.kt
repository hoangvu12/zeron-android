package sh.zeron.android.settings

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Settings screen's pure parts (#35, rewriting ticket 103's suite):
 * the [SettingsSection] display order — iOS's
 * (`MoreViewController.swift:185-248`) — the [settingsSectionFor] row-id →
 * section map and [settingsBottomPadding]. Roboco's Background and About
 * sections and its engine rows are gone, and their cases with them.
 */
class SettingsSectionTest {

    @Test
    fun theSectionsFollowIosOrder() {
        assertEquals(
            listOf(
                SettingsSection.Account,
                SettingsSection.AgentAccounts,
                SettingsSection.Devices,
                SettingsSection.Voice,
                SettingsSection.Notifications,
                SettingsSection.Appearance,
                SettingsSection.Wallpaper,
                SettingsSection.Math,
                SettingsSection.Sessions,
                SettingsSection.SignOut,
            ),
            SettingsSection.entries,
        )
    }

    @Test
    fun sectionKeysAreUnique() {
        assertEquals(
            SettingsSection.entries.size,
            SettingsSection.entries.map { it.key }.toSet().size,
        )
    }

    @Test
    fun rowIdsMapToTheirSections() {
        assertEquals(SettingsSection.Account, settingsSectionFor(AccountRowIds.Account))
        assertEquals(SettingsSection.Account, settingsSectionFor(AccountRowIds.Relay))
        assertEquals(SettingsSection.AgentAccounts, settingsSectionFor("agent-account:claude-code"))
        assertEquals(SettingsSection.Devices, settingsSectionFor(DevicesRowIds.device("mac")))
        assertEquals(SettingsSection.Voice, settingsSectionFor("voice"))
        assertEquals(SettingsSection.Notifications, settingsSectionFor("notify:enabled"))
        assertEquals(SettingsSection.Appearance, settingsSectionFor("appearance:2"))
        assertEquals(SettingsSection.Wallpaper, settingsSectionFor("wallpaper:choose"))
        assertEquals(SettingsSection.Math, settingsSectionFor("math:render"))
        assertEquals(SettingsSection.Sessions, settingsSectionFor(SessionsSettingsRow.GroupByProject.id))
        assertEquals(SettingsSection.Sessions, settingsSectionFor(SessionsSettingsRow.Archived.id))
        assertEquals(SettingsSection.SignOut, settingsSectionFor(AccountRowIds.SignOut))
    }

    @Test
    fun removedRowsMapToNothing() {
        assertNull("D8: no Background section", settingsSectionFor("background:stay-connected"))
        assertNull("D10: no About/Licences", settingsSectionFor("about:licenses"))
        assertNull(settingsSectionFor("unknown"))
    }

    @Test
    fun theBottomPaddingAddsTheTrailingSpaceToTheChromePadding() {
        assertEquals(16.dp, settingsBottomPadding(0.dp))
        assertEquals(104.dp, settingsBottomPadding(88.dp))
    }
}
