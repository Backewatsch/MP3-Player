package com.backewatsch.mp3player.data

import com.backewatsch.mp3player.model.LibraryState
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
            val state = library.snapshot()
            ZipOutputStream(output.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(INDEX))
                zip.write(json.encodeToString(LibraryState.serializer(), state).toByteArray())
                zip.closeEntry()
                if (includeMusic) {
                    val names = mutableSetOf<String>()
                    state.tracks.forEachIndexed { n, track ->
                        onProgress(n, state.tracks.size)
                        val file = library.fileOf(track)
                        if (file.exists() && names.add(track.fileName)) {
                            zip.putNextEntry(ZipEntry(MUSIC + track.fileName).apply { method = ZipEntry.DEFLATED })
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

    companion object {
        const val INDEX = "library.json"
        const val MUSIC = "music/"
        const val ARTWORK = "artwork/"
    }
}
