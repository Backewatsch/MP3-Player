package com.backewatsch.mp3player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
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
/** Lieblingssongs (IDs) – einmal zentral beobachtet statt in jeder Liedzeile */
val LocalFavorites = androidx.compose.runtime.compositionLocalOf<Set<String>> { emptySet() }

/** Tabs und Navigation innerhalb der Mediathek */
const val TAB_START = 0
const val TAB_LIBRARY = 1
const val TAB_EDITOR = 2
/** Suche – kein eigener Tab, liegt über dem gewählten */
const val TAB_SEARCH = -1

class AppNavigation {
    var tab by mutableStateOf(0)
    val libraryStack = mutableStateListOf<LibRoute>(LibRoute.Root)
    var showNowPlaying by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var showDownloads by mutableStateOf(false)
    var showTransfer by mutableStateOf(false)
    var showSound by mutableStateOf(false)
    /** Jahresrückblick, der gerade gezeigt wird */
    var recapYear by mutableStateOf<Int?>(null)
    /** Lied, das im Tab „Bearbeiten“ geöffnet ist */
    var editTrackId by mutableStateOf<String?>(null)

    fun openEditor(track: com.backewatsch.mp3player.model.Track) {
        showNowPlaying = false
        leaveSearch()
        editTrackId = track.id
        tab = TAB_EDITOR
    }
    val actions = TrackActionsState()

    fun push(route: LibRoute) {
        // Treffer aus der Suche geöffnet → „Zurück“ führt später wieder in die Suche
        if (showSearch) { searchDepth = libraryStack.size; showSearch = false }
        tab = TAB_LIBRARY
        showNowPlaying = false
        libraryStack.add(route)
    }

    fun pop(): Boolean {
        if (libraryStack.size <= 1) return false
        libraryStack.removeAt(libraryStack.lastIndex)
        if (searchDepth != null && libraryStack.size == searchDepth) { searchDepth = null; showSearch = true }
        return true
    }

    /** Suche liegt über dem Tab, von dem aus sie geöffnet wurde */
    var showSearch by mutableStateOf(false)
    /** Text im Suchfeld – bleibt erhalten, wenn man aus einem Treffer zurückkommt */
    var searchQuery by mutableStateOf("")
    private var searchOriginTab by mutableStateOf(TAB_START)
    private var searchDepth: Int? = null
    /** Tab, der unten als gewählt erscheint */
    val shownTab: Int get() = if (showSearch) searchOriginTab else tab

    /** Suche öffnen (rundes Lupen-Symbol auf Start und Mediathek) */
    fun openSearch() {
        searchOriginTab = tab
        searchQuery = ""
        searchDepth = null
        showSearch = true
    }

    /** Suche schließen: zurück auf den Tab, von dem aus sie geöffnet wurde */
    fun closeSearch() {
        showSearch = false
        searchDepth = null
        tab = searchOriginTab
    }

    /** Suche verlassen, weil woanders hin gewechselt wird */
    fun leaveSearch() { showSearch = false; searchDepth = null }
}

