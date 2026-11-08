package com.backewatsch.mp3player.data

import com.backewatsch.mp3player.convert.Mp3Converter
import com.backewatsch.mp3player.model.LibraryState
import com.backewatsch.mp3player.model.LibraryState.Companion.DOWNLOADS_PLAYLIST_ID
import com.backewatsch.mp3player.model.Playlist
import com.backewatsch.mp3player.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** Verwaltet Titel, Playlists und Kurzwahl. Alle neuen Dateien werden zu MP3. */
class Library(
    val storage: Storage,
    private val scope: CoroutineScope,
    /** Neu gefundene Nicht-MP3-Dateien (z. B. per USB kopiert) automatisch umwandeln */
    private val autoConvert: Boolean = true,
) {
    class LibraryException(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    /** Fortschritt einer laufenden Umwandlung, sonst null */
    private val _conversionStatus = MutableStateFlow<String?>(null)
    val conversionStatus: StateFlow<String?> = _conversionStatus.asStateFlow()

    /** Wird aufgerufen, wenn ein Titel hinzugekommen ist (Ziel-Playlist bei Downloads) */
    var onTrackAdded: ((Track, String?) -> Unit)? = null

    private val mutex = Mutex()
    private val importing = mutableSetOf<String>()
    @Volatile private var converting = false

    init {
        load()
        ensureDownloadsPlaylist()
    }

    // region Abfragen

    val tracks get() = _state.value.tracks
    val playlists get() = _state.value.playlists

    fun track(id: String) = tracks.firstOrNull { it.id == id }
    fun playlist(id: String) = playlists.firstOrNull { it.id == id }

    fun tracksIn(playlist: Playlist): List<Track> {
        val byId = tracks.associateBy { it.id }
        return playlist.trackIds.mapNotNull { byId[it] }
    }

    fun sortedTracks(): List<Track> = tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    fun pinnedTracks(): List<Track> = _state.value.pinned.mapNotNull { track(it) }
    fun isPinned(track: Track) = track.id in _state.value.pinned
    fun tracksByArtist(artist: String) = sortedTracks().filter { it.artist.equals(artist, ignoreCase = true) }
    fun tracksInAlbum(album: String) = sortedTracks().filter { it.album.equals(album, ignoreCase = true) }
    fun albumNames() = groupNames(tracks.map { it.album })
    fun artistNames() = groupNames(tracks.map { it.artist })
    fun convertibleTracks() = tracks.filter { !FileNames.isMp3(it.fileName) }
    fun fileOf(track: Track) = storage.fileFor(track.fileName)
    fun artworkOf(track: Track) = storage.artworkFor(track.id).takeIf { it.exists() }

    private fun groupNames(names: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return names.filter { it.isNotBlank() && seen.add(it.lowercase()) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    // endregion

    // region Import

    /**
     * Übernimmt eine Datei in die Bibliothek. Alles, was nicht MP3 ist, wird vorher in MP3 umgewandelt.
     * @param move Quelle verschieben (temporäre Downloads) statt kopieren
     */
    suspend fun import(
        source: File,
        preferredName: String? = null,
        move: Boolean,
        addToDownloads: Boolean = false,
        extraPlaylistId: String? = null,
    ): Track {
        val name = FileNames.sanitize(preferredName ?: source.name)
        if (!FileNames.isImportable(name)) {
            throw LibraryException("„$name“ ist keine unterstützte Audio- oder Videodatei.")
        }

        // Alles, was nicht MP3 ist, umwandeln
        if (!FileNames.isMp3(name)) {
            val dir = storage.newIncomingDir()
            val converted = File(dir, FileNames.baseName(name) + ".mp3")
            try {
                withContext(Dispatchers.Default) { Mp3Converter.convert(source, converted) }
                if (move) source.delete()
                return import(converted, move = true, addToDownloads = addToDownloads, extraPlaylistId = extraPlaylistId)
            } finally {
                dir.deleteRecursively()
            }
        }

        val destination = mutex.withLock {
            storage.uniqueDestination(name).also { importing += it.name }
        }
        try {
            withContext(Dispatchers.IO) {
                if (move && source.renameTo(destination)) return@withContext
                source.copyTo(destination, overwrite = false)
                if (move) source.delete()
            }
            val track = withContext(Dispatchers.IO) { makeTrack(destination) }
            var target: String? = null
            mutex.withLock {
                _state.update { s ->
                    var playlists = s.playlists
                    if (addToDownloads) {
                        playlists = playlists.map { if (it.id == DOWNLOADS_PLAYLIST_ID) it.copy(trackIds = it.trackIds + track.id) else it }
                        target = DOWNLOADS_PLAYLIST_ID
                    }
                    if (extraPlaylistId != null && playlists.any { it.id == extraPlaylistId }) {
                        playlists = playlists.map { if (it.id == extraPlaylistId) it.copy(trackIds = it.trackIds + track.id) else it }
                        target = extraPlaylistId
                    }
                    s.copy(tracks = s.tracks + track, playlists = playlists)
                }
            }
            save()
            onTrackAdded?.invoke(track, target)
            return track
        } finally {
            mutex.withLock { importing -= destination.name }
        }
    }

    /** Gleicht die Bibliothek mit dem Musikordner ab (z. B. per USB kopierte Dateien). */
    suspend fun rescan() {
        val files = withContext(Dispatchers.IO) {
            storage.musicDir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") && FileNames.isAudio(it.name) }.orEmpty()
        }
        val present = files.map { it.name }.toSet()
        val busy = mutex.withLock { importing.toSet() }
        val known = tracks.map { it.fileName }.toSet()
        val newTracks = withContext(Dispatchers.IO) {
            files.filter { it.name !in known && it.name !in busy }.map { makeTrack(it) }
        }
        var changed = false
        mutex.withLock {
            _state.update { s ->
                val missing = s.tracks.filter { it.fileName !in present && it.fileName !in importing }.map { it.id }.toSet()
                val fresh = newTracks.filter { t -> s.tracks.none { it.fileName == t.fileName } && t.fileName !in importing }
                if (missing.isEmpty() && fresh.isEmpty()) return@update s
                changed = true
                s.copy(
                    tracks = s.tracks.filter { it.id !in missing } + fresh,
                    playlists = s.playlists.map { p -> p.copy(trackIds = p.trackIds.filter { it !in missing }) },
                    pinned = s.pinned.filter { it !in missing },
                )
            }
        }
        if (changed) save()
        if (autoConvert && convertibleTracks().isNotEmpty()) {
            scope.launch { convertAllToMp3() }
        }
    }

    private fun makeTrack(file: File): Track {
        val info = MetadataReader.read(file)
        val track = Track(
            fileName = file.name,
            title = info.title?.takeIf { it.isNotBlank() } ?: FileNames.baseName(file.name),
            artist = info.artist.orEmpty(),
            album = info.album.orEmpty(),
            durationMs = info.durationMs,
        )
        MetadataReader.shrinkArtwork(info.artwork)?.let { storage.artworkFor(track.id).writeBytes(it) }
        return track
    }

    // endregion

    // region Umwandeln in MP3

    /** Wandelt die Datei eines Titels in MP3 um; ID, Playlists, Kurzwahl und Cover bleiben. */
    suspend fun convertToMp3(track: Track): Track {
        val current = track(track.id) ?: return track
        if (FileNames.isMp3(current.fileName)) return current
        val oldFile = fileOf(current)
        val dir = storage.newIncomingDir()
        try {
            val converted = File(dir, FileNames.baseName(current.fileName) + ".mp3")
            withContext(Dispatchers.Default) { Mp3Converter.convert(oldFile, converted) }
            val destination = mutex.withLock {
                storage.uniqueDestination(converted.name).also { importing += it.name }
            }
            try {
                withContext(Dispatchers.IO) { converted.copyTo(destination) }
                val duration = withContext(Dispatchers.IO) { MetadataReader.read(destination).durationMs }
                mutex.withLock {
                    _state.update { s ->
                        s.copy(tracks = s.tracks.map {
                            if (it.id == current.id) it.copy(fileName = destination.name, durationMs = if (duration > 0) duration else it.durationMs) else it
                        })
                    }
                }
                oldFile.delete()
                save()
            } finally {
                mutex.withLock { importing -= destination.name }
            }
        } finally {
            dir.deleteRecursively()
        }
        return track(current.id) ?: current
    }

    /** Wandelt alle Nicht-MP3-Titel nacheinander um. */
    suspend fun convertAllToMp3(skipId: String? = null): Pair<Int, Int> {
        if (converting) return 0 to 0
        converting = true
        var ok = 0
        var failed = 0
        try {
            val todo = convertibleTracks().filter { it.id != skipId }
            todo.forEachIndexed { n, t ->
                _conversionStatus.value = "Wandle um: ${n + 1} von ${todo.size} – „${t.title}“"
                try {
                    convertToMp3(t); ok++
                } catch (e: Exception) {
                    failed++
                }
            }
        } finally {
            _conversionStatus.value = null
            converting = false
        }
        return ok to failed
    }

    fun setConversionStatus(text: String?) {
        _conversionStatus.value = text
    }

    // endregion

    // region Bearbeiten

    fun delete(track: Track) {
        fileOf(track).delete()
        storage.artworkFor(track.id).delete()
        _state.update { s ->
            s.copy(
                tracks = s.tracks.filter { it.id != track.id },
                playlists = s.playlists.map { p -> p.copy(trackIds = p.trackIds - track.id) },
                pinned = s.pinned - track.id,
            )
        }
        save()
    }

    fun rename(track: Track, title: String, artist: String) {
        _state.update { s ->
            s.copy(tracks = s.tracks.map {
                if (it.id == track.id) it.copy(title = title.trim().ifEmpty { it.title }, artist = artist.trim()) else it
            })
        }
        save()
    }

    fun togglePin(track: Track) {
        _state.update { s ->
            s.copy(pinned = if (track.id in s.pinned) s.pinned - track.id else listOf(track.id) + s.pinned)
        }
        save()
    }

    fun createPlaylist(name: String): Playlist {
        val playlist = Playlist(name = name.trim().ifEmpty { "Neue Playlist" })
        _state.update { it.copy(playlists = it.playlists + playlist) }
        save()
        return playlist
    }

    fun renamePlaylist(playlist: Playlist, name: String) {
        if (name.isBlank()) return
        updatePlaylist(playlist.id) { it.copy(name = name.trim()) }
    }

    fun deletePlaylist(playlist: Playlist) {
        if (playlist.id == DOWNLOADS_PLAYLIST_ID) return
        _state.update { s -> s.copy(playlists = s.playlists.filter { it.id != playlist.id }) }
        save()
    }

    fun add(track: Track, playlist: Playlist) =
        updatePlaylist(playlist.id) { if (track.id in it.trackIds) it else it.copy(trackIds = it.trackIds + track.id) }

    fun remove(track: Track, playlist: Playlist) =
        updatePlaylist(playlist.id) { it.copy(trackIds = it.trackIds - track.id) }

    fun move(playlist: Playlist, from: Int, to: Int) = updatePlaylist(playlist.id) { p ->
        val ids = tracksIn(p).map { it.id }.toMutableList()
        if (from !in ids.indices || to !in ids.indices) return@updatePlaylist p
        ids.add(to, ids.removeAt(from))
        p.copy(trackIds = ids)
    }

    private fun updatePlaylist(id: String, change: (Playlist) -> Playlist) {
        _state.update { s -> s.copy(playlists = s.playlists.map { if (it.id == id) change(it) else it }) }
        save()
    }

    // endregion

    // region Speichern

    private fun load() {
        val file = storage.indexFile
        if (!file.exists()) return
        runCatching { json.decodeFromString(LibraryState.serializer(), file.readText()) }
            .onSuccess { loaded -> _state.value = loaded.copy(pinned = loaded.pinned.filter { id -> loaded.tracks.any { it.id == id } }) }
    }

    private fun save() {
        val text = json.encodeToString(LibraryState.serializer(), _state.value.copy(version = LibraryState.CURRENT_VERSION))
        val tmp = File(storage.indexFile.parentFile, storage.indexFile.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(storage.indexFile)
    }

    /** Nur für automatische Tests: leere Bibliothek */
    fun resetForTests() {
        storage.resetAll()
        _state.value = LibraryState()
        ensureDownloadsPlaylist()
    }

    private fun ensureDownloadsPlaylist() {
        if (playlists.none { it.id == DOWNLOADS_PLAYLIST_ID }) {
            _state.update { it.copy(playlists = listOf(Playlist(id = DOWNLOADS_PLAYLIST_ID, name = "Heruntergeladen")) + it.playlists) }
            save()
        }
    }

    // endregion
}
