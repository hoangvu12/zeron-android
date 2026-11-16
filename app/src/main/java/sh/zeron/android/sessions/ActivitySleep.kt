package sh.zeron.android.sessions

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import sh.zeron.android.design.ActionSheetAction
import sh.zeron.android.design.ActionSheetModel
import sh.zeron.android.design.ActionSheetPresenter
import sh.zeron.android.design.LocalActionSheet
import sh.zeron.android.shell.LocalSheetHost
import sh.zeron.android.shell.SheetHostState
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Sleep's copy, verbatim from iOS (`SessionListController.swift:395-431`). */
object SleepCopy {
    const val Sleep = "Sleep"
    const val Wake = "Wake"
    const val Custom = "Custom…"
    const val StopWaiting = "Stop Waiting"
    const val Archive = "Archive"
    const val ArchiveWorking = "Stop it first: its agent is still working"
    const val SheetTitle = "Sleep Until"
}

/**
 * Sleep's two prompts (iOS `presentSleepSheet` / `presentCustomSleep`):
 * the swipe's "Sleep Until" action sheet — each time with when it wakes,
 * Custom…, Cancel — and Custom…'s picker sheet. Either ends in [sleep].
 */
class SleepPrompts(
    private val sheets: ActionSheetPresenter,
    private val sheetHost: () -> SheetHostState?,
    private val sleep: (chatId: String, untilMs: Long) -> Unit,
    private val presetsAt: (nowMs: Long) -> List<ActivityList.Preset>,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Sleep's times from now (the menu's Sleep ▸ and the action sheet). */
    fun presets(): List<ActivityList.Preset> = presetsAt(now())

    /** The swipe's Sleep: its times as an action sheet. */
    fun sleepSheet(chatId: String, chatTitle: String?) {
        val actions = presets().map { p ->
            ActionSheetAction(title = "${p.title} · ${p.detail}") { sleep(chatId, p.untilMs) }
        } + ActionSheetAction(title = SleepCopy.Custom) { customSleep(chatId, chatTitle) }
        sheets.show(ActionSheetModel(title = SleepCopy.SheetTitle, actions = actions))
    }

    /** Custom…: the picker, from tomorrow at 9. */
    fun customSleep(chatId: String, chatTitle: String?) {
        sheetHost()?.presentSleepPicker(chatTitle, now()) { until -> sleep(chatId, until) }
    }

    companion object {
        /**
         * The app's short time, following the 24-hour setting
         * (iOS `timeStyle = .short` follows it there).
         */
        fun shortTime(context: Context, locale: Locale): (ZonedDateTime) -> String {
            val is24 = android.text.format.DateFormat.is24HourFormat(context)
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, if (is24) "Hm" else "hm")
            val f = DateTimeFormatter.ofPattern(pattern, locale)
            return { f.format(it) }
        }
    }
}

/** The ambient [SleepPrompts] over [actions]' sleep. */
@Composable
fun rememberSleepPrompts(actions: RowActions): SleepPrompts {
    val sheets = LocalActionSheet.current
    val host = LocalSheetHost.current
    val context = LocalContext.current
    return remember(sheets, host, actions, context) {
        SleepPrompts(
            sheets = sheets,
            sheetHost = { host },
            sleep = { id, until -> actions.sleep(id, until) },
            presetsAt = { nowMs ->
                val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
                ActivityList.presets(nowMs, ZoneId.systemDefault(), locale, SleepPrompts.shortTime(context, locale))
            },
        )
    }
}
