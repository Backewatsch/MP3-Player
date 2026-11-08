package com.backewatsch.mp3player.data

import com.backewatsch.mp3player.model.Track

/** Sortierung der Titelliste (ohne Android-Abhängigkeiten, getestet). */
enum class TrackSort(val label: String) {
    TITLE("Titel (A–Z)"),
    ARTIST("Interpret (A–Z)"),
    NEWEST("Zuletzt hinzugefügt"),
    MOST_PLAYED("Am meisten gespielt"),
    LONGEST("Länge");

    fun apply(tracks: List<Track>, playCounts: Map<String, Int> = emptyMap()): List<Track> {
        val byTitle = compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        return when (this) {
            TITLE -> tracks.sortedWith(byTitle)
            ARTIST -> tracks.sortedWith(
                compareBy<Track> { it.artist.isBlank() }   // ohne Interpret ans Ende
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.artist }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.album }
                    .then(byTitle),
            )
            NEWEST -> tracks.sortedWith(compareByDescending<Track> { it.addedAt }.then(byTitle))
            MOST_PLAYED -> tracks.sortedWith(compareByDescending<Track> { playCounts[it.id] ?: 0 }.then(byTitle))
            LONGEST -> tracks.sortedWith(compareByDescending<Track> { it.durationMs }.then(byTitle))
        }
    }

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: TITLE
    }
}
