package sh.zeron.android.design.lab

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.composeunstyled.DialogPanel
import com.composeunstyled.DragIndication
import com.composeunstyled.DropdownMenuPanel
import com.composeunstyled.ModalHost
import com.composeunstyled.RadioButton
import com.composeunstyled.Scrim
import com.composeunstyled.Sheet
import com.composeunstyled.Tab
import com.composeunstyled.TabList
import com.composeunstyled.TextInput
import com.composeunstyled.Thumb
import com.composeunstyled.Track
import com.composeunstyled.UnstyledDialog
import com.composeunstyled.UnstyledDropdownMenu
import com.composeunstyled.UnstyledDropdownMenuItem
import com.composeunstyled.UnstyledModalBottomSheet
import com.composeunstyled.UnstyledRadioGroup
import com.composeunstyled.UnstyledSwitch
import com.composeunstyled.UnstyledTabGroup
import com.composeunstyled.UnstyledTextField
import com.composeunstyled.rememberModalBottomSheetState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.zeron.android.core.Face
import sh.zeron.android.design.ActionSheetPanel
import sh.zeron.android.design.AlertButton
import sh.zeron.android.design.AlertButtonRole
import sh.zeron.android.design.AlertField
import sh.zeron.android.design.CapsuleShape
import sh.zeron.android.design.GalleryBackdrop
import sh.zeron.android.design.HarnessMark
import sh.zeron.android.design.Icon
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.ZeronAlert
import sh.zeron.android.design.ZeronAlertCard
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronSwitchTokens
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.glass.ActivitySpinner
import sh.zeron.android.design.glass.GlassSurface
import sh.zeron.android.design.glass.LocalHazeState
import sh.zeron.android.design.rememberSmoothRoundedShape
import sh.zeron.android.design.sheet.SmoothTopRoundedShape
import sh.zeron.android.design.uiStyle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState as rememberM3PullState
import com.composeunstyled.SheetDetent as UnstyledDetent

/**
 * Variant 2 of every row: our look (glass, palette, Geist, our cards) on
 * headless primitives — Compose Unstyled 2.10.1 where it has one, Compose
 * Foundation otherwise. Where the primitive leaves a contract item to the
 * caller, the glue here fills it (predictive back, hiding content behind,
 * labels); the catalog says which part is the library's and which is ours.
 *
 * The overlay stages carry a host switch: under `ModalHost` the primitive
 * renders in-window through a Portal (glass keeps its blur); without it,
 * Compose Unstyled opens a `ComponentDialog` window and the glass falls back
 * to the flat fill.
 */
@Composable
internal fun HeadlessVariant(component: LabComponent) {
    when (component) {
        LabComponent.Alert -> HeadlessAlert()
        LabComponent.ActionSheet -> HeadlessActionSheet()
        LabComponent.Menu -> HeadlessMenu()
        LabComponent.BottomSheet -> HeadlessBottomSheet()
        LabComponent.Switch -> HeadlessSwitch()
        LabComponent.Toast -> HeadlessToast()
        LabComponent.Segmented -> HeadlessSegmented()
        LabComponent.ProviderTabs -> HeadlessProviderTabs()
        LabComponent.PullToRefresh -> HeadlessPullToRefresh()
        LabComponent.Search -> HeadlessSearch()
    }
}

/**
 * The overlay stage: the host switch over the backdrop, the trigger
 * controls, and the content behind hidden from TalkBack while [overlayOpen]
 * (a Portal sibling has no window modality, so the screen reader would
 * otherwise walk straight out of the overlay).
 */
@Composable
private fun OverlayStage(
    overlayOpen: Boolean,
    controls: @Composable () -> Unit,
    overlay: @Composable () -> Unit,
) {
    var inWindow by remember { mutableStateOf(true) }
    val stage: @Composable () -> Unit = {
        HazeStage {
            Box(
                Modifier
                    .fillMaxSize()
                    .let { if (overlayOpen) it.semantics { hideFromAccessibility() } else it },
            ) {
                StageControls {
                    HostChoice(inWindow) { inWindow = it }
                    controls()
                }
            }
            overlay()
        }
    }
    // Keyed so flipping the switch re-creates the overlay under the new host.
    key(inWindow) {
        CompositionLocalProvider(LocalOverlayInWindow provides inWindow) {
            if (inWindow) ModalHost(Modifier.fillMaxSize()) { stage() } else stage()
        }
    }
}

