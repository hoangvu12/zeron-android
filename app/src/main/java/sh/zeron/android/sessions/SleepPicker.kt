package sh.zeron.android.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import sh.zeron.android.core.Face
import sh.zeron.android.design.Haptic
import sh.zeron.android.design.Icon
import sh.zeron.android.design.LocalFeedback
import sh.zeron.android.design.LocalTypeScale
import sh.zeron.android.design.SmoothRoundedShape
import sh.zeron.android.design.SymbolWeight
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.ZeronTheme
import sh.zeron.android.design.glass.GlassCircleButton
import sh.zeron.android.design.glass.GlassTextCapsule
import sh.zeron.android.design.sheet.GlassSheetDefaults
import sh.zeron.android.design.uiStyle
import sh.zeron.android.shell.SheetHostState
import sh.zeron.android.shell.SheetStyle
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs

/** The picker's copy, verbatim from iOS (`ActivityViews.swift:3-93`). */
object SleepPickerCopy {
    const val Title = "Sleep Until"
    const val Sleep = "Sleep"
    const val Cancel = "Cancel"
    const val Time = "Time"
    const val Untitled = "This chat"

    /** The sentence under the picker (`updateSummary`). */
    fun summary(chatTitle: String?, whenText: String): String {
        val name = chatTitle?.let { "“$it”" } ?: Untitled
        return "$name moves to Asleep until $whenText, on every device. " +
            "It wakes early if it asks you something, needs an approval or fails."
    }
}

/**
 * The picker's pure rules — iOS's `UIDatePicker` in `.dateAndTime`,
 * `.inline`, with `minuteInterval = 5` and `minimumDate = now + 60 s`
 * (`ActivityViews.swift:37-43`).
 */
object SleepPickerMath {

    /** `minuteInterval`. */
    const val MINUTE_STEP = 5

    /** The earliest choosable time: [ActivityList.customMinimum], up to the next 5-minute mark. */
    fun minimum(nowMs: Long, zone: ZoneId): LocalDateTime =
        ceilToStep(LocalDateTime.ofInstant(Instant.ofEpochMilli(ActivityList.customMinimum(nowMs)), zone))

    /** Up to the next [MINUTE_STEP] mark (seconds dropped); already on one: unchanged. */
    fun ceilToStep(t: LocalDateTime): LocalDateTime {
        val whole = t.truncatedTo(ChronoUnit.MINUTES)
        val up = if (whole == t) whole else whole.plusMinutes(1)
        val rem = up.minute % MINUTE_STEP
        return if (rem == 0) up else up.plusMinutes((MINUTE_STEP - rem).toLong())
    }

    /** UIKit's minimum: a choice before [minimum] lands on it. */
    fun clamp(t: LocalDateTime, minimum: LocalDateTime): LocalDateTime = if (t < minimum) minimum else t

    /** The day [date] chosen at [time]'s hour and minute, clamped. */
    fun withDay(time: LocalDateTime, date: LocalDate, minimum: LocalDateTime): LocalDateTime =
        clamp(date.atTime(time.hour, time.minute), minimum)

    /** [time]'s day at [hour]:[minute], clamped. */
    fun withTime(time: LocalDateTime, hour: Int, minute: Int, minimum: LocalDateTime): LocalDateTime =
        clamp(time.toLocalDate().atTime(hour, minute), minimum)

    /**
     * The month's grid, week rows starting on [firstDay]: `null` pads the
     * days before the 1st and after the last. Always whole weeks.
     */
    fun monthGrid(month: YearMonth, firstDay: DayOfWeek): List<LocalDate?> {
        val first = month.atDay(1)
        val lead = Math.floorMod(first.dayOfWeek.value - firstDay.value, 7)
        val cells = ArrayList<LocalDate?>(42)
        repeat(lead) { cells += null }
        for (d in 1..month.lengthOfMonth()) cells += month.atDay(d)
        while (cells.size % 7 != 0) cells += null
        return cells
    }

    /** The week's day order from [firstDay]. */
    fun weekdays(firstDay: DayOfWeek): List<DayOfWeek> = (0L until 7L).map { firstDay.plus(it) }

    /** The confirmed time (`max(picker.date, now + 60)`), epoch ms. */
    fun confirm(selected: LocalDateTime, zone: ZoneId, nowMs: Long): Long =
        maxOf(selected.atZone(zone).toInstant().toEpochMilli(), ActivityList.customMinimum(nowMs))
}

/**
 * The picker's state: the chosen time (opening on [ActivityList.customStart],
 * tomorrow at 9) and the month the calendar shows.
 */
