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
        data class Downloading(val progress: Float) : State
        data object NoReleases : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var lastCheck = 0L

    suspend fun checkIfDue() {
        if (System.currentTimeMillis() - lastCheck > 6 * 3600_000L || _state.value == State.Idle) check()
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
            } catch (e: Exception) {
                State.Failed(e.message ?: "Keine Verbindung.")
            }
        }
    }

    /** Lädt die neue APK und öffnet den Android-Installationsdialog. */
    suspend fun install(release: Release) {
        val apkUrl = release.apkUrl ?: return
        try {
            val file = withContext(Dispatchers.IO) {
                val target = File(context.cacheDir, "update.apk")
                val c = URL(apkUrl).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                val total = c.contentLengthLong
                c.inputStream.use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) _state.value = State.Downloading(done.toFloat() / total)
                        }
                    }
                }
                target
            }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            _state.value = State.Available(release)
        } catch (e: Exception) {
            _state.value = State.Failed("Update-Download fehlgeschlagen: ${e.message}")
        }
    }

    companion object {
        const val REPOSITORY = "Backewatsch/MP3-Player"
        const val API_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
        const val RELEASES_PAGE = "https://github.com/$REPOSITORY/releases/latest"

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
