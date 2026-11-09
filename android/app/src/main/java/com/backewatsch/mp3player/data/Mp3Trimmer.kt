package com.backewatsch.mp3player.data

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Schneidet MP3s verlustfrei zu: Es werden nur ganze MP3-Frames (je ca. 26 ms) weggelassen,
 * nichts wird neu kodiert. Tags (Titel, Cover, Songtext) bleiben erhalten, die Längenangabe
 * (Xing/Info-Header) wird an die neue Länge angepasst.
 */
object Mp3Trimmer {
    class Mp3Exception(message: String) : Exception(message)

    data class Frame(val offset: Int, val length: Int, val samples: Int, val sampleRate: Int) {
        val durationUs: Long get() = samples * 1_000_000L / sampleRate
    }

    class Parsed(
        val bytes: ByteArray,
        /** Alles vor dem ersten Frame (ID3v2-Tag) */
        val headerEnd: Int,
        /** Xing/Info/VBRI-Frame mit Längenangaben (kein Ton) */
        val infoFrame: Frame?,
        val infoTagPos: Int,
        val frames: List<Frame>,
        /** Alles nach dem letzten Frame (ID3v1/APE-Tag) */
        val tailStart: Int,
    ) {
        val durationMs: Long get() = frames.sumOf { it.durationUs } / 1000
    }

    private val BITRATES_V1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val BITRATES_V2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val SAMPLE_RATES = intArrayOf(44100, 48000, 32000)

    /** Liest den Frame-Kopf an [pos]; null, wenn dort kein gültiger MPEG-Layer-III-Frame beginnt */
    internal fun frameAt(b: ByteArray, pos: Int, end: Int): Frame? {
        if (pos + 4 > end) return null
        val b0 = b[pos].toInt() and 0xFF
        val b1 = b[pos + 1].toInt() and 0xFF
        val b2 = b[pos + 2].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
        val version = (b1 shr 3) and 3 // 0 = MPEG 2.5, 2 = MPEG 2, 3 = MPEG 1
        if (version == 1) return null
        if (((b1 shr 1) and 3) != 1) return null // nur Layer III
        val bitrateIndex = (b2 shr 4) and 0xF
        val rateIndex = (b2 shr 2) and 3
        if (bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null
        val padding = (b2 shr 1) and 1
        val mpeg1 = version == 3
        val sampleRate = SAMPLE_RATES[rateIndex] / when (version) { 3 -> 1; 2 -> 2; else -> 4 }
        val bitrate = (if (mpeg1) BITRATES_V1 else BITRATES_V2)[bitrateIndex] * 1000
        val length = (if (mpeg1) 144 else 72) * bitrate / sampleRate + padding
        if (length < 4 || pos + length > end) return null
        return Frame(pos, length, if (mpeg1) 1152 else 576, sampleRate)
    }

    private fun id3v2End(b: ByteArray): Int {
        var pos = 0
        while (pos + 10 <= b.size && b[pos] == 'I'.code.toByte() && b[pos + 1] == 'D'.code.toByte() && b[pos + 2] == '3'.code.toByte()) {
            val size = ((b[pos + 6].toInt() and 0x7F) shl 21) or ((b[pos + 7].toInt() and 0x7F) shl 14) or
                ((b[pos + 8].toInt() and 0x7F) shl 7) or (b[pos + 9].toInt() and 0x7F)
            val footer = if ((b[pos + 5].toInt() and 0x10) != 0) 10 else 0
            pos += 10 + size + footer
        }
        return pos.coerceAtMost(b.size)
    }

    /** Ende der Audiodaten: ID3v1 („TAG“) und APE-Tags am Ende gehören nicht dazu */
    private fun audioEnd(b: ByteArray): Int {
        var end = b.size
        if (end >= 128 && b[end - 128] == 'T'.code.toByte() && b[end - 127] == 'A'.code.toByte() && b[end - 126] == 'G'.code.toByte()) end -= 128
        return end
    }

    private fun ascii(b: ByteArray, pos: Int, text: String) =
        pos + text.length <= b.size && text.indices.all { b[pos + it] == text[it].code.toByte() }

    /** Position des Xing/Info-Kennworts im Frame (oder -1) */
    private fun xingPos(b: ByteArray, f: Frame): Int {
        val mpeg1 = f.samples == 1152
        val mono = ((b[f.offset + 3].toInt() and 0xFF) shr 6) == 3
        val side = if (mpeg1) (if (mono) 17 else 32) else (if (mono) 9 else 17)
        val pos = f.offset + 4 + side
        return if (ascii(b, pos, "Xing") || ascii(b, pos, "Info")) pos else -1
    }

    fun parse(bytes: ByteArray): Parsed {
        val start = id3v2End(bytes)
        val end = audioEnd(bytes)
        val frames = ArrayList<Frame>()
        var pos = start
        var headerEnd = -1
        while (pos < end) {
            val f = frameAt(bytes, pos, end)
            // Gültig, wenn direkt danach der nächste Frame beginnt (oder die Datei endet) – schützt vor Zufallstreffern
            if (f != null && (f.offset + f.length >= end || frameAt(bytes, f.offset + f.length, end) != null || frames.isNotEmpty())) {
                if (headerEnd < 0) headerEnd = pos
                frames += f
                pos += f.length
            } else {
                if (frames.isNotEmpty() && (ascii(bytes, pos, "TAG") || ascii(bytes, pos, "APETAGEX") || ascii(bytes, pos, "LYRICS"))) break
                pos++
            }
        }
        if (frames.isEmpty()) throw Mp3Exception("Keine MP3-Daten gefunden.")
        val tailStart = frames.last().let { it.offset + it.length }
        // Erster Frame mit Xing/Info/VBRI = nur Längenangaben, kein Ton
        val first = frames.first()
        var info: Frame? = null
        var infoPos = -1
        val xing = xingPos(bytes, first)
        if (xing >= 0) { info = first; infoPos = xing }
        else if (ascii(bytes, first.offset + 36, "VBRI")) { info = first }
        val audio = if (info != null) frames.drop(1) else frames
        return Parsed(bytes, headerEnd, info, infoPos, audio, tailStart)
    }

    fun durationMs(file: File): Long = parse(file.readBytes()).durationMs

    /** Behält den Teil von [startMs] bis [endMs]; liefert die neue Datei als Bytes */
    fun trim(bytes: ByteArray, startMs: Long, endMs: Long): ByteArray {
        val p = parse(bytes)
        val total = p.durationMs
        val from = startMs.coerceIn(0, total)
        val to = endMs.coerceIn(from, total)
        if (to - from < 1000) throw Mp3Exception("Es muss mindestens 1 Sekunde übrig bleiben.")

        // Frames, deren Mitte im gewünschten Bereich liegt
        val kept = ArrayList<Frame>()
        var t = 0L
        for (f in p.frames) {
            val mid = (t + f.durationUs / 2) / 1000
            if (mid in from until to) kept += f
            t += f.durationUs
        }
        val cutStart = kept.first() !== p.frames.first()
        val cutEnd = kept.last() !== p.frames.last()

        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, p.headerEnd)
        val info = p.infoFrame
        if (info != null && p.infoTagPos >= 0) {
            out.write(updatedXing(p, kept, cutStart, cutEnd))
        }
        // VBRI-Frames (selten) werden weggelassen – sonst stimmte die Länge nicht
        for (f in kept) out.write(bytes, f.offset, f.length)
        out.write(bytes, p.tailStart, bytes.size - p.tailStart)
        return out.toByteArray()
    }

