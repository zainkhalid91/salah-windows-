package salah.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import salah.app.AppModel
import salah.app.quran.QuranPlayer
import salah.core.TimeFormatting
import salah.core.quran.BuiltInPlaylist
import salah.core.quran.QueueSource
import salah.core.quran.QuranCatalog
import salah.core.quran.QuranPlaylist
import salah.core.quran.QuranRepeatMode
import salah.core.quran.Surah

private enum class QuranView { SURAHS, PLAYLISTS }

/** Surahs or playlists on the left, the player and its queue on the right. */
@Composable
fun QuranScreen(model: AppModel) {
    val c = palette
    var view by remember { mutableStateOf(QuranView.SURAHS) }
    var query by remember { mutableStateOf("") }
    var addTo by remember { mutableStateOf<Int?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize().background(c.background)) {
        val wide = maxWidth >= 820.dp
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 30.dp, end = if (wide) 18.dp else 30.dp, top = 22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Label(tr("Quran"), size = 20f, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    PillPicker(listOf(QuranView.SURAHS to tr("Surahs"), QuranView.PLAYLISTS to tr("Playlists")), view, { view = it })
                }
                Row(Modifier.padding(top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (view == QuranView.SURAHS) TextInput(query, { query = it }, tr("Search by name, meaning or number"), Modifier.weight(1f))
                    else Spacer(Modifier.weight(1f))
                    DropdownPicker(
                        QuranCatalog.reciters.map { it.id to it.displayName },
                        model.config.quran.reciterId,
                        { id ->
                            model.update { it.copy(quran = it.quran.copy(reciterId = id)) }
                            model.quran.reciterChanged()
                        },
                        maxWidth = 260.dp,
                    )
                }
                when (view) {
                    QuranView.SURAHS -> SurahList(model, query, onAddToPlaylist = { addTo = it })
                    QuranView.PLAYLISTS -> PlaylistsView(model)
                }
            }
            if (wide) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
                Column(Modifier.width(340.dp).fillMaxHeight().background(c.chrome.copy(alpha = 0.5f))) {
                    FullPlayer(model)
                    HorizontalDivider(color = c.line)
                    QueueList(model, Modifier.weight(1f))
                }
            }
        }
        if (!wide && model.quran.queue != null) {
            Box(Modifier.align(Alignment.BottomCenter)) { MiniPlayer(model, onOpen = { model.showQueue = true }) }
        }
    }
    addTo?.let { surah -> AddToPlaylistSheet(model, surah) { addTo = null } }
    if (model.showQueue) {
        Sheet(onDismiss = { model.showQueue = false }, width = 420.dp) {
            FullPlayer(model)
            QueueList(model, Modifier.height(260.dp))
        }
    }
}

@Composable
private fun SurahList(model: AppModel, query: String, onAddToPlaylist: (Int) -> Unit) {
    val surahs = QuranCatalog.surahs.filter { query.isBlank() || QuranCatalog.matches(it, query) }
    val state = rememberLazyListState()
    val reciter = model.config.quran.reciter
    Box(Modifier.fillMaxSize()) {
        if (surahs.isEmpty()) Label(tr("No surah matches “{0}”.", query), color = palette.secondary, modifier = Modifier.padding(top = 12.dp))
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(end = 10.dp)) {
            itemsIndexed(surahs, key = { _, s -> s.number }) { _, s ->
                SurahRow(
                    s,
                    playing = model.quran.current == s.number,
                    available = s.number in reciter.surahs,
                    onPlay = { model.quran.play(surahs.map { it.number }, surahs.indexOf(s), QueueSource.SurahList) },
                    menu = { close ->
                        DropdownMenuItem(text = { Label(tr("Play next")) }, onClick = { close(); model.quran.playNext(s.number) })
                        DropdownMenuItem(text = { Label(tr("Add to queue")) }, onClick = { close(); model.quran.append(s.number) })
                        DropdownMenuItem(text = { Label(tr("Add to playlist…")) }, onClick = { close(); onAddToPlaylist(s.number) })
                    },
                )
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp).width(8.dp))
    }
}

