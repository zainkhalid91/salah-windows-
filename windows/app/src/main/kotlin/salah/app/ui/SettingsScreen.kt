package salah.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import salah.app.AppModel
import salah.app.platform.Updater
import salah.app.platform.WindowsIntegration
import salah.core.AppLanguage
import salah.core.CalculationSettings
import salah.core.HighLatitudeSetting
import salah.core.MadhabSetting
import salah.core.MenuBarStyle
import salah.core.MethodID
import salah.core.Prayer
import salah.core.SalahInfo
import salah.core.SavedLocation
import salah.core.ThemeSetting
import java.util.Locale

@Composable
fun SettingsScreen(model: AppModel) {
    val c = model.config
    val pal = palette
    fun calc(body: (CalculationSettings) -> CalculationSettings) = model.update { it.copy(calculation = body(it.calculation)) }
    fun display(body: (salah.core.DisplaySettings) -> salah.core.DisplaySettings) = model.update { it.copy(display = body(it.display)) }
    var tzPicker by remember { mutableStateOf(false) }

    Pane(tr("Settings"), tr("Times update as you change these.")) {
        SettingsGroup {
            row {
                SettingsRow(tr("Language")) {
                    PillPicker(AppLanguage.entries.map { it to it.nativeName }, c.display.lang, { v -> display { it.copy(language = v) } })
                }
            }
            row {
                SettingsRow(tr("Location"), locationHint(c.location)) { SecondaryButton(tr("Change…")) { model.showLocationSheet = true } }
            }
            val loc = c.location
            if (loc != null) row {
                SettingsRow(tr("Time zone"), tr("Follows the location. Times are always shown in this zone.")) {
                    SecondaryButton("${loc.timeZone}  ⌄") { tzPicker = true }
                }
            }
            row {
                val options = listOf<Pair<MethodID?, String>>(null to tr("Automatic ({0})", tr(MethodID.automatic(c.location).displayName))) +
                    MethodID.entries.map { it to if (it == MethodID.CUSTOM) tr("Custom angles") else tr(it.displayName) }
                SettingsRow(tr("Calculation method")) {
                    DropdownPicker(options, c.calculation.method, { v -> calc { it.copy(method = v) } }, dividerAfter = 0, maxWidth = 320.dp)
                }
            }
            if (c.calculation.method == MethodID.CUSTOM) row {
                SettingsRow(tr("Custom angles"), tr("e.g. Kemenag Indonesia uses Fajr 20°, Isha 18°")) {
                    AngleStepper(tr("Fajr"), c.calculation.customFajrAngle) { v -> calc { it.copy(customFajrAngle = v) } }
                    AngleStepper(tr("Isha"), c.calculation.customIshaAngle) { v -> calc { it.copy(customIshaAngle = v) } }
                }
            }
            row {
                SettingsRow(tr("Asr"), tr("Hanafi places Asr later in the afternoon")) {
                    DropdownPicker(MadhabSetting.entries.map { it to tr(it.displayName) }, c.calculation.madhab, { v -> calc { it.copy(madhab = v) } })
                }
            }
            row {
                SettingsRow(tr("High-latitude rule"), tr("How Fajr and Isha are estimated when twilight never fully ends")) {
                    DropdownPicker(
                        listOf<Pair<HighLatitudeSetting?, String>>(null to tr("Automatic")) + HighLatitudeSetting.entries.map { it to tr(it.displayName) },
                        c.calculation.highLatitudeRule, { v -> calc { it.copy(highLatitudeRule = v) } }, dividerAfter = 0,
                    )
                }
            }
        }

        Label(tr("Offsets"), size = 13f, weight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        SettingsGroup {
            for (p in Prayer.entries) row {
                val m = c.calculation.offset(p)
                SettingsRow(tr(p.displayName)) {
                    Stepper(tr("{0} min", if (m == 0) "0" else "${if (m > 0) "+" else ""}$m"), m > -60, m < 60, { calc { it.withOffset(m - 1, p) } }, { calc { it.withOffset(m + 1, p) } })
                }
            }
        }

        SettingsGroup {
            row {
                val h = c.display.hijriAdjustment
                SettingsRow(tr("Hijri date adjustment"), tr("For local moon sighting")) {
                    Stepper(
                        if (h == 0) tr("None") else tr(if (kotlin.math.abs(h) == 1) "{0} day" else "{0} days", "${if (h > 0) "+" else ""}$h"),
                        h > -2, h < 2, { display { it.copy(hijriAdjustment = h - 1) } }, { display { it.copy(hijriAdjustment = h + 1) } },
                    )
                }
            }
            row {
                SettingsRow(tr("Show Jumuah on Fridays"), tr("Relabels Dhuhr on Fridays")) {
                    SalahSwitch(c.display.jumuahRelabel) { v -> display { it.copy(jumuahRelabel = v) } }
                }
            }
            row {
                SettingsRow(tr("Sunnah prayers"), tr("Tahajjud, Ishraq, Duha, Zawal, Awwabin and midnight on the timeline")) {
                    SalahSwitch(c.display.showSunnahTimes) { v -> display { it.copy(showSunnahTimes = v) } }
                }
            }
            row {
                val w = c.display.nowWindowMinutes
                SettingsRow(tr("NOW display"), tr("How long the display shows NOW after a prayer starts")) {
                    Stepper(if (w == 0) tr("Off") else tr("{0} min", w), w > 0, w < 60, { display { it.copy(nowWindowMinutes = (w - 5).coerceAtLeast(0)) } }, { display { it.copy(nowWindowMinutes = (w + 5).coerceAtMost(60)) } })
                }
            }
        }

        SettingsGroup {
            row {
                SettingsRow(tr("Clock")) {
                    PillPicker(listOf(true to tr("24-hour"), false to tr("12-hour")), c.display.use24HourClock, { v -> display { it.copy(use24HourClock = v) } })
                }
            }
            row {
                SettingsRow(tr("Appearance")) {
                    PillPicker(listOf(ThemeSetting.SYSTEM to tr("System"), ThemeSetting.LIGHT to tr("Light"), ThemeSetting.DARK to tr("Dark")), c.display.theme, { v -> display { it.copy(theme = v) } })
                }
            }
            row {
                SettingsRow(tr("Start with Windows"), model.loginItemMessage ?: tr("Keeps reminders coming; Salah starts quietly in the notification area")) {
                    SalahSwitch(c.launchAtLogin) { v -> model.update { it.copy(launchAtLogin = v) } }
                }
            }
            row {
                SettingsRow(tr("Notification area"), tr("Shows the next prayer when you point at the ☾ icon")) {
                    DropdownPicker(MenuBarStyle.entries.map { it to tr(it.displayName) }, c.display.menuBarStyle, { v -> display { it.copy(menuBarStyle = v) } }, enabled = c.display.showMenuBarExtra)
                    SalahSwitch(c.display.showMenuBarExtra) { v -> display { it.copy(showMenuBarExtra = v) } }
                }
            }
        }

        Label(
            tr("Calculated times are approximations. Your local authority may differ by a few minutes; adjust per prayer if needed."),
            color = pal.secondary,
        )
    }
    val tzLoc = c.location
    if (tzPicker && tzLoc != null) {
        TimeZonePicker(tzLoc.timeZone, onDismiss = { tzPicker = false }) { tz ->
            model.update { cfg -> cfg.copy(location = cfg.location?.copy(timeZone = tz)) }
            tzPicker = false
        }
    }
}

@Composable
private fun locationHint(loc: SavedLocation?): String {
    loc ?: return tr("Not set")
    return "${loc.name} · ${loc.coordinateDescription} · ${tr(if (loc.source == SavedLocation.Source.AUTOMATIC) "from Windows location" else "entered manually")}"
}

@Composable
private fun AngleStepper(label: String, value: Double, onChange: (Double) -> Unit) {
    val text = "$label ${if (Math.rint(value) == value) String.format(Locale.ROOT, "%.0f", value) else String.format(Locale.ROOT, "%.1f", value)}°"
    Stepper(text, value > 0, value < 30, { onChange((value - 0.5).coerceAtLeast(0.0)) }, { onChange((value + 0.5).coerceAtMost(30.0)) })
}

@Composable
fun AboutScreen(model: AppModel) {
    val pal = palette
    var cliMessage by remember { mutableStateOf<String?>(null) }
    Pane(tr("About Salah"), tr("Version {0} for Windows", model.updater.currentVersion)) {
        UpdatesGroup(model)
        SettingsGroup {
            row {
                SettingsRow(tr("Command line tool"), cliMessage ?: tr("Use `salah` in Terminal or PowerShell. Shares settings with this app.")) {
                    SecondaryButton(tr("Install…")) { cliMessage = WindowsIntegration.installCommandLineTool().second }
                }
            }
        }
        SettingsGroup {
            row { About(tr("Calculation"), tr("Prayer times are calculated by {0}, matching the macOS app minute for minute. Calculated times are approximations; your local authority may differ by several minutes, which is what the per-prayer offsets are for.", SalahInfo.CALCULATION_LIBRARY)) }
            row { About(tr("Hijri date"), tr("Umm al-Qura calendar from Java's java.time, with a manual ±2 day adjustment for local moon sighting.")) }
            row { About(tr("Font"), tr("Doto by The Doto Project Authors, licensed under the SIL Open Font License 1.1. The license is included with the app (fonts/OFL.txt).")) }
        }
        SettingsGroup {
            row { About(tr("Privacy"), tr("Everything stays on this PC. No accounts, analytics or sync. Your location is requested only when you choose “Use my location”. Update checks ask GitHub's public API for the latest release and send nothing about you.")) }
            row { About(tr("City search"), tr("City search uses Open-Meteo's geocoding service, and naming a coordinate uses BigDataCloud, so search queries and a coordinate lookup are sent to them.")) }
        }
        SettingsGroup {
            row {
                SettingsRow(tr("Original macOS app")) { LinkButton("github.com/primayudantra/salah", size = 13f) { WindowsIntegration.openUrl(SalahInfo.DOCUMENTATION_URL) } }
            }
            row {
                SettingsRow(tr("Windows releases")) { LinkButton(SalahInfo.releasesUrl.removePrefix("https://"), size = 13f) { WindowsIntegration.openUrl(SalahInfo.releasesUrl) } }
            }
            row {
                SettingsRow(tr("Config file"), model.store.path.toString()) { SecondaryButton(tr("Show in Explorer")) { WindowsIntegration.showInExplorer(model.store.path) } }
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label(tr("Close the window and Salah keeps running in the notification area so reminders arrive. Use “Quit completely” from the ☾ icon's menu to stop it."), size = 12f, color = pal.secondary)
        }
    }
}

@Composable
private fun UpdatesGroup(model: AppModel) {
    val u = model.updater
    val busy = u.state is Updater.State.Checking || u.state is Updater.State.Downloading
    val status = when (val s = u.state) {
        Updater.State.Idle -> tr("You have version {0}", u.currentVersion)
        Updater.State.Checking -> tr("Checking…")
        Updater.State.UpToDate -> tr("Up to date ({0})", u.currentVersion)
        is Updater.State.Available -> tr("Version {0} is available", s.release.version)
        is Updater.State.Downloading -> tr("Downloading {0}…", s.release.version)
        is Updater.State.Failed -> tr("Last check failed: {0}", s.message)
    }
    SettingsGroup {
        row {
            SettingsRow(tr("Updates"), status) {
                val r = u.availableRelease
                if (r != null && !busy) AccentButton(tr("Install {0}…", r.version)) { u.install(r) { model.onQuitCompletely() } }
                else SecondaryButton(tr("Check now"), enabled = !busy) { u.check(userInitiated = true) }
            }
        }
        row {
            SettingsRow(tr("Check for updates automatically"), tr("Once a day, from GitHub Releases")) { SalahSwitch(u.autoCheck) { u.changeAutoCheck(it) } }
        }
    }
}

@Composable
private fun About(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(title, weight = FontWeight.Medium)
        Label(text, color = palette.secondary)
    }
}
