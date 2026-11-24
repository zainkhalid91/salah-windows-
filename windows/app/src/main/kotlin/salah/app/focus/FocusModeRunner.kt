package salah.app.focus

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.jna.Native
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import salah.core.Platform
import salah.core.Prayer
import salah.core.SalahConfig
import salah.core.focusmode.BusyReason
import salah.core.focusmode.BusyState
import salah.core.focusmode.CardNote
import salah.core.focusmode.FocusModeContext
import salah.core.focusmode.FocusModePlanner
import salah.core.focusmode.PlannedAction
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** The full-screen card on screen now. */
data class FocusCard(val prayer: Prayer, val label: String, val prayerTime: Instant, val closesAt: Instant, val note: CardNote?)

/** A small "it's prayer time" nudge shown instead of the card while the user is busy. */
data class FocusNudge(val label: String, val reason: BusyReason, val cardLater: Boolean)

/**
 * Runs Focus Mode at each prayer time, using the same pure planner as macOS and Android.
 *
 * On Windows: the card is an always-on-top full-screen window, "pause media" pauses Salah's Quran
 * player, and busy means the microphone is in use (a call) or an app is full screen or presenting.
 * Windows has no public API to turn on Do Not Disturb, so that action isn't offered here.
 */
class FocusModeRunner(
    private val scope: CoroutineScope,
    private val config: () -> SalahConfig,
    private val pauseMedia: () -> Boolean,
    private val resumeMedia: () -> Unit,
) {
    var card by mutableStateOf<FocusCard?>(null)
        private set
    var nudge by mutableStateOf<FocusNudge?>(null)
        private set

    private val handled = LinkedHashSet<String>()
    private var pausedByUs = false
    private var autoClose: Job? = null
    private var waiting: Job? = null

    /** Called by the reminder loop when a prayer begins. */
    fun prayerStarted(prayer: Prayer, label: String, prayerTime: Instant, date: LocalDate, pendingNote: CardNote? = null) {
        val c = config()
        val key = "$date.${prayer.raw}"
        val busy = Busy.state()
        val actions = FocusModePlanner.decide(
            FocusModeContext(
                prayer = prayer, prayerTime = prayerTime, now = Instant.now(), settings = c.focusMode,
                busy = busy, isScreenLocked = false, alreadyHandled = key in handled, salahOwnsFocus = false,
                pendingNote = pendingNote,
            ),
        )
        if (actions.isEmpty()) return
        val deferred = actions.any { it is PlannedAction.DeferUntilFree || it is PlannedAction.DeferUntilUnlock }
        if (!deferred) {
            handled += key
            while (handled.size > 20) handled.remove(handled.first())
        }
        for (a in actions) when (a) {
            PlannedAction.PauseMedia -> if (pauseMedia()) pausedByUs = c.focusMode.pauseMedia.resumeOnDone
            PlannedAction.FocusOn -> Unit // not available on Windows
            is PlannedAction.ShowCard -> showCard(FocusCard(prayer, label, prayerTime, prayerTime.plus(Duration.ofMinutes(c.focusMode.card.autoCloseMinutes.toLong())), a.note))
            is PlannedAction.ShowNudge -> nudge = FocusNudge(label, a.reason, a.cardLater)
            PlannedAction.DeferUntilFree, PlannedAction.DeferUntilUnlock -> waitUntilFree(prayer, label, prayerTime, date, busy)
        }
    }

    /** Shows the card now, for the Focus Mode screen's preview. */
    fun preview(label: String) {
        val now = Instant.now()
        showCard(FocusCard(Prayer.ASR, label, now, now.plus(Duration.ofMinutes(config().focusMode.card.autoCloseMinutes.toLong())), null))
    }

    fun done() {
        card = null
        autoClose?.cancel()
        if (pausedByUs) resumeMedia()
        pausedByUs = false
    }

    fun dismissNudge() {
        nudge = null
    }

    private fun showCard(c: FocusCard) {
        nudge = null
        card = c
        autoClose?.cancel()
        autoClose = scope.launch {
            val wait = Duration.between(Instant.now(), c.closesAt).toMillis()
            if (wait > 0) delay(wait)
            done()
        }
    }

    /** Checks every 15 seconds; once free, replays the prayer with the "call ended" pill. */
    private fun waitUntilFree(prayer: Prayer, label: String, prayerTime: Instant, date: LocalDate, was: BusyState) {
        waiting?.cancel()
        waiting = scope.launch {
            val giveUp = prayerTime.plus(Duration.ofMinutes(config().focusMode.card.autoCloseMinutes.toLong()))
            while (Instant.now() < giveUp) {
                delay(15_000)
                if (Busy.state() == BusyState.FREE) {
                    val note = if (was == BusyState.ON_CALL) CardNote.CALL_ENDED else CardNote.FOCUS_ENDED
                    nudge = null
                    prayerStarted(prayer, label, prayerTime, date, pendingNote = note)
                    return@launch
                }
            }
            nudge = null
        }
    }
}

/** Whether the user is busy right now, from what Windows exposes without admin rights. */
object Busy {
    private interface Shell32 : StdCallLibrary {
        fun SHQueryUserNotificationState(state: IntByReference): Int

        companion object {
            val INSTANCE: Shell32? = runCatching { Native.load("shell32", Shell32::class.java, W32APIOptions.DEFAULT_OPTIONS) }.getOrNull()
        }
    }

    private const val MIC = "Software\\Microsoft\\Windows\\CurrentVersion\\CapabilityAccessManager\\ConsentStore\\microphone"

    fun state(): BusyState {
        if (!Platform.isWindows) return BusyState.FREE
        if (micInUse()) return BusyState.ON_CALL
        if (fullScreenOrPresenting()) return BusyState.FOCUS_ON
        return BusyState.FREE
    }

    /**
     * An app is using the microphone: Windows records a start time with no stop time under the
     * privacy store, the same data behind the microphone icon in the taskbar.
     */
    fun micInUse(): Boolean = runCatching {
        fun inUse(path: String): Boolean = runCatching {
            Advapi32Util.registryGetKeys(WinReg.HKEY_CURRENT_USER, path).any { app ->
                if (app == "NonPackaged") return@any inUse("$path\\$app")
                val key = "$path\\$app"
                val values = Advapi32Util.registryGetValues(WinReg.HKEY_CURRENT_USER, key)
                val start = (values["LastUsedTimeStart"] as? Number)?.toLong() ?: 0L
                val stop = (values["LastUsedTimeStop"] as? Number)?.toLong() ?: -1L
                start != 0L && stop == 0L
            }
        }.getOrDefault(false)
        inUse(MIC)
    }.getOrDefault(false)

    /** A full-screen app, game or presentation (QUNS_BUSY, D3D full screen, presentation mode). */
    fun fullScreenOrPresenting(): Boolean = runCatching {
        val ref = IntByReference()
        val shell = Shell32.INSTANCE ?: return false
        if (shell.SHQueryUserNotificationState(ref) != 0) return false
        ref.value in setOf(2, 3, 4)
    }.getOrDefault(false)
}
