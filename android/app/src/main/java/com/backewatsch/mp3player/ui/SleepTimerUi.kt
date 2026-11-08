package com.backewatsch.mp3player.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.backewatsch.mp3player.player.SleepTimer
import kotlinx.coroutines.delay

/** Restzeit des Schlafmodus, sekündlich aktualisiert (null = aus) */
@Composable
fun rememberSleepRemaining(): Long? {
    val app = LocalApp.current
    val endAt by app.player.sleepEndAt.collectAsState()
    var remaining by remember { mutableLongStateOf(0L) }
    LaunchedEffect(endAt) {
        while (endAt != null) {
            remaining = app.player.sleepRemainingMs() ?: 0
            delay(1000)
        }
    }
    return if (endAt == null) null else remaining
}

/** Mond-Knopf für den Player: öffnet den Schlafmodus, zeigt die Restzeit, wenn er läuft. */
@Composable
fun SleepTimerButton() {
    var open by remember { mutableStateOf(false) }
    val remaining = rememberSleepRemaining()
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (remaining != null) {
            Text(SleepTimer.formatRemaining(remaining), style = MaterialTheme.typography.labelMedium, color = Accent,
                modifier = Modifier.testTag("sleepRemaining"))
        }
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("sleepButton")) {
            if (remaining != null) Icon(Icons.Filled.Bedtime, "Schlafmodus läuft", tint = Accent)
            else Icon(Icons.Outlined.Bedtime, "Schlafmodus")
        }
    }
    if (open) SleepTimerDialog(onDismiss = { open = false })
}

/** Feld zum Eingeben der Minuten; danach geht die Musik aus. */
@Composable
fun SleepTimerDialog(onDismiss: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val remaining = rememberSleepRemaining()
    var text by remember { mutableStateOf("") }
    val minutes = SleepTimer.parseMinutes(text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun start() {
        val m = minutes ?: return
        app.player.startSleepTimer(m)
        Toast.makeText(context, "Schlafmodus: Musik geht in $m ${if (m == 1) "Minute" else "Minuten"} aus", Toast.LENGTH_SHORT).show()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Bedtime, null, tint = Accent) },
        title = { Text("Schlafmodus") },
        text = {
            Column {
                if (remaining != null) {
                    Text("Läuft – Musik geht aus in ${SleepTimer.formatRemaining(remaining)}", color = Accent,
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                }
                Text("Nach wie vielen Minuten soll die Musik ausgehen?", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { new -> text = new.filter(Char::isDigit).take(4) },
                    label = { Text("Minuten") },
                    suffix = { Text("Min.") },
                    singleLine = true,
                    isError = text.isNotEmpty() && minutes == null,
                    supportingText = {
                        if (text.isNotEmpty() && minutes == null) Text("Bitte 1 bis ${SleepTimer.MAX_MINUTES} Minuten eingeben")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { start() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("sleepMinutes"),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (preset in listOf(15, 30, 45, 60)) {
                        FilterChip(selected = minutes == preset, onClick = { text = preset.toString() }, label = { Text("$preset") })
                    }
                }
                Text("In den letzten Sekunden wird die Musik sanft leiser.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = ::start, enabled = minutes != null, modifier = Modifier.testTag("sleepStart")) {
                Text(if (remaining != null) "Neu starten" else "Starten")
            }
        },
        dismissButton = {
            if (remaining != null) {
                TextButton(onClick = {
                    app.player.cancelSleepTimer()
                    Toast.makeText(context, "Schlafmodus beendet", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }, modifier = Modifier.testTag("sleepCancel")) { Text("Timer beenden", color = MaterialTheme.colorScheme.error) }
            } else {
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}
