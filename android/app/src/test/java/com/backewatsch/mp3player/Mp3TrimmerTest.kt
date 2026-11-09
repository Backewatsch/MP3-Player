package com.backewatsch.mp3player

import com.backewatsch.mp3player.data.Mp3Trimmer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Verlustfreies Zuschneiden: nur ganze Frames, Tags bleiben, Längenangabe stimmt */
class Mp3TrimmerTest {
    /** MPEG-1 Layer III, 128 kbit/s, 44,1 kHz, Stereo → 417 Bytes, 1152 Samples (≈ 26,1 ms) */
    private fun frame(fill: Int): ByteArray = ByteArray(417).also {
        it[0] = 0xFF.toByte(); it[1] = 0xFB.toByte(); it[2] = 0x90.toByte(); it[3] = 0x00
        for (i in 4 until it.size) it[i] = fill.toByte()
    }

    private fun xingFrame(frames: Int, bytes: Int): ByteArray = ByteArray(417).also {
        it[0] = 0xFF.toByte(); it[1] = 0xFB.toByte(); it[2] = 0x90.toByte(); it[3] = 0x00
        "Info".forEachIndexed { i, c -> it[36 + i] = c.code.toByte() }
        ByteBuffer.wrap(it, 40, 12).putInt(3).putInt(frames).putInt(bytes)
    }

    private val id3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 20) + ByteArray(20) { 7 }
    private val id3v1 = byteArrayOf('T'.code.toByte(), 'A'.code.toByte(), 'G'.code.toByte()) + ByteArray(125) { 1 }

    private fun song(frames: Int, withInfo: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id3)
        if (withInfo) out.write(xingFrame(frames, 417 * (frames + 1)))
        repeat(frames) { out.write(frame(it % 100)) }
        out.write(id3v1)
        return out.toByteArray()
    }

    @Test fun readsDuration() {
        val p = Mp3Trimmer.parse(song(383, withInfo = true)) // 383 × 26,12 ms ≈ 10 s
        assertEquals(383, p.frames.size)
        assertEquals(10_004L, p.durationMs)
        assertTrue(p.infoFrame != null)
    }

    @Test fun cutsTheLastSecondsAndKeepsTags() {
        val original = song(383, withInfo = true)
        val trimmed = Mp3Trimmer.trim(original, 0, 10_004 - 3_000)
        val p = Mp3Trimmer.parse(trimmed)
        assertTrue("≈ 7 s übrig, war ${p.durationMs}", p.durationMs in 6_980..7_020)
        // ID3v2 vorne und ID3v1 hinten unverändert
        assertArrayEquals(id3, trimmed.copyOfRange(0, id3.size))
        assertArrayEquals(id3v1, trimmed.copyOfRange(trimmed.size - 128, trimmed.size))
        // Info-Kopf an neue Länge angepasst
        val info = ByteBuffer.wrap(trimmed, id3.size + 44, 8)
        assertEquals(p.frames.size, info.int)
        assertEquals(417 * (p.frames.size + 1), info.int)
        // Erste Frames sind die ursprünglichen
        assertEquals(0.toByte(), trimmed[id3.size + 417 + 4])
    }

    @Test fun cutsStartAndEnd() {
        val trimmed = Mp3Trimmer.trim(song(383, withInfo = false), 2_000, 5_000)
        val p = Mp3Trimmer.parse(trimmed)
        assertTrue("≈ 3 s, war ${p.durationMs}", p.durationMs in 2_970..3_030)
        // Erster behaltener Frame ist Frame Nr. 77 (Zählung ab 0) (2 s / 26,12 ms)
        assertEquals(77.toByte(), trimmed[id3.size + 4])
    }

    @Test(expected = Mp3Trimmer.Mp3Exception::class)
    fun refusesToCutEverything() {
        Mp3Trimmer.trim(song(100, withInfo = false), 1_000, 1_500)
    }

    @Test(expected = Mp3Trimmer.Mp3Exception::class)
    fun rejectsNonMp3() {
        Mp3Trimmer.parse(ByteArray(5000) { 0x41 })
    }
}
