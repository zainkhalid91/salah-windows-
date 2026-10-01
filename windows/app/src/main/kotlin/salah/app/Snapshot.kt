package salah.app

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import salah.app.ui.RootView
import salah.app.ui.SalahTheme
import salah.core.CalculationSettings
import salah.core.ConfigStore
import salah.core.MethodID
import salah.core.Prayer
import salah.core.SalahConfig
import salah.core.SavedLocation
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Renders every screen offscreen to PNG, like the macOS app's SnapshotRenderer. Used to check the
 * design without a display (CI, headless machines):
 *
 *     gradlew :app:run --args="--snapshot build/snapshots"
 */
object Snapshot {
    private val singapore = SavedLocation("Singapore", 1.3521, 103.8198, "Asia/Singapore", "SG", SavedLocation.Source.AUTOMATIC)

    fun renderAll(outDir: File, scale: Float = 2f) {
        outDir.mkdirs()
        val next = OffsetDateTime.parse("2026-10-01T17:18:12+08:00").toInstant()
        val now = OffsetDateTime.parse("2026-10-01T16:09:30+08:00").toInstant()
        val config = SalahConfig(location = singapore, calculation = CalculationSettings(method = MethodID.SINGAPORE))
        for (dark in listOf(false, true)) {
            val sfx = if (dark) "dark" else "light"
            shot(outDir, "today-next-$sfx", config, next, dark, 940, 560, scale)
            shot(outDir, "today-now-$sfx", config, now, dark, 940, 560, scale)
            shot(outDir, "today-narrow-$sfx", config, next, dark, 640, 920, scale)
            shot(outDir, "today-detail-$sfx", config, next, dark, 940, 560, scale) { it.detailPrayer = Prayer.ASR }
            shot(outDir, "today-preview-$sfx", config, next, dark, 940, 560, scale) { it.previewDate = LocalDate.of(2026, 10, 9) }
            shot(outDir, "welcome-$sfx", SalahConfig(), next, dark, 940, 560, scale)
            shot(outDir, "schedule-$sfx", config, next, dark, 940, 640, scale) { it.tab = AppModel.Tab.SCHEDULE }
            shot(outDir, "reminders-$sfx", config, next, dark, 940, 760, scale) { it.tab = AppModel.Tab.REMINDERS }
            shot(outDir, "settings-$sfx", config, next, dark, 940, 900, scale) { it.tab = AppModel.Tab.SETTINGS }
            shot(outDir, "about-$sfx", config, next, dark, 940, 700, scale) { it.tab = AppModel.Tab.ABOUT }
            shot(outDir, "location-$sfx", config, next, dark, 940, 560, scale) { it.showLocationSheet = true }
        }
        val twelve = config.copy(display = config.display.copy(use24HourClock = false))
        shot(outDir, "today-12h-light", twelve, next, false, 940, 560, scale)
        val tromso = config.copy(location = SavedLocation("Tromsø", 69.6492, 18.9553, "Europe/Oslo", "NO"), calculation = CalculationSettings())
        shot(outDir, "today-polar-light", tromso, OffsetDateTime.parse("2026-06-21T12:00:00+02:00").toInstant(), false, 940, 560, scale)
        shot(outDir, "tray-panel-light", config, next, false, 300, TRAY_PANEL_HEIGHT, scale, tray = true)
        shot(outDir, "tray-panel-dark", config, next, true, 300, TRAY_PANEL_HEIGHT, scale, tray = true)
        shot(outDir, "toast-light", config, next, false, 380, TOAST_HEIGHT, scale, toast = true)
        shot(outDir, "toast-dark", config, next, true, 380, TOAST_HEIGHT, scale, toast = true)
    }

    private fun shot(
        dir: File, name: String, config: SalahConfig, now: Instant, dark: Boolean, w: Int, h: Int, scale: Float,
        tray: Boolean = false, toast: Boolean = false, setup: (AppModel) -> Unit = {},
    ) {
        val tmp = Files.createTempDirectory("salah-snap").resolve("config.json")
        ConfigStore(tmp).save(config)
        val model = AppModel(ConfigStore(tmp), live = false, fixedNow = now)
        setup(model)
        ImageComposeScene((w * scale).toInt(), (h * scale).toInt(), Density(scale)) {
            SalahTheme(dark) {
                when {
                    tray -> salah.app.ui.TrayPanel(model)
                    toast -> salah.app.ui.ToastCard("Asr in 10 minutes", "16:03 · Singapore", onOpen = {}, onClose = {})
                    else -> RootView(model)
                }
            }
        }.use { scene ->
            scene.render(0)
            // Second frame lets animations settle into their initial state.
            val image = scene.render(500_000_000)
            val bytes = image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
            File(dir, "$name.png").writeBytes(bytes)
        }
        println("wrote $name.png")
    }
}
