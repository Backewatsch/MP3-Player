package com.backewatsch.mp3player.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File

/** Liest Titel, Interpret, Album, Dauer und Cover aus einer Mediendatei. */
object MetadataReader {
    data class Info(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long,
        val artwork: ByteArray?,
    )

    fun read(file: File): Info {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            Info(
                title = r.extract(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = r.extract(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: r.extract(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = r.extract(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = r.extract(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                artwork = r.embeddedPicture,
            )
        } catch (e: Exception) {
            Info(null, null, null, 0, null)
        } finally {
            runCatching { r.release() }
        }
    }

    /** Mitwirkende und Dateiangaben („Mitwirkende an Song anzeigen“) */
    fun credits(file: File): List<Pair<String, String>> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            listOf(
                "Interpret" to MediaMetadataRetriever.METADATA_KEY_ARTIST,
                "Album-Interpret" to MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST,
                "Album" to MediaMetadataRetriever.METADATA_KEY_ALBUM,
                "Komponist" to MediaMetadataRetriever.METADATA_KEY_COMPOSER,
                "Autor" to MediaMetadataRetriever.METADATA_KEY_AUTHOR,
                "Texter" to MediaMetadataRetriever.METADATA_KEY_WRITER,
                "Genre" to MediaMetadataRetriever.METADATA_KEY_GENRE,
                "Jahr" to MediaMetadataRetriever.METADATA_KEY_YEAR,
            ).mapNotNull { (label, key) -> r.extract(key)?.let { label to it } }
        } catch (e: Exception) {
            emptyList()
        } finally {
            runCatching { r.release() }
        }
    }

    private fun MediaMetadataRetriever.extract(key: Int): String? =
        extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }

    /** Cover verkleinern (max. 500 px) und als JPEG liefern */
    fun shrinkArtwork(bytes: ByteArray?, maxSize: Int = 500): ByteArray? {
        if (bytes == null) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scale = minOf(1f, maxSize.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }
}
