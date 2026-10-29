package sh.zeron.android.newsession

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import sh.zeron.android.ZeronApplication
import sh.zeron.android.core.Face
import sh.zeron.android.design.GroupedAccessory
import sh.zeron.android.design.GroupedPosition
import sh.zeron.android.design.GroupedRow
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.SymbolWeight
import sh.zeron.android.design.groupedSection
import sh.zeron.android.design.uiStyle
import sh.zeron.android.design.glass.GlassCircleButton
import sh.zeron.android.design.glass.GlassTextCapsule
import sh.zeron.android.design.glass.Menu
import sh.zeron.android.design.glass.MenuIcon
import sh.zeron.android.design.glass.MenuItem
import sh.zeron.android.design.glass.hasContent
import sh.zeron.android.design.glass.rememberGlassMenuState
import sh.zeron.android.shell.PageSheetScope
import sh.zeron.android.shell.SheetHostState
import sh.zeron.android.shell.SheetStyle

/**
 * The New Project screen (android-ui-parity #110, #27): the port of
 * `ios:Threads/NewProjectViewController.swift` — the page sheet's nav bar
 * (✕ / folder title / device menu), the inset-grouped folder list and the
 * bottom bar (status / Use), over the [FolderBrowserController]'s load
 * guard and the [FolderBrowserActions]' create. Nothing iOS lacks: no
 * locations rail, no folder search or typed path, no New Folder.
 *
 * No Material anywhere: the nav bar is a custom [Layout] (its title
 * centring is UIKit nav-bar behaviour — see [navTitleX]), the rows are
 * ticket 70's [GroupedRow]s, the buttons are ticket 08's glass items and
 * the device menu is ticket 15's model.
 */

/** The screen's geometry tokens (dp); everything not listed is ticket 70's. */
object FolderBrowserTokens {
    /** The nav bar's height (ticket 17's `GlassSheetDefaults.HeaderHeight`). */
    val NavHeight = 52.dp

    /** The ✕ at the start; the device capsule at the end (16 from each screen edge). */
    val SideInset = 16.dp

    /** The title region's clearance from the ✕ and the capsule. */
    val ItemGap = 8.dp

    /** The capsule's max width: 0.45 × the sheet width (system rendering, starred; freeze in the side-by-side). */
    const val CapsuleMaxFraction = 0.45f

    /** UIKit's inset-grouped spacing above a header-less section (starred; freeze in the side-by-side). */
    val ListTop = 35.dp

    /** `contentInset.bottom = 110` on iOS; 160 here for the taller glass capsule, plus the navigation-bar inset. */
    val ListBottom = 160.dp
}

/**
 * The New Project page (`ios:Threads/NewProjectViewController.swift`):
 * the nav bar, then the folder list filling the rest of the page down to
 * the physical bottom, with the bottom bar (status / Use) overlaid on it.
 * The create callback and the dismissal live in [actions]; while a write
 * runs, [sheet]'s `dismissBlocked` keeps it up.
 */
