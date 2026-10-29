package sh.zeron.android.sessions

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.zeron.android.core.Face
import sh.zeron.android.design.ActionSheetHost
import sh.zeron.android.design.ActionSheetHostState
import sh.zeron.android.design.AlertHost
import sh.zeron.android.design.Appearance
import sh.zeron.android.design.GalleryBackdrop
import sh.zeron.android.design.LocalToaster
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.Toaster
import sh.zeron.android.design.ToastHost
import sh.zeron.android.design.uiStyle
import uniffi.zeron_core.ChatIndicator
import uniffi.zeron_core.SectionView
import uniffi.zeron_core.SessionRow

/**
 * The gallery's [RowActions] fake: every call logs to [log] (the "Last
 * result" line, e.g. `createSection(Infra 2)`), `onResult` fires
 * synchronously, and [failMode] imitates ticket 42's offline row for the
 * **section** writes only —
 * [ActionOutcome.Failed] — so "Delete fails (offline)" shows a
 * refused delete that never pops.
 */
private class LoggingRowActions(
    private val toaster: Toaster,
    private val failMode: () -> Boolean,
    private val log: (String) -> Unit,
) : RowActions {

    /** The ticket's fixture: Backend, Design review and Infra. */
    private val fixtureSections = listOf(
        SectionView(id = "backend", name = "Backend", collapsed = false, sessions = emptyList()),
        SectionView(id = "design", name = "Design review", collapsed = false, sessions = emptyList()),
        SectionView(id = "infra", name = "Infra", collapsed = false, sessions = emptyList()),
    )

    private fun ok(onResult: (ActionOutcome) -> Unit) = onResult(ActionOutcome.Ok)

    private fun sectionWrite(what: String, onResult: (ActionOutcome) -> Unit, onOk: () -> Unit = {}) {
        log(what)
        if (failMode()) {
            onResult(ActionOutcome.Failed)
        } else {
            onOk()
            onResult(ActionOutcome.Ok)
        }
    }

    override fun setPinned(chatId: String, pinned: Boolean, onResult: (ActionOutcome) -> Unit) {
        log("setPinned($chatId, $pinned)")
        ok(onResult)
    }

    override fun archive(chatId: String, onResult: (ActionOutcome) -> Unit) {
        log("archive($chatId)")
        ok(onResult)
    }

    override fun unarchive(chatId: String, onResult: (ActionOutcome) -> Unit) {
        log("unarchive($chatId)")
        ok(onResult)
    }
    override fun delete(chatId: String, onResult: (ActionOutcome) -> Unit) {
        log("delete($chatId)")
        ok(onResult)
    }

    override fun move(chatId: String, toSection: String?, onResult: (ActionOutcome) -> Unit) {
        log("move($chatId, ${toSection ?: "null"})")
        ok(onResult)
    }

    override fun movePin(chatId: String, after: String?, before: String?, onResult: (ActionOutcome) -> Unit) {
        log("movePin($chatId)")
        ok(onResult)
    }

    override fun rename(chatId: String, title: String, onResult: (ActionOutcome) -> Unit) {
        log("rename($chatId, $title)")
        ok(onResult)
    }

    override fun createSection(
        name: String,
        onResult: (ActionOutcome) -> Unit,
        onCreated: (sectionId: String) -> Unit,
    ) = sectionWrite("createSection($name)", onResult) { onCreated("gallery:s1") }

    override fun renameSection(sectionId: String, name: String, onResult: (ActionOutcome) -> Unit) =
        sectionWrite("renameSection($sectionId, $name)", onResult)

    override fun deleteSection(sectionId: String, onResult: (ActionOutcome) -> Unit) =
        sectionWrite("deleteSection($sectionId)", onResult)

    override fun sections(): List<SectionView> = fixtureSections
}

/** The row the launchers take (44 passes the live record): id and displayed title. */
private fun galleryRow(id: String, title: String): SessionRow = SessionRow(
    id = id,
    revision = 1uL,
    title = title,
    hasTitle = true,
    preview = null,
    project = null,
    deviceId = "device-studio",
    deviceName = null,
    deviceOnline = true,
    isCloud = false,
    harness = null,
    harnessLabel = null,
    model = null,
    modelLabel = null,
    reasoning = null,
    branch = null,
    cwd = null,
    indicator = ChatIndicator.IDLE,
    hostIndicator = ChatIndicator.IDLE,
    workingSinceMs = null,
    lastActivityMs = 0L,
    timeLabel = "now",
    createdAtMs = 0L,
    unseen = false,
    archived = false,
    pinned = false,
    sectionId = null,
    pullRequest = null,
    sendState = null,
    parentChatId = null,
    roomGen = 0u,
)

