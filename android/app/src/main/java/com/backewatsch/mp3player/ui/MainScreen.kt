package com.backewatsch.mp3player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.backewatsch.mp3player.App
import com.backewatsch.mp3player.app
import kotlinx.coroutines.launch

val LocalApp = staticCompositionLocalOf<App> { error("App fehlt") }
val LocalNav = staticCompositionLocalOf<AppNavigation> { error("Navigation fehlt") }

/** Tabs und Navigation innerhalb der Mediathek */
const val TAB_START = 0
const val TAB_LIBRARY = 1
const val TAB_DOWNLOADER = 2

class AppNavigation {
    var tab by mutableStateOf(0)
    val libraryStack = mutableStateListOf<LibRoute>(LibRoute.Root)
    var showNowPlaying by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var showDownloads by mutableStateOf(false)
    var showTransfer by mutableStateOf(false)
    var showSound by mutableStateOf(false)
    val actions = TrackActionsState()

    fun push(route: LibRoute) {
        tab = TAB_LIBRARY
        showNowPlaying = false
        libraryStack.add(route)
    }

    fun pop(): Boolean {
        if (libraryStack.size <= 1) return false
        libraryStack.removeAt(libraryStack.lastIndex)
        return true
    }
}

@Composable
fun MainScreen() {
    val app = LocalContext.current.app
    val nav = remember { AppNavigation() }
    CompositionLocalProvider(LocalApp provides app, LocalNav provides nav) {
        val downloads by app.downloads.items.collectAsState()
        val unseen by app.downloads.unseen.collectAsState()
        val active = downloads.count { it.state is com.backewatsch.mp3player.download.DownloadRepository.State.Running ||
            it.state is com.backewatsch.mp3player.download.DownloadRepository.State.Converting }

        BackHandler(enabled = nav.tab == TAB_LIBRARY && nav.libraryStack.size > 1) { nav.pop() }
        BackHandler(enabled = nav.tab == TAB_DOWNLOADER) { app.browser.back() }

        Scaffold(
            modifier = Modifier.cyberBackground(),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            // Bei durchsichtigem Hintergrund die Textfarbe ausdrücklich setzen (sonst Schwarz)
            contentColor = MaterialTheme.colorScheme.onBackground,
            bottomBar = {
                Column {
                    MiniPlayer(onOpen = { nav.showNowPlaying = true })
                    NavigationBar(containerColor = CyberBlack.copy(alpha = 0.97f), tonalElevation = 0.dp) {
                        val colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = androidx.compose.ui.graphics.Color.White, selectedTextColor = androidx.compose.ui.graphics.Color.White,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = Accent.copy(alpha = 0.16f))
                        NavigationBarItem(
                            selected = nav.tab == TAB_START, onClick = { nav.tab = TAB_START },
                            icon = { Icon(Icons.Default.Home, null) }, label = { Text("Start", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) },
                            colors = colors, modifier = Modifier.testTag("tab-start"),
                        )
                        NavigationBarItem(
                            selected = nav.tab == TAB_LIBRARY, onClick = { if (nav.tab == TAB_LIBRARY) nav.libraryStack.retainAll(listOf(LibRoute.Root)); nav.tab = TAB_LIBRARY },
                            icon = { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Mediathek") },
                            colors = colors, modifier = Modifier.testTag("tab-library"),
                        )
                        NavigationBarItem(
                            selected = nav.tab == TAB_DOWNLOADER, onClick = { nav.tab = TAB_DOWNLOADER },
                            icon = {
                                BadgedBox(badge = { if (active + unseen > 0) Badge { Text("${active + unseen}") } }) {
                                    Icon(Icons.Default.Download, null)
                                }
                            },
                            label = { Text("Lieder downloaden") },
                            colors = colors, modifier = Modifier.testTag("tab-downloader"),
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (nav.tab) {
                    TAB_START -> StartScreen()
                    TAB_LIBRARY -> LibraryHost()
                    else -> BrowserScreen()
                }
            }
        }

        if (nav.showNowPlaying) NowPlayingSheet(onDismiss = { nav.showNowPlaying = false })
        if (nav.showSettings) SettingsSheet(onDismiss = { nav.showSettings = false })
        if (nav.showDownloads) DownloadsSheet(onDismiss = { nav.showDownloads = false })
        if (nav.showTransfer) TransferSheet(onDismiss = { nav.showTransfer = false })
        if (nav.showSound) SoundSheet(onDismiss = { nav.showSound = false })
        TrackActionsHost(nav.actions)
        UpdatePrompt()
    }
}

/** Fenster beim Öffnen der App, wenn eine neue Version bereitsteht – bleibt offen und zeigt Fortschritt und Fehler */
@androidx.compose.runtime.Composable
private fun UpdatePrompt() {
    val app = LocalApp.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val update by app.updates.state.collectAsState()
    val install by app.updates.install.collectAsState()
    val installing = install !is com.backewatsch.mp3player.update.UpdateChecker.Install.Idle
    val available = (update as? com.backewatsch.mp3player.update.UpdateChecker.State.Available)?.release
    var hidden by androidx.compose.runtime.remember(available?.build) {
        mutableStateOf(available == null || !app.updates.shouldPrompt(available))
    }
    if (!installing && (available == null || hidden)) return

    val release = when (val s = install) {
        is com.backewatsch.mp3player.update.UpdateChecker.Install.NeedsPermission -> s.release
        is com.backewatsch.mp3player.update.UpdateChecker.Install.Downloading -> s.release
        is com.backewatsch.mp3player.update.UpdateChecker.Install.Installing -> s.release
        is com.backewatsch.mp3player.update.UpdateChecker.Install.Failed -> s.release
        else -> available
    } ?: return
    fun close() { hidden = true; app.updates.clearInstallState() }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = {
            if (install is com.backewatsch.mp3player.update.UpdateChecker.Install.Downloading) return@AlertDialog
            close()
        },
        title = { Text(if (install is com.backewatsch.mp3player.update.UpdateChecker.Install.Failed) "Update fehlgeschlagen" else "Update verfügbar") },
        text = {
            androidx.compose.foundation.layout.Column {
                when (val s = install) {
                    is com.backewatsch.mp3player.update.UpdateChecker.Install.NeedsPermission ->
                        Text("Einmalig nötig: Erlaube ILLEGAL FREEDOM MUSIC, Apps zu installieren.\n\nTippe auf „Erlauben“, schalte „Aus dieser Quelle zulassen“ ein und komm zurück – das Update startet dann automatisch.",
                            modifier = Modifier.testTag("updateNeedsPermission"))
                    is com.backewatsch.mp3player.update.UpdateChecker.Install.Downloading -> {
                        Text("Version ${release.version} wird geladen … ${(s.progress * 100).toInt()} %")
                        androidx.compose.material3.LinearProgressIndicator(progress = { s.progress },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp), color = Accent)
                    }
                    is com.backewatsch.mp3player.update.UpdateChecker.Install.Installing ->
                        Text("Android installiert das Update – bitte im nächsten Fenster „Aktualisieren“ bzw. „Installieren“ tippen. Die App startet danach neu.")
                    is com.backewatsch.mp3player.update.UpdateChecker.Install.Failed ->
                        Text(s.message, modifier = Modifier.testTag("updateError"))
                    else -> Text("Version ${release.version} von ILLEGAL FREEDOM MUSIC ist da" +
                        (if (release.notes.isNotBlank()) ":\n\n" + release.notes.take(300) else ".") +
                        "\n\nDeine Musik und Playlists bleiben erhalten.")
                }
            }
        },
        confirmButton = {
            when (install) {
                is com.backewatsch.mp3player.update.UpdateChecker.Install.NeedsPermission ->
                    androidx.compose.material3.TextButton(onClick = { app.updates.openInstallPermissionSettings() },
                        modifier = Modifier.testTag("updateAllow")) { Text("Erlauben", color = Accent) }
                is com.backewatsch.mp3player.update.UpdateChecker.Install.Downloading,
                is com.backewatsch.mp3player.update.UpdateChecker.Install.Installing -> Unit
                is com.backewatsch.mp3player.update.UpdateChecker.Install.Failed ->
                    androidx.compose.material3.TextButton(onClick = { app.scope.launch { app.updates.install(release) } },
                        modifier = Modifier.testTag("updateRetry")) { Text("Nochmal versuchen", color = Accent) }
                else -> androidx.compose.material3.TextButton(
                    onClick = { app.scope.launch { app.updates.install(release) } },
                    modifier = Modifier.testTag("updateInstall"),
                ) { Text("Jetzt installieren", color = Accent) }
            }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                if (install is com.backewatsch.mp3player.update.UpdateChecker.Install.Failed && release.apkUrl != null) {
                    // Notlösung: im Browser laden und von Hand installieren
                    androidx.compose.material3.TextButton(onClick = {
                        runCatching {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(release.apkUrl)))
                        }
                    }) { Text("Im Browser laden") }
                }
                if (install !is com.backewatsch.mp3player.update.UpdateChecker.Install.Downloading) {
                    androidx.compose.material3.TextButton(onClick = {
                        if (install is com.backewatsch.mp3player.update.UpdateChecker.Install.Idle) app.updates.dismiss(release)
                        close()
                    }) { Text(if (install is com.backewatsch.mp3player.update.UpdateChecker.Install.Idle) "Später" else "Schließen") }
                }
            }
        },
    )
}
