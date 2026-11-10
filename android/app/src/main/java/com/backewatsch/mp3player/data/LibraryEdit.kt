package com.backewatsch.mp3player.data

import com.backewatsch.mp3player.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

// Lieder bearbeiten: zuschneiden (verlustfrei, ohne neu zu kodieren)

/** Kann das Lied zugeschnitten werden? (nur MP3) */
fun Library.canTrim(track: Track): Boolean = FileNames.isMp3(track.fileName) && fileOf(track).exists()

/** Genaue Länge laut MP3-Frames */
suspend fun Library.exactDurationMs(track: Track): Long = withContext(Dispatchers.IO) {
    runCatching { Mp3Trimmer.durationMs(fileOf(track)) }.getOrDefault(track.durationMs)
}

/**
 * Behält nur den Teil von [startMs] bis [endMs].
 * @param asCopy true = als neues Lied „… (gekürzt)“ speichern, false = Original ersetzen
 * @return das gekürzte Lied
 */
suspend fun Library.trim(
    track: Track, startMs: Long, endMs: Long, asCopy: Boolean, fadeInMs: Long = 0, fadeOutMs: Long = 0,
): Track {
    if (!canTrim(track)) throw Mp3Trimmer.Mp3Exception("Nur MP3-Dateien können zugeschnitten werden.")
    val original = fileOf(track)

    /** Schneidet zu und blendet bei Bedarf ein/aus (dann wird der Ton neu kodiert; die Tags bleiben) */
    fun cut(): ByteArray {
        val trimmed = Mp3Trimmer.trim(original.readBytes(), startMs, endMs)
        if (fadeInMs <= 0 && fadeOutMs <= 0) return trimmed
        val dir = storage.newIncomingDir()
        try {
            val source = File(dir, "schnitt.mp3").apply { writeBytes(trimmed) }
            val faded = File(dir, "blende.mp3")
            val total = Mp3Trimmer.parse(trimmed).durationMs
            // Blenden zusammen höchstens so lang wie das Lied
            val fadeIn = fadeInMs.coerceIn(0, total / 2)
            val fadeOut = fadeOutMs.coerceIn(0, total / 2)
            com.backewatsch.mp3player.convert.Mp3Converter.convert(
                source, faded, com.backewatsch.mp3player.convert.Mp3Converter.Fade(fadeIn, fadeOut, total))
            return Mp3Trimmer.replaceAudio(trimmed, faded.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    if (!asCopy) {
        // Original ersetzen: lesen, schneiden und austauschen in einem Schritt – zusammen mit anderen
        // Dateiänderungen nacheinander, damit nichts verloren geht. Erst komplett schreiben, dann austauschen.
        val newDuration = withContext(fileDispatcher) {
            val trimmed = cut()
            val tmp = File(original.parentFile, ".trim-${track.id}.tmp")
            tmp.writeBytes(trimmed)
            if (!tmp.renameTo(original)) {
                tmp.copyTo(original, overwrite = true); tmp.delete()
            }
            Mp3Trimmer.parse(trimmed).durationMs
        }
        _state.update { s -> s.copy(tracks = s.tracks.map { if (it.id == track.id) it.copy(durationMs = newDuration) else it }) }
        save()
        return track(track.id)!!
    }

    val trimmed = withContext(fileDispatcher) { cut() }
    val dir = storage.newIncomingDir()
    try {
        val file = File(dir, FileNames.baseName(track.fileName) + " (gekürzt).mp3")
        withContext(Dispatchers.IO) { file.writeBytes(trimmed) }
        val copy = import(file, move = true)
        val title = track.title + " (gekürzt)"
        editInfo(copy, title, track.artist, track.album, track.genre)
        // Eigenes Cover übernehmen
        withContext(Dispatchers.IO) {
            storage.artworkFor(track.id).takeIf { it.exists() }?.copyTo(storage.artworkFor(copy.id), overwrite = true)
        }
        return track(copy.id) ?: copy
    } finally {
        dir.deleteRecursively()
    }
}
