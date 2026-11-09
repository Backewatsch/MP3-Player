package com.backewatsch.mp3player.data

import com.backewatsch.mp3player.model.Track
import java.text.Normalizer
import java.util.Locale

/** Findet doppelte Lieder (gleicher Titel + Interpret, fast gleiche Länge) – ohne Android, getestet. */
object Duplicates {
    /** Zusätze, die oft an Dateinamen/Titeln hängen und für den Vergleich keine Rolle spielen */
    private val noise = Regex(
        "\\((official|offizielles|lyrics?|audio|video|hq|hd|explicit|clean|remaster(ed)?|\\d{4} remaster(ed)?)[^)]*\\)|" +
            "\\[(official|lyrics?|audio|video|hq|hd|explicit)[^]]*]|\\(\\d+\\)$",
        RegexOption.IGNORE_CASE,
    )

    fun normalize(text: String): String {
        val base = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return base.replace(noise, " ").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    /** Gruppen mit mindestens zwei gleichen Liedern */
    fun groups(tracks: List<Track>, toleranceMs: Long = 3_000): List<List<Track>> =
        tracks.groupBy { normalize(it.title) + "|" + normalize(it.artist) }
            .filterKeys { !it.startsWith("|") }
            .values
            .flatMap { sameName ->
                // Innerhalb gleicher Namen nach Länge trennen (Live-Version ≠ Studio-Version)
                val clusters = mutableListOf<MutableList<Track>>()
                for (t in sameName.sortedBy { it.durationMs }) {
                    val c = clusters.lastOrNull()
                    if (c != null && (t.durationMs <= 0 || c.last().durationMs <= 0 || t.durationMs - c.last().durationMs <= toleranceMs)) c += t
                    else clusters += mutableListOf(t)
                }
                clusters
            }
            .filter { it.size >= 2 }

    /** Welches Lied bleibt: MP3 vor anderen Formaten, dann meistgespielt, in mehr Playlists, Herz, zuerst hinzugefügt */
    fun keeper(group: List<Track>, playCounts: Map<String, Int>, playlistCount: (Track) -> Int, favorites: Set<String>): Track =
        group.sortedWith(
            compareByDescending<Track> { FileNames.isMp3(it.fileName) }
                .thenByDescending { playCounts[it.id] ?: 0 }
                .thenByDescending { playlistCount(it) }
                .thenByDescending { it.id in favorites }
                .thenBy { it.addedAt },
        ).first()
}
