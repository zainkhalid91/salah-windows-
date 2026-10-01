package salah.app.platform

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import salah.core.Platform
import java.awt.Window

/**
 * Dark mode plumbing Windows doesn't give a JVM app for free: reading the "Choose your app mode"
 * setting live, and asking DWM to draw the title bar dark so it matches the app.
 */
object WindowsTheme {
    private const val PERSONALIZE = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"

    /** Windows' app mode: true when "Choose your app mode" is Dark. Null off Windows or if unreadable. */
    fun appsUseDark(): Boolean? {
        if (!Platform.isWindows) return null
        return runCatching {
            Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, PERSONALIZE, "AppsUseLightTheme") == 0
        }.getOrNull()
    }

    /** Whether the taskbar (and notification area) is dark; decides the tray icon color. */
    fun taskbarIsDark(): Boolean {
        if (!Platform.isWindows) return true
        return runCatching {
            Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, PERSONALIZE, "SystemUsesLightTheme") == 0
        }.getOrDefault(true)
    }

    private interface Dwm : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int

        companion object {
            val INSTANCE: Dwm? by lazy { runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull() }
        }
    }

    /**
     * Paints the native title bar dark or light (DWMWA_USE_IMMERSIVE_DARK_MODE, Windows 10 20H1+ is
     * attribute 20; earlier builds used 19). Harmless where unsupported.
     */
    fun applyTitleBar(window: Window, dark: Boolean) {
        if (!Platform.isWindows) return
        val dwm = Dwm.INSTANCE ?: return
        runCatching {
            val hwnd = WinDef.HWND(Pointer(Native.getComponentID(window)))
            val value = IntByReference(if (dark) 1 else 0)
            if (dwm.DwmSetWindowAttribute(hwnd, 20, value, 4) != 0) dwm.DwmSetWindowAttribute(hwnd, 19, value, 4)
            // Nudge Windows to repaint the non-client area right away.
            window.size = window.size.let { java.awt.Dimension(it.width + 1, it.height) }
            window.size = window.size.let { java.awt.Dimension(it.width - 1, it.height) }
        }
    }
}

/**
 * The system's dark/light setting, kept live: Windows doesn't notify JVM apps when you switch app
 * mode, so the registry value is re-read every couple of seconds (a microsecond read, no process).
 */
@Composable
fun systemDarkTheme(): Boolean {
    val composeGuess = isSystemInDarkTheme()
    var dark by remember { mutableStateOf(WindowsTheme.appsUseDark() ?: composeGuess) }
    if (Platform.isWindows) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(2_000)
                val now = withContext(Dispatchers.IO) { WindowsTheme.appsUseDark() }
                if (now != null && now != dark) dark = now
            }
        }
    }
    return if (Platform.isWindows) dark else composeGuess
}
