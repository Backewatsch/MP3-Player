package com.backewatsch.mp3player.data

import com.backewatsch.mp3player.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

// „Importierte Lieder“: Lieder von einem anderen Handy landen zuerst in einem eigenen Ordner.
// Erst was man dort selbst aussucht, kommt in die Titel-Liste (und damit in Alben, Suche, Statistik …).

/** Schnelles Nachschlagen „ist dieses Lied schon da?“ – auch bei tausenden Liedern */
class SongIndex(tracks: List<Track> = emptyList()) {
    private val ids = HashSet<String>()
    private val sources = HashSet<String>()
    /** Titel + Interpret (klein geschrieben) → Längen der Lieder mit diesem Namen */
    private val lengths = HashMap<String, MutableList<Long>>()

    init { tracks.forEach(::add) }

    fun add(track: Track) {
        ids.add(track.id)
        if (track.source.isNotEmpty()) sources.add(track.source)
        lengths.getOrPut(key(track)) { mutableListOf() }.add(track.durationMs)
    }

    fun hasId(id: String) = id in ids

    /**
     * Dasselbe Lied? Gleiche Kennung, gleiche Herkunft oder gleicher Titel und Interpret bei (fast) gleicher Länge.
     * Der Dateiname allein zählt nicht – „Track 01.mp3“ gibt es in vielen Alben.
     */
    fun contains(track: Track): Boolean =
        track.id in ids ||
            (track.source.isNotEmpty() && track.source in sources) ||
            lengths[key(track)]?.any { kotlin.math.abs(it - track.durationMs) < 2_000 } == true

    private fun key(track: Track) = track.title.trim().lowercase() + "\u0001" + track.artist.trim().lowercase()
}

/** Datei eines importierten Liedes (im Ordner „Importierte Lieder“) */
fun Library.importedFile(track: Track): File = storage.importedFileFor(track.fileName)

/**
 * Gleicht die Liste mit dem Ordner „Importierte Lieder“ ab: Dateien ohne Eintrag (z. B. per USB hineinkopiert)
 * kommen dazu, Einträge ohne Datei verschwinden. Ruht, solange ein Import läuft oder der Speicher nicht bereit ist.
 */
suspend fun Library.syncImportedFolder() {
    if (songImportRunning || !storage.musicStorageReady()) return
    // Ordner nicht lesbar → nichts anfassen
    val files = withContext(Dispatchers.IO) {
        storage.importedDir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") && FileNames.isAudio(it.name) }
    } ?: return
    val present = files.map { it.name }.toSet()
    val known = imported.map { it.fileName }.toSet()
    if (present == known) return
    val fresh = withContext(Dispatchers.IO) { files.filter { it.name !in known }.map { makeTrack(it) } }
    // Inzwischen hat ein Import begonnen: Der trägt seine Lieder selbst ein
    if (songImportRunning) {
        fresh.forEach { storage.artworkFor(it.id).delete() }
        return
    }
    var gone = emptyList<Track>()
    var unused = emptyList<Track>()
    mutex.withLock {
        _state.update { s ->
            // Der Ordner kann sich inzwischen geändert haben (z. B. ein abgeschlossener Import):
            // nur Einträge entfernen, deren Datei jetzt wirklich fehlt
            gone = s.imported.filter { it.fileName !in present && !storage.importedFileFor(it.fileName).exists() }
            val goneIds = gone.map { it.id }.toSet()
            val add = fresh.filter { f -> s.imported.none { it.fileName == f.fileName } }
            unused = fresh - add.toSet()
            if (gone.isEmpty() && add.isEmpty()) s
            else s.copy(imported = s.imported.filter { it.id !in goneIds } + add)
        }
    }
    val libraryIds = tracks.map { it.id }.toSet()
    withContext(Dispatchers.IO) {
        (gone + unused).forEach { if (it.id !in libraryIds) storage.artworkFor(it.id).delete() }
    }
    save()
}

/**
 * Übernimmt importierte Lieder in die Titel-Liste: Die Datei wandert in den Musikordner, das Lied behält
 * Titel, Interpret und Cover. Liefert, wie viele übernommen wurden.
 */
suspend fun Library.adoptImported(ids: Collection<String>): Int {
    var adopted = 0
    for (id in ids) {
        val staged = imported.firstOrNull { it.id == id } ?: continue
        val source = importedFile(staged)
        // Namen im Musikordner vormerken, damit der Abgleich die Datei nicht gleichzeitig als „neu“ aufnimmt
        val destination = mutex.withLock { storage.uniqueDestination(staged.fileName).also { importing += it.name } }
        try {
            val moved = withContext(Dispatchers.IO) {
                when {
                    !source.exists() -> false
                    source.renameTo(destination) -> true
                    else -> {
                        source.copyTo(destination, overwrite = false)
                        source.delete()
                        true
                    }
                }
            }
            if (!moved) {
                // Datei fehlt (z. B. von Hand gelöscht) → der Eintrag hat keinen Sinn mehr
                mutex.withLock { _state.update { s -> s.copy(imported = s.imported.filter { it.id != id }) } }
                if (tracks.none { it.id == id }) storage.artworkFor(id).delete()
                continue
            }
            // Kennung schon in der Titel-Liste vergeben (z. B. durch „Sicherung wiederherstellen“) → neue Kennung
            val newId = if (tracks.any { it.id == id }) java.util.UUID.randomUUID().toString() else id
            if (newId != id) withContext(Dispatchers.IO) {
                val art = storage.artworkFor(id)
                if (art.exists()) art.copyTo(storage.artworkFor(newId), overwrite = true)
            }
            val track = staged.copy(id = newId, fileName = destination.name, path = "")
            mutex.withLock {
                _state.update { s -> s.copy(tracks = s.tracks + track, imported = s.imported.filter { it.id != id }) }
            }
            adopted++
            onTrackAdded?.invoke(track, null)
        } catch (e: Exception) {
            // z. B. Speicher voll: Das Lied bleibt bei den importierten; eine halbe Kopie wieder entfernen
            withContext(Dispatchers.IO) { if (source.exists()) destination.delete() }
        } finally {
            mutex.withLock { importing -= destination.name }
        }
    }
    save()
    return adopted
}

/** Löscht importierte Lieder samt Datei. Liefert, wie viele gelöscht wurden. */
suspend fun Library.deleteImported(ids: Collection<String>): Int {
    val chosen = ids.toSet()
    val doomed = imported.filter { it.id in chosen }
    if (doomed.isEmpty()) return 0
    val libraryIds = tracks.map { it.id }.toSet()
    withContext(Dispatchers.IO) {
        doomed.forEach {
            importedFile(it).delete()
            if (it.id !in libraryIds) storage.artworkFor(it.id).delete()
        }
    }
    mutex.withLock { _state.update { s -> s.copy(imported = s.imported.filter { it.id !in chosen }) } }
    save()
    return doomed.size
}
