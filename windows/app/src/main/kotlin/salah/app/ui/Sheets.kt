package salah.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import salah.app.AppModel
import salah.core.LocationSearch
import salah.core.SavedLocation
import java.time.ZoneId
import java.util.Locale

/** A centered card over a dimmed window, like a macOS sheet. Esc or a click outside dismisses it. */
@Composable
fun Sheet(onDismiss: () -> Unit, width: Dp = 440.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = palette
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (c.isDark) 0.5f else 0.28f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .onPreviewKeyEvent { if (it.key == Key.Escape) { onDismiss(); true } else false },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.padding(top = 40.dp).width(width)
                .shadow(24.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(c.background)
                .border(1.dp, c.line, RoundedCornerShape(12.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
fun TextInput(
    value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    onSubmit: () -> Unit = {}, focus: FocusRequester? = null,
) {
    val c = palette
    BasicTextField(
        value, onChange, singleLine = true,
        textStyle = TextStyle(color = c.text, fontSize = 13.sp),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        modifier = modifier.then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onPreviewKeyEvent { if (it.key == Key.Enter && it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) { onSubmit(); true } else false },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().background(if (c.isDark) Color(0xFF1C1D1D) else Color.White, RoundedCornerShape(6.dp))
                    .border(1.dp, c.text.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                if (value.isEmpty()) Label(placeholder, color = c.secondary)
                inner()
            }
        },
    )
}

/** Manual location entry: city search or coordinates. Works without location permission. */
@Composable
fun LocationSheet(model: AppModel) {
    val c = palette
    val scope = rememberCoroutineScope()
    var coordinates by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SavedLocation>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val loc = model.location
    var name by remember { mutableStateOf(loc?.name ?: "") }
    var lat by remember { mutableStateOf(loc?.latitude?.toString() ?: "") }
    var lon by remember { mutableStateOf(loc?.longitude?.toString() ?: "") }
    var tz by remember { mutableStateOf(loc?.timeZone ?: ZoneId.systemDefault().id) }
    var tzPicker by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val close = { model.showLocationSheet = false }

    fun search() {
        val q = query.trim()
        if (q.isEmpty() || searching) return
        searching = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { LocationSearch.search(q) } }
            results = r.getOrDefault(emptyList())
            error = r.exceptionOrNull()?.message
            searching = false
        }
    }

    fun saveCoordinates() {
        val la = lat.trim().toDoubleOrNull()
        val lo = lon.trim().toDoubleOrNull()
        if (la == null || la !in -90.0..90.0) { error = "Latitude must be a number from -90 to 90."; return }
        if (lo == null || lo !in -180.0..180.0) { error = "Longitude must be a number from -180 to 180."; return }
        val n = name.trim()
        model.setLocation(SavedLocation(n.ifEmpty { String.format(Locale.ROOT, "%.4f, %.4f", la, lo) }, la, lo, tz))
    }

    Sheet(onDismiss = close) {
        Label("Location", size = 17f, weight = FontWeight.SemiBold)
        PillPicker(listOf(false to "Search city", true to "Coordinates"), coordinates, { coordinates = it; error = null }, compact = false)
        if (!coordinates) {
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextInput(query, { query = it }, "City, e.g. Singapore or Jakarta", Modifier.weight(1f), ::search, focus)
                SecondaryButton("Search", enabled = query.isNotBlank() && !searching) { search() }
            }
            if (searching) CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in results) ResultRow(r) { model.setLocation(r) }
            }
        } else {
            CoordRow("Name") { TextInput(name, { name = it }, "e.g. Home", Modifier.fillMaxWidth()) }
            CoordRow("Latitude") { TextInput(lat, { lat = it }, "-90 to 90, e.g. 1.3521", Modifier.fillMaxWidth()) }
            CoordRow("Longitude") { TextInput(lon, { lon = it }, "-180 to 180, e.g. 103.8198", Modifier.fillMaxWidth()) }
            CoordRow("Time zone") { SecondaryButton("$tz  ⌄") { tzPicker = true } }
        }
        error?.let { Label(it, size = 12f, color = c.accent) }
        HorizontalDivider(color = c.line)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(if (model.locationProvider.isLocating) "Locating…" else "⌖  Use my location", enabled = !model.locationProvider.isLocating) {
                model.locationProvider.requestLocation()
                close()
            }
            Spacer(Modifier.weight(1f))
            SecondaryButton("Cancel", onClick = close)
            if (coordinates) AccentButton("Save") { saveCoordinates() }
        }
        Label(
            "City search uses Open-Meteo's geocoding service, so your query is sent to Open-Meteo. Coordinates you type are never sent anywhere.",
            size = 11f, color = c.secondary,
        )
    }
    if (tzPicker) TimeZonePicker(tz, onDismiss = { tzPicker = false }) { tz = it; tzPicker = false }
}

@Composable
private fun CoordRow(label: String, field: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label(label, modifier = Modifier.width(84.dp))
        Box(Modifier.weight(1f)) { field() }
    }
}

@Composable
private fun ResultRow(r: SavedLocation, onPick: () -> Unit) {
    val c = palette
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(c.display.copy(alpha = if (hovered) 0.95f else 0.6f))
            .hoverable(source).clickable(onClick = onPick).pointerHoverIcon(PointerIcon.Hand)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Label(listOfNotNull(r.name, LocationSearch.region(r)).joinToString(", "), weight = FontWeight.Medium)
        Label("${r.timeZone} · ${r.coordinateDescription}", size = 11.5f, color = c.secondary)
    }
}

/** Searchable list of IANA time zones. */
@Composable
fun TimeZonePicker(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = palette
    var filter by remember { mutableStateOf("") }
    val all = remember { ZoneId.getAvailableZoneIds().filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }.sorted() }
    val shown = remember(filter) { all.filter { it.contains(filter.trim().replace(' ', '_'), ignoreCase = true) } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Sheet(onDismiss = onDismiss, width = 380.dp) {
        Label("Time zone", size = 15f, weight = FontWeight.SemiBold)
        TextInput(filter, { filter = it }, "Search, e.g. Jakarta", Modifier.fillMaxWidth(), { shown.firstOrNull()?.let(onPick) }, focus)
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(shown, key = { it }) { id ->
                val selected = id == current
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(if (selected) c.accent.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { onPick(id) }.pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Label(id, weight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    if (selected) Label("✓", color = c.accent)
                }
            }
        }
    }
}
