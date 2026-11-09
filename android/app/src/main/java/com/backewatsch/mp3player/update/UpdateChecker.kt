package com.backewatsch.mp3player.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Sucht über GitHub-Releases nach neuen Versionen und installiert sie (Android fragt zur Bestätigung). */
class UpdateChecker(private val context: Context, val currentVersion: String, val currentBuild: Int) {
    data class Release(val version: String, val build: Int, val notes: String, val apkUrl: String?, val pageUrl: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data object NoReleases : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var lastCheck = 0L
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** Beim Öffnen der App: höchstens alle 10 Minuten bei GitHub nachsehen */
    suspend fun checkIfDue() {
        if (System.currentTimeMillis() - lastCheck > 10 * 60_000L || _state.value == State.Idle) check()
    }

    /** Soll für diese Version das Update-Fenster erscheinen? (nach „Später“ nicht mehr) */
    fun shouldPrompt(release: Release) = prefs.getInt("dismissedBuild", 0) < release.build

    fun dismiss(release: Release) {
        prefs.edit().putInt("dismissedBuild", release.build).apply()
    }

    suspend fun check() {
        if (_state.value == State.Checking) return
        _state.value = State.Checking
        lastCheck = System.currentTimeMillis()
        _state.value = withContext(Dispatchers.IO) {
            try {
                val c = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Accept", "application/vnd.github+json")
                    connectTimeout = 15_000; readTimeout = 15_000
                }
                when (val code = c.responseCode) {
                    404 -> State.NoReleases
                    in 200..299 -> {
                        val release = parseRelease(c.inputStream.bufferedReader().readText())
                        when {
                            release == null -> State.Failed("Die Release-Daten konnten nicht gelesen werden.")
                            release.build > currentBuild -> State.Available(release)
                            else -> State.UpToDate
                        }
                    }
                    else -> State.Failed("GitHub antwortet mit Fehler $code.")
                }
            } catch (e: java.io.IOException) {
                State.Failed("Offline – Update-Suche nicht möglich.")
            } catch (e: Exception) {
                State.Failed(e.message ?: "Keine Verbindung.")
            }
        }
    }

    // region Installieren

    /** Ablauf einer Installation (getrennt von der Update-Suche, damit nichts verschwindet) */
    sealed interface Install {
        data object Idle : Install
        /** Android muss erst erlauben, dass diese App Apps installieren darf */
        data class NeedsPermission(val release: Release) : Install
        data class Downloading(val release: Release, val progress: Float) : Install
        data class Installing(val release: Release) : Install
        data class Failed(val release: Release, val message: String) : Install
    }

    private val _install = MutableStateFlow<Install>(Install.Idle)
    val install: StateFlow<Install> = _install.asStateFlow()

    /** Darf diese App Updates installieren („Unbekannte Apps installieren“)? */
    fun canInstall(): Boolean =
        android.os.Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Öffnet die Android-Einstellung „Unbekannte Apps installieren“ für diese App */
    fun openInstallPermissionSettings() {
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Zurück in der App: Erlaubnis inzwischen erteilt → Installation automatisch fortsetzen */
    suspend fun resumeAfterPermission() {
        val pending = _install.value as? Install.NeedsPermission ?: return
        if (canInstall()) install(pending.release)
    }

    fun clearInstallState() { _install.value = Install.Idle }

    /** Lädt die neue APK, prüft sie und installiert sie über den Paket-Installer von Android. */
    suspend fun install(release: Release) {
        val apkUrl = release.apkUrl ?: run {
            _install.value = Install.Failed(release, "In dieser Version fehlt die Android-Datei.")
            return
        }
        if (!canInstall()) {
            _install.value = Install.NeedsPermission(release)
            return
        }
        _install.value = Install.Downloading(release, 0f)
        val file = File(context.cacheDir, "update.apk")
        try {
            withContext(Dispatchers.IO) { download(apkUrl, file) { p -> _install.value = Install.Downloading(release, p) } }
        } catch (e: Exception) {
            file.delete()
            _install.value = Install.Failed(release, e.message ?: "Download fehlgeschlagen.")
            return
        }
        _install.value = Install.Installing(release)
        try {
            withContext(Dispatchers.IO) { InstallResultReceiver.startSession(context, file) }
            // Ergebnis kommt über InstallResultReceiver (Bestätigung durch den Nutzer, Erfolg oder Fehler)
        } catch (e: Exception) {
            _install.value = Install.Failed(release, "Installation konnte nicht gestartet werden: ${e.message}")
        }
    }

    /** Rückmeldung des Paket-Installers */
    fun onInstallResult(status: Int, message: String?) {
        val release = when (val s = _install.value) {
            is Install.Installing -> s.release
            is Install.Downloading -> s.release
            is Install.Failed -> s.release
            is Install.NeedsPermission -> s.release
            Install.Idle -> null
        } ?: return
        _install.value = if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) Install.Idle
        else Install.Failed(release, installErrorText(status, message))
    }

