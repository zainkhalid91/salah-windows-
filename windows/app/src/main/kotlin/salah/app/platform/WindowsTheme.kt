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
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import salah.core.Platform

/**
 * Dark mode plumbing Windows doesn't give a JVM app for free: reading the "Choose your app mode"
 * setting live, and DWM window attributes.
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

    internal fun dwmAttribute(hwnd: WinDef.HWND, attribute: Int, value: Int): Boolean =
        Dwm.INSTANCE?.DwmSetWindowAttribute(hwnd, attribute, IntByReference(value), 4) == 0
}

/**
 * Window styling for the undecorated main window: the minimize and maximize styles so the taskbar
 * button and Win+arrow keys still work, rounded corners on Windows 11, and maximized bounds that
 * stop at the taskbar instead of covering it. Harmless where unsupported.
 */
object WindowFrame {
    private const val WS_SYSMENU = 0x00080000
    private const val WS_MINIMIZEBOX = 0x00020000
    private const val WS_MAXIMIZEBOX = 0x00010000
    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    private const val DWMWCP_ROUND = 2

    fun style(window: java.awt.Frame) {
        keepMaximizedOffTaskbar(window)
        window.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentMoved(e: java.awt.event.ComponentEvent) = keepMaximizedOffTaskbar(window)
        })
        if (!Platform.isWindows) return
        runCatching {
            val hwnd = WinDef.HWND(Pointer(Native.getComponentID(window)))
            val u = User32.INSTANCE
            val style = u.GetWindowLong(hwnd, WinUser.GWL_STYLE)
            u.SetWindowLong(hwnd, WinUser.GWL_STYLE, style or WS_SYSMENU or WS_MINIMIZEBOX or WS_MAXIMIZEBOX)
            WindowsTheme.dwmAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_ROUND)
        }
    }

    /** The work area of the screen the window is on, so maximize stops at the taskbar. */
    private fun keepMaximizedOffTaskbar(window: java.awt.Frame) {
        val gc = window.graphicsConfiguration ?: return
        val screen = gc.bounds
        val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
        // Relative to the screen's own origin, as Windows expects for WM_GETMINMAXINFO.
        window.maximizedBounds = java.awt.Rectangle(
            insets.left, insets.top,
            screen.width - insets.left - insets.right,
            screen.height - insets.top - insets.bottom,
        )
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