@Stable
class SleepPickerState(val nowMs: Long, val zone: ZoneId) {
    val minimum: LocalDateTime = SleepPickerMath.minimum(nowMs, zone)
    var selected: LocalDateTime by mutableStateOf(
        SleepPickerMath.clamp(
            LocalDateTime.ofInstant(Instant.ofEpochMilli(ActivityList.customStart(nowMs, zone)), zone),
            minimum,
        ),
    )
        private set
    var month: YearMonth by mutableStateOf(YearMonth.from(selected))
        private set

    val canGoBack: Boolean get() = month > YearMonth.from(minimum)
    val untilMs: Long get() = SleepPickerMath.confirm(selected, zone, nowMs)

    fun selectDay(date: LocalDate) {
        selected = SleepPickerMath.withDay(selected, date, minimum)
        month = YearMonth.from(date)
    }

    fun setTime(hour: Int, minute: Int) {
        selected = SleepPickerMath.withTime(selected, hour, minute, minimum)
    }

    fun previousMonth() {
        if (canGoBack) month = month.minusMonths(1)
    }

    fun nextMonth() {
        month = month.plusMonths(1)
    }

    fun isEnabled(date: LocalDate): Boolean = date >= minimum.toLocalDate()
}

/**
 * Sleep → Custom… (iOS `SleepPickerViewController`): a large page sheet
 * with its grabber — Cancel, "Sleep Until", Sleep — over an inline calendar
 * and time on a card, and the sentence saying what it'll do. Sleep
 * confirms no earlier than a minute from now.
 */
fun SheetHostState.presentSleepPicker(
    chatTitle: String?,
    nowMs: Long = System.currentTimeMillis(),
    onSleep: (untilMs: Long) -> Unit,
): Boolean = present("sleep-until", SheetStyle.Page(grabber = true)) {
    SleepPickerSheet(
        chatTitle = chatTitle,
        nowMs = nowMs,
        onCancel = { dismiss() },
        onSleep = { until ->
            dismiss()
            onSleep(until)
        },
    )
}

@Composable
fun SleepPickerSheet(
    chatTitle: String?,
    nowMs: Long,
    onCancel: () -> Unit,
    onSleep: (untilMs: Long) -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val is24 = remember(context) { android.text.format.DateFormat.is24HourFormat(context) }
    val state = remember(nowMs, zone) { SleepPickerState(nowMs, zone) }
    val summaryFormat = remember(locale, is24) {
        DateTimeFormatter.ofPattern(
            android.text.format.DateFormat.getBestDateTimePattern(locale, if (is24) "EEEEMMMdHHmm" else "EEEEMMMdhmma"),
            locale,
        )
    }
    Column(Modifier.fillMaxSize().testTag("sleep-until")) {
        SleepPickerBar(onCancel = onCancel, onSleep = { onSleep(state.untilMs) })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(SmoothRoundedShape(26.dp))
                    .background(palette.elevated)
                    .padding(8.dp)
                    .testTag("sleep-picker"),
            ) {
                SleepPicker(state, locale, is24)
            }
            BasicText(
                SleepPickerCopy.summary(chatTitle, summaryFormat.format(state.selected.atZone(zone))),
                Modifier.padding(horizontal = 4.dp).testTag("sleep-summary"),
                style = uiStyle(Face.Sans, ts.scaled(13f), palette.secondary),
            )
        }
    }
}

/** The sheet's bar: ✕ Cancel, the title, and the bold Sleep (`.done`). */
@Composable
private fun SleepPickerBar(onCancel: () -> Unit, onSleep: () -> Unit) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    Box(Modifier.fillMaxWidth().height(GlassSheetDefaults.HeaderHeight)) {
        BasicText(
            SleepPickerCopy.Title,
            Modifier.align(Alignment.Center).widthIn(max = 220.dp).semantics { heading() },
            style = uiStyle(Face.SansSemibold, ts.fixed(17f), palette.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        GlassCircleButton(
            icon = ZeronIcons.Xmark,
            contentDescription = SleepPickerCopy.Cancel,
            onClick = onCancel,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp).testTag("sleep-cancel"),
        )
        GlassTextCapsule(
            text = SleepPickerCopy.Sleep,
            onClick = onSleep,
            emphasized = true,
            ink = palette.accent,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).testTag("sleep-confirm"),
        )
    }
}

