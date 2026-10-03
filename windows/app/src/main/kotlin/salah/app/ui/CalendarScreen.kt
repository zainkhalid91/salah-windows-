package salah.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import salah.app.AppModel
import salah.core.CalKey
import salah.core.CalLang
import salah.core.CalendarDay
import salah.core.CalendarSettings
import salah.core.CalendarText
import salah.core.IslamicAlertTime
import salah.core.IslamicCalendar
import salah.core.MonthGrid
import salah.core.PrimaryCalendar
import salah.core.localDate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Hijri and Gregorian month views, a converter, this year's Islamic dates and
 * the alert settings, in the app's language.
 */
@Composable
fun CalendarScreen(model: AppModel) {
    val locale = appLocale()
    CalendarBody(model, locale, CalendarText.lang(locale))
}

@Composable
private fun CalendarBody(model: AppModel, locale: Locale, lang: CalLang) {
    val c = palette
    val adj = model.config.display.hijriAdjustment
    val today = localDate(model.now, model.zone)
    val todayHijri = IslamicCalendar.toHijri(today, adj)
    val primary = model.config.calendar.primary
    val firstDay = CalendarText.firstDayOfWeek(locale)
    var offset by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(today) }

    val grid: MonthGrid? = when (primary) {
        PrimaryCalendar.HIJRI -> todayHijri?.let {
            val (y, m) = IslamicCalendar.shiftHijriMonth(it.year, it.month, offset)
            IslamicCalendar.hijriMonth(y, m, adj, firstDay)
        }
        PrimaryCalendar.GREGORIAN -> IslamicCalendar.gregorianMonth(YearMonth.from(today).plusMonths(offset.toLong()), adj, firstDay)
    }

    fun jumpTo(date: LocalDate) {
        selected = date
        offset = when (primary) {
            PrimaryCalendar.GREGORIAN -> ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(date)).toInt()
            PrimaryCalendar.HIJRI -> {
                val h = IslamicCalendar.toHijri(date, adj)
                if (h == null || todayHijri == null) 0 else (h.year * 12 + h.month) - (todayHijri.year * 12 + todayHijri.month)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(c.background)) {
        val wide = maxWidth > 820.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(CalendarText.text(CalKey.CALENDAR, lang), size = 20f, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                PillPicker(
                    listOf(
                        PrimaryCalendar.HIJRI to CalendarText.text(CalKey.HIJRI, lang),
                        PrimaryCalendar.GREGORIAN to CalendarText.text(CalKey.GREGORIAN, lang),
                    ),
                    primary,
                    { p ->
                        model.update { it.copy(calendar = it.calendar.copy(primary = p)) }
                        offset = 0
                    },
                )
            }
            Spacer(Modifier.height(16.dp))

            val month: @Composable (Modifier) -> Unit = { m ->
                if (grid == null) Label(CalendarText.text(CalKey.OUT_OF_RANGE, lang), color = c.secondary, modifier = m)
                else MonthCard(grid, lang, locale, today, selected, { selected = it }, { offset += it }, { jumpTo(today) }, m)
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    month(Modifier.weight(1.15f))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SelectedDay(selected, adj, today, lang, locale)
                        YearEvents(adj, today, lang, locale) { jumpTo(it) }
                    }
                }
            } else {
                month(Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                SelectedDay(selected, adj, today, lang, locale)
                Spacer(Modifier.height(16.dp))
                YearEvents(adj, today, lang, locale) { jumpTo(it) }
            }
            Spacer(Modifier.height(18.dp))
            Column(Modifier.widthIn(max = 720.dp)) {
                Converter(adj, lang, locale) { jumpTo(it) }
                Alerts(model, lang)
            }
        }
    }
}