@Composable
fun MainScreen() {
    val app = LocalContext.current.app
    val nav = remember { AppNavigation() }
    val favoriteIds by app.library.state.collectAsState().let { lib ->
        androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { lib.value.favorites.toSet() } }
    }
    CompositionLocalProvider(LocalApp provides app, LocalNav provides nav, LocalFavorites provides favoriteIds) {
        BackHandler(enabled = nav.tab == TAB_LIBRARY && nav.libraryStack.size > 1) { nav.pop() }
        BackHandler(enabled = nav.showSearch) { nav.closeSearch() }

        // Hinweise erscheinen in der App, solange kein Blatt über der Hauptseite liegt
        androidx.compose.runtime.DisposableEffect(Unit) {
            Notice.blocked = { nav.showNowPlaying || nav.showSettings || nav.showDownloads || nav.showTransfer || nav.showSound || nav.recapYear != null }
            onDispose { Notice.blocked = { true } }
        }

        // Navigation blendet beim Herunterscrollen aus und kommt beim Hochscrollen wieder
        var navVisible by remember { mutableStateOf(true) }
        val hideAfter = with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.toPx() }
        val scrollWatcher = remember(hideAfter) {
            object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                var down = 0f
                /** Nur echtes Scrollen mit dem Finger zählt – nicht, wenn die App selbst eine Liste verschiebt */
                var touching = false
                override fun onPostScroll(
                    consumed: androidx.compose.ui.geometry.Offset,
                    available: androidx.compose.ui.geometry.Offset,
                    source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
                ): androidx.compose.ui.geometry.Offset {
                    if (!touching) return androidx.compose.ui.geometry.Offset.Zero
                    if (consumed.y > 0f) { down = 0f; navVisible = true }
                    else if (consumed.y < 0f) { down -= consumed.y; if (down > hideAfter) navVisible = false }
                    return androidx.compose.ui.geometry.Offset.Zero
                }
            }
        }
        androidx.compose.runtime.LaunchedEffect(nav.tab, nav.libraryStack.size, nav.showSearch, nav.editTrackId) {
            scrollWatcher.down = 0f; navVisible = true
        }

        Scaffold(
            modifier = Modifier.cyberBackground(),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            // Bei durchsichtigem Hintergrund die Textfarbe ausdrücklich setzen (sonst Schwarz)
            contentColor = MaterialTheme.colorScheme.onBackground,
            bottomBar = {
                Column {
                    MiniPlayer(onOpen = { nav.showNowPlaying = true })
                    androidx.compose.animation.AnimatedVisibility(
                        visible = navVisible,
                        enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
                    ) { FloatingNavBar(nav) }
                    // Ohne Leiste bleibt der Mini-Player über der Gestenleiste des Handys
                    if (!navVisible) androidx.compose.foundation.layout.Spacer(Modifier.navigationBarsPadding().height(6.dp))
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).nestedScroll(scrollWatcher)
                .pointerInput(scrollWatcher) {
                    // Merken, ob gerade ein Finger auf dem Bildschirm liegt (ohne die Berührung zu verbrauchen)
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            scrollWatcher.touching = event.changes.any { it.pressed }
                        }
                    }
                }) {
                // Tabs blenden weich ineinander über
                androidx.compose.animation.AnimatedContent(
                    targetState = if (nav.showSearch) TAB_SEARCH else nav.tab,
                    transitionSpec = {
                        (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                            androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(220), initialScale = 0.97f))
                            .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)))
                    },
                    label = "tabs",
                ) { tab ->
                    when (tab) {
                        TAB_SEARCH -> SearchScreen()
                        TAB_START -> StartScreen()
                        TAB_LIBRARY -> LibraryHost()
                        else -> EditorScreen()
                    }
                }
                NoticeHost(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 8.dp))
            }
        }

        if (nav.showNowPlaying) NowPlayingSheet(onDismiss = { nav.showNowPlaying = false })
        if (nav.showSettings) SettingsSheet(onDismiss = { nav.showSettings = false })
        if (nav.showDownloads) DownloadsSheet(onDismiss = { nav.showDownloads = false })
        if (nav.showTransfer) TransferSheet(onDismiss = { nav.showTransfer = false })
        if (nav.showSound) SoundSheet(onDismiss = { nav.showSound = false })
        nav.recapYear?.let { year -> RecapStory(year, onClose = { nav.recapYear = null }) }
        TrackActionsHost(nav.actions)
        UpdatePrompt()
    }
}

/** Schwebende Leiste unten: alle Tabs mit Beschriftung, der gewählte als farbige Pille */
@Composable
private fun FloatingNavBar(nav: AppNavigation) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.shadow(16.dp, CircleShape).clip(CircleShape).background(CyberPanelHigh)
                .border(1.dp, CyberLine, CircleShape).padding(6.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            NavPill("Start", Icons.Default.Home, nav.shownTab == TAB_START, "tab-start") { nav.leaveSearch(); nav.tab = TAB_START }
            NavPill("Mediathek", Icons.Default.LibraryMusic, nav.shownTab == TAB_LIBRARY, "tab-library") {
                val wasSearching = nav.showSearch
                nav.leaveSearch()
                if (nav.tab == TAB_LIBRARY && !wasSearching) nav.libraryStack.retainAll(listOf(LibRoute.Root)); nav.tab = TAB_LIBRARY
            }
            NavPill("Bearbeiten", Icons.Default.ContentCut, nav.shownTab == TAB_EDITOR, "tab-editor") {
                nav.leaveSearch()
                if (nav.tab == TAB_EDITOR) nav.editTrackId = null; nav.tab = TAB_EDITOR
            }
        }
    }
}

@Composable
private fun NavPill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, tag: String, onClick: () -> Unit) {
    val bg by androidx.compose.animation.animateColorAsState(if (selected) Accent else androidx.compose.ui.graphics.Color.Transparent, label = "navBg")
    val fg by androidx.compose.animation.animateColorAsState(
        if (selected) androidx.compose.ui.graphics.Color(0xFF04202E) else MaterialTheme.colorScheme.onSurfaceVariant, label = "navFg")
    Column(
        Modifier.width(88.dp).height(56.dp).clip(CircleShape).background(bg)
            .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick)
            .testTag(tag),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp))
        Text(label, color = fg, maxLines = 1, style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium)
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
