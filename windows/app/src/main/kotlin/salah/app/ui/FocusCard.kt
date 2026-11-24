package salah.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import salah.app.AppModel
import salah.app.focus.FocusCard
import salah.core.TimeFormatting
import salah.core.focusmode.CardNote
import salah.core.kindwords.KindWordsPlanner

/**
 * Focus Mode's card: a calm full-screen window over everything at prayer time. Done (or Esc)
 * closes it and resumes the Quran player if Focus Mode paused it; it also closes by itself.
 */
@Composable
fun ApplicationScope.FocusCardWindow(model: AppModel, card: FocusCard, dark: Boolean) {
    val state = rememberWindowState(placement = WindowPlacement.Fullscreen)
    Window(
        onCloseRequest = { model.focus.done() },
        state = state,
        title = "Salah",
        undecorated = true,
        alwaysOnTop = true,
        resizable = false,
        icon = painterResource("icons/salah.png"),
        onPreviewKeyEvent = { if (it.key == Key.Escape) { model.focus.done(); true } else false },
    ) {
        LaunchedEffect(Unit) {
            window.toFront()
            window.requestFocus()
        }
        SalahTheme(dark) {
            Localized(model.config.display.lang) { FocusCardContent(model, card) }
        }
    }
}

@Composable
fun FocusCardContent(model: AppModel, card: FocusCard) {
    val c = palette
    val zone = model.zone
    Box(Modifier.fillMaxSize().background(c.timeline), contentAlignment = Alignment.Center) {
        DottedBackground()
        Column(
            Modifier.widthIn(max = 620.dp).padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            card.note?.let { note ->
                Label(
                    tr(if (note == CardNote.CALL_ENDED) "Your call ended" else "You're free now"),
                    size = 12f, weight = FontWeight.SemiBold, color = c.timeline,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(c.onTimeline).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            Eyebrow(tr("IT'S TIME"), color = c.onTimelineDim)
            Label(card.label, size = 44f, weight = FontWeight.SemiBold, color = c.onTimeline)
            PixelText(TimeFormatting.clock(card.prayerTime, zone, model.config.display.use24HourClock, lang = model.config.display.lang), 40f, color = c.onTimeline)
            // The same verse or hadith as the early reminder, picked for this prayer and day.
            val line = model.schedule(card.prayerTime.atZone(zone).toLocalDate())?.let { KindWordsPlanner.beforePrayerLine(card.prayer, it, model.config, force = true) }
            Label(
                line ?: tr("Leave what you're doing for a few minutes. It will be here when you come back."),
                size = 15f, color = c.onTimelineDim, modifier = Modifier.padding(top = 8.dp),
            )
            Box(Modifier.padding(top = 18.dp)) {
                SecondaryButton(tr("Done")) { model.focus.done() }
            }
            Label(
                tr("Closes by itself at {0}", TimeFormatting.clock(card.closesAt, zone, model.config.display.use24HourClock, lang = model.config.display.lang)),
                size = 12f, color = c.onTimelineDim,
            )
        }
    }
}

