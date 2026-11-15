package com.backewatsch.mp3player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.backewatsch.mp3player.data.Backup
import com.backewatsch.mp3player.data.Library
import com.backewatsch.mp3player.data.Storage
import com.backewatsch.mp3player.data.adoptImported
import com.backewatsch.mp3player.data.deleteImported
import com.backewatsch.mp3player.data.importedFile
import com.backewatsch.mp3player.data.recordPlay
import com.backewatsch.mp3player.data.syncImportedFolder
import com.backewatsch.mp3player.model.LibraryState
import com.backewatsch.mp3player.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * „Importierte Lieder“: Lieder von einem anderen Handy landen in einem eigenen Ordner und kommen erst
 * nach eigener Auswahl in die Titel-Liste. Läuft auf dem Gerät/Emulator.
 */
@RunWith(AndroidJUnit4::class)
class ImportedSongsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private lateinit var root: File
    private lateinit var oldPhone: Library
    private lateinit var newPhone: Library

    @Before fun setUp() {
        root = File(context.cacheDir, "imported-" + UUID.randomUUID()).apply { mkdirs() }
        oldPhone = library("alt")
        newPhone = library("neu")
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun library(name: String) = Library(
        Storage(context, File(root, name).apply { mkdirs() }),
        CoroutineScope(SupervisorJob() + Dispatchers.Default), autoConvert = false,
    )

    private fun asset(name: String, as_: String = name): File {
        val dir = File(root, "src-" + UUID.randomUUID()).apply { mkdirs() }
        val file = File(dir, as_)
        testContext.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    /** „Alle Lieder exportieren“ auf dem alten Handy */
    private fun export(library: Library): ByteArray =
        ByteArrayOutputStream().also { runBlocking { Backup(library).export(it, includeMusic = true) } }.toByteArray()

    private fun importInto(library: Library, bytes: ByteArray) =
        runBlocking { Backup(library).importSongs(ByteArrayInputStream(bytes)) }

    /** Eintrag ohne Kompression mit Größe und Prüfsumme vorab – so schreibt die iPhone-App */
    private fun stored(zip: ZipOutputStream, name: String, data: ByteArray) {
        val sum = CRC32().apply { update(data) }
        zip.putNextEntry(ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            compressedSize = data.size.toLong()
            crc = sum.value
        })
        zip.write(data)
        zip.closeEntry()
    }

    @Test fun songsLandInTheirOwnFolderAndOnlyChosenOnesJoinTheLibrary() = runBlocking<Unit> {
        val a = oldPhone.import(asset("tagged.mp3", "Sommer.mp3"), move = true)
        val b = oldPhone.import(asset("untagged.mp3", "Winter.mp3"), move = true)
        val bytes = export(oldPhone)

        assertEquals(Backup.SongImportResult(2, 0, 0), importInto(newPhone, bytes))
        assertTrue("Titel-Liste bleibt leer", newPhone.tracks.isEmpty())
        assertEquals(setOf(a.title, b.title), newPhone.imported.map { it.title }.toSet())
        assertEquals(setOf(a.id, b.id), newPhone.imported.map { it.id }.toSet())
        assertTrue(newPhone.imported.all {
            newPhone.importedFile(it).exists() && newPhone.importedFile(it).parentFile?.name == "Importierte Lieder"
        })
        assertTrue("im Musikordner selbst liegt nichts", newPhone.storage.musicDir.listFiles().orEmpty().none { it.isFile })

        // Noch einmal importieren: nichts kommt doppelt
        assertEquals(Backup.SongImportResult(0, 2, 0), importInto(newPhone, bytes))
        assertEquals(2, newPhone.imported.size)

        // Der Abgleich des Musikordners lässt importierte Lieder in Ruhe
        newPhone.rescan()
        assertTrue(newPhone.tracks.isEmpty())
        assertEquals(2, newPhone.imported.size)

        // Eins übernehmen: wandert in die Titel-Liste und in den Musikordner
        val chosen = newPhone.imported.first { it.id == a.id }
        assertEquals(1, newPhone.adoptImported(listOf(chosen.id)))
        val adopted = newPhone.tracks.single()
        assertEquals(a.id, adopted.id)
        assertEquals(a.title, adopted.title)
        assertTrue(newPhone.fileOf(adopted).exists())
        assertEquals(newPhone.storage.musicDir, newPhone.fileOf(adopted).parentFile)
        assertFalse(newPhone.importedFile(chosen).exists())
        assertEquals(listOf(b.id), newPhone.imported.map { it.id })
        newPhone.rescan()
        assertEquals(listOf(a.id), newPhone.tracks.map { it.id })
        assertEquals(Backup.SongImportResult(0, 2, 0), importInto(newPhone, bytes))

        // Das andere löschen: Eintrag und Datei weg, die Titel-Liste bleibt
        val other = newPhone.imported.single()
        assertEquals(1, newPhone.deleteImported(listOf(other.id)))
        assertTrue(newPhone.imported.isEmpty())
        assertFalse(newPhone.importedFile(other).exists())
        assertEquals(1, newPhone.tracks.size)
    }

    @Test fun folderIsKeptInSyncWithTheList() = runBlocking<Unit> {
        oldPhone.import(asset("untagged.mp3", "Eins.mp3"), move = true)
        importInto(newPhone, export(oldPhone))
        val staged = newPhone.imported.single()

        // Von Hand (z. B. per USB) in den Ordner kopiert → erscheint in der Liste
        asset("tagged.mp3", "Kopiert.mp3").copyTo(File(newPhone.storage.importedDir, "Kopiert.mp3"))
        newPhone.syncImportedFolder()
        assertEquals(setOf(staged.fileName, "Kopiert.mp3"), newPhone.imported.map { it.fileName }.toSet())

        // Datei von Hand gelöscht → der Eintrag verschwindet
        assertTrue(newPhone.importedFile(staged).delete())
        newPhone.syncImportedFolder()
        assertEquals(listOf("Kopiert.mp3"), newPhone.imported.map { it.fileName })
        assertTrue(newPhone.tracks.isEmpty())

        // Übernehmen eines Liedes, dessen Datei fehlt: Eintrag weg, nichts übernommen
        val copied = newPhone.imported.single()
        assertTrue(newPhone.importedFile(copied).delete())
        assertEquals(0, newPhone.adoptImported(listOf(copied.id)))
        assertTrue(newPhone.imported.isEmpty())
        assertTrue(newPhone.tracks.isEmpty())
    }

    @Test fun importedSongsSurviveARestart() = runBlocking<Unit> {
        oldPhone.import(asset("untagged.mp3", "Eins.mp3"), move = true)
        importInto(newPhone, export(oldPhone))
        newPhone.flush()
        val restarted = Library(newPhone.storage, CoroutineScope(SupervisorJob() + Dispatchers.Default), autoConvert = false)
        assertEquals(newPhone.imported, restarted.imported)
        assertEquals(1, restarted.imported.size)
        assertTrue(restarted.tracks.isEmpty())
    }

    @Test fun backupsNeverContainImportedSongs() = runBlocking<Unit> {
        oldPhone.import(asset("untagged.mp3", "Eins.mp3"), move = true)
        importInto(newPhone, export(oldPhone))
        val own = newPhone.import(asset("tagged.mp3", "Eigen.mp3"), move = true)

        val names = mutableListOf<String>()
        var json = ""
        ZipInputStream(ByteArrayInputStream(export(newPhone))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                if (entry.name == "library.json") json = zip.readBytes().decodeToString()
            }
        }
        assertEquals(listOf("music/${own.fileName}"), names.filter { it.startsWith("music/") })
        val state = Json { ignoreUnknownKeys = true }.decodeFromString(LibraryState.serializer(), json)
        assertTrue(state.imported.isEmpty())
        assertEquals(listOf(own.id), state.tracks.map { it.id })
        assertEquals(1, newPhone.imported.size)
    }

    @Test fun archiveFromTheIphoneIsAccepted() = runBlocking<Unit> {
        val song = asset("untagged.mp3").readBytes()
        val id = "7b1d6c1e-5a0f-4d6e-9c1a-0c3b5e2f1a02"
        // So schreibt die iPhone-App ihre library.json: ohne „source“ und „path“
        val json = """{"version":4,"tracks":[{"id":"$id","fileName":"Über den Wolken.mp3","title":"Über den Wolken",""" +
            """"artist":"Reinhard","album":"","durationMs":2040,"addedAt":1762700100000,"genre":"Pop"}],""" +
            """"playlists":[],"pinned":[],"favorites":["$id"],"genresScanned":true}"""
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                stored(zip, "library.json", json.toByteArray())
                stored(zip, "music/Über den Wolken.mp3", song)
                stored(zip, "artwork/$id.jpg", byteArrayOf(1, 2, 3))
            }
        }.toByteArray()

        assertEquals(Backup.SongImportResult(1, 0, 0), importInto(newPhone, bytes))
        val staged = newPhone.imported.single()
        assertEquals(id, staged.id)
        assertEquals("Über den Wolken", staged.title)
        assertEquals("Reinhard", staged.artist)
        assertEquals("Pop", staged.genre)
        assertEquals(2040L, staged.durationMs)
        assertEquals(1762700100000L, staged.addedAt)
        assertArrayEquals(song, newPhone.importedFile(staged).readBytes())
        assertArrayEquals("Cover aus der Datei übernommen", byteArrayOf(1, 2, 3), newPhone.storage.artworkFor(id).readBytes())
        assertTrue("Herzen kommen auf diesem Weg nicht mit", newPhone.state.value.favorites.isEmpty())
        assertTrue(newPhone.tracks.isEmpty())
    }

    @Test fun twoSongsWithTheSameFileNameAreBothImported() = runBlocking<Unit> {
        val first = asset("tagged.mp3").readBytes()
        val second = asset("untagged.mp3").readBytes()
        val idA = UUID.randomUUID().toString()
        val idB = UUID.randomUUID().toString()
        // Zwei Lieder aus verschiedenen Ordnern mit demselben Dateinamen: Das zweite steht mit Kennung im Archiv
        val state = LibraryState(tracks = listOf(
            Track(id = idA, fileName = "Intro.mp3", title = "Intro A", artist = "Band A", durationMs = 3_000),
            Track(id = idB, fileName = "Intro.mp3", title = "Intro B", artist = "Band B", durationMs = 2_000),
        ))
        val json = Json { encodeDefaults = true }.encodeToString(LibraryState.serializer(), state)
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("library.json")); zip.write(json.toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("music/Intro.mp3")); zip.write(first); zip.closeEntry()
                zip.putNextEntry(ZipEntry("music/${idB}__Intro.mp3")); zip.write(second); zip.closeEntry()
            }
        }.toByteArray()

        assertEquals(Backup.SongImportResult(2, 0, 0), importInto(newPhone, bytes))
        val a = newPhone.imported.single { it.id == idA }
        val b = newPhone.imported.single { it.id == idB }
        assertEquals("Intro A", a.title)
        assertEquals("Intro B", b.title)
        assertNotEquals(a.fileName, b.fileName)
        assertArrayEquals(first, newPhone.importedFile(a).readBytes())
        assertArrayEquals(second, newPhone.importedFile(b).readBytes())

        // Beide übernehmen: Die Titel-Liste bekommt zwei verschiedene Dateien
        assertEquals(2, newPhone.adoptImported(listOf(idA, idB)))
        assertEquals(2, newPhone.tracks.map { it.fileName }.toSet().size)
        assertArrayEquals(second, newPhone.fileOf(newPhone.track(idB)!!).readBytes())
    }

    @Test fun playlistsAndHeartsFollowTheChosenSongs() = runBlocking<Unit> {
        val a = oldPhone.import(asset("tagged.mp3", "Sommer.mp3"), move = true)
        val b = oldPhone.import(asset("untagged.mp3", "Winter.mp3"), move = true)
        val mix = oldPhone.createPlaylist("Mix")
        oldPhone.add(a, mix); oldPhone.add(b, mix)
        val onlyWinter = oldPhone.createPlaylist("Nur Winter")
        oldPhone.add(b, onlyWinter)
        oldPhone.toggleFavorite(a); oldPhone.toggleFavorite(b)
        oldPhone.recordPlay(a.id)
        val bytes = export(oldPhone)

        importInto(newPhone, bytes)
        // Noch nichts übernommen: Es wird nichts angelegt
        val nothing = Backup(newPhone).restoreInfo(ByteArrayInputStream(bytes))
        assertEquals(0, nothing.tracksMatched)
        assertEquals(2, nothing.tracksMissing)
        assertTrue(newPhone.tracks.isEmpty())
        assertTrue(newPhone.playlists.none { it.name == "Mix" || it.name == "Nur Winter" })
        assertEquals(2, newPhone.imported.size)

        // Nur „Sommer“ übernehmen: Playlist und Herz kommen nur für dieses Lied
        assertEquals(1, newPhone.adoptImported(listOf(a.id)))
        val first = Backup(newPhone).restoreInfo(ByteArrayInputStream(bytes))
        assertEquals(0, first.tracksAdded)
        assertEquals(1, first.tracksMatched)
        assertEquals(1, first.tracksMissing)
        assertEquals(1, first.playlistsFilled)
        assertEquals(1, first.favorites)
        assertEquals(listOf(a.id), newPhone.tracks.map { it.id })
        assertEquals("das nicht ausgesuchte Lied wartet weiter", listOf(b.id), newPhone.imported.map { it.id })
        assertTrue(newPhone.importedFile(newPhone.imported.single()).exists())
        assertEquals(listOf(a.id), newPhone.playlists.single { it.name == "Mix" }.trackIds)
        assertTrue("keine leere Playlist", newPhone.playlists.none { it.name == "Nur Winter" })
        assertEquals(listOf(a.id), newPhone.state.value.favorites)
        assertEquals(1, newPhone.state.value.playCounts[a.id])

        // Später auch „Winter“ übernehmen und wiederholen: nichts doppelt, der Rest kommt dazu
        assertEquals(1, newPhone.adoptImported(listOf(b.id)))
        val second = Backup(newPhone).restoreInfo(ByteArrayInputStream(bytes))
        assertEquals(2, second.tracksMatched)
        assertEquals(0, second.tracksMissing)
        assertEquals(listOf(a.id, b.id), newPhone.playlists.single { it.name == "Mix" }.trackIds)
        assertEquals(listOf(b.id), newPhone.playlists.single { it.name == "Nur Winter" }.trackIds)
        assertEquals(setOf(a.id, b.id), newPhone.state.value.favorites.toSet())
        assertEquals(2, newPhone.state.value.favorites.size)
        assertEquals(2, newPhone.tracks.size)
        assertEquals(1, newPhone.state.value.playCounts[a.id])
    }

    @Test fun fullRestoreTakesWaitingSongsFromTheImportedFolder() = runBlocking<Unit> {
        val a = oldPhone.import(asset("tagged.mp3", "Sommer.mp3"), move = true)
        val b = oldPhone.import(asset("untagged.mp3", "Winter.mp3"), move = true)
        val mix = oldPhone.createPlaylist("Mix")
        oldPhone.add(a, mix); oldPhone.add(b, mix)
        val bytes = export(oldPhone)

        importInto(newPhone, bytes)
        assertEquals(2, newPhone.imported.size)
        // „Sicherung wiederherstellen“ mit derselben Datei: Die wartenden Lieder ziehen um, nichts liegt doppelt da
        val result = Backup(newPhone).restore(ByteArrayInputStream(bytes))
        assertEquals(2, result.fromImported)
        assertEquals(0, result.tracksAdded)
        assertEquals(0, result.tracksMatched)
        assertEquals(0, result.tracksMissing)
        assertEquals(setOf(a.id, b.id), newPhone.tracks.map { it.id }.toSet())
        assertEquals(2, newPhone.tracks.size)
        assertTrue(newPhone.imported.isEmpty())
        assertTrue(newPhone.storage.importedDir.listFiles().orEmpty().none { it.isFile })
        assertEquals(2, newPhone.storage.musicDir.listFiles().orEmpty().count { it.isFile })
        assertEquals(setOf(a.id, b.id), newPhone.playlists.single { it.name == "Mix" }.trackIds.toSet())
    }

    @Test fun wrongFilesGiveClearMessages() {
        val noInfo = runCatching {
            runBlocking { Backup(newPhone).restoreInfo(ByteArrayInputStream("kein Archiv".toByteArray())) }
        }.exceptionOrNull()
        assertEquals("Das ist keine Sicherung von ILLEGAL FREEDOM MUSIC.", noInfo?.message)

        val noBackup = runCatching { importInto(newPhone, "kein Archiv".toByteArray()) }.exceptionOrNull()
        assertEquals("Das ist keine Sicherung von ILLEGAL FREEDOM MUSIC.", noBackup?.message)

        runBlocking { oldPhone.import(asset("untagged.mp3", "Eins.mp3"), move = true) }
        val withoutMusic = ByteArrayOutputStream().also { runBlocking { Backup(oldPhone).export(it, includeMusic = false) } }.toByteArray()
        val error = runCatching { importInto(newPhone, withoutMusic) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().startsWith("Diese Sicherung enthält keine Lieder."))
        assertTrue(newPhone.imported.isEmpty())
    }
}