    /** Xing/Info-Frame mit neuer Frame-Anzahl, Größe und Sprungtabelle */
    private fun updatedXing(p: Parsed, kept: List<Frame>, cutStart: Boolean, cutEnd: Boolean): ByteArray {
        val info = p.infoFrame!!
        val frame = p.bytes.copyOfRange(info.offset, info.offset + info.length)
        var pos = p.infoTagPos - info.offset
        val flags = readInt(frame, pos + 4)
        pos += 8
        val dataSize = info.length + kept.sumOf { it.length }
        if (flags and 1 != 0) { writeInt(frame, pos, kept.size); pos += 4 }
        if (flags and 2 != 0) { writeInt(frame, pos, dataSize); pos += 4 }
        if (flags and 4 != 0) {
            // Sprungtabelle: Position (0–255 von dataSize) bei 0 %, 1 % … 99 % der Länge
            val totalUs = kept.sumOf { it.durationUs }
            var idx = 0; var timeUs = 0L; var bytePos = info.length.toLong()
            for (i in 0 until 100) {
                val target = totalUs * i / 100
                while (idx < kept.size && timeUs + kept[idx].durationUs <= target) {
                    timeUs += kept[idx].durationUs; bytePos += kept[idx].length; idx++
                }
                frame[pos + i] = (bytePos * 256 / dataSize).coerceIn(0, 255).toByte()
            }
            pos += 100
        }
        if (flags and 8 != 0) pos += 4
        // LAME-Kopf: Encoder-Verzögerung/Auffüllung gelten nach dem Schnitt nicht mehr
        if (pos + 24 <= frame.size) {
            val p21 = pos + 21
            val v = ((frame[p21].toInt() and 0xFF) shl 16) or ((frame[p21 + 1].toInt() and 0xFF) shl 8) or (frame[p21 + 2].toInt() and 0xFF)
            var delay = v shr 12
            var padding = v and 0xFFF
            if (cutStart) delay = 0
            if (cutEnd) padding = 0
            val nv = (delay shl 12) or padding
            frame[p21] = (nv shr 16).toByte(); frame[p21 + 1] = (nv shr 8).toByte(); frame[p21 + 2] = nv.toByte()
        }
        return frame
    }

    private fun readInt(b: ByteArray, pos: Int) =
        ((b[pos].toInt() and 0xFF) shl 24) or ((b[pos + 1].toInt() and 0xFF) shl 16) or
            ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)

    private fun writeInt(b: ByteArray, pos: Int, v: Int) {
        b[pos] = (v shr 24).toByte(); b[pos + 1] = (v shr 16).toByte(); b[pos + 2] = (v shr 8).toByte(); b[pos + 3] = v.toByte()
    }
}