@Composable
private fun SurahRow(
    s: Surah,
    playing: Boolean,
    available: Boolean = true,
    onPlay: () -> Unit,
    menu: (@Composable (close: () -> Unit) -> Unit)? = null,
) {
    val c = palette
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (playing) c.accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(enabled = available, onClick = onPlay).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Label("${s.number}", color = if (playing) c.accent else c.secondary, weight = FontWeight.SemiBold, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Label(s.name, weight = if (playing) FontWeight.SemiBold else FontWeight.Medium, color = if (available) c.text else c.secondary)
            Label(
                if (available) tr("{0} · {1} ayahs · {2}", s.meaning, s.ayahCount, tr(s.revelation.displayName)) else tr("Not available from this reciter"),
                size = 12f, color = c.secondary, maxLines = 1,
            )
        }
        Label(s.arabicName, size = 17f, color = if (available) c.text else c.secondary)
        if (menu != null) {
            Box {
                IconButtonBox({ open = true }, tooltip = tr("More")) { Label("⋯", size = 15f, color = c.secondary) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(c.highlight)) { menu { open = false } }
            }
        }
    }
}

@Composable
private fun PlaylistsView(model: AppModel) {
    val c = palette
    val q = model.config.quran
    var creating by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<QuranPlaylist?>(null) }
    val scroll = rememberScrollState()
    fun playlists(body: (List<QuranPlaylist>) -> List<QuranPlaylist>) = model.update { it.copy(quran = it.quran.copy(playlists = body(it.quran.playlists))) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 12.dp, bottom = 20.dp)) {
            SettingsGroup {
                for (b in BuiltInPlaylist.entries) row {
                    SettingsRow(tr(b.displayName), tr("{0} surahs", b.surahs.size)) {
                        AccentButton(tr("Play")) { model.quran.play(b.surahs, 0, QueueSource.BuiltIn(b)) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Label(tr("Your playlists"), size = 13f, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                LinkButton(tr("New playlist"), size = 13f) { creating = true }
            }
            if (q.playlists.isEmpty()) {
                Label(tr("Make a playlist, then add surahs from the list with ⋯."), color = c.secondary)
            }
            for (p in q.playlists) {
                SettingsGroup {
                    row {
                        SettingsRow(p.name, tr("{0} surahs", p.surahs.size)) {
                            LinkButton(tr("Rename")) { rename = p }
                            LinkButton(tr("Delete")) { playlists { all -> all.filter { it.id != p.id } } }
                            AccentButton(tr("Play"), enabled = p.surahs.isNotEmpty()) { model.quran.play(p.surahs, 0, QueueSource.Playlist(p.id)) }
                        }
                    }
                    p.surahs.forEachIndexed { i, n ->
                        val s = QuranCatalog.surah(n) ?: return@forEachIndexed
                        row {
                            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    SurahRow(s, playing = model.quran.current == n, onPlay = { model.quran.play(p.surahs, i, QueueSource.Playlist(p.id)) })
                                }
                                IconButtonBox({ playlists { all -> all.map { if (it.id == p.id) it.copy(surahs = it.surahs.filterIndexed { j, _ -> j != i }) else it } } }, tooltip = tr("Remove")) {
                                    Label("✕", size = 12f, color = c.secondary)
                                }
                            }
                        }
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp).width(8.dp))
    }
    if (creating) NameSheet(tr("New playlist"), "") { name ->
        creating = false
        if (name != null) playlists { it + QuranPlaylist(name = name.ifBlank { "Playlist" }) }
    }
    rename?.let { p ->
        NameSheet(tr("Rename playlist"), p.name) { name ->
            rename = null
            if (name != null) playlists { all -> all.map { if (it.id == p.id) it.copy(name = name.ifBlank { p.name }) else it } }
        }
    }
}

@Composable
private fun NameSheet(title: String, initial: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Sheet(onDismiss = { onDone(null) }, width = 360.dp) {
        Label(title, size = 17f, weight = FontWeight.SemiBold)
        TextInput(text, { text = it }, tr("Name"), onSubmit = { onDone(text.trim()) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            SecondaryButton(tr("Cancel")) { onDone(null) }
            AccentButton(tr("Save")) { onDone(text.trim()) }
        }
    }
}

@Composable
private fun AddToPlaylistSheet(model: AppModel, surah: Int, onDone: () -> Unit) {
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        NameSheet(tr("New playlist"), "") { name ->
            if (name != null) model.update { it.copy(quran = it.quran.copy(playlists = it.quran.playlists + QuranPlaylist(name = name.ifBlank { "Playlist" }, surahs = listOf(surah)))) }
            onDone()
        }
        return
    }
    Sheet(onDismiss = onDone, width = 360.dp) {
        Label(tr("Add to playlist"), size = 17f, weight = FontWeight.SemiBold)
        for (p in model.config.quran.playlists) {
            LinkButton(p.name, size = 14f, color = palette.text) {
                model.update { cfg -> cfg.copy(quran = cfg.quran.copy(playlists = cfg.quran.playlists.map { if (it.id == p.id) it.copy(surahs = it.surahs + surah) else it })) }
                onDone()
            }
        }
        LinkButton(tr("New playlist"), size = 14f) { naming = true }
    }
}

@Composable
private fun sourceLine(model: AppModel): String {
    val q = model.quran.queue ?: return ""
    return when (val s = q.source) {
        QueueSource.SurahList -> tr("Surahs")
        is QueueSource.BuiltIn -> tr(s.playlist.displayName)
        is QueueSource.Playlist -> model.config.quran.playlist(s.id)?.name ?: tr("Playlist")
    }
}

@Composable
private fun FullPlayer(model: AppModel) {
    val c = palette
    val p = model.quran
    val settings = model.config.quran
    val s = p.current?.let { QuranCatalog.surah(it) }
    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(tr(if (p.isPlaying) "NOW PLAYING" else "QURAN"))
        if (s == null) {
            Label(tr("Choose a surah to listen."), color = c.secondary, modifier = Modifier.padding(vertical = 18.dp))
            return@Column
        }
        Label(s.arabicName, size = 26f)
        Label("${s.number}. ${s.name}", size = 16f, weight = FontWeight.SemiBold)
        Label("${settings.reciter.name} · ${sourceLine(model)}", size = 12f, color = c.secondary, maxLines = 1)
        var dragging by remember { mutableStateOf<Float?>(null) }
        val duration = p.durationMs
        val position = dragging?.let { (it * duration).toLong() } ?: p.positionMs
        Slider(
            value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { p.seekTo((it * duration).toLong()) }; dragging = null },
            enabled = duration > 0,
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.text.copy(alpha = 0.15f)),
            modifier = Modifier.fillMaxWidth().height(24.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            Label(TimeFormatting.mediaTime(position / 1000), size = 11f, color = c.secondary, modifier = Modifier.weight(1f))
            Label(if (duration > 0) TimeFormatting.mediaTime(duration / 1000) else if (p.isBuffering && p.isPlaying) tr("Loading…") else "", size = 11f, color = c.secondary)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ModeButton(Glyph.SHUFFLE, settings.shuffle, tr("Shuffle")) { p.setShuffle(!settings.shuffle) }
            CircleButton(Glyph.PREVIOUS, tr("Previous"), filled = false) { p.previous() }
            CircleButton(if (p.isPlaying) Glyph.PAUSE else Glyph.PLAY, tr(if (p.isPlaying) "Pause" else "Play"), size = 48.dp) { p.togglePlay() }
            CircleButton(Glyph.NEXT, tr("Next"), filled = false) { p.next() }
            ModeButton(if (settings.repeatMode == QuranRepeatMode.ONE) Glyph.REPEAT_ONE else Glyph.REPEAT, settings.repeatMode != QuranRepeatMode.OFF, tr(settings.repeatMode.label())) {
                p.setRepeat(settings.repeatMode.next)
            }
        }
        p.error?.let { Label(tr(it), size = 12f, color = c.accent) }
    }
}