@Composable
private fun MonthCard(
    grid: MonthGrid, lang: CalLang, locale: Locale, today: LocalDate, selected: LocalDate,
    onSelect: (LocalDate) -> Unit, onShift: (Int) -> Unit, onToday: () -> Unit, modifier: Modifier,
) {
    val c = palette
    val days = grid.inMonth
    val title: String
    val subtitle: String
    if (grid.primary == PrimaryCalendar.HIJRI) {
        title = CalendarText.hijriMonth(grid.month, lang)
        val a = days.first().date
        val b = days.last().date
        subtitle = "${CalendarText.number(grid.year, lang)} · ${CalendarText.gregorianMonth(a.monthValue, locale)} – ${CalendarText.gregorianMonth(b.monthValue, locale)} ${CalendarText.number(b.year, lang)}"
    } else {
        title = CalendarText.gregorianMonth(grid.month, locale)
        val a = days.first().hijri
        val b = days.last().hijri
        subtitle = "${CalendarText.number(grid.year, lang)} · ${CalendarText.hijriMonth(a.month, lang)} – ${CalendarText.hijriMonthYear(b.year, b.month, lang)}"
    }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(c.display).border(1.dp, c.line, RoundedCornerShape(14.dp))) {
        DottedBackground(Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (lang.rtl) Label(title, size = 28f, weight = FontWeight.Bold)
                    else PixelText(title.uppercase(locale), 30f)
                    Label(subtitle, size = 12.5f, color = c.secondary)
                }
                IconButtonBox({ onShift(-1) }, null) { Label(if (lang.rtl) "›" else "‹", size = 16f) }
                Spacer(Modifier.width(6.dp))
                IconButtonBox({ onShift(1) }, null) { Label(if (lang.rtl) "‹" else "›", size = 16f) }
            }
            LinkButton(CalendarText.text(CalKey.TODAY, lang), modifier = Modifier.padding(top = 8.dp)) { onToday() }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
                for (cell in grid.weeks.first()) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Label(
                            CalendarText.weekdayShort(cell.date.dayOfWeek, locale),
                            size = 11f, weight = FontWeight.SemiBold,
                            color = if (cell.date.dayOfWeek == DayOfWeek.FRIDAY) c.accent else c.secondary,
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (week in grid.weeks) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (cell in week) DayCell(cell, grid.primary, lang, cell.date == today, cell.date == selected, Modifier.weight(1f)) { onSelect(cell.date) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(cell: CalendarDay, primary: PrimaryCalendar, lang: CalLang, isToday: Boolean, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = palette
    val main = if (primary == PrimaryCalendar.HIJRI) cell.hijri.day else cell.date.dayOfMonth
    val second = if (primary == PrimaryCalendar.HIJRI) cell.date.dayOfMonth else cell.hijri.day
    val major = cell.events.any { it.major }
    val bg = when {
        isToday -> c.accent
        major -> c.accent.copy(alpha = 0.16f)
        cell.events.isNotEmpty() || cell.whiteDay -> c.text.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    val fg = when {
        isToday -> c.onAccent
        cell.date.dayOfWeek == DayOfWeek.FRIDAY -> c.accent
        else -> c.text
    }
    Box(
        modifier.aspectRatio(1.05f).clip(RoundedCornerShape(8.dp)).background(bg)
            .then(if (isSelected && !isToday) Modifier.border(1.5.dp, c.accent, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .alpha(if (cell.inMonth) 1f else 0.32f),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (lang.rtl) Label(CalendarText.number(main, lang), size = 15f, weight = FontWeight.Bold, color = fg)
            else PixelText(main.toString(), 16f, FontWeight.Bold, fg)
            Label(CalendarText.number(second, lang), size = 9.5f, color = if (isToday) c.onAccent.copy(alpha = 0.8f) else c.secondary)
        }
        if (cell.events.isNotEmpty() || cell.whiteDay) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(5.dp).size(5.dp).clip(CircleShape)
                    .background(if (isToday) c.onAccent else if (major) c.accent else c.secondary),
            )
        }
    }
}

@Composable
private fun SelectedDay(date: LocalDate, adj: Int, today: LocalDate, lang: CalLang, locale: Locale) {
    val c = palette
    val day = IslamicCalendar.day(date, adj) ?: return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.timeline).padding(20.dp)) {
        Row {
            Label(CalendarText.weekdayLong(date.dayOfWeek, locale).uppercase(locale), size = 12f, weight = FontWeight.SemiBold, color = c.onTimelineDim, tracking = 1.2f, modifier = Modifier.weight(1f))
            val rel = ChronoUnit.DAYS.between(today, date)
            if (rel != 0L) Label(CalendarText.relative(rel, lang), size = 12f, color = c.onTimelineDim)
        }
        Label(CalendarText.hijri(day.hijri, lang, monthNumber = true), size = 20f, weight = FontWeight.SemiBold, color = c.onTimeline, modifier = Modifier.padding(top = 6.dp))
        Label(CalendarText.gregorian(date, locale), size = 13.5f, color = c.onTimelineDim)
        Spacer(Modifier.height(10.dp))
        val names = day.events.map { CalendarText.event(it, lang) } + if (day.whiteDay) listOf(CalendarText.text(CalKey.WHITE_DAYS, lang)) else emptyList()
        if (names.isEmpty()) Label(CalendarText.text(CalKey.NO_EVENTS, lang), size = 13f, color = c.onTimelineDim)
        for (n in names) {
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(c.onTimeline))
                Label(n, size = 14f, weight = FontWeight.Medium, color = c.onTimeline, modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}

@Composable
private fun YearEvents(adj: Int, today: LocalDate, lang: CalLang, locale: Locale, onShow: (LocalDate) -> Unit) {
    val c = palette
    val year = IslamicCalendar.hijriYear(today, adj) ?: return
    val events = remember(year, adj) { IslamicCalendar.yearEvents(year, adj) }
    val next = events.firstOrNull { !it.second.isBefore(today) }?.first
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.display.copy(alpha = 0.45f)).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(16.dp)) {
        Eyebrow("${CalendarText.text(CalKey.EVENTS_THIS_YEAR, lang).uppercase(locale)} · ${CalendarText.number(year, lang)}")
        Spacer(Modifier.height(8.dp))
        for ((event, date) in events) {
            val past = date.isBefore(today)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onShow(date) }.pointerHoverIcon(PointerIcon.Hand)
                    .padding(vertical = 7.dp, horizontal = 4.dp).alpha(if (past) 0.45f else 1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (lang.rtl) Label(CalendarText.number(event.day, lang), size = 17f, weight = FontWeight.Bold, color = if (event.major) c.accent else c.text)
                    else PixelText(event.day.toString(), 18f, FontWeight.Bold, if (event.major) c.accent else c.text)
                    Label(CalendarText.hijriMonth(event.month, lang), size = 10f, color = c.secondary, maxLines = 1)
                }
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Label(CalendarText.event(event, lang), size = 14f, weight = if (event.major) FontWeight.SemiBold else FontWeight.Normal)
                    Label(CalendarText.gregorian(date, locale), size = 12f, color = c.secondary)
                }
                if (!past) {
                    val isNext = event == next
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(if (isNext) c.accent else c.text.copy(alpha = 0.07f))
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    ) {
                        Label(CalendarText.relative(ChronoUnit.DAYS.between(today, date), lang), size = 11f, weight = FontWeight.SemiBold, color = if (isNext) c.onAccent else c.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun Converter(adj: Int, lang: CalLang, locale: Locale, onShow: (LocalDate) -> Unit) {
    val c = palette
    var toHijri by remember { mutableStateOf(true) }
    val now = remember { LocalDate.now() }
    var g by remember { mutableStateOf(now) }
    val start = remember { IslamicCalendar.toHijri(now, adj) }
    var hDay by remember { mutableIntStateOf(start?.day ?: 1) }
    var hMonth by remember { mutableIntStateOf(start?.month ?: 1) }
    var hYear by remember { mutableIntStateOf(start?.year ?: 1447) }

    Eyebrow(CalendarText.text(CalKey.CONVERTER, lang).uppercase(locale), modifier = Modifier.padding(bottom = 8.dp))
    SettingsGroup {
        row {
            SettingsRow(CalendarText.text(CalKey.CONVERTER, lang)) {
                PillPicker(
                    listOf(true to CalendarText.text(CalKey.GREGORIAN_TO_HIJRI, lang), false to CalendarText.text(CalKey.HIJRI_TO_GREGORIAN, lang)),
                    toHijri, { toHijri = it },
                )
            }
        }
        if (toHijri) {
            row {
                SettingsRow(CalendarText.text(CalKey.DAY, lang)) {
                    Stepper(CalendarText.number(g.dayOfMonth, lang), true, true, { g = g.minusDays(1) }, { g = g.plusDays(1) })
                }
            }
            row {
                SettingsRow(CalendarText.text(CalKey.MONTH, lang)) {
                    DropdownPicker((1..12).map { it to CalendarText.gregorianMonth(it, locale) }, g.monthValue, { m ->
                        g = g.withMonth(m)
                    })
                }
            }
            row {
                SettingsRow(CalendarText.text(CalKey.YEAR, lang)) {
                    Stepper(CalendarText.number(g.year, lang), g.year > 1883, g.year < 2173, { g = g.minusYears(1) }, { g = g.plusYears(1) })
                }
            }
        } else {
            val maxDay = IslamicCalendar.monthLength(hYear, hMonth) ?: 30
            if (hDay > maxDay) hDay = maxDay
            row {
                SettingsRow(CalendarText.text(CalKey.DAY, lang)) {
                    Stepper(CalendarText.number(hDay, lang), hDay > 1, hDay < maxDay, { hDay-- }, { hDay++ })
                }
            }
            row {
                SettingsRow(CalendarText.text(CalKey.MONTH, lang)) {
                    DropdownPicker((1..12).map { it to CalendarText.hijriMonth(it, lang) }, hMonth, { hMonth = it })
                }
            }
            row {
                SettingsRow(CalendarText.text(CalKey.YEAR, lang)) {
                    Stepper(
                        CalendarText.number(hYear, lang), hYear > IslamicCalendar.MIN_HIJRI_YEAR, hYear < IslamicCalendar.MAX_HIJRI_YEAR - 1,
                        { hYear-- }, { hYear++ },
                    )
                }
            }
        }
        row {
            val target: LocalDate? = if (toHijri) g else IslamicCalendar.toGregorian(hYear, hMonth, hDay, adj)
            val text = if (toHijri) {
                IslamicCalendar.toHijri(g, adj)?.let { CalendarText.hijri(it, lang) }
            } else {
                target?.let { CalendarText.gregorian(it, locale) }
            } ?: CalendarText.text(CalKey.OUT_OF_RANGE, lang)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(text, size = 17f, weight = FontWeight.SemiBold, color = c.text, modifier = Modifier.weight(1f))
                if (target != null) LinkButton(CalendarText.text(CalKey.CALENDAR, lang) + if (lang.rtl) " ←" else " →") { onShow(target) }
            }
        }
    }
}

@Composable
private fun Alerts(model: AppModel, lang: CalLang) {
    val cal = model.config.calendar
    fun set(body: (CalendarSettings) -> CalendarSettings) = model.update { it.copy(calendar = body(it.calendar)) }
    Eyebrow(CalendarText.text(CalKey.ALERTS, lang).uppercase(), modifier = Modifier.padding(bottom = 8.dp))
    SettingsGroup {
        row { SettingsRow(CalendarText.text(CalKey.ALERT_NEW_MONTH, lang)) { SalahSwitch(cal.notifyNewMonth) { v -> set { it.copy(notifyNewMonth = v) } } } }
        row { SettingsRow(CalendarText.text(CalKey.ALERT_SPECIAL_DAYS, lang)) { SalahSwitch(cal.notifySpecialDays) { v -> set { it.copy(notifySpecialDays = v) } } } }
        row { SettingsRow(CalendarText.text(CalKey.ALERT_WHITE_DAYS, lang)) { SalahSwitch(cal.notifyWhiteDays) { v -> set { it.copy(notifyWhiteDays = v) } } } }
        row {
            SettingsRow(CalendarText.text(CalKey.ALERT_TIME, lang)) {
                DropdownPicker(
                    listOf(
                        IslamicAlertTime.MAGHRIB_BEFORE to CalendarText.text(CalKey.EVENING_BEFORE, lang),
                        IslamicAlertTime.MORNING to CalendarText.text(CalKey.MORNING_OF, lang),
                    ),
                    cal.alertTime, { t -> set { it.copy(alertTime = t) } },
                )
            }
        }
    }
}
