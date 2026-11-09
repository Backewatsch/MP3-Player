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
suspend fun Library.trim(track: Track, startMs: Long, endMs: Long, asCopy: Boolean): Track {
    if (!canTrim(track)) throw Mp3Trimmer.Mp3Exception("Nur MP3-Dateien können zugeschnitten werden.")
    val original = fileOf(track)
    val trimmed = withContext(Dispatchers.IO) { Mp3Trimmer.trim(original.readBytes(), startMs, endMs) }
    val newDuration = withContext(Dispatchers.IO) { Mp3Trimmer.parse(trimmed).durationMs }

    if (asCopy) {
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

    // Original ersetzen: erst komplett schreiben, dann austauschen (bricht nie halb ab)
    withContext(Dispatchers.IO) {
        val tmp = File(original.parentFile, ".trim-${track.id}.tmp")
        tmp.writeBytes(trimmed)
        if (!tmp.renameTo(original)) {
            tmp.copyTo(original, overwrite = true); tmp.delete()
        }
    }
    _state.update { s -> s.copy(tracks = s.tracks.map { if (it.id == track.id) it.copy(durationMs = newDuration) else it }) }
    save()
    return track(track.id)!!
}
