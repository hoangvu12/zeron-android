package sh.zeron.android.newsession

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.zeron.android.core.Face
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.glass.ProminentGlassButton
import sh.zeron.android.design.uiStyle

/**
 * The folder browser's bottom stack geometry (dp), the port of
 * `ios:Threads/NewProjectViewController.swift:61-83`
 * (the UIStackView metrics): 20 from the horizontal safe area, 12 above
 * the navigation-bar inset — **not** the IME; the alert is a separate
 * in-window layer (ticket 18) — 8 between children, every child the
 * full stack width.
 */
internal object FolderBottomBarTokens {

    /** `leading/trailing = safeArea ± 20`. */
    val SideInset: Dp = 20.dp

    /** `bottom = safeArea.bottom − 12`. */
    val BottomInset: Dp = 12.dp

    /** `stack.spacing = 8`. */
    val Spacing: Dp = 8.dp
}

/**
 * The bottom of the New Project folder browser (android-ui-parity #111):
 * a vertical stack — the status label and the full-width prominent Use
 * capsule — overlaid on the folder list
 * (110's 160 dp bottom content inset keeps the last row reachable).
 *
 * The stack has **no background**: rows scroll underneath it, and the
 * column itself is not a scroll container and consumes no touches —
 * there is no `clickable`/`pointerInput` on it, so taps between the
 * children reach the list underneath, exactly like a `UIStackView`
 * outside its subviews. No motion of its own: status, title and enable
 * changes apply on the next frame (UIKit sets them instantly); the busy
 * spinner and press response are ticket 08's, the press dimming here.
 *
 * @param status 110's status string; `""` composes no row (iOS's empty
 * label is 0 tall, the rest of the stack just closes up)
 * @param useTitle `Use “<name>”` (U+201C/U+201D), `""` before the first
 * listing — an empty title still lays out one line, so the capsule
 * keeps its height
 * @param useEnabled `canAct || creatingProject`: the busy capsule keeps
 * the enabled look (ticket 08 ignores clicks while busy)
 * @param useBusy ticket 08's spinner + "In progress" state
 */
@Composable
fun FolderBrowserBottomBar(
    status: String,
    useTitle: String,
    useEnabled: Boolean,
    useBusy: Boolean,
    onUse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    Column(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .padding(
                start = FolderBottomBarTokens.SideInset,
                end = FolderBottomBarTokens.SideInset,
                bottom = FolderBottomBarTokens.BottomInset,
            )
            .testTag("folder-bottom-bar"),
        verticalArrangement = Arrangement.spacedBy(FolderBottomBarTokens.Spacing),
    ) {
        if (status.isNotEmpty()) {
            BasicText(
                status,
                Modifier
                    .fillMaxWidth()
                    .testTag("folder-status"),
                style = uiStyle(Face.Sans, ts.fixed(13f), palette.secondary)
                    .copy(textAlign = TextAlign.Center),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ProminentGlassButton(
            text = useTitle,
            onClick = onUse,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("use-folder"),
            busy = useBusy,
            enabled = useEnabled,
        )
    }
}
