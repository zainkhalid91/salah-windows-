package salah.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// region Text helpers

@Composable
fun Label(
    text: String,
    size: Float = 13f,
    weight: FontWeight = FontWeight.Normal,
    color: Color = palette.text,
    tracking: Float = 0f,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text, modifier = modifier, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        // Arabic letters join, so no extra tracking there.
        style = TextStyle(fontFamily = UiFont, fontSize = size.sp, fontWeight = weight, letterSpacing = (if (LocalLang.current.rtl) 0f else tracking).sp, lineHeight = (size * 1.35f).sp),
    )
}

/** Small caps-style status label, e.g. "NEXT PRAYER". */
@Composable
fun Eyebrow(text: String, color: Color = palette.secondary, modifier: Modifier = Modifier) =
    Label(text, size = 11f, weight = FontWeight.SemiBold, color = color, tracking = 1.5f, modifier = modifier)

@Composable
fun PixelText(text: String, size: Float, weight: FontWeight = FontWeight.Black, color: Color = palette.text, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = color, maxLines = 1, softWrap = false, style = PixelFont.style(size, weight).copy(lineHeight = (size * 1.08f).sp))
}

// endregion

// region Buttons

private val clickCursor = PointerIcon.Hand

@Composable
fun AccentButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val c = palette
    val bg = when {
        !enabled -> c.accent.copy(alpha = 0.45f)
        pressed -> c.accent.copy(alpha = 0.8f)
        hovered -> c.accent.copy(alpha = 0.92f)
        else -> c.accent
    }
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .hoverable(source)
            .clickable(source, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .pointerHoverIcon(clickCursor)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        Label(text, size = 13f, weight = FontWeight.Medium, color = c.onAccent)
    }
}

/** A quiet bordered button with full-strength text. */
@Composable
fun SecondaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val c = palette
    val alpha = if (pressed) 0.22f else if (hovered) 0.16f else 0.10f
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(c.text.copy(alpha = alpha))
            .border(1.dp, c.text.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
            .hoverable(source)
            .clickable(source, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .pointerHoverIcon(clickCursor)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Label(text, size = 13f, weight = FontWeight.Medium, color = if (enabled) c.text else c.secondary)
    }
}

/** Text-only accent link, like the macOS `.link` buttons. */
@Composable
fun LinkButton(text: String, size: Float = 12f, color: Color = palette.accent, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        modifier
            .hoverable(source)
            .clickable(source, null, role = Role.Button, onClick = onClick)
            .pointerHoverIcon(clickCursor),
    ) {
        Text(
            text, color = color,
            style = TextStyle(
                fontFamily = UiFont, fontSize = size.sp, fontWeight = FontWeight.SemiBold,
                textDecoration = if (hovered) androidx.compose.ui.text.style.TextDecoration.Underline else null,
            ),
        )
    }
}

/** An icon-sized square button with a hover wash. */
@Composable
fun IconButtonBox(onClick: () -> Unit, tooltip: String? = null, enabled: Boolean = true, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val c = palette
    val box = @Composable {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(6.dp))
                .background(if (hovered && enabled) c.text.copy(alpha = 0.08f) else Color.Transparent)
                .border(1.dp, c.text.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                .hoverable(source)
                .clickable(source, null, enabled = enabled, role = Role.Button, onClick = onClick)
                .pointerHoverIcon(clickCursor),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
    if (tooltip != null) Tip(tooltip) { box() } else box()
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Tip(text: String, content: @Composable () -> Unit) {
    val c = palette
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            Box(Modifier.shadow(6.dp, RoundedCornerShape(5.dp)).background(c.highlight, RoundedCornerShape(5.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Label(text, size = 12f)
            }
        },
        delayMillis = 500,
    ) { content() }
}

// endregion

// region Controls

/** The macOS-style switch, in the accent color. */
@Composable
fun SalahSwitch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = palette
    val track by animateColorAsState(
        if (checked) c.accent.copy(alpha = if (enabled) 1f else 0.45f) else c.text.copy(alpha = if (enabled) 0.18f else 0.08f),
        tween(160),
        label = "switch-track",
    )
    val x by animateDpAsState(if (checked) 16.dp else 2.dp, tween(160), label = "switch-knob")
    Box(
        Modifier.size(width = 36.dp, height = 22.dp).clip(RoundedCornerShape(11.dp)).background(track)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .pointerHoverIcon(clickCursor),
    ) {
        Box(Modifier.offset(x = x, y = 2.dp).size(18.dp).shadow(1.dp, CircleShape).background(Color.White, CircleShape))
    }
}

@Composable
fun SalahCheckbox(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = palette
    Row(
        Modifier.clickable(enabled = enabled, role = Role.Checkbox) { onChange(!checked) }.pointerHoverIcon(clickCursor),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val on = if (enabled) c.accent else c.accent.copy(alpha = 0.4f)
        Box(
            Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
                .background(if (checked) on else Color.Transparent)
                .border(1.dp, if (checked) on else c.text.copy(alpha = 0.3f), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Label("✓", size = 11f, weight = FontWeight.Bold, color = c.onAccent)
        }
        Label(label, color = if (enabled) c.text else c.secondary)
    }
}

/** A small segmented pill, matching the mock's tab bar and clock/appearance selectors. */
@Composable
fun <T> PillPicker(options: List<Pair<T, String>>, selection: T, onSelect: (T) -> Unit, compact: Boolean = true) {
    val c = palette
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(c.text.copy(alpha = if (c.isDark) 0.10f else 0.07f)).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((value, title) in options) {
            val selected = value == selection
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            Box(
                Modifier
                    .then(if (selected) Modifier.shadow(1.dp, RoundedCornerShape(6.dp)) else Modifier)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        when {
                            selected -> if (c.isDark) Color(0xFF3A3C3C) else Color.White
                            hovered -> c.text.copy(alpha = 0.06f)
                            else -> Color.Transparent
                        },
                    )
                    .hoverable(source)
                    .clickable(source, null, role = Role.Tab) { onSelect(value) }
                    .pointerHoverIcon(clickCursor)
                    .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 4.dp else 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Label(title, size = 13f, weight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = c.text)
            }
        }
    }
}

