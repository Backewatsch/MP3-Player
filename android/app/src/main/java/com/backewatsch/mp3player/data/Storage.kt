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

    private val isTest = testRoot != null
    /** Musikordner auf dem gemeinsamen Speicher; null, wenn der beim Start der App (noch) nicht da war */
    private val externalMusicDir: File? = if (testRoot == null) context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) else null

    val musicDir: File = (testRoot?.let { File(it, "Music") }
        ?: externalMusicDir ?: File(context.filesDir, "Music")).apply { mkdirs() }

    /**
     * Ist der Speicher mit dem Musikordner gerade wirklich da? Direkt nach dem Einschalten des Handys kann die App
     * schon laufen (z. B. fürs Widget), bevor der Speicher bereit ist. Dann sähen alle Lieder „gelöscht“ aus –
     * in diesem Zustand darf nichts aus der Mediathek gestrichen werden.
     */
    fun musicStorageReady(): Boolean =
        isTest || (externalMusicDir != null && Environment.getExternalStorageState(musicDir) == Environment.MEDIA_MOUNTED)

    /** Ist der Speicher, auf dem diese Datei liegt, gerade eingebunden? (Eine herausgenommene SD-Karte ist es nicht.) */
    fun volumeMounted(file: File): Boolean =
        isTest || runCatching { Environment.getExternalStorageState(file) == Environment.MEDIA_MOUNTED }.getOrDefault(false)
    val artworkDir: File = File(testRoot ?: context.filesDir, "artwork").apply { mkdirs() }
    val indexFile: File = File(testRoot ?: context.filesDir, "library.json")
    val incomingRoot: File = File(testRoot ?: context.cacheDir, "incoming")

    fun newIncomingDir(): File = File(incomingRoot, UUID.randomUUID().toString()).apply { mkdirs() }

    fun fileFor(fileName: String) = File(musicDir, fileName)

    /**
     * Ordner „Importierte Lieder“ im Musikordner: Lieder von einem anderen Handy, die noch nicht in der Titel-Liste sind.
     * Der Abgleich des Musikordners sieht nur dessen eigene Dateien – was hier liegt, bleibt außen vor.
     */
    val importedDir: File get() = File(musicDir, IMPORTED_FOLDER).apply { mkdirs() }
    fun importedFileFor(fileName: String) = File(importedDir, fileName)

    /** Freier Dateiname im Ordner „Importierte Lieder“ */
    fun uniqueImportedDestination(name: String, taken: Set<String> = emptySet()): File {
        val dir = importedDir
        var candidate = File(dir, name)
        val base = FileNames.baseName(name)
        val ext = FileNames.extension(name)
        var n = 2
        while (candidate.exists() || candidate.name in taken) {
            candidate = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
            n++
        }
        return candidate
    }
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

    companion object {
        const val IMPORTED_FOLDER = "Importierte Lieder"
    }

    /** Nur für automatische Tests */
    fun resetAll() {
        musicDir.listFiles()?.forEach { it.deleteRecursively() }
        artworkDir.listFiles()?.forEach { it.delete() }
        indexFile.delete()
        File(indexFile.path + ".bak").delete()
        File(indexFile.path + ".unlesbar").delete()
    }
}
