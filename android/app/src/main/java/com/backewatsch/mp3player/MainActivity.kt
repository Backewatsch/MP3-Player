package com.backewatsch.mp3player

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.backewatsch.mp3player.ui.MainScreen
import com.backewatsch.mp3player.ui.MP3Theme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            MP3Theme { MainScreen() }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            app.library.rescan()
            app.updates.checkIfDue()
        }
    }

    /** „Öffnen mit MP3 Player“ / „Teilen“ aus anderen Apps */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        setIntent(Intent(this, MainActivity::class.java))
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Wird übernommen und in MP3 umgewandelt …", Toast.LENGTH_SHORT).show()
            val errors = app.importUris(uris)
            if (errors.isNotEmpty()) Toast.makeText(this@MainActivity, errors.joinToString("\n"), Toast.LENGTH_LONG).show()
        }
    }
}