private fun QuranRepeatMode.label(): String = when (this) {
    QuranRepeatMode.OFF -> "Repeat off"
    QuranRepeatMode.ALL -> "Repeat all"
    QuranRepeatMode.ONE -> "Repeat one"
}

@Composable
private fun QueueList(model: AppModel, modifier: Modifier) {
    val c = palette
    val q = model.quran.queue
    Column(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Eyebrow(tr("UP NEXT"), modifier = Modifier.weight(1f))
            if (q != null && q.upcoming.isNotEmpty()) LinkButton(tr("Clear")) { model.quran.clearUpcoming() }
        }
        if (q == null || q.upcoming.isEmpty()) {
            Label(tr("Nothing queued."), size = 12f, color = c.secondary, modifier = Modifier.padding(6.dp))
            return@Column
        }
        // Read revision so the list redraws on every queue change.
        val rev = model.quran.revision
        val state = rememberLazyListState()
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(end = 8.dp)) {
                val start = q.index + 1
                itemsIndexed(q.upcoming, key = { i, n -> "$rev.$i.$n" }) { i, n ->
                    val s = QuranCatalog.surah(n) ?: return@itemsIndexed
                    val pos = start + i
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { model.quran.jump(pos) }.padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Label("${s.number}. ${s.name}", modifier = Modifier.weight(1f), maxLines = 1)
                        if (pos > start) IconButtonBox({ model.quran.moveUpcoming(pos, pos - 1) }, tooltip = tr("Move up")) { Label("↑", size = 12f, color = c.secondary) }
                        if (pos < q.items.size - 1) IconButtonBox({ model.quran.moveUpcoming(pos, pos + 1) }, tooltip = tr("Move down")) { Label("↓", size = 12f, color = c.secondary) }
                        IconButtonBox({ model.quran.remove(pos) }, tooltip = tr("Remove")) { Label("✕", size = 11f, color = c.secondary) }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(8.dp))
        }
    }
}

