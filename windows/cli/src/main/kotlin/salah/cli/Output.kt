package salah.cli

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream

/** A run of text with one style. */
data class Span(val text: String, val kind: Kind = Kind.NORMAL) {
    enum class Kind { NORMAL, BOLD, DIM, ACCENT }

    companion object {
        fun normal(t: String) = Span(t, Kind.NORMAL)
        fun bold(t: String) = Span(t, Kind.BOLD)
        fun dim(t: String) = Span(t, Kind.DIM)
        fun accent(t: String) = Span(t, Kind.ACCENT)
    }
}

typealias Line = List<Span>

enum class OutputMode { PRETTY, PLAIN, COMPACT, JSON }

/** Renders sections of lines as a rounded box (pretty) or as bare text (plain). */
class Renderer(val mode: OutputMode, val color: Boolean) {
    fun render(sections: List<List<Line>>): String {
        val style = Style(color && mode == OutputMode.PRETTY)
        val nonEmpty = sections.filter { it.isNotEmpty() }
        if (mode != OutputMode.PRETTY) {
            return nonEmpty.joinToString("\n\n") { s -> s.joinToString("\n") { plainText(it) } } + "\n"
        }
        val widest = nonEmpty.flatten().maxOfOrNull { width(it) } ?: 0
        val inner = maxOf(MIN_INNER_WIDTH, widest + 4)
        val out = mutableListOf("╭" + "─".repeat(inner) + "╮")
        nonEmpty.forEachIndexed { i, section ->
            if (i > 0) out += "├" + "─".repeat(inner) + "┤"
            for (line in section) {
                val pad = inner - 2 - width(line)
                out += "│  " + styled(line, style) + " ".repeat(maxOf(0, pad)) + "│"
            }
        }
        out += "╰" + "─".repeat(inner) + "╯"
        return out.joinToString("\n") + "\n"
    }

    companion object {
        const val MIN_INNER_WIDTH = 44

        fun width(line: Line): Int = line.sumOf { it.text.codePointCount(0, it.text.length) }

        fun plainText(line: Line): String = line.joinToString("") { it.text }.trimEnd()

        fun styled(line: Line, style: Style): String = line.joinToString("") { s ->
            when (s.kind) {
                Span.Kind.NORMAL -> s.text
                Span.Kind.BOLD -> style.bold(s.text)
                Span.Kind.DIM -> style.dim(s.text)
                Span.Kind.ACCENT -> style.accent(s.text)
            }
        }

        /** Left-aligns [s] in a column of [width] characters. */
        fun pad(s: String, width: Int): String = if (s.length >= width) s else s + " ".repeat(width - s.length)
    }
}

/** ANSI styling that collapses to plain text when color is off. */
class Style(private val enabled: Boolean) {
    fun bold(s: String) = wrap(s, "1")
    fun dim(s: String) = wrap(s, "2")
    fun accent(s: String) = wrap(s, "1;31")
    private fun wrap(s: String, code: String) = if (enabled && s.isNotEmpty()) "\u001B[${code}m$s\u001B[0m" else s
}

object Terminal {
    /** UTF-8 regardless of the Windows console code page; salah.cmd also switches the console to 65001. */
    val out = PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8)
    val err = PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8)

    /** Set by salah.cmd, which knows whether stdout is redirected; otherwise ask the JVM. */
    val stdoutIsTTY: Boolean
        get() = System.getenv("SALAH_TTY")?.let { it == "1" } ?: (System.console() != null)

    val stdinIsTTY: Boolean get() = System.console() != null

    /** Color only on an interactive stdout, and never when NO_COLOR is set or TERM=dumb. */
    fun colorAllowed(env: Map<String, String> = System.getenv(), isTTY: Boolean = stdoutIsTTY): Boolean {
        if (!isTTY) return false
        if (!env["NO_COLOR"].isNullOrEmpty()) return false
        if (env["TERM"] == "dumb") return false
        return true
    }

    fun printError(message: String) = err.println("salah: $message")

    fun write(s: String) {
        out.print(s)
        out.flush()
    }
}
