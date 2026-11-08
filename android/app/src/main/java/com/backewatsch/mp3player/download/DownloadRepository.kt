package com.backewatsch.mp3player.download

import android.webkit.CookieManager
import android.webkit.URLUtil
import com.backewatsch.mp3player.data.FileNames
import com.backewatsch.mp3player.data.Library
import com.backewatsch.mp3player.data.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Lädt Audio-/Videodateien herunter und übernimmt sie (als MP3) in die Bibliothek. */
class DownloadRepository(
    private val library: Library,
    private val storage: Storage,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Running : State
        data object Converting : State
        data object Finished : State
        data class Failed(val message: String) : State
    }

    data class Item(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val source: String,
        val progress: Float = 0f,
        val state: State = State.Running,
        val playlistId: String? = null,
    )

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    /** Neue fertige Downloads seit dem letzten Öffnen der Liste */
    private val _unseen = MutableStateFlow(0)
    val unseen: StateFlow<Int> = _unseen.asStateFlow()

    var userAgent: String? = null

    init {
        storage.incomingRoot.deleteRecursively() // Reste vom letzten Start
    }

    fun markSeen() { _unseen.value = 0 }

    fun activeCount() = _items.value.count { it.state == State.Running || it.state == State.Converting }

    fun download(url: String, playlistId: String? = null, suggestedName: String? = null, mime: String? = null) {
        val item = Item(name = suggestedName ?: url.substringAfterLast('/').substringBefore('?').ifEmpty { url },
            source = url, playlistId = playlistId)
        _items.update { listOf(item) + it }
        scope.launch { run(item, suggestedName, mime) }
    }

    fun retry(item: Item) {
        _items.update { list -> list.filter { it.id != item.id } }
        download(item.source, item.playlistId)
    }

    fun clearFinished() {
        _items.update { list -> list.filter { it.state == State.Running || it.state == State.Converting } }
    }

    private fun update(id: String, change: (Item) -> Item) {
        _items.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private suspend fun run(item: Item, suggestedName: String?, mimeHint: String?) {
        val dir = storage.newIncomingDir()
        try {
            val file = withContext(Dispatchers.IO) { fetch(item, dir, suggestedName, mimeHint) }
            update(item.id) {
                it.copy(name = file.name, progress = 1f,
                    state = if (FileNames.isMp3(file.name)) State.Running else State.Converting)
            }
            val track = library.import(file, move = true, addToDownloads = true, extraPlaylistId = item.playlistId)
            update(item.id) { it.copy(name = track.title, state = State.Finished) }
            _unseen.value += 1
        } catch (e: java.net.UnknownHostException) {
            update(item.id) { it.copy(state = State.Failed("Keine Internetverbindung – bitte später erneut versuchen.")) }
        } catch (e: java.net.ConnectException) {
            update(item.id) { it.copy(state = State.Failed("Keine Internetverbindung – bitte später erneut versuchen.")) }
        } catch (e: Exception) {
            update(item.id) { it.copy(state = State.Failed(e.message ?: "Download fehlgeschlagen.")) }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun fetch(item: Item, dir: File, suggestedName: String?, mimeHint: String?): File {
        if (item.source.startsWith("file:")) {
            // Lokale Datei (z. B. Tests)
            val src = File(java.net.URI(item.source))
            val name = FileNames.mediaFileName(suggestedName ?: src.name, mimeHint, src.name)
                ?: throw Exception(FileNames.notMediaMessage(mimeHint))
            return File(dir, name).also { src.copyTo(it) }
        }
        var url = URL(item.source)
        var connection: HttpURLConnection
        var redirects = 0
        while (true) {
            connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
                userAgent?.let { setRequestProperty("User-Agent", it) }
                CookieManager.getInstance().getCookie(url.toString())?.let { setRequestProperty("Cookie", it) }
            }
            val code = connection.responseCode
            if (code in 300..399 && redirects < 5) {
                val location = connection.getHeaderField("Location") ?: break
                url = URL(url, location)
                redirects++
                connection.disconnect()
                continue
            }
            if (code !in 200..299) throw Exception("Server-Fehler $code.")
            break
        }
        val mime = connection.contentType ?: mimeHint
        val disposition = connection.getHeaderField("Content-Disposition")
        val guessed = suggestedName ?: if (disposition != null) URLUtil.guessFileName(url.toString(), disposition, mime) else null
        val name = FileNames.mediaFileName(guessed, mime, url.path)
            ?: run { connection.disconnect(); throw Exception(FileNames.notMediaMessage(mime)) }
        update(item.id) { it.copy(name = name) }

        val total = connection.contentLengthLong
        val target = File(dir, name)
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    done += n
                    if (total > 0 && done - lastReport > 256 * 1024) {
                        lastReport = done
                        val p = done.toFloat() / total
                        update(item.id) { it.copy(progress = p) }
                    }
                }
            }
        }
        connection.disconnect()
        return target
    }
}
