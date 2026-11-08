package com.backewatsch.mp3player.convert

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.backewatsch.mp3player.data.MetadataReader
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder

/**
 * Wandelt Audio- und Videodateien (M4A, AAC, WAV, FLAC, OGG, Opus, MP4, WebM, MKV …) in echte MP3-Dateien um.
 * Android dekodiert (MediaCodec), LAME kodiert: 192 kbit/s CBR, 44,1 kHz, Tags als ID3v2 (UTF-16).
 */
object Mp3Converter {
    class ConversionException(message: String) : Exception(message)

    const val BITRATE = 192

    /** Wandelt [input] um und schreibt die MP3 nach [output]. Läuft blockierend – im Hintergrund aufrufen. */
    fun convert(input: File, output: File) {
        val info = MetadataReader.read(input)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
        } catch (e: Exception) {
            extractor.release()
            throw ConversionException("Die Datei konnte nicht gelesen werden.")
        }
        try {
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw ConversionException("Die Datei enthält keine Tonspur.")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!

            FileOutputStream(output).use { out ->
                val encoder = Encoder(out, info)
                try {
                    if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                        decodeRaw(extractor, format, encoder)
                    } else {
                        decodeWithCodec(extractor, format, mime, encoder)
                    }
                    encoder.finish()
                } finally {
                    encoder.close()
                }
            }
        } catch (e: ConversionException) {
            output.delete()
            throw e
        } catch (e: Exception) {
            output.delete()
            throw ConversionException("MP3-Umwandlung fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            extractor.release()
        }
    }

    private fun decodeWithCodec(extractor: MediaExtractor, format: MediaFormat, mime: String, encoder: Encoder) {
        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            throw ConversionException("Das Audioformat ($mime) wird auf diesem Gerät nicht unterstützt.")
        }
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            var outFormat = codec.outputFormat
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(outIndex)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            encoder.write(buffer.slice().order(ByteOrder.nativeOrder()), outFormat)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    /** WAV & Co. liegen bereits als PCM vor */
    private fun decodeRaw(extractor: MediaExtractor, format: MediaFormat, encoder: Encoder) {
        val buffer = java.nio.ByteBuffer.allocateDirect(1 shl 20)
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            buffer.limit(size)
            encoder.write(buffer.slice().order(ByteOrder.LITTLE_ENDIAN), format)
            extractor.advance()
        }
    }

    /** Sammelt PCM, wandelt in 16 Bit (max. 2 Kanäle) und füttert LAME. */
    private class Encoder(private val out: FileOutputStream, private val info: MetadataReader.Info) {
        private var handle = 0L
        private var channelsOut = 0
        private var mp3 = ByteArray(64 * 1024)

        fun write(data: java.nio.ByteBuffer, format: MediaFormat) {
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelsIn = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else AudioFormat.ENCODING_PCM_16BIT
            if (handle == 0L) start(sampleRate, minOf(channelsIn, 2))

            // Samples als 16 Bit lesen
            val samples: ShortArray = when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    val fb = data.asFloatBuffer()
                    ShortArray(fb.remaining()) { (fb.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
                }
                AudioFormat.ENCODING_PCM_8BIT -> ShortArray(data.remaining()) { (((data.get().toInt() and 0xFF) - 128) shl 8).toShort() }
                else -> {
                    val sb = data.asShortBuffer()
                    ShortArray(sb.remaining()).also { sb.get(it) }
                }
            }
            if (samples.isEmpty() || channelsIn <= 0) return
            val frames = samples.size / channelsIn
            // Auf Mono/Stereo heruntermischen
            val pcm = if (channelsIn <= 2) samples else ShortArray(frames * 2) { i ->
                val frame = i / 2
                samples[frame * channelsIn + (i % 2)]
            }
            val needed = (frames * 1.25).toInt() + 7200
            if (mp3.size < needed) mp3 = ByteArray(needed)
            val written = LameNative.nativeEncode(handle, pcm, frames, channelsOut, mp3)
            if (written < 0) throw ConversionException("MP3-Encoder-Fehler $written")
            if (written > 0) out.write(mp3, 0, written)
        }

        private fun start(sampleRate: Int, channels: Int) {
            channelsOut = channels
            handle = LameNative.nativeInit(sampleRate, channels, BITRATE, 2)
            if (handle == 0L) throw ConversionException("MP3-Encoder konnte nicht starten.")
            val result = LameNative.nativeSetTags(
                handle,
                info.title?.takeIf { it.isNotBlank() },
                info.artist?.takeIf { it.isNotBlank() },
                info.album?.takeIf { it.isNotBlank() },
                MetadataReader.shrinkArtwork(info.artwork),
            )
            if (result < 0) throw ConversionException("Ungültige Encoder-Einstellungen.")
        }

        fun finish() {
            if (handle == 0L) throw ConversionException("Die Tonspur ist leer.")
            val written = LameNative.nativeFlush(handle, mp3)
            if (written > 0) out.write(mp3, 0, written)
        }

        fun close() {
            if (handle != 0L) LameNative.nativeClose(handle)
            handle = 0
        }
    }
}
