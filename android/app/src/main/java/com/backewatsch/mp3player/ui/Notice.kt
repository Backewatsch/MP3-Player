package com.backewatsch.mp3player.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Kurze Hinweise („2 Titel zu Lieblingssongs“) in der App selbst – über dem Mini-Player statt über der Navigation.
 * Liegt gerade ein Blatt (Player, Einstellungen …) über der Hauptseite, erscheint der Hinweis als Android-Hinweis.
 */
object Notice {
    data class Message(val id: Long, val text: String)
    val current = MutableStateFlow<Message?>(null)
    /** true = die Hauptseite ist nicht sichtbar (oder noch nicht da) → Android-Hinweis verwenden */
    @Volatile var blocked: () -> Boolean = { true }

    fun show(context: Context, text: String) {
        if (blocked()) Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        else current.value = Message(System.nanoTime(), text)
    }
}

@Composable
fun NoticeHost(modifier: Modifier = Modifier) {
    val message by Notice.current.collectAsState()
    val shown = remember { mutableStateOf("") }
    message?.let { shown.value = it.text }
    LaunchedEffect(message?.id) {
        if (message != null) { delay(2600); Notice.current.value = null }
    }
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Text(shown.value, color = Color(0xFF04202E), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 24.dp).shadow(10.dp, CircleShape).clip(CircleShape)
                .background(Color(0xFFE2E9F0)).padding(horizontal = 18.dp, vertical = 10.dp).testTag("notice"))
    }
}
