package salah.app.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import salah.core.LocationSearch
import salah.core.Platform
import salah.core.SalahInfo
import salah.core.ReminderSound
import salah.core.SavedLocation
import salah.core.SemanticVersion
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import javax.sound.sampled.AudioSystem
import kotlin.concurrent.thread

/** Watches config.json's directory and calls [onChange] (debounced) when the CLI or another tool writes it. */
class ConfigWatcher(private val file: Path, private val onChange: () -> Unit) {
    private val service = runCatching { FileSystems.getDefault().newWatchService() }.getOrNull()
    @Volatile private var closed = false

    init {
        val dir = file.toAbsolutePath().parent
        val ws = service
        if (ws != null && dir != null) {
            runCatching { Files.createDirectories(dir) }
            runCatching {
                dir.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE)
            }
            thread(isDaemon = true, name = "salah-config-watcher") {
                while (!closed) {
                    val key = runCatching { ws.take() }.getOrNull() ?: break
                    val relevant = key.pollEvents().any { (it.context() as? Path)?.fileName?.toString() == file.fileName.toString() }
                    key.reset()
                    if (relevant) {
                        // Atomic saves arrive as several events; let them settle.
                        Thread.sleep(150)
                        while (true) {
                            val more = ws.poll(100, TimeUnit.MILLISECONDS) ?: break
                            more.pollEvents(); more.reset()
                        }
                        onChange()
                    }
                }
            }
        }
    }

    fun close() {
        closed = true
        runCatching { service?.close() }
    }
}

/**
 * "Use my location" via Windows Location Services (System.Device.Location through Windows
 * PowerShell, so no native code ships with the app). Coordinates never leave the PC except to
 * look up the place name and time zone.
 */
class LocationProvider(private val scope: CoroutineScope, private val onLocation: (SavedLocation) -> Unit) {
    var isLocating by mutableStateOf(false)
        private set
    var isDenied by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun requestLocation() {
        if (isLocating) return
        isLocating = true
        errorMessage = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { locate() }
            isLocating = false
            result.fold(
                onSuccess = { onLocation(it) },
                onFailure = { errorMessage = it.message },
            )
        }
    }

    private fun locate(): Result<SavedLocation> = runCatching {
        if (!Platform.isWindows) error("Use my location is available on Windows. Enter your city instead.")
        val script = """
            Add-Type -AssemblyName System.Device
            ${'$'}w = New-Object System.Device.Location.GeoCoordinateWatcher([System.Device.Location.GeoPositionAccuracy]::Default)
            ${'$'}null = ${'$'}w.TryStart(${'$'}false, [TimeSpan]::FromSeconds(10))
            ${'$'}s = [DateTime]::Now
            while ((${'$'}w.Status -ne 'Ready') -and (${'$'}w.Permission -ne 'Denied') -and (([DateTime]::Now - ${'$'}s).TotalSeconds -lt 20)) { Start-Sleep -Milliseconds 200 }
            if (${'$'}w.Permission -eq 'Denied') { 'DENIED'; exit }
            ${'$'}c = ${'$'}w.Position.Location
            if (${'$'}c.IsUnknown) { 'UNKNOWN' } else { ${'$'}c.Latitude.ToString([cultureinfo]::InvariantCulture) + ',' + ${'$'}c.Longitude.ToString([cultureinfo]::InvariantCulture) }
        """.trimIndent()
        val out = WindowsIntegration.powershell(script, timeoutSeconds = 30)?.trim()
        when {
            out == null -> error("Couldn't reach Windows Location Services.")
            out.contains("DENIED") -> {
                isDenied = true
                error("Location access is off for desktop apps.")
            }
            out.contains("UNKNOWN") || !out.contains(",") -> error("Windows couldn't determine your location. Enter your city instead.")
        }
        isDenied = false
        val (lat, lon) = out!!.lines().last().split(",").map { it.trim().toDouble() }
        LocationSearch.reverse(lat, lon, SavedLocation.Source.AUTOMATIC)
            ?: SavedLocation(
                "Current location", lat, lon, ZoneId.systemDefault().id, null, SavedLocation.Source.AUTOMATIC,
            )
    }
}

