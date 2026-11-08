package com.backewatsch.mp3player

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.backewatsch.mp3player.browser.BrowserController
import com.backewatsch.mp3player.data.FileNames
import com.backewatsch.mp3player.data.Library
import com.backewatsch.mp3player.data.Storage
import com.backewatsch.mp3player.download.DownloadRepository
import com.backewatsch.mp3player.player.PlayerConnection
import com.backewatsch.mp3player.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Hält die Dienste der App (Bibliothek, Player, Downloads, Browser, Updates). */
class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var storage: Storage
    lateinit var library: Library
    lateinit var downloads: DownloadRepository
    lateinit var player: PlayerConnection
    lateinit var updates: UpdateChecker
    lateinit var deviceImporter: com.backewatsch.mp3player.data.DeviceMusicImporter
    lateinit var sound: com.backewatsch.mp3player.player.AudioEffects
    lateinit var backup: com.backewatsch.mp3player.data.Backup
    val browser: BrowserController by lazy { BrowserController(this, downloads) }

    /** Eigene Cover der Lieder statt des App-Logos anzeigen (Standard: aus → immer das Logo) */
    private val settings by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    val showOwnCovers by lazy { kotlinx.coroutines.flow.MutableStateFlow(settings.getBoolean("showOwnCovers", false)) }
    fun setShowOwnCovers(value: Boolean) {
        settings.edit().putBoolean("showOwnCovers", value).apply()
        showOwnCovers.value = value
        player.refreshArtwork()
    }

    /** Sortierung der Titelliste (bleibt gespeichert) */
    val trackSort by lazy {
        kotlinx.coroutines.flow.MutableStateFlow(com.backewatsch.mp3player.data.TrackSort.fromName(settings.getString("trackSort", null)))
    }
    fun setTrackSort(sort: com.backewatsch.mp3player.data.TrackSort) {
        settings.edit().putString("trackSort", sort.name).apply()
        trackSort.value = sort
    }

    override fun onCreate() {
        super.onCreate()
        storage = Storage(this)
        sound = com.backewatsch.mp3player.player.AudioEffects(this)
        library = Library(storage, scope)
        downloads = DownloadRepository(library, storage, scope)
        player = PlayerConnection(this, library, scope)
        updates = UpdateChecker(this, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        deviceImporter = com.backewatsch.mp3player.data.DeviceMusicImporter(this, library)
        backup = com.backewatsch.mp3player.data.Backup(library)

        // Heruntergeladene Titel kommen in die Wiedergabeliste (pausiert, wenn gerade nichts läuft)
        library.onTrackAdded = { track, playlistId -> if (playlistId != null) player.enqueue(track) }
        scope.launch { library.state.collect { player.libraryChanged(it.tracks) } }
        scope.launch { library.rescan() }
        // Widget auf dem Startbildschirm aktuell halten
        scope.launch {
            kotlinx.coroutines.flow.combine(player.state, library.state) { p, l ->
                val t = l.tracks.firstOrNull { it.id == p.currentId }
                com.backewatsch.mp3player.widget.PlayerWidget.Info(t?.title, t?.artist, p.isPlaying, t != null && t.id in l.favorites)
            }.distinctUntilChanged().collect { com.backewatsch.mp3player.widget.PlayerWidget.update(this@App, it) }
        }
    }

    /** Dateien aus „Öffnen mit …“, „Teilen“ oder der Dateiauswahl übernehmen (werden zu MP3). */
    suspend fun importUris(uris: List<Uri>): List<String> {
        val errors = mutableListOf<String>()
        for (uri in uris) {
            val dir = storage.newIncomingDir()
            try {
                val name = FileNames.sanitize(displayName(this, uri) ?: "Import")
                val file = File(dir, name)
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                        ?: throw IllegalStateException("Datei nicht lesbar.")
                }
                library.import(file, move = true)
            } catch (e: Exception) {
                errors += e.message ?: "Import fehlgeschlagen."
            } finally {
                dir.deleteRecursively()
            }
        }
        return errors
    }

    private fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }
}

val Context.app: App get() = applicationContext as App
