package salah.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import salah.app.AppModel
import salah.app.platform.WindowsIntegration
import salah.core.AppLanguage
import salah.core.AppText
import salah.core.CalendarText
import salah.core.DaySchedule
import salah.core.ExtraTime
import salah.core.ExtraWindow
import salah.core.HijriDate
import salah.core.MadhabSetting
import salah.core.Prayer
import salah.core.PrayerClockState
import salah.core.PrayerReminder
import salah.core.SunnahTimes
import salah.core.TimeFormatting
import salah.core.TimelineItem
import salah.core.monthName
import salah.core.weekdayName
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/** The split device face: red timeline and digital display. Stacks below ~760dp wide. */
@Composable
fun TodayScreen(model: AppModel) {
    val now = model.now
    val state = model.clockState(now)
    BoxWithConstraints(Modifier.fillMaxSize().background(palette.background)) {
        val narrow = maxWidth < 760.dp
        if (narrow) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DisplayPanel(model, state, now, Modifier.fillMaxWidth().height(480.dp))
                TimelinePanel(model, state, now, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)))
            }
        } else {
            val timelineWidth = (maxWidth.value * 0.35f).coerceIn(260f, 330f).dp
            Row(Modifier.fillMaxSize()) {
                TimelinePanel(model, state, now, Modifier.width(timelineWidth).fillMaxHeight())
                DisplayPanel(model, state, now, Modifier.weight(1f).fillMaxHeight().padding(14.dp))
            }
        }
    }
}

// region Timeline

