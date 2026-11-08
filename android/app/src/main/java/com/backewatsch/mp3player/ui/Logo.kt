package com.backewatsch.mp3player.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import com.backewatsch.mp3player.R
import java.io.ByteArrayOutputStream

/** Das App-Logo (ILLEGAL FREEDOM MUSIC) als Bild – wird als Cover neben jedem Lied gezeigt */
@Composable
fun LogoArt(size: Dp, shape: Shape) {
    Box(Modifier.size(size).clip(shape)) {
        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        // Vordergrund ist für adaptive Symbole mit Rand angelegt → etwas vergrößern
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize().scale(1.35f),
            contentScale = ContentScale.Fit)
    }
}

/** Logo als PNG (für Benachrichtigung und Sperrbildschirm), einmal erzeugt und zwischengespeichert */
object LogoBitmap {
    @Volatile private var cached: ByteArray? = null

    fun png(context: Context, size: Int = 512): ByteArray {
        cached?.let { return it }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        ContextCompat.getDrawable(context, R.drawable.ic_launcher_background)?.apply { setBounds(0, 0, size, size); draw(canvas) }
        val inset = -(size * 0.175f).toInt() // gleiche Vergrößerung wie in LogoArt
        ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)?.apply {
            setBounds(inset, inset, size - inset, size - inset); draw(canvas)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray().also { cached = it }
    }
}
