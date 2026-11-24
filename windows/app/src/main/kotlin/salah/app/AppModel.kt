package salah.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import salah.app.platform.ConfigWatcher
import salah.app.platform.LocationProvider
import salah.app.platform.Notifier
import salah.app.platform.Updater
import salah.app.platform.WindowsIntegration
import salah.core.AppText
import salah.core.ConfigException
import salah.core.ConfigStore
import salah.core.DaySchedule
import salah.core.ExtraTime
import salah.core.IslamicAlertPlanner
import salah.core.NotificationPlanner
import salah.core.PlannedAlert
import salah.core.PlannedNotification
import salah.core.Prayer
import salah.core.PrayerClock
import salah.core.PrayerClockState
import salah.core.PrayerSchedule
import salah.core.SalahConfig
import salah.core.SavedLocation
import salah.core.TimeFormatting
import salah.core.localDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** App state: the shared config (mirrored to disk), navigation, the clock and reminder upkeep. */
class AppModel(
    val store: ConfigStore = ConfigStore(),
    /** False for offscreen snapshots: no timers, watchers, tray or notifications. */
    private val live: Boolean = true,
    fixedNow: Instant? = null,
) {
    enum class Tab(val title: String) {
        HOME("Home"), TODAY("Today"), QURAN("Quran"), CALENDAR("Calendar"), SCHEDULE("Schedule"), REMINDERS("Reminders"), SETTINGS("Settings"),
    }

    /** Screens opened from a tab (with a back arrow) instead of having their own. */
    enum class More(val title: String) { FOCUS_MODE("Focus Mode"), KIND_WORDS("Kind Words"), DUA("Du'a"), ABOUT("About") }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var tab by mutableStateOf(Tab.HOME)
    /** A screen shown over the current tab, with a back arrow. */
    var more by mutableStateOf<More?>(null)
    var showCustomizeHome by mutableStateOf(false)
    var showQueue by mutableStateOf(false)
    var config by mutableStateOf(SalahConfig.DEFAULT)
        private set
    /** Set when the config file exists but can't be read. The dashboard shows a reset action. */
    var configError by mutableStateOf<String?>(null)
        private set
    /** A date the Today screen is previewing instead of today. */
    var previewDate by mutableStateOf<LocalDate?>(null)
    /** A prayer whose details replace the next-prayer display. */
    var detailPrayer by mutableStateOf<Prayer?>(null)
    /** A sunnah time whose details replace the next-prayer display. */
    var detailExtra by mutableStateOf<ExtraTime?>(null)
    var showLocationSheet by mutableStateOf(false)
    val schedule = salah.app.ui.ScheduleState()
    var scheduled by mutableStateOf<List<PlannedNotification>>(emptyList())
        private set
    var loginItemMessage by mutableStateOf<String?>(null)
        private set

    /** The app's notion of "now". Ticks each second while a Salah window is on screen, else each minute. */
    var now by mutableStateOf(fixedNow ?: Instant.now())
        private set

    var windowVisible by mutableStateOf(true)
        private set
    var trayPanelOpen by mutableStateOf(false)
        private set

    val locationProvider = LocationProvider(scope) { setLocation(it) }
    val updater = Updater(scope, enabled = live)
    val notifier = Notifier()
    val quran = salah.app.quran.QuranPlayer(scope, { config.quran }, { body -> update { it.copy(quran = body(it.quran)) } }, live)
    val focus = salah.app.focus.FocusModeRunner(scope, { config }, pauseMedia = { quran.pauseForPrayer() }, resumeMedia = { quran.resume() })

    private var watcher: ConfigWatcher? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val replanSignal = Channel<Unit>(Channel.CONFLATED)
    private var plannedDay: LocalDate? = null
    private val delivered = LinkedHashSet<String>()
    private var lastPlan: List<PlannedNotification> = emptyList()
    private var lastAlerts: List<PlannedAlert> = emptyList()
    private var lastMoments: List<PrayerMoment> = emptyList()

    /** The start of one of the five prayers: Focus Mode and the Quran player act on it. */
    private data class PrayerMoment(val id: String, val prayer: Prayer, val time: Instant, val date: LocalDate, val label: String)
    private var jobs = mutableListOf<Job>()

    init {
        loadFromDisk()
        if (live) {
            watcher = ConfigWatcher(store.path) { scope.launch { loadFromDisk() } }
            jobs += scope.launch { tickLoop() }
            jobs += scope.launch { reminderLoop() }
            applyLaunchAtLogin()
            updater.start()
        }
    }

    // region Derived

    val location: SavedLocation? get() = config.location
    val zone: ZoneId get() = config.location?.zone ?: ZoneId.systemDefault()

    fun clockState(at: Instant = now): PrayerClockState? {
        val loc = config.location?.takeIf { it.isValid } ?: return null
        return PrayerClock.state(at, loc, config.calculation, config.display.nowWindowMinutes)
    }

    fun schedule(date: LocalDate): DaySchedule? {
        val loc = config.location?.takeIf { it.isValid } ?: return null
        return PrayerSchedule.forDate(date, loc, config.calculation)
    }

    fun today(at: Instant = now): LocalDate = localDate(at, zone)

    fun clock(t: Instant): String = TimeFormatting.clock(t, zone, config.display.use24HourClock, lang = config.display.lang)

    /** Switches tab, closing any screen opened over the old one. */
    fun open(t: Tab) {
        tab = t
        more = null
        if (t != Tab.TODAY) clearDetail()
    }

    fun open(m: More) {
        more = m
    }

    fun clearDetail() {
        detailPrayer = null
        detailExtra = null
    }

    /** "☾ Asr · 12m", "☾ 4:05 PM" or "Salah": the tray tooltip, like the macOS menu bar label. */
    val trayLabel: String
        get() {
            val st = clockState() ?: return "Salah"
            val d = config.display
            val lang = d.lang
            st.nowPrayer?.let { return "☾ ${st.today.label(it, d.jumuahRelabel, lang)} · ${AppText.t(lang, "now")}" }
            val n = st.next ?: return "Salah"
            return when (d.menuBarStyle) {
                salah.core.MenuBarStyle.NAME_AND_COUNTDOWN -> "☾ ${n.label(d.jumuahRelabel, lang)} · ${TimeFormatting.short(n.secondsRemaining(now))}"
                salah.core.MenuBarStyle.TIME_ONLY -> "☾ ${n.label(d.jumuahRelabel, lang)} ${clock(n.time)}"
                salah.core.MenuBarStyle.ICON_ONLY -> "Salah"
            }
        }

    val reminderStatus: String
        get() {
            val r = config.reminders
            if (!r.enabled) return "Reminders off"
            if (r.isPaused(Instant.now())) return "Reminders paused"
            return "Reminders on"
        }

    // endregion

    // region Config

    /** Applies a change, saves it atomically, and re-plans reminders. */
    fun update(body: (SalahConfig) -> SalahConfig) {
        val c = body(config)
        if (c == config) return
        val old = config
        config = c
        if (live) {
            runCatching { store.save(c) }.onFailure { System.err.println("Save failed: ${it.message}") }
        }
        didChange(old)
    }

    fun setLocation(loc: SavedLocation) {
        update { it.copy(location = loc) }
        showLocationSheet = false
        previewDate = null
        clearDetail()
    }

    fun resetToDefaults() {
        val c = SalahConfig.DEFAULT
        runCatching { store.save(c) }
        configError = null
        val old = config
        config = c
        didChange(old)
    }

    /** Reloads after an external write (the CLI) or at launch. */
    fun loadFromDisk() {
        try {
            val c = store.load()
            configError = null
            if (c == config) return
            val old = config
            config = c
            didChange(old)
        } catch (e: ConfigException) {
            configError = e.message
        }
    }

    private fun didChange(old: SalahConfig) {
        if (!live) return
        if (old.launchAtLogin != config.launchAtLogin) applyLaunchAtLogin()
        replanSignal.trySend(Unit)
    }

    // endregion

    // region Clock

    fun windowVisibilityChanged(v: Boolean) {
        if (windowVisible == v) return
        windowVisible = v
        wake.trySend(Unit)
        if (!v) clearDetail()
    }

    fun openTrayPanel(v: Boolean) {
        trayPanelOpen = v
        wake.trySend(Unit)
    }

    private suspend fun tickLoop() {
        var lastWall = System.currentTimeMillis()
        var lastMono = System.nanoTime()
        while (true) {
            now = Instant.now()
            // Wake from sleep, or a clock or time zone change: re-plan straight away.
            val wall = System.currentTimeMillis()
            val mono = System.nanoTime()
            val drift = (wall - lastWall) - (mono - lastMono) / 1_000_000
            if (kotlin.math.abs(drift) > 5_000) replanSignal.trySend(Unit)
            lastWall = wall
            lastMono = mono
            checkRollover()
            val ms = now.toEpochMilli()
            val wait = if (windowVisible || trayPanelOpen) 1000 - ms % 1000 + 20 else 60_000 - ms % 60_000 + 50
            withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    /** Local midnight in the location's zone: swap the day and top up the window. */
    private fun checkRollover() {
        val day = localDate(now, zone)
        if (plannedDay != null && day != plannedDay) {
            plannedDay = day
            replanSignal.trySend(Unit)
        }
    }

    // endregion

    // region Reminders

    /**
     * Keeps the 3-day plan and delivers each reminder at its time. Windows has no per-app
     * scheduled-notification store like macOS, so the tray app owns the timer; it wakes at the next
     * fire time, on any config change, and at least once a minute (to survive sleep).
     */
    private suspend fun reminderLoop() {
        while (true) {
            val nowI = Instant.now()
            // Deliver anything due since the last plan, unless we slept through it by >5 minutes.
            for (p in lastPlan) {
                if (p.fireDate <= nowI && p.id !in delivered) {
                    delivered += p.id
                    if (nowI.epochSecond - p.fireDate.epochSecond <= 300) {
                        val sound = if (p.silent) salah.core.ReminderSound.SILENT else config.reminders.sound
                        notifier.show(p.title, p.body, sound, NotificationPlanner.playsAzan(p, config.reminders)) { showMainWindow() }
                    }
                }
            }
            for (a in lastAlerts) {
                if (a.fireDate <= nowI && a.id !in delivered) {
                    delivered += a.id
                    if (nowI.epochSecond - a.fireDate.epochSecond <= 300) notifier.show(a.title, a.body, config.reminders.sound) { showMainWindow() }
                }
            }
            for (m in lastMoments) {
                if (m.time <= nowI && m.id !in delivered) {
                    delivered += m.id
                    if (nowI.epochSecond - m.time.epochSecond <= 600) prayerStarted(m)
                }
            }
            while (delivered.size > 200) delivered.remove(delivered.first())
            updater.maybeInstallQuietly(windowVisible || trayPanelOpen, busy = quran.isPlaying || focus.card != null) { onQuitCompletely() }
            val plan = withContext(Dispatchers.Default) { NotificationPlanner.plan(nowI, config) }
            // Islamic date alerts (new month, special days), in the app's language.
            val alerts = withContext(Dispatchers.Default) { IslamicAlertPlanner.plan(nowI, config, config.display.lang.locale()) }
            val moments = withContext(Dispatchers.Default) { prayerMoments(nowI) }
            lastPlan = plan
            lastAlerts = alerts
            lastMoments = moments
            scheduled = plan
            plannedDay = localDate(nowI, zone)
            val next = listOfNotNull(plan.firstOrNull()?.fireDate, alerts.firstOrNull()?.fireDate, moments.firstOrNull { it.time > nowI }?.time).minOrNull()
            val untilNext = next?.let { it.toEpochMilli() - System.currentTimeMillis() } ?: Long.MAX_VALUE
            val wait = untilNext.coerceIn(0, 60_000)
            withTimeoutOrNull(wait) {
                replanSignal.receive()
                delay(300) // debounce bursts of edits
            }
        }
    }

    /** The next two days' prayer starts, whether or not a reminder is set for them. */
    private fun prayerMoments(nowI: Instant): List<PrayerMoment> {
        val loc = config.location?.takeIf { it.isValid } ?: return emptyList()
        val start = localDate(nowI, loc.zone)
        val lang = config.display.lang
        return PrayerSchedule.range(start.minusDays(1), 3, loc, config.calculation).flatMap { day ->
            Prayer.prayers.mapNotNull { p ->
                val t = day.time(p) ?: return@mapNotNull null
                PrayerMoment("moment.${day.date}.${p.raw}", p, t, day.date, day.label(p, config.display.jumuahRelabel, lang))
            }
        }.filter { it.time.epochSecond > nowI.epochSecond - 600 }.sortedBy { it.time }
    }

    private fun prayerStarted(m: PrayerMoment) {
        // Focus Mode's own pause wins when it's on for this prayer, so Done can resume.
        val focusPauses = config.focusMode.let { it.enabled && m.prayer in it.prayers && it.pauseMedia.enabled }
        if (config.quran.pauseAtPrayerTime && !focusPauses) quran.pauseForPrayer()
        focus.prayerStarted(m.prayer, m.label, m.time, m.date)
    }

    fun sendTestNotification() {
        val st = clockState(Instant.now())
        val next = st?.next
        val lang = config.display.lang
        val label = next?.label(config.display.jumuahRelabel, lang) ?: AppText.t(lang, "Asr")
        val lead = next?.let { config.reminders.reminder(it.prayer).leadMinutes } ?: 10
        val title = NotificationPlanner.title(label, if (lead == 0) 10 else lead, lang)
        val loc = location
        val body = if (next != null && loc != null) NotificationPlanner.body(next.time, loc, config.display.use24HourClock, lang) else AppText.t(lang, "Test notification")
        notifier.show(title, body, config.reminders.sound) { showMainWindow() }
    }

    fun pause(until: Instant?) = update { it.copy(reminders = it.reminders.withPausedUntil(until)) }

    // endregion

    // region Windows

    /** Set by the UI layer: brings the main window to the front. */
    var onShowMainWindow: () -> Unit = {}
    var onQuitCompletely: () -> Unit = {}

    fun showMainWindow() = onShowMainWindow()

    private fun applyLaunchAtLogin() {
        scope.launch(Dispatchers.IO) {
            val msg = WindowsIntegration.setLaunchAtLogin(config.launchAtLogin)
            withContext(Dispatchers.Main) { loginItemMessage = msg }
        }
    }

    fun dispose() {
        quran.dispose()
        watcher?.close()
        notifier.dispose()
        scope.cancel()
    }

    // endregion
}
