package sh.zeron.android.cloud

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import sh.zeron.android.core.CloudStore
import sh.zeron.android.design.Feedback
import sh.zeron.android.design.Haptic
import sh.zeron.android.design.Toaster
import sh.zeron.android.shell.AppRouter
import sh.zeron.android.shell.LocalAppRouter
import sh.zeron.android.shell.LocalPageSheetScope
import sh.zeron.android.shell.LocalSheetHost
import sh.zeron.android.shell.PageSheetScope
import sh.zeron.android.shell.SheetHostState
import sh.zeron.android.shell.SheetStyle

/**
 * Ways into Cloud's pages from anywhere in the app — a toast's button, a
 * chat's notice (#53; iOS `Cloud/CloudRoutes.swift`).
 */
object CloudRoutes {
    /** The fallback when no sheet can be presented: the router's Billing page (the phone's Settings tab, the split's Settings card). */
    fun openBilling(router: AppRouter) = router.showDebugPages(listOf(BillingRoute))

    /** iOS `showOutOfCredits`: the warning haptic and "Out of Cloud credits" with "Add credits". */
    fun showOutOfCredits(toaster: Toaster, feedback: Feedback, openBilling: () -> Unit) {
        feedback.haptic(Haptic.Attention)
        toaster.show(CloudStore.OUT_OF_CREDITS, action = "Add credits") { openBilling() }
    }
}

/**
 * iOS `CloudRoutes.openBilling` (#63): Billing in a page sheet with a Done
 * button, over whatever is showing — stacked on the New Session sheet when
 * called from inside it (its draft stays put), else presented from the root
 * sheet host. When neither can present (another sheet is busy), the
 * router shows Billing as a Settings page instead.
 */
@Composable
fun rememberBillingOpener(): () -> Unit {
    val sheet = LocalPageSheetScope.current
    val host = LocalSheetHost.current
    val router by rememberUpdatedState(LocalAppRouter.current)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return remember(sheet, host, focus, keyboard) {
        {
            // UIKit's modal presentation takes the keyboard away.
            focus.clearFocus(force = true)
            keyboard?.hide()
            val shown = sheet?.presentBilling() ?: host?.presentBilling() ?: false
            if (!shown) {
                Log.w("Cloud", "billing sheet not presented; showing the page")
                CloudRoutes.openBilling(router)
            }
        }
    }
}

internal const val BILLING_SHEET_KEY = "billing"

private fun PageSheetScope.presentBilling(): Boolean =
    present(BILLING_SHEET_KEY, SheetStyle.Page(grabber = false)) { BillingSheetBody() }

private fun SheetHostState.presentBilling(): Boolean =
    present(BILLING_SHEET_KEY, SheetStyle.Page(grabber = false)) { BillingSheetBody() }

@Composable
private fun PageSheetScope.BillingSheetBody() {
    BillingScreen(onDone = { dismiss(animated = true) })
}