@Composable
private fun TimelinePanel(model: AppModel, state: PrayerClockState?, now: Instant, modifier: Modifier) {
    val c = palette
    val date = model.previewDate ?: state?.today?.date ?: model.today(now)
    val isPreview = model.previewDate != null && model.previewDate != state?.today?.date
    val schedule = if (isPreview) model.schedule(date) else state?.today
    val hijri = HijriDate.of(date, model.config.display.hijriAdjustment)
    val lang = model.config.display.lang
    val location = model.location?.takeIf { it.isValid }
    val extras = if (model.config.display.showSunnahTimes && location != null) {
        remember(date, location, model.config.calculation) { SunnahTimes.forDate(date, location, model.config.calculation) }
    } else emptyList()
    val items = TimelineItem.of(schedule, extras)
    var showPicker by remember { mutableStateOf(false) }

    Column(modifier.background(c.timeline).padding(start = 26.dp, top = 26.dp, end = 26.dp, bottom = 22.dp)) {
        Box {
            Column(
                Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { showPicker = true }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .semantics { contentDescription = "${AppText.longDate(date, lang, includeYear = true)}, ${AppText.hijri(hijri, lang)}" },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Label(AppText.longDate(date, lang).uppercase(), size = 15f, weight = FontWeight.SemiBold, color = c.onTimeline, tracking = 0.2f)
                Label(AppText.hijri(hijri, lang).uppercase(), size = 12.5f, color = c.onTimelineDim)
            }
            if (showPicker) {
                Popup(offset = androidx.compose.ui.unit.IntOffset(0, 54), onDismissRequest = { showPicker = false }, properties = PopupProperties(focusable = true)) {
                    DatePopover(model, date) { showPicker = false }
                }
            }
        }

        Column(Modifier.padding(top = 26.dp)) {
            items.forEachIndexed { i, item ->
                val first = i == 0
                val last = i == items.lastIndex
                when (item) {
                    is TimelineItem.Fard -> TimelineRow(model, item.prayer, schedule, if (isPreview) null else state, now, first, last)
                    is TimelineItem.Extra -> ExtraRow(model, item.window, now, isPreview, first, last)
                }
            }
        }

        Spacer(Modifier.weight(1f).heightIn(min = 14.dp))

        val methodLine = if (model.location == null) tr("No location set") else {
            tr(model.config.methodName) + if (model.config.calculation.madhab == MadhabSetting.HANAFI) " · " + tr("Hanafi Asr") else ""
        }
        Label(methodLine, size = 11.5f, color = c.onTimelineDim, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun TimelineRow(
    model: AppModel, prayer: Prayer, schedule: DaySchedule?, state: PrayerClockState?, now: Instant,
    isFirst: Boolean, isLast: Boolean,
) {
    val c = palette
    val time = schedule?.time(prayer)
    val isNext = state?.next?.let { !it.isTomorrow && it.prayer == prayer } ?: false
    val isCurrent = state?.let { it.nowPrayer == prayer || (it.nowPrayer == null && it.current == prayer) } ?: false
    val isPast = state != null && (time?.let { it <= now } ?: false) && !isCurrent
    val isSunrise = prayer == Prayer.SUNRISE
    val label = schedule?.label(prayer, model.config.display.jumuahRelabel, model.config.display.lang) ?: tr(prayer.displayName)
    val fg = if (isNext) c.text else if (isSunrise) c.onTimelineDim else c.onTimeline
    val lineColor = c.onTimelineDim.copy(alpha = 0.55f)
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()

    val description = rowDescription(model, label, time, schedule, isSunrise, isNext, isCurrent, state)
    Box(Modifier.fillMaxWidth().timelineLine(lineColor, isFirst, isLast)) {
        Row(
            Modifier.fillMaxWidth()
                .offset(x = if (isNext) (-10).dp else 0.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isNext) c.highlight else if (hovered && schedule != null) Color.White.copy(alpha = 0.07f) else Color.Transparent)
                .hoverable(source)
                .clickable(enabled = schedule != null, role = Role.Button) { model.clearDetail(); model.detailPrayer = prayer }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(start = if (isNext) 10.dp else 0.dp, end = if (isNext) 2.dp else 12.dp, top = 9.dp, bottom = 9.dp)
                .alpha(if (isPast && !isNext) 0.55f else 1f)
                .semantics { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(22.dp), contentAlignment = Alignment.CenterStart) { Marker(isSunrise, isNext) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(label, size = if (isSunrise) 12f else 14f, weight = FontWeight.Medium, color = fg)
                if (isCurrent && !isNext) {
                    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                        Label(tr("now"), size = 10.5f, weight = FontWeight.Medium, color = fg)
                    }
                }
            }
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            TimeCell(model, time, schedule, isSunrise, fg)
        }
    }
}

@Composable
private fun Marker(isSunrise: Boolean, isNext: Boolean) {
    val c = palette
    if (isSunrise) {
        Box(Modifier.padding(start = 1.dp).size(9.dp).background(c.timeline, CircleShape).border(1.5.dp, c.onTimelineDim, CircleShape))
    } else {
        // A ring the color of what's behind it, so the connector line appears to stop at the dot.
        val ring = if (isNext) c.highlight else c.timeline
        Box(Modifier.size(11.dp).drawBehind {
            drawCircle(ring, radius = size.minDimension / 2 + 1.5.dp.toPx())
            drawCircle(if (isNext) c.accent else c.onTimeline, radius = size.minDimension / 2)
        })
    }
}

@Composable
private fun TimeCell(model: AppModel, time: Instant?, schedule: DaySchedule?, isSunrise: Boolean, color: Color) {
    val size = if (isSunrise) 14f else 17f
    when {
        time != null && schedule != null -> {
            val (t, period) = TimeFormatting.parts(time, schedule.zone, model.config.display.use24HourClock, padHour = true, lang = model.config.display.lang)
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                PixelText(t, size, FontWeight.Bold, color, Modifier.alignByBaseline())
                if (period.isNotEmpty()) Label(period, size = 10f, weight = FontWeight.SemiBold, color = color.copy(alpha = 0.7f), modifier = Modifier.alignByBaseline())
            }
        }
        schedule == null -> PixelText("--:--", size, FontWeight.Bold, color)
        // Doto's em dash is tiny; use the UI font so "undefined" reads clearly.
        else -> Label("—", size = size, weight = FontWeight.SemiBold, color = color)
    }
}

