package sh.zeron.android.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Immutable
import sh.zeron.android.core.CredentialStore
import sh.zeron.android.design.ActionSheetAction
import sh.zeron.android.design.ActionSheetModel
import sh.zeron.android.design.ActionSheetStyle
import sh.zeron.android.design.GroupedAccessory
import sh.zeron.android.design.GroupedRow
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.groupedSection
import sh.zeron.android.design.settingsTag

/**
 * Settings' **Account** and **Sign Out** sections (#18): the port of
 * `MoreViewController.swift:191-196, 246-248, 310-315` and `AppModel`'s
 * `accountName` / `accountDetail` (`AppModel.swift:637-649`). Account is
 * the first section, Sign Out the last; the rest of the Settings
 * restructure is #35's.
 */

/** The row ids (`settings-account`, `settings-signout`), iOS's. */
object AccountRowIds {
    const val Account = "account"

    /** #130: the Relay row under the account. */
    const val Relay = "relay"
    const val SignOut = "signout"
}

/** Who the app runs as, for the Account row (`ZeronApplication.account`). */
@Immutable
data class AccountIdentity(
    val demo: Boolean = false,
    val signedIn: Boolean = false,
    val profile: CredentialStore.Profile = CredentialStore.Profile(),
)

/** iOS `accountName`: "Demo" / "Signed out" / name ?? email ?? "Signed in" — never the WorkOS ids. */
fun accountName(identity: AccountIdentity): String = when {
    identity.demo -> "Demo"
    !identity.signedIn -> "Signed out"
    else -> identity.profile.name ?: identity.profile.email ?: "Signed in"
}

/** iOS `accountDetail`: "Offline demo workspace" / "email · org" / "Zeron account". */
fun accountDetail(identity: AccountIdentity): String {
    if (identity.demo) return "Offline demo workspace"
    val p = identity.profile
    // The email is the title when there is no name, so it isn't repeated here.
    return listOfNotNull(if (p.name != null) p.email else null, p.orgName)
        .joinToString(" · ")
        .ifEmpty { "Zeron account" }
}

/** The confirmation (`MoreViewController.swift:310-315`), verbatim. */
fun signOutSheet(onSignOut: () -> Unit): ActionSheetModel = ActionSheetModel(
    title = "Sign out?",
    message = "Local drafts stay on this device.",
    actions = listOf(ActionSheetAction("Sign Out", ActionSheetStyle.Destructive, onSelect = onSignOut)),
)

/**
 * The Account section: the account row (person icon, no accessory, no
 * tap), then #130's Relay row — "Zeron", or the custom relay's host — which
 * opens the Relay setting.
 */
fun LazyListScope.accountSection(
    identity: AccountIdentity,
    relay: String? = null,
    onRelay: () -> Unit = {},
) {
    groupedSection(
        key = "account",
        header = "Account",
        items = listOf(AccountRowIds.Account, AccountRowIds.Relay),
        itemKey = { it },
    ) { id, position ->
        when (id) {
            AccountRowIds.Account -> GroupedRow(
                title = accountName(identity),
                position = position,
                subtitle = accountDetail(identity),
                icon = ZeronIcons.PersonCropCircle,
                accessories = emptyList(),
                testTag = settingsTag(AccountRowIds.Account),
            )
            else -> GroupedRow(
                title = "Relay",
                position = position,
                icon = ZeronIcons.ServerRack,
                accessories = listOf(GroupedAccessory.Value(relayLabel(relay)), GroupedAccessory.Disclosure),
                testTag = settingsTag(AccountRowIds.Relay),
                onClick = onRelay,
            )
        }
    }
}

/**
 * The untitled last section: the destructive Sign Out row. [onClick]
 * presents [signOutSheet] (also in the demo, which it leaves).
 */
fun LazyListScope.signOutSection(onClick: () -> Unit) {
    groupedSection(
        key = "signout",
        header = " ",
        items = listOf(AccountRowIds.SignOut),
        itemKey = { it },
    ) { id, position ->
        GroupedRow(
            title = "Sign Out",
            position = position,
            icon = ZeronIcons.RectanglePortraitAndArrowRight,
            destructive = true,
            accessories = emptyList(),
            testTag = settingsTag(id),
            onClick = onClick,
        )
    }
}
