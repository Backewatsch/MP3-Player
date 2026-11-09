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

// Sicherung in die Mediathek zurückspielen

/**
 * Spielt eine Sicherung ein, ohne Vorhandenes zu verlieren: Titel werden über ID, Herkunft oder
 * Titel+Interpret+Länge wiedererkannt; fehlende Titel kommen aus [musicFiles] (Dateiname → Datei), falls vorhanden.
 * Playlists werden über den Namen zusammengeführt, Herzen/Kurzwahl vereinigt, Zähler und Hörzeit übernommen.
 */
suspend fun Library.restore(backup: LibraryState, musicFiles: Map<String, File>, artworkFiles: Map<String, File>): RestoreResult {
    val idMap = mutableMapOf<String, String>()
    var added = 0
    var matched = 0
    var missing = 0
    val newTracks = mutableListOf<Track>()
    val reserved = mutableListOf<String>()
    try {
        for (bt in backup.tracks) {
            val existing = track(bt.id)
                ?: tracks.firstOrNull { bt.source.isNotEmpty() && it.source == bt.source }
                ?: tracks.firstOrNull {
                    it.title.equals(bt.title, ignoreCase = true) && it.artist.equals(bt.artist, ignoreCase = true) &&
                        kotlin.math.abs(it.durationMs - bt.durationMs) < 2_000
                }
            if (existing != null) {
                idMap[bt.id] = existing.id; matched++; continue
            }
            val file = musicFiles[bt.fileName]
            if (file == null || !file.exists()) { missing++; continue }
            val destination = mutex.withLock {
                storage.uniqueDestination(FileNames.sanitize(bt.fileName)).also { importing += it.name; reserved += it.name }
            }
            withContext(Dispatchers.IO) { file.copyTo(destination, overwrite = false) }
            val id = if (newTracks.none { it.id == bt.id }) bt.id else java.util.UUID.randomUUID().toString()
            artworkFiles[bt.id]?.takeIf { it.exists() }?.let { art ->
                withContext(Dispatchers.IO) { art.copyTo(storage.artworkFor(id), overwrite = true) }
            }
            newTracks += bt.copy(id = id, fileName = destination.name)
            idMap[bt.id] = id
            added++
        }

        fun map(ids: List<String>) = ids.mapNotNull { idMap[it] }
        mutex.withLock {
            _state.update { s ->
                var playlists = s.playlists
                for (bp in backup.playlists) {
                    val ids = map(bp.trackIds)
                    val target = if (bp.id == DOWNLOADS_PLAYLIST_ID) playlists.firstOrNull { it.id == DOWNLOADS_PLAYLIST_ID }
                        else playlists.firstOrNull { it.id != DOWNLOADS_PLAYLIST_ID && it.name.equals(bp.name.trim(), ignoreCase = true) }
                    playlists = if (target != null) {
                        playlists.map { if (it.id == target.id) it.copy(trackIds = (it.trackIds + ids).distinct()) else it }
                    } else {
                        val id = if (playlists.none { it.id == bp.id }) bp.id else java.util.UUID.randomUUID().toString()
                        playlists + Playlist(id = id, name = bp.name, trackIds = ids.distinct())
                    }
                }
                val counts = s.playCounts.toMutableMap()
                backup.playCounts.forEach { (id, n) -> idMap[id]?.let { counts[it] = maxOf(counts[it] ?: 0, n) } }
                val listened = s.listenedByMonth.toMutableMap()
                backup.listenedByMonth.forEach { (k, v) -> listened[k] = maxOf(listened[k] ?: 0, v) }
                fun mergeEvents(a: List<PlayEvent>, b: List<PlayEvent>, max: Int) =
                    (a + b.mapNotNull { e -> idMap[e.trackId]?.let { e.copy(trackId = it) } })
                        .distinctBy { it.trackId to it.at }.sortedByDescending { it.at }.take(max)
                s.copy(
                    tracks = s.tracks + newTracks,
                    playlists = playlists,
                    pinned = (s.pinned + map(backup.pinned)).distinct(),
                    favorites = (s.favorites + map(backup.favorites)).distinct(),
                    playCounts = counts,
                    history = mergeEvents(s.history, backup.history, Library.MAX_HISTORY),
                    plays = mergeEvents(s.plays, backup.plays, Stats.MAX_PLAYS),
                    listenedByMonth = listened,
                )
            }
        }
        save()
        return RestoreResult(added, matched, missing, backup.playlists.size)
    } finally {
        mutex.withLock { importing -= reserved.toSet() }
    }
}
