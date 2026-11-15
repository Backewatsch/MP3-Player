package com.backewatsch.mp3player.player

import com.backewatsch.mp3player.data.*

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.backewatsch.mp3player.data.Library
import com.backewatsch.mp3player.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Verbindung der Oberfläche zum Wiedergabedienst (Wiedergabeliste, Steuerung, Zustand). */
class PlayerConnection(
    private val context: Context,
    private val library: Library,
    scope: CoroutineScope,
) {
    data class UiState(
        val currentId: String? = null,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val queueIds: List<String> = emptyList(),
        val currentIndex: Int = -1,
        val shuffle: Boolean = false,
        val repeatMode: Int = Player.REPEAT_MODE_OFF,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Schlafmodus: Zeitpunkt (SystemClock.elapsedRealtime), zu dem die Musik ausgeht; null = aus */
    private val _sleepEndAt = MutableStateFlow<Long?>(null)
    val sleepEndAt: StateFlow<Long?> = _sleepEndAt.asStateFlow()

    /** Hörzeit für die Statistik, wird gesammelt und alle 30 s gespeichert */
    private var listenedPending = 0L
    private var lastTick = 0L
    private val playbackPrefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    /** Stelle im Lied: eigene kleine Datei, damit beim Hören nicht alle 5 s die ganze Wiedergabeliste neu geschrieben wird */
    private val positionPrefs = context.getSharedPreferences("playbackPosition", Context.MODE_PRIVATE)
    private var saveTicks = 0
    /** Zuletzt gespeicherter Stand der Wiedergabeliste – geschrieben wird nur, wenn sich etwas geändert hat */
    private var savedIds: List<String>? = null
    private var savedOriginal: List<String>? = null
    private var savedShuffled = false
    private var savedRepeat = Player.REPEAT_MODE_OFF

    /** Meldung für die Oberfläche, wenn sich ein Lied nicht abspielen lässt */
    var onPlaybackProblem: ((String) -> Unit)? = null
    /** Lieder, die sich hintereinander nicht abspielen ließen (verhindert endloses Überspringen) */
    private var failedInRow = 0

    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    /**
     * Zufallswiedergabe verwaltet die App selbst (statt des Player-Zufalls), damit „Als Nächstes abspielen“
     * immer direkt nach dem laufenden Titel kommt. [originalOrder] ist die Reihenfolge ohne Zufall.
     */
    private var shuffled = false
    private var originalOrder: List<String> = emptyList()

    /** Wie viele Titel per „Als Nächstes“ schon hinter dem laufenden eingereiht sind (Reihenfolge bleibt erhalten) */
    private var nextUpCount = 0

    /** Für Verlauf und „Oft gespielt“: welcher Titel schon eingetragen bzw. gezählt wurde */
    private var startedId: String? = null
    private var countedId: String? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            startedId = null
            countedId = null
            // Ein „Als Nächstes“-Titel ist dran (oder es wurde gesprungen) → Zähler neu beginnen
            nextUpCount = 0
            flushListened()
            if (controller?.isPlaying == true) noteStart()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                failedInRow = 0
                noteStart()
            }
        }

        /** Datei fehlt, ist beschädigt oder nicht lesbar: Bescheid geben und mit dem nächsten Lied weitermachen */
        override fun onPlayerError(error: PlaybackException) {
            val c = controller ?: return
            // Nur wenn gerade gespielt werden soll: Ein pausiertes Lied (z. B. während seine Datei neu geschrieben wird)
            // springt nicht von selbst weiter – beim nächsten Tipper auf Play wird es erneut versucht
            if (!c.playWhenReady) return
            val title = c.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty().ifEmpty { "Das Lied" }
            failedInRow++
            val skip = c.mediaItemCount > 1 && c.hasNextMediaItem() && failedInRow <= minOf(MAX_SKIPS, c.mediaItemCount - 1)
            onPlaybackProblem?.invoke(
                if (skip) "„$title“ lässt sich nicht abspielen – weiter mit dem nächsten Lied."
                else "„$title“ lässt sich nicht abspielen.",
            )
            if (skip) {
                c.seekToNextMediaItem()
                c.prepare()
                c.play()
            }
        }
    }

    private fun noteStart() {
        val id = controller?.currentMediaItem?.mediaId ?: return
        if (startedId == id) return
        startedId = id
        library.recordStart(id)
    }

    /** Gezählt wird ein Titel nach 30 Sekunden (bei kurzen Titeln nach der Hälfte) */
    private fun noteProgress(c: Player) {
        val id = c.currentMediaItem?.mediaId ?: return
        if (countedId == id) return
        val duration = duration(c)
        val threshold = if (duration > 0) minOf(30_000L, duration / 2) else 30_000L
        if (c.currentPosition >= threshold) {
            countedId = id
            library.recordPlay(id)
        }
    }

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.onSuccess { c ->
                controller = c
                c.addListener(listener)
                // Nach einem Neustart: letzte Wiedergabeliste pausiert an gleicher Stelle bereitstellen.
                // Unabhängig davon, ob schon Aufträge warten: Beim Start meldet sich immer zuerst die Mediathek
                // (libraryChanged) – früher wurde die Liste deshalb nie wiederhergestellt, sondern gelöscht.
                // Ein wartender Auftrag „spiele X“ ersetzt die wiederhergestellte Liste einfach.
                if (c.mediaItemCount == 0) restoreQueue(c)
                pending.forEach { it(c) }
                pending.clear()
                refresh()
            }
        }, ContextCompat.getMainExecutor(context))

        // Position regelmäßig aktualisieren
        scope.launch {
            while (isActive) {
                controller?.let { c ->
                    if (c.isPlaying) {
                        _state.value = _state.value.copy(positionMs = c.currentPosition, durationMs = duration(c))
                        noteProgress(c)
                    }
                    checkSleepTimer(c)
                    countListening(c.isPlaying)
                    // Position alle 5 s merken (für „Weiterhören nach Neustart“)
                    if (c.isPlaying && ++saveTicks >= 10) { saveTicks = 0; savePosition(c) }
                }
                delay(500)
            }
        }
    }

    // ---------- Weiterhören nach Neustart ----------


    /** Wiedergabeliste, laufender Titel, Position, Zufall und Wiederholen merken */
    private fun saveQueue(c: MediaController, ids: List<String>) {
        if (ids.isEmpty()) {
            if (savedIds?.isEmpty() != true) {
                playbackPrefs.edit().clear().apply()
                positionPrefs.edit().clear().apply()
                savedIds = emptyList()
                savedOriginal = null
            }
            return
        }
        val repeat = c.repeatMode
        if (ids != savedIds || originalOrder != savedOriginal || shuffled != savedShuffled || repeat != savedRepeat) {
            playbackPrefs.edit()
                .putString("queue", ids.joinToString(","))
                .putString("original", originalOrder.joinToString(","))
                .putBoolean("shuffled", shuffled)
                .putInt("repeat", repeat)
                .apply()
            savedIds = ids
            savedOriginal = originalOrder
            savedShuffled = shuffled
            savedRepeat = repeat
        }
        savePosition(c)
    }

    /** Laufender Titel und Stelle im Lied – zwei kleine Werte in einer eigenen Datei */
    private fun savePosition(c: MediaController) {
        if (c.mediaItemCount == 0) return
        positionPrefs.edit()
            .putInt("index", c.currentMediaItemIndex)
            .putLong("position", c.currentPosition.coerceAtLeast(0))
            .apply()
    }

    private fun restoreQueue(c: MediaController) {
        val ids = playbackPrefs.getString("queue", null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        if (ids.isEmpty()) return
        val byId = library.tracks.associateBy { it.id }
        // Bis Version 1.0.113 standen Titel und Stelle mit in der großen Datei
        val place = if (positionPrefs.contains("index")) positionPrefs else playbackPrefs
        val savedIndex = place.getInt("index", 0)
        val currentId = ids.getOrNull(savedIndex)
        val tracks = ids.mapNotNull { byId[it] }
        if (tracks.isEmpty()) return
        val index = tracks.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val position = if (tracks.getOrNull(index)?.id == currentId) place.getLong("position", 0) else 0
        originalOrder = playbackPrefs.getString("original", null)?.split(',')?.filter { it in byId }.orEmpty()
            .ifEmpty { tracks.map { it.id } }
        shuffled = playbackPrefs.getBoolean("shuffled", false)
        c.repeatMode = playbackPrefs.getInt("repeat", Player.REPEAT_MODE_OFF)
        c.setMediaItems(tracks.map(::item), index, position)
        c.prepare() // pausiert bereit – auf Play tippen spielt sofort weiter
        refresh()
    }

    // ---------- Schlafmodus ----------

    /** Musik nach [minutes] Minuten ausschalten (ersetzt einen laufenden Timer). */
    fun startSleepTimer(minutes: Int) = startSleepTimerMs(minutes * 60_000L)

    /** Wie [startSleepTimer], aber in Millisekunden (auch für Tests). */
    fun startSleepTimerMs(durationMs: Long) {
        _sleepEndAt.value = SystemClock.elapsedRealtime() + durationMs
    }

    /** Lautstärke regelt der Wiedergabedienst (leiser werden in den letzten Sekunden) */
    fun cancelSleepTimer() {
        _sleepEndAt.value = null
    }

    /** Restzeit des Schlafmodus in ms, oder null wenn er aus ist */
    fun sleepRemainingMs(): Long? = _sleepEndAt.value?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0) }

    private fun checkSleepTimer(c: MediaController) {
        val remaining = sleepRemainingMs() ?: return
        if (remaining <= 0) {
            c.pause()
            _sleepEndAt.value = null
            refresh()
        }
    }

    /** Hörzeit zählen (Statistik) */
    private fun countListening(playing: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (playing && lastTick > 0) listenedPending += (now - lastTick).coerceIn(0, 2_000)
        lastTick = if (playing) now else 0
        // Bei Pause sofort, sonst beim Liedwechsel (siehe onMediaItemTransition) oder spätestens nach 5 Minuten:
        // Jede Übernahme speichert die ganze Mediathek und rechnet die Statistik neu
        if (listenedPending >= 300_000 || !playing) flushListened()
    }

    private fun flushListened() {
        if (listenedPending <= 0) return
        library.addListened(listenedPending)
        listenedPending = 0
    }

    private fun duration(c: Player): Long = c.duration.takeIf { it > 0 } ?: 0

    private fun refresh() {
        val c = controller ?: return
        if (c.shuffleModeEnabled) {
            // Zufall von außen eingeschaltet (z. B. Kopfhörer/Auto) → auf eigenen Zufall umstellen
            c.shuffleModeEnabled = false
            if (!shuffled) toggleShuffle()
        }
        val ids = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        _state.value = UiState(
            currentId = c.currentMediaItem?.mediaId,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition,
            durationMs = duration(c),
            queueIds = ids,
            currentIndex = if (ids.isEmpty()) -1 else c.currentMediaItemIndex,
            shuffle = shuffled,
            repeatMode = c.repeatMode,
        )
        saveQueue(c, ids)
    }

    private fun withController(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) action(c) else pending += action
    }

    /** Nach dem Umschalten „eigene Cover“: Bilder in der Wiedergabeliste aktualisieren */
    fun refreshArtwork() = withController { c ->
        val byId = library.tracks.associateBy { it.id }
        for (i in 0 until c.mediaItemCount) {
            if (i == c.currentMediaItemIndex) continue
            byId[c.getMediaItemAt(i).mediaId]?.let { c.replaceMediaItem(i, item(it)) }
        }
    }

    /** Welche Datei zu welchem Eintrag gehört (der Controller sieht die URI der Einträge nicht) */
    private val itemFiles = mutableMapOf<String, String>()

    private fun item(track: Track): MediaItem {
        itemFiles[track.id] = track.fileName
        return MediaItems.track(context, library, track)
    }

    // region Steuerung

    fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean = false) = withController { c ->
        if (tracks.isEmpty()) return@withController
        val start = startIndex.coerceIn(0, tracks.lastIndex)
        c.shuffleModeEnabled = false
        originalOrder = tracks.map { it.id }
        shuffled = shuffle
        nextUpCount = 0
        if (shuffle) {
            // Gewählter Titel zuerst, der Rest gemischt
            val first = tracks[start]
            val rest = tracks.filterIndexed { i, _ -> i != start }.shuffled()
            c.setMediaItems((listOf(first) + rest).map(::item), 0, 0)
        } else {
            c.setMediaItems(tracks.map(::item), start, 0)
        }
        c.prepare()
        c.play()
        refresh()
    }

    /** Weiterspielen – oder, wenn nichts in der Wiedergabeliste ist, alle Lieder abspielen (Widget, Bluetooth) */
    fun resumeOrStart() = withController { c ->
        if (c.mediaItemCount == 0) {
            val all = library.sortedTracks()
            if (all.isNotEmpty()) play(all, 0)
        } else {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0)
            c.prepare(); c.play()
        }
    }

    /** Widget: Abspielen/Pause – bei leerer Wiedergabeliste alle Lieder starten */
    fun togglePlayPauseOrStart() = withController { c ->
        when {
            c.mediaItemCount == 0 -> library.sortedTracks().takeIf { it.isNotEmpty() }?.let { play(it, 0) }
            c.isPlaying -> c.pause()
            else -> {
                if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0)
                c.prepare(); c.play()
            }
        }
    }

    fun togglePlayPause() = withController { c ->
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0)
            c.prepare(); c.play()
        }
    }

    fun next() = withController {
        if (it.hasNextMediaItem()) it.seekToNextMediaItem() else if (it.mediaItemCount > 0) it.seekTo(0, 0)
        // Nach einem Fehler steht der Player still – erst „prepare“ lädt das neue Lied
        if (it.playbackState == Player.STATE_IDLE && it.mediaItemCount > 0) it.prepare()
    }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms); refresh() }
    fun playQueueIndex(index: Int) = withController { it.seekTo(index, 0); it.prepare(); it.play() }

    /**
     * Großer Knopf einer Sammlung (Playlist, Album, Interpret …): Läuft gerade ein Lied daraus → Pause.
     * Ist sie pausiert und die Wiedergabeliste besteht genau aus dieser Sammlung → an derselben Stelle weiter.
     * Sonst die Sammlung von vorn abspielen.
     */
    fun playOrPause(tracks: List<Track>) = withController { c ->
        if (tracks.isEmpty()) return@withController
        val currentId = c.currentMediaItem?.mediaId
        val fromHere = currentId != null && tracks.any { it.id == currentId }
        when {
            fromHere && c.isPlaying -> c.pause()
            fromHere && queueIsExactly(c, tracks) -> {
                if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0)
                c.prepare(); c.play()
            }
            else -> play(tracks, 0)
        }
    }

    private fun queueIsExactly(c: MediaController, tracks: List<Track>): Boolean {
        if (c.mediaItemCount != tracks.size) return false
        val ids = tracks.mapTo(HashSet()) { it.id }
        return (0 until c.mediaItemCount).all { c.getMediaItemAt(it).mediaId in ids }
    }

    /**
     * Zufall an: kommende Titel mischen. Zufall aus: ursprüngliche Reihenfolge wiederherstellen. Der laufende Titel
     * spielt weiter. Die Liste wird in ein, zwei Schritten ersetzt – jedes Lied einzeln zu verschieben ließ lange
     * Wiedergabelisten sekundenlang hängen.
     */
    fun toggleShuffle() = withController { c ->
        val ids = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        val current = c.currentMediaItemIndex
        if (current !in ids.indices) return@withController
        val byId = library.tracks.associateBy { it.id }
        // Eintrag an einer bisherigen Stelle der Liste
        fun entry(position: Int): MediaItem = byId[ids[position]]?.let(::item) ?: c.getMediaItemAt(position)
        if (!shuffled) {
            if (originalOrder.isEmpty()) originalOrder = ids
            val upcoming = ((current + 1) until ids.size).shuffled()
            if (upcoming.size > 1) c.replaceMediaItems(current + 1, ids.size, upcoming.map(::entry))
            shuffled = true
        } else {
            // Jede Stelle der Liste genau einmal: erst in der ursprünglichen Reihenfolge, später Dazugekommenes ans Ende
            val free = HashMap<String, ArrayDeque<Int>>()
            ids.forEachIndexed { i, id -> if (i != current) free.getOrPut(id) { ArrayDeque() }.addLast(i) }
            val before = mutableListOf<Int>()
            val after = mutableListOf<Int>()
            var passedCurrent = false
            for (id in originalOrder) {
                if (!passedCurrent && id == ids[current]) {
                    passedCurrent = true
                    continue
                }
                val position = free[id]?.removeFirstOrNull() ?: continue
                if (passedCurrent) after += position else before += position
            }
            val placed = (before + after).toHashSet()
            ids.indices.forEach { if (it != current && it !in placed) after += it }
            // Erst hinter dem laufenden Titel ersetzen (verschiebt ihn nicht), dann davor
            if (after != ((current + 1) until ids.size).toList()) c.replaceMediaItems(current + 1, ids.size, after.map(::entry))
            if (before != (0 until current).toList()) c.replaceMediaItems(0, current, before.map(::entry))
            shuffled = false
        }
        nextUpCount = 0
        refresh()
    }

    fun setRepeatMode(mode: Int) = withController { it.repeatMode = mode }

    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /**
     * „Als Nächstes abspielen“: direkt hinter den laufenden Titel – mehrere hintereinander in der gewählten
     * Reihenfolge. Gilt auch bei Zufallswiedergabe.
     */
    fun playNext(track: Track) = withController { c ->
        if (c.mediaItemCount == 0) {
            play(listOf(track), 0)
            return@withController
        }
        val insertAt = (c.currentMediaItemIndex + 1 + nextUpCount).coerceAtMost(c.mediaItemCount)
        c.addMediaItem(insertAt, item(track))
        nextUpCount++
        // Auch in der Reihenfolge ohne Zufall hinter den laufenden Titel setzen
        val currentId = c.currentMediaItem?.mediaId
        val pos = originalOrder.indexOf(currentId)
        originalOrder = if (pos >= 0) {
            originalOrder.toMutableList().apply { add((pos + nextUpCount).coerceAtMost(size), track.id) }
        } else originalOrder + track.id
        refresh()
    }

    /** „In die Wiedergabeliste“: ans Ende */
    fun addToQueue(track: Track) = withController { c ->
        if (c.mediaItemCount == 0) play(listOf(track), 0) else {
            c.addMediaItem(item(track))
            originalOrder = originalOrder + track.id
            refresh()
        }
    }

    /** Anhängen ohne die Wiedergabe zu starten (Downloads); leere Liste → pausiert bereit. */
    fun enqueue(track: Track) = withController { c ->
        if ((0 until c.mediaItemCount).any { c.getMediaItemAt(it).mediaId == track.id }) return@withController
        if (c.mediaItemCount == 0) {
            originalOrder = listOf(track.id)
            shuffled = false
            c.setMediaItems(listOf(item(track)))
            c.prepare()
        } else {
            c.addMediaItem(item(track))
            originalOrder = originalOrder + track.id
        }
    }

    /** „Mix starten“: Titel zuerst, dann gemischt – gleicher Interpret bevorzugt. */
    fun startMix(seed: Track, all: List<Track>) {
        val others = all.filter { it.id != seed.id }
        val same = if (seed.artist.isBlank()) emptyList() else others.filter { it.artist == seed.artist }.shuffled()
        val rest = others.filter { seed.artist.isBlank() || it.artist != seed.artist }.shuffled()
        play(listOf(seed) + same + rest, 0)
    }

    /** Gemerkte Wiedergabeliste (z. B. vor dem Probehören im Zuschneider) */
    class SavedQueue internal constructor(
        internal val ids: List<String>, internal val index: Int, internal val positionMs: Long, internal val playing: Boolean,
        internal val original: List<String>, internal val shuffled: Boolean,
    )

    fun saveQueue(): SavedQueue {
        val s = _state.value
        return SavedQueue(s.queueIds, s.currentIndex, s.positionMs, s.isPlaying, originalOrder, shuffled)
    }

    /** Stellt eine gemerkte Wiedergabeliste wieder her – an derselben Stelle, spielend oder pausiert wie vorher */
    fun restoreQueue(saved: SavedQueue) = withController { c ->
        val byId = library.tracks.associateBy { it.id }
        val tracks = saved.ids.mapNotNull { byId[it] }
        nextUpCount = 0
        if (tracks.isEmpty()) {
            // Vorher lief nichts → Wiedergabeliste wieder schließen
            c.stop(); c.clearMediaItems()
            originalOrder = emptyList(); shuffled = false
        } else {
            val currentId = saved.ids.getOrNull(saved.index)
            val index = tracks.indexOfFirst { it.id == currentId }
            c.shuffleModeEnabled = false
            c.setMediaItems(tracks.map(::item), index.coerceAtLeast(0), if (index >= 0) saved.positionMs else 0)
            originalOrder = saved.original.filter { it in byId }
            shuffled = saved.shuffled
            c.prepare()
            if (saved.playing) c.play() else c.pause()
        }
        refresh()
    }

    /** „Wiedergabeliste schließen“ */
    /** Nur für automatische Tests: Verbindung zum Wiedergabe-Dienst wieder lösen */
    internal fun releaseForTests() {
        controller?.let { c ->
            c.removeListener(listener)
            c.release()
        }
        controller = null
        pending.clear()
    }

    fun stop() = withController {
        it.stop(); it.clearMediaItems()
        originalOrder = emptyList(); shuffled = false; nextUpCount = 0
        cancelSleepTimer()
    }

    fun removeFromQueue(index: Int) = withController { c ->
        if (index != c.currentMediaItemIndex && index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    fun moveInQueue(from: Int, to: Int) = withController { c ->
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) {
            c.moveMediaItem(from, to)
            nextUpCount = 0
            refresh()
        }
    }

    /** Titel gelöscht oder Datei ersetzt → Wiedergabeliste anpassen */
    fun libraryChanged(tracks: List<Track>) = withController { c ->
        val byId = tracks.associateBy { it.id }
        for (i in c.mediaItemCount - 1 downTo 0) {
            val id = c.getMediaItemAt(i).mediaId
            val track = byId[id]
            if (track == null) {
                c.removeMediaItem(i)
            } else if (i != c.currentMediaItemIndex && itemFiles[id] != track.fileName) {
                // Nur wenn sich die Datei geändert hat (z. B. nach dem Umwandeln in MP3)
                c.replaceMediaItem(i, item(track))
            }
        }
    }

    // endregion

    private companion object {
        /** So viele unspielbare Lieder werden höchstens hintereinander übersprungen */
        const val MAX_SKIPS = 10
    }
}
