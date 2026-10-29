package sh.zeron.android.design.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.zeron.android.core.Face
import sh.zeron.android.design.ActionSheetAction
import sh.zeron.android.design.ActionSheetHost
import sh.zeron.android.design.ActionSheetModel
import sh.zeron.android.design.ActionSheetStyle
import sh.zeron.android.design.AlertButton
import sh.zeron.android.design.AlertButtonRole
import sh.zeron.android.design.AlertField
import sh.zeron.android.design.CapsuleShape
import sh.zeron.android.design.GalleryBackdrop
import sh.zeron.android.design.HarnessMark
import sh.zeron.android.design.LocalActionSheet
import sh.zeron.android.design.LocalAlerts
import sh.zeron.android.design.LocalToaster
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.ZeronAlert
import sh.zeron.android.design.ZeronSwitch
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.glass.GlassMenuHost
import sh.zeron.android.design.glass.Menu
import sh.zeron.android.design.glass.MenuItem
import sh.zeron.android.design.glass.MenuSection
import sh.zeron.android.design.glass.glassMenu
import sh.zeron.android.design.rememberPullToRefreshState
import sh.zeron.android.design.sheet.GlassSheet
import sh.zeron.android.design.sheet.SheetDetent
import sh.zeron.android.design.sheet.SheetHeader
import sh.zeron.android.design.sheet.SheetTrailing
import sh.zeron.android.design.sheet.rememberGlassSheetState
import sh.zeron.android.design.uiStyle
import sh.zeron.android.shell.LargeTitleScaffold
import sh.zeron.android.shell.tablet.SidebarSearchField
import sh.zeron.android.shell.tablet.rememberSidebarState

/**
 * Variant 1 of every row: today's Roboco component, called exactly as the
 * app calls it. Where the app has no reusable component (segmented control)
 * or only a private one (the model picker's provider switcher), the stage
 * shows the Roboco idiom / a faithful replica and says so in the catalog.
 */
@Composable
internal fun CustomVariant(component: LabComponent) {
    when (component) {
        LabComponent.Alert -> CustomAlert()
        LabComponent.ActionSheet -> CustomActionSheet()
        LabComponent.Menu -> CustomMenu()
        LabComponent.BottomSheet -> CustomBottomSheet()
        LabComponent.Switch -> CustomSwitch()
        LabComponent.Toast -> CustomToast()
        LabComponent.Segmented -> CustomSegmented()
        LabComponent.ProviderTabs -> CustomProviderTabs()
        LabComponent.PullToRefresh -> CustomPullToRefresh()
        LabComponent.Search -> CustomSearch()
    }
}

@Composable
private fun CustomAlert() {
    // GalleryProviders mounts the real AlertHost around the whole lab.
    val alerts = LocalAlerts.current
    HazeStage {
        StageControls {
            LabButton("Delete session?") {
                alerts.show(
                    ZeronAlert(
                        title = "Delete session?",
                        message = "This deletes the session on Studio.",
                        buttons = listOf(
                            AlertButton("Cancel", role = AlertButtonRole.Cancel),
                            AlertButton("Delete", role = AlertButtonRole.Destructive),
                        ),
                    ),
                )
            }
            LabButton("Rename Session (field)") {
                alerts.show(
                    ZeronAlert(
                        title = "Rename Session",
                        field = AlertField(text = "Fix flaky test", capitalization = KeyboardCapitalization.Sentences),
                        buttons = listOf(
                            AlertButton("Cancel", role = AlertButtonRole.Cancel),
                            AlertButton("Rename", requiresText = true),
                        ),
                    ),
                )
            }
        }
    }
}

@Composable
private fun CustomActionSheet() {
    ActionSheetHost {
        val sheets = LocalActionSheet.current
        HazeStage {
            StageControls {
                LabButton("Show action sheet") { sheets.show(SampleActionSheet) }
            }
        }
    }
}

internal val SampleActionSheet = ActionSheetModel(
    title = "Wallpaper Effect",
    actions = listOf(
        ActionSheetAction("None", checked = true) {},
        ActionSheetAction("Blur") {},
        ActionSheetAction("Dim") {},
        ActionSheetAction("Remove Wallpaper", style = ActionSheetStyle.Destructive) {},
    ),
)