/** What a screen reader says for a row. */
@Composable
private fun rowDescription(
    model: AppModel, label: String, time: Instant?, schedule: DaySchedule?, isSunrise: Boolean,
    isNext: Boolean, isCurrent: Boolean, state: PrayerClockState?,
): String {
    if (time == null) return "$label, ${tr(if (schedule == null) "No location set" else "can't be calculated")}"
    var s = "$label, ${model.clock(time)}"
    if (isSunrise) s += ", " + tr("End of Fajr time, not a prayer")
    val secs = state?.secondsToNext
    if (isNext && secs != null) s += ", " + tr("NEXT PRAYER") + ", " + tr("IN") + " " + AppText.spoken(Math.floor(secs / 60) * 60, LocalLang.current)
    else if (isCurrent) s += ", " + tr("now")
    return s
}

/** Line through the markers, joining the rows. Drawn on the start side, so it follows RTL. */
private fun Modifier.timelineLine(color: Color, isFirst: Boolean, isLast: Boolean) = drawBehind {
    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - 5.5.dp.toPx() else 5.5.dp.toPx()
    if (!isFirst) drawLine(color, Offset(x, 0f), Offset(x, size.height / 2), 1.dp.toPx())
    if (!isLast) drawLine(color, Offset(x, size.height / 2), Offset(x, size.height), 1.dp.toPx())
}

/** A sunnah prayer or marker: smaller and dimmer than the five, with when its window closes. */
@Composable
private fun ExtraRow(model: AppModel, window: ExtraWindow, now: Instant, isPreview: Boolean, isFirst: Boolean, isLast: Boolean) {
    val c = palette
    val lang = model.config.display.lang
    val active = !isPreview && now >= window.start && now < window.end
    val past = !isPreview && now >= window.end && !active
    val fg = if (active) c.onTimeline else c.onTimelineDim
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(Modifier.fillMaxWidth().timelineLine(c.onTimelineDim.copy(alpha = 0.55f), isFirst, isLast)) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (hovered) Color.White.copy(alpha = 0.07f) else Color.Transparent)
                .hoverable(source)
                .clickable(role = Role.Button) { model.clearDetail(); model.previewDate = null; model.detailExtra = window.time }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(end = 12.dp, top = 5.dp, bottom = 5.dp)
                .alpha(if (past) 0.55f else 1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(22.dp), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.padding(start = 2.dp).size(7.dp).background(if (active) c.onTimeline else c.timeline, CircleShape).border(1.dp, c.onTimelineDim, CircleShape))
            }
            Column {
                Label(AppText.extra(window.time, lang), size = 12f, weight = FontWeight.Medium, color = fg)
                if (window.end != window.start) Label(tr("until {0}", model.clock(window.end)), size = 10f, color = c.onTimelineDim)
            }
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            val (t, period) = TimeFormatting.parts(window.start, model.zone, model.config.display.use24HourClock, padHour = true, lang = lang)
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                PixelText(t, 14f, FontWeight.Bold, fg, Modifier.alignByBaseline())
                if (period.isNotEmpty()) Label(period, size = 10f, weight = FontWeight.SemiBold, color = fg.copy(alpha = 0.7f), modifier = Modifier.alignByBaseline())
            }
        }
    }
}

