package com.backewatsch.mp3player.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.backewatsch.mp3player.app

/** Spielt im Hintergrund weiter – mit Steuerung in Benachrichtigung, Sperrbildschirm und Kopfhörern. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var exo: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Ein-/Ausblenden und Schlafmodus: Lautstärke alle 50 ms nachführen, solange Musik läuft */
    private val fader = object : Runnable {
        override fun run() {
            val p = exo ?: return
            val fadeMs = app.sound.settings.value.crossfadeSec * 1000L
            val v = SoundPresets.volume(p.currentPosition, p.duration.coerceAtLeast(0), fadeMs, app.player.sleepRemainingMs())
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
            override fun onAudioSessionIdChanged(audioSessionId: Int) = app.sound.attach(audioSessionId)

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(fader)
                if (isPlaying) fader.run() else player.volume = 1f // pausiert: beim Weiterspielen regelt der Fader neu
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // Neues Lied: sofort richtige Lautstärke (z. B. leise beginnen beim Einblenden)
                handler.removeCallbacks(fader)
                fader.run()
            }
        })
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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
}
