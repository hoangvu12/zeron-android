package sh.zeron.android.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import sh.zeron.android.design.ActionSheetAction
import sh.zeron.android.design.ActionSheetModel
import sh.zeron.android.design.LocalActionSheet

/**
 * "Choose an organization" (`SignInViewController.swift:155-166`): one row
 * per organization plus Cancel, on the app's action sheet. Mounted once
 * inside the root hosts, so it shows over whichever screen started the
 * sign-in. Cancel (or the scrim) stops sign-in quietly.
 */
@Composable
fun OrgPicker(session: AuthSession) {
    val choice by session.orgChoice.collectAsState()
    val sheets = LocalActionSheet.current
    LaunchedEffect(choice) {
        val pending = choice ?: return@LaunchedEffect
        sheets.show(
            ActionSheetModel(
                title = "Choose an organization",
                actions = pending.orgs.map { org -> ActionSheetAction(org.name) { pending.choose(org) } },
                onCancel = { pending.choose(null) },
            ),
        )
    }
}
