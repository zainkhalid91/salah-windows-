package salah.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import salah.app.platform.WindowsTheme
import salah.app.platform.systemDarkTheme
import salah.app.ui.Localized
import salah.app.ui.RootView
import salah.app.ui.SalahTheme
import salah.app.ui.ScheduleSpan
import salah.app.ui.ToastCard
import salah.app.ui.TrayPanel
import salah.core.AppInstance
import salah.core.AppText
import salah.core.ThemeSetting
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // Any startup failure is written to crash.log and shown, instead of Windows' bare
    // "Failed to launch JVM". --selftest (used by CI) starts the real app and exits after 10 s.
    val selfTest = "--selftest" in args
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        CrashLog.write(e, "uncaught on ${t.name}")
        if (selfTest) Runtime.getRuntime().halt(1)
    }
    if (selfTest) {
        Thread {
            Thread.sleep(10_000)
            println("selftest: app ran for 10 s without errors")
            AppInstance.release()
            Runtime.getRuntime().halt(0)
        }.apply { isDaemon = true }.start()
    }
    try {
        run(args)
    } catch (e: Throwable) {
        CrashLog.write(e, "startup")
        if (!selfTest) CrashLog.showDialog(e)
        exitProcess(1)
    }
}

private fun run(args: Array<String>) {
    val snapshot = args.indexOf("--snapshot")
    if (snapshot >= 0) {
        Snapshot.renderAll(File(args.getOrNull(snapshot + 1) ?: "snapshots"))
        exitProcess(0)
    }
    val startHidden = "--background" in args

    // One Salah at a time: a second launch (Start menu, startup entry) brings the first one forward.
    var showRequest: () -> Unit = {}
    if (!AppInstance.claim { SwingUtilities.invokeLater { showRequest() } }) exitProcess(0)

    application(exitProcessOnExit = true) {
        val model = remember { AppModel() }
        val trayEnabled = model.config.display.showMenuBarExtra && SystemTray.isSupported()
        var windowOpen by remember { mutableStateOf(!startHidden || !trayEnabled) }
        val windowState = rememberWindowState(size = DpSize(940.dp, 560.dp), position = WindowPosition(Alignment.Center))
        var bringToFront by remember { mutableStateOf(0) }

        fun quitCompletely() {
            AppInstance.release()
            model.dispose()
            exitApplication()
        }
        model.onShowMainWindow = {
            windowOpen = true
            if (windowState.isMinimized) windowState.isMinimized = false
            bringToFront++
        }
        model.onQuitCompletely = ::quitCompletely
        showRequest = { model.showMainWindow() }

        val dark = when (model.config.display.theme) {
            ThemeSetting.SYSTEM -> systemDarkTheme()
            ThemeSetting.LIGHT -> false
            ThemeSetting.DARK -> true
        }
        val visibleNow = windowOpen && !windowState.isMinimized
        LaunchedEffect(visibleNow) { model.windowVisibilityChanged(visibleNow) }

        Window(
            onCloseRequest = {
                // Closing keeps Salah in the notification area so reminders keep coming. Without the
                // tray icon, closing quits, so the app never ends up running invisibly.
                if (trayEnabled) windowOpen = false else quitCompletely()
            },
            visible = windowOpen,
            state = windowState,
            title = "Salah",
            icon = painterResource("icons/salah.png"),
            onPreviewKeyEvent = { e -> handleKey(e, model, close = { if (trayEnabled) windowOpen = false else quitCompletely() }, quit = ::quitCompletely) },
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(640, 420) }
            LaunchedEffect(bringToFront) {
                if (bringToFront > 0) {
                    if (window.extendedState and Frame.ICONIFIED != 0) window.extendedState = Frame.NORMAL
                    window.toFront()
                    window.requestFocus()
                }
            }
            // Match the native title bar to the app, so dark mode is dark edge to edge.
            LaunchedEffect(dark) { WindowsTheme.applyTitleBar(window, dark) }
            SalahTheme(dark) { Localized(model.config.display.lang) { RootView(model) } }
        }

        if (trayEnabled) NotificationAreaIcon(model, dark, onQuit = ::quitCompletely)
        ToastHost(model, dark)
    }
}