/** Month calendar: picking a day previews that day's schedule on the timeline. */
@Composable
private fun DatePopover(model: AppModel, date: LocalDate, close: () -> Unit) {
    val c = palette
    var month by remember { mutableStateOf(YearMonth.from(date)) }
    val today = model.today()
    Column(
        Modifier.width(272.dp).padding(4.dp)
            .background(c.highlight, RoundedCornerShape(12.dp))
            .border(1.dp, c.line, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(AppText.monthYear(month.atDay(1), LocalLang.current), size = 14f, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButtonBox({ month = month.minusMonths(1) }, tr("Previous month")) { Label("‹", size = 16f) }
            Spacer(Modifier.width(6.dp))
            IconButtonBox({ month = month.plusMonths(1) }, tr("Next month")) { Label("›", size = 16f) }
        }
        Row {
            val locale = appLocale()
            for (d in DayOfWeek.entries.map { CalendarText.weekdayShort(it, locale).take(1) }) {
                Label(d, size = 11f, weight = FontWeight.SemiBold, color = c.secondary, modifier = Modifier.weight(1f).padding(start = 9.dp))
            }
        }
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value - 1
        val cells = (0 until 42).map { i -> first.plusDays((i - lead).toLong()) }
        for (week in cells.chunked(7)) {
            if (week.all { it.month != month.month } && week.first() > first) break
            Row {
                for (d in week) {
                    val inMonth = d.month == month.month
                    val selected = d == date
                    val isToday = d == today
                    Box(
                        Modifier.weight(1f).height(30.dp).padding(1.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (selected) c.accent else Color.Transparent)
                            .then(if (isToday && !selected) Modifier.border(1.dp, c.accent, RoundedCornerShape(6.dp)) else Modifier)
                            .clickable {
                                model.previewDate = if (d == today) null else d
                                model.clearDetail()
                                close()
                            }
                            .pointerHoverIcon(PointerIcon.Hand),
                        contentAlignment = Alignment.Center,
                    ) {
                        PixelText(
                            d.dayOfMonth.toString(), 13f, FontWeight.Bold,
                            when {
                                selected -> c.onAccent
                                inMonth -> c.text
                                else -> c.secondary.copy(alpha = 0.5f)
                            },
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinkButton(tr("Back to today")) { model.previewDate = null; close() }
            Spacer(Modifier.weight(1f))
            Label(AppText.hijri(HijriDate.of(date, model.config.display.hijriAdjustment), LocalLang.current), size = 11.5f, color = c.secondary)
        }
    }
}

// endregion

// region Display

@Composable
private fun DisplayPanel(model: AppModel, state: PrayerClockState?, now: Instant, modifier: Modifier) {
    val c = palette
    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(14.dp)).background(c.display)
            .border(1.dp, c.line, RoundedCornerShape(14.dp)),
    ) {
        DottedBackground()
        // A static inset edge instead of a drop shadow: shadows re-blur whenever the countdown changes.
        Box(Modifier.fillMaxWidth().height(1.dp).offset(y = 1.dp).background(Color.Black.copy(alpha = 0.05f)))
        val w = maxWidth.value
        val nameSize = (w * 0.11f).coerceIn(40f, 84f)
        val timeSize = (w * 0.19f).coerceIn(60f, 138f)
        val contentWidth = maxWidth - 68.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 30.dp)) {
            Column(Modifier.weight(1f, fill = true)) { DisplayContent(model, state, now, nameSize, timeSize, contentWidth) }
            Spacer(Modifier.height(16.dp))
            Footer(model, now)
        }
    }
}

@Composable
private fun DisplayContent(model: AppModel, state: PrayerClockState?, now: Instant, nameSize: Float, timeSize: Float, width: Dp) {
    val d = model.config.display
    val lang = d.lang
    when {
        model.location == null -> SetLocationContent(model, nameSize, width)
        state == null -> {
            StatusRow(tr("LOCATION"))
            Label(tr("The saved location is invalid."), modifier = Modifier.padding(top = 20.dp))
            SecondaryButton(tr("Change location…"), modifier = Modifier.padding(top = 10.dp)) { model.showLocationSheet = true }
        }
        model.previewDate != null && model.previewDate != state.today.date -> PreviewContent(model, model.previewDate!!, nameSize, timeSize, width)
        model.detailPrayer != null -> DetailContent(model, model.detailPrayer!!, state.today, nameSize, timeSize, width)
        model.detailExtra != null -> ExtraContent(model, model.detailExtra!!, state.today.date, nameSize, timeSize, width)
        state.nowPrayer != null && state.today.time(state.nowPrayer!!) != null -> {
            val p = state.nowPrayer!!
            StatusRow(tr("NOW"))
            PrayerName(state.today.label(p, d.jumuahRelabel, lang), nameSize, palette.accent, width)
            PrayerTime(model, state.today.time(p)!!, timeSize)
            Rule()
            Countdown(tr("STARTED"), state.secondsSinceNow ?: 0.0)
        }
        state.next != null -> {
            val n = state.next!!
            StatusRow(tr("NEXT PRAYER"), if (n.isTomorrow) tr("TOMORROW") else null)
            PrayerName(n.label(d.jumuahRelabel, lang), nameSize, palette.text, width)
            PrayerTime(model, n.time, timeSize)
            Rule()
            Countdown(tr("IN"), n.secondsRemaining(now))
            state.today.undefinedExplanation(model.config.display.lang)?.let { UndefinedNote(model, it, Modifier.padding(top = 14.dp)) }
        }
        else -> {
            StatusRow(tr("NEXT PRAYER"))
            PrayerName("—", nameSize, palette.text, width)
            state.today.undefinedExplanation(model.config.display.lang)?.let { UndefinedNote(model, it, Modifier.padding(top = 16.dp)) }
        }
    }
}

@Composable
private fun PreviewContent(model: AppModel, d: LocalDate, nameSize: Float, timeSize: Float, width: Dp) {
    val c = palette
    StatusRow(tr("PREVIEW"))
    val lang = model.config.display.lang
    if (lang == AppLanguage.EN) {
        PrayerName(d.weekdayName.take(3), nameSize, c.text, width)
        PixelText(String.format(Locale.ROOT, "%02d %s", d.dayOfMonth, d.monthName.take(3).uppercase()), timeSize * 0.62f, modifier = Modifier.padding(top = 6.dp))
    } else {
        val locale = appLocale()
        PrayerName(CalendarText.weekdayLong(d.dayOfWeek, locale), nameSize, c.text, width)
        Label("${d.dayOfMonth} ${CalendarText.gregorianMonth(d.monthValue, locale)}", size = 40f, weight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
    }
    Rule()
    Label("${d.year} · ${AppText.hijri(HijriDate.of(d, model.config.display.hijriAdjustment), lang)}", color = c.secondary)
    LinkButton(tr("Back to today"), modifier = Modifier.padding(top = 14.dp)) { model.previewDate = null }
}

@Composable
private fun DetailContent(model: AppModel, p: Prayer, schedule: DaySchedule, nameSize: Float, timeSize: Float, width: Dp) {
    val c = palette
    val reminder = model.config.reminders.reminder(p)
    val offset = model.config.calculation.offset(p)
    StatusRow(if (p == Prayer.SUNRISE) tr("SUNRISE") else tr("PRAYER DETAIL"))
    PrayerName(schedule.label(p, model.config.display.jumuahRelabel, model.config.display.lang), nameSize, c.text, width)
    val t = schedule.time(p)
    if (t != null) PrayerTime(model, t, timeSize) else PixelText("—", timeSize, modifier = Modifier.padding(top = 6.dp))
    Rule()
    val rows = buildList {
        if (p == Prayer.SUNRISE) add(tr("Note") to tr("End of Fajr time; not a prayer")) else add(tr("Reminder") to reminderText(model, reminder))
        add(tr("Method") to tr(model.config.methodName))
        add(tr("Offset") to if (offset == 0) tr("None") else tr("{0} min", "${if (offset > 0) "+" else ""}$offset"))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((k, v) in rows) Row {
            Label(k, color = c.secondary, modifier = Modifier.width(84.dp))
            Label(v)
        }
    }
}

/** What a sunnah time is, and its window today. */
@Composable
private fun ExtraContent(model: AppModel, extra: ExtraTime, date: LocalDate, nameSize: Float, timeSize: Float, width: Dp) {
    val c = palette
    val location = model.location ?: return
    val window = remember(date, location, model.config.calculation) {
        SunnahTimes.forDate(date, location, model.config.calculation).firstOrNull { it.time == extra }
    }
    StatusRow(tr(if (extra.isPrayer) "SUNNAH PRAYER" else "PRAYER DETAIL"))
    PrayerName(AppText.extra(extra, model.config.display.lang), nameSize, c.text, width)
    window?.let { PrayerTime(model, it.start, timeSize) }
    Rule()
    Label(tr(EXTRA_NOTES.getValue(extra)), modifier = Modifier.widthIn(max = 460.dp))
    if (window != null && window.end != window.start) {
        Row(Modifier.padding(top = 8.dp)) {
            Label(tr("Window"), color = c.secondary, modifier = Modifier.width(84.dp))
            Label(tr("{0} to {1}", model.clock(window.start), model.clock(window.end)))
        }
    }
}

private val EXTRA_NOTES = mapOf(
    ExtraTime.TAHAJJUD to "The last third of the night, the best time for night prayer.",
    ExtraTime.ISHRAQ to "Once the sun has fully risen, about 20 minutes after sunrise.",
    ExtraTime.DUHA to "The forenoon prayer, once a quarter of the day has passed.",
    ExtraTime.ZAWAL to "The sun is at its height. Wait for Dhuhr before praying.",
    ExtraTime.AWWABIN to "Between Maghrib and Isha, after the sunnah of Maghrib.",
    ExtraTime.MIDNIGHT to "Halfway between Maghrib and Fajr. Pray Isha before it.",
)

@Composable
private fun reminderText(model: AppModel, r: PrayerReminder): String {
    if (!model.config.reminders.enabled) return tr("Off (all reminders)")
    if (!r.enabled || r.leads.isEmpty()) return tr("Off")
    val parts = mutableListOf<String>()
    if (r.leadMinutes > 0) parts += tr("{0} min before", r.leadMinutes)
    if (r.atTime) parts += tr("at prayer time")
    val text = if (parts.size == 2) tr("{0} and {1}", parts[0], parts[1]) else parts[0]
    return text.replaceFirstChar { it.uppercase() }
}

@Composable
private fun StatusRow(text: String, tag: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Eyebrow(text, modifier = Modifier.weight(1f))
        if (tag != null) Label(tag, size = 11f, weight = FontWeight.SemiBold, color = palette.accent, tracking = 0.6f)
    }
}

/** The big pixel prayer name; shrinks (down to half) to fit, and cross-fades when it changes. */
@Composable
private fun PrayerName(name: String, size: Float, color: Color, width: Dp) {
    val fitted = fittedPixelSize(name.uppercase(), size, width)
    AnimatedContent(
        targetState = name,
        transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(350)) },
        modifier = Modifier.padding(top = 26.dp),
        label = "prayer-name",
    ) { n ->
        if (n == "—") {
            // Doto's em dash is tiny; the UI font reads clearly as "undefined".
            Label("—", size = fitted, weight = FontWeight.SemiBold, color = color)
        } else {
            PixelText(n.uppercase(), fitted, color = color, modifier = Modifier.semantics { contentDescription = n })
        }
    }
}