/** A popup-button picker: shows the current choice, opens a menu of options. */
@Composable
fun <T> DropdownPicker(
    options: List<Pair<T, String>>,
    selection: T,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
    maxWidth: Dp = 280.dp,
    dividerAfter: Int? = null,
) {
    var open by remember { mutableStateOf(false) }
    val c = palette
    val current = options.firstOrNull { it.first == selection }?.second ?: ""
    Box {
        Row(
            Modifier.widthIn(max = maxWidth).clip(RoundedCornerShape(6.dp))
                .background(c.text.copy(alpha = if (enabled) 0.07f else 0.03f))
                .border(1.dp, c.text.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                .clickable(enabled = enabled, role = Role.DropdownList) { open = true }
                .pointerHoverIcon(clickCursor)
                .padding(start = 10.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Label(current, color = if (enabled) c.text else c.secondary, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            Label("⌄", size = 13f, color = c.secondary, modifier = Modifier.offset(y = (-3).dp))
        }
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.background(c.highlight).heightIn(max = 420.dp),
        ) {
            options.forEachIndexed { i, (value, title) ->
                DropdownMenuItem(
                    text = { Label(title, weight = if (value == selection) FontWeight.SemiBold else FontWeight.Normal) },
                    leadingIcon = { Label(if (value == selection) "✓" else " ", color = c.accent, modifier = Modifier.width(12.dp)) },
                    onClick = { open = false; onSelect(value) },
                    modifier = Modifier.height(34.dp),
                )
                if (dividerAfter == i) HorizontalDivider(color = c.line)
            }
        }
    }
}

/** − value + stepper. */
@Composable
fun Stepper(text: String, canDecrement: Boolean, canIncrement: Boolean, onDecrement: () -> Unit, onIncrement: () -> Unit) {
    val c = palette
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(text, modifier = Modifier.widthIn(min = 64.dp), color = c.text)
        Row(Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, c.text.copy(alpha = 0.14f), RoundedCornerShape(6.dp))) {
            StepButton("−", canDecrement, onDecrement)
            Box(Modifier.width(1.dp).height(24.dp).background(c.text.copy(alpha = 0.14f)))
            StepButton("+", canIncrement, onIncrement)
        }
    }
}

@Composable
private fun StepButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val c = palette
    Box(
        Modifier.size(width = 28.dp, height = 24.dp)
            .background(if (hovered && enabled) c.text.copy(alpha = 0.08f) else Color.Transparent)
            .hoverable(source)
            .clickable(source, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .pointerHoverIcon(clickCursor),
        contentAlignment = Alignment.Center,
    ) { Label(symbol, size = 14f, weight = FontWeight.Medium, color = if (enabled) c.text else c.secondary.copy(alpha = 0.5f)) }
}

// endregion

// region Layout

/** A rounded settings group with hairline dividers between rows. Rows are declared with [SettingsGroupBuilder.row]. */
@Composable
fun SettingsGroup(build: SettingsGroupBuilder.() -> Unit) {
    val c = palette
    val rows = SettingsGroupBuilder().apply(build).rows
    Column(
        Modifier.fillMaxWidth().padding(bottom = 18.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.display.copy(alpha = 0.45f))
            .border(1.dp, c.line, RoundedCornerShape(10.dp)),
    ) {
        rows.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(color = c.line, thickness = 1.dp)
            row()
        }
    }
}

class SettingsGroupBuilder {
    internal val rows = mutableListOf<@Composable () -> Unit>()
    fun row(content: @Composable () -> Unit) { rows += content }
}

/** One row: label and hint on the left, a control on the right. */
@Composable
fun SettingsRow(label: String, hint: String? = null, control: @Composable RowScope.() -> Unit) {
    val c = palette
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Label(label, weight = FontWeight.Medium)
            if (hint != null) Label(hint, size = 12f, color = c.secondary)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = control)
    }
}

/** Pane scaffold for the non-Today screens. */
@Composable
fun Pane(title: String, subtitle: String? = null, scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val c = palette
    val state = rememberScrollState()
    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(
            Modifier.fillMaxSize().then(if (scroll) Modifier.verticalScroll(state) else Modifier)
                .padding(horizontal = 30.dp, vertical = 26.dp),
        ) {
            Column(Modifier.widthIn(max = 720.dp)) {
                Label(title, size = 20f, weight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
                if (subtitle != null) Label(subtitle, color = c.secondary, modifier = Modifier.padding(bottom = 20.dp))
                content()
            }
        }
        if (scroll) {
            androidx.compose.foundation.VerticalScrollbar(
                androidx.compose.foundation.rememberScrollbarAdapter(state),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp).width(8.dp),
            )
        }
    }
}

// endregion
