package com.backewatsch.mp3player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.backewatsch.mp3player.MainActivity
import com.backewatsch.mp3player.R
import com.backewatsch.mp3player.app

/** Widget für den Startbildschirm: aktuelles Lied, Herz, Zurück, Abspielen/Pause, Weiter. */
class PlayerWidget : AppWidgetProvider() {
    data class Info(val title: String?, val artist: String?, val isPlaying: Boolean, val favorite: Boolean)

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        update(context, current(context), manager, ids)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val player = context.app.player
        when (intent.action) {
            ACTION_TOGGLE -> {
                // Nach einem Neustart ist die Wiedergabeliste leer → alle Lieder abspielen
                if (context.app.library.tracks.isEmpty()) openApp(context) else player.togglePlayPauseOrStart()
            }
            ACTION_NEXT -> player.next()
            ACTION_PREV -> player.previous()
            ACTION_FAV -> {
                val id = player.state.value.currentId
                context.app.library.track(id ?: "")?.let { context.app.library.toggleFavorite(it) }
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.backewatsch.mp3player.widget.TOGGLE"
        const val ACTION_NEXT = "com.backewatsch.mp3player.widget.NEXT"
        const val ACTION_PREV = "com.backewatsch.mp3player.widget.PREV"
        const val ACTION_FAV = "com.backewatsch.mp3player.widget.FAV"

        private fun current(context: Context): Info {
            val app = context.app
            val p = app.player.state.value
            val t = app.library.track(p.currentId ?: "")
            return Info(t?.title, t?.artist, p.isPlaying, t != null && app.library.isFavorite(t))
        }

        /** Alle Widgets mit dem aktuellen Stand neu zeichnen */
        fun update(context: Context, info: Info) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java)) }.getOrNull() ?: return
            if (ids.isNotEmpty()) update(context, info, manager, ids)
        }

        private fun update(context: Context, info: Info, manager: AppWidgetManager, ids: IntArray) {
            val views = views(context, info)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        /** Ansicht des Widgets (auch für Tests) */
        fun views(context: Context, info: Info): RemoteViews = RemoteViews(context.packageName, R.layout.widget_player).apply {
            setTextViewText(R.id.widget_title, info.title ?: context.getString(R.string.widget_idle))
            setTextViewText(R.id.widget_artist, info.artist?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name))
            setImageViewResource(R.id.widget_play, if (info.isPlaying) R.drawable.ic_w_pause else R.drawable.ic_w_play)
            setContentDescription(R.id.widget_play, if (info.isPlaying) "Pause" else "Abspielen")
            setImageViewResource(R.id.widget_fav, if (info.favorite) R.drawable.ic_w_heart else R.drawable.ic_w_heart_outline)
            setOnClickPendingIntent(R.id.widget_play, action(context, ACTION_TOGGLE, 1))
            setOnClickPendingIntent(R.id.widget_next, action(context, ACTION_NEXT, 2))
            setOnClickPendingIntent(R.id.widget_prev, action(context, ACTION_PREV, 3))
            setOnClickPendingIntent(R.id.widget_fav, action(context, ACTION_FAV, 4))
            val open = PendingIntent.getActivity(context, 5, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            setOnClickPendingIntent(R.id.widget_logo, open)
            setOnClickPendingIntent(R.id.widget_title, open)
        }

        private fun action(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
            context, code, Intent(context, PlayerWidget::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun openApp(context: Context) {
            runCatching { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
