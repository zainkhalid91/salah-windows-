package salah.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import salah.app.AppModel
import salah.app.platform.WindowsIntegration

/** Tab bar plus the selected screen, or the invalid-config view. Sheets draw on top. */
@Composable
fun RootView(model: AppModel) {
    val c = palette
    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize()) {
            TabBar(model)
            HorizontalDivider(color = c.line, thickness = 1.dp)
            UpdateBanner(model)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val error = model.configError
                val more = model.more
                if (error != null) {
                    InvalidConfigView(model, error)
                } else if (more != null) {
                    MoreScreen(model, more)
                } else {
                    when (model.tab) {
                        AppModel.Tab.HOME -> HomeScreen(model)
                        AppModel.Tab.TODAY -> TodayScreen(model)
                        AppModel.Tab.QURAN -> QuranScreen(model)
                        AppModel.Tab.CALENDAR -> CalendarScreen(model)
                        AppModel.Tab.SCHEDULE -> ScheduleScreen(model)
                        AppModel.Tab.REMINDERS -> RemindersScreen(model)
                        AppModel.Tab.SETTINGS -> SettingsScreen(model)
                    }
                }
            }
            // The mini player follows you around while something is queued.
            if (model.configError == null && model.quran.queue != null && (model.tab != AppModel.Tab.QURAN || model.more != null) && model.config.quran.showInMenuBar) {
                MiniPlayer(model, onOpen = { model.open(AppModel.Tab.QURAN) })
            }
        }
        if (model.showLocationSheet) LocationSheet(model)
        if (model.config.display.language == null && model.configError == null) {
            LanguagePicker { picked -> model.update { it.copy(display = it.display.copy(language = picked)) } }
        }
    }
}

/** The top bar, which is also the window's title bar: drag it to move the window. */
@Composable
private fun TabBar(model: AppModel) {
    val c = palette
    val chrome = LocalWindowChrome.current
    TitleBarArea(Modifier.fillMaxWidth().height(48.dp).background(c.chrome)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Narrow windows drop the app name and the status first, so the tabs stay on one line.
            val roomy = maxWidth >= 900.dp
            val showName = maxWidth >= 800.dp
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.padding(start = 14.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label("☾", size = 15f, weight = FontWeight.SemiBold, color = c.accent)
                    if (showName) Label("Salah", size = 13f, weight = FontWeight.SemiBold)
                }
                // The tabs sit in the middle of whatever space is left, so they never run into the edges.
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    PillPicker(AppModel.Tab.entries.map { it to tr(it.title) }, model.tab, { model.open(it) })
                }
                if (roomy) Label(tr(model.reminderStatus), size = 12f, color = c.secondary, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp))
                if (chrome != null) CaptionButtons(chrome, Modifier.padding(start = if (roomy) 0.dp else 12.dp))
            }
        }
    }
}

/** Shown instead of the dashboard when the config file can't be read. Never crashes, never blank. */
@Composable
private fun InvalidConfigView(model: AppModel, message: String) {
    val c = palette
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 560.dp).padding(40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Eyebrow(tr(tr("SETTINGS ERROR")), color = c.accent)
            Label(tr(tr("Salah couldn't read its settings.")), size = 20f, weight = FontWeight.SemiBold)
            Label(message, color = c.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AccentButton(tr(tr("Reset to defaults"))) { model.resetToDefaults() }
                SecondaryButton(tr(tr("Show in Explorer"))) { WindowsIntegration.showInExplorer(model.store.path) }
            }
            Label(tr(tr("Resetting replaces the file with defaults. You'll need to set your location again.")), size = 12f, color = c.secondary)
        }
    }
}

/** "Salah 1.5.12 is ready" under the tab bar, once an update has downloaded (or can be). */
@Composable
private fun UpdateBanner(model: AppModel) {
    val c = palette
    val u = model.updater
    val r = u.availableRelease ?: return
    if (u.bannerDismissed == r.version) return
    val ready = u.state is salah.app.platform.Updater.State.Ready
    Row(
        Modifier.fillMaxWidth().background(c.accent.copy(alpha = 0.12f)).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Label(
            tr(if (ready) "Salah {0} is ready. Restart to update." else "Salah {0} is available.", r.version),
            weight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1,
        )
        LinkButton(tr("What's new"), size = 12f) { salah.app.platform.WindowsIntegration.openUrl(r.pageUrl) }
        LinkButton(tr("Later"), size = 12f, color = c.secondary) { u.bannerDismissed = r.version }
        if (ready) AccentButton(tr("Restart to update")) { u.install({ model.onQuitCompletely() }) }
        else AccentButton(tr("Download")) { u.download(r) }
    }
}
