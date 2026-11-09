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
                    favorites = s.favorites.filter { it !in missing },
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

    fun delete(track: Track) = deleteAll(listOf(track))

    /** Löscht mehrere Titel vom Gerät und aus allen Playlists. */
    fun deleteAll(list: List<Track>) {
        if (list.isEmpty()) return
        val ids = list.map { it.id }.toSet()
        list.forEach { fileOf(it).delete(); storage.artworkFor(it.id).delete() }
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

    // region Lautstärke, Songtexte, Cover, Doppelte, automatische Playlists

    fun gainOf(trackId: String): Float? = _state.value.gains[trackId]
    fun tracksWithoutGain(): List<Track> = tracks.filter { it.id !in _state.value.gains }

    fun setGain(trackId: String, gainDb: Float) {
        if (track(trackId) == null) return
        _state.update { s -> s.copy(gains = s.gains + (trackId to gainDb)) }
        save()
    }

    /** Songtext: selbst eingetragen oder aus der MP3 (USLT) gelesen */
    fun lyricsOf(track: Track): String? =
        _state.value.lyrics[track.id] ?: Id3Lyrics.read(fileOf(track))

    fun setLyrics(track: Track, text: String) {
        val clean = text.trim()
        _state.update { s -> s.copy(lyrics = if (clean.isEmpty()) s.lyrics - track.id else s.lyrics + (track.id to clean)) }
        save()
    }

    fun hasCustomCover(track: Track) = track.id in _state.value.customCovers

    /** Eigenes Cover (JPEG-Bytes) für einen Titel speichern */
    fun setCustomCover(track: Track, jpeg: ByteArray) {
        storage.artworkFor(track.id).writeBytes(jpeg)
        _state.update { s -> s.copy(customCovers = (listOf(track.id) + s.customCovers).distinct()) }
        coverVersion.value++
        save()
    }

    fun removeCustomCover(track: Track) {
        storage.artworkFor(track.id).delete()
        // Eingebettetes Cover wiederherstellen
        MetadataReader.shrinkArtwork(MetadataReader.read(fileOf(track)).artwork)?.let { storage.artworkFor(track.id).writeBytes(it) }
        _state.update { s -> s.copy(customCovers = s.customCovers - track.id) }
        coverVersion.value++
        save()
    }

    fun playlistCoverFile(playlist: Playlist) = File(storage.artworkDir, "playlist-${playlist.id}.jpg")

    fun setPlaylistCover(playlist: Playlist, jpeg: ByteArray?) {
        val f = playlistCoverFile(playlist)
        if (jpeg == null) f.delete() else f.writeBytes(jpeg)
        // Anzeige lädt das Bild neu
        coverVersion.value++
    }

    /** Zählt hoch, wenn sich ein Cover-Bild geändert hat (Anzeige lädt dann neu) */
    val coverVersion = MutableStateFlow(0)

    fun duplicateGroups(): List<List<Track>> = Duplicates.groups(tracks)

    /** Behält ein Lied der Gruppe, übernimmt Playlists, Herzen, Zähler und Verlauf der anderen und löscht diese. */
    fun mergeDuplicates(group: List<Track>): Track {
        val s0 = _state.value
        val keep = Duplicates.keeper(group, s0.playCounts, { t -> s0.playlists.count { t.id in it.trackIds } }, s0.favorites.toSet())
        val others = group.filter { it.id != keep.id }
        if (others.isEmpty()) return keep
        val gone = others.map { it.id }.toSet()
        fun swap(id: String) = if (id in gone) keep.id else id
        _state.update { s ->
            s.copy(
                playlists = s.playlists.map { p -> p.copy(trackIds = p.trackIds.map(::swap).distinct()) },
                pinned = s.pinned.map(::swap).distinct(),
                favorites = s.favorites.map(::swap).distinct(),
                playCounts = (s.playCounts - gone) + (keep.id to group.sumOf { s.playCounts[it.id] ?: 0 }),
                history = s.history.map { it.copy(trackId = swap(it.trackId)) },
                plays = s.plays.map { it.copy(trackId = swap(it.trackId)) },
                lyrics = run {
                    val text = s.lyrics[keep.id] ?: others.firstNotNullOfOrNull { s.lyrics[it.id] }
                    (s.lyrics - gone - keep.id) + (if (text != null) mapOf(keep.id to text) else emptyMap())
                },
            )
        }
        save()
        deleteAll(others)
        return keep
    }

    fun smartTracks(kind: SmartPlaylist): List<Track> = kind.tracks(_state.value)

    // endregion

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

    // region Oft gespielt & Verlauf

    /** Titel wurde gestartet → in den Verlauf (gleicher Titel innerhalb einer Minute nur einmal) */
    fun recordStart(trackId: String, now: Long = System.currentTimeMillis()) {
        if (track(trackId) == null) return
        _state.update { s ->
            val recent = s.history.firstOrNull()
            if (recent != null && recent.trackId == trackId && now - recent.at < 60_000) return@update s
            s.copy(history = (listOf(PlayEvent(trackId, now)) + s.history).take(MAX_HISTORY))
        }
        save()
    }

    /** Titel wurde richtig gehört (nicht nur angespielt) → zählt für „Oft gespielt“ */
    fun recordPlay(trackId: String) {
        if (track(trackId) == null) return
        val now = System.currentTimeMillis()
        _state.update { s ->
            s.copy(
                playCounts = s.playCounts + (trackId to ((s.playCounts[trackId] ?: 0) + 1)),
                plays = (listOf(PlayEvent(trackId, now)) + s.plays).take(Stats.MAX_PLAYS),
            )
        }
        save()
    }

    /** Hörzeit für die Statistik hinzufügen */
    fun addListened(ms: Long, now: Long = System.currentTimeMillis()) {
        if (ms <= 0) return
        val key = Stats.monthKey(now)
        _state.update { s -> s.copy(listenedByMonth = s.listenedByMonth + (key to ((s.listenedByMonth[key] ?: 0) + ms))) }
        save()
    }

    fun clearStats() {
        _state.update { it.copy(plays = emptyList(), listenedByMonth = emptyMap()) }
        save()
    }

    fun playCount(track: Track) = _state.value.playCounts[track.id] ?: 0

    /** Meistgehörte Titel, häufigste zuerst */
    fun mostPlayed(limit: Int = 50): List<Pair<Track, Int>> {
        val byId = tracks.associateBy { it.id }
        return _state.value.playCounts.entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .mapNotNull { (id, count) -> byId[id]?.let { it to count } }
            .take(limit)
    }

    /** Verlauf: Titel mit Zeitpunkt, neueste zuerst */
    fun history(limit: Int = MAX_HISTORY): List<Pair<Track, Long>> {
        val byId = tracks.associateBy { it.id }
        return _state.value.history.mapNotNull { e -> byId[e.trackId]?.let { it to e.at } }.take(limit)
    }

    /** Zuletzt gehört, jeder Titel nur einmal */
    fun recentlyPlayed(limit: Int = 20): List<Track> = history().map { it.first }.distinctBy { it.id }.take(limit)

    fun clearHistory() {
        _state.update { it.copy(history = emptyList(), playCounts = emptyMap()) }
        save()
    }

    // endregion

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

    /**
     * Spielt eine Sicherung ein, ohne Vorhandenes zu verlieren: Titel werden über ID, Herkunft oder
     * Titel+Interpret+Länge wiedererkannt; fehlende Titel kommen aus [musicFiles] (Dateiname → Datei), falls vorhanden.
     * Playlists werden über den Namen zusammengeführt, Herzen/Kurzwahl vereinigt, Zähler und Hörzeit übernommen.
     */
    suspend fun restore(backup: LibraryState, musicFiles: Map<String, File>, artworkFiles: Map<String, File>): RestoreResult {
        val idMap = mutableMapOf<String, String>()
        var added = 0
        var matched = 0
        var missing = 0
        val newTracks = mutableListOf<Track>()
        val reserved = mutableListOf<String>()
        try {
            for (bt in backup.tracks) {
                val existing = track(bt.id)
                    ?: tracks.firstOrNull { bt.source.isNotEmpty() && it.source == bt.source }
                    ?: tracks.firstOrNull {
                        it.title.equals(bt.title, ignoreCase = true) && it.artist.equals(bt.artist, ignoreCase = true) &&
                            kotlin.math.abs(it.durationMs - bt.durationMs) < 2_000
                    }
                if (existing != null) {
                    idMap[bt.id] = existing.id; matched++; continue
                }
                val file = musicFiles[bt.fileName]
                if (file == null || !file.exists()) { missing++; continue }
                val destination = mutex.withLock {
                    storage.uniqueDestination(FileNames.sanitize(bt.fileName)).also { importing += it.name; reserved += it.name }
                }
                withContext(Dispatchers.IO) { file.copyTo(destination, overwrite = false) }
                val id = if (newTracks.none { it.id == bt.id }) bt.id else java.util.UUID.randomUUID().toString()
                artworkFiles[bt.id]?.takeIf { it.exists() }?.let { art ->
                    withContext(Dispatchers.IO) { art.copyTo(storage.artworkFor(id), overwrite = true) }
                }
                newTracks += bt.copy(id = id, fileName = destination.name)
                idMap[bt.id] = id
                added++
            }

            fun map(ids: List<String>) = ids.mapNotNull { idMap[it] }
            mutex.withLock {
                _state.update { s ->
                    var playlists = s.playlists
                    for (bp in backup.playlists) {
                        val ids = map(bp.trackIds)
                        val target = if (bp.id == DOWNLOADS_PLAYLIST_ID) playlists.firstOrNull { it.id == DOWNLOADS_PLAYLIST_ID }
                            else playlists.firstOrNull { it.id != DOWNLOADS_PLAYLIST_ID && it.name.equals(bp.name.trim(), ignoreCase = true) }
                        playlists = if (target != null) {
                            playlists.map { if (it.id == target.id) it.copy(trackIds = (it.trackIds + ids).distinct()) else it }
                        } else {
                            val id = if (playlists.none { it.id == bp.id }) bp.id else java.util.UUID.randomUUID().toString()
                            playlists + Playlist(id = id, name = bp.name, trackIds = ids.distinct())
                        }
                    }
                    val counts = s.playCounts.toMutableMap()
                    backup.playCounts.forEach { (id, n) -> idMap[id]?.let { counts[it] = maxOf(counts[it] ?: 0, n) } }
                    val listened = s.listenedByMonth.toMutableMap()
                    backup.listenedByMonth.forEach { (k, v) -> listened[k] = maxOf(listened[k] ?: 0, v) }
                    fun mergeEvents(a: List<PlayEvent>, b: List<PlayEvent>, max: Int) =
                        (a + b.mapNotNull { e -> idMap[e.trackId]?.let { e.copy(trackId = it) } })
                            .distinctBy { it.trackId to it.at }.sortedByDescending { it.at }.take(max)
                    s.copy(
                        tracks = s.tracks + newTracks,
                        playlists = playlists,
                        pinned = (s.pinned + map(backup.pinned)).distinct(),
                        favorites = (s.favorites + map(backup.favorites)).distinct(),
                        playCounts = counts,
                        history = mergeEvents(s.history, backup.history, MAX_HISTORY),
                        plays = mergeEvents(s.plays, backup.plays, Stats.MAX_PLAYS),
                        listenedByMonth = listened,
                    )
                }
            }
            save()
            return RestoreResult(added, matched, missing, backup.playlists.size)
        } finally {
            mutex.withLock { importing -= reserved.toSet() }
        }
    }

    // endregion

    // region Speichern

    private fun load() {
        val file = storage.indexFile
        if (!file.exists()) return
        runCatching { json.decodeFromString(LibraryState.serializer(), file.readText()) }
            .onSuccess { loaded -> _state.value = loaded.copy(pinned = loaded.pinned.filter { id -> loaded.tracks.any { it.id == id } }) }
    }

    @Synchronized
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

    companion object {
        const val MAX_HISTORY = 300
    }

    private fun ensureDownloadsPlaylist() {
        if (playlists.none { it.id == DOWNLOADS_PLAYLIST_ID }) {
            _state.update { it.copy(playlists = listOf(Playlist(id = DOWNLOADS_PLAYLIST_ID, name = "Heruntergeladen")) + it.playlists) }
            save()
        }
    }

    // endregion
}