@Composable
fun NewProjectScreen(
    sheet: PageSheetScope,
    controller: FolderBrowserController,
    actions: FolderBrowserActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .semantics { paneTitle = FolderBrowserCopy.DefaultTitle },
    ) {
        FolderBrowserNavBar(
            controller,
            enabled = !controller.submitting,
            onClose = { sheet.dismiss(animated = true) },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            FolderList(controller)
            // The bottom stack, overlaid bottom-centre on the list (no
            // background — rows scroll underneath; the 160 dp bottom
            // content padding keeps the last row reachable).
            FolderBrowserBottomBar(
                status = controller.status,
                useTitle = actions.useTitle,
                useEnabled = actions.canAct || actions.creatingProject,
                useBusy = actions.creatingProject,
                onUse = actions::createProject,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
    // A write in flight locks the sheet: no drag, no back, no a11y
    // dismiss — programmatic dismissal (the success path) still works.
    LaunchedEffect(controller.submitting) {
        sheet.dismissBlocked = controller.submitting
    }
}

// ---------------------------------------------------------------------------
// The nav bar
// ---------------------------------------------------------------------------

/**
 * The 52 dp nav bar: a glass ✕ at the start, the folder name centred (see
 * [navTitleX]), a glass text capsule with the selected device at the end
 * (`NewProjectViewController.swift:30-96`). Opaque — the list starts below
 * it (ticket 17's ADAPT). While a write runs (111's `submitting`) both
 * ends are disabled (`setSubmitting`, `pr685:…:230-237`). The capsule opens
 * the device menu on tap (`rememberGlassMenuState().open(...)` — ticket
 * 15's rule for elements that already have their own `clickable`), building
 * the menu at tap time so it always sees the live host list; with no
 * hosts the [Menu.hasContent] guard opens nothing.
 */
@Composable
private fun FolderBrowserNavBar(controller: FolderBrowserController, enabled: Boolean, onClose: () -> Unit) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    Layout(
        content = {
            // 0: the ✕. 1: the title. 2: the capsule — the measure block
            // positions them by index.
            GlassCircleButton(
                icon = ZeronIcons.Xmark,
                contentDescription = "Close",
                onClick = onClose,
                size = 44.dp,
                iconPt = 17f,
                weight = SymbolWeight.Medium,
                enabled = enabled,
                modifier = Modifier.testTag("sheet-close"),
            )
            BasicText(
                controller.title,
                style = uiStyle(Face.SansSemibold, ts.fixed(17f), palette.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .semantics { heading() }
                    .testTag("folder-title"),
            )
            val menuState = rememberGlassMenuState()
            var capsuleBounds by remember { mutableStateOf(Rect.Zero) }
            GlassTextCapsule(
                text = controller.deviceTitle,
                onClick = {
                    val menu = deviceMenu(controller)
                    if (menu.hasContent()) menuState.open(capsuleBounds, menu)
                },
                enabled = enabled,
                modifier = Modifier
                    .onGloballyPositioned { capsuleBounds = it.boundsInWindow() }
                    .testTag("folder-device"),
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(FolderBrowserTokens.NavHeight),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val navHeight = FolderBrowserTokens.NavHeight.roundToPx()
        val inset = FolderBrowserTokens.SideInset.roundToPx()
        val gap = FolderBrowserTokens.ItemGap.roundToPx()

        val close = measurables[0].measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxHeight = navHeight),
        )
        val capsuleMax = (FolderBrowserTokens.CapsuleMaxFraction * width).roundToInt()
        val capsule = measurables[2].measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxWidth = capsuleMax, maxHeight = navHeight),
        )
        val regionStart = inset + close.width + gap
        val regionEnd = width - inset - capsule.width - gap
        val titleMax = (regionEnd - regionStart).coerceAtLeast(0)
        val title = measurables[1].measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxWidth = titleMax, maxHeight = navHeight),
        )
        val titleX = navTitleX(width, title.width, regionStart, regionEnd)
        layout(width, navHeight) {
            // placeRelative mirrors every x in RTL; all three centre vertically.
            close.placeRelative(inset, (navHeight - close.height) / 2)
            title.placeRelative(titleX, (navHeight - title.height) / 2)
            capsule.placeRelative(width - inset - capsule.width, (navHeight - capsule.height) / 2)
        }
    }
}

/**
 * The title's x: centred on the **sheet** when it fits, otherwise slid
 * just enough to clear the button it would overlap (UIKit nav-bar
 * behaviour). All values in px; pure and unit-tested.
 */
internal fun navTitleX(width: Int, titleWidth: Int, regionStart: Int, regionEnd: Int): Int =
    ((width - titleWidth) / 2).coerceIn(regionStart, maxOf(regionStart, regionEnd - titleWidth))

/**
 * The device menu (ticket 15's model), built at tap time from the live
 * host list (`UIMenu(title: "Device", …)`): one row per host with its
 * Online/Offline subtitle and the `desktopcomputer` icon, no checkmark
 * (iOS marks no current host). Picking a row — including the current one
 * — re-titles the capsule and reloads at home.
 */
internal fun deviceMenu(controller: FolderBrowserController): Menu = Menu(
    title = FolderBrowserCopy.Device,
    children = controller.devices.map { host ->
        MenuItem(
            title = host.name,
            subtitle = if (host.online) FolderBrowserCopy.Online else FolderBrowserCopy.Offline,
            icon = MenuIcon.Symbol(ZeronIcons.Desktopcomputer),
            testTag = "folder-device-row",
            action = { controller.selectDevice(host.id) },
        )
    },
)

// ---------------------------------------------------------------------------
// The folder list
// ---------------------------------------------------------------------------

