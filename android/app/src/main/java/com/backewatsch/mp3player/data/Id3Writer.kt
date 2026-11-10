package com.backewatsch.mp3player.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Schreibt Titel, Interpret, Album, Genre, Cover und Songtext direkt in die MP3 (ID3v2-Tag).
 * Andere Angaben im Tag bleiben erhalten. Ohne Android, getestet.
 */
object Id3Writer {
    /** Was geändert werden soll – `null` lässt den Wert unverändert, "" entfernt ihn */
    data class Update(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val genre: String? = null,
        /** JPEG-Bytes; leeres Array entfernt das Cover */
        val cover: ByteArray? = null,
        val lyrics: String? = null,
    )

    private class Frame(val id: String, val flags: ByteArray, val data: ByteArray)

    fun write(file: File, update: Update) {
        val (version, frames, audioStart) = readTag(file)
        val replaced = buildSet {
            if (update.title != null) add("TIT2")
            if (update.artist != null) add("TPE1")
            if (update.album != null) add("TALB")
            if (update.genre != null) add("TCON")
            if (update.cover != null) add("APIC")
            if (update.lyrics != null) add("USLT")
        }
        val out = frames.filter { it.id !in replaced }.toMutableList()
        val noFlags = byteArrayOf(0, 0)
        fun text(id: String, value: String?) {
            if (!value.isNullOrEmpty()) out += Frame(id, noFlags, byteArrayOf(1) + utf16(value))
        }
        text("TIT2", update.title)
        text("TPE1", update.artist)
        text("TALB", update.album)
        text("TCON", update.genre)
        update.cover?.takeIf { it.isNotEmpty() }?.let { jpeg ->
            // Kodierung 0, MIME, Bildtyp 3 (Vorderseite), leere Beschreibung
            out += Frame("APIC", noFlags, byteArrayOf(0) + "image/jpeg".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 3, 0) + jpeg)
        }
        update.lyrics?.takeIf { it.isNotEmpty() }?.let { lyrics ->
            out += Frame("USLT", noFlags, byteArrayOf(1) + "deu".toByteArray() + utf16("") + utf16(lyrics))
        }

        val body = ByteArrayOutputStream()
        for (f in out) {
            body.write(f.id.toByteArray(Charsets.ISO_8859_1))
            body.write(if (version >= 4) synchsafe(f.data.size) else int32(f.data.size))
            body.write(f.flags)
            body.write(f.data)
        }
        body.write(ByteArray(1024)) // Platz für spätere Änderungen
        val tag = "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, 0) + synchsafe(body.size()) + body.toByteArray()

        // Neue Datei schreiben und dann austauschen (die alte bleibt bei Fehlern unangetastet)
        // Endung .mp3: in Musik-/Download-Ordnern erlaubt Android nur Audiodateien
        val tmp = File(file.parentFile, ".${file.nameWithoutExtension}.tag.mp3")
        tmp.outputStream().use { o ->
            o.write(tag)
            RandomAccessFile(file, "r").use { r ->
                r.seek(audioStart)
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = r.read(buf); if (n < 0) break
                    o.write(buf, 0, n)
                }
            }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    /** Vorhandenes Tag lesen: Version, Frames (bei 2.2 keine) und wo die Musik beginnt */
    private fun readTag(file: File): Triple<Int, List<Frame>, Long> {
        RandomAccessFile(file, "r").use { f ->
            val header = ByteArray(10)
            if (f.length() < 10 || f.read(header) != 10 || String(header, 0, 3, Charsets.ISO_8859_1) != "ID3") {
                return Triple(3, emptyList(), 0L)
            }
            val version = header[3].toInt()
            val flags = header[5].toInt()
            val size = synchsafe(header, 6)
            val footer = if (version >= 4 && flags and 0x10 != 0) 10 else 0
            val audioStart = 10L + size + footer
            if (version !in 3..4) return Triple(3, emptyList(), audioStart) // ID3v2.2: ersetzen statt übernehmen
            var body = ByteArray(size).also { f.readFully(it) }
            if (flags and 0x80 != 0 && version == 3) body = deunsync(body)
            var pos = 0
            if (flags and 0x40 != 0 && body.size >= 4) pos = if (version >= 4) synchsafe(body, 0) else int32(body, 0) + 4
            val frames = mutableListOf<Frame>()
            while (pos + 10 <= body.size) {
                val id = String(body, pos, 4, Charsets.ISO_8859_1)
                if (!id.all { it.isLetterOrDigit() }) break
                val len = if (version >= 4) synchsafe(body, pos + 4) else int32(body, pos + 4)
                if (len <= 0 || pos + 10 + len > body.size) break
                val frameFlags = body.copyOfRange(pos + 8, pos + 10)
                // Frames mit Kompression/Verschlüsselung/Unsynchronisation unverändert übernehmen wäre riskant → nur einfache
                val formatFlags = frameFlags[1].toInt()
                if (formatFlags == 0) frames += Frame(id, frameFlags, body.copyOfRange(pos + 10, pos + 10 + len))
                pos += 10 + len
            }
            return Triple(version, frames, audioStart)
        }
    }

    private fun utf16(s: String) = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)

    private fun synchsafe(n: Int) = byteArrayOf(((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte())
    private fun int32(n: Int) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    private fun synchsafe(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)
    private fun int32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun deunsync(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if ((b[i].toInt() and 0xFF) == 0xFF && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }
}
