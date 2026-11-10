package com.backewatsch.mp3player.data

import com.backewatsch.mp3player.model.LibraryState
import com.backewatsch.mp3player.model.Playlist
import com.backewatsch.mp3player.model.Track

/** Suche über Lieder, Interpreten, Alben, Genres und Playlists – ohne Android, getestet. */
object Search {
    data class Results(
        val tracks: List<Track>,
        val artists: List<String>,
        val albums: List<String>,
        val genres: List<String>,
        val playlists: List<Playlist>,
    ) {
        val isEmpty get() = tracks.isEmpty() && artists.isEmpty() && albums.isEmpty() && genres.isEmpty() && playlists.isEmpty()
    }

    private fun norm(s: String) = Duplicates.normalize(s)

    /** Treffer-Güte: 3 = beginnt damit, 2 = Wort beginnt damit, 1 = enthält */
    private fun score(text: String, q: String): Int {
        val t = norm(text)
        return when {
            q.isEmpty() || t.isEmpty() -> 0
            t.startsWith(q) -> 3
            t.split(' ').any { it.startsWith(q) } -> 2
            t.contains(q) -> 1
            else -> 0
        }
    }

    fun run(state: LibraryState, query: String): Results {
        val q = norm(query)
        if (q.isEmpty()) return Results(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        fun distinctNames(names: List<String>) = names.filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .map { it to score(it, q) }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
            .map { it.first }
        val tracks = state.tracks
            .map { t -> t to maxOf(score(t.title, q) * 3, score(t.artist, q) * 2, score(t.album, q), score(t.genre, q)) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Track, Int>> { it.second }.thenBy { it.first.title.lowercase() })
            .map { it.first }
        return Results(
            tracks = tracks,
            artists = distinctNames(state.tracks.map { it.artist }).take(10),
            albums = distinctNames(state.tracks.map { it.album }).take(10),
            genres = distinctNames(state.tracks.map { it.genre }).take(10),
            playlists = state.playlists.filter { score(it.name, q) > 0 }.sortedByDescending { score(it.name, q) },
        )
    }

    /** Lieder einer Liste (Playlist, Album …) filtern – die Reihenfolge der Liste bleibt erhalten */
    fun filter(tracks: List<Track>, query: String): List<Track> {
        val q = norm(query)
        if (q.isEmpty()) return tracks
        return tracks.filter { score(it.title, q) > 0 || score(it.artist, q) > 0 || score(it.album, q) > 0 }
    }

    /** Letzte Suchen: neueste zuerst, ohne Doppelte, höchstens [max] */
    fun remember(recent: List<String>, query: String, max: Int = 8): List<String> {
        val q = query.trim()
        if (q.length < 2) return recent
        return (listOf(q) + recent.filter { !it.equals(q, ignoreCase = true) }).take(max)
    }
}