/**
 * The inset-grouped card of folders (ticket 70's geometry, unchanged):
 * one section, no header, a `Parent Folder` row plus one row per
 * directory, 35 dp of top padding and 160 dp + the navigation-bar inset
 * of bottom padding so the last rows scroll clear of the bottom bar.
 *
 * The section key carries the listing serial, so every new listing gives
 * every row a new key: the LazyColumn keeps the old first-visible index
 * and offset, clamped to the new item count — UIKit's content-offset
 * behaviour across `apply(animatingDifferences: false)`. Never
 * `scrollToItem(0)`; no `Modifier.animateItem()`.
 */
@Composable
private fun FolderList(controller: FolderBrowserController) {
    val listState = rememberLazyListState()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("folder-list"),
        contentPadding = PaddingValues(
            top = FolderBrowserTokens.ListTop,
            bottom = FolderBrowserTokens.ListBottom + navBottom,
        ),
    ) {
        val serial = controller.listingSerial
        groupedSection(
            key = "folders-$serial",
            header = null,
            items = controller.rows,
            itemKey = { it.key },
        ) { row, position ->
            FolderRowView(row, position) { controller.tap(row) }
        }
    }
}

/**
 * One row: the parent (Geist Regular `bodyScaled(17)`, `accent` icon, no
 * accessory) or a folder (ticket 70's default Geist Medium `fixed(16)`
 * title, disclosure chevron; `folder.badge.gearshape` in `accent` for
 * repos, `folder` in `secondary` otherwise). The click is passed always —
 * the controller ignores the taps it must ignore, and the row still
 * shows its press highlight (iOS still selects and deselects the cell).
 */
@Composable
private fun FolderRowView(row: FolderRow, position: GroupedPosition, onClick: () -> Unit) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    when (row) {
        FolderRow.Parent -> GroupedRow(
            title = FolderBrowserCopy.ParentFolder,
            position = position,
            icon = ZeronIcons.ArrowTurnLeftUp,
            iconTint = palette.accent,
            accessories = emptyList(),
            titleStyle = uiStyle(Face.Sans, ts.bodyScaled(17f), palette.text),
            testTag = "folder-parent",
            onClick = onClick,
        )
        is FolderRow.Folder -> GroupedRow(
            title = row.name,
            position = position,
            icon = if (row.isRepo) ZeronIcons.FolderBadgeGearshape else ZeronIcons.Folder,
            iconTint = if (row.isRepo) palette.accent else palette.secondary,
            accessories = listOf(GroupedAccessory.Disclosure),
            testTag = "folder-row",
            onClick = onClick,
        )
    }
}

// ---------------------------------------------------------------------------
// The sheet body and the presentation entry points
// ---------------------------------------------------------------------------

/** From the New Session sheet's project-chip menu (ticket 113, phone). */
fun PageSheetScope.presentNewProject(onCreated: (String) -> Unit): Boolean =
    present("new-project", SheetStyle.Page(grabber = false)) { NewProjectSheetBody(onCreated) }

/** From the root host (the tablet's embedded New Session, tickets 113/106). */
fun SheetHostState.presentNewProject(onCreated: (String) -> Unit): Boolean =
    present("new-project", SheetStyle.Page(grabber = false)) { NewProjectSheetBody(onCreated) }

/**
 * The shared body of both [presentNewProject] overloads (ticket 92 step
 * 10's wiring, mirrored exactly): the app's single workspace feed mapped
 * into the hosts flow (initial value = the current snapshot, never
 * `null`), the core source over `clientOrNull()`, and the screen over the
 * controller — the same feed and the same client provider 92's model
 * uses; never a second workspace listener. 111 adds the write seam
 * ([CoreProjectWriteApi], the same provider) and the actions over it.
 */
@Composable
internal fun PageSheetScope.NewProjectSheetBody(onCreated: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as ZeronApplication
    val pageScope = rememberCoroutineScope()
    val feed = app.workspaceFeed
    val hosts = remember {
        feed.snapshot
            .map { it?.toNewSessionOptions()?.hosts }
            .stateIn(
                pageScope,
                SharingStarted.Eagerly,
                feed.snapshot.value?.toNewSessionOptions()?.hosts,
            )
    }
    val source = remember { CoreFolderBrowserSource({ app.clientOrNull() }) }
    val controller = rememberFolderBrowserController(hosts, source)
    val writeApi = remember { CoreProjectWriteApi({ app.clientOrNull() }) }
    val actions = rememberFolderBrowserActions(
        controller = controller,
        paths = HostPaths,
        api = writeApi,
        onCreated = onCreated,
        dismissSheet = { dismiss(animated = true) },
    )
    NewProjectScreen(sheet = this, controller = controller, actions = actions)
}
