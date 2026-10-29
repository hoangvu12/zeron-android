package sh.zeron.android.cloud

import sh.zeron.android.core.CloudStore
import sh.zeron.android.design.Feedback
import sh.zeron.android.design.Haptic
import sh.zeron.android.design.Toaster
import sh.zeron.android.shell.AppRouter

/**
 * Ways into Cloud's pages from anywhere in the app — a toast's button, a
 * chat's notice (#53; iOS `Cloud/CloudRoutes.swift`).
 */
object CloudRoutes {
    /** iOS `openBilling`: Billing over whatever is showing (pushed on the shell's main stack here). */
    fun openBilling(router: AppRouter) = router.showDebugPages(listOf(BillingRoute))

    /** iOS `showOutOfCredits`: the warning haptic and "Out of Cloud credits" with "Add credits". */
    fun showOutOfCredits(toaster: Toaster, feedback: Feedback, router: AppRouter) {
        feedback.haptic(Haptic.Attention)
        toaster.show(CloudStore.OUT_OF_CREDITS, action = "Add credits") { openBilling(router) }
    }
}
