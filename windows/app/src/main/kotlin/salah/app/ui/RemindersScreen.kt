package salah.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import salah.app.AppModel
import salah.core.Prayer
import salah.core.PrayerReminder
import salah.core.QuietHours
import salah.core.ReminderSound
import salah.core.SalahConfig
import salah.core.TimeFormatting
import salah.core.localDate
import java.time.Instant
import java.util.Locale

@Composable
fun RemindersScreen(model: AppModel) {
    val r = model.config.reminders
    val now = Instant.now()
    Pane(
        "Reminders",
        "Salah plans the next 3 days of reminders and shows them from the notification area. Keep it starting with Windows so they're never missed.",
    ) {
        SettingsGroup {
            row {
                val hint = when {
                    !r.enabled -> "Off"
                    model.location == null -> "Set a location first"
                    model.scheduled.isEmpty() -> "Shown as Salah notifications"
                    else -> "${model.scheduled.size} planned, through ${TimeFormatting.shortDate(model.scheduled.last().date)}"
                }
                SettingsRow("Prayer reminders", hint) {
                    SalahSwitch(r.enabled) { v -> model.update { it.copy(reminders = it.reminders.copy(enabled = v)) } }
                }
            }
            row {
                val until = r.pausedUntil
                val hint = if (until != null && r.isPaused(now)) {
                    "Paused until ${TimeFormatting.shortDate(localDate(until, model.zone))}, ${model.clock(until)}"
                } else null
                SettingsRow("Pause", hint) {
                    if (r.isPaused(now)) SecondaryButton("Resume") { model.pause(null) } else PauseMenu(model, enabled = r.enabled)
                }
            }
        }

        SettingsGroup {
            for (p in Prayer.prayers) row { PrayerRow(model, p) }
        }

        SettingsGroup {
            row {
                SettingsRow("Sound") {
                    DropdownPicker(ReminderSound.entries.map { it to it.displayName }, r.sound, { v ->
                        model.update { it.copy(reminders = it.reminders.copy(sound = v)) }
                    })
                }
            }
            row {
                SettingsRow("Quiet hours", "No reminders are shown in this window") {
                    TimeField(r.quietHours.start, r.quietHours.enabled) { v -> model.update { it.quiet { q -> q.copy(start = v) } } }
                    Label("–")
                    TimeField(r.quietHours.end, r.quietHours.enabled) { v -> model.update { it.quiet { q -> q.copy(end = v) } } }
                    SalahSwitch(r.quietHours.enabled) { v -> model.update { it.quiet { q -> q.copy(enabled = v) } } }
                }
            }
            row {
                SettingsRow("Check a reminder looks right") {
                    AccentButton("Send test notification") { model.sendTestNotification() }
                }
            }
        }
    }
}

private fun SalahConfig.quiet(body: (QuietHours) -> QuietHours) = copy(reminders = reminders.copy(quietHours = body(reminders.quietHours)))

@Composable
private fun PrayerRow(model: AppModel, p: Prayer) {
    val r = model.config.reminders
    val pr = r.reminder(p)
    val enabled = r.enabled && pr.enabled
    fun set(body: (PrayerReminder) -> PrayerReminder) = model.update { it.copy(reminders = it.reminders.update(p, body)) }
    SettingsRow(p.displayName) {
        DropdownPicker(
            listOf(0 to "No early reminder") + listOf(5, 10, 15, 30).map { it to "$it min before" },
            pr.leadMinutes, { v -> set { it.copy(leadMinutes = v) } }, enabled = enabled,
        )
        SalahCheckbox("At prayer time", pr.atTime, enabled) { v -> set { it.copy(atTime = v) } }
        SalahSwitch(pr.enabled, enabled = r.enabled) { v -> set { it.copy(enabled = v) } }
    }
}

@Composable
private fun PauseMenu(model: AppModel, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    val c = palette
    Box {
        SecondaryButton("Pause until…  ⌄", enabled = enabled) { open = true }
        DropdownMenu(open, { open = false }, Modifier.background(c.highlight)) {
            DropdownMenuItem({ Label("1 hour") }, { open = false; model.pause(Instant.now().plusSeconds(3600)) })
            DropdownMenuItem({ Label("Until tomorrow") }, { open = false; model.pause(model.today(Instant.now()).plusDays(1).atStartOfDay(model.zone).toInstant()) })
            HorizontalDivider(color = c.line)
            DropdownMenuItem({ Label("Custom…") }, { open = false; custom = true })
        }
    }
    if (custom) CustomPauseSheet(model) { custom = false }
}

/** Pause until a chosen day and time, in the location's time zone. */
@Composable
private fun CustomPauseSheet(model: AppModel, close: () -> Unit) {
    val start = Instant.now().plusSeconds(3 * 3600).atZone(model.zone)
    var day by remember { mutableStateOf(start.toLocalDate()) }
    var time by remember { mutableStateOf(String.format(Locale.ROOT, "%02d:%02d", start.hour, start.minute)) }
    Sheet(onDismiss = close, width = 340.dp) {
        Label("Pause reminders until", size = 15f, weight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButtonBox({ if (day > model.today(Instant.now())) day = day.minusDays(1) }) { Label("‹", size = 16f) }
            Label(TimeFormatting.shortDate(day), modifier = Modifier.width(96.dp))
            IconButtonBox({ day = day.plusDays(1) }) { Label("›", size = 16f) }
            TimeField(time, true) { time = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f))
            SecondaryButton("Cancel", onClick = close)
            AccentButton("Pause") {
                val m = QuietHours.minutes(time) ?: 0
                val until = day.atTime(m / 60, m % 60).atZone(model.zone).toInstant()
                if (until > Instant.now()) model.pause(until)
                close()
            }
        }
    }
}

/** HH:MM field that commits only valid 24-hour times. */
@Composable
fun TimeField(value: String, enabled: Boolean, onCommit: (String) -> Unit) {
    val c = palette
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) { text = value }
    BasicTextField(
        text,
        onValueChange = { t ->
            val clean = t.filter { it.isDigit() || it == ':' }.take(5)
            text = clean
            QuietHours.minutes(clean)?.let { m -> if (clean.length == 5) onCommit(String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)) }
        },
        enabled = enabled,
        singleLine = true,
        cursorBrush = SolidColor(c.accent),
        textStyle = TextStyle(color = if (enabled) c.text else c.secondary, fontSize = 13.sp, fontFeatureSettings = "tnum"),
        modifier = Modifier.width(58.dp),
        decorationBox = { inner ->
            Box(
                Modifier.background(c.background, RoundedCornerShape(5.dp)).border(1.dp, c.text.copy(alpha = 0.18f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) { inner() }
        },
    )
}
