package com.backewatsch.mp3player.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.backewatsch.mp3player.app
import com.backewatsch.mp3player.data.SmartPlaylist
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Spielt im Hintergrund weiter – mit Steuerung in Benachrichtigung, Sperrbildschirm, Kopfhörern und Android Auto
 * (Mediathek im Auto durchsuchen).
 */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private var exo: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Ein-/Ausblenden, Schlafmodus und Lautstärke-Angleichung: alle 50 ms nachführen, solange Musik läuft */
    private val fader = object : Runnable {
        override fun run() {
            val p = exo ?: return
            val fadeMs = app.sound.settings.value.crossfadeSec * 1000L
            val v = SoundPresets.volume(p.currentPosition, p.duration.coerceAtLeast(0), fadeMs, app.player.sleepRemainingMs()) *
                app.sound.attenuation()
            if (kotlin.math.abs(p.volume - v) > 0.002f) p.volume = v
            if (p.isPlaying) handler.postDelayed(this, 50)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // Kopfhörer abgezogen → Pause
            .setWakeMode(C.WAKE_MODE_LOCAL) // läuft bei ausgeschaltetem Bildschirm zuverlässig weiter (auch der Schlafmodus)
            .build()
        exo = player
        // Equalizer an die Audio-Sitzung hängen
        app.sound.attach(player.audioSessionId)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                app.sound.attach(audioSessionId)
                applyTrackGain(player.currentMediaItem)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(fader)
                if (isPlaying) fader.run() else player.volume = app.sound.attenuation() // pausiert: beim Weiterspielen regelt der Fader neu
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Neues Lied: Lautstärke-Angleichung und sofort richtige Lautstärke (z. B. leise beginnen beim Einblenden)
                applyTrackGain(mediaItem)
                handler.removeCallbacks(fader)
                fader.run()
            }
        })
        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
    }

    private fun applyTrackGain(item: MediaItem?) {
        app.sound.setTrackGain(item?.mediaId?.let { app.library.gainOf(it) } ?: 0f)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(fader)
        app.sound.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        exo = null
        super.onDestroy()
    }

    /** Mediathek für Android Auto & Co. und Auflösen von Einträgen ohne Datei-Adresse */
    private inner class LibraryCallback : MediaLibrarySession.Callback {
        private val library get() = app.library

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(MediaItems.folder(ROOT, "ILLEGAL FREEDOM MUSIC"), params))

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val track = library.track(mediaId)
            return Futures.immediateFuture(
                if (track != null) LibraryResult.ofItem(MediaItems.track(this@PlaybackService, library, track), null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE),
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items = children(parentId)
            val from = (page * pageSize).coerceAtMost(items.size)
            val to = (from + pageSize).coerceAtMost(items.size)
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params))
        }

        private fun children(parentId: String): List<MediaItem> {
            val ctx = this@PlaybackService
            fun tracks(list: List<com.backewatsch.mp3player.model.Track>) = list.map { MediaItems.track(ctx, library, it) }
            return when {
                parentId == ROOT -> listOf(
                    MediaItems.folder(FAVORITES, "Lieblingssongs"),
                    MediaItems.folder(RECENT, "Zuletzt gehört"),
                    MediaItems.folder(MOST, "Oft gespielt"),
                    MediaItems.folder(PLAYLISTS, "Playlists"),
                    MediaItems.folder(MIXES, "Deine Mixe"),
                    MediaItems.folder(ALL, "Alle Titel"),
                )
                parentId == FAVORITES -> tracks(library.favoriteTracks())
                parentId == RECENT -> tracks(library.recentlyPlayed(50))
                parentId == MOST -> tracks(library.mostPlayed(50).map { it.first })
                parentId == ALL -> tracks(library.sortedTracks())
                parentId == PLAYLISTS -> library.playlists.map { MediaItems.folder(PLAYLIST + it.id, it.name, "${it.trackIds.size} Titel") }
                parentId == MIXES -> SmartPlaylist.entries.map { MediaItems.folder(MIX + it.name, it.title, it.subtitle) }
                parentId.startsWith(PLAYLIST) -> library.playlist(parentId.removePrefix(PLAYLIST))?.let { tracks(library.tracksIn(it)) }.orEmpty()
                parentId.startsWith(MIX) -> SmartPlaylist.fromName(parentId.removePrefix(MIX))?.let { tracks(library.smartTracks(it)) }.orEmpty()
                else -> emptyList()
            }
        }

        /** Einträge ohne Datei (z. B. aus Android Auto) über die ID in der Mediathek finden */
        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.mapNotNull { item ->
                if (item.localConfiguration != null) item
                else library.track(item.mediaId)?.let { MediaItems.track(this@PlaybackService, library, it) }
            }.toMutableList(),
        )
    }

    companion object {
        const val ROOT = "root"
        const val FAVORITES = "favorites"
        const val RECENT = "recent"
        const val MOST = "most"
        const val ALL = "all"
        const val PLAYLISTS = "playlists"
        const val PLAYLIST = "playlist:"
        const val MIXES = "mixes"
        const val MIX = "mix:"
    }
}
