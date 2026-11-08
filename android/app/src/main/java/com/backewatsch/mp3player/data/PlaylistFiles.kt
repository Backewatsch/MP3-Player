package com.backewatsch.mp3player.data

import com.backewatsch.mp3player.model.Track
import java.util.Locale

/** Liest .m3u/.m3u8-Playlists (z. B. aus Samsung Music exportiert) und ordnet die Einträge Titeln zu. Ohne Android-Abhängigkeiten. */
object PlaylistFiles {
    data class Entry(val path: String, val title: String?)

    fun isPlaylistFile(name: String) = FileNames.extension(name) in setOf("m3u", "m3u8")

    fun parse(text: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        var pendingTitle: String? = null
        text.removePrefix("﻿").lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) ->
                    // #EXTINF:123,Interpret - Titel
                    pendingTitle = line.substringAfter(',', "").trim().ifEmpty { null }
                line.startsWith("#") -> Unit
                else -> {
                    entries += Entry(line.replace('\\', '/'), pendingTitle)
                    pendingTitle = null
                }
            }
        }
        return entries
    }

    /** Dateiname ohne Ordner und Endung, vereinfacht für den Vergleich */
    fun key(path: String): String =
        FileNames.baseName(path.replace('\\', '/').substringAfterLast('/'))
            .lowercase(Locale.ROOT).replace(Regex("\\s*\\(\\d+\\)$"), "").trim()

    /**
     * Sucht den passenden Titel: zuerst über den Originalpfad, dann über den Dateinamen,
     * zuletzt über den Titel aus #EXTINF.
     */
    fun match(entry: Entry, tracks: List<Track>): Track? {
        val path = entry.path.replace('\\', '/')
        tracks.firstOrNull { it.source.isNotEmpty() && it.source.equals(path, ignoreCase = true) }?.let { return it }
        val k = key(path)
        if (k.isNotEmpty()) {
            tracks.firstOrNull { it.source.isNotEmpty() && key(it.source) == k }?.let { return it }
            tracks.firstOrNull { key(it.fileName) == k }?.let { return it }
        }
        val title = entry.title?.lowercase(Locale.ROOT) ?: return null
        return tracks.firstOrNull {
            val t = it.title.lowercase(Locale.ROOT)
            t == title || (it.artist.isNotBlank() && "${it.artist} - ${it.title}".lowercase(Locale.ROOT) == title)
        }
    }

    /** Playlist-Name aus dem Dateinamen */
    fun nameFromFile(fileName: String) = FileNames.baseName(fileName).trim().ifEmpty { "Importierte Playlist" }
}