/** Whether the overlay stage's primitives render in-window (under ModalHost). */
private val LocalOverlayInWindow = staticCompositionLocalOf { true }

/** Haze for glass inside a Modal: the stage's source in-window, the flat fallback in a separate window. */
@Composable
private fun OverlayHaze(content: @Composable () -> Unit) {
    val haze = if (LocalOverlayInWindow.current) LocalStageHaze.current else null
    CompositionLocalProvider(LocalHazeState provides haze, content = content)
}

// ---------------------------------------------------------------------------
// Alert: UnstyledDialog + DialogPanel, our ZeronAlertCard inside
// ---------------------------------------------------------------------------

@Composable
private fun HeadlessAlert() {
    var alert by remember { mutableStateOf<ZeronAlert?>(null) }
    var visible by remember { mutableStateOf(false) }
    OverlayStage(
        overlayOpen = visible,
        controls = {
            LabButton("Delete session?") {
                alert = ZeronAlert(
                    title = "Delete session?",
                    message = "This deletes the session on Studio.",
                    buttons = listOf(
                        AlertButton("Cancel", role = AlertButtonRole.Cancel),
                        AlertButton("Delete", role = AlertButtonRole.Destructive),
                    ),
                )
                visible = true
            }
            LabButton("Rename Session (field)") {
                alert = ZeronAlert(
                    title = "Rename Session",
                    field = AlertField(text = "Fix flaky test"),
                    buttons = listOf(
                        AlertButton("Cancel", role = AlertButtonRole.Cancel),
                        AlertButton("Rename", requiresText = true),
                    ),
                )
                visible = true
            }
        },
    ) {
        val current = alert ?: return@OverlayStage
        UnstyledDialog(
            visible = visible,
            onDismissRequest = { visible = false },
            overlay = { Scrim(scrimColor = Color.Black.copy(alpha = 0.2f)) },
        ) {
            var field by remember(current) {
                val text = current.field?.text.orEmpty()
                mutableStateOf(TextFieldValue(text, TextRange(text.length)))
            }
            var backProgress by remember { mutableFloatStateOf(0f) }
            // The library dismisses on Key.Back, which never arrives with
            // enableOnBackInvokedCallback; this is the contract's back.
            PredictiveBackHandler(enabled = visible) { events ->
                try {
                    events.collect { backProgress = it.progress }
                    visible = false
                } catch (e: CancellationException) {
                    backProgress = 0f
                    throw e
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                DialogPanel(
                    paneTitle = current.title,
                    modifier = Modifier.graphicsLayer {
                        val scale = 1f - 0.1f * backProgress
                        scaleX = scale
                        scaleY = scale
                    },
                ) {
                    OverlayHaze {
                        ZeronAlertCard(
                            alert = current,
                            fieldValue = field,
                            onFieldValueChange = { field = it },
                            onButton = { visible = false },
                            onImeDone = { visible = false },
                            hazeState = LocalHazeState.current,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Action sheet: UnstyledModalBottomSheet, our ActionSheetPanel inside
// ---------------------------------------------------------------------------

@Composable
private fun HeadlessActionSheet() {
    val state = rememberModalBottomSheetState(initialDetent = UnstyledDetent.Hidden)
    val open = state.targetDetent != UnstyledDetent.Hidden
    OverlayStage(
        overlayOpen = open,
        controls = { LabButton("Show action sheet") { state.targetDetent = UnstyledDetent.FullyExpanded } },
    ) {
        UnstyledModalBottomSheet(
            state = state,
            overlay = { Scrim(scrimColor = Color.Black.copy(alpha = 0.2f)) },
        ) {
            val backProgress = sheetPredictiveBack(enabled = open) { state.targetDetent = UnstyledDetent.Hidden }
            Sheet(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .graphicsLayer { translationY = size.height * 0.25f * backProgress() }
                    .semantics { paneTitle = SampleActionSheet.title.orEmpty() },
            ) {
                OverlayHaze {
                    ActionSheetPanel(
                        model = SampleActionSheet,
                        hazeState = LocalHazeState.current,
                        onAction = { state.targetDetent = UnstyledDetent.Hidden },
                        onCancel = { state.targetDetent = UnstyledDetent.Hidden },
                    )
                }
            }
        }
    }
}

/**
 * Our predictive back for a sheet: composed inside the sheet, so it sits
 * above the library's plain BackHandler (the most recent callback wins).
 * Returns the gesture progress for the caller's slide.
 */
@Composable
private fun sheetPredictiveBack(enabled: Boolean, onBack: () -> Unit): () -> Float {
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(enabled) { if (enabled) progress = 0f }
    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { progress = it.progress }
            onBack()
        } catch (e: CancellationException) {
            progress = 0f
            throw e
        }
    }
    return { progress }
}

// ---------------------------------------------------------------------------
// Menu: UnstyledDropdownMenu, the panel wrapped in our GlassSurface
// ---------------------------------------------------------------------------

@Composable
private fun HeadlessMenu() {
    var expanded by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(false) }
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    OverlayStage(
        overlayOpen = false, // the menu restores focus itself; content behind stays readable like a popup's
        controls = {
            UnstyledDropdownMenu(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                anchor = {
                    LabMenuTrigger(
                        "Session ⋯",
                        Modifier.clickable(role = Role.Button, onClickLabel = "Open menu") { expanded = true },
                    )
                },
                panel = {
                    // Items must be direct children of DropdownMenuPanel (its
                    // layout reads their focus targets), so the glass wraps
                    // the panel instead of sitting inside it.
                    OverlayHaze {
                        GlassSurface(
                            modifier = Modifier.width(250.dp).padding(top = 6.dp),
                            shape = rememberSmoothRoundedShape(14.dp),
                            animateVisibility = false,
                        ) {
                            DropdownMenuPanel(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                listOf(
                                    (if (pinned) "Unpin" else "Pin") to { pinned = !pinned },
                                    "Copy Transcript" to {},
                                    "Archive" to {},
                                ).forEachIndexed { index, (title, action) ->
                                    UnstyledDropdownMenuItem(
                                        onClick = action,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .semantics {
                                                // The primitive's item is a bare clickable.
                                                role = Role.Button
                                                if (index == 0) selected = pinned
                                            },
                                    ) {
                                        BasicText(
                                            title,
                                            style = uiStyle(
                                                Face.Sans,
                                                typeScale.scaled(17f),
                                                if (title == "Archive") palette.danger else palette.text,
                                            ),
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
            )
            LabCaption("Keyboard: focus the trigger, then ↓ / Enter opens; arrows move; Tab leaves.")
        },
        overlay = { },
    )
}

// ---------------------------------------------------------------------------
// Bottom sheet: UnstyledModalBottomSheet with half/full detents
// ---------------------------------------------------------------------------

private val HalfDetent = UnstyledDetent("half") { containerHeight, _ -> containerHeight * 0.5f }

@Composable
private fun HeadlessBottomSheet() {
    val state = rememberModalBottomSheetState(
        initialDetent = UnstyledDetent.Hidden,
        detents = listOf(UnstyledDetent.Hidden, HalfDetent, UnstyledDetent.FullyExpanded),
    )
    val open = state.targetDetent != UnstyledDetent.Hidden
    val palette = ZeronTheme.palette
    OverlayStage(
        overlayOpen = open,
        controls = { LabButton("Show sheet") { state.targetDetent = HalfDetent } },
    ) {
        UnstyledModalBottomSheet(
            state = state,
            overlay = { Scrim(scrimColor = Color.Black.copy(alpha = 0.2f)) },
        ) {
            val backProgress = sheetPredictiveBack(enabled = open) { state.targetDetent = UnstyledDetent.Hidden }
            Sheet(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.94f)
                    .graphicsLayer { translationY = size.height * 0.25f * backProgress() }
                    .semantics { paneTitle = "Tool detail" },
            ) {
                OverlayHaze {
                    GlassSurface(
                        modifier = Modifier.fillMaxSize(),
                        shape = SmoothTopRoundedShape(26.dp),
                        animateVisibility = false,
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            DragIndication(
                                Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(top = 6.dp)
                                    .size(width = 36.dp, height = 5.dp)
                                    .clip(CapsuleShape)
                                    .background(palette.tertiary)
                                    .semantics { contentDescription = "Sheet grabber" },
                            )
                            SheetSampleContent(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Switch: UnstyledSwitch with our capsule track and thumb
// ---------------------------------------------------------------------------

@Composable
private fun HeadlessSwitch() {
    val palette = ZeronTheme.palette
    SwitchRows { checked, onChange, enabled ->
        UnstyledSwitch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            accessibilityLabel = null, // the row's text is beside it; see the catalog
        ) {
            Track(
                Modifier
                    .size(width = ZeronSwitchTokens.Width, height = ZeronSwitchTokens.Height)
                    .clip(CapsuleShape)
                    .background(if (checked) palette.accent else ZeronSwitchTokens.OffTrack)
                    .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
                    .padding(2.dp),
            ) {
                Thumb(
                    Modifier
                        .size(width = ZeronSwitchTokens.ThumbWidth, height = ZeronSwitchTokens.ThumbHeight)
                        .clip(CapsuleShape)
                        .background(Color.White),
                    animationSpec = spring(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Toast: Foundation only — our glass capsule, a polite live region and the
// recommended timeout
// ---------------------------------------------------------------------------

@Composable
private fun HeadlessToast() {
    var shown by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(false) }
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(shown) {
        if (shown == 0) return@LaunchedEffect
        visible = true
        val hold = accessibility?.calculateRecommendedTimeoutMillis(
            originalTimeoutMillis = 4000L,
            containsIcons = false,
            containsText = true,
            containsControls = true,
        ) ?: 4000L
        delay(hold)
        visible = false
    }
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    HazeStage {
        StageControls {
            LabButton("Show toast") { shown++ }
            LabTextField(placeholder = "Raise the keyboard, then show it")
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            GlassSurface(shape = CapsuleShape, animateVisibility = false) {
                Row(
                    Modifier.height(48.dp).padding(start = 18.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        "Session archived",
                        style = uiStyle(Face.SansMedium, typeScale.scaled(15f), palette.text),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicText(
                        "Undo",
                        style = uiStyle(Face.SansSemibold, typeScale.scaled(15f), palette.accent),
                        modifier = Modifier
                            .clip(CapsuleShape)
                            .clickable(role = Role.Button) { visible = false }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Segmented control: UnstyledRadioGroup, our sliding thumb
// ---------------------------------------------------------------------------

@Composable
private fun HeadlessSegmented() {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    var selected by remember { mutableStateOf(SchemeOptions.first()) }
    Column(Modifier.fillMaxSize().background(palette.background).padding(16.dp)) {
        UnstyledRadioGroup(
            value = selected,
            onValueChange = { selected = it },
            accessibilityLabel = "Color scheme",
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(CapsuleShape)
                    .background(palette.controlFill)
                    .padding(2.dp),
            ) {
                val segment = maxWidth / SchemeOptions.size
                val thumbX by animateDpAsState(segment * SchemeOptions.indexOf(selected), label = "thumb")
                Box(
                    Modifier
                        .offset(x = thumbX)
                        .width(segment)
                        .fillMaxHeight()
                        .clip(CapsuleShape)
                        .background(if (ZeronTheme.isDark) Color.White.copy(alpha = 0.14f) else Color.White),
                )
                Row(Modifier.fillMaxSize()) {
                    SchemeOptions.forEach { option ->
                        RadioButton(
                            value = option,
                            modifier = Modifier.weight(1f).fillMaxHeight().clip(CapsuleShape),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                BasicText(
                                    option,
                                    style = uiStyle(Face.SansMedium, typeScale.fixed(13f), palette.text),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Provider tab switcher: UnstyledTabGroup, our capsule thumb on the pager
// ---------------------------------------------------------------------------

@Composable
private fun HeadlessProviderTabs() {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    val pager = rememberPagerState { ProviderOptions.size }
    val scope = rememberCoroutineScope()
    val keys = ProviderOptions.map { it.first }
    val thumb = if (ZeronTheme.isDark) Color.White.copy(alpha = 0.14f) else Color.White
    Column(Modifier.fillMaxSize().background(palette.background)) {
        UnstyledTabGroup(
            selectedTab = keys[pager.currentPage],
            onSelectedTabChange = { key -> scope.launch { pager.animateScrollToPage(keys.indexOf(key)) } },
            tabs = keys,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BoxWithConstraints(
                Modifier
                    .padding(start = 20.dp, end = 20.dp, top = 14.dp)
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(CapsuleShape)
                    .background(palette.controlFill)
                    .padding(3.dp),
            ) {
                // Equal segments so the thumb can follow the pager's
                // fractional position; five providers fit a phone width.
                val segment = maxWidth / keys.size
                val position = pager.currentPage + pager.currentPageOffsetFraction
                Box(
                    Modifier
                        .offset(x = segment * position)
                        .width(segment)
                        .fillMaxHeight()
                        .clip(CapsuleShape)
                        .background(thumb),
                )
                TabList(Modifier.fillMaxSize().selectableGroup()) {
                    Row(Modifier.fillMaxSize()) {
                        ProviderOptions.forEach { (harness, label) ->
                            Tab(
                                key = harness,
                                modifier = Modifier.weight(1f).fillMaxHeight().clip(CapsuleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                val ink = if (selected) palette.text else palette.secondary
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    HarnessMark(harness, 13.dp, ink)
                                    Spacer(Modifier.width(4.dp))
                                    BasicText(label, style = uiStyle(Face.SansMedium, typeScale.fixed(13f), ink), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page -> ProviderPage(ProviderOptions[page].second) }
    }
}

// ---------------------------------------------------------------------------
// Pull to refresh: Material's behaviour-only modifier, our spinner and
// semantics (neither Foundation nor Compose Unstyled has a headless one)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeadlessPullToRefresh() {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    val state = rememberM3PullState()
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val refresh: () -> Unit = {
        if (!refreshing) {
            refreshing = true
            scope.launch {
                delay(RefreshMillis)
                refreshing = false
            }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .pullToRefresh(isRefreshing = refreshing, state = state, onRefresh = refresh),
    ) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .semantics { customActions = listOf(CustomAccessibilityAction("Refresh") { refresh(); true }) },
        ) {
            items(RefreshRows) { index ->
                Box(Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp), contentAlignment = Alignment.CenterStart) {
                    BasicText("Session ${index + 1}", style = uiStyle(Face.Sans, typeScale.fixed(17f), palette.text))
                }
            }
        }
        // Our indicator: the iOS cell spinner, fading in with the pull.
        val shown = refreshing || state.distanceFraction > 0f
        if (shown) {
            ActivitySpinner(
                ink = palette.secondary,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .size(21.dp)
                    .graphicsLayer {
                        alpha = if (refreshing) 1f else state.distanceFraction.coerceIn(0f, 1f)
                        translationY = 40.dp.toPx() * state.distanceFraction.coerceIn(0f, 1.5f)
                    },
            )
        }
        // The announcement TalkBack reads (the spinner itself is decorative).
        LabCaption(
            if (refreshing) "Refreshing…" else "Pull down, or use the list's Refresh action",
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

// ---------------------------------------------------------------------------
// Search field: UnstyledTextField in our capsule
// ---------------------------------------------------------------------------

@Composable
private fun HeadlessSearch() {
    val palette = ZeronTheme.palette
    val typeScale = LocalTypeScale.current
    val state = rememberTextFieldState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val query = state.text.toString()
    val hits = SearchCorpus.count { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
    HazeStage {
        Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
            GlassSurface(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(40.dp),
                shape = CapsuleShape,
                animateVisibility = false,
            ) {
                Row(Modifier.fillMaxSize().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ZeronIcons.Magnifyingglass, palette.secondary, 15f)
                    Spacer(Modifier.width(6.dp))
                    UnstyledTextField(
                        state = state,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { event ->
                                // Escape: clear, then blur (UISearchTextField's cancel).
                                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                    if (state.text.isNotEmpty()) state.clearText() else focus.clearFocus()
                                    true
                                } else {
                                    false
                                }
                            },
                        accessibilityLabel = "Search",
                        cursorBrush = SolidColor(palette.accent),
                        textStyle = uiStyle(Face.Sans, typeScale.scaled(17f), palette.text),
                        lineLimits = TextFieldLineLimits.SingleLine,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                        onKeyboardAction = {
                            keyboard?.hide()
                            focus.clearFocus()
                        },
                    ) {
                        TextInput(placeholder = {
                            BasicText("Search", style = uiStyle(Face.Sans, typeScale.scaled(17f), palette.tertiary))
                        })
                    }
                    if (query.isNotEmpty()) {
                        Box(
                            Modifier
                                .size(width = 44.dp, height = 40.dp)
                                .clickable(role = Role.Button, onClickLabel = null) {
                                    state.clearText()
                                    focusRequester.requestFocus()
                                }
                                .semantics { contentDescription = "Clear text" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(ZeronIcons.XmarkCircleFill, palette.tertiary, 17f)
                        }
                    }
                }
            }
            BasicText(
                if (query.isBlank()) "" else if (hits == 1) "1 result" else "$hits results",
                style = uiStyle(Face.Sans, typeScale.fixed(12f), palette.secondary),
                modifier = Modifier
                    .padding(start = 28.dp, top = 6.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            SearchResults(query, Modifier.weight(1f))
        }
    }
}
