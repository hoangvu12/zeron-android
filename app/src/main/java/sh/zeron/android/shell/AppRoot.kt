package sh.zeron.android.shell

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.zeron.android.R
import sh.zeron.android.core.Face
import sh.zeron.android.design.Appearance
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.Palette
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.uiStyle

/**
 * The observed state of the app root (android-ui-parity #35). [kind] is
 * the root on screen, `null` while `Loading`/`CoreFailed` — ticket 36
 * reads it to drop deep links on the welcome root. Written only from
 * [SideEffect]s (never during composition).
 */
@Stable
class AppRootState {
    var kind: RootKind? by mutableStateOf(null)
        internal set
}

/**
 * The window root (android-ui-parity #35): what iOS's `SceneDelegate`
 * does outside any screen. Shows exactly one of three roots —
 *
 * - [RootInput.Loading]: nothing drawn; the splash stays ([FirstFrameHold]
 *   holds the first frame, and this box matches the launch background);
 * - [RootInput.CoreFailed]: the diagnostic screen ([CoreUnavailableScreen]);
 * - [RootInput.Ready]: the welcome root when signed out, the phone
 *   tab shell (26) below [SplitBreakpoint], the tablet split (33) at it
 *   and wider — [chooseRoot] over this composable's real constraints.
 *
 * Welcome ↔ Shell swaps through [RootCrossfade]: the presentations are
 * dismissed instantly first, then the old root fades out **on top** of
 * the new one over 350 ms. Phone ↔ Split is in place, no fade (108 owns
 * the carry-over). The first root appears without animation —
 * `RootCrossfade` is first composed with `shown = target`, the
 * `SceneDelegate.swift:95-97` rule.
 *
 * Must be inside `ZeronTheme`. The root paints the window background
 * ([Palette.background]) under the roots (every root paints its own
 * opaque background too, so the cross-fade never shows it) and consumes
 * or pads **no** insets: every screen applies its own `safeDrawing`/`ime`.
 *
 * @param shellScope wraps both shells, inside the shell group of the
 *   cross-fade. Ticket 108's production wiring passes its
 *   [ShellStateScope] here, so the hoisted shell state (tabs, tablet,
 *   sidebar, routers) lives exactly as long as the shell group: it
 *   survives every Phone ↔ Split switch (the scope is composed for both
 *   kinds) and is dropped after the 350 ms fade-out to Welcome. The
 *   default is the identity scope (previews, tests, the gallery).
 */
@Composable
fun AppRoot(
    input: RootInput,
    state: AppRootState,
    presentations: RootPresentations,
    welcome: @Composable () -> Unit,
    phone: @Composable () -> Unit,
    split: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    coreFailed: @Composable (String) -> Unit = { CoreUnavailableScreen(it) },
    shellScope: @Composable (kind: RootKind, content: @Composable () -> Unit) -> Unit = { _, c -> c() },
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(ZeronTheme.palette.background),
    ) {
        when (input) {
            RootInput.Loading -> {
                // The splash's colour: the hold (FirstFrameHold) usually
                // keeps this from ever drawing.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            if (ZeronTheme.isDark) Palette.LaunchBackgroundDark else Palette.LaunchBackgroundLight,
                        ),
                )
                SideEffect { state.kind = null }
            }

            is RootInput.CoreFailed -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("root-core-failed"),
                ) { coreFailed(input.reason) }
                SideEffect { state.kind = null }
            }

            is RootInput.Ready -> {
                val kind = chooseRoot(input.signedIn, maxWidth, maxHeight)
                SideEffect { state.kind = kind }
                RootCrossfade(
                    target = kind.group,
                    beforeSwap = presentations::dismissAllInstantly,
                ) { group ->
                    when (group) {
                        RootGroup.Welcome -> {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .testTag("root-welcome"),
                            ) { welcome() }
                        }

                        RootGroup.Shell -> {
                            // Re-chosen live: a size change across the breakpoint
                            // switches phone/split in place, inside the
                            // shell group, with no fade (108 owns the
                            // carry-over). The shell scope wraps the switch,
                            // so the hoisted shell state survives it.
                            val shellKind = chooseRoot(signedIn = true, width = maxWidth, height = maxHeight)
                            shellScope(shellKind) {
                                if (shellKind == RootKind.Split) {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .testTag("root-split"),
                                    ) { split() }
                                } else {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .testTag("root-phone"),
                                    ) { phone() }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The core-load failure screen (android-app#13's placeholder content,
 * moved here by ticket 35): full-screen launch background, "Zeron" in
 * Geist SemiBold 22 and the diagnostic line in GeistMono 13 at 60 %
 * alpha, `#000000` light / `#FFFFFF` dark. The copy is verbatim:
 * "Couldn't load the Zeron core: <reason>".
 */
@Composable
fun CoreUnavailableScreen(reason: String, modifier: Modifier = Modifier) {
    val typeScale = LocalTypeScale.current
    // #000000 on the light launch background, #FFFFFF on the dark one
    // (the placeholder's inks, kept verbatim).
    val ink = if (ZeronTheme.isDark) Color(0xFFFFFFFF) else Color(0xFF000000)
    Box(
        modifier
            .fillMaxSize()
            .background(colorResource(R.color.launch_background)),
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BasicText(
                text = "Zeron",
                style = uiStyle(Face.SansSemibold, typeScale.fixed(22f), ink),
            )
            BasicText(
                text = "Couldn't load the Zeron core: $reason",
                style = uiStyle(Face.Mono, typeScale.fixed(13f), ink.copy(alpha = 0.6f)),
            )
        }
    }
}

/**
 * The ticket's fake root: a full-size opaque box in [fill] with [label]
 * centred — for the previews, the Roborazzi goldens and the debug
 * gallery. The fills differ per root so the cross-fade and its mid-fade
 * composite are visible: Welcome = `palette.accent`, Phone =
 * `palette.background`, Split = `palette.codeBackground`.
 */
@Composable
internal fun FakeRoot(
    label: String,
    fill: Color,
    text: Color,
    modifier: Modifier = Modifier,
) {
    val typeScale = LocalTypeScale.current
    Box(
        modifier
            .fillMaxSize()
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = uiStyle(Face.SansSemibold, typeScale.fixed(22f), text))
    }
}

// ---------------------------------------------------------------------------
// Previews — the ticket's frame: 393 × 852 (a phone, so the shell is the
// tab shell; the gallery toggles 393/900 for the split).
// ---------------------------------------------------------------------------

@Preview(name = "AppRoot – Light", widthDp = 393, heightDp = 852)
@Composable
private fun AppRootLightPreview() {
    AppRootPreviewBody(Appearance.Light)
}

@Preview(name = "AppRoot – Dark", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AppRootDarkPreview() {
    AppRootPreviewBody(Appearance.Dark)
}

@Composable
private fun AppRootPreviewBody(appearance: Appearance) {
    ZeronTheme(appearance) {
        val palette = ZeronTheme.palette
        AppRoot(
            input = RootInput.Ready(signedIn = true),
            state = remember { AppRootState() },
            presentations = remember { RootPresentations() },
            welcome = { FakeRoot("Welcome", palette.accent, palette.text) },
            phone = { FakeRoot("Phone", palette.background, palette.text) },
            split = { FakeRoot("Split", palette.codeBackground, palette.text) },
        )
    }
}
