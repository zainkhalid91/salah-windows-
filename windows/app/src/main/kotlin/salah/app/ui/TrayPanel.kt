package salah.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import salah.app.AppModel
import salah.core.Prayer
import salah.core.TimeFormatting

/** The notification-area panel: next prayer, today's times, the reminders switch and "Open Salah". */
@Composable
fun TrayPanel(model: AppModel, onOpen: () -> Unit = {}, onQuit: () -> Unit = {}) {
    val c = palette
    val now = model.now
    val state = model.clockState(now)
    val d = model.config.display
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(c.background)
            .border(1.dp, c.line, RoundedCornerShape(10.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val n = state?.next
        if (state != null && n != null) {
            val nowP = state.nowPrayer
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Label(if (nowP != null) "NOW" else if (n.isTomorrow) "NEXT · TOMORROW" else "NEXT PRAYER", size = 10.5f, weight = FontWeight.SemiBold, color = c.secondary, tracking = 1.2f)
                val label = nowP?.let { state.today.label(it, d.jumuahRelabel) } ?: n.label(d.jumuahRelabel)
                Row(verticalAlignment = Alignment.Bottom) {
                    PixelText(label.uppercase(), 22f, color = if (nowP != null) c.accent else c.text, modifier = Modifier.weight(1f))
                    PixelText(model.clock(nowP?.let { state.today.time(it) } ?: n.time), 18f, FontWeight.Bold)
                }
                Label(
                    if (nowP != null) "Next: ${n.label(d.jumuahRelabel)} in ${TimeFormatting.short(n.secondsRemaining(now))}"
                    else "in ${TimeFormatting.countdown(n.secondsRemaining(now))}",
                    size = 12f, color = c.secondary,
                )
            }
            HorizontalDivider(color = c.line)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (p in Prayer.entries) {
                    val isNext = !n.isTomorrow && n.prayer == p
                    val color = if (p == Prayer.SUNRISE) c.secondary else if (isNext) c.accent else c.text
                    val size = if (p == Prayer.SUNRISE) 11.5f else 13f
                    Row {
                        Label(state.today.label(p, d.jumuahRelabel), size = size, color = color, weight = if (isNext) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                        Label(state.today.time(p)?.let { model.clock(it) } ?: "—", size = size, color = color, weight = if (isNext) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            Label("${model.location?.name ?: ""} · ${model.config.calculation.methodShortName(model.location)}", size = 11f, color = c.secondary)
        } else if (model.location == null) {
            Label("Set your location to see prayer times.", color = c.secondary)
        } else {
            Label("Prayer times can't be calculated for this location today.", color = c.secondary)
        }
        model.updater.availableRelease?.let { r ->
            HorizontalDivider(color = c.line)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("Salah ${r.version} is available", size = 12.5f, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
                AccentButton("Update") { model.updater.install(r) { model.onQuitCompletely() } }
            }
        }
        Spacer(Modifier.weight(1f))
        HorizontalDivider(color = c.line)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Reminders", modifier = Modifier.weight(1f))
            SalahSwitch(model.config.reminders.enabled) { v -> model.update { it.copy(reminders = it.reminders.copy(enabled = v)) } }
        }
        Row {
            SecondaryButton("Open Salah", onClick = onOpen)
            Spacer(Modifier.weight(1f))
            SecondaryButton("Quit completely", onClick = onQuit)
        }
    }
}

/** A reminder toast, drawn by Salah in the corner of the screen. */
@Composable
fun ToastCard(title: String, body: String, onOpen: () -> Unit, onClose: () -> Unit) {
    val c = palette
    Row(
        Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(c.background)
            .border(1.dp, c.line, RoundedCornerShape(10.dp))
            .clickable(onClick = onOpen).pointerHoverIcon(PointerIcon.Hand),
    ) {
        Box(Modifier.width(6.dp).fillMaxSize().background(c.timeline))
        Row(Modifier.fillMaxSize().padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(c.timeline), contentAlignment = Alignment.Center) {
                Label("☾", size = 18f, weight = FontWeight.Bold, color = c.onTimeline)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Label("SALAH", size = 10f, weight = FontWeight.SemiBold, color = c.secondary, tracking = 1.2f)
                Label(title, size = 14f, weight = FontWeight.SemiBold, maxLines = 1)
                Label(body, size = 12.5f, color = c.secondary, maxLines = 1)
            }
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)).clickable(onClick = onClose).pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center,
            ) { Label("✕", size = 12f, color = c.secondary) }
        }
    }
}
