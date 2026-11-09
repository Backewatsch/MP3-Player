package com.backewatsch.mp3player

import android.Manifest
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.backewatsch.mp3player.data.DeviceMusicImporter
import com.backewatsch.mp3player.data.DownloadWatcher
import com.backewatsch.mp3player.data.Library
import com.backewatsch.mp3player.data.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Neue MP3 im Download-Ordner (wie von Chrome gespeichert) → landet automatisch in der Mediathek */
@RunWith(AndroidJUnit4::class)
class DownloadWatcherTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_AUDIO)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val app get() = context.applicationContext as App
    private lateinit var root: File
    private lateinit var library: Library
    private lateinit var watcher: DownloadWatcher
    private val inserted = mutableListOf<Uri>()
    private val prefsName = "downloadWatcherTest-" + UUID.randomUUID().toString().take(6)
    private val songName = "Chrome-Download-${UUID.randomUUID().toString().take(6)}.mp3"

    @Before fun setUp() {
        // Den Wächter der echten App anhalten, damit er das Testlied nicht auch übernimmt
        app.downloadWatcher.setEnabled(false)
        root = File(context.cacheDir, "watch-" + UUID.randomUUID()).apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        library = Library(Storage(context, root), scope, autoConvert = false)
        watcher = DownloadWatcher(context, DeviceMusicImporter(context, library), scope, prefsName)
        runBlocking { watcher.check() } // legt den Startzeitpunkt fest
    }

    @After fun tearDown() {
        inserted.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        context.deleteSharedPreferences(prefsName)
        root.deleteRecursively()
        app.downloadWatcher.setEnabled(true)
    }

    /** Speichert die Datei so, wie ein Browser es tut: über MediaStore.Downloads in „Download“ */
    private fun downloadLikeBrowser(folder: String = "Download") {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, songName)
            put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Downloads.RELATIVE_PATH, folder)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        inserted += uri
        resolver.openOutputStream(uri)!!.use { out -> testContext.assets.open("tagged.mp3").use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    }

    private fun checkUntilFound(): Int = runBlocking {
        var found = watcher.check()
        repeat(20) { if (found == 0) { Thread.sleep(500); found = watcher.check() } }
        found
    }

    @Test fun newDownloadAppearsInLibraryOnlyOnce() {
        downloadLikeBrowser()
        assertEquals("neues Lied wird übernommen", 1, checkUntilFound())
        val track = library.tracks.single()
        assertEquals("Testlied", track.title)
        assertTrue("liegt auch in „Heruntergeladen“", library.playlists.any { it.trackIds.contains(track.id) })

        // Nochmal nachsehen → nicht doppelt
        assertEquals(0, runBlocking { watcher.check() })
        assertEquals(1, library.tracks.size)

        // In der App gelöscht → kommt nicht wieder
        library.delete(track)
        assertEquals(0, runBlocking { watcher.check() })
        assertEquals(0, library.tracks.size)
    }

    @Test fun switchedOffImportsNothing() {
        watcher.setEnabled(false)
        downloadLikeBrowser()
        Thread.sleep(2_000)
        assertEquals(0, runBlocking { watcher.check() })
        assertEquals(0, library.tracks.size)
    }
}