@Composable
private fun CustomMenu() {
    // GlassMenuHost makes its content the window's haze source, so the
    // backdrop goes inside it rather than in a HazeStage.
    GlassMenuHost {
        Box(Modifier.fillMaxSize()) {
            GalleryBackdrop(Modifier.matchParentSize())
            StageControls {
                var pinned by remember { mutableStateOf(false) }
                LabMenuTrigger(
                    "Session ⋯",
                    Modifier.glassMenu {
                        Menu(
                            children = listOf(
                                MenuItem(if (pinned) "Unpin" else "Pin") { pinned = !pinned },
                                MenuItem("Copy Transcript") {},
                                MenuSection(
                                    inline = true,
                                    children = listOf(MenuItem("Archive", destructive = true) {}),
                                ),
                            ),
                        )
                    },
                )
                LabCaption("The trigger is Modifier.glassMenu (detectTapGestures): try opening it with a keyboard.")
            }
        }
    }
}

/** The menu trigger look shared by the three menu variants (no semantics of its own). */
@Composable
internal fun LabMenuTrigger(text: String, modifier: Modifier = Modifier) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    Box(
        modifier
            .height(40.dp)
            .clip(CapsuleShape)
            .background(palette.text)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = uiStyle(Face.SansMedium, typeScale.fixed(15f), palette.background), maxLines = 1)
    }
}

@Composable
private fun CustomBottomSheet() {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        GalleryBackdrop(Modifier.matchParentSize())
        StageControls { LabButton("Show sheet") { open = true } }
        if (open) {
            GlassSheet(
                onDismiss = { open = false },
                state = rememberGlassSheetState(listOf(SheetDetent.Medium, SheetDetent.Large)),
                grabber = true,
                title = "Tool detail",
                header = { SheetHeader("Tool detail", trailing = SheetTrailing.Done) },
            ) { bottomInset ->
                SheetSampleContent(Modifier.padding(bottom = bottomInset))
            }
        }
    }
}

/** A field (for the IME) and enough rows to scroll, shared by the three sheet variants. */
@Composable
internal fun SheetSampleContent(modifier: Modifier = Modifier) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LabTextField(placeholder = "Type to raise the keyboard")
        repeat(24) { i ->
            BasicText(
                "Output line ${i + 1}",
                style = uiStyle(Face.Mono, typeScale.fixed(13f), palette.text),
            )
        }
    }
}