/** Checks GitHub Releases for a newer Windows installer, at most once a day when enabled. */
class Updater(private val scope: CoroutineScope, private val enabled: Boolean) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release) : State
        data class Failed(val message: String) : State
    }

    data class Release(val version: SemanticVersion, val pageUrl: String, val installerUrl: String?)

    val currentVersion: String = SalahInfo.VERSION
    var state by mutableStateOf<State>(State.Idle)
        private set
    private val prefs = Preferences.userRoot().node("salah")
    var autoCheck by mutableStateOf(prefs.getBoolean("autoCheckUpdates", true))
        private set

    val availableRelease: Release? get() = (state as? State.Available)?.release

    fun changeAutoCheck(v: Boolean) {
        autoCheck = v
        runCatching { prefs.putBoolean("autoCheckUpdates", v) }
    }

    fun start() {
        if (!enabled) return
        scope.launch {
            delay(5_000)
            while (true) {
                val last = prefs.getLong("lastUpdateCheck", 0)
                if (autoCheck && System.currentTimeMillis() - last > 24 * 3600_000L) check(userInitiated = false)
                delay(3600_000L)
            }
        }
    }

    fun check(userInitiated: Boolean) {
        if (state is State.Checking) return
        state = State.Checking
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { fetchLatest() } }
            runCatching { prefs.putLong("lastUpdateCheck", System.currentTimeMillis()) }
            state = result.fold(
                onSuccess = { r ->
                    val current = SemanticVersion.parse(currentVersion)
                    if (r != null && current != null && r.version > current) State.Available(r) else State.UpToDate
                },
                onFailure = { if (userInitiated) State.Failed(it.message ?: "network error") else State.Idle },
            )
        }
    }

    /** Downloads the MSI and hands it to Windows Installer, then quits so files can be replaced. */
    fun install(release: Release, quit: () -> Unit) {
        val url = release.installerUrl
        if (url == null || !Platform.isWindows) {
            WindowsIntegration.openUrl(release.pageUrl)
            return
        }
        state = State.Downloading(release)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(System.getProperty("java.io.tmpdir"), "Salah-${release.version}.msi")
                    val res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).build(), HttpResponse.BodyHandlers.ofFile(target.toPath()))
                    check(res.statusCode() in 200..299) { "HTTP ${res.statusCode()}" }
                    ProcessBuilder("msiexec", "/i", target.absolutePath).start()
                }
            }
            ok.onSuccess { quit() }.onFailure { state = State.Failed(it.message ?: "download failed") }
        }
    }

    private val http by lazy { HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build() }

    private fun fetchLatest(): Release? {
        val req = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/${SalahInfo.repository}/releases/latest"))
            .header("Accept", "application/vnd.github+json").header("User-Agent", "Salah-Windows/$currentVersion")
            .timeout(Duration.ofSeconds(15)).build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() == 404) return null
        check(res.statusCode() in 200..299) { "GitHub answered ${res.statusCode()}" }
        val o = Json.parseToJsonElement(res.body()) as JsonObject
        val tag = (o["tag_name"] as? JsonPrimitive)?.contentOrNull ?: return null
        val version = SemanticVersion.parse(tag.removePrefix("windows-")) ?: return null
        val page = (o["html_url"] as? JsonPrimitive)?.contentOrNull ?: SalahInfo.releasesUrl
        val msi = (o["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull?.endsWith(".msi", ignoreCase = true) == true }
            ?.let { (it["browser_download_url"] as? JsonPrimitive)?.contentOrNull }
        return Release(version, page, msi)
    }
}

/**
 * Salah's reminders: a designed toast in the corner of the screen (see ToastHost) plus the chosen
 * sound. Windows' own notification API needs a registered COM activator, which a pure JVM app
 * can't provide, so Salah draws its own and controls the sound exactly.
 */
class Notifier {
    data class Toast(val id: Long, val title: String, val body: String, val onOpen: () -> Unit)

    val toasts = mutableStateListOf<Toast>()
    private var nextId = 0L

    fun show(title: String, body: String, sound: ReminderSound, onOpen: () -> Unit) {
        toasts += Toast(nextId++, title, body, onOpen)
        while (toasts.size > 3) toasts.removeAt(0)
        play(sound)
    }

    fun dismiss(t: Toast) {
        toasts.remove(t)
    }

    fun dispose() = toasts.clear()

