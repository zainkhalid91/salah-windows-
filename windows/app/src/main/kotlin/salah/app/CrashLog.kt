package salah.app

import salah.core.Platform
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import javax.swing.JOptionPane

/** Writes startup and uncaught errors to `crash.log` next to config.json, so failures are diagnosable. */
object CrashLog {
    val file: File get() = File(Platform.configDirectory(), "crash.log")

    fun write(e: Throwable, context: String) {
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        val entry = buildString {
            appendLine("=== ${Instant.now()} · $context")
            appendLine("Salah ${salah.core.SalahInfo.VERSION} · ${System.getProperty("os.name")} ${System.getProperty("os.version")} · Java ${System.getProperty("java.version")}")
            appendLine(trace)
        }
        System.err.print(entry)
        runCatching {
            file.parentFile.mkdirs()
            file.appendText(entry)
        }
    }

    fun showDialog(e: Throwable) {
        runCatching {
            JOptionPane.showMessageDialog(
                null,
                "Salah couldn't start: ${e.javaClass.simpleName}: ${e.message}\n\nDetails were saved to:\n${file.absolutePath}",
                "Salah",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }
}
