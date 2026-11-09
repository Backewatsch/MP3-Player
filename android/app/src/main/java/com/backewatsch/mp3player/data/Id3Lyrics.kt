package com.backewatsch.mp3player.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/** Liest den Songtext (ID3-Frame USLT/ULT) aus einer MP3 – ohne Android, getestet. */
object Id3Lyrics {
    fun read(file: File): String? = try {
        RandomAccessFile(file, "r").use { f ->
            val header = ByteArray(10)
            if (f.read(header) != 10) return null
            parse(header, f)
        }
    } catch (e: Exception) {
        null
    }

    /** Liest aus vollständigen Tag-Bytes (Kopf + Inhalt) */
    fun parse(bytes: ByteArray): String? {
        if (bytes.size < 10) return null
        return parseTag(bytes.copyOfRange(0, 10), bytes.copyOfRange(10, bytes.size))
    }

    private fun parse(header: ByteArray, f: RandomAccessFile): String? {
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return null
        val size = synchsafe(header, 6)
        if (size <= 0 || size > 50_000_000) return null
        val body = ByteArray(size)
        f.readFully(body)
        return parseTag(header, body)
    }

    private fun parseTag(header: ByteArray, raw: ByteArray): String? {
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return null
        val version = header[3].toInt()
        val flags = header[5].toInt()
        // Unsynchronisation rückgängig machen (FF 00 → FF)
        var body = if (flags and 0x80 != 0 && version < 4) deunsync(raw) else raw
        var pos = 0
        // Erweiterten Kopf überspringen
        if (flags and 0x40 != 0 && body.size >= 4) {
            pos = if (version >= 4) synchsafe(body, 0) else int32(body, 0) + 4
        }
        val idLen = if (version <= 2) 3 else 4
        val sizeLen = if (version <= 2) 3 else 4
        val headerLen = if (version <= 2) 6 else 10
        while (pos + headerLen <= body.size) {
            val id = String(body, pos, idLen, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val frameSize = when {
                version <= 2 -> ((body[pos + 3].toInt() and 0xFF) shl 16) or ((body[pos + 4].toInt() and 0xFF) shl 8) or (body[pos + 5].toInt() and 0xFF)
                version >= 4 -> synchsafe(body, pos + 4)
                else -> int32(body, pos + 4)
            }
            if (frameSize <= 0 || pos + headerLen + frameSize > body.size) break
            if (id == "USLT" || id == "ULT") {
                return decodeUslt(body.copyOfRange(pos + headerLen, pos + headerLen + frameSize))
                    ?.takeIf { it.isNotBlank() }
            }
            pos += headerLen + frameSize
        }
        return null
    }

    /** USLT: Kodierung (1) · Sprache (3) · Beschreibung (bis 0) · Text */
    private fun decodeUslt(data: ByteArray): String? {
        if (data.size < 5) return null
        val enc = data[0].toInt()
        val charset: Charset = when (enc) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val wide = enc == 1 || enc == 2
        var i = 4
        // Beschreibung überspringen
        if (wide) {
            while (i + 1 < data.size && !(data[i].toInt() == 0 && data[i + 1].toInt() == 0)) i += 2
            i += 2
        } else {
            while (i < data.size && data[i].toInt() != 0) i++
            i += 1
        }
        if (i > data.size) return null
        var text = String(data, i, data.size - i, charset).trimEnd('\u0000')
        // Ohne BOM bei UTF-16 (Kodierung 1) als Little Endian versuchen
        if (enc == 1 && text.contains('�')) text = String(data, i, data.size - i, Charsets.UTF_16LE).trimEnd('\u0000')
        return text.replace("\r\n", "\n").replace('\r', '\n').trim()
    }

    private fun synchsafe(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun int32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun deunsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if ((b[i].toInt() and 0xFF) == 0xFF && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }
}
