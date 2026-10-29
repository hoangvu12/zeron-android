package sh.zeron.android.sessions

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.zeron.android.core.Face
import sh.zeron.android.design.Appearance
import sh.zeron.android.design.LocalToaster
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.ToastHost
import sh.zeron.android.design.uiStyle
import uniffi.zeron_core.CoreException

/**
 * The fake's scripted failure mode — each maps to one row of the ticket's
 * failure table, so every mode/button pair can be checked against it.
 */
private enum class WriteMode(val label: String) {
    Ok("OK"),
    Offline("offline"),
    NotSynced("not synced"),
    Gone("gone"),
    Invalid("invalid"),
}

/**
 * The gallery's [SessionWrites] fake: throws per [mode] — the exact
 * errors android-app#07 raises: `HostUnavailable("Engine is offline;
 * reconnecting")`, `Unsupported("pins are not synced yet")`,
 * `NotFound("chat")` and `InvalidArgument("section name is too long")`.
 */
private class FakeSessionWrites(var mode: WriteMode = WriteMode.Ok) : SessionWrites {
    private fun fail() {
        when (mode) {
            WriteMode.Ok -> {}
            WriteMode.Offline -> throw CoreException.HostUnavailable("Engine is offline; reconnecting")
            WriteMode.NotSynced -> throw CoreException.Unsupported("pins are not synced yet")
            WriteMode.Gone -> throw CoreException.NotFound("chat")
            WriteMode.Invalid -> throw CoreException.InvalidArgument("section name is too long")
        }
    }

    override fun pinSession(chatId: String) = fail()
    override fun unpinSession(chatId: String) = fail()
    override fun archiveSession(chatId: String) = fail()
    override fun unarchiveSession(chatId: String) = fail()
    override fun deleteSession(chatId: String) = fail()
    override fun pinSessionAt(chatId: String, after: String?, before: String?) = fail()
    override fun placeSession(chatId: String, sectionId: String?, after: String?, before: String?) = fail()
    override fun assignSection(chatId: String, sectionId: String?) = fail()
    override fun movePin(chatId: String, after: String?, before: String?) = fail()
    override fun renameSession(chatId: String, title: String) = fail()
    override fun createSection(name: String): String {
        fail()
        return "gallery:s1"
    }
    override fun renameSection(sectionId: String, name: String) = fail()
    override fun deleteSection(sectionId: String) = fail()
}

/**
 * The "Row actions" page of the debug design gallery (android-ui-parity
 * #42): the write surface over a fake — a mode switch for the scripted
 * failures and the Archive/Pin/Move/Rename buttons. A failure is only
 * logged (iOS `attempt`); the one toast is "Archived · Undo", whose Undo
 * drives the real unarchive path.
 *
 * Registered in `design/Gallery.kt` (ticket 22) as
 * `GallerySection("Row actions") { RowActionsGallerySection() }`; the
 * toasts appear through the `ToastHost` inside `GalleryProviders`.
 *
 * The `CoreRowActions` here holds a `rememberCoroutineScope()` — a screen
 * scope, acceptable **only** in the gallery: the real instance is
 * app-scoped (see [CoreRowActions]). No Material controls: plain
 * `BasicText` + `clickable` rows, mode rows in `palette.text`, the
 * selected mode and the buttons in `palette.accent` (ticket 01 roles:
 * light `#27272C` / `#5B43E8`, dark `#E8E8EA` / `#8B7CF6`).
 */
@Composable
fun RowActionsGallerySection() {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(WriteMode.Ok) }
    val fake = remember { FakeSessionWrites() }
    val actions = remember {
        CoreRowActions(
            writes = { fake },
            sections = { emptyList() },
            toaster = toaster,
            scope = scope,
            log = {},
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GalleryLabel(
            "Row actions",
            uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text),
        )
        GalleryLabel(
            "The mode scripts the fake's failure; the buttons run through " +
                "CoreRowActions (onResult and the serial IO lane).",
            uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
        )
        for (candidate in WriteMode.entries) {
            GalleryLabel(
                candidate.label,
                uiStyle(
                    Face.SansMedium,
                    typeScale.fixed(14f),
                    if (candidate == mode) palette.accent else palette.text,
                ),
                Modifier.clickable {
                    mode = candidate
                    fake.mode = candidate
                },
            )
        }
        val actionStyle = uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.accent)
        GalleryLabel("Archive", actionStyle, Modifier.clickable { actions.archive("gallery-chat") })
        GalleryLabel("Pin", actionStyle, Modifier.clickable { actions.setPinned("gallery-chat", true) })
        GalleryLabel("Move", actionStyle, Modifier.clickable { actions.move("gallery-chat", null) })
        GalleryLabel("Rename", actionStyle, Modifier.clickable { actions.rename("gallery-chat", "Gallery rename") })
    }
}

/** A plain text row; with a `clickable` modifier attached it is a button (no Material controls). */
@Composable
private fun GalleryLabel(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    BasicText(text, style = style, modifier = modifier.padding(vertical = 2.dp))
}

// ---------------------------------------------------------------------------
// Previews: the section needs a ToastHost to show anything — its own mount,
// which the gallery's GalleryProviders replaces for the real gallery.
// ---------------------------------------------------------------------------

@Preview(name = "Row actions gallery light")
@Composable
private fun RowActionsGalleryLightPreview() {
    ZeronTheme(Appearance.Light) { ToastHost { RowActionsGallerySection() } }
}

@Preview(name = "Row actions gallery dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RowActionsGalleryDarkPreview() {
    ZeronTheme(Appearance.Dark) { ToastHost { RowActionsGallerySection() } }
}