/** The bar shown on the other tabs while something is queued. Click it to open the Quran tab. */
@Composable
fun MiniPlayer(model: AppModel, onOpen: () -> Unit) {
    val c = palette
    val p: QuranPlayer = model.quran
    val s = p.current?.let { QuranCatalog.surah(it) } ?: return
    val d = p.durationMs
    Column(Modifier.fillMaxWidth().background(c.chrome)) {
        HorizontalDivider(color = c.line)
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (d > 0) Box(Modifier.fillMaxWidth((p.positionMs.toFloat() / d).coerceIn(0f, 1f)).fillMaxHeight().background(c.accent))
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Label(s.arabicName, size = 15f)
            Column(Modifier.weight(1f)) {
                Label("${s.number}. ${s.name}", weight = FontWeight.SemiBold, maxLines = 1)
                Label(model.config.quran.reciter.name, size = 11f, color = c.secondary, maxLines = 1)
            }
            CircleButton(Glyph.PREVIOUS, tr("Previous"), filled = false, size = 30.dp) { p.previous() }
            CircleButton(if (p.isPlaying) Glyph.PAUSE else Glyph.PLAY, tr(if (p.isPlaying) "Pause" else "Play"), size = 34.dp) { p.togglePlay() }
            CircleButton(Glyph.NEXT, tr("Next"), filled = false, size = 30.dp) { p.next() }
        }
    }
}

@Composable
private fun CircleButton(glyph: Glyph, tooltip: String, filled: Boolean = true, size: Dp = 36.dp, onClick: () -> Unit) {
    val c = palette
    Tip(tooltip) {
        Box(
            Modifier.size(size).clip(CircleShape)
                .background(if (filled) c.accent else Color.Transparent)
                .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) {
            PlayerGlyph(glyph, if (filled) c.onAccent else c.text, size * 0.36f)
        }
    }
}

@Composable
private fun ModeButton(glyph: Glyph, on: Boolean, tooltip: String, onClick: () -> Unit) {
    val c = palette
    Tip(tooltip) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(6.dp))
                .background(if (on) c.accent.copy(alpha = 0.14f) else Color.Transparent)
                .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) {
            PlayerGlyph(glyph, if (on) c.accent else c.secondary, 13.dp)
        }
    }
}

enum class Glyph { PLAY, PAUSE, NEXT, PREVIOUS, SHUFFLE, REPEAT, REPEAT_ONE }

/** Player icons drawn as shapes, so they look the same on every Windows font setup. */
@Composable
fun PlayerGlyph(glyph: Glyph, color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        fun triangle(x0: Float, x1: Float) = Path().apply { moveTo(x0, 0f); lineTo(x1, h / 2); lineTo(x0, h); close() }
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.12f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        when (glyph) {
            Glyph.PLAY -> drawPath(triangle(w * 0.12f, w), color)
            Glyph.PAUSE -> {
                drawRect(color, Offset(w * 0.12f, 0f), Size(w * 0.28f, h))
                drawRect(color, Offset(w * 0.60f, 0f), Size(w * 0.28f, h))
            }
            Glyph.NEXT -> {
                drawPath(triangle(0f, w * 0.78f), color)
                drawRect(color, Offset(w * 0.80f, 0f), Size(w * 0.18f, h))
            }
            Glyph.PREVIOUS -> {
                drawRect(color, Offset(w * 0.02f, 0f), Size(w * 0.18f, h))
                drawPath(Path().apply { moveTo(w, 0f); lineTo(w * 0.22f, h / 2); lineTo(w, h); close() }, color)
            }
            Glyph.SHUFFLE -> {
                drawLine(color, Offset(0f, h * 0.2f), Offset(w, h * 0.8f), stroke.width, stroke.cap)
                drawLine(color, Offset(0f, h * 0.8f), Offset(w, h * 0.2f), stroke.width, stroke.cap)
            }
            Glyph.REPEAT, Glyph.REPEAT_ONE -> {
                drawRoundRect(color, Offset(w * 0.06f, h * 0.18f), Size(w * 0.88f, h * 0.64f), androidx.compose.ui.geometry.CornerRadius(h * 0.3f), style = stroke)
                if (glyph == Glyph.REPEAT_ONE) drawCircle(color, w * 0.12f, Offset(w / 2, h / 2))
            }
        }
    }
}
