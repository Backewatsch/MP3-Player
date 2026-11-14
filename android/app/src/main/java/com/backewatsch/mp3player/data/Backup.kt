package com.backewatsch.mp3player.data

import com.backewatsch.mp3player.model.LibraryState
import com.backewatsch.mp3player.model.Track
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Sicherung als ZIP: „library.json“ (Playlists, Herzen, Statistik …) und auf Wunsch die Musik („music/…“)
 * samt Covern („artwork/…“). Damit lässt sich alles auf einem neuen Handy oder nach Neuinstallation zurückholen.
 */
class Backup(private val library: Library) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Schreibt die Sicherung; [onProgress] bekommt (fertig, gesamt). */
    suspend fun export(output: OutputStream, includeMusic: Boolean, onProgress: (Int, Int) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.IO) {
            // Noch laufende Änderungen an den MP3s (Infos, Cover) erst fertig schreiben
            library.awaitFileWrites()
            // „Importierte Lieder“ gehören (noch) nicht zur Mediathek und kommen nicht in die Sicherung
            val state = library.snapshot().copy(imported = emptyList())
            ZipOutputStream(output.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(INDEX))
                zip.write(json.encodeToString(LibraryState.serializer(), state).toByteArray())
                zip.closeEntry()
                if (includeMusic) {
                    val names = mutableSetOf<String>()
                    state.tracks.forEachIndexed { n, track ->
                        onProgress(n, state.tracks.size)
                        val file = library.fileOf(track)
                        // Gleicher Dateiname zweimal (z. B. verknüpftes Original und eigene Kopie) → eindeutig machen.
                        // Der Name wird unabhängig davon vergeben, ob die Datei da ist – sonst passt er beim Einlesen nicht zum Lied.
                        val entryName = if (names.add(track.fileName)) track.fileName else "${track.id}__${track.fileName}"
                        if (file.exists()) {
                            zip.putNextEntry(ZipEntry(MUSIC + entryName).apply { method = ZipEntry.DEFLATED })
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                        val art = library.storage.artworkFor(track.id)
                        if (art.exists()) {
                            zip.putNextEntry(ZipEntry(ARTWORK + track.id + ".jpg"))
                            art.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                    onProgress(state.tracks.size, state.tracks.size)
                }
            }
        }

    /** Liest eine Sicherung und führt sie mit der Mediathek zusammen. */
    suspend fun restore(input: InputStream): Library.RestoreResult {
        val dir = library.storage.newIncomingDir()
        try {
            var state: LibraryState? = null
            val music = mutableMapOf<String, File>()
            val artwork = mutableMapOf<String, File>()
            withContext(Dispatchers.IO) {
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        when {
                            entry.isDirectory -> Unit
                            name == INDEX -> state = json.decodeFromString(LibraryState.serializer(), zip.readBytes().decodeToString())
                            name.startsWith(MUSIC) -> {
                                // Nur reine Dateinamen (kein „../“) annehmen
                                val fileName = FileNames.sanitize(name.removePrefix(MUSIC))
                                if (fileName.isNotEmpty() && !fileName.contains('/')) {
                                    val f = File(dir, "m-" + music.size + "-" + fileName)
                                    f.outputStream().use { zip.copyTo(it) }
                                    music[name.removePrefix(MUSIC)] = f
                                }
                            }
                            name.startsWith(ARTWORK) && name.endsWith(".jpg") -> {
                                val id = name.removePrefix(ARTWORK).removeSuffix(".jpg")
                                if (id.matches(Regex("[A-Za-z0-9-]+"))) {
                                    val f = File(dir, "a-$id.jpg")
                                    f.outputStream().use { zip.copyTo(it) }
                                    artwork[id] = f
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            val backup = state ?: throw Library.LibraryException("Das ist keine Sicherung von ILLEGAL FREEDOM MUSIC.")
            return library.restore(backup, music, artwork)
        } finally {
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
        }
    }

    /** Ergebnis von „Lieder importieren“ */
    data class SongImportResult(val staged: Int, val alreadyThere: Int, val failed: Int)

    /**
     * „Lieder importieren“: liest eine Sicherung mit Musik (auch vom iPhone) und legt ihre Lieder im Ordner
     * „Importierte Lieder“ ab – nicht in der Titel-Liste. Lieder, die schon da sind, werden übersprungen.
     * Playlists, Herzen und Statistik der Sicherung bleiben unberührt (dafür gibt es „Sicherung wiederherstellen“).
     * Die Datei wird einmal der Reihe nach gelesen; jedes Lied geht direkt in den Ordner (keine zweite Kopie).
     */
    suspend fun importSongs(input: InputStream, onProgress: (Int, Int) -> Unit = { _, _ -> }): SongImportResult =
        withContext(Dispatchers.IO) {
            library.songImportRunning = true
            try {
                var state: LibraryState? = null
                var byEntry: Map<String, Track> = emptyMap()
                val index = SongIndex(library.tracks + library.imported)
                // Kennung in der Sicherung → Kennung des importierten Liedes (für die Cover)
                val newIds = HashMap<String, String>()
                var staged = 0
                var alreadyThere = 0
                var failed = 0
                var seen = 0
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        when {
                            entry.isDirectory -> Unit
                            name == INDEX -> runCatching {
                                json.decodeFromString(LibraryState.serializer(), zip.readBytes().decodeToString())
                            }.getOrNull()?.let { decoded ->
                                state = decoded
                                byEntry = entryNames(decoded.tracks)
                            }
                            name.startsWith(MUSIC) -> {
                                seen++
                                val plain = name.removePrefix(MUSIC)
                                val saved = byEntry[plain]
                                // Auf dem Handy der echte Dateiname, nicht „<Kennung>__Name.mp3“
                                val fileName = FileNames.sanitize(saved?.fileName ?: plain)
                                when {
                                    // Nur reine Dateinamen (kein „../“) und nur Musik annehmen
                                    fileName.isEmpty() || plain.contains('/') || plain.contains('\\') || !FileNames.isAudio(fileName) -> failed++
                                    saved != null && index.contains(saved) -> alreadyThere++
                                    else -> when (val track = stage(zip, fileName, saved, index)) {
                                        null -> failed++
                                        else -> if (saved == null && index.contains(track)) {
                                            // Ohne Angaben in der Sicherung zeigt erst die Datei selbst, dass das Lied schon da ist
                                            library.importedFile(track).delete()
                                            library.storage.artworkFor(track.id).delete()
                                            alreadyThere++
                                        } else {
                                            library._state.update { s -> s.copy(imported = s.imported + track) }
                                            library.save()
                                            index.add(track)
                                            if (saved != null) newIds[saved.id] = track.id
                                            staged++
                                        }
                                    }
                                }
                                onProgress(seen, maxOf(seen, state?.tracks?.size ?: 0))
                            }
                            name.startsWith(ARTWORK) && name.endsWith(".jpg") -> {
                                // Cover aus der Sicherung (z. B. selbst gewählte) für eben importierte Lieder übernehmen
                                newIds[name.removePrefix(ARTWORK).removeSuffix(".jpg")]?.let { id ->
                                    runCatching { library.storage.artworkFor(id).outputStream().use { zip.copyTo(it) } }
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
                if (seen == 0) {
                    throw Library.LibraryException(
                        if (state == null) "Das ist keine Sicherung von ILLEGAL FREEDOM MUSIC."
                        else "Diese Sicherung enthält keine Lieder. Erstelle sie auf dem alten Handy mit „Alle Lieder exportieren“.")
                }
                library.coverVersion.value++
                SongImportResult(staged, alreadyThere, failed)
            } finally {
                library.songImportRunning = false
            }
        }

    /**
     * Welcher Eintrag „music/…“ gehört zu welchem Lied? Haben zwei Lieder denselben Dateinamen, steht beim
     * zweiten die Kennung davor („<Kennung>__Name.mp3“).
     */
    private fun entryNames(tracks: List<Track>): Map<String, Track> {
        val map = HashMap<String, Track>()
        for (track in tracks) {
            map[track.id + "__" + track.fileName] = track
            if (track.fileName !in map) map[track.fileName] = track
        }
        return map
    }

    /** Entpackt den aktuellen Eintrag in den Ordner „Importierte Lieder“ und liefert das Lied dazu (null = nicht lesbar) */
    private fun stage(zip: ZipInputStream, fileName: String, saved: Track?, index: SongIndex): Track? {
        val destination = library.storage.uniqueImportedDestination(fileName)
        try {
            destination.outputStream().use { zip.copyTo(it) }
        } catch (e: Exception) {
            destination.delete()
            return null
        }
        if (destination.length() == 0L) {
            destination.delete()
            return null
        }
        // Kennung aus der Sicherung behalten, solange sie hier noch frei ist
        // (nur Kennungen aus Buchstaben, Ziffern und Bindestrichen – sie wird Teil des Cover-Dateinamens)
        val id = saved?.id?.takeIf { it.matches(Regex("[A-Za-z0-9-]+")) && !index.hasId(it) } ?: java.util.UUID.randomUUID().toString()
        val made = library.makeTrack(destination, id)
        if (saved == null) return made
        // Angaben aus der Sicherung gehen vor (dort können sie von Hand geändert worden sein)
        return made.copy(
            title = saved.title.ifBlank { made.title },
            artist = saved.artist.ifBlank { made.artist },
            album = saved.album.ifBlank { made.album },
            genre = saved.genre.ifBlank { made.genre },
            durationMs = if (saved.durationMs > 0) saved.durationMs else made.durationMs,
            addedAt = if (saved.addedAt > 0) saved.addedAt else made.addedAt,
            source = saved.source,
        )
    }

    companion object {
        const val INDEX = "library.json"
        const val MUSIC = "music/"
        const val ARTWORK = "artwork/"
    }
}
