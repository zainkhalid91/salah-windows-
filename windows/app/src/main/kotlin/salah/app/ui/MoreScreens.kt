package salah.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import salah.app.AppModel
import salah.app.platform.WindowsIntegration
import salah.core.Prayer
import salah.core.QuietHours
import salah.core.ReminderSound
import salah.core.focusmode.CallBusyMode
import salah.core.focusmode.FocusModeCardSettings
import salah.core.focusmode.FocusModeSettings
import salah.core.kindwords.DuaMoment
import salah.core.kindwords.KindWordCategory
import salah.core.kindwords.KindWordsCadence
import salah.core.kindwords.KindWordsLibrary
import salah.core.kindwords.KindWordsSettings
import java.time.LocalDate
import java.util.Locale

/** The screen opened over a tab, with a back link. */
@Composable
fun MoreScreen(model: AppModel, more: AppModel.More) {
    when (more) {
        AppModel.More.FOCUS_MODE -> FocusModeScreen(model)
        AppModel.More.KIND_WORDS -> KindWordsScreen(model)
        AppModel.More.DUA -> DuaScreen(model)
        AppModel.More.ABOUT -> AboutScreen(model, onBack = { model.more = null })
    }
}

@Composable
private fun FocusModeScreen(model: AppModel) {
    val f = model.config.focusMode
    fun set(body: (FocusModeSettings) -> FocusModeSettings) = model.update { it.copy(focusMode = body(it.focusMode)) }

    Pane(
        tr("Focus Mode"),
        tr("At prayer time, Salah can show a calm full-screen card and pause the Quran player. On a call it only sends a small nudge."),
        onBack = { model.more = null },
    ) {
        SettingsGroup {
            row {
                SettingsRow(tr("Focus Mode"), tr("Off by default")) { SalahSwitch(f.enabled) { on -> set { it.copy(enabled = on) } } }
            }
            row {
                SettingsRow(tr("Prayers")) {
                    for (p in Prayer.prayers) SalahCheckbox(tr(p.displayName), p in f.prayers, enabled = f.enabled) { on -> set { it.withPrayer(p, on) } }
                }
            }
        }

        Label(tr("Card"), size = 13f, weight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        SettingsGroup {
            row {
                SettingsRow(tr("Full-screen card"), tr("Covers the screen until you press Done")) {
                    SalahSwitch(f.card.enabled, enabled = f.enabled) { on -> set { it.copy(card = it.card.copy(enabled = on)) } }
                }
            }
            row {
                SettingsRow(tr("Closes by itself after")) {
                    PillPicker(
                        FocusModeCardSettings.ALLOWED_AUTO_CLOSE_MINUTES.map { it to tr("{0} min", it) },
                        f.card.autoCloseMinutes,
                        { m -> set { it.copy(card = it.card.copy(autoCloseMinutes = m)) } },
                    )
                }
            }
            row {
                val asr = tr("Asr")
                SettingsRow(tr("Preview the card"), tr("Press Done or Esc to close it")) {
                    SecondaryButton(tr("Preview")) { model.focus.preview(asr) }
                }
            }
        }

        Label(tr("Audio"), size = 13f, weight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        SettingsGroup {
            row {
                SettingsRow(tr("Pause the Quran player"), tr("At prayer time, if it's playing")) {
                    SalahSwitch(f.pauseMedia.enabled, enabled = f.enabled) { on -> set { it.copy(pauseMedia = it.pauseMedia.copy(enabled = on)) } }
                }
            }
            row {
                SettingsRow(tr("Resume when I'm done")) {
                    SalahSwitch(f.pauseMedia.resumeOnDone, enabled = f.enabled && f.pauseMedia.enabled) { on -> set { it.copy(pauseMedia = it.pauseMedia.copy(resumeOnDone = on)) } }
                }
            }
        }

        Label(tr("When I'm busy"), size = 13f, weight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        SettingsGroup {
            row {
                SettingsRow(tr("On a call"), tr("When an app is using the microphone")) {
                    PillPicker(CallBusyMode.entries.map { it to tr(it.displayName) }, f.whenBusy.onCall, { m -> set { it.copy(whenBusy = it.whenBusy.copy(onCall = m)) } })
                }
            }
            row {
                SettingsRow(tr("Full screen counts as busy"), tr("A game, video or presentation fills the screen")) {
                    SalahSwitch(f.whenBusy.focusIsBusy, enabled = f.enabled) { on -> set { it.copy(whenBusy = it.whenBusy.copy(focusIsBusy = on)) } }
                }
            }
        }
        Label(tr("Windows doesn't let apps turn on Do Not Disturb, so Salah doesn't change it."), size = 12f, color = palette.secondary)
    }
}

@Composable
private fun KindWordsScreen(model: AppModel) {
    val k = model.config.kindWords
    val pal = palette
    fun set(body: (KindWordsSettings) -> KindWordsSettings) = model.update { it.copy(kindWords = body(it.kindWords)) }

    Pane(
        tr("Kind Words"),
        tr("Gentle verses, hadith and du'as through the day, never close to a prayer time. They follow your quiet hours and pause."),
        onBack = { model.more = null },
    ) {
        SettingsGroup {
            row { SettingsRow(tr("Kind Words")) { SalahSwitch(k.enabled) { on -> set { it.copy(enabled = on) } } } }
            row {
                SettingsRow(tr("Daytime words"), tr("Never within {0} minutes of a prayer", salah.core.kindwords.KindWordsPlanner.PRAYER_GAP_MINUTES)) {
                    PillPicker(KindWordsCadence.entries.map { it to tr(it.displayName) }, k.cadence, { v -> set { it.copy(cadence = v) } })
                }
            }
            row {
                SettingsRow(tr("Before prayer"), tr("A verse or hadith with the early reminder")) { SalahSwitch(k.beforePrayer, enabled = k.enabled) { on -> set { it.copy(beforePrayer = on) } } }
            }
            row {
                SettingsRow(tr("After prayer"), tr("\"Have you prayed?\" after each prayer")) {
                    if (k.afterPrayer) {
                        PillPicker(
                            KindWordsSettings.ALLOWED_AFTER_PRAYER_MINUTES.map { it to tr("{0} min", it) },
                            k.afterPrayerMinutes,
                            { m -> set { it.copy(afterPrayerMinutes = m) } },
                        )
                    }
                    SalahSwitch(k.afterPrayer, enabled = k.enabled) { on -> set { it.copy(afterPrayer = on) } }
                }
            }
            row {
                SettingsRow(tr("Friday"), tr("Al-Kahf in the morning, the last hour after Asr")) { SalahSwitch(k.friday, enabled = k.enabled) { on -> set { it.copy(friday = on) } } }
            }
            row {
                SettingsRow(tr("Work du'as"), tr("At the start and end of the work day, Monday to Friday")) { SalahSwitch(k.workDuas, enabled = k.enabled) { on -> set { it.copy(workDuas = on) } } }
            }
            if (k.workDuas) {
                row { SettingsRow(tr("Work starts")) { HalfHourStepper(k.workStart) { v -> set { it.copy(workStart = v) } } } }
                row { SettingsRow(tr("Work ends")) { HalfHourStepper(k.workEnd) { v -> set { it.copy(workEnd = v) } } } }
            }
            row {
                SettingsRow(tr("Silent"), tr("No sound, whatever your reminder sound is")) { SalahSwitch(k.silent, enabled = k.enabled) { on -> set { it.copy(silent = on) } } }
            }
            row {
                SettingsRow(tr("Send a preview")) {
                    SecondaryButton(tr("Send")) {
                        val w = KindWordsLibrary.BUNDLED.kindWordOfTheDay(LocalDate.now()) ?: return@SecondaryButton
                        model.notifier.show(AppTextT(model, "A kind word"), w.notificationBody, if (k.silent) ReminderSound.SILENT else model.config.reminders.sound) { model.showMainWindow() }
                    }
                }
            }
        }
        for (cat in KindWordCategory.entries) {
            val words = KindWordsLibrary.BUNDLED.words(cat)
            if (words.isEmpty()) continue
            Label(tr(cat.displayName), size = 13f, weight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
            SettingsGroup {
                for (w in words) row {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Label(w.text)
                        Label("— ${w.source}", size = 12f, color = pal.secondary)
                    }
                }
            }
        }
        Label(tr("Kind Words and du'as are in English."), size = 12f, color = pal.secondary)
    }
}

/** "09:00  − +" in 30 minute steps. */
@Composable
private fun HalfHourStepper(value: String, onChange: (String) -> Unit) {
    val m = QuietHours.minutes(value) ?: (9 * 60)
    fun fmt(x: Int) = String.format(Locale.ROOT, "%02d:%02d", x / 60, x % 60)
    Stepper(value, true, true, { onChange(fmt((m - 30 + 1440) % 1440)) }, { onChange(fmt((m + 30) % 1440)) })
}

@Composable
private fun DuaScreen(model: AppModel) {
    val pal = palette
    Pane(tr("Du'a"), tr("Du'as for the moments of the day."), onBack = { model.more = null }) {
        for (moment in DuaMoment.entries) {
            val duas = KindWordsLibrary.BUNDLED.duas(moment)
            if (duas.isEmpty()) continue
            Label(tr(moment.displayName), size = 13f, weight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
            SettingsGroup {
                for (d in duas) row {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Label(d.label, weight = FontWeight.SemiBold)
                        Label(d.english)
                        if (d.transliteration.isNotBlank()) Label(d.transliteration, size = 12f, color = pal.secondary)
                        Label(d.source, size = 12f, color = pal.secondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 4.dp)) {
                            LinkButton(tr("Copy")) {
                                WindowsIntegration.copy(listOf(d.english, d.transliteration, "— ${d.source}").filter { it.isNotBlank() }.joinToString("\n"))
                            }
                            LinkButton(tr("Notify me")) { model.notifier.show(d.label, d.notificationBody, ReminderSound.SILENT) { model.showMainWindow() } }
                        }
                    }
                }
            }
        }
    }
}

/** Translation outside a composable's [tr], in the app's current language. */
internal fun AppTextT(model: AppModel, en: String, vararg args: Any): String = salah.core.AppText.t(model.config.display.lang, en, *args)
