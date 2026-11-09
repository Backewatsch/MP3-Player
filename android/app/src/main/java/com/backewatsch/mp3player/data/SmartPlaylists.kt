package com.backewatsch.mp3player.data

import com.backewatsch.mp3player.model.LibraryState
import com.backewatsch.mp3player.model.Track

/** Playlists, die sich selbst füllen – ohne Android, getestet. */
enum class SmartPlaylist(val title: String, val subtitle: String) {
    RECENTLY_ADDED("Neu hinzugefügt", "Deine neuesten Lieder"),
    ON_REPEAT("Dauerschleife", "Was du gerade oft hörst"),
    REDISCOVER("Wiederentdecken", "Lange nicht mehr gehört"),
    NEVER_PLAYED("Noch nie gehört", "Gib ihnen eine Chance");

    fun tracks(state: LibraryState, now: Long = System.currentTimeMillis()): List<Track> {
        val month = 30L * 24 * 3600 * 1000
        val lastHeard = HashMap<String, Long>()
        for (e in state.history) lastHeard.merge(e.trackId, e.at) { a, b -> maxOf(a, b) }
        for (e in state.plays) lastHeard.merge(e.trackId, e.at) { a, b -> maxOf(a, b) }
        return when (this) {
            RECENTLY_ADDED -> {
                val newest = state.tracks.sortedByDescending { it.addedAt }
                newest.filter { now - it.addedAt <= month }.ifEmpty { newest.take(25) }.take(100)
            }
            ON_REPEAT -> {
                val recent = state.plays.filter { now - it.at <= month }.groupingBy { it.trackId }.eachCount()
                val byId = state.tracks.associateBy { it.id }
                recent.entries.filter { it.value >= 2 }.sortedByDescending { it.value }.mapNotNull { byId[it.key] }.take(50)
            }
            REDISCOVER -> state.tracks
                .filter { t -> lastHeard[t.id]?.let { now - it > month } == true }
                .sortedBy { lastHeard[it.id] }
                .take(100)
            NEVER_PLAYED -> state.tracks
                .filter { (state.playCounts[it.id] ?: 0) == 0 && it.id !in lastHeard }
                .sortedByDescending { it.addedAt }
        }
    }

    companion object {
        fun fromName(name: String) = entries.firstOrNull { it.name == name }
    }
}
