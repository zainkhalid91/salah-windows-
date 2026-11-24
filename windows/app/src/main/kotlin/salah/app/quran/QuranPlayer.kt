package salah.app.quran

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.Header
import javazoom.jl.decoder.SampleBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import salah.core.SalahInfo
import salah.core.quran.QueueSource
import salah.core.quran.QuranCatalog
import salah.core.quran.QuranQueue
import salah.core.quran.QuranRepeatMode
import salah.core.quran.QuranSettings
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.prefs.Preferences
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

/**
 * The app's single Quran player. Recitations stream from mp3quran.net and are decoded in pure Java
 * (JLayer) into javax.sound, so the installer ships nothing native. Seeking re-opens the stream at
 * the matching byte with an HTTP Range request (the recitations are constant bitrate).
 *
 * The queue rules live in core ([QuranQueue]); this class only plays its current entry. Nothing is
 * fetched until the user presses play. The queue and position are saved per Windows user, so the
 * next launch shows where they left off, paused.
 */
class QuranPlayer(
    private val scope: CoroutineScope,
    private val settings: () -> QuranSettings,
    private val saveSettings: ((QuranSettings) -> QuranSettings) -> Unit,
    /** False for offscreen snapshots: nothing is restored or saved. */
    private val live: Boolean = true,
) {
    var queue by mutableStateOf<QuranQueue?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isBuffering by mutableStateOf(false)
        private set
    /** Shown under the player, e.g. when the recitation server can't be reached. */
    var error by mutableStateOf<String?>(null)
        private set
    /** Bumped on every queue change, since [QuranQueue] is mutable. */
    var revision by mutableStateOf(0)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)
        private set

    val current: Int? get() = queue?.current

    private val prefs = runCatching { Preferences.userRoot().node("salah") }.getOrNull()
    private val json = Json { ignoreUnknownKeys = true }
    private var session: Session? = null
    private var generation = 0
    /** Bitrate per URL, learned on first play, so a seek opens the stream only once. */
    private val bitrates = java.util.concurrent.ConcurrentHashMap<String, Int>()

    init {
        if (live) restore()
        scope.launch {
            var ticks = 0
            while (true) {
                delay(250)
                val s = session
                if (s != null && isPlaying) {
                    positionMs = s.positionMs()
                    if (s.durationMs > 0) durationMs = s.durationMs
                    isBuffering = s.buffering
                    // Save every 10 seconds while playing, so a crash resumes close by.
                    if (++ticks % 40 == 0) save()
                }
            }
        }
    }

    // region Controls

    /** Starts a new queue at [startAt] and plays it. */
    fun play(items: List<Int>, startAt: Int = 0, source: QueueSource = QueueSource.SurahList) {
        if (items.isEmpty()) return
        val q = QuranQueue(items, startAt, source)
        if (settings().shuffle) q.setShuffle(true, System.nanoTime())
        queue = q
        startCurrent(0L)
        changed()
    }

    /** Loads a queue without playing it: for design snapshots. */
    internal fun loadPaused(items: List<Int>, startAt: Int) {
        queue = QuranQueue(items, startAt)
        changed()
    }

    fun togglePlay() = if (isPlaying) pause() else resume()

    fun resume() {
        if (queue?.current == null) return
        val s = session
        if (s != null && s.alive && error == null) {
            s.paused = false
            isPlaying = true
        } else {
            startCurrent(positionMs)
        }
    }

    fun pause() {
        session?.paused = true
        isPlaying = false
        session?.let { positionMs = it.positionMs() }
        save()
    }

    fun next() = advance(userInitiated = true)

    fun previous() {
        val q = queue ?: return
        val target = q.previous(positionMs / 1000.0)
        if (target == null) return
        startCurrent(0L)
        changed()
    }

    fun seekTo(ms: Long) {
        val d = durationMs
        val to = if (d > 0) ms.coerceIn(0, d) else ms.coerceAtLeast(0)
        positionMs = to
        if (isPlaying) startCurrent(to) else {
            stopSession()
            save()
        }
    }

    /** Jumps to the entry at [position] in the queue. */
    fun jump(position: Int) {
        val q = queue ?: return
        q.jump(position)
        startCurrent(0L)
        changed()
    }

    fun playNext(surah: Int) {
        val q = queue ?: return play(listOf(surah))
        q.playNext(surah)
        changed()
    }

    fun append(surah: Int) {
        val q = queue ?: return play(listOf(surah))
        q.append(surah)
        changed()
    }

    fun remove(position: Int) {
        val q = queue ?: return
        if (position == q.index) return
        q.remove(position)
        changed()
    }

    fun moveUpcoming(from: Int, to: Int) {
        val q = queue ?: return
        if (from <= q.index || to <= q.index) return
        q.moveUpcoming(from, to)
        changed()
    }

    fun clearUpcoming() {
        queue?.clearUpcoming()
        changed()
    }

    fun setRepeat(mode: QuranRepeatMode) = saveSettings { it.copy(repeatMode = mode) }

    /** Shuffles what's after the current surah (or restores the order), keeping the current one playing. */
    fun setShuffle(on: Boolean) {
        saveSettings { it.copy(shuffle = on) }
        queue?.setShuffle(on, System.nanoTime())
        changed()
    }

    /** A new reciter applies from the current surah, at the same spot. */
    fun reciterChanged() {
        if (queue == null) return
        if (isPlaying) startCurrent(positionMs) else stopSession()
    }

    /** At prayer time: pauses if playing. Returns true if it paused. */
    fun pauseForPrayer(): Boolean {
        if (!isPlaying) return false
        pause()
        return true
    }

    fun dispose() {
        save()
        stopSession()
    }

    // endregion

    // region Engine

    private fun advance(userInitiated: Boolean) {
        val q = queue ?: return
        val next = q.next(settings().repeatMode, userInitiated)
        if (next == null) {
            // End of the queue: stop at the start of the last surah.
            stopSession()
            isPlaying = false
            positionMs = 0
            save()
            return
        }
        startCurrent(0L)
        changed()
    }

    private fun startCurrent(fromMs: Long) {
        val q = queue ?: return
        val surah = q.current ?: return
        val url = QuranCatalog.audioUrl(settings().reciter, surah)
        stopSession()
        positionMs = fromMs
        durationMs = 0
        error = null
        if (url == null) {
            error = "This reciter doesn't have this surah."
            isPlaying = false
            return
        }
        val gen = ++generation
        isPlaying = true
        isBuffering = true
        session = Session(url, fromMs, gen).also { it.start() }
    }

    private fun stopSession() {
        generation++
        session?.stop()
        session = null
    }

    private fun onEnded(gen: Int) = scope.launch {
        if (gen != generation) return@launch
        advance(userInitiated = false)
    }

    private fun onFailed(gen: Int, message: String) = scope.launch {
        if (gen != generation) return@launch
        session?.let { positionMs = it.positionMs() }
        session = null
        isPlaying = false
        isBuffering = false
        error = message
    }

    /**
     * One stream being played on its own thread. Pausing stops the audio line and parks the
     * thread; a long pause can drop the connection, which [resume] handles by re-opening.
     */
    private inner class Session(private val url: String, private val startMs: Long, private val gen: Int) {
        @Volatile var paused = false
        @Volatile var alive = true
        @Volatile var buffering = true
        @Volatile var durationMs = 0L
        @Volatile private var decodedMs = startMs.toDouble()
        @Volatile private var line: SourceDataLine? = null
        @Volatile private var input: InputStream? = null
        @Volatile private var bytesPerMs = 0.0
        private val lock = Object()

        fun start() {
            thread(isDaemon = true, name = "salah-quran") {
                try {
                    run()
                } catch (e: Exception) {
                    if (alive) {
                        System.err.println("Quran stream failed: $url: $e")
                        if (System.getProperty("salah.quranDebug") != null) e.printStackTrace()
                        onFailed(gen, "Can't reach the recitation server. Check your connection.")
                    }
                } finally {
                    alive = false
                    runCatching { line?.close() }
                    runCatching { input?.close() }
                }
            }
        }

        fun stop() {
            alive = false
            synchronized(lock) { lock.notifyAll() }
            runCatching { input?.close() }
            runCatching { line?.stop(); line?.flush() }
        }

        /** What's audible: decoded minus what's still queued in the line's buffer. */
        fun positionMs(): Long {
            val l = line
            val queued = if (l != null && bytesPerMs > 0) (l.bufferSize - l.available()) / bytesPerMs else 0.0
            return (decodedMs - queued).toLong().coerceAtLeast(0)
        }

        private fun open(offset: Long): Pair<InputStream, Long> {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "Salah-Windows/${SalahInfo.VERSION}")
            if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
            val code = conn.responseCode
            check(code in 200..299) { "HTTP $code" }
            // "bytes 1000-5000/5001" gives the full size; otherwise the length plus the offset.
            val total = conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: conn.contentLengthLong.takeIf { it > 0 }?.let { if (code == 206) it + offset else it }
                ?: -1L
            val stream = BufferedInputStream(conn.inputStream, 64 * 1024)
            if (offset == 0L) skipId3(stream)
            return stream to total
        }

        /**
         * Skips a leading ID3v2 tag. JLayer tries to, but a single short read over the network
         * leaves it inside the tag, where it syncs on junk (and reads a bitrate of 0).
         */
        private fun skipId3(stream: BufferedInputStream) {
            stream.mark(10)
            val h = stream.readNBytes(10)
            if (h.size == 10 && h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte()) {
                val size = ((h[6].toInt() and 0x7f) shl 21) or ((h[7].toInt() and 0x7f) shl 14) or ((h[8].toInt() and 0x7f) shl 7) or (h[9].toInt() and 0x7f)
                val footer = if (h[5].toInt() and 0x10 != 0) 10 else 0
                stream.skipNBytes((size + footer).toLong())
            } else {
                stream.reset()
            }
        }

        /** The next real MPEG audio frame, stepping over bad syncs (common right after a seek). */
        private fun nextFrame(bitstream: Bitstream): Header? {
            var misses = 0
            while (alive) {
                val h = try {
                    bitstream.readFrame()
                } catch (e: javazoom.jl.decoder.BitstreamException) {
                    if (++misses > 50) throw e
                    continue
                } ?: return null
                if (h.layer() == 3 && h.bitrate() > 0) return h
                bitstream.closeFrame()
                if (++misses > 50) error("not an MP3 stream")
            }
            return null
        }

        private fun run() {
            val known = bitrates[url]
            if (startMs > 0 && known != null) {
                // Seek: jump straight to the byte for this time.
                val offset = startMs * known / 8_000L
                val (stream, total) = open(offset)
                input = stream
                if (total > 0) durationMs = total * 8_000L / known
                if (total in 1..offset) return finish()
                return decodeLoop(Bitstream(stream), null)
            }
            // The first frame gives the bitrate, which turns a time into a byte offset.
            var (stream, total) = open(0)
            input = stream
            var bitstream = Bitstream(stream)
            val first: Header = nextFrame(bitstream) ?: return finish()
            val bitrate = first.bitrate()
            bitrates[url] = bitrate
            if (total > 0) durationMs = total * 8_000L / bitrate
            if (startMs <= 0) return decodeLoop(bitstream, first)
            bitstream.closeFrame()
            runCatching { bitstream.close() }
            val offset = startMs * bitrate / 8_000L
            if (total in 1..offset) return finish()
            val reopened = open(offset)
            stream = reopened.first
            input = stream
            bitstream = Bitstream(stream)
            decodeLoop(bitstream, null)
        }

        private fun decodeLoop(bitstream: Bitstream, pending: Header?) {
            val decoder = Decoder()
            var header = pending
            while (alive) {
                if (paused) {
                    line?.stop()
                    synchronized(lock) { while (paused && alive) lock.wait(500) }
                    if (!alive) return
                    line?.start()
                }
                val h = header ?: nextFrame(bitstream) ?: break
                header = null
                val samples = try {
                    decoder.decodeFrame(h, bitstream) as SampleBuffer
                } catch (e: Exception) {
                    // A damaged frame (or a mid-frame start after seeking): skip it.
                    bitstream.closeFrame()
                    continue
                }
                val l = line ?: openLine(samples).also { line = it }
                val pcm = toBytes(samples)
                l.write(pcm, 0, pcm.size)
                buffering = false
                decodedMs += h.ms_per_frame()
                bitstream.closeFrame()
            }
            if (alive) finish()
        }

        private fun finish() {
            line?.drain()
            if (alive) onEnded(gen)
        }

        private fun openLine(s: SampleBuffer): SourceDataLine {
            val format = AudioFormat(s.sampleFrequency.toFloat(), 16, s.channelCount, true, false)
            bytesPerMs = s.sampleFrequency * s.channelCount * 2 / 1000.0
            val l = AudioSystem.getSourceDataLine(format)
            // About half a second of audio: smooth over network hiccups, quick to pause.
            l.open(format, (bytesPerMs * 500).toInt().let { it - it % (2 * s.channelCount) })
            l.start()
            return l
        }

        private fun toBytes(s: SampleBuffer): ByteArray {
            val n = s.bufferLength
            val buf = s.buffer
            val out = ByteArray(n * 2)
            for (i in 0 until n) {
                val v = buf[i].toInt()
                out[2 * i] = v.toByte()
                out[2 * i + 1] = (v shr 8).toByte()
            }
            return out
        }
    }

    // endregion

    // region Persistence

    private fun changed() {
        revision++
        save()
    }

    private fun save() {
        if (!live) return
        val p = prefs ?: return
        runCatching {
            val q = queue
            if (q == null) p.remove(KEY_QUEUE) else p.put(KEY_QUEUE, json.encodeToString(QuranQueue.serializer(), q))
            p.putLong(KEY_POSITION, positionMs)
        }
    }

    private fun restore() {
        val text = runCatching { prefs?.get(KEY_QUEUE, null) }.getOrNull() ?: return
        queue = runCatching { json.decodeFromString(QuranQueue.serializer(), text) }.getOrNull()?.takeUnless { it.isEmpty }
        positionMs = runCatching { prefs?.getLong(KEY_POSITION, 0L) }.getOrNull() ?: 0L
        revision++
    }

    // endregion

    companion object {
        private const val KEY_QUEUE = "quranQueue"
        private const val KEY_POSITION = "quranPosition"
    }
}
