package salah.core

import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.concurrent.thread

/**
 * Single-instance coordination between Salah.exe and the CLI, without native code.
 *
 * The running app holds an exclusive lock on `app.lock` next to config.json (released by the OS
 * if the process dies) and listens on a loopback port written to `app.port`. A second launch
 * sends "show" to that port and exits; the CLI only checks the lock to say whether the app runs.
 */
object AppInstance {
    private val dir: Path get() = ConfigStore.defaultPath().toAbsolutePath().parent ?: Paths.get(Platform.configDirectory())
    private val lockPath: Path get() = dir.resolve("app.lock")
    private val portPath: Path get() = dir.resolve("app.port")

    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    /** True when another process holds the app lock. */
    fun isRunning(): Boolean {
        if (lock != null) return true
        return try {
            Files.createDirectories(dir)
            RandomAccessFile(lockPath.toFile(), "rw").channel.use { ch ->
                val l = try { ch.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (l == null) true else { l.release(); false }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Claims the single instance. Returns false if another app instance holds it, after asking
     * that instance to show its window. [onShowRequest] runs on a background thread.
     */
    fun claim(onShowRequest: () -> Unit): Boolean {
        Files.createDirectories(dir)
        val ch = RandomAccessFile(lockPath.toFile(), "rw").channel
        val l = try { ch.tryLock() } catch (_: OverlappingFileLockException) { null }
        if (l == null) {
            ch.close()
            signalExisting()
            return false
        }
        channel = ch
        lock = l
        runCatching {
            val s = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
            server = s
            Files.writeString(portPath, s.localPort.toString())
            thread(isDaemon = true, name = "salah-instance") {
                while (!s.isClosed) {
                    runCatching {
                        s.accept().use { c ->
                            val msg = c.getInputStream().bufferedReader().readLine()
                            if (msg == "show") onShowRequest()
                        }
                    }
                }
            }
        }
        return true
    }

    fun release() {
        runCatching { server?.close() }
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        runCatching { Files.deleteIfExists(portPath) }
        lock = null
    }

    private fun signalExisting() {
        runCatching {
            val port = Files.readString(portPath).trim().toInt()
            Socket(InetAddress.getLoopbackAddress(), port).use { it.getOutputStream().write("show\n".toByteArray()) }
        }
    }
}
