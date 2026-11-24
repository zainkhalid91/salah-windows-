package salah.app.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import salah.app.AppModel
import salah.core.AppText
import salah.core.DaySchedule
import salah.core.HijriDate
import salah.core.NextPrayerResolver
import salah.core.Prayer
import salah.core.PrayerSchedule
import salah.core.SalahConfig
import salah.core.SavedLocation
import salah.core.SunnahFast
import salah.core.TimeFormatting
import salah.core.home.HomeSettings
import salah.core.home.HomeWidget
import salah.core.isFriday
import salah.core.kindwords.KindWordsCadence
import salah.core.kindwords.KindWordsLibrary
import salah.core.kindwords.KindWordsPlanner
import salah.core.quran.QueueSource
import salah.core.quran.QuranCatalog
import java.time.Instant
import java.time.LocalDate

/**
 * Home: a greeting, the moment's main card (the next prayer, or Friday's last hour after Asr), then
 * the widgets the user picked, in their order. Wide windows put the widgets in two columns.
 */
@Composable
fun HomeScreen(model: AppModel) {
    val c = palette
    val config = model.config
    val now = model.now
    val location = config.location?.takeIf { it.isValid }
    val scroll = rememberScrollState()

    Box(Modifier.fillMaxSize().background(c.background)) {
        BoxWithConstraints(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 30.dp, vertical = 26.dp)) {
            val twoColumns = maxWidth >= 760.dp
            Column(Modifier.widthIn(max = 980.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (location == null) {
                    Label(tr("Assalamu alaikum."), size = 24f, weight = FontWeight.SemiBold)
                    HomeCard(onClick = { model.showLocationSheet = true }) {
                        Label(tr("Set your location to see prayer times."), size = 15f)
                        Label(tr("Set location…"), color = c.accent, modifier = Modifier.padding(top = 6.dp))
                    }
                    return@Column
                }
                val zone = location.zone
                val today = model.today()
                val schedule = PrayerSchedule.forDate(today, location, config.calculation)
                val lang = config.display.lang

                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Label(tr(greeting(now.atZone(zone).hour)), size = 24f, weight = FontWeight.SemiBold)
                        val hijri = runCatching { HijriDate.of(today, config.display.hijriAdjustment) }.getOrNull()
                        Label(listOfNotNull(AppText.longDate(today, lang), hijri?.let { AppText.hijri(it, lang) }).joinToString(" · "), color = c.secondary)
                    }
                    LinkButton(tr("Customize Home"), size = 13f) { model.showCustomizeHome = true }
                }

                FeaturedCard(config, location, schedule, now)

                val cards: List<@Composable () -> Unit> = config.home.widgets.mapNotNull { w ->
                    when (w) {
                        HomeWidget.NEXT_PRAYER -> null // the featured card above
                        HomeWidget.PRAYER_TIMES -> ({ PrayerTimesCard(config, location, schedule, now) { model.open(AppModel.Tab.TODAY) } })
                        HomeWidget.DUA_OF_THE_DAY -> ({ DuaCard(today) { model.open(AppModel.More.DUA) } })
                        HomeWidget.QURAN -> ({ QuranCard(model, now, location) })
                        HomeWidget.SUNNAH_FAST -> if (fastReasons(config, schedule, now).isNotEmpty()) ({ FastCard(config, location, schedule, now) }) else null
                        HomeWidget.KIND_WORD -> ({ KindWordCard(today) { model.open(AppModel.More.KIND_WORDS) } })
                        HomeWidget.KIND_WORDS_SCHEDULE -> ({ KindScheduleCard(config) { model.open(AppModel.More.KIND_WORDS) } })
                    }
                }
                if (twoColumns) {
                    // Fill left then right, alternating, so the order reads across.
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        for (col in 0..1) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                cards.filterIndexed { i, _ -> i % 2 == col }.forEach { it() }
                            }
                        }
                    }
                } else {
                    cards.forEach { it() }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp).width(8.dp))
    }
    if (model.showCustomizeHome) CustomizeHomeSheet(model)
}

private fun greeting(hour: Int): String = when (hour) {
    in 4 until 12 -> "Good morning."
    in 12 until 15 -> "A calm afternoon."
    in 15 until 18 -> "Good afternoon."
    else -> "Good evening."
}

