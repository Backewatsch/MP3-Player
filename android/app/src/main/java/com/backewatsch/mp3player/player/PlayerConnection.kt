package com.backewatsch.mp3player.player

import com.backewatsch.mp3player.data.*

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
    private var saveTicks = 0

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
            if (controller?.isPlaying == true) noteStart()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) noteStart()
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
                // Nach einem Neustart: letzte Wiedergabeliste pausiert an gleicher Stelle bereitstellen
                if (c.mediaItemCount == 0 && pending.isEmpty()) restoreQueue(c)
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
                    if (c.isPlaying && ++saveTicks >= 10) { saveTicks = 0; saveQueue(c) }
                }
                delay(500)
            }
        }
    }

    // ---------- Weiterhören nach Neustart ----------


    /** Wiedergabeliste, laufender Titel, Position, Zufall und Wiederholen merken */
    private fun saveQueue(c: MediaController) {
        val ids = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        if (ids.isEmpty()) {
            playbackPrefs.edit().clear().apply()
            return
        }
        playbackPrefs.edit()
            .putString("queue", ids.joinToString(","))
            .putString("original", originalOrder.joinToString(","))
            .putInt("index", c.currentMediaItemIndex)
            .putLong("position", c.currentPosition.coerceAtLeast(0))
            .putBoolean("shuffled", shuffled)
            .putInt("repeat", c.repeatMode)
            .apply()
    }

    private fun restoreQueue(c: MediaController) {
        val ids = playbackPrefs.getString("queue", null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        if (ids.isEmpty()) return
        val byId = library.tracks.associateBy { it.id }
        val savedIndex = playbackPrefs.getInt("index", 0)
        val currentId = ids.getOrNull(savedIndex)
        val tracks = ids.mapNotNull { byId[it] }
        if (tracks.isEmpty()) return
        val index = tracks.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val position = if (tracks.getOrNull(index)?.id == currentId) playbackPrefs.getLong("position", 0) else 0
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
        if (listenedPending >= 30_000 || (!playing && listenedPending > 0)) {
            library.addListened(listenedPending)
            listenedPending = 0
        }
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
        saveQueue(c)
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

    fun next() = withController { if (it.hasNextMediaItem()) it.seekToNextMediaItem() else if (it.mediaItemCount > 0) it.seekTo(0, 0) }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms); refresh() }
    fun playQueueIndex(index: Int) = withController { it.seekTo(index, 0); it.play() }

    /** Zufall an: kommende Titel mischen. Zufall aus: ursprüngliche Reihenfolge wiederherstellen. Der laufende Titel spielt weiter. */
    fun toggleShuffle() = withController { c ->
        val ids = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        val current = c.currentMediaItemIndex
        if (!shuffled) {
            if (originalOrder.isEmpty()) originalOrder = ids
            val upcoming = ids.drop(current + 1).shuffled()
            applyOrder(c, upcoming, current + 1)
            shuffled = true
        } else {
            val known = originalOrder.filter { it in ids }
            val extra = ids.filter { it !in originalOrder }
            applyOrder(c, known + extra, 0)
            shuffled = false
        }
        nextUpCount = 0
        refresh()
    }

    /** Ordnet die Wiedergabeliste ab [from] in die Reihenfolge [desired] um (ohne die Wiedergabe zu unterbrechen) */
    private fun applyOrder(c: MediaController, desired: List<String>, from: Int) {
        desired.forEachIndexed { offset, id ->
            val target = from + offset
            if (target >= c.mediaItemCount) return
            if (c.getMediaItemAt(target).mediaId == id) return@forEachIndexed
            val j = (target until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == id } ?: return@forEachIndexed
            c.moveMediaItem(j, target)
        }
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

    /** „Wiedergabeliste schließen“ */
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
}