/** Ctrl+1–5 switch screens, Ctrl+, opens Settings, Ctrl+W/Ctrl+Q close to the tray, Ctrl+Shift+Q quits. */
private fun handleKey(e: KeyEvent, model: AppModel, close: () -> Unit, quit: () -> Unit): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    if (e.isCtrlPressed) {
        val tabs = AppModel.Tab.entries
        val index = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five).indexOf(e.key)
        when {
            index in tabs.indices -> { model.tab = tabs[index]; return true }
            e.key == Key.Comma -> { model.tab = AppModel.Tab.SETTINGS; return true }
            e.key == Key.Q && e.isShiftPressed -> { quit(); return true }
            e.key == Key.Q || e.key == Key.W -> { close(); return true }
        }
        return false
    }
    if (model.showLocationSheet) return false
    when (model.tab) {
        AppModel.Tab.TODAY -> when (e.key) {
            Key.Escape -> if (model.detailPrayer != null || model.previewDate != null) {
                model.detailPrayer = null
                model.previewDate = null
                return true
            }
            Key.T -> if (model.previewDate != null) { model.previewDate = null; return true }
            else -> Unit
        }
        AppModel.Tab.SCHEDULE -> when (e.key) {
            Key.DirectionLeft -> { model.schedule.step(-1, model.today()); return true }
            Key.DirectionRight -> { model.schedule.step(1, model.today()); return true }
            Key.T -> { model.schedule.anchor = null; return true }
            Key.D -> { model.schedule.span = ScheduleSpan.DAY; return true }
            Key.W -> { model.schedule.span = ScheduleSpan.WEEK; return true }
            Key.M -> { model.schedule.span = ScheduleSpan.MONTH; return true }
            else -> Unit
        }
        else -> Unit
    }
    return false
}

/**
 * The ☾ icon in the Windows notification area. Hovering shows "☾ Asr · 12m"; a left click opens
 * the Salah panel; a double click opens the window; a right click shows the menu.
 */