/** The inline date-and-time picker: the month, its days, and Time. */
@Composable
private fun SleepPicker(state: SleepPickerState, locale: Locale, is24: Boolean) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    val feedback = LocalFeedback.current
    val firstDay = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val monthFormat = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }
    val dayLabel = remember(locale) { DateTimeFormatter.ofPattern("EEEE, MMMM d", locale) }
    val timeFormat = remember(locale, is24) {
        DateTimeFormatter.ofPattern(
            android.text.format.DateFormat.getBestDateTimePattern(locale, if (is24) "HHmm" else "hmma"),
            locale,
        )
    }
    val today = remember(state) { Instant.ofEpochMilli(state.nowMs).atZone(state.zone).toLocalDate() }
    var showTime by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        // The month, and its back / forward chevrons.
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                monthFormat.format(state.month),
                Modifier.weight(1f).semantics { heading() },
                style = uiStyle(Face.SansSemibold, ts.fixed(17f), palette.text),
            )
            MonthButton(ZeronIcons.ChevronBackward, "Previous Month", state.canGoBack) {
                state.previousMonth()
            }
            MonthButton(ZeronIcons.ChevronRight, "Next Month", true) { state.nextMonth() }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            for (day in SleepPickerMath.weekdays(firstDay)) {
                BasicText(
                    day.getDisplayName(TextStyle.SHORT, locale).uppercase(locale),
                    Modifier.weight(1f).clearAndSetSemantics { },
                    style = uiStyle(Face.SansSemibold, ts.fixed(13f), palette.tertiary)
                        .copy(textAlign = TextAlign.Center),
                )
            }
        }
        val cells = remember(state.month, firstDay) { SleepPickerMath.monthGrid(state.month, firstDay) }
        for (week in cells.chunked(7)) {
            Row(Modifier.fillMaxWidth()) {
                for (date in week) {
                    Box(Modifier.weight(1f).height(44.dp), contentAlignment = Alignment.Center) {
                        if (date != null) {
                            DayCell(
                                date = date,
                                selected = date == state.selected.toLocalDate(),
                                today = date == today,
                                enabled = state.isEnabled(date),
                                label = dayLabel.format(date),
                            ) {
                                feedback.haptic(Haptic.Select)
                                state.selectDay(date)
                            }
                        }
                    }
                }
            }
        }
        Box(
            Modifier
                .padding(start = 8.dp, end = 8.dp, top = 8.dp)
                .fillMaxWidth()
                .height((1f / LocalDensity.current.density).dp)
                .background(palette.hairline),
        )
        // Time, and its pill: a tap opens the wheels below.
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                SleepPickerCopy.Time,
                Modifier.weight(1f),
                style = uiStyle(Face.SansMedium, ts.fixed(17f), palette.text),
            )
            val pillSource = remember { MutableInteractionSource() }
            BasicText(
                timeFormat.format(state.selected),
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.controlFill)
                    .clickable(pillSource, indication = null, role = Role.Button) { showTime = !showTime }
                    .semantics { stateDescription = if (showTime) "Expanded" else "Collapsed" }
                    .padding(horizontal = 11.dp, vertical = 6.dp)
                    .testTag("sleep-time"),
                style = uiStyle(Face.Sans, ts.fixed(17f), if (showTime) palette.accent else palette.text),
            )
        }
        if (showTime) {
            TimeWheels(state, is24, locale)
        }
    }
}

@Composable
private fun MonthButton(icon: sh.zeron.android.design.ZeronIcon, label: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = ZeronTheme.palette
    val feedback = LocalFeedback.current
    Box(
        Modifier
            .size(44.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Button) {
                feedback.haptic(Haptic.Select)
                onClick()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, if (enabled) palette.accent else palette.tertiary, 17f, SymbolWeight.Semibold)
    }
}

/**
 * One day: today in the accent; the chosen day on an accent wash (filled
 * accent when it's today); days before the minimum dimmed and out of reach.
 */
@Composable
private fun DayCell(
    date: LocalDate,
    selected: Boolean,
    today: Boolean,
    enabled: Boolean,
    label: String,
    onPick: () -> Unit,
) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    val fill = when {
        selected && today -> palette.accent
        selected -> palette.accent.copy(alpha = 0.15f)
        else -> Color.Transparent
    }
    val ink = when {
        selected && today -> Color.White
        selected || today -> palette.accent
        !enabled -> palette.tertiary
        else -> palette.text
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(
                remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onPick,
            )
            .semantics {
                contentDescription = label
                this.selected = selected
            }
            .testTag("sleep-day-$date"),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            date.dayOfMonth.toString(),
            Modifier.clearAndSetSemantics { },
            style = uiStyle(if (selected) Face.SansSemibold else Face.Sans, ts.fixed(20f), ink)
                .copy(textAlign = TextAlign.Center),
        )
    }
}

