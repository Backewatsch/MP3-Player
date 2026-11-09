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

// Oft gespielt, Verlauf und Hör-Statistik der Mediathek

/** Titel wurde gestartet → in den Verlauf (gleicher Titel innerhalb einer Minute nur einmal) */
fun Library.recordStart(trackId: String, now: Long = System.currentTimeMillis()) {
    if (track(trackId) == null) return
    _state.update { s ->
        val recent = s.history.firstOrNull()
        if (recent != null && recent.trackId == trackId && now - recent.at < 60_000) return@update s
        s.copy(history = (listOf(PlayEvent(trackId, now)) + s.history).take(Library.MAX_HISTORY))
    }
    save()
}

/** Titel wurde richtig gehört (nicht nur angespielt) → zählt für „Oft gespielt“ */
fun Library.recordPlay(trackId: String) {
    if (track(trackId) == null) return
    val now = System.currentTimeMillis()
    _state.update { s ->
        s.copy(
            playCounts = s.playCounts + (trackId to ((s.playCounts[trackId] ?: 0) + 1)),
            plays = (listOf(PlayEvent(trackId, now)) + s.plays).take(Stats.MAX_PLAYS),
        )
    }
    save()
}

/** Hörzeit für die Statistik hinzufügen */
fun Library.addListened(ms: Long, now: Long = System.currentTimeMillis()) {
    if (ms <= 0) return
    val key = Stats.monthKey(now)
    _state.update { s -> s.copy(listenedByMonth = s.listenedByMonth + (key to ((s.listenedByMonth[key] ?: 0) + ms))) }
    save()
}

fun Library.clearStats() {
    _state.update { it.copy(plays = emptyList(), listenedByMonth = emptyMap()) }
    save()
}

fun Library.playCount(track: Track) = _state.value.playCounts[track.id] ?: 0

/** Meistgehörte Titel, häufigste zuerst */
fun Library.mostPlayed(limit: Int = 50): List<Pair<Track, Int>> {
    val byId = tracks.associateBy { it.id }
    return _state.value.playCounts.entries
        .filter { it.value > 0 }
        .sortedByDescending { it.value }
        .mapNotNull { (id, count) -> byId[id]?.let { it to count } }
        .take(limit)
}

/** Verlauf: Titel mit Zeitpunkt, neueste zuerst */
fun Library.history(limit: Int = Library.MAX_HISTORY): List<Pair<Track, Long>> {
    val byId = tracks.associateBy { it.id }
    return _state.value.history.mapNotNull { e -> byId[e.trackId]?.let { it to e.at } }.take(limit)
}

/** Zuletzt gehört, jeder Titel nur einmal */
fun Library.recentlyPlayed(limit: Int = 20): List<Track> = history().map { it.first }.distinctBy { it.id }.take(limit)

fun Library.clearHistory() {
    _state.update { it.copy(history = emptyList(), playCounts = emptyMap()) }
    save()
}
