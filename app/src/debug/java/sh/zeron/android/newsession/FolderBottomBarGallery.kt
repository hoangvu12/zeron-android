package sh.zeron.android.newsession

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.zeron.android.core.Face
import sh.zeron.android.design.Appearance
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.uiStyle

/**
 * The "Folder bottom bar" section of the debug design gallery
 * (android-ui-parity #111): the status line and the full-width Use
 * capsule over folder rows — the ticket's four fixtures (a)–(d), each in
 * a fixed-height page whose rows run underneath the background-less
 * stack.
 *
 * The fixtures and the stage are shared verbatim with the screenshot
 * test `FolderBrowserBottomBarTest` (the debug source set is visible to
 * `testDebugUnitTest`). Registered in `design/Gallery.kt` as
 * `GallerySection("Folder bottom bar", screenshotHeightDp = 600) { FolderBottomBarGallerySection() }`.
 */

// ---------------------------------------------------------------------------
// Fixtures (shared with the screenshot tests)
// ---------------------------------------------------------------------------

/** One bottom-bar state: the four inputs of [FolderBrowserBottomBar]. */
data class FolderBottomBarSpec(
    val status: String,
    val useTitle: String,
    val useEnabled: Boolean,
    val useBusy: Boolean,
)

/** The status of fixture (d): past two lines of the 13 pt label, so it ellipsizes. */
const val FOLDER_BOTTOM_BAR_LONG_STATUS: String =
    "/Volumes/Seagate Backup Plus Drive/2026 Photography Archive/a folder name that runs past " +
        "the two line limit of the thirteen point status label and ellipsizes at the tail"

/** The ticket's four fixtures (a)–(d), in gallery order. */
fun folderBottomBarFixtures(): List<Pair<String, FolderBottomBarSpec>> = listOf(
    "a — a good listing" to FolderBottomBarSpec(
        status = "/srv",
        useTitle = "Use \u201Csrv\u201D",
        useEnabled = true,
        useBusy = false,
    ),
    "b — loading (everything disabled)" to FolderBottomBarSpec(
        status = "Loading…",
        useTitle = "",
        useEnabled = false,
        useBusy = false,
    ),
    "c — creating the project (busy Use)" to FolderBottomBarSpec(
        status = "/srv",
        useTitle = "Use \u201Csrv\u201D",
        useEnabled = true,
        useBusy = true,
    ),
    "d — a two-line status that ellipsizes" to FolderBottomBarSpec(
        status = FOLDER_BOTTOM_BAR_LONG_STATUS,
        useTitle = "Use \u201Csrv\u201D",
        useEnabled = true,
        useBusy = false,
    ),
)

// ---------------------------------------------------------------------------
// The stage (shared with the screenshot tests and the previews)
// ---------------------------------------------------------------------------

/**
 * One fixture page: plain folder rows tall enough to run under the bar
 * (the stack has no background — that underlap is the point), with the
 * bottom bar pinned to the page's bottom edge. The page is clipped, so
 * the rows that overshoot a short page (the gallery's cards) stop at
 * its edge instead of drawing over the next card; the caller owns the
 * page height — the gallery fixes it, the screenshot test fills the
 * frame so the 12 dp bottom inset is measured against the screen.
 */
@Composable
internal fun FolderBottomBarStage(spec: FolderBottomBarSpec, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().clipToBounds()) {
        FolderFixtureRows(Modifier.fillMaxSize())
        FolderBrowserBottomBar(
            status = spec.status,
            useTitle = spec.useTitle,
            useEnabled = spec.useEnabled,
            useBusy = spec.useBusy,
            onUse = {},
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * Plain 52 dp folder rows — enough to fill the tallest page (the test
 * frame is 874 dp), so rows run under the background-less bar in every
 * fixture page, like the list in the iOS reference captures.
 */
@Composable
private fun FolderFixtureRows(modifier: Modifier = Modifier) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    val names = listOf("Desktop", "Documents", "Downloads", "Projects", "zeron", "zeron")
    Column(modifier.background(palette.background)) {
        repeat(17) { index ->
            Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp)) {
                BasicText(
                    if (index < names.size) names[index] else "${names[index % names.size]} ${index + 1}",
                    style = uiStyle(Face.Sans, ts.fixed(16f), palette.text),
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The gallery section
// ---------------------------------------------------------------------------

@Composable
fun FolderBottomBarGallerySection() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for ((label, spec) in folderBottomBarFixtures()) {
            FixtureCard(label, spec)
        }
    }
}

/** One fixture: the label, then the 180 dp stage with rows under the bar. */
@Composable
private fun FixtureCard(label: String, spec: FolderBottomBarSpec) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(label, style = uiStyle(Face.Sans, ts.fixed(13f), palette.secondary))
        FolderBottomBarStage(spec, Modifier.height(180.dp))
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

/** The preview stage: one fixture on a full page, light or dark. */
@Composable
private fun FolderBottomBarPreviewStage(appearance: Appearance, spec: FolderBottomBarSpec) {
    ZeronTheme(appearance) {
        FolderBottomBarStage(spec, Modifier.fillMaxSize())
    }
}

private fun spec(label: Char): FolderBottomBarSpec =
    folderBottomBarFixtures().first { it.first.startsWith(label) }.second

@Preview(name = "a light")
@Composable
private fun FolderBottomBarALightPreview() {
    FolderBottomBarPreviewStage(Appearance.Light, spec('a'))
}

@Preview(name = "a dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FolderBottomBarADarkPreview() {
    FolderBottomBarPreviewStage(Appearance.Dark, spec('a'))
}

@Preview(name = "b light — loading")
@Composable
private fun FolderBottomBarBLightPreview() {
    FolderBottomBarPreviewStage(Appearance.Light, spec('b'))
}

@Preview(name = "b dark — loading", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FolderBottomBarBDarkPreview() {
    FolderBottomBarPreviewStage(Appearance.Dark, spec('b'))
}

@Preview(name = "c light — busy create")
@Composable
private fun FolderBottomBarCLightPreview() {
    FolderBottomBarPreviewStage(Appearance.Light, spec('c'))
}

@Preview(name = "c dark — busy create", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FolderBottomBarCDarkPreview() {
    FolderBottomBarPreviewStage(Appearance.Dark, spec('c'))
}

@Preview(name = "d light — two-line status")
@Composable
private fun FolderBottomBarDLightPreview() {
    FolderBottomBarPreviewStage(Appearance.Light, spec('d'))
}

@Preview(name = "d dark — two-line status", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FolderBottomBarDDarkPreview() {
    FolderBottomBarPreviewStage(Appearance.Dark, spec('d'))
}
