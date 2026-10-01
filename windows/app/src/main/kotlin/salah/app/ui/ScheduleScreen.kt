package salah.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import salah.app.AppModel
import salah.app.platform.WindowsIntegration
import salah.core.DaySchedule
import salah.core.Prayer
import salah.core.PrayerSchedule
import salah.core.ScheduleExporter
import salah.core.TimeFormatting
import salah.core.monthName
import java.time.LocalDate

enum class ScheduleSpan(val title: String) { DAY("Day"), WEEK("Week"), MONTH("Month") }

/** Schedule navigation lives on the model so the window's keyboard shortcuts (←, →, T) can drive it. */
class ScheduleState {
    var span by mutableStateOf(ScheduleSpan.WEEK)
    var anchor by mutableStateOf<LocalDate?>(null)

    fun step(dir: Int, today: LocalDate) {
        val start = anchor ?: today
        anchor = when (span) {
            ScheduleSpan.DAY -> start.plusDays(dir.toLong())
            ScheduleSpan.WEEK -> start.plusDays(7L * dir)
            ScheduleSpan.MONTH -> start.withDayOfMonth(1).plusMonths(dir.toLong())
        }
    }
}

@Composable
fun ScheduleScreen(model: AppModel) {
    val c = palette
    val s = model.schedule
    val today = model.today()
    val start = s.anchor ?: today
    val loc = model.location?.takeIf { it.isValid }
    val days: List<DaySchedule> = if (loc == null) emptyList() else when (s.span) {
        ScheduleSpan.DAY -> listOf(PrayerSchedule.forDate(start, loc, model.config.calculation))
        ScheduleSpan.WEEK -> PrayerSchedule.range(start, 7, loc, model.config.calculation)
        ScheduleSpan.MONTH -> PrayerSchedule.month(start, loc, model.config.calculation)
    }
    val title = run {
        val first = days.firstOrNull()?.date
        val last = days.lastOrNull()?.date
        if (first == null || last == null) "Schedule" else when (s.span) {
            ScheduleSpan.DAY -> if (first == today) "Today" else TimeFormatting.longDate(first)
            ScheduleSpan.WEEK -> if (first == today) "This week" else "${first.dayOfMonth} ${first.monthName.take(3)} – ${last.dayOfMonth} ${last.monthName.take(3)} ${last.year}"
            ScheduleSpan.MONTH -> "${first.monthName} ${first.year}"
        }
    }

    Pane(title, model.location?.let { "${it.name} · ${model.config.methodName}" } ?: "Set a location to see the schedule.") {
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillPicker(ScheduleSpan.entries.map { it to it.title }, s.span, { s.span = it })
            Spacer(Modifier.weight(1f))
            IconButtonBox({ s.step(-1, today) }, "Previous (←)") { Label("‹", size = 16f) }
            SecondaryButton("Today") { s.anchor = null }
            IconButtonBox({ s.step(1, today) }, "Next (→)") { Label("›", size = 16f) }
            ExportMenu(model, days)
        }
        if (days.isNotEmpty()) ScheduleTable(model, days, today)
        Label("Sunrise marks the end of Fajr and is not a prayer.", color = c.secondary, modifier = Modifier.padding(top = 14.dp))
        if (days.any { it.hasUndefined }) {
            Label("— means the time can't be calculated here on that date.", color = c.accent, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ExportMenu(model: AppModel, days: List<DaySchedule>) {
    var open by remember { mutableStateOf(false) }
    val c = palette
    val loc = model.location
    Box {
        SecondaryButton("Export  ⌄", enabled = days.isNotEmpty() && loc != null) { open = true }
        DropdownMenu(open, { open = false }, Modifier.background(c.highlight)) {
            fun item(text: String, action: () -> Unit) = @Composable {
                DropdownMenuItem(text = { Label(text) }, onClick = { open = false; action() }, modifier = Modifier.heightIn(max = 34.dp))
            }
            if (loc != null && days.isNotEmpty()) {
                item("Copy to clipboard") { WindowsIntegration.copy(ScheduleExporter.text(days, loc, model.config.display)) }()
                item("Export CSV…") {
                    WindowsIntegration.saveFile("salah-${days.first().date}.csv", "csv")?.writeText(ScheduleExporter.csv(days))
                }()
                item("Export calendar (ICS)…") {
                    WindowsIntegration.saveFile("salah-${days.first().date}.ics", "ics")
                        ?.writeText(ScheduleExporter.ics(days, loc, model.config.display.jumuahRelabel))
                }()
            }
        }
    }
}

@Composable
private fun ScheduleTable(model: AppModel, days: List<DaySchedule>, today: LocalDate) {
    val c = palette
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, c.line, RoundedCornerShape(10.dp))) {
        Row(Modifier.fillMaxWidth()) {
            HeaderCell("Date", Modifier.weight(1.25f))
            for (p in Prayer.entries) HeaderCell(p.displayName, Modifier.weight(1f))
        }
        for (d in days) {
            HorizontalDivider(color = c.line)
            val isToday = d.date == today
            Row(Modifier.fillMaxWidth().background(if (isToday) c.highlight else Color.Transparent).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1.25f), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).heightIn(min = 40.dp).fillMaxHeight().background(if (isToday) c.accent else Color.Transparent))
                    Label(TimeFormatting.shortDate(d.date), modifier = Modifier.padding(start = 11.dp), weight = if (isToday) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
                for (p in Prayer.entries) TimeCell(model, d, p, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Box(modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Label(text, size = 11.5f, weight = FontWeight.SemiBold, color = palette.secondary, maxLines = 1)
    }
}

@Composable
private fun TimeCell(model: AppModel, d: DaySchedule, p: Prayer, modifier: Modifier) {
    val c = palette
    val t = d.time(p)?.let { TimeFormatting.clock(it, d.zone, model.config.display.use24HourClock) } ?: "—"
    val jumuah = p == Prayer.DHUHR && d.isFriday && model.config.display.jumuahRelabel
    Column(modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        PixelText(t, 15f, FontWeight.Bold, if (p == Prayer.SUNRISE) c.secondary else c.text)
        if (jumuah) Label("JUMU'AH", size = 9f, weight = FontWeight.SemiBold, color = c.accent, tracking = 0.4f)
    }
}