    /**
     * Lädt mit bis zu 3 Versuchen und prüft, dass wirklich eine APK angekommen ist
     * (GitHub liefert bei Störungen manchmal eine Fehlerseite statt der Datei).
     */
    fun download(url: String, target: File, onProgress: (Float) -> Unit = {}) {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 20_000; readTimeout = 30_000
                }
                val code = c.responseCode
                if (code !in 200..299) throw java.io.IOException("Server antwortet mit Fehler $code")
                val total = c.contentLengthLong
                var done = 0L
                c.inputStream.use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
                if (total > 0 && done != total) throw java.io.IOException("Download unvollständig ($done von $total Bytes)")
                if (!isApk(target)) throw java.io.IOException("Die geladene Datei ist keine gültige Android-App")
                return
            } catch (e: Exception) {
                lastError = e
                if (attempt < 2) Thread.sleep(1_500L * (attempt + 1))
            }
        }
        val reason = lastError?.message ?: "unbekannter Fehler"
        throw java.io.IOException(
            if (lastError is java.net.UnknownHostException) "Keine Internetverbindung – Update kann nicht geladen werden."
            else "Update-Download fehlgeschlagen: $reason"
        )
    }

    // endregion

    companion object {
        const val REPOSITORY = "Backewatsch/MP3-Player"
        const val API_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
        const val RELEASES_PAGE = "https://github.com/$REPOSITORY/releases/latest"

        /** APK = ZIP-Datei mit AndroidManifest.xml */
        fun isApk(file: File): Boolean = try {
            file.length() > 1024 && java.util.zip.ZipFile(file).use { it.getEntry("AndroidManifest.xml") != null }
        } catch (e: Exception) {
            false
        }

        /** Verständliche Fehlermeldung zum Ergebnis des Paket-Installers */
        fun installErrorText(status: Int, message: String?): String {
            val detail = message?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            return when (status) {
                android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation abgebrochen."
                android.content.pm.PackageInstaller.STATUS_FAILURE_BLOCKED ->
                    "Android hat die Installation blockiert. Bei Samsung: Einstellungen › Sicherheit und Datenschutz › Auto Blocker ausschalten, dann nochmal versuchen.$detail"
                android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT ->
                    "Konflikt mit der installierten Version (andere Signatur). Erstelle eine Sicherung, deinstalliere die App einmal und installiere die neue Version.$detail"
                android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Die neue Version passt nicht zu diesem Handy.$detail"
                android.content.pm.PackageInstaller.STATUS_FAILURE_INVALID -> "Die Update-Datei ist beschädigt – bitte nochmal versuchen.$detail"
                android.content.pm.PackageInstaller.STATUS_FAILURE_STORAGE -> "Zu wenig Speicherplatz für das Update.$detail"
                else -> "Installation fehlgeschlagen$detail."
            }
        }

        /** `v1.0.42` → 42 */
        fun buildNumber(tag: String): Int? = tag.substringAfterLast('.').toIntOrNull()

        fun parseRelease(json: String): Release? = try {
            val o = Json.parseToJsonElement(json).jsonObject
            val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull
            val build = tag?.let(::buildNumber)
            if (tag == null || build == null) null else {
                val apk = o["assets"]?.jsonArray
                    ?.map { it.jsonObject }
                    ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", ignoreCase = true) == true }
                    ?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
                Release(
                    version = tag.removePrefix("v"),
                    build = build,
                    notes = o["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    apkUrl = apk,
                    pageUrl = o["html_url"]?.jsonPrimitive?.contentOrNull ?: RELEASES_PAGE,
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
