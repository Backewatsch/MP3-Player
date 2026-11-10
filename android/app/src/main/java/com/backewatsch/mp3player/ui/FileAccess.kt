package com.backewatsch.mp3player.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag

/**
 * „Zugriff auf alle Dateien“: nötig, damit die App verknüpfte Originale (aus Samsung Music, Download …)
 * löschen und zuschneiden darf. Android erlaubt das nur über diese Einstellung.
 */
object FileAccess {
    fun granted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Öffnet die Einstellung „Zugriff auf alle Dateien“ für diese App (ab Android 11) */
    fun openSettings(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val own = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(own) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
    }
}

/** Liefert eine Funktion, die den Zugriff anfragt (Einstellung ab Android 11, sonst Berechtigungsdialog) */
@Composable
fun rememberFileAccessRequest(): () -> Unit {
    val context = LocalContext.current
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= 30) FileAccess.openSettings(context)
        else legacy.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}

/**
 * Hinweis, wenn eine Originaldatei geändert/gelöscht werden soll, die App das aber noch nicht darf.
 * @param libraryOnly optional: „Nur aus der Mediathek entfernen“ statt Original zu löschen
 */
@Composable
fun FileAccessDialog(what: String, onDismiss: () -> Unit, libraryOnly: (() -> Unit)? = null) {
    val request = rememberFileAccessRequest()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zugriff auf Originaldateien") },
        text = {
            Text("$what liegt außerhalb der App (z. B. in Samsung Music oder im Download-Ordner). Damit die App es " +
                "dort ändern oder löschen darf, schalte einmalig „Zugriff auf alle Dateien“ für ILLEGAL FREEDOM MUSIC ein " +
                "und versuche es danach erneut.")
        },
        confirmButton = {
            Column {
                TextButton(onClick = { onDismiss(); request() }, modifier = Modifier.testTag("fileAccessAllow")) {
                    Text("Zugriff erlauben")
                }
                if (libraryOnly != null) TextButton(onClick = { onDismiss(); libraryOnly() }, modifier = Modifier.testTag("fileAccessLibraryOnly")) {
                    Text("Nur aus Mediathek entfernen", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
