package sh.zeron.android.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import sh.zeron.android.R
import sh.zeron.android.core.Face
import sh.zeron.android.design.CapsuleShape
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.WallpaperHero
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.uiStyle
import sh.zeron.android.settings.RelayLink

/**
 * The signed-out root: a port of `ios:Shell/SignInViewController.swift:58-106`
 * — the wallpaper over the whole page, the Zeron mark, "Welcome to Zeron",
 * the tagline, and a full-width "Get started" capsule at the bottom.
 *
 * [onGetStarted] is `getStarted()` (SignInVC:108-114): iOS presents the
 * onboarding flow with a cross-dissolve, opening on the appearance step
 * unless this phone has seen it, otherwise on sync (logging in). Both the
 * flow and that step choice belong to onboarding (#17); sign-in itself
 * (#15) runs from onboarding's sync step. There is no demo button (D12).
 */
@Composable
fun WelcomeScreen(onGetStarted: () -> Unit, modifier: Modifier = Modifier) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    Box(modifier.fillMaxSize().background(palette.background)) {
        // SignInVC:61-64: a full-bleed `WallpaperView` (the mask fades it
        // over the whole height).
        WallpaperHero(Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            // SignInVC:89-102: the stack centred on the safe area, 40 pt
            // above centre, at least 32 pt from the sides.
            Column(
                Modifier
                    .align(Alignment.Center)
                    .offset(y = (-40).dp)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // SignInVC:70: 53 × 53 (the square mark of upstream 2a2212c70).
                ZeronMark(palette.text, Modifier.size(53.dp))
                Spacer(Modifier.height(26.dp))
                BasicText(
                    "Welcome to Zeron",
                    style = uiStyle(Face.SansSemibold, typeScale.fixed(30f), palette.text)
                        .copy(textAlign = TextAlign.Center),
                )
                Spacer(Modifier.height(12.dp))
                BasicText(
                    "Your coding agents, from anywhere.",
                    style = uiStyle(Face.Sans, typeScale.fixed(17f), palette.secondary)
                        .copy(textAlign = TextAlign.Center),
                )
            }
            // #130 (beyond iOS): the relay sign-in goes through, small in
            // the top corner so the page itself is unchanged.
            RelayLink(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 12.dp))
            // SignInVC:103-105: 24 pt side insets, 16 pt above the safe area.
            WelcomePrimaryButton(
                title = "Get started",
                onClick = onGetStarted,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
                    .fillMaxWidth()
                    .testTag("sign-in"),
            )
        }
    }
}

/**
 * `CloudUI.primaryButton` (`ios:Cloud/CloudUI.swift:7-24`): a filled
 * capsule in the text colour with the title in the background colour,
 * Sans SemiBold 17, 15 × 20 pt insets. Pressed dims the fill like a
 * highlighted filled `UIButton`.
 */
@Composable
private fun WelcomePrimaryButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val fill = if (pressed) lerp(palette.text, palette.background, 0.25f) else palette.text
    Box(
        modifier
            .clip(CapsuleShape)
            .background(fill)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(title, style = uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.background))
    }
}

/**
 * `CloudUI.zeronMark`: the `zeron-logo` template image
 * (`ios:Assets.xcassets/zeron-logo.imageset/zeron-logo.svg`, the 780 × 780
 * app-icon mark since upstream 2a2212c70), tinted and aspect-fitted into
 * the frame. Drawn from `zi_zeron`, generated from the same mark
 * (`icons/zeron.svg`).
 */
@Composable
internal fun ZeronMark(tint: Color, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.zi_zeron),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(tint),
    )
}