@Composable
private fun ApplicationScope.NotificationAreaIcon(model: AppModel, dark: Boolean, onQuit: () -> Unit) {
    var panelAnchor by remember { mutableStateOf<java.awt.Point?>(null) }
    var closedAt by remember { mutableStateOf(0L) }
    val remindersItem = remember { MenuItem("Turn reminders off") }
    val openItem = remember { MenuItem("Open Salah") }
    val quitItem = remember { MenuItem("Quit Salah completely") }

    val icon = remember {
        val darkTaskbar = WindowsTheme.taskbarIsDark()
        TrayIcon(moonImage(if (darkTaskbar) Color(0xFFFFFF) else Color(0x1A1A1A)), "Salah").apply { isImageAutoSize = true }
    }
    DisposableEffect(icon) {
        val menu = PopupMenu()
        menu.add(openItem.apply { addActionListener { SwingUtilities.invokeLater { model.showMainWindow() } } })
        remindersItem.addActionListener {
            SwingUtilities.invokeLater { model.update { it.copy(reminders = it.reminders.copy(enabled = !it.reminders.enabled)) } }
        }
        menu.add(remindersItem)
        menu.addSeparator()
        menu.add(quitItem.apply { addActionListener { SwingUtilities.invokeLater(onQuit) } })
        icon.popupMenu = menu
        icon.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                if (e.clickCount >= 2) {
                    SwingUtilities.invokeLater { model.openTrayPanel(false); model.showMainWindow() }
                    return
                }
                val p = e.locationOnScreen
                SwingUtilities.invokeLater {
                    // The click that dismissed the panel shouldn't immediately reopen it.
                    if (System.currentTimeMillis() - closedAt < 400) return@invokeLater
                    panelAnchor = p
                    model.openTrayPanel(true)
                }
            }
        })
        runCatching { SystemTray.getSystemTray().add(icon) }
        onDispose { runCatching { SystemTray.getSystemTray().remove(icon) } }
    }
    // The tooltip is the Windows stand-in for the macOS menu bar label.
    val label = model.trayLabel
    val remindersOn = model.config.reminders.enabled
    val lang = model.config.display.lang
    LaunchedEffect(label, remindersOn, lang) {
        icon.toolTip = label
        remindersItem.label = AppText.t(lang, if (remindersOn) "Turn reminders off" else "Turn reminders on")
        openItem.label = AppText.t(lang, "Open Salah")
        quitItem.label = AppText.t(lang, "Quit Salah completely")
    }

    val anchor = panelAnchor
    if (model.trayPanelOpen && anchor != null) {
        val w = 300
        val h = TRAY_PANEL_HEIGHT
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val x = (anchor.x - w / 2).coerceIn(bounds.x + 8, bounds.x + bounds.width - w - 8)
        val y = if (anchor.y > bounds.y + bounds.height / 2) bounds.y + bounds.height - h - 8 else bounds.y + 8
        val state = rememberWindowState(size = DpSize(w.dp, h.dp), position = WindowPosition(x.dp, y.dp))
        val close = {
            closedAt = System.currentTimeMillis()
            model.openTrayPanel(false)
        }
        Window(
            onCloseRequest = close, state = state, title = "Salah", undecorated = true, transparent = true,
            resizable = false, alwaysOnTop = true, icon = painterResource("icons/salah.png"),
            onPreviewKeyEvent = { if (it.key == Key.Escape) { close(); true } else false },
        ) {
            DisposableEffect(window) {
                val l = object : WindowFocusListener {
                    override fun windowGainedFocus(e: WindowEvent?) = Unit
                    override fun windowLostFocus(e: WindowEvent?) = close()
                }
                window.addWindowFocusListener(l)
                window.toFront()
                window.requestFocus()
                onDispose { window.removeWindowFocusListener(l) }
            }
            SalahTheme(dark) {
                Localized(model.config.display.lang) {
                    TrayPanel(model, onOpen = { close(); model.showMainWindow() }, onQuit = onQuit)
                }
            }
        }
    }
}

internal const val TRAY_PANEL_HEIGHT = 352
internal const val TOAST_HEIGHT = 96

/** Reminder toasts, stacked in the bottom-right corner above the taskbar. Each dismisses itself after 10 s. */
@Composable
private fun ToastHost(model: AppModel, dark: Boolean) {
    val toasts = model.notifier.toasts.toList()
    val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    toasts.asReversed().forEachIndexed { i, t ->
        val w = 380
        val h = TOAST_HEIGHT
        val x = bounds.x + bounds.width - w - 16
        val y = bounds.y + bounds.height - (h + 10) * (i + 1) - 6
        key(t.id) {
            val state = rememberWindowState(size = DpSize(w.dp, h.dp), position = WindowPosition(x.dp, y.dp))
            LaunchedEffect(y) { state.position = WindowPosition(x.dp, y.dp) }
            LaunchedEffect(t.id) {
                delay(10_000)
                model.notifier.dismiss(t)
            }
            Window(
                onCloseRequest = { model.notifier.dismiss(t) }, state = state, title = t.title, undecorated = true, transparent = true,
                resizable = false, alwaysOnTop = true, focusable = false, icon = painterResource("icons/salah.png"),
            ) {
                SalahTheme(dark) {
                    Localized(model.config.display.lang) {
                        ToastCard(t.title, t.body, onOpen = { model.notifier.dismiss(t); t.onOpen() }, onClose = { model.notifier.dismiss(t) })
                    }
                }
            }
        }
    }
}

/** A crescent moon for the notification area, drawn at 64 px so Windows can scale it crisply. */
private fun moonImage(color: Color): BufferedImage {
    val s = 64
    val img = BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = color
    g.fill(Ellipse2D.Double(6.0, 6.0, 52.0, 52.0))
    g.composite = AlphaComposite.Clear
    g.fill(Ellipse2D.Double(22.0, -2.0, 50.0, 50.0))
    g.dispose()
    return img
}