@Composable
private fun fittedPixelSize(text: String, size: Float, width: Dp): Float {
    val measurer = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    return remember(text, size, width) {
        val maxPx = with(density) { width.toPx() }
        var s = size
        while (s > size * 0.5f) {
            val w = measurer.measure(text, PixelFont.style(s), softWrap = false).size.width
            if (w <= maxPx) break
            s -= 2f
        }
        s
    }
}

@Composable
private fun PrayerTime(model: AppModel, t: Instant, size: Float) {
    val (time, period) = TimeFormatting.parts(t, model.zone, model.config.display.use24HourClock, padHour = true, lang = model.config.display.lang)
    Row(Modifier.padding(top = 6.dp).semantics(mergeDescendants = true) { contentDescription = model.clock(t) }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PixelText(time, size, modifier = Modifier.alignByBaseline())
        if (period.isNotEmpty()) Label(period, size = 18f, weight = FontWeight.SemiBold, color = palette.secondary, modifier = Modifier.alignByBaseline())
    }
}

@Composable
private fun Rule() {
    Box(Modifier.padding(top = 22.dp, bottom = 16.dp).size(width = 88.dp, height = 2.dp).clip(RoundedCornerShape(1.dp)).background(palette.text))
}

/** Ticks each second; the spoken value changes once a minute so screen readers aren't flooded. */
@Composable
private fun Countdown(label: String, seconds: Double) {
    val spoken = "$label ${AppText.spoken(Math.floor(seconds / 60) * 60, LocalLang.current)}"
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label(label, size = 12f, weight = FontWeight.SemiBold, color = palette.secondary, tracking = 1.5f, modifier = Modifier.alignByBaseline())
        PixelText(TimeFormatting.countdown(seconds), 28f, FontWeight.Bold, modifier = Modifier.alignByBaseline())
    }
}

