package salah.app.quran

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import salah.core.quran.QuranSettings

/** `Salah --quran-probe`: plays two short surahs, seeks, and reports, to check streaming on a PC. */
internal object QuranProbe {
    fun run() {
        var settings = QuranSettings()
        val p = QuranPlayer(CoroutineScope(SupervisorJob() + Dispatchers.Default), { settings }, { settings = it(settings) }, live = false)
        fun report(what: String) = println("$what: surah=${p.current} playing=${p.isPlaying} buffering=${p.isBuffering} pos=${p.positionMs}ms dur=${p.durationMs}ms error=${p.error}")
        p.play(listOf(108, 112))
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 8_000) {
            Thread.sleep(500)
            report("t=${System.currentTimeMillis() - t0}ms")
        }
        p.seekTo(12_000)
        Thread.sleep(3_000); report("after seek to 12 s + 3 s")
        p.pause()
        Thread.sleep(1_500); report("paused 1.5 s")
        p.resume()
        // Al-Kawthar is about 20 s; wait for it to end and move on.
        repeat(30) {
            Thread.sleep(1_000)
            if (p.current == 112 && p.positionMs > 1_000) { report("advanced"); p.dispose(); return }
        }
        report("did not advance")
        p.dispose()
    }
}
