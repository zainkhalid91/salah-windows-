package salah.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Window

/**
 * What the custom title bar needs from the window. The main window is undecorated, so the
 * app's top bar is the title bar: it drags the window and carries the caption buttons.
 * Null in snapshots and other windows.
 */
class WindowChrome(
    val isMaximized: Boolean,
    val minimize: () -> Unit,
    val toggleMaximize: () -> Unit,
    val close: () -> Unit,
    /** Wraps content so dragging it moves the window (Compose's WindowDraggableArea). */
    val dragArea: @Composable (Modifier, @Composable () -> Unit) -> Unit,
)

val LocalWindowChrome = staticCompositionLocalOf<WindowChrome?> { null }

/** The title bar area: drags the window and maximizes on double click. */
@Composable
fun TitleBarArea(modifier: Modifier, content: @Composable () -> Unit) {
    val chrome = LocalWindowChrome.current
    if (chrome == null) {
        Box(modifier) { content() }
        return
    }
    val doubleClick = Modifier.pointerInput(chrome) { detectTapGestures(onDoubleTap = { chrome.toggleMaximize() }) }
    if (chrome.isMaximized) {
        // A maximized window doesn't move; double click restores it.
        Box(modifier.then(doubleClick)) { content() }
    } else {
        chrome.dragArea(modifier.then(doubleClick), content)
    }
}

private enum class Caption { MINIMIZE, MAXIMIZE, RESTORE, CLOSE }

/** Minimize, maximize and close, sized and coloured like the Windows 10 and 11 caption buttons. */
@Composable
fun CaptionButtons(chrome: WindowChrome, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxHeight()) {
        CaptionButton(Caption.MINIMIZE, tr("Minimize"), chrome.minimize)
        CaptionButton(if (chrome.isMaximized) Caption.RESTORE else Caption.MAXIMIZE, tr(if (chrome.isMaximized) "Restore" else "Maximize"), chrome.toggleMaximize)
        CaptionButton(Caption.CLOSE, tr("Close"), chrome.close)
    }
}

@Composable
private fun CaptionButton(kind: Caption, label: String, onClick: () -> Unit) {
    val c = palette
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val isClose = kind == Caption.CLOSE
    val bg = when {
        isClose && pressed -> Color(0xFFF1707A)
        isClose && hovered -> Color(0xFFE81123)
        pressed -> c.text.copy(alpha = 0.16f)
        hovered -> c.text.copy(alpha = 0.09f)
        else -> Color.Transparent
    }
    val glyph = if (isClose && (hovered || pressed)) Color.White else c.text
    Box(
        Modifier.width(46.dp).fillMaxHeight().background(bg)
            .hoverable(source)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(10.dp)) {
            val s = size.width
            val stroke = 1.dp.toPx()
            when (kind) {
                Caption.MINIMIZE -> drawLine(glyph, Offset(0f, s / 2), Offset(s, s / 2), stroke)
                Caption.MAXIMIZE -> drawRect(glyph, style = Stroke(stroke))
                Caption.RESTORE -> {
                    val o = s * 0.2f
                    drawRect(glyph, topLeft = Offset(0f, o), size = androidx.compose.ui.geometry.Size(s - o, s - o), style = Stroke(stroke))
                    drawLine(glyph, Offset(o, 0f), Offset(s, 0f), stroke)
                    drawLine(glyph, Offset(s, 0f), Offset(s, s - o), stroke)
                }
                Caption.CLOSE -> {
                    drawLine(glyph, Offset(0f, 0f), Offset(s, s), stroke)
                    drawLine(glyph, Offset(s, 0f), Offset(0f, s), stroke)
                }
            }
        }
    }
}

private const val LEFT = 1
private const val RIGHT = 2
private const val TOP = 4
private const val BOTTOM = 8

/**
 * Resize handles along the edges of an undecorated window. Uses screen coordinates from AWT,
 * so the handle doesn't jitter while the window moves under it.
 */
@Composable
fun BoxScope.ResizeEdges(window: Window) {
    val e = 5.dp
    Edge(window, LEFT, Cursor.W_RESIZE_CURSOR, Modifier.align(Alignment.CenterStart).width(e).fillMaxHeight())
    Edge(window, RIGHT, Cursor.E_RESIZE_CURSOR, Modifier.align(Alignment.CenterEnd).width(e).fillMaxHeight())
    Edge(window, TOP, Cursor.N_RESIZE_CURSOR, Modifier.align(Alignment.TopCenter).height(e).fillMaxWidth())
    Edge(window, BOTTOM, Cursor.S_RESIZE_CURSOR, Modifier.align(Alignment.BottomCenter).height(e).fillMaxWidth())
    val corner = 10.dp
    Edge(window, TOP or LEFT, Cursor.NW_RESIZE_CURSOR, Modifier.align(Alignment.TopStart).size(corner))
    Edge(window, TOP or RIGHT, Cursor.NE_RESIZE_CURSOR, Modifier.align(Alignment.TopEnd).size(corner))
    Edge(window, BOTTOM or LEFT, Cursor.SW_RESIZE_CURSOR, Modifier.align(Alignment.BottomStart).size(corner))
    Edge(window, BOTTOM or RIGHT, Cursor.SE_RESIZE_CURSOR, Modifier.align(Alignment.BottomEnd).size(corner))
}

@Composable
private fun Edge(window: Window, edges: Int, cursor: Int, modifier: Modifier) {
    Box(
        modifier
            .pointerHoverIcon(PointerIcon(Cursor(cursor)))
            .pointerInput(window, edges) {
                var start = java.awt.Point()
                var from = java.awt.Rectangle()
                detectDragGestures(
                    onDragStart = {
                        start = MouseInfo.getPointerInfo().location
                        from = window.bounds
                    },
                ) { change, _ ->
                    change.consume()
                    val p = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                    val dx = p.x - start.x
                    val dy = p.y - start.y
                    val min = window.minimumSize
                    var x = from.x
                    var y = from.y
                    var w = from.width
                    var h = from.height
                    if (edges and LEFT != 0) { w = (from.width - dx).coerceAtLeast(min.width); x = from.x + from.width - w }
                    if (edges and RIGHT != 0) w = (from.width + dx).coerceAtLeast(min.width)
                    if (edges and TOP != 0) { h = (from.height - dy).coerceAtLeast(min.height); y = from.y + from.height - h }
                    if (edges and BOTTOM != 0) h = (from.height + dy).coerceAtLeast(min.height)
                    window.setBounds(x, y, w, h)
                }
            },
    )
}
