package com.backewatsch.mp3player.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
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

    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    /** Für Verlauf und „Oft gespielt“: welcher Titel schon eingetragen bzw. gezählt wurde */
    private var startedId: String? = null
    private var countedId: String? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            startedId = null
            countedId = null
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
                }
                delay(500)
            }
        }
    }

    private fun duration(c: Player): Long = c.duration.takeIf { it > 0 } ?: 0

    private fun refresh() {
        val c = controller ?: return
        val ids = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        _state.value = UiState(
            currentId = c.currentMediaItem?.mediaId,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition,
            durationMs = duration(c),
            queueIds = ids,
            currentIndex = if (ids.isEmpty()) -1 else c.currentMediaItemIndex,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
        )
    }

    private fun withController(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) action(c) else pending += action
    }

    private fun item(track: Track): MediaItem {
        val artwork = library.artworkOf(track)
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(Uri.fromFile(library.fileOf(track)))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist.ifEmpty { null })
                    .setAlbumTitle(track.album.ifEmpty { null })
                    .setArtworkUri(artwork?.let { Uri.fromFile(it) })
                    .build(),
            )
            .build()
    }

    // region Steuerung

    fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean = false) = withController { c ->
        if (tracks.isEmpty()) return@withController
        c.shuffleModeEnabled = shuffle
        c.setMediaItems(tracks.map(::item), startIndex.coerceIn(0, tracks.lastIndex), 0)
        c.prepare()
        c.play()
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

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** „Als Nächstes abspielen“ */
    fun playNext(track: Track) = withController { c ->
        if (c.mediaItemCount == 0) play(listOf(track), 0) else c.addMediaItem(c.currentMediaItemIndex + 1, item(track))
    }

    /** „In die Wiedergabeliste“ */
    fun addToQueue(track: Track) = withController { c ->
        if (c.mediaItemCount == 0) play(listOf(track), 0) else c.addMediaItem(item(track))
    }

    /** Anhängen ohne die Wiedergabe zu starten (Downloads); leere Liste → pausiert bereit. */
    fun enqueue(track: Track) = withController { c ->
        if ((0 until c.mediaItemCount).any { c.getMediaItemAt(it).mediaId == track.id }) return@withController
        if (c.mediaItemCount == 0) {
            c.setMediaItems(listOf(item(track)))
            c.prepare()
        } else {
            c.addMediaItem(item(track))
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
    fun stop() = withController { it.stop(); it.clearMediaItems() }

    fun removeFromQueue(index: Int) = withController { c ->
        if (index != c.currentMediaItemIndex && index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    fun moveInQueue(from: Int, to: Int) = withController { c ->
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) c.moveMediaItem(from, to)
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