@Composable
private fun CustomSwitch() {
    SwitchRows { checked, onChange, enabled ->
        ZeronSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * Two Settings-style rows, the second disabled: label at the start, the
 * variant's switch at the end. The row itself carries no semantics, so each
 * switch shows how it labels itself (custom: it doesn't).
 */
@Composable
internal fun SwitchRows(switch: @Composable (checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean) -> Unit) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    var math by remember { mutableStateOf(true) }
    var grouped by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(palette.background).padding(16.dp)) {
        listOf(
            Triple("Render Math", math, true),
            Triple("Group by Project (disabled)", grouped, false),
        ).forEachIndexed { index, (label, checked, enabled) ->
            Row(
                Modifier.fillMaxWidth().height(52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    label,
                    style = uiStyle(Face.Sans, typeScale.scaled(17f), palette.text),
                    modifier = Modifier.weight(1f),
                )
                val onChange: (Boolean) -> Unit = { value ->
                    if (index == 0) {
                        math = value
                    } else {
                        grouped = value
                    }
                }
                switch(checked, onChange, enabled)
            }
        }
    }
}

@Composable
private fun CustomToast() {
    // GalleryProviders mounts the real ToastHost around the whole lab.
    val toaster = LocalToaster.current
    HazeStage {
        StageControls {
            LabButton("Show toast") { toaster.show("Session archived", "Undo") {} }
            LabTextField(placeholder = "Raise the keyboard, then show it")
        }
    }
}

@Composable
private fun CustomSegmented() {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    var selected by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(palette.background).padding(16.dp)) {
        LabCaption("Roboco has no segmented control; this is its idiom (the gallery's capsule segments).")
        Spacer(Modifier.height(16.dp))
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SchemeOptions.forEachIndexed { index, label ->
                val on = index == selected
                Box(
                    Modifier
                        .height(30.dp)
                        .clip(CapsuleShape)
                        .background(if (on) palette.text else palette.controlFill)
                        .selectable(
                            selected = on,
                            interactionSource = null,
                            indication = null,
                            role = Role.RadioButton,
                        ) { selected = index }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        label,
                        style = uiStyle(Face.SansMedium, typeScale.fixed(13f), if (on) palette.background else palette.text),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

internal val SchemeOptions = listOf("System", "Light", "Dark")

/** The model picker's providers: harness id to switcher label. */
internal val ProviderOptions = listOf(
    "claude-code" to "Claude",
    "codex" to "Codex",
    "cursor" to "Cursor",
    "opencode" to "OpenCode",
    "grok" to "Grok",
)

/**
 * A replica of `ModelPicker.kt`'s private `ProviderSwitcher` (same track,
 * thumb, mark, type and semantics) driving a pager, as the picker does.
 */
@Composable
private fun CustomProviderTabs() {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    val pager = rememberPagerState { ProviderOptions.size }
    val scope = rememberCoroutineScope()
    val thumb = if (ZeronTheme.isDark) Color.White.copy(alpha = 0.14f) else Color.White
    Column(Modifier.fillMaxSize().background(palette.background)) {
        Row(
            Modifier
                .padding(start = 20.dp, end = 20.dp, top = 14.dp)
                .fillMaxWidth()
                .height(40.dp)
                .clip(CapsuleShape)
                .background(palette.controlFill)
                .horizontalScroll(rememberScrollState())
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderOptions.forEachIndexed { ix, (harness, label) ->
                val chosen = ix == pager.currentPage
                Row(
                    Modifier
                        .height(34.dp)
                        .clip(CapsuleShape)
                        .background(if (chosen) thumb else Color.Transparent)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { scope.launch { pager.animateScrollToPage(ix) } }
                        .padding(horizontal = 12.dp)
                        .semantics { this.selected = chosen },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HarnessMark(harness, 15.dp, if (chosen) palette.text else palette.secondary)
                    Spacer(Modifier.width(6.dp))
                    BasicText(
                        label,
                        style = uiStyle(Face.SansMedium, typeScale.fixed(14f), if (chosen) palette.text else palette.secondary),
                        maxLines = 1,
                    )
                }
            }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page -> ProviderPage(ProviderOptions[page].second) }
    }
}

/** One provider's page, shared by the three switcher variants. */
@Composable
internal fun ProviderPage(label: String) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(5) { i ->
            BasicText("$label model ${i + 1}", style = uiStyle(Face.Sans, typeScale.scaled(16f), palette.text))
        }
    }
}

@Composable
private fun CustomPullToRefresh() {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    val state = rememberPullToRefreshState(onRefresh = { delay(RefreshMillis) })
    LargeTitleScaffold(
        title = "Sessions",
        topInset = 0.dp,
        showBack = false,
        onBack = {},
        pullToRefresh = state,
    ) {
        items(RefreshRows) { index ->
            Box(Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp), contentAlignment = Alignment.CenterStart) {
                BasicText("Session ${index + 1}", style = uiStyle(Face.Sans, typeScale.fixed(17f), palette.text))
            }
        }
    }
}

internal const val RefreshMillis = 1500L
internal const val RefreshRows = 20

@Composable
private fun CustomSearch() {
    val state = rememberSidebarState()
    HazeStage {
        Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
            SidebarSearchField(state)
            SearchResults(state.query, Modifier.weight(1f))
        }
    }
}

internal val SearchCorpus = listOf(
    "Fix flaky test", "Port the composer", "Release notes", "Refactor sheet detents",
    "Search field parity", "Toast timing", "Predictive back audit", "Switch geometry",
)

/** The filtered corpus on an opaque card, shared by the three search variants. */
@Composable
internal fun SearchResults(query: String, modifier: Modifier = Modifier) {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    val hits = SearchCorpus.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
    Column(
        modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hits.isEmpty()) {
            BasicText("No results", style = uiStyle(Face.Sans, typeScale.scaled(15f), palette.secondary))
        }
        hits.forEach { BasicText(it, style = uiStyle(Face.Sans, typeScale.scaled(16f), palette.text)) }
    }
}
