package com.backewatsch.mp3player.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Track(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    /** Dauer in Millisekunden */
    val durationMs: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
    /** Genre (z. B. „Hip-Hop“), aus der Datei gelesen oder selbst eingetragen */
    val genre: String = "",
    /** Herkunft (z. B. Pfad der Originaldatei bei Übernahme aus Samsung Music) – verhindert Doppelte */
    val source: String = "",
)

@Serializable
data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val trackIds: List<String> = emptyList(),
)

/** Ein Eintrag im Verlauf: welcher Titel wann gestartet wurde */
@Serializable
data class PlayEvent(val trackId: String, val at: Long)

@Serializable
data class LibraryState(
    val version: Int = CURRENT_VERSION,
    val tracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val pinned: List<String> = emptyList(),
    /** Wie oft jeder Titel gehört wurde („Oft gespielt“) */
    val playCounts: Map<String, Int> = emptyMap(),
    /** Zuletzt gestartete Titel, neueste zuerst („Verlauf“) */
    val history: List<PlayEvent> = emptyList(),
    /** Lieblingssongs (Herz), zuletzt markierte zuerst */
    val favorites: List<String> = emptyList(),
    /** Richtig gehörte Titel mit Zeitpunkt, neueste zuerst – für die Statistik */
    val plays: List<PlayEvent> = emptyList(),
    /** Hörzeit in ms je Monat („2026-11“) – für die Statistik */
    val listenedByMonth: Map<String, Long> = emptyMap(),
    /** Gemessene Verstärkung je Titel in dB („Lautstärke angleichen“) */
    val gains: Map<String, Float> = emptyMap(),
    /** Selbst eingetragene oder aus der Datei gelesene Songtexte */
    val lyrics: Map<String, String> = emptyMap(),
    /** Titel mit selbst gewähltem Cover (wird immer angezeigt) */
    val customCovers: List<String> = emptyList(),
    /** Genres der vorhandenen Titel wurden einmal aus den Dateien gelesen */
    val genresScanned: Boolean = false,
) {
    companion object {
        const val CURRENT_VERSION = 4
        /** Feste ID der Playlist „Heruntergeladen“ */
        const val DOWNLOADS_PLAYLIST_ID = "00000000-0000-0000-0000-00000000d001"
    }
}

/** 3:07 bzw. 1:02:03 */
fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