    private fun play(sound: ReminderSound) {
        when (sound) {
            ReminderSound.SILENT -> Unit
            ReminderSound.SYSTEM_DEFAULT -> {
                val r = Toolkit.getDefaultToolkit().getDesktopProperty("win.sound.default") as? Runnable
                if (r != null) r.run() else Toolkit.getDefaultToolkit().beep()
            }
            ReminderSound.CHIME -> thread(isDaemon = true, name = "salah-chime") {
                runCatching {
                    val stream = Notifier::class.java.getResourceAsStream("/sounds/salah-chime.wav")!!.buffered()
                    AudioSystem.getAudioInputStream(stream).use { audio ->
                        val clip = AudioSystem.getClip()
                        clip.open(audio)
                        clip.start()
                        Thread.sleep((clip.microsecondLength / 1000) + 200)
                        clip.close()
                    }
                }.onFailure { Toolkit.getDefaultToolkit().beep() }
            }
        }
    }
}

/** Registry, PATH and shell helpers. No-ops (with a message) when not running the installed Windows app. */
object WindowsIntegration {
    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"

    /** The installed Salah.exe, set by the jpackage launcher. Null when run from Gradle. */
    val appExe: String? get() = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }

    /** Folder holding salah.cmd inside the installation. */
    val cliDir: File?
        get() = System.getProperty("compose.application.resources.dir")?.let { File(it) }?.takeIf { File(it, "salah.cmd").isFile }

    /** Adds or removes the HKCU Run entry. Returns a hint for the Settings row, or null when all is well. */
    fun setLaunchAtLogin(enabled: Boolean): String? {
        if (!Platform.isWindows) return "Available in the Windows app."
        val exe = appExe ?: return "Available in the installed app."
        return if (enabled) {
            val ok = run("reg", "add", RUN_KEY, "/v", "Salah", "/t", "REG_SZ", "/d", "\"$exe\" --background", "/f")
            if (ok) null else "Couldn't add Salah to startup apps."
        } else {
            run("reg", "delete", RUN_KEY, "/v", "Salah", "/f")
            null
        }
    }

    /** Adds the folder containing salah.cmd to the user's PATH. Returns a message for the user. */
    fun installCommandLineTool(): Pair<Boolean, String> {
        if (!Platform.isWindows) return false to "The command line tool is installed with the Windows app."
        val dir = cliDir ?: return false to "The command line tool isn't in this build of Salah. Install Salah with the MSI from GitHub Releases."
        val script = """
            ${'$'}d = '${dir.absolutePath.replace("'", "''")}'
            ${'$'}p = [Environment]::GetEnvironmentVariable('Path', 'User')
            if (-not ${'$'}p) { ${'$'}p = '' }
            if ((${'$'}p -split ';') -notcontains ${'$'}d) { [Environment]::SetEnvironmentVariable('Path', (${'$'}p.TrimEnd(';') + ';' + ${'$'}d).TrimStart(';'), 'User') }
            'OK'
        """.trimIndent()
        val out = powershell(script)
        return if (out?.contains("OK") == true) {
            true to "Open a new terminal and run `salah`. Added to your PATH: ${dir.absolutePath}"
        } else {
            false to "Couldn't update your PATH. Add this folder manually: ${dir.absolutePath}"
        }
    }

    fun powershell(script: String, timeoutSeconds: Long = 20): String? = runCatching {
        val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", "-")
            .redirectErrorStream(true).start()
        p.outputStream.bufferedWriter().use { it.write(script) }
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        out
    }.getOrNull()

    private fun run(vararg cmd: String): Boolean = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    fun openUrl(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
            .onFailure { if (Platform.isWindows) run("rundll32", "url.dll,FileProtocolHandler", url) }
    }

    /** Opens Windows Settings at a page, e.g. "privacy-location". */
    fun openSettings(page: String) = openUrl("ms-settings:$page")

    fun showInExplorer(path: Path) {
        if (Platform.isWindows) run("explorer.exe", "/select,", path.toAbsolutePath().toString())
        else runCatching { Desktop.getDesktop().open(path.toAbsolutePath().parent.toFile()) }
    }

    fun copy(text: String) = Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)

    /** Native save dialog. Returns the chosen file, or null. */
    fun saveFile(suggestedName: String, extension: String): File? {
        val d = java.awt.FileDialog(null as java.awt.Frame?, "Export", java.awt.FileDialog.SAVE)
        d.file = suggestedName
        d.isVisible = true
        val name = d.file ?: return null
        val f = File(d.directory, name)
        return if (f.name.lowercase(Locale.ROOT).endsWith(".$extension")) f else File(f.path + ".$extension")
    }
}
