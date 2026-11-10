package com.backewatsch.mp3player.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.backewatsch.mp3player.model.Track

/**
 * Übernimmt neue Lieder aus dem Download-Ordner des Handys automatisch in die Mediathek –
 * egal mit welcher App (Chrome, Samsung Internet …) sie geladen wurden.
 * Gelöschte Lieder kommen nicht wieder: jede Datei wird nur einmal übernommen.
 */
class DownloadWatcher(
    private val context: Context,
    private val importer: DeviceMusicImporter,
    private val scope: CoroutineScope,
    prefsName: String = "downloadWatcher",
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", true))
    val enabled: StateFlow<Boolean> = _enabled

    /** Meldet neu übernommene Lieder (für einen kurzen Hinweis) */
    private val _imported = MutableSharedFlow<Track>(extraBufferCapacity = 16)
    val imported: SharedFlow<Track> = _imported.asSharedFlow()

    private val mutex = Mutex()
    private var pendingCheck: Job? = null
    private var observing = false

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = checkSoon()
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Hinweis in der Mediathek („Erlauben“) wurde weggeklickt */
    private val _hintDismissed = MutableStateFlow(prefs.getBoolean("hintDismissed", false))
    val hintDismissed: StateFlow<Boolean> = _hintDismissed
    fun dismissHint() {
        prefs.edit().putBoolean("hintDismissed", true).apply()
        _hintDismissed.value = true
    }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean("enabled", on).apply()
        _enabled.value = on
        if (on) start() else stop()
    }

    /** Beobachten starten (bei App-Start und nachdem die Erlaubnis erteilt wurde) */
    fun start() {
        if (!_enabled.value || !hasPermission()) return
        sinceSeconds()
        if (!observing) {
            runCatching {
                context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
                observing = true
            }
        }
        checkSoon(0)
    }

    /** Erst Downloads ab dem ersten Einschalten: ältere lassen sich über „Von Samsung Music übertragen“ holen */
    private fun sinceSeconds(): Long {
        if (!prefs.contains("since")) prefs.edit().putLong("since", System.currentTimeMillis() / 1000 - 60).apply()
        return prefs.getLong("since", 0)
    }

    private fun stop() {
        if (observing) runCatching { context.contentResolver.unregisterContentObserver(observer) }
        observing = false
    }

    /** Kurz warten (Browser schreibt die Datei in Teilen), dann nachsehen */
    fun checkSoon(delayMs: Long = 2_000) {
        if (!_enabled.value || !hasPermission()) return
        pendingCheck?.cancel()
        pendingCheck = scope.launch {
            delay(delayMs)
            check()
        }
    }

    /** Neue Lieder im Download-Ordner suchen und übernehmen. Gibt die Anzahl übernommener Lieder zurück. */
    suspend fun check(): Int = mutex.withLock {
        if (!_enabled.value || !hasPermission()) return 0
        val since = sinceSeconds()
        val handled = prefs.getStringSet("handled", emptySet()).orEmpty().toMutableSet()
        val candidates = withContext(Dispatchers.IO) { newDownloads(since) }.filter { it.sourceKey !in handled }
        var count = 0
        for (song in candidates) {
            // Schlägt die Übernahme fehl (Datei z. B. noch unvollständig), wird es beim nächsten Mal erneut versucht
            val result = runCatching { importer.importDownloaded(song) }
                .onFailure { android.util.Log.w("DownloadWatcher", "Übernahme fehlgeschlagen: ${song.displayName}", it) }
            if (result.isFailure) continue
            handled += song.sourceKey
            prefs.edit().putStringSet("handled", HashSet(handled)).apply()
            val track = result.getOrNull() ?: continue // war schon in der Mediathek
            count++
            _imported.tryEmit(track)
        }
        count
    }

    /** Audiodateien im Download-Ordner, die nach [sinceSeconds] hinzugekommen und fertig geschrieben sind */
    private fun newDownloads(sinceSeconds: Long): List<DeviceMusicImporter.DeviceSong> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        @Suppress("DEPRECATION")
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
        ) + (if (Build.VERSION.SDK_INT >= 29) arrayOf(MediaStore.Audio.Media.RELATIVE_PATH) else emptyArray())
        // Nur fertig geschriebene Dateien (IS_PENDING gibt es ab Android 10)
        val selection = "${MediaStore.Audio.Media.DATE_ADDED} > ?" +
            if (Build.VERSION.SDK_INT >= 29) " AND ${MediaStore.Audio.Media.IS_PENDING} = 0" else ""
        val result = mutableListOf<DeviceMusicImporter.DeviceSong>()
        context.contentResolver.query(collection, projection, selection, arrayOf(sinceSeconds.toString()),
            "${MediaStore.Audio.Media.DATE_ADDED} ASC")?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            @Suppress("DEPRECATION") val data = c.getColumnIndex(MediaStore.Audio.Media.DATA)
            val size = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val title = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artist = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val duration = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val rel = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) else -1
            while (c.moveToNext()) {
                val fileName = c.getString(name) ?: continue
                if (fileName.startsWith(".")) continue // versteckte Zwischendateien (z. B. beim Zuschneiden)
                val relative = if (rel >= 0) c.getString(rel).orEmpty() else ""
                val path = (if (data >= 0) c.getString(data) else null) ?: "media:${c.getLong(id)}:$fileName"
                if (!isInDownloads(relative, path) || !FileNames.isImportable(fileName)) continue
                if (c.getLong(size) <= 0) continue
                result += DeviceMusicImporter.DeviceSong(
                    uri = ContentUris.withAppendedId(collection, c.getLong(id)),
                    displayName = fileName,
                    path = path,
                    size = c.getLong(size),
                    title = (if (title >= 0) c.getString(title) else null).orEmpty(),
                    artist = (if (artist >= 0) c.getString(artist) else null).orEmpty(),
                    durationMs = if (duration >= 0) c.getLong(duration) else 0,
                )
            }
        }
        return result
    }

    companion object {
        /** Erlaubnis, die zum Lesen der Musikdateien nötig ist */
        val permission: String
            get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

        /** Liegt die Datei im Download-Ordner (oder einem Unterordner davon)? */
        fun isInDownloads(relativePath: String, path: String): Boolean =
            relativePath.startsWith("Download/", ignoreCase = true) || relativePath.equals("Download", ignoreCase = true) ||
                path.contains("/Download/", ignoreCase = true)
    }
}
