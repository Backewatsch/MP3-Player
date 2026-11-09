package com.backewatsch.mp3player.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.backewatsch.mp3player.app
import com.backewatsch.mp3player.data.Library
import com.backewatsch.mp3player.model.Track
import com.backewatsch.mp3player.ui.LogoBitmap

/** Baut die Einträge für den Player (App, Benachrichtigung, Sperrbildschirm, Android Auto). */
object MediaItems {
    fun track(context: Context, library: Library, track: Track): MediaItem {
        val showCover = context.app.showOwnCovers.value || library.hasCustomCover(track)
        val artwork = if (showCover) library.artworkOf(track) else null
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(Uri.fromFile(library.fileOf(track)))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist.ifEmpty { null })
                    .setAlbumTitle(track.album.ifEmpty { null })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .apply {
                        if (artwork != null) setArtworkUri(Uri.fromFile(artwork))
                        else setArtworkData(LogoBitmap.png(context), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    }
                    .build(),
            )
            .build()
    }

    /** Ordner für Android Auto (Lieblingssongs, Playlists …) */
    fun folder(id: String, title: String, subtitle: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()
}
