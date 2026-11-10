package com.backewatsch.mp3player.data

import com.backewatsch.mp3player.convert.Mp3Converter
import com.backewatsch.mp3player.model.LibraryState
import com.backewatsch.mp3player.model.LibraryState.Companion.DOWNLOADS_PLAYLIST_ID
import com.backewatsch.mp3player.model.PlayEvent
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
    internal val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    /** Fortschritt einer laufenden Umwandlung, sonst null */
    private val _conversionStatus = MutableStateFlow<String?>(null)
    val conversionStatus: StateFlow<String?> = _conversionStatus.asStateFlow()

    /** Wird aufgerufen, wenn ein Titel hinzugekommen ist (Ziel-Playlist bei Downloads) */
    var onTrackAdded: ((Track, String?) -> Unit)? = null

    internal val mutex = Mutex()
    internal val importing = mutableSetOf<String>()
    @Volatile private var converting = false

    /** Speicher-Wünsche: werden gebündelt und im Hintergrund geschrieben */
    private val saveRequests = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    @Volatile private var dirty = false

    init {
        load()
        ensureDownloadsPlaylist()
        scope.launch(Dispatchers.IO) {
            for (request in saveRequests) {
                // Kurz warten, damit mehrere Änderungen in einem Schreibvorgang landen
                kotlinx.coroutines.delay(SAVE_DELAY_MS)
                writeNow()
            }
        }
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
    fun sortedTracks(order: TrackSort): List<Track> = order.apply(tracks, _state.value.playCounts)
    fun isFavorite(track: Track) = track.id in _state.value.favorites
    /** Lieblingssongs, zuletzt markierte zuerst */
    fun favoriteTracks(): List<Track> {
        val byId = tracks.associateBy { it.id }
        return _state.value.favorites.mapNotNull { byId[it] }
    }
    fun pinnedTracks(): List<Track> = _state.value.pinned.mapNotNull { track(it) }
    fun isPinned(track: Track) = track.id in _state.value.pinned
    fun tracksByArtist(artist: String) = sortedTracks().filter { it.artist.equals(artist, ignoreCase = true) }
    fun tracksInAlbum(album: String) = sortedTracks().filter { it.album.equals(album, ignoreCase = true) }
    fun albumNames() = groupNames(tracks.map { it.album })
    fun genreNames() = groupNames(tracks.map { it.genre })
    fun tracksInGenre(genre: String) = sortedTracks().filter { it.genre.equals(genre, ignoreCase = true) }
    fun artistNames() = groupNames(tracks.map { it.artist })
    /** Nicht-MP3 im eigenen Ordner (verknüpfte Originale werden nie umgewandelt – sie bleiben, wie sie sind) */
    fun convertibleTracks() = tracks.filter { !it.isLinked && !FileNames.isMp3(it.fileName) }
    /** Datei eines Titels: verknüpftes Original oder Datei im Musikordner der App */
    fun fileOf(track: Track) = if (track.isLinked) File(track.path) else storage.fileFor(track.fileName)
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
        sourceKey: String = "",
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
                return import(converted, move = true, addToDownloads = addToDownloads, extraPlaylistId = extraPlaylistId,
                    sourceKey = sourceKey)
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
            val track = withContext(Dispatchers.IO) { makeTrack(destination) }.copy(source = sourceKey)
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
        val known = tracks.filter { !it.isLinked }.map { it.fileName }.toSet()
        // Verknüpfte Originale, die es nicht mehr gibt (z. B. in Samsung Music gelöscht). Nur prüfen, wenn die App
        // überhaupt lesen darf – sonst wirkten alle Dateien „verschwunden“.
        val linkedGone = if (storage.canReadSharedMusic()) withContext(Dispatchers.IO) {
            tracks.filter { it.isLinked && !File(it.path).exists() }.map { it.id }.toSet()
        } else emptySet()
        val newTracks = withContext(Dispatchers.IO) {
            files.filter { it.name !in known && it.name !in busy }.map { makeTrack(it) }
        }
        var changed = false
        mutex.withLock {
            _state.update { s ->
                val missing = s.tracks.filter { !it.isLinked && it.fileName !in present && it.fileName !in importing }
                    .map { it.id }.toSet() + linkedGone
                val fresh = newTracks.filter { t -> s.tracks.none { !it.isLinked && it.fileName == t.fileName } && t.fileName !in importing }
                if (missing.isEmpty() && fresh.isEmpty()) return@update s
                changed = true
                s.copy(
                    tracks = s.tracks.filter { it.id !in missing } + fresh,
                    playlists = s.playlists.map { p -> p.copy(trackIds = p.trackIds.filter { it !in missing }) },
                    pinned = s.pinned.filter { it !in missing },
                    favorites = s.favorites.filter { it !in missing },
                )
            }
        }
        if (changed) save()
        if (autoConvert && convertibleTracks().isNotEmpty()) {
            scope.launch { convertAllToMp3() }
        }
    }

    /**
     * Verknüpft eine Datei auf dem Handy (ohne sie zu kopieren). Ist sie schon verknüpft, kommt der vorhandene Titel zurück.
     * @param sourceKey Herkunft zum Wiedererkennen (Pfad aus dem Medienspeicher)
     */
    suspend fun link(file: File, sourceKey: String = file.absolutePath, addToDownloads: Boolean = false): Track {
        val path = file.absolutePath
        tracks.firstOrNull { it.path == path || (sourceKey.isNotEmpty() && it.source == sourceKey) }?.let { return it }
        if (!file.canRead()) throw LibraryException("„${file.name}“ ist nicht lesbar.")
        if (!FileNames.isImportable(file.name)) throw LibraryException("„${file.name}“ ist keine unterstützte Audiodatei.")
        val track = withContext(Dispatchers.IO) { makeTrack(file) }.copy(source = sourceKey, path = path)
        var target: String? = null
        mutex.withLock {
            _state.update { s ->
                var playlists = s.playlists
                if (addToDownloads) {
                    playlists = playlists.map { if (it.id == DOWNLOADS_PLAYLIST_ID) it.copy(trackIds = it.trackIds + track.id) else it }
                    target = DOWNLOADS_PLAYLIST_ID
                }
                s.copy(tracks = s.tracks + track, playlists = playlists)
            }
        }
        save()
        onTrackAdded?.invoke(track, target)
        return track
    }

    /** Kopien, die genau dem Original auf dem Handy entsprechen – können durch Verknüpfungen ersetzt werden */
    suspend fun linkableCopies(): List<Track> = withContext(Dispatchers.IO) {
        if (!storage.canReadSharedMusic()) return@withContext emptyList()
        tracks.filter { t ->
            if (t.isLinked || !t.source.startsWith("/")) return@filter false
            val original = File(t.source)
            val copy = storage.fileFor(t.fileName)
            // Gleiche Größe = unverändert übernommen (zugeschnittene, umgewandelte oder bearbeitete Kopien bleiben)
            original.exists() && original.canRead() && copy.exists() && original.length() == copy.length()
        }
    }

    /** Ersetzt unveränderte Kopien durch Verknüpfungen zum Original; liefert Anzahl und freigegebene Bytes */
    suspend fun replaceCopiesWithLinks(): Pair<Int, Long> {
        val candidates = linkableCopies()
        var freed = 0L
        var count = 0
        for (t in candidates) {
            val copy = storage.fileFor(t.fileName)
            val original = File(t.source)
            val size = copy.length()
            mutex.withLock {
                _state.update { s ->
                    s.copy(tracks = s.tracks.map { if (it.id == t.id) it.copy(path = original.absolutePath, fileName = original.name) else it })
                }
            }
            if (withContext(Dispatchers.IO) { copy.delete() }) { freed += size; count++ }
        }
        if (candidates.isNotEmpty()) save()
        return count to freed
    }

    private fun makeTrack(file: File): Track {
        val info = MetadataReader.read(file)
        val track = Track(
            fileName = file.name,
            title = info.title?.takeIf { it.isNotBlank() } ?: FileNames.baseName(file.name),
            artist = info.artist.orEmpty(),
            album = info.album.orEmpty(),
            durationMs = info.durationMs,
            genre = Genres.normalize(info.genre),
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

    fun delete(track: Track) = deleteAll(listOf(track))

    /** Löscht mehrere Titel vom Gerät und aus allen Playlists. */
    fun deleteAll(list: List<Track>) {
        if (list.isEmpty()) return
        val ids = list.map { it.id }.toSet()
        list.forEach {
            // Verknüpfte Originale bleiben auf dem Handy – nur die eigene Kopie wird gelöscht
            if (!it.isLinked) fileOf(it).delete()
            storage.artworkFor(it.id).delete(); storage.originalCoverFor(it.id).delete()
        }
        _state.update { s ->
            s.copy(
                tracks = s.tracks.filter { it.id !in ids },
                playlists = s.playlists.map { p -> p.copy(trackIds = p.trackIds.filter { it !in ids }) },
                pinned = s.pinned.filter { it !in ids },
                favorites = s.favorites.filter { it !in ids },
                playCounts = s.playCounts - ids,
                history = s.history.filter { it.trackId !in ids },
                plays = s.plays.filter { it.trackId !in ids },
                gains = s.gains - ids,
                lyrics = s.lyrics - ids,
                customCovers = s.customCovers.filter { it !in ids },
            )
        }
        save()
    }

    // Lautstärke, Songtexte, Cover, Doppelte, Mixe: siehe LibraryExtras.kt
    /** Zählt hoch, wenn sich ein Cover-Bild geändert hat (Anzeige lädt dann neu) */
    val coverVersion = MutableStateFlow(0)

    fun toggleFavorite(track: Track) {
        _state.update { s ->
            s.copy(favorites = if (track.id in s.favorites) s.favorites - track.id else listOf(track.id) + s.favorites)
        }
        save()
    }

    /** Mehrere Titel als Lieblingssongs markieren */
    fun addFavorites(list: List<Track>) {
        _state.update { s -> s.copy(favorites = (list.map { it.id }.filter { it !in s.favorites } + s.favorites)) }
        save()
    }

    /**
     * Infos ändern und – bei MP3s – auch in die Datei schreiben (Titel, Interpret, Album, Genre).
     * Blockiert kurz (Datei schreiben) → im Hintergrund aufrufen.
     */
    fun editInfo(track: Track, title: String, artist: String, album: String, genre: String) {
        val t = title.trim().ifEmpty { track.title }
        val g = Genres.normalize(genre).ifEmpty { genre.trim() }
        _state.update { s ->
            s.copy(tracks = s.tracks.map {
                if (it.id == track.id) it.copy(title = t, artist = artist.trim(), album = album.trim(), genre = g) else it
            })
        }
        save()
        writeTags(track, Id3Writer.Update(title = t, artist = artist.trim(), album = album.trim(), genre = g))
    }

    /**
     * Alle Änderungen an den MP3-Dateien laufen nacheinander im Hintergrund – nie im Hauptthread und nie
     * zwei gleichzeitig (sonst könnte z. B. Zuschneiden eine gerade geschriebene Info überschreiben).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal val fileDispatcher = Dispatchers.IO.limitedParallelism(1)
    @Volatile private var lastFileJob: kotlinx.coroutines.Job? = null

    internal fun runOnFiles(block: () -> Unit) {
        lastFileJob = scope.launch(fileDispatcher) { runCatching(block) }
    }

    /** Wartet, bis alle Änderungen an den MP3-Dateien geschrieben sind (Tests, Sicherung) */
    fun awaitFileWrites() = kotlinx.coroutines.runBlocking { lastFileJob?.join() }

    /** Änderungen in die MP3 schreiben (Fehler ändern nichts an der Mediathek) */
    internal fun writeTags(track: Track, update: Id3Writer.Update) {
        // Verknüpfte Originale gehören anderen Apps → Änderungen nur in der Mediathek, die Datei bleibt unberührt
        if (track(track.id)?.isLinked ?: track.isLinked) return
        val file = fileOf(track)
        if (!FileNames.isMp3(file.name)) return
        // Die Datei bleibt dabei immer vollständig (neue Fassung wird erst fertig geschrieben, dann ausgetauscht)
        runOnFiles { if (file.exists()) Id3Writer.write(file, update) }
    }

    /** Genres aller Titel einmalig aus den Dateien lesen (ältere Mediatheken kannten noch keine Genres) */
    suspend fun scanGenresOnce() {
        if (_state.value.genresScanned) return
        val found = withContext(Dispatchers.IO) {
            tracks.filter { it.genre.isBlank() }.associate { it.id to Genres.normalize(MetadataReader.read(fileOf(it)).genre) }
        }.filterValues { it.isNotEmpty() }
        _state.update { s ->
            s.copy(tracks = s.tracks.map { t -> found[t.id]?.let { t.copy(genre = it) } ?: t }, genresScanned = true)
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

    /** Titel, der aus dieser Quelle stammt (bereits übertragen?) */
    fun trackFromSource(sourceKey: String) = tracks.firstOrNull { it.source.isNotEmpty() && it.source == sourceKey }

    /** Playlist mit diesem Namen holen oder anlegen und Titel (ohne Doppelte) anhängen */
    fun mergePlaylist(name: String, trackIds: List<String>): Playlist {
        val existing = playlists.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) && it.id != DOWNLOADS_PLAYLIST_ID }
        val playlist = existing ?: createPlaylist(name)
        updatePlaylist(playlist.id) { p -> p.copy(trackIds = (p.trackIds + trackIds).distinct()) }
        return playlist(playlist.id) ?: playlist
    }

    // Oft gespielt, Verlauf, Statistik: siehe LibraryHistory.kt

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

    /** Mehrere Titel (ohne Doppelte) an eine Playlist anhängen */
    fun addAll(list: List<Track>, playlist: Playlist) =
        updatePlaylist(playlist.id) { p -> p.copy(trackIds = (p.trackIds + list.map { it.id }).distinct()) }

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

    // region Sicherung

    /** Ergebnis einer Wiederherstellung */
    data class RestoreResult(val tracksAdded: Int, val tracksMatched: Int, val tracksMissing: Int, val playlists: Int)

    /** Aktueller Stand für eine Sicherung */
    fun snapshot(): LibraryState = _state.value


    // Wiederherstellen: siehe LibraryBackup.kt

    // endregion

    // region Speichern

    private fun load() {
        val file = storage.indexFile
        if (!file.exists()) return
        runCatching { json.decodeFromString(LibraryState.serializer(), file.readText()) }
            .onSuccess { loaded -> _state.value = loaded.copy(pinned = loaded.pinned.filter { id -> loaded.tracks.any { it.id == id } }) }
    }

    /** Änderung merken – geschrieben wird gebündelt im Hintergrund (nie im Haupt-Thread) */
    internal fun save() {
        dirty = true
        saveRequests.trySend(Unit)
    }

    /** Sofort schreiben, falls etwas offen ist (z. B. wenn die App in den Hintergrund geht) */
    fun flush() {
        if (dirty) writeNow()
    }

    @Synchronized
    private fun writeNow() {
        dirty = false
        val folder = storage.indexFile.parentFile ?: return
        // Ordner weg (z. B. Test-Ordner schon gelöscht) → nichts zu speichern, aber auch kein Absturz
        if (!folder.isDirectory) return
        try {
            val text = json.encodeToString(LibraryState.serializer(), _state.value.copy(version = LibraryState.CURRENT_VERSION))
            val tmp = File(folder, storage.indexFile.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(storage.indexFile)) {
                storage.indexFile.writeText(text)
                tmp.delete()
            }
        } catch (e: Exception) {
            // z. B. Speicher voll: beim nächsten Speichern erneut versuchen, die App läuft weiter
            dirty = true
            android.util.Log.w("Library", "Speichern fehlgeschlagen: ${e.message}")
        }
    }

    /** Nur für automatische Tests: leere Bibliothek */
    fun resetForTests() {
        dirty = false
        storage.resetAll()
        _state.value = LibraryState()
        ensureDownloadsPlaylist()
    }

    companion object {
        const val MAX_HISTORY = 300
        const val SAVE_DELAY_MS = 400L
    }

    private fun ensureDownloadsPlaylist() {
        if (playlists.none { it.id == DOWNLOADS_PLAYLIST_ID }) {
            _state.update { it.copy(playlists = listOf(Playlist(id = DOWNLOADS_PLAYLIST_ID, name = "Heruntergeladen")) + it.playlists) }
            save()
        }
    }

    // endregion
}