@Composable
private fun HomeCard(onClick: (() -> Unit)? = null, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = palette
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.display.copy(alpha = 0.6f))
            .border(1.dp, c.line, RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .padding(16.dp),
    ) {
        if (title != null) Eyebrow(title, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

/** The next prayer with a countdown, or on Friday between Asr and Maghrib, the last hour. */
@Composable
private fun FeaturedCard(config: SalahConfig, location: SavedLocation, schedule: DaySchedule, now: Instant) {
    val c = palette
    val lang = config.display.lang
    val zone = location.zone
    val asr = schedule.time(Prayer.ASR)
    val maghrib = schedule.time(Prayer.MAGHRIB)
    val fridayAfterAsr = schedule.isFriday && asr != null && maghrib != null && now >= asr && now < maghrib
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.timeline)) {
        DottedBackground(Modifier.matchParentSize())
        Column(Modifier.padding(horizontal = 22.dp, vertical = 18.dp)) {
            if (fridayAfterAsr) {
                Eyebrow(tr("FRIDAY · AFTER ASR"), color = c.onTimelineDim)
                Label(tr("Maghrib in"), size = 15f, color = c.onTimeline, modifier = Modifier.padding(top = 6.dp))
                PixelText(TimeFormatting.countdown((maghrib!!.toEpochMilli() - now.toEpochMilli()) / 1000.0), 46f, color = c.onTimeline)
                KindWordsLibrary.BUNDLED.word(KindWordsLibrary.LAST_HOUR_ID)?.let {
                    Label(it.notificationBody, color = c.onTimelineDim, modifier = Modifier.padding(top = 10.dp))
                }
            } else {
                val next = NextPrayerResolver.resolve(now, location, config.calculation) ?: return@Column
                Eyebrow(tr("NEXT PRAYER"), color = c.onTimelineDim)
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Label(next.label(config.display.jumuahRelabel, lang), size = 26f, weight = FontWeight.SemiBold, color = c.onTimeline)
                        PixelText(TimeFormatting.countdown(next.secondsRemaining(now)), 46f, color = c.onTimeline, modifier = Modifier.padding(top = 4.dp))
                    }
                    PixelText(TimeFormatting.clock(next.time, zone, config.display.use24HourClock, lang = lang), 28f, color = c.onTimeline)
                }
                val day = if (next.date == schedule.date) schedule else PrayerSchedule.forDate(next.date, location, config.calculation)
                KindWordsPlanner.beforePrayerLine(next.prayer, day, config, force = true)?.let {
                    Label(it, color = c.onTimelineDim, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun PrayerTimesCard(config: SalahConfig, location: SavedLocation, schedule: DaySchedule, now: Instant, onOpen: () -> Unit) {
    val c = palette
    val lang = config.display.lang
    val next = NextPrayerResolver.resolve(now, location, config.calculation)
    HomeCard(onClick = onOpen, title = tr("PRAYER TIMES")) {
        for (p in Prayer.entries) {
            val t = schedule.time(p)
            val isNext = next != null && !next.isTomorrow && next.prayer == p
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .background(if (isNext) c.accent.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label(
                    schedule.label(p, config.display.jumuahRelabel, lang),
                    color = if (p == Prayer.SUNRISE) c.secondary else c.text,
                    weight = if (isNext) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                PixelText(t?.let { TimeFormatting.clock(it, location.zone, config.display.use24HourClock, lang = lang) } ?: "--", 15f, color = if (isNext) c.accent else c.text)
            }
        }
    }
}

@Composable
private fun DuaCard(today: LocalDate, onOpen: () -> Unit) {
    val c = palette
    val dua = KindWordsLibrary.BUNDLED.duaOfTheDay(today) ?: return
    HomeCard(onClick = onOpen, title = tr("DU'A OF THE DAY")) {
        Label(dua.label, size = 15f, weight = FontWeight.SemiBold)
        Label(dua.english, modifier = Modifier.padding(top = 6.dp))
        if (dua.transliteration.isNotBlank()) Label(dua.transliteration, size = 12f, color = c.secondary, modifier = Modifier.padding(top = 6.dp))
        Label(dua.source, size = 12f, color = c.secondary, modifier = Modifier.padding(top = 6.dp))
    }
}

/** What's playing, or a surah for the moment: Al-Kahf on Friday, Al-Mulk at night, else a short surah. */
@Composable
private fun QuranCard(model: AppModel, now: Instant, location: SavedLocation) {
    val c = palette
    val player = model.quran
    val playing = player.current?.let { QuranCatalog.surah(it) }
    val local = now.atZone(location.zone)
    val suggestion = when {
        local.toLocalDate().isFriday -> 18
        local.hour >= 19 || local.hour < 4 -> 67
        else -> 93 + (local.toLocalDate().toEpochDay() % 22).toInt()
    }
    val s = playing ?: QuranCatalog.surah(suggestion) ?: return
    HomeCard(onClick = { model.open(AppModel.Tab.QURAN) }, title = tr(if (playing != null) "NOW PLAYING" else "QURAN")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("${s.number}. ${s.name}", size = 15f, weight = FontWeight.SemiBold)
                Label(
                    if (playing != null) model.config.quran.reciter.name
                    else tr(
                        when (suggestion) {
                            18 -> "Al-Kahf on Friday"
                            67 -> "Al-Mulk before sleep"
                            else -> "A short surah for today"
                        },
                    ),
                    color = c.secondary,
                )
            }
            Label(s.arabicName, size = 19f, modifier = Modifier.padding(horizontal = 12.dp))
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(c.accent).pointerHoverIcon(PointerIcon.Hand).clickable {
                    if (playing != null) player.togglePlay() else player.play(listOf(s.number), 0, QueueSource.SurahList)
                },
                contentAlignment = Alignment.Center,
            ) {
                PlayerGlyph(if (playing != null && player.isPlaying) Glyph.PAUSE else Glyph.PLAY, c.onAccent, 14.dp)
            }
        }
    }
}

private fun fastReasons(config: SalahConfig, schedule: DaySchedule, now: Instant) = run {
    val maghrib = schedule.time(Prayer.MAGHRIB)
    val date = if (maghrib != null && now >= maghrib) schedule.date.plusDays(1) else schedule.date
    SunnahFast.reasons(date, config.display.hijriAdjustment)
}

/** Today's sunnah fast, or tomorrow's once Maghrib has passed (the fast starts at the next Fajr). */
@Composable
private fun FastCard(config: SalahConfig, location: SavedLocation, schedule: DaySchedule, now: Instant) {
    val c = palette
    val maghrib = schedule.time(Prayer.MAGHRIB)
    val tomorrow = maghrib != null && now >= maghrib
    val reasons = fastReasons(config, schedule, now)
    if (reasons.isEmpty()) return
    val lang = config.display.lang
    HomeCard(title = tr(if (tomorrow) "SUNNAH FAST TOMORROW" else "SUNNAH FAST TODAY")) {
        Label(SunnahFast.tag(reasons, lang).orEmpty(), size = 15f, weight = FontWeight.SemiBold)
        Label(reasons.first().reason, color = c.secondary, modifier = Modifier.padding(top = 6.dp))
        val fajr = schedule.time(Prayer.FAJR)
        if (!tomorrow && fajr != null && maghrib != null) {
            Label(
                tr("Fajr {0} · Maghrib {1}", TimeFormatting.clock(fajr, location.zone, config.display.use24HourClock, lang = lang), TimeFormatting.clock(maghrib, location.zone, config.display.use24HourClock, lang = lang)),
                color = c.accent,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun KindWordCard(today: LocalDate, onOpen: () -> Unit) {
    val c = palette
    val w = KindWordsLibrary.BUNDLED.kindWordOfTheDay(today) ?: return
    HomeCard(onClick = onOpen, title = tr("KIND WORD OF THE DAY")) {
        Label(w.text, size = 14f)
        Label("— ${w.source}", size = 12f, color = c.secondary, modifier = Modifier.padding(top = 6.dp))
    }
}

/** How Kind Words decides when to send, in plain words. */
@Composable
private fun KindScheduleCard(config: SalahConfig, onOpen: () -> Unit) {
    val c = palette
    val k = config.kindWords
    HomeCard(onClick = onOpen, title = tr("KIND WORDS SCHEDULE")) {
        if (!k.enabled) {
            Label(tr("Kind Words is off."), size = 14f)
            Label(tr("Open Kind Words to turn it on"), color = c.accent, modifier = Modifier.padding(top = 4.dp))
            return@HomeCard
        }
        @Composable
        fun line(text: String) = Label("•  $text", modifier = Modifier.padding(vertical = 2.dp))
        if (k.cadence != KindWordsCadence.OFF) line(tr("{0}, never within {1} minutes of a prayer", tr(k.cadence.displayName), KindWordsPlanner.PRAYER_GAP_MINUTES))
        if (k.beforePrayer) line(tr("A verse or hadith with each early reminder"))
        if (k.afterPrayer) line(tr("\"Have you prayed?\" {0} minutes after each prayer", k.afterPrayerMinutes))
        if (k.friday) line(tr("Friday: Al-Kahf at 8:00 and the last hour before Maghrib"))
        if (k.workDuas) line(tr("Work du'as at {0} and {1}, Monday to Friday", k.workStart, k.workEnd))
        Label(tr(if (k.silent) "Arrives silently." else "Arrives with your reminder sound."), size = 12f, color = c.secondary, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Turn Home widgets on or off and change their order. */
@Composable
private fun CustomizeHomeSheet(model: AppModel) {
    val c = palette
    val home = model.config.home
    fun set(body: (HomeSettings) -> HomeSettings) = model.update { it.copy(home = body(it.home)) }
    val ordered = home.widgets + HomeWidget.entries.filter { it !in home.widgets }
    Sheet(onDismiss = { model.showCustomizeHome = false }) {
        Label(tr("Customize Home"), size = 17f, weight = FontWeight.SemiBold)
        Column {
            for (w in ordered) {
                val on = home.isOn(w)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label(tr(w.displayName), color = if (on) c.text else c.secondary, modifier = Modifier.weight(1f))
                    if (on) {
                        IconButtonBox({ set { it.move(w, -1) } }, tooltip = tr("Move up")) { Label("↑", color = c.secondary) }
                        IconButtonBox({ set { it.move(w, 1) } }, tooltip = tr("Move down")) { Label("↓", color = c.secondary) }
                    }
                    SalahSwitch(on) { v -> set { it.set(w, v) } }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            AccentButton(tr("Done")) { model.showCustomizeHome = false }
        }
    }
}
