package salah.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
            Box(Modifier.fillMaxSize()) {
                val error = model.configError
                if (error != null) {
                    InvalidConfigView(model, error)
                } else {
                    when (model.tab) {
                        AppModel.Tab.TODAY -> TodayScreen(model)
                        AppModel.Tab.CALENDAR -> CalendarScreen(model)
                        AppModel.Tab.SCHEDULE -> ScheduleScreen(model)
                        AppModel.Tab.REMINDERS -> RemindersScreen(model)
                        AppModel.Tab.SETTINGS -> SettingsScreen(model)
                        AppModel.Tab.ABOUT -> AboutScreen(model)
                    }
                }
            }
        }
        if (model.showLocationSheet) LocationSheet(model)
        if (model.config.display.language == null && model.configError == null) {
            LanguagePicker { picked -> model.update { it.copy(display = it.display.copy(language = picked)) } }
        }
    }
}

@Composable
private fun TabBar(model: AppModel) {
    val c = palette
    Box(Modifier.fillMaxWidth().height(48.dp).background(c.chrome).padding(horizontal = 14.dp)) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("☾", size = 15f, weight = FontWeight.SemiBold, color = c.accent)
            Label("Salah", size = 13f, weight = FontWeight.SemiBold)
        }
        Box(Modifier.align(Alignment.Center)) {
            PillPicker(AppModel.Tab.entries.map { it to tr(it.title) }, model.tab, { model.tab = it; if (it != AppModel.Tab.TODAY) model.clearDetail() })
        }
        Label(tr(model.reminderStatus), size = 12f, color = c.secondary, modifier = Modifier.align(Alignment.CenterEnd))
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