/** The time's wheels: hours, 5-minute steps and — on a 12-hour clock — AM/PM. */
@Composable
private fun TimeWheels(state: SleepPickerState, is24: Boolean, locale: Locale) {
    val palette = ZeronTheme.palette
    val hour = state.selected.hour
    val minute = state.selected.minute
    val minutes = remember { (0 until 60 step SleepPickerMath.MINUTE_STEP).map { "%02d".format(it) } }
    val marks = remember(locale) {
        val f = DateTimeFormatter.ofPattern("a", locale)
        listOf(f.format(LocalDateTime.of(2000, 1, 1, 9, 0)), f.format(LocalDateTime.of(2000, 1, 1, 21, 0)))
    }
    Box(Modifier.fillMaxWidth().height(WheelRowHeight * WheelVisibleRows), contentAlignment = Alignment.Center) {
        // The selection band behind the middle row, as UIKit draws it.
        Box(
            Modifier
                .fillMaxWidth()
                .height(WheelRowHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.controlFill),
        )
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (is24) {
                Wheel(
                    values = remember { (0 until 24).map { "%02d".format(it) } },
                    selected = hour,
                    loop = true,
                    label = "Hour",
                    tag = "sleep-wheel-hour",
                ) { state.setTime(it, state.selected.minute) }
            } else {
                Wheel(
                    values = remember { (1..12).map { it.toString() } },
                    selected = (hour + 11) % 12,
                    loop = true,
                    label = "Hour",
                    tag = "sleep-wheel-hour",
                ) { i -> state.setTime((i + 1) % 12 + if (state.selected.hour >= 12) 12 else 0, state.selected.minute) }
            }
            Wheel(
                values = minutes,
                selected = minute / SleepPickerMath.MINUTE_STEP,
                loop = true,
                label = "Minute",
                tag = "sleep-wheel-minute",
            ) { i -> state.setTime(state.selected.hour, i * SleepPickerMath.MINUTE_STEP) }
            if (!is24) {
                Wheel(
                    values = marks,
                    selected = if (hour >= 12) 1 else 0,
                    loop = false,
                    label = "AM/PM",
                    tag = "sleep-wheel-ampm",
                ) { i -> state.setTime(state.selected.hour % 12 + i * 12, state.selected.minute) }
            }
        }
    }
}

private val WheelRowHeight = 34.dp
private const val WheelVisibleRows = 5
private const val WheelLoops = 400

/**
 * One wheel: a snapping column whose middle row is the choice. A looping
 * wheel repeats its values (UIKit's hour and minute wheels); each row
 * passing the middle ticks, as UIKit's do, and the choice lands when it
 * settles. A choice the minimum moves turns the wheel to it.
 */
@Composable
private fun Wheel(
    values: List<String>,
    selected: Int,
    loop: Boolean,
    label: String,
    tag: String,
    onSelect: (Int) -> Unit,
) {
    val palette = ZeronTheme.palette
    val ts = LocalTypeScale.current
    val feedback = LocalFeedback.current
    val n = values.size
    val count = if (loop) n * WheelLoops else n
    val base = if (loop) n * (WheelLoops / 2) else 0
    val list = rememberLazyListState(initialFirstVisibleItemIndex = base + selected)
    val fling = rememberSnapFlingBehavior(list)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestSelected by rememberUpdatedState(selected)
    val middle by remember {
        derivedStateOf {
            list.layoutInfo.visibleItemsInfo.minByOrNull { abs(it.offset) }?.index ?: list.firstVisibleItemIndex
        }
    }
    // A tick per row crossing the middle.
    LaunchedEffect(list) {
        snapshotFlow { middle }.distinctUntilChanged().drop(1).collect {
            if (list.isScrollInProgress) feedback.haptic(Haptic.Select)
        }
    }
    // The choice, once the wheel settles.
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.distinctUntilChanged().filter { !it }.collect {
            val value = Math.floorMod(middle, n)
            if (value != latestSelected) latestSelect(value)
        }
    }
    // The state moved the choice (the minimum): turn to it.
    LaunchedEffect(selected) {
        if (list.isScrollInProgress) return@LaunchedEffect
        val current = Math.floorMod(middle, n)
        if (current == selected) return@LaunchedEffect
        var delta = selected - current
        if (loop && abs(delta) > n / 2) delta -= Integer.signum(delta) * n
        list.animateScrollToItem((middle + delta).coerceIn(0, count - 1))
    }
    LazyColumn(
        state = list,
        flingBehavior = fling,
        contentPadding = PaddingValues(vertical = WheelRowHeight * (WheelVisibleRows / 2)),
        modifier = Modifier
            .width(if (n == 2) 64.dp else 56.dp)
            .height(WheelRowHeight * WheelVisibleRows)
            .semantics {
                contentDescription = label
                stateDescription = values[Math.floorMod(selected, n)]
            }
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(count) { i ->
            val v = Math.floorMod(i, n)
            val distance = abs(i - middle)
            BasicText(
                values[v],
                Modifier
                    .height(WheelRowHeight)
                    .padding(top = 4.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {
                        if (v != latestSelected) latestSelect(v)
                    },
                style = uiStyle(
                    Face.Sans,
                    ts.fixed(21f),
                    when (distance) {
                        0 -> palette.text
                        1 -> palette.secondary
                        else -> palette.tertiary
                    },
                ).copy(textAlign = TextAlign.Center),
            )
        }
    }
    Spacer(Modifier.width(4.dp))
}
