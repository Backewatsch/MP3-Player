package com.backewatsch.mp3player.data

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.UUID

/**
 * Speicherorte der App. Musik liegt in Android/data/<App>/files/Music (per USB am PC erreichbar).
 * @param testRoot eigener Ordner für automatische Tests
 */
class Storage(context: Context, testRoot: File? = null) {
    private val appContext = context.applicationContext

    /** Andere Apps (Samsung Music, „Eigene Dateien“) über eine geänderte oder gelöschte Datei informieren */
    fun notifyMediaChanged(path: String) {
        runCatching { android.media.MediaScannerConnection.scanFile(appContext, arrayOf(path), null, null) }
    }

    /** Darf die App Musikdateien außerhalb ihres Ordners lesen (für verknüpfte Lieder)? */
    fun canReadSharedMusic(): Boolean {
        val permission = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
            else android.Manifest.permission.READ_EXTERNAL_STORAGE
        return androidx.core.content.ContextCompat.checkSelfPermission(appContext, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    val musicDir: File = (testRoot?.let { File(it, "Music") }
        ?: context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "Music")).apply { mkdirs() }
    val artworkDir: File = File(testRoot ?: context.filesDir, "artwork").apply { mkdirs() }
    val indexFile: File = File(testRoot ?: context.filesDir, "library.json")
    val incomingRoot: File = File(testRoot ?: context.cacheDir, "incoming")

    fun newIncomingDir(): File = File(incomingRoot, UUID.randomUUID().toString()).apply { mkdirs() }

    fun fileFor(fileName: String) = File(musicDir, fileName)
    fun artworkFor(trackId: String) = File(artworkDir, "$trackId.jpg")
    /** Gesichertes Original-Cover aus der MP3 (bevor ein eigenes Cover hineingeschrieben wurde) */
    fun originalCoverFor(trackId: String) = File(artworkDir, "$trackId.original")

    fun uniqueDestination(name: String): File {
        var candidate = File(musicDir, name)
        val base = FileNames.baseName(name)
        val ext = FileNames.extension(name)
        var n = 2
        while (candidate.exists()) {
            candidate = File(musicDir, "$base ($n).$ext")
            n++
        }
        return candidate
    }

    /** Nur für automatische Tests */
    fun resetAll() {
        musicDir.listFiles()?.forEach { it.deleteRecursively() }
        artworkDir.listFiles()?.forEach { it.delete() }
        indexFile.delete()
    }
}
