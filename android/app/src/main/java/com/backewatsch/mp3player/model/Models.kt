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
)

@Serializable
data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val trackIds: List<String> = emptyList(),
)

@Serializable
data class LibraryState(
    val version: Int = CURRENT_VERSION,
    val tracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val pinned: List<String> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 2
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