/**
 * The "Session Prompts" page of the debug design gallery
 * (android-ui-parity #43): the five prompt launchers over fakes — the
 * nine buttons of the ticket's step 6, the fail-mode switch, and the last
 * result line the fake [RowActions] writes (`createSection(Infra 2)`,
 * `renamed: API`, `popped`, …).
 *
 * The gallery's `GalleryProviders` already wraps every section in
 * `ContextMenuHost { AlertHost { ToastHost { … } } }`, so this section
 * adds only the innermost [ActionSheetHost] — together exactly ticket
 * 35's mount order, and the alerts/toasts of the launchers draw over the
 * sheets. It also provides the [LoggingRowActions] as the local
 * [rememberSessionPrompts] reads.
 *
 * Fill layout (like every section that owns an in-window sheet host): the
 * `ActionSheetHost` needs the body's bounded height — the Scroll frame's
 * column would give it unbounded height and collapse the scrim and the
 * bottom anchoring — so the buttons scroll inside instead. (The ticket's
 * "default Scroll" suggestion cannot host the sheet; the registration
 * line therefore uses `GalleryLayout.Fill`.)
 *
 * Registered in `design/Gallery.kt` as
 * `GallerySection("Session Prompts", GalleryLayout.Fill) { SessionPromptsGallerySection() }`.
 */
@Composable
fun SessionPromptsGallerySection() {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    val toaster = LocalToaster.current
    val sheets = remember { ActionSheetHostState() }

    var result by remember { mutableStateOf("—") }
    var failMode by remember { mutableStateOf(false) }
    val actions = remember { LoggingRowActions(toaster, { failMode }) { result = it } }
    val c1 = remember { galleryRow("c1", "Fix flaky test") }

    ActionSheetHost(state = sheets) {
        CompositionLocalProvider(LocalRowActions provides actions) {
            Box(Modifier.fillMaxSize()) {
                GalleryBackdrop(Modifier.matchParentSize())
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                        )
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val prompts = rememberSessionPrompts()

                    PromptLabel(
                        "Session Prompts",
                        uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text),
                    )
                    PromptLabel(
                        "The five prompts over fakes: sections Backend, Design review and " +
                            "Infra; row \"Fix flaky test\".",
                        uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                    )
                    PromptLabel(
                        if (failMode) "Fail mode: on (section writes fail offline)" else "Fail mode: off",
                        uiStyle(
                            Face.SansMedium,
                            typeScale.fixed(14f),
                            if (failMode) palette.accent else palette.text,
                        ),
                        Modifier.clickable { failMode = !failMode },
                    )

                    val buttonStyle = uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.accent)
                    PromptLabel("Rename Session", buttonStyle, Modifier.clickable { prompts.renameSession(c1) })
                    PromptLabel("New Section", buttonStyle, Modifier.clickable { prompts.newSection() })
                    PromptLabel(
                        "Rename Section",
                        buttonStyle,
                        Modifier.clickable {
                            prompts.renameSection("backend", "Backend") { result = "renamed: $it" }
                        },
                    )
                    PromptLabel("Move to Section", buttonStyle, Modifier.clickable { prompts.moveToSection(c1) })
                    PromptLabel(
                        "Delete Section",
                        buttonStyle,
                        Modifier.clickable {
                            failMode = false
                            prompts.confirmDeleteSection("backend", "Backend") { result = "popped" }
                        },
                    )
                    PromptLabel(
                        "Delete fails (offline)",
                        buttonStyle,
                        Modifier.clickable {
                            failMode = true
                            prompts.confirmDeleteSection("backend", "Backend") { result = "popped" }
                        },
                    )

                    PromptLabel(
                        "Last result: $result",
                        uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                    )
                }
            }
        }
    }
}

/** A plain text row; with a `clickable` modifier attached it is a button (no Material controls). */
@Composable
private fun PromptLabel(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    BasicText(text, style = style, modifier = modifier.padding(vertical = 2.dp))
}

// ---------------------------------------------------------------------------
// Previews: the section needs the alert and toast hosts around it (the
// real gallery's GalleryProviders provides them); its own ActionSheetHost
// comes with the section.
// ---------------------------------------------------------------------------

@Preview(name = "Session prompts gallery light")
@Composable
private fun SessionPromptsGalleryLightPreview() {
    ZeronTheme(Appearance.Light) {
        AlertHost { ToastHost { SessionPromptsGallerySection() } }
    }
}

@Preview(name = "Session prompts gallery dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SessionPromptsGalleryDarkPreview() {
    ZeronTheme(Appearance.Dark) {
        AlertHost { ToastHost { SessionPromptsGallerySection() } }
    }
}
