package com.backewatsch.mp3player.convert

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.backewatsch.mp3player.data.LoudnessMeter
import java.io.File
import java.nio.ByteOrder

/** Dekodiert ein Lied und misst seine Lautheit („Lautstärke angleichen“). Blockierend – im Hintergrund aufrufen. */
object LoudnessAnalyzer {
    /** Verstärkung in dB, damit das Lied so laut klingt wie die anderen; null wenn nicht lesbar */
    fun gainDb(file: File): Float? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val codec = MediaCodec.createDecoderByType(mime)
            var meter: LoudnessMeter? = null
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                var outFormat = codec.outputFormat
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var samples = ShortArray(0)
                while (true) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val size = extractor.readSampleData(codec.getInputBuffer(inIndex)!!, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val out = codec.dequeueOutputBuffer(info, 10_000)
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outFormat = codec.outputFormat
                    } else if (out >= 0) {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(out)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val data = buffer.slice().order(ByteOrder.nativeOrder())
                            val m = meter ?: LoudnessMeter(
                                outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                            ).also { meter = it }
                            val float = outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            if (float) {
                                val fb = data.asFloatBuffer()
                                val n = fb.remaining()
                                if (samples.size < n) samples = ShortArray(n)
                                for (i in 0 until n) samples[i] = (fb.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                m.add(samples, n)
                            } else {
                                val sb = data.asShortBuffer()
                                val n = sb.remaining()
                                if (samples.size < n) samples = ShortArray(n)
                                sb.get(samples, 0, n)
                                m.add(samples, n)
                            }
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            return meter?.loudnessDb()?.let { LoudnessMeter.gainFor(it) }
        } catch (e: Exception) {
            return null
        } finally {
            extractor.release()
        }
    }
}
