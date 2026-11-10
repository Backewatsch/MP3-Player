package com.backewatsch.mp3player.data

import com.backewatsch.mp3player.model.LibraryState
import com.backewatsch.mp3player.model.LibraryState.Companion.DOWNLOADS_PLAYLIST_ID
import com.backewatsch.mp3player.model.PlayEvent
import com.backewatsch.mp3player.model.Playlist
import com.backewatsch.mp3player.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

// Lautstärke-Angleichung, Songtexte, eigene Cover, Doppelte und automatische Playlists der Mediathek

fun Library.gainOf(trackId: String): Float? = _state.value.gains[trackId]
fun Library.tracksWithoutGain(): List<Track> = tracks.filter { it.id !in _state.value.gains }

fun Library.setGain(trackId: String, gainDb: Float) {
    if (track(trackId) == null) return
    _state.update { s -> s.copy(gains = s.gains + (trackId to gainDb)) }
    save()
}

/** Songtext: selbst eingetragen oder aus der MP3 (USLT) gelesen */
fun Library.lyricsOf(track: Track): String? =
    _state.value.lyrics[track.id] ?: Id3Lyrics.read(fileOf(track))

fun Library.setLyrics(track: Track, text: String) {
    val clean = text.trim()
    _state.update { s -> s.copy(lyrics = if (clean.isEmpty()) s.lyrics - track.id else s.lyrics + (track.id to clean)) }
    save()
    writeTags(track, Id3Writer.Update(lyrics = clean))
}

fun Library.hasCustomCover(track: Track) = track.id in _state.value.customCovers

/** Eigenes Cover (JPEG-Bytes) für einen Titel speichern */
fun Library.setCustomCover(track: Track, jpeg: ByteArray) {
    val firstTime = track.id !in _state.value.customCovers
    storage.artworkFor(track.id).writeBytes(jpeg)
    _state.update { s -> s.copy(customCovers = (listOf(track.id) + s.customCovers).distinct()) }
    coverVersion.value++
    save()
    // Auch in die MP3 schreiben – dann sehen andere Apps das Cover ebenfalls.
    // Vorher das Original-Cover sichern, damit „Eigenes Cover entfernen“ es zurückholen kann.
    val file = fileOf(track)
    // Verknüpfte Originale ohne Zugriff bleiben unberührt (Cover dann nur in der App)
    if (!FileNames.isMp3(file.name) || !canModifyFile(track)) return
    val backup = storage.originalCoverFor(track.id)
    runOnFiles {
        if (firstTime && !backup.exists()) MetadataReader.read(file).artwork?.let { backup.writeBytes(it) }
        Id3Writer.write(file, Id3Writer.Update(cover = jpeg))
        if (track.isLinked) storage.notifyMediaChanged(track.path)
    }
}

/** Eigenes Cover entfernen: das ursprüngliche Cover der MP3 kommt zurück (falls es eines gab) */
fun Library.removeCustomCover(track: Track) {
    storage.artworkFor(track.id).delete()
    _state.update { s -> s.copy(customCovers = s.customCovers - track.id) }
    coverVersion.value++
    save()
    val file = fileOf(track)
    val thumb = storage.artworkFor(track.id)
    if (track.isLinked && !canModifyFile(track)) {
        // Verknüpft ohne Zugriff: Vorschaubild wieder aus dem Cover der Originaldatei erzeugen
        runOnFiles {
            MetadataReader.shrinkArtwork(MetadataReader.read(file).artwork)?.let { thumb.writeBytes(it) }
            coverVersion.value++
        }
        return
    }
    if (!FileNames.isMp3(file.name)) return
    val backup = storage.originalCoverFor(track.id)
    runOnFiles {
        val original = backup.takeIf { it.exists() }?.readBytes()
        Id3Writer.write(file, Id3Writer.Update(cover = original ?: ByteArray(0)))
        original?.let { MetadataReader.shrinkArtwork(it)?.let { small -> thumb.writeBytes(small) } }
        backup.delete()
        if (track.isLinked) storage.notifyMediaChanged(track.path)
        coverVersion.value++
    }
}

fun Library.playlistCoverFile(playlist: Playlist) = File(storage.artworkDir, "playlist-${playlist.id}.jpg")

fun Library.setPlaylistCover(playlist: Playlist, jpeg: ByteArray?) {
    val f = playlistCoverFile(playlist)
    if (jpeg == null) f.delete() else f.writeBytes(jpeg)
    // Anzeige lädt das Bild neu
    coverVersion.value++
}


fun Library.duplicateGroups(): List<List<Track>> = Duplicates.groups(tracks)

/** Behält ein Lied der Gruppe, übernimmt Playlists, Herzen, Zähler und Verlauf der anderen und löscht diese. */
fun Library.mergeDuplicates(group: List<Track>): Track {
    val s0 = _state.value
    val keep = Duplicates.keeper(group, s0.playCounts, { t -> s0.playlists.count { t.id in it.trackIds } }, s0.favorites.toSet())
    val others = group.filter { it.id != keep.id }
    if (others.isEmpty()) return keep
    val gone = others.map { it.id }.toSet()
    fun swap(id: String) = if (id in gone) keep.id else id
    _state.update { s ->
        s.copy(
            playlists = s.playlists.map { p -> p.copy(trackIds = p.trackIds.map(::swap).distinct()) },
            pinned = s.pinned.map(::swap).distinct(),
            favorites = s.favorites.map(::swap).distinct(),
            playCounts = (s.playCounts - gone) + (keep.id to group.sumOf { s.playCounts[it.id] ?: 0 }),
            history = s.history.map { it.copy(trackId = swap(it.trackId)) },
            plays = s.plays.map { it.copy(trackId = swap(it.trackId)) },
            lyrics = run {
                val text = s.lyrics[keep.id] ?: others.firstNotNullOfOrNull { s.lyrics[it.id] }
                (s.lyrics - gone - keep.id) + (if (text != null) mapOf(keep.id to text) else emptyMap())
            },
        )
    }
    save()
    deleteAll(others)
    return keep
}

fun Library.smartTracks(kind: SmartPlaylist): List<Track> = kind.tracks(_state.value)
