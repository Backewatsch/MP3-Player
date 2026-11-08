package com.backewatsch.mp3player.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Überträgt Musik vom Handy (z. B. aus Samsung Music) in die App:
 * - alle Lieder aus dem Android-Medienspeicher (MediaStore)
 * - Playlists aus .m3u-Dateien (Samsung Music → Playlists → Exportieren) und aus dem Medienspeicher
 */
class DeviceMusicImporter(private val context: Context, private val library: Library) {
    data class DeviceSong(
        val uri: Uri,
        val displayName: String,
        /** Originalpfad (z. B. /storage/emulated/0/Music/Lied.mp3) – dient zum Wiedererkennen */
        val path: String,
        val size: Long,
    ) {
        val sourceKey get() = path
    }

    data class Progress(val done: Int, val total: Int, val current: String)

    data class SongResult(val imported: Int, val skipped: Int, val failed: Int, val errors: List<String>)
    data class PlaylistResult(val playlists: Int, val tracks: Int, val missing: Int)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    /** Alle Musikdateien auf dem Gerät (ohne Klingeltöne, Benachrichtigungstöne und die eigenen Dateien der App) */
    suspend fun scanSongs(): List<DeviceSong> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        @Suppress("DEPRECATION")
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val songs = mutableListOf<DeviceSong>()
        val ownFolder = library.storage.musicDir.absolutePath
        context.contentResolver.query(collection, projection, selection, null, MediaStore.Audio.Media.DISPLAY_NAME)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            @Suppress("DEPRECATION")
            val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: continue
                val path = (if (dataCol >= 0) c.getString(dataCol) else null) ?: "media:$id:$name"
                if (path.startsWith(ownFolder)) continue
                if (!FileNames.isImportable(name)) continue
                songs += DeviceSong(ContentUris.withAppendedId(collection, id), name, path, c.getLong(sizeCol))
            }
        }
        songs
    }

    /** Kopiert die Lieder in die Mediathek (Nicht-MP3 wird umgewandelt). Bereits übertragene werden übersprungen. */
    suspend fun importSongs(songs: List<DeviceSong>): SongResult {
        var imported = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        try {
            songs.forEachIndexed { index, song ->
                _progress.value = Progress(index, songs.size, song.displayName)
                if (library.trackFromSource(song.sourceKey) != null) {
                    skipped++
                    return@forEachIndexed
                }
                try {
                    importSong(song)
                    imported++
                } catch (e: Exception) {
                    errors += "${song.displayName}: ${e.message}"
                }
            }
        } finally {
            _progress.value = null
        }
        return SongResult(imported, skipped, errors.size, errors)
    }

    private suspend fun importSong(song: DeviceSong) {
        val dir = library.storage.newIncomingDir()
        try {
            val file = File(dir, FileNames.sanitize(song.displayName))
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(song.uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                    ?: throw IllegalStateException("Datei nicht lesbar.")
            }
            library.import(file, move = true, sourceKey = song.sourceKey)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Importiert .m3u/.m3u8-Dateien als Playlists. Lieder, die noch nicht in der App sind, aber auf dem Gerät
     * liegen, werden automatisch mit übertragen.
     */
    suspend fun importPlaylistFiles(uris: List<Uri>): PlaylistResult {
        var playlists = 0
        var tracks = 0
        var missing = 0
        var deviceSongs: List<DeviceSong>? = null
        for (uri in uris) {
            val name = displayName(uri) ?: uri.lastPathSegment ?: "Playlist"
            if (!PlaylistFiles.isPlaylistFile(name)) continue
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            } ?: continue
            val ids = mutableListOf<String>()
            for (entry in PlaylistFiles.parse(text)) {
                var track = PlaylistFiles.match(entry, library.tracks)
                if (track == null) {
                    // Lied liegt auf dem Gerät, ist aber noch nicht übertragen → jetzt holen
                    val key = PlaylistFiles.key(entry.path)
                    val all = deviceSongs ?: scanSongsOrEmpty().also { deviceSongs = it }
                    val song = all.firstOrNull { it.path.equals(entry.path, true) }
                        ?: all.firstOrNull { PlaylistFiles.key(it.path) == key }
                    if (song != null) {
                        runCatching { importSong(song) }
                        track = library.trackFromSource(song.sourceKey)
                    }
                }
                if (track != null) ids += track.id else missing++
            }
            library.mergePlaylist(PlaylistFiles.nameFromFile(name), ids)
            playlists++
            tracks += ids.size
        }
        return PlaylistResult(playlists, tracks, missing)
    }

    private suspend fun scanSongsOrEmpty() = runCatching { scanSongs() }.getOrDefault(emptyList())

    /** Playlists, die im Android-Medienspeicher hinterlegt sind (ältere Geräte/Apps) */
    @Suppress("DEPRECATION")
    suspend fun importMediaStorePlaylists(): PlaylistResult = withContext(Dispatchers.IO) {
        var playlists = 0
        var tracks = 0
        var missing = 0
        val found = mutableListOf<Pair<String, List<String>>>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Playlists._ID, MediaStore.Audio.Playlists.NAME), null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    val paths = mutableListOf<String>()
                    context.contentResolver.query(
                        MediaStore.Audio.Playlists.Members.getContentUri("external", id),
                        arrayOf(MediaStore.Audio.Playlists.Members.DATA), null, null,
                        MediaStore.Audio.Playlists.Members.PLAY_ORDER,
                    )?.use { m -> while (m.moveToNext()) m.getString(0)?.let { paths += it } }
                    if (paths.isNotEmpty()) found += name to paths
                }
            }
        }
        withContext(Dispatchers.Main) {
            for ((name, paths) in found) {
                val ids = paths.mapNotNull { p ->
                    PlaylistFiles.match(PlaylistFiles.Entry(p, null), library.tracks)?.id.also { if (it == null) missing++ }
                }
                if (ids.isNotEmpty()) {
                    library.mergePlaylist(name, ids)
                    playlists++
                    tracks += ids.size
                }
            }
        }
        PlaylistResult(playlists, tracks, missing)
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }
}
