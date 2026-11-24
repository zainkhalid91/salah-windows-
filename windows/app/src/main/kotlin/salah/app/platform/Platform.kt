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

/**
 * Keeps Salah up to date from GitHub Releases. CI publishes a release for every push to main, so
 * this checks at launch and every six hours, downloads a newer MSI in the background, verifies its
 * SHA-256 against GitHub's digest, and then either waits for "Restart to update" or, with automatic
 * installs on, installs while Salah sits in the notification area. The MSI keeps the same upgrade
 * code, so Windows Installer replaces the old version in place, per user, without an admin prompt.
 */
class Updater(private val scope: CoroutineScope, private val enabled: Boolean) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release) : State
        /** Downloaded and verified, ready to install. */
        data class Ready(val release: Release, val file: File) : State
        data class Failed(val message: String) : State
    }

    data class Release(val version: SemanticVersion, val pageUrl: String, val installerUrl: String?, val sha256: String?, val notes: String)

    val currentVersion: String = SalahInfo.VERSION
    var state by mutableStateOf<State>(State.Idle)
        private set
    private val prefs = Preferences.userRoot().node("salah")
    var autoCheck by mutableStateOf(prefs.getBoolean("autoCheckUpdates", true))
        private set
    /** Install a downloaded update by itself while the window is closed. */
    var autoInstall by mutableStateOf(prefs.getBoolean("autoInstallUpdates", true))
        private set
    /** The user closed the banner for this version. */
    var bannerDismissed by mutableStateOf<SemanticVersion?>(null)

    val availableRelease: Release?
        get() = when (val s = state) {
            is State.Available -> s.release
            is State.Ready -> s.release
            else -> null
        }

    fun changeAutoCheck(v: Boolean) {
        autoCheck = v
        runCatching { prefs.putBoolean("autoCheckUpdates", v) }
    }

    fun changeAutoInstall(v: Boolean) {
        autoInstall = v
        runCatching { prefs.putBoolean("autoInstallUpdates", v) }
    }

    fun start() {
        if (!enabled) return
        scope.launch {
            delay(5_000)
            while (true) {
                val last = prefs.getLong("lastUpdateCheck", 0)
                if (autoCheck && System.currentTimeMillis() - last > CHECK_EVERY_MS && state !is State.Ready) check(userInitiated = false)
                delay(15 * 60_000L)
            }
        }
    }

    fun check(userInitiated: Boolean) {
        if (state is State.Checking || state is State.Downloading) return
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
            // Fetch it straight away, so installing is one click (or none).
            val available = state as? State.Available
            if (available != null && (autoCheck || userInitiated) && Platform.isWindows && available.release.installerUrl != null) download(available.release)
        }
    }

    /** Downloads the MSI to the temp folder and checks its digest. */
    fun download(release: Release) {
        val url = release.installerUrl ?: return WindowsIntegration.openUrl(release.pageUrl)
        if (state is State.Downloading) return
        state = State.Downloading(release)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(System.getProperty("java.io.tmpdir"), "Salah-updates").apply { mkdirs() }
                    // Older downloads are useless once a newer one exists.
                    dir.listFiles()?.forEach { if (it.name != "Salah-${release.version}.msi") it.delete() }
                    val target = File(dir, "Salah-${release.version}.msi")
                    val part = File(dir, target.name + ".part")
                    val res = http.send(
                        HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "Salah-Windows/$currentVersion").timeout(Duration.ofMinutes(10)).build(),
                        HttpResponse.BodyHandlers.ofFile(part.toPath()),
                    )
                    check(res.statusCode() in 200..299) { "HTTP ${res.statusCode()}" }
                    val expected = release.sha256
                    if (expected != null) {
                        check(sha256(part).equals(expected, ignoreCase = true)) { "the download didn't match its checksum" }
                    }
                    Files.move(part.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    target
                }
            }
            state = result.fold({ State.Ready(release, it) }, { State.Failed(it.message ?: "download failed") })
        }
    }

    /**
     * Installs the downloaded MSI quietly (a small progress window, no questions), then starts the
     * new version. [background] starts it in the notification area, as after an automatic install.
     */
    fun install(quit: () -> Unit, background: Boolean = false) {
        when (val s = state) {
            is State.Available -> download(s.release)
            is State.Ready -> {
                if (!Platform.isWindows || !s.file.isFile) return WindowsIntegration.openUrl(s.release.pageUrl)
                val exe = WindowsIntegration.appExe
                val q = '"'
                val script = File(s.file.parentFile, "install.cmd")
                script.writeText(
                    buildString {
                        appendLine("@echo off")
                        // Let this copy of Salah exit and release its files first.
                        appendLine("ping -n 3 127.0.0.1 >nul")
                        appendLine("msiexec /i $q${s.file.absolutePath}$q /passive /norestart")
                        if (exe != null) appendLine("start $q$q $q$exe$q" + if (background) " --background" else "")
                    },
                )
                runCatching { ProcessBuilder("cmd.exe", "/c", script.absolutePath).start() }
                    .onSuccess { quit() }
                    .onFailure { state = State.Failed(it.message ?: "couldn't start the installer") }
            }
            else -> Unit
        }
    }

    /** Called about once a minute: installs a ready update when nobody is looking. */
    fun maybeInstallQuietly(windowVisible: Boolean, busy: Boolean, quit: () -> Unit) {
        if (autoInstall && !windowVisible && !busy && state is State.Ready && WindowsIntegration.appExe != null) install(quit, background = true)
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
        val url = msi?.let { (it["browser_download_url"] as? JsonPrimitive)?.contentOrNull }
        // GitHub reports "sha256:<hex>" for each uploaded asset.
        val sha = msi?.let { (it["digest"] as? JsonPrimitive)?.contentOrNull }?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
        val notes = (o["body"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return Release(version, page, url, sha, notes)
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val CHECK_EVERY_MS = 6 * 3600_000L
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

    /** [azan] plays the azan instead of [sound], for the start of the five prayers. */
    fun show(title: String, body: String, sound: ReminderSound, azan: Boolean = false, onOpen: () -> Unit) {
        toasts += Toast(nextId++, title, body, onOpen)
        while (toasts.size > 3) toasts.removeAt(0)
        if (azan && sound != ReminderSound.SILENT) playResource("/sounds/salah-azan.wav") else play(sound)
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
            ReminderSound.CHIME -> playResource("/sounds/salah-chime.wav")
        }
    }

    private fun playResource(path: String) = thread(isDaemon = true, name = "salah-sound") {
        runCatching {
            val stream = Notifier::class.java.getResourceAsStream(path)!!.buffered()
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
