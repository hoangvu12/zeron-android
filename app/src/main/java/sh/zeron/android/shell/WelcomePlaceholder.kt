package sh.zeron.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import sh.zeron.android.design.ZeronTheme

/**
 * Stub for the signed-out root while the welcome screen is unported: an
 * opaque page in the window background, so the cross-fade has something
 * to fade to. #16 replaces it with the port of iOS's
 * `Shell/SignInViewController.swift`.
 */
@Composable
internal fun WelcomePlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(ZeronTheme.palette.background),
    )
}
