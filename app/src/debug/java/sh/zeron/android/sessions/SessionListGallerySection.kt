package sh.zeron.android.sessions

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.util.Collections
import sh.zeron.android.core.Face
import sh.zeron.android.design.Appearance
import sh.zeron.android.design.LinearFontScaleDensity
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.ProvideGlyphClock
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.TypeScale
import sh.zeron.android.design.uiStyle

/** The gallery's status selector: rows, or the blank list (iOS has no other state). */
private enum class StatusDemo(val label: String) {
    Content("Content"),
    Blank("Blank"),
}

/**
 * The "Session list" page of the debug design gallery (android-ui-parity
 * #41): the shared list host over [SessionListFixtures] with the ticket's
 * toggles — collapsible, fold "Mobile", current row 3, editing, content or
 * blank, "Shuffle Recent" (to watch the move animations), the
 * folder + plain-header demo, and the fake font scale 0.85 / 1.0 / 1.6
 * via [LinearFontScaleDensity].
 *
 * Tapping a row shows the `BasicText` readout "open <id>" (no NavStack in
 * the gallery); `onScreen` stays true. The list's padding mimics the
 * front page: top 155 (59 status + 44 nav row + 52 large title), bottom
 * 110.
 *
 * Registered in `design/Gallery.kt` as
 * `GallerySection("Session list", layout = GalleryLayout.Fill, screenshotHeightDp = 852) { SessionListGallerySection() }`.
 * No Material controls: plain `BasicText` + `clickable` rows, the
 * selected value in `accent`.
 */
@Composable
fun SessionListGallerySection() {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current

    var collapsible by remember { mutableStateOf(true) }
    var foldMobile by remember { mutableStateOf(false) }
    var currentRow3 by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var folderDemo by remember { mutableStateOf(false) }
    var statusKind by remember { mutableStateOf(StatusDemo.Content) }
    var fontScale by remember { mutableStateOf(1f) }
    var recent by remember { mutableStateOf(SessionListFixtures.recentRows) }
    var opened by remember { mutableStateOf<String?>(null) }

    val sections = when {
        statusKind != StatusDemo.Content -> emptyList()
        folderDemo -> SessionListFixtures.folderPlainSections()
        else -> SessionListFixtures.sections(recent = recent)
    }
    // The folder demo is the non-collapsible API: plain headers, one folder.
    val effectiveCollapsible = collapsible && !folderDemo
    val collapsedIds = if (foldMobile) setOf("mobile") else emptySet()

    Column(Modifier.fillMaxSize().background(palette.background)) {
        ControlRow(
            "Collapsible",
            collapsible,
        ) { collapsible = !collapsible }
        ControlRow("Fold \u201cMobile\u201d", foldMobile) { foldMobile = !foldMobile }
        ControlRow("Current = row 3", currentRow3) { currentRow3 = !currentRow3 }
        ControlRow("Editing", editing) { editing = !editing }
        ControlRow("Folder + plain header", folderDemo) { folderDemo = !folderDemo }
        for (kind in StatusDemo.entries) {
            ControlRow("Status: ${kind.label}", statusKind == kind) { statusKind = kind }
        }
        for (factor in listOf(0.85f, 1f, 1.6f)) {
            ControlRow("Fake font scale $factor", fontScale == factor) { fontScale = factor }
        }
        ControlRow("Shuffle Recent", false) {
            recent = recent.toMutableList().apply { Collections.shuffle(this) }
        }

        // The fake font scale: the same linear-density override as the Type
        // gallery, so the factor (and the row heights) hit 0.85 / 1.6
        // exactly, clamped ends included.
        val outer = LocalDensity.current
        val scaled = remember(outer.density, fontScale) { LinearFontScaleDensity(outer.density, fontScale) }
        val scaledTypeScale = remember(scaled) { TypeScale.from(scaled) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CompositionLocalProvider(
                LocalDensity provides scaled,
                LocalTypeScale provides scaledTypeScale,
            ) {
                // The model is built inside the fake scale so its
                // animation gate sees the same factor the rows do.
                val model = rememberSessionListModel(
                    sections = sections,
                    collapsible = effectiveCollapsible,
                    collapsedIds = collapsedIds,
                )
                SessionList(
                    model = model,
                    contentPadding = PaddingValues(top = 155.dp, bottom = 110.dp),
                    onOpenSession = { id -> opened = id },
                    onOpenFolder = { id -> opened = "folder $id" },
                    onToggleSection = { id -> if (id == "mobile") foldMobile = !foldMobile },
                    currentChatId = if (currentRow3) SessionListFixtures.currentRow.id else null,
                    editing = editing,
                )
            }
        }

        BasicText(
            opened?.let { "open $it" } ?: "tap a row",
            style = uiStyle(Face.Mono, typeScale.fixed(11f), palette.tertiary),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** A toggle row: the label in `accent` when on, `secondary` when off. */
@Composable
private fun ControlRow(text: String, checked: Boolean, onClick: () -> Unit) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    BasicText(
        text,
        style = uiStyle(
            Face.SansMedium,
            typeScale.fixed(13f),
            if (checked) palette.accent else palette.secondary,
        ),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 2.dp, bottom = 2.dp, end = 16.dp),
    )
}

// ---------------------------------------------------------------------------
// Previews: light + dark with the fixture, plus the blank list.
// All pass their own onOpenSession — rememberSessionListOpener needs a
// NavStackHost, which previews do not have.
// ---------------------------------------------------------------------------

@Composable
private fun SessionListPreviewBody(
    appearance: Appearance,
    sections: List<ListSection> = SessionListFixtures.sections(),
    collapsible: Boolean = true,
) {
    ZeronTheme(appearance) {
        // Frozen at 0 ns: the spinner renders the cycle-0 pattern and the
        // preview never starts a frame loop.
        ProvideGlyphClock(frozenAtNanos = 0L) {
            val model = rememberSessionListModel(
                sections = sections,
                collapsible = collapsible,
            )
            SessionList(
                model = model,
                contentPadding = PaddingValues(top = 155.dp, bottom = 110.dp),
                onOpenSession = {},
            )
        }
    }
}

@Preview(name = "Session list – light", widthDp = 393, heightDp = 852)
@Composable
private fun SessionListLightPreview() {
    SessionListPreviewBody(Appearance.Light)
}

@Preview(name = "Session list – dark", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SessionListDarkPreview() {
    SessionListPreviewBody(Appearance.Dark)
}

@Preview(name = "Session list – blank", widthDp = 393, heightDp = 852)
@Composable
private fun SessionListBlankPreview() {
    SessionListPreviewBody(Appearance.Light, sections = emptyList())
}

@Preview(name = "Session list – folder + plain header", widthDp = 393, heightDp = 852)
@Composable
private fun SessionListFolderPlainPreview() {
    SessionListPreviewBody(Appearance.Light, sections = SessionListFixtures.folderPlainSections(), collapsible = false)
}
