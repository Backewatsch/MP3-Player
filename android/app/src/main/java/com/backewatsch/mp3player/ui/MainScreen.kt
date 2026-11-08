package com.backewatsch.mp3player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
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
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    MiniPlayer(onOpen = { nav.showNowPlaying = true })
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        val colors = NavigationBarItemDefaults.colors(selectedIconColor = Accent, selectedTextColor = Accent,
                            indicatorColor = Accent.copy(alpha = 0.18f))
                        NavigationBarItem(
                            selected = nav.tab == TAB_START, onClick = { nav.tab = TAB_START },
                            icon = { Icon(Icons.Default.Home, null) }, label = { Text("Start") },
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
        TrackActionsHost(nav.actions)
        UpdatePrompt()
    }
}

/** Fenster beim Öffnen der App, wenn eine neue Version bereitsteht */
@androidx.compose.runtime.Composable
private fun UpdatePrompt() {
    val app = LocalApp.current
    val update by app.updates.state.collectAsState()
    val available = (update as? com.backewatsch.mp3player.update.UpdateChecker.State.Available)?.release ?: return
    var hidden by androidx.compose.runtime.remember(available.build) { mutableStateOf(!app.updates.shouldPrompt(available)) }
    if (hidden) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { hidden = true },
        title = { Text("Update verfügbar") },
        text = {
            Text("Version ${available.version} von ILLEGAL FREEDOM MUSIC ist da" +
                (if (available.notes.isNotBlank()) ":\n\n" + available.notes.take(300) else ".") +
                "\n\nDeine Musik und Playlists bleiben erhalten.")
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { hidden = true; app.scope.launch { app.updates.install(available) } },
                modifier = Modifier.testTag("updateInstall"),
            ) { Text("Jetzt installieren", color = Accent) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { app.updates.dismiss(available); hidden = true }) { Text("Später") }
        },
    )
}
