package sh.zeron.android.settings

import androidx.annotation.MainThread
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import sh.zeron.android.ZeronApplication
import sh.zeron.android.core.ActivitySettings
import sh.zeron.android.design.GroupedAccessory
import sh.zeron.android.design.GroupedRow
import sh.zeron.android.design.LocalBottomChrome
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronSwitch
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.glass.LocalGlassMenuHost
import sh.zeron.android.design.glass.Menu
import sh.zeron.android.design.glass.MenuItem
import sh.zeron.android.design.glass.rememberGlassMenuState
import sh.zeron.android.design.groupedSection
import sh.zeron.android.design.scrollEndPadding
import sh.zeron.android.sessions.ActivityList
import sh.zeron.android.shell.LargeTitleScaffold
import sh.zeron.android.shell.NavRoute
import sh.zeron.android.shell.NavStack
import sh.zeron.android.shell.TitleMode

/**
 * Settings → Activity (iOS 1.0.2 `ActivitySettingsViewController`),
 * pushed from Settings' Activity row and the Sessions ⋯ menu's
 * "Activity Settings…". The bottom bar steps aside, like Settings itself.
 */
data object ActivitySettingsRoute : NavRoute {
    override val hidesBottomChrome: Boolean get() = true
    override val paneTitle: String get() = ActivitySettingsCopy.Title
}

/** Pushes [ActivitySettingsRoute] on [nav] unless it is already on top. */
@MainThread
fun openActivitySettings(nav: NavStack) {
    if (nav.top.route == ActivitySettingsRoute) return
    nav.push(ActivitySettingsRoute, animated = true)
}

/** The page's copy, verbatim from iOS (`ActivityViews.swift:114-207`). */
object ActivitySettingsCopy {
    const val Title = "Activity"
    const val View = "Activity Sidebar"
    const val Working = "Fold Working Chats into Working"
    const val Sleep = "Sleep"
    const val SleepDetail = "Swipe or touch and hold a chat"
    const val ArchiveAfter = "Archive Idle Chats"
    const val ActivityHeader = "Activity"
    const val ArchivingHeader = "Archiving"
    const val ViewFooter =
        "One list of the chats that need you, the latest at the top. Working, asleep and archived chats fold " +
            "away at its end. Also in the Sessions “…” menu."
    const val ActivityFooter =
        "A chat moves into Working while its agent runs, or waits on work that will wake it, and comes back to " +
            "the top when it’s done or needs you.\n\nSwipe a chat, or touch and hold it, and choose Sleep to put " +
            "it in Asleep until a time you pick, on every device. It wakes early if it asks you something, needs " +
            "an approval or fails, and comes back to the top."
    const val ArchivingFooter =
        "Chats with nothing new for this long move to Archived. Pinned, working and asleep chats stay, as do " +
            "chats waiting on you."
}

/** The rows' ids (iOS `activity-setting-<id>`, `toggle-activity-<id>`). */
private enum class ActivitySettingRow(val id: String) { View("view"), Working("working"), Sleep("sleep"), ArchiveAfter("archive-after") }

/**
 * The page: the view's own switch, then — dimmed and out of reach while
 * it's off — whether working chats fold into Working, what Sleep does, and
 * how long a chat may sit idle before it's archived. All of it is this
 * phone's ([sh.zeron.android.core.Prefs]); the Sessions ⋯ menu writes the
 * same flow, so the page follows it.
 */
