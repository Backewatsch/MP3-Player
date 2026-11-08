package com.backewatsch.mp3player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
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

val LocalApp = staticCompositionLocalOf<App> { error("App fehlt") }
val LocalNav = staticCompositionLocalOf<AppNavigation> { error("Navigation fehlt") }

/** Tabs und Navigation innerhalb der Mediathek */
class AppNavigation {
    var tab by mutableStateOf(0)
    val libraryStack = mutableStateListOf<LibRoute>(LibRoute.Root)
    var showNowPlaying by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var showDownloads by mutableStateOf(false)
    val actions = TrackActionsState()

    fun push(route: LibRoute) {
        tab = 0
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

        BackHandler(enabled = nav.tab == 0 && nav.libraryStack.size > 1) { nav.pop() }
        BackHandler(enabled = nav.tab == 1) { app.browser.back() }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    MiniPlayer(onOpen = { nav.showNowPlaying = true })
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        val colors = NavigationBarItemDefaults.colors(selectedIconColor = Accent, selectedTextColor = Accent,
                            indicatorColor = Accent.copy(alpha = 0.18f))
                        NavigationBarItem(
                            selected = nav.tab == 0, onClick = { if (nav.tab == 0) nav.libraryStack.retainAll(listOf(LibRoute.Root)); nav.tab = 0 },
                            icon = { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Mediathek") },
                            colors = colors, modifier = Modifier.testTag("tab-library"),
                        )
                        NavigationBarItem(
                            selected = nav.tab == 1, onClick = { nav.tab = 1 },
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
                    0 -> LibraryHost()
                    else -> BrowserScreen()
                }
            }
        }

        if (nav.showNowPlaying) NowPlayingSheet(onDismiss = { nav.showNowPlaying = false })
        if (nav.showSettings) SettingsSheet(onDismiss = { nav.showSettings = false })
        if (nav.showDownloads) DownloadsSheet(onDismiss = { nav.showDownloads = false })
        TrackActionsHost(nav.actions)
    }
}
