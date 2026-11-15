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
 * Findet die Lieder einer Sicherung in einer Liederliste: über Kennung, Herkunft oder Titel + Interpret + Länge.
 * Nachschlagetabellen statt Suchen, damit auch Mediatheken mit tausenden Liedern flott gehen.
 */
private class TrackMatcher(tracks: List<Track>) {
    private val byId = HashMap<String, Track>()
    private val bySource = HashMap<String, Track>()
    private val byName = HashMap<String, MutableList<Track>>()

    init { tracks.forEach(::add) }

    fun add(track: Track) {
        if (track.id !in byId) byId[track.id] = track
        if (track.source.isNotEmpty() && track.source !in bySource) bySource[track.source] = track
        byName.getOrPut(key(track)) { mutableListOf() }.add(track)
    }

    fun find(saved: Track): Track? =
        byId[saved.id]
            ?: (if (saved.source.isNotEmpty()) bySource[saved.source] else null)
            ?: byName[key(saved)]?.firstOrNull { kotlin.math.abs(it.durationMs - saved.durationMs) < 2_000 }

    private fun key(track: Track) = track.title.lowercase() + "\u0001" + track.artist.lowercase()
}

/**
 * Spielt eine Sicherung ein, ohne Vorhandenes zu verlieren: Titel werden über ID, Herkunft oder
 * Titel+Interpret+Länge wiedererkannt; fehlende Titel kommen aus [musicFiles] (Dateiname → Datei), falls vorhanden.
 * Playlists werden über den Namen zusammengeführt, Herzen/Kurzwahl vereinigt, Zähler und Hörzeit übernommen.
 * Lieder der Sicherung, die schon unter „Importierte Lieder“ warten, werden von dort übernommen statt doppelt angelegt.
 *
 * [addMissing] = false („Playlists und Herzen übernehmen“): Es kommt kein Lied dazu – Playlists, Herzen, Kurzwahl und
 * Statistik gelten nur für Lieder, die schon in der Titel-Liste sind. Playlists ohne ein solches Lied werden nicht angelegt.
 */