@Composable
fun ActivitySettingsScreen(
    modifier: Modifier = Modifier,
    settings: StateFlow<ActivitySettings> =
        (LocalContext.current.applicationContext as ZeronApplication).prefs.activity,
    onChange: (ActivitySettings) -> Unit =
        (LocalContext.current.applicationContext as ZeronApplication).prefs::setActivity,
) {
    val s by settings.collectAsState()
    val chrome = LocalBottomChrome.current
    val menus = if (LocalGlassMenuHost.current != null) rememberGlassMenuState() else null
    var archiveAnchor by remember { mutableStateOf(Rect.Zero) }
    val palette = ZeronTheme.palette

    LargeTitleScaffold(
        title = ActivitySettingsCopy.Title,
        modifier = modifier.testTag("activity-settings"),
        mode = TitleMode.InlineOnly,
        contentPadding = PaddingValues(bottom = scrollEndPadding(chrome) + 16.dp),
    ) {
        groupedSection(
            key = "activity-view",
            header = null,
            items = listOf(ActivitySettingRow.View),
            itemKey = { it.id },
            footer = ActivitySettingsCopy.ViewFooter,
        ) { row, position ->
            GroupedRow(
                title = ActivitySettingsCopy.View,
                position = position,
                icon = ZeronIcons.TrayFull,
                accessories = listOf(
                    GroupedAccessory.Custom {
                        ZeronSwitch(
                            checked = s.on,
                            onCheckedChange = { onChange(s.copy(on = it)) },
                            testTag = "toggle-activity-${row.id}",
                        )
                    },
                ),
                testTag = "activity-setting-${row.id}",
            )
        }
        val enabled = s.on
        val dim = if (enabled) palette.secondary else palette.tertiary
        groupedSection(
            key = "activity-behaviour",
            header = ActivitySettingsCopy.ActivityHeader,
            items = listOf(ActivitySettingRow.Working, ActivitySettingRow.Sleep),
            itemKey = { it.id },
            footer = ActivitySettingsCopy.ActivityFooter,
        ) { row, position ->
            when (row) {
                ActivitySettingRow.Working -> GroupedRow(
                    title = ActivitySettingsCopy.Working,
                    position = position,
                    icon = ZeronIcons.CircleDashed,
                    iconTint = dim,
                    accessories = listOf(
                        GroupedAccessory.Custom {
                            ZeronSwitch(
                                checked = s.workingShelf,
                                onCheckedChange = { onChange(s.copy(workingShelf = it)) },
                                testTag = "toggle-activity-${row.id}",
                                enabled = enabled,
                            )
                        },
                    ),
                    testTag = "activity-setting-${row.id}",
                    disabled = !enabled,
                )
                else -> GroupedRow(
                    title = ActivitySettingsCopy.Sleep,
                    position = position,
                    subtitle = ActivitySettingsCopy.SleepDetail,
                    subtitleColor = if (enabled) null else palette.tertiary,
                    icon = ZeronIcons.Moon,
                    iconTint = dim,
                    accessories = emptyList(),
                    testTag = "activity-setting-${row.id}",
                    disabled = !enabled,
                )
            }
        }
        groupedSection(
            key = "activity-archiving",
            header = ActivitySettingsCopy.ArchivingHeader,
            items = listOf(ActivitySettingRow.ArchiveAfter),
            itemKey = { it.id },
            footer = ActivitySettingsCopy.ArchivingFooter,
        ) { row, position ->
            GroupedRow(
                title = ActivitySettingsCopy.ArchiveAfter,
                position = position,
                modifier = Modifier.onGloballyPositioned { archiveAnchor = it.boundsInWindow() },
                icon = ZeronIcons.Archivebox,
                iconTint = dim,
                accessories = listOf(GroupedAccessory.Value(ActivityList.archiveAfterLabel(s.archiveAfterDays))),
                testTag = "activity-setting-${row.id}",
                disabled = !enabled,
                // iOS's pop-up button (`archiveButton`): Never, After 1 Day, …
                onClick = {
                    menus?.open(archiveAnchor, archiveAfterMenu(s.archiveAfterDays) { days ->
                        onChange(s.copy(archiveAfterDays = days))
                    })
                },
            )
        }
    }
}

/** Never, After 1 Day, … — the current choice checked (iOS `archiveButton`'s menu). Pure. */
fun archiveAfterMenu(days: Int, onSelect: (Int) -> Unit): Menu = Menu(
    children = ActivityList.archiveAfterChoices.map { choice ->
        MenuItem(
            title = ActivityList.archiveAfterLabel(choice),
            checked = choice == days,
            testTag = "activity-archive-after-$choice",
        ) { onSelect(choice) }
    },
)
