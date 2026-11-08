package com.backewatsch.mp3player.player

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

    /** true, solange die Lautstärke für den Schlafmodus heruntergeregelt ist */
    private var fading = false

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
                }
                delay(500)
            }
        }
    }

    // ---------- Schlafmodus ----------

    /** Musik nach [minutes] Minuten ausschalten (ersetzt einen laufenden Timer). */
    fun startSleepTimer(minutes: Int) = startSleepTimerMs(minutes * 60_000L)

    /** Wie [startSleepTimer], aber in Millisekunden (auch für Tests). */
    fun startSleepTimerMs(durationMs: Long) {
        restoreVolume()
        _sleepEndAt.value = SystemClock.elapsedRealtime() + durationMs
    }

    fun cancelSleepTimer() {
        _sleepEndAt.value = null
        restoreVolume()
    }

    /** Restzeit des Schlafmodus in ms, oder null wenn er aus ist */
    fun sleepRemainingMs(): Long? = _sleepEndAt.value?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0) }

    private fun restoreVolume() {
        if (fading) {
            controller?.volume = 1f
            fading = false
        }
    }

    private fun checkSleepTimer(c: MediaController) {
        val remaining = sleepRemainingMs() ?: return
        if (remaining <= 0) {
            c.pause()
            _sleepEndAt.value = null
            c.volume = 1f
            fading = false
            refresh()
        } else if (remaining < SleepTimer.FADE_MS && c.isPlaying) {
            fading = true
            c.volume = SleepTimer.volumeFor(remaining)
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

    private fun item(track: Track): MediaItem {
        val ownCovers = (context.applicationContext as com.backewatsch.mp3player.App).showOwnCovers.value
        val artwork = if (ownCovers) library.artworkOf(track) else null
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(Uri.fromFile(library.fileOf(track)))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist.ifEmpty { null })
                    .setAlbumTitle(track.album.ifEmpty { null })
                    .apply {
                        if (artwork != null) setArtworkUri(Uri.fromFile(artwork))
                        else setArtworkData(com.backewatsch.mp3player.ui.LogoBitmap.png(context), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    }
                    .build(),
            )
            .build()
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
            } else if (i != c.currentMediaItemIndex) {
                val wanted = Uri.fromFile(library.fileOf(track))
                if (c.getMediaItemAt(i).localConfiguration?.uri != wanted) c.replaceMediaItem(i, item(track))
            }
        }
    }

    // endregion
}