suspend fun Library.restore(
    backup: LibraryState, musicFiles: Map<String, File>, artworkFiles: Map<String, File>, addMissing: Boolean = true,
): Library.RestoreResult {
    val idMap = mutableMapOf<String, String>()
    var added = 0
    var matched = 0
    var missing = 0
    var fromImported = 0
    val newTracks = mutableListOf<Track>()
    val reserved = mutableListOf<String>()
    val known = TrackMatcher(tracks)
    val waiting = if (addMissing) TrackMatcher(imported) else null
    // Lieder, deren Cover-Bild aus der Sicherung stammt – nur bei ihnen wird „eigenes Cover“ übernommen
    val coverFromBackup = mutableSetOf<String>()
    try {
        for (bt in backup.tracks) {
            var existing = known.find(bt)
            if (existing == null && waiting != null) {
                // Das Lied wartet schon unter „Importierte Lieder“ → von dort in die Titel-Liste holen
                val staged = waiting.find(bt)
                if (staged != null) {
                    adoptImported(listOf(staged.id)) { moved ->
                        known.add(moved)
                        coverFromBackup += moved.id
                        existing = moved
                        fromImported++
                    }
                }
            }
            val found = existing
            if (found != null) {
                idMap[bt.id] = found.id; matched++
                // Ohne neue Lieder stammen die erkannten in aller Regel aus „Lieder importieren“ derselben Datei
                if (!addMissing) coverFromBackup += found.id
                continue
            }
            if (!addMissing) {
                missing++; continue
            }
            val file = musicFiles["${bt.id}__${bt.fileName}"] ?: musicFiles[bt.fileName]
            if (file == null || !file.exists()) {
                // Verknüpftes Lied ohne Datei in der Sicherung: Original liegt auf diesem Handy noch am selben Ort?
                if (bt.isLinked && withContext(Dispatchers.IO) { File(bt.path).canRead() } && newTracks.none { it.path == bt.path }) {
                    val id = if (newTracks.none { it.id == bt.id }) bt.id else java.util.UUID.randomUUID().toString()
                    newTracks += bt.copy(id = id)
                    idMap[bt.id] = id
                    added++
                } else missing++
                continue
            }
            val destination = mutex.withLock {
                storage.uniqueDestination(FileNames.sanitize(bt.fileName)).also { importing += it.name; reserved += it.name }
            }
            withContext(Dispatchers.IO) { file.copyTo(destination, overwrite = false) }
            val id = if (newTracks.none { it.id == bt.id }) bt.id else java.util.UUID.randomUUID().toString()
            artworkFiles[bt.id]?.takeIf { it.exists() }?.let { art ->
                withContext(Dispatchers.IO) { art.copyTo(storage.artworkFor(id), overwrite = true) }
                coverFromBackup += id
            }
            // Aus der Sicherung wiederhergestellte Dateien liegen danach im Ordner der App (keine Verknüpfung mehr)
            newTracks += bt.copy(id = id, fileName = destination.name, path = "")
            idMap[bt.id] = id
            added++
        }

        fun map(ids: List<String>) = ids.mapNotNull { idMap[it] }
        val hearts = map(backup.favorites).distinct()
        val covers = withContext(Dispatchers.IO) {
            map(backup.customCovers).distinct().filter { it in coverFromBackup && storage.artworkFor(it).exists() }
        }
        var playlistsFilled = 0
        mutex.withLock {
            _state.update { s ->
                playlistsFilled = 0
                var playlists = s.playlists
                for (bp in backup.playlists) {
                    val ids = map(bp.trackIds).distinct()
                    if (ids.isNotEmpty()) playlistsFilled++
                    val target = if (bp.id == DOWNLOADS_PLAYLIST_ID) playlists.firstOrNull { it.id == DOWNLOADS_PLAYLIST_ID }
                        else playlists.firstOrNull { it.id != DOWNLOADS_PLAYLIST_ID && it.name.equals(bp.name.trim(), ignoreCase = true) }
                    playlists = when {
                        target != null ->
                            playlists.map { if (it.id == target.id) it.copy(trackIds = (it.trackIds + ids).distinct()) else it }
                        // Zu dieser Playlist ist (noch) kein Lied in der Titel-Liste → keine leere Playlist anlegen
                        !addMissing && ids.isEmpty() -> playlists
                        else -> {
                            val id = if (playlists.none { it.id == bp.id }) bp.id else java.util.UUID.randomUUID().toString()
                            playlists + Playlist(id = id, name = bp.name, trackIds = ids)
                        }
                    }
                }
                val counts = s.playCounts.toMutableMap()
                backup.playCounts.forEach { (id, n) -> idMap[id]?.let { counts[it] = maxOf(counts[it] ?: 0, n) } }
                val listened = s.listenedByMonth.toMutableMap()
                backup.listenedByMonth.forEach { (k, v) -> listened[k] = maxOf(listened[k] ?: 0, v) }
                fun mergeEvents(a: List<PlayEvent>, b: List<PlayEvent>, max: Int) =
                    (a + b.mapNotNull { e -> idMap[e.trackId]?.let { e.copy(trackId = it) } })
                        .distinctBy { it.trackId to it.at }.sortedByDescending { it.at }.take(max)
                // Lautstärke-Werte und Songtexte kommen mit – was hier schon eingetragen ist, geht vor
                val gains = s.gains.toMutableMap()
                backup.gains.forEach { (id, db) -> idMap[id]?.let { if (it !in gains) gains[it] = db } }
                val lyrics = s.lyrics.toMutableMap()
                backup.lyrics.forEach { (id, text) -> idMap[id]?.let { if (it !in lyrics) lyrics[it] = text } }
                s.copy(
                    tracks = s.tracks + newTracks,
                    playlists = playlists,
                    pinned = (s.pinned + map(backup.pinned)).distinct(),
                    favorites = (s.favorites + hearts).distinct(),
                    playCounts = counts,
                    history = mergeEvents(s.history, backup.history, Library.MAX_HISTORY),
                    plays = mergeEvents(s.plays, backup.plays, Stats.MAX_PLAYS),
                    listenedByMonth = listened,
                    gains = gains,
                    lyrics = lyrics,
                    customCovers = (s.customCovers + covers).distinct(),
                )
            }
        }
        if (covers.isNotEmpty()) coverVersion.value++
        save()
        return Library.RestoreResult(
            tracksAdded = added, tracksMatched = matched - fromImported, tracksMissing = missing,
            playlists = backup.playlists.size, fromImported = fromImported, playlistsFilled = playlistsFilled,
            favorites = hearts.size,
        )
    } finally {
        mutex.withLock { importing -= reserved.toSet() }
    }
}
