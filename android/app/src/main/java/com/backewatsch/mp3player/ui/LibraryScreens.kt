package com.backewatsch.mp3player.ui

import android.widget.Toast
import androidx.compose.animation.togetherWith
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.backewatsch.mp3player.model.LibraryState.Companion.DOWNLOADS_PLAYLIST_ID
import com.backewatsch.mp3player.model.Playlist
import com.backewatsch.mp3player.model.formatTime
import com.backewatsch.mp3player.update.UpdateChecker
import kotlinx.coroutines.launch

sealed interface LibRoute {
    data object Root : LibRoute
    data object AllTracks : LibRoute
    data object Albums : LibRoute
    data object Artists : LibRoute
    data class Album(val name: String) : LibRoute
    data class Artist(val name: String) : LibRoute
    data class PlaylistDetail(val id: String) : LibRoute
    data object Favorites : LibRoute
    data object Stats : LibRoute
    data class Smart(val name: String) : LibRoute
    data object Duplicates : LibRoute
    data object Genres : LibRoute
    data class Genre(val name: String) : LibRoute
}

@Composable
fun LibraryHost() {
    val nav = LocalNav.current
    // Vor und zurück gleitet die Seite in die passende Richtung
    androidx.compose.animation.AnimatedContent(
        targetState = nav.libraryStack.last() to nav.libraryStack.size,
        transitionSpec = {
            val dir = if (targetState.second >= initialState.second) 1 else -1
            (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(260)) { it / 6 * dir } +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)))
                .togetherWith(androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(200)) { -it / 6 * dir } +
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)))
        },
        label = "library",
    ) { target ->
        when (val route = target.first) {
            LibRoute.Root -> MediathekRoot()
            LibRoute.AllTracks -> AllTracksScreen()
            LibRoute.Albums -> GroupScreen(albums = true)
            LibRoute.Artists -> GroupScreen(albums = false)
            is LibRoute.Album -> CollectionScreen(route.name, "Album")
            is LibRoute.Artist -> CollectionScreen(route.name, "Interpret")
            is LibRoute.PlaylistDetail -> PlaylistScreen(route.id)
            LibRoute.Favorites -> FavoritesScreen()
            LibRoute.Stats -> StatsScreen()
            is LibRoute.Smart -> SmartPlaylistScreen(route.name)
            LibRoute.Duplicates -> DuplicatesScreen()
            LibRoute.Genres -> GenresScreen()
            is LibRoute.Genre -> CollectionScreen(route.name, "Genre")
        }
    }
}

/** Kopfzeile: großer, linksbündiger Titel; „Zurück“ als runder Knopf */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    androidx.compose.material3.TopAppBar(
        title = {
            Text(title, style = if (onBack == null) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = if (onBack == null) 4.dp else 6.dp))
        },
        navigationIcon = {
            if (onBack != null) androidx.compose.material3.FilledTonalIconButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
            }
        },
        actions = { actions() },
        // Der Abstand zur Statusleiste kommt schon vom Rahmen der App – sonst wäre er doppelt
        windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

@Composable
internal fun PlayShuffleButtons(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.material3.Button(onClick = onPlay, modifier = Modifier.weight(1f).height(52.dp).testTag("playAllButton")) {
            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Abspielen", fontWeight = FontWeight.Bold)
        }
        FilledTonalButton(onClick = onShuffle, modifier = Modifier.weight(1f).height(52.dp)) {
            Icon(Icons.Default.Shuffle, null, tint = Accent); Spacer(Modifier.width(6.dp)); Text("Zufällig", color = Accent, fontWeight = FontWeight.Bold)
        }
    }
}
