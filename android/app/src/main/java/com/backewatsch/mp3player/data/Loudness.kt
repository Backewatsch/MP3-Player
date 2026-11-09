package com.backewatsch.mp3player.data

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Misst, wie laut ein Lied ist (RMS in Blöcken, stille Stellen zählen nicht), und berechnet die Verstärkung,
 * damit alle Lieder ähnlich laut klingen – ohne Android, getestet.
 */
class LoudnessMeter(private val sampleRate: Int, private val channels: Int) {
    private val blockSamples = maxOf(1, sampleRate / 10) * maxOf(1, channels) // 100 ms
    private var blockSum = 0.0
    private var blockCount = 0
    private var gatedSum = 0.0
    private var gatedBlocks = 0

    fun add(samples: ShortArray, count: Int = samples.size) {
        for (i in 0 until count) {
            val v = samples[i] / 32768.0
            blockSum += v * v
            if (++blockCount == blockSamples) finishBlock()
        }
    }

    private fun finishBlock() {
        val ms = blockSum / blockCount
        // Stille (unter −60 dBFS) nicht mitzählen
        if (ms > 1e-6) {
            gatedSum += ms
            gatedBlocks++
        }
        blockSum = 0.0
        blockCount = 0
    }

    /** Lautheit in dBFS (RMS), null wenn das Lied nur Stille ist */
    fun loudnessDb(): Double? {
        if (blockCount > blockSamples / 2) finishBlock()
        if (gatedBlocks == 0) return null
        return 20 * log10(sqrt(gatedSum / gatedBlocks))
    }

    companion object {
        /** Ziel-Lautheit (ähnlich wie Streaming-Dienste) */
        const val TARGET_DB = -14.0

        /** Verstärkung in dB, begrenzt auf −12 … +6 dB */
        fun gainFor(loudnessDb: Double): Float = (TARGET_DB - loudnessDb).coerceIn(-12.0, 6.0).toFloat()

        /** Lautstärke-Faktor für Absenkungen (Anhebungen macht der LoudnessEnhancer) */
        fun attenuation(gainDb: Float): Float = if (gainDb >= 0f) 1f else Math.pow(10.0, gainDb / 20.0).toFloat()
    }
}