@Composable
private fun UndefinedNote(model: AppModel, e: Pair<String, String?>, modifier: Modifier) {
    Column(modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(e.first, size = 12.5f, color = palette.secondary)
        e.second?.let { LinkButton(it) { model.tab = AppModel.Tab.SETTINGS } }
    }
}

@Composable
private fun Footer(model: AppModel, now: Instant) {
    val c = palette
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            model.location?.let { loc ->
                Box(Modifier.clickable(role = Role.Button) { model.tab = AppModel.Tab.SETTINGS }.pointerHoverIcon(PointerIcon.Hand)) {
                    Label(loc.name, size = 12.5f, weight = FontWeight.Medium)
                }
                Label(loc.timeZone, size = 12.5f, color = c.secondary)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (model.location != null) {
                val use24 = model.config.display.use24HourClock
                val (t, period) = TimeFormatting.parts(now, model.zone, use24, lang = model.config.display.lang)
                val secs = now.atZone(model.zone).second
                PixelText(if (use24) t + String.format(Locale.ROOT, ":%02d", secs) else "$t $period", 16f, FontWeight.Bold)
            }
            if (model.detailPrayer != null || model.detailExtra != null) LinkButton(tr("Back to next prayer")) { model.clearDetail() }
        }
    }
}

/** First run, or location permission denied: never shows fake times. */
@Composable
private fun SetLocationContent(model: AppModel, nameSize: Float, width: Dp) {
    val c = palette
    val provider = model.locationProvider
    val title = tr("SET LOCATION")
    Eyebrow(tr("WELCOME"))
    PixelText(title, fittedPixelSize(title, nameSize * 0.8f, width), modifier = Modifier.padding(top = 26.dp))
    Label(tr("Prayer times are calculated for where you are."), color = c.secondary, modifier = Modifier.padding(top = 14.dp))
    Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        AccentButton(
            if (provider.isLocating) tr("Locating…") else tr("Use my location"), enabled = !provider.isLocating,
            leading = if (provider.isLocating) ({ CircularProgressIndicator(Modifier.size(12.dp), color = c.onAccent, strokeWidth = 1.5.dp) }) else null,
        ) { provider.requestLocation() }
        SecondaryButton(tr("Enter manually")) { model.showLocationSheet = true }
    }
    if (provider.isDenied || provider.errorMessage != null) {
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(tr(provider.errorMessage ?: "Location access is off for Salah."), size = 12f, color = c.secondary)
            if (provider.isDenied) LinkButton(tr("Open Windows Settings")) { WindowsIntegration.openSettings("privacy-location") }
        }
    }
}

// endregion
