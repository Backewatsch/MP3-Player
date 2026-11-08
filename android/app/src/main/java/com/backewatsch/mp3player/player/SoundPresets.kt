package com.backewatsch.mp3player.player

import kotlin.math.ln

/** Klang-Voreinstellungen und Lautstärke-Rechnung (ohne Android-Abhängigkeiten, getestet). */
object SoundPresets {
    /** Eine Klangkurve: Stützpunkte Frequenz (Hz) → Verstärkung (dB) */
    data class Preset(val name: String, val curve: List<Pair<Int, Float>>)

    const val CUSTOM = "Eigene"

    val all = listOf(
        Preset("Normal", listOf(60 to 0f, 14_000 to 0f)),
        Preset("Bass-Boost", listOf(60 to 6f, 150 to 4.5f, 400 to 1.5f, 1_000 to 0f, 14_000 to 0f)),
        Preset("Pop", listOf(60 to -1f, 230 to 2f, 910 to 4f, 3_600 to 2f, 14_000 to -1f)),
        Preset("Rock", listOf(60 to 5f, 230 to 3f, 910 to -1f, 3_600 to 3f, 14_000 to 5f)),
        Preset("Hip-Hop", listOf(60 to 5f, 230 to 4f, 910 to 0f, 3_600 to 1f, 14_000 to 3f)),
        Preset("Elektro", listOf(60 to 5f, 230 to 2f, 910 to 0f, 3_600 to 2f, 14_000 to 5f)),
        Preset("Gesang", listOf(60 to -2f, 230 to 0f, 910 to 3f, 3_600 to 4f, 14_000 to 1f)),
        Preset("Klassik", listOf(60 to 4f, 230 to 2f, 910 to -1f, 3_600 to 2f, 14_000 to 4f)),
        Preset("Höhen", listOf(60 to 0f, 910 to 0f, 3_600 to 3f, 14_000 to 6f)),
    )

    fun byName(name: String) = all.firstOrNull { it.name == name }

    /** Verstärkung in dB bei [hz] – lineare Interpolation auf logarithmischer Frequenzachse */
    fun gainAt(preset: Preset, hz: Int): Float {
        val c = preset.curve
        if (hz <= c.first().first) return c.first().second
        if (hz >= c.last().first) return c.last().second
        for (i in 0 until c.lastIndex) {
            val (f1, g1) = c[i]
            val (f2, g2) = c[i + 1]
            if (hz in f1..f2) {
                val t = ((ln(hz.toDouble()) - ln(f1.toDouble())) / (ln(f2.toDouble()) - ln(f1.toDouble()))).toFloat()
                return g1 + (g2 - g1) * t
            }
        }
        return 0f
    }

    /** Bandpegel in Millibel für die Bänder des Geräts, begrenzt auf dessen Bereich */
    fun levelsFor(preset: Preset, centerHz: List<Int>, minMb: Int, maxMb: Int): List<Int> =
        centerHz.map { (gainAt(preset, it) * 100).toInt().coerceIn(minMb, maxMb) }

    /**
     * Lautstärke 0…1 aus Ein-/Ausblenden am Liedanfang/-ende und dem Schlafmodus.
     * @param fadeMs Dauer des Ein-/Ausblendens (0 = aus); nur bei Liedern, die mindestens dreimal so lang sind
     */
    fun volume(positionMs: Long, durationMs: Long, fadeMs: Long, sleepRemainingMs: Long?): Float {
        var v = 1f
        if (fadeMs > 0 && durationMs >= fadeMs * 3) {
            if (positionMs < fadeMs) v = minOf(v, positionMs.coerceAtLeast(0).toFloat() / fadeMs)
            val remaining = durationMs - positionMs
            if (remaining < fadeMs) v = minOf(v, remaining.coerceAtLeast(0).toFloat() / fadeMs)
        }
        if (sleepRemainingMs != null) v *= SleepTimer.volumeFor(sleepRemainingMs)
        return v.coerceIn(0f, 1f)
    }
}
