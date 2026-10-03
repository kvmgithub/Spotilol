package com.project.lol.ui.screens

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import com.project.lol.searchEngine.GenericSearchEngine
import com.project.lol.searchEngine.SearchableFieldExtractor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.core.content.ContextCompat
import com.project.lol.R
import com.project.lol.offline.OfflineSong
import com.project.lol.offline.OfflineStore
import com.project.lol.service.OfflineMediaService
import com.project.lol.ui.components.SettingsDialog
import com.project.lol.util.BuildInfo
import compose.icons.TablerIcons
import compose.icons.tablericons.CloudOff
import compose.icons.tablericons.Logout
import compose.icons.tablericons.Menu2
import compose.icons.tablericons.Music
import compose.icons.tablericons.PlayerPause
import compose.icons.tablericons.PlayerPlay
import compose.icons.tablericons.PlayerSkipBack
import compose.icons.tablericons.PlayerSkipForward
import compose.icons.tablericons.Search
import compose.icons.tablericons.Settings
import compose.icons.tablericons.Trash
import compose.icons.tablericons.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineScreen(
    modifier: Modifier = Modifier,
    prefs: SharedPreferences,
    materialYou: Boolean,
    onMaterialYouChange: (Boolean) -> Unit,
    amoledTheme: Boolean,
    onAmoledThemeChange: (Boolean) -> Unit,
    hideTopBar: Boolean,
    onHideTopBarChange: (Boolean) -> Unit,
    landscapeMode: Boolean,
    onLandscapeModeChange: (Boolean) -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    paletteSeed: String?,
    onPaletteSeedChange: (String?) -> Unit,
    onConnectionModeChange: (String) -> Unit,
    onOfflineModeChange: (Boolean) -> Unit,
    onSaveProfile: (String, String) -> Unit,
    onLoadProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onClearCache: () -> Unit,
    onClearData: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var settingsDialogOpen by remember { mutableStateOf(false) }
    var showQuickMenu by remember { mutableStateOf(false) }

    var songs by remember { mutableStateOf<List<OfflineSong>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var currentIndex by remember { mutableIntStateOf(-1) }
    var playerSong by remember { mutableStateOf<OfflineSong?>(null) }
    var pendingDelete by remember { mutableStateOf<OfflineSong?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(0) }
    var scrubMs by remember { mutableIntStateOf(-1) }

    val mediaPlayer = remember { MediaPlayer() }
    var searchQuery by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<OfflineSong>?>(null) }

    val searchEngine = remember { GenericSearchEngine<OfflineSong>(maxResult = 100) }
    val songExtractor = remember {
        SearchableFieldExtractor<OfflineSong> { song ->
            arrayOf(song.title, song.artist, song.album, song.ytAlbum, song.ytArtist)
        }
    }

    LaunchedEffect(searchQuery, songs) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            searchResults = null
        } else {
            delay(300.milliseconds)
            searchResults = withContext(Dispatchers.Default) {
                searchEngine.filter(songs, q, songExtractor)
            }
        }
    }
    val visibleSongs = searchResults ?: songs

    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: ""
    }

    fun syncService() {
        val song = playerSong ?: return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OfflineMediaService::class.java).apply {
                    putExtra("title", song.title)
                    putExtra("artist", song.artist)
                    putExtra("album", song.album)
                    putExtra("duration", durationMs.toLong())
                    putExtra("playing", isPlaying)
                    putExtra("position", positionMs.toLong())
                    putExtra("coverPath", song.coverFile?.absolutePath)
                }
            )
        }
    }

    fun play(index: Int) {
        playAt(mediaPlayer, context, songs, index, { currentIndex = it }, { playerSong = it }, { isPlaying = it }, { durationMs = it }, { positionMs = it })
        syncService()
    }

    fun togglePlayPause() {
        runCatching {
            if (mediaPlayer.isPlaying) {
                mediaPlayer.pause()
                isPlaying = false
            } else if (durationMs > 0) {
                mediaPlayer.start()
                isPlaying = true
            }
        }
        syncService()
    }

    fun step(delta: Int) {
        if (songs.isEmpty()) return
        val next = ((currentIndex + delta) % songs.size + songs.size) % songs.size
        play(next)
    }

    fun seekTo(position: Long) {
        if (durationMs <= 0) return
        runCatching { mediaPlayer.seekTo(position.toInt()) }
        positionMs = position.toInt()
        OfflineMediaService.instance?.updatePosition(position)
    }

    fun stopAndClear() {
        runCatching {
            if (mediaPlayer.isPlaying) mediaPlayer.pause()
            mediaPlayer.reset()
        }
        isPlaying = false
        currentIndex = -1
        positionMs = 0
        durationMs = 0
        runCatching { context.stopService(Intent(context, OfflineMediaService::class.java)) }
    }

    fun performDelete(song: OfflineSong) {
        val index = songs.indexOfFirst { it.id == song.id && it.uri == song.uri }
        if (index == -1) return
        if (index == currentIndex) {
            stopAndClear()
        } else if (index < currentIndex) {
            currentIndex -= 1
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { OfflineStore.deleteSong(context, song) }
            songs = songs.filterNot { it.id == song.id && it.uri == song.uri }
            if (!ok) {
                Toast.makeText(context, resources.getString(R.string.offline_toast_could_not_delete_file), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun performDeleteAll() {
        val all = songs
        if (all.isEmpty()) return
        stopAndClear()
        searchQuery = ""
        searchResults = null
        songs = emptyList()
        scope.launch {
            val failed = withContext(Dispatchers.IO) {
                all.count { song -> !OfflineStore.deleteSong(context, song) }
            }
            if (failed > 0) {
                Toast.makeText(
                    context,
                    if (failed == 1) resources.getString(R.string.offline_toast_could_not_delete_one_file) else resources.getString(R.string.offline_toast_could_not_delete_files, failed),
                    Toast.LENGTH_SHORT
                ).show()
                songs = withContext(Dispatchers.IO) { OfflineStore.loadSongs(context) }
            }
        }
    }

    BackHandler(enabled = settingsDialogOpen || searchFocused || searchQuery.isNotBlank()) {
        when {
            settingsDialogOpen -> settingsDialogOpen = false
            searchFocused -> {
                focusManager.clearFocus()
                keyboardController?.hide()
            }
            else -> searchQuery = ""
        }
    }

    DisposableEffect(Unit) {
        val ctrl = object : OfflineMediaService.OfflineController {
            override fun onPlayFromSearch(query: String?) {
                if (query.isNullOrBlank()) {
                    if (!isPlaying) {
                        if (currentIndex >= 0) togglePlayPause() else if (songs.isNotEmpty()) play(0)
                    }
                } else {
                    val match = searchEngine.filter(songs, query.take(1024), songExtractor).firstOrNull()
                    val index = songs.indexOf(match)
                    if (index >= 0) play(index)
                }
            }
            override fun onPlayPause() = togglePlayPause()
            override fun onNext() = step(1)
            override fun onPrev() = step(-1)
            override fun onStop() {
                stopAndClear()
                runCatching { context.stopService(Intent(context, OfflineMediaService::class.java)) }
            }

            override fun onSeekTo(position: Long) = seekTo(position)
        }
        OfflineMediaService.controller = ctrl
        onDispose {
            if (OfflineMediaService.controller === ctrl) OfflineMediaService.controller = null
            runCatching { mediaPlayer.release() }
            runCatching { context.stopService(Intent(context, OfflineMediaService::class.java)) }
        }
    }

    DisposableEffect(mediaPlayer) {
        mediaPlayer.setOnCompletionListener {
            val index = currentIndex
            if (index in 0 until songs.lastIndex) {
                play(index + 1)
            } else {
                isPlaying = false
                positionMs = 0
                OfflineMediaService.instance?.updatePlaying(false, 0)
            }
        }
        onDispose { }
    }

    LaunchedEffect(Unit) {
        songs = withContext(Dispatchers.IO) { OfflineStore.loadSongs(context) }
        loading = false
    }

    LaunchedEffect(isPlaying, currentIndex) {
        while (isPlaying) {
            runCatching { positionMs = mediaPlayer.currentPosition }
            OfflineMediaService.instance?.updatePosition(positionMs.toLong())
            delay(500.milliseconds)
        }
    }

    SettingsDialog(
        visible = settingsDialogOpen,
        onClose = { settingsDialogOpen = false },
        prefs = prefs,
        materialYou = materialYou,
        onMaterialYouChange = onMaterialYouChange,
        amoledThemeState = amoledTheme,
        onAmoledThemeChange = onAmoledThemeChange,
        hideTopBar = hideTopBar,
        onHideTopBarChange = onHideTopBarChange,
        landscapeMode = landscapeMode,
        onLandscapeModeChange = onLandscapeModeChange,
        keepScreenOn = keepScreenOn,
        onKeepScreenOnChange = onKeepScreenOnChange,
        paletteSeed = paletteSeed,
        onPaletteSeedChange = onPaletteSeedChange,
        onConnectionModeChange = onConnectionModeChange,
        onOfflineModeChange = onOfflineModeChange,
        onSaveProfile = onSaveProfile,
        onLoadProfile = onLoadProfile,
        onDeleteProfile = onDeleteProfile,
        onClearCache = onClearCache,
        onClearData = onClearData,
        onDebugToggle = {},
        blockServiceWorker = prefs.getBoolean("BlockServiceWorker", true),
        onBlockServiceWorkerChange = { enabled ->
            prefs.edit().putBoolean("BlockServiceWorker", enabled).apply()
        }
    ) {
        Scaffold(
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = {
                if (!hideTopBar) {
                    CenterAlignedTopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.offline_app_name),
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.offline_version, versionName, BuildInfo.id),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { settingsDialogOpen = true }) {
                                Icon(
                                    imageVector = TablerIcons.Menu2,
                                    contentDescription = stringResource(R.string.offline_desc_settings),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = onExit) {
                                Icon(
                                    imageVector = TablerIcons.Logout,
                                    contentDescription = stringResource(R.string.offline_desc_exit_offline_mode),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                            actionIconContentColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp)
                            .padding(top = if (hideTopBar) 56.dp else 10.dp, bottom = 8.dp)
                    ) {
                        Text(
                            text = when {
                                loading -> stringResource(R.string.offline_status_loading)
                                searchQuery.isNotBlank() -> {
                                    val n = visibleSongs.size
                                    if (n == 0) {
                                        stringResource(R.string.offline_status_no_results_for, searchQuery.trim())
                                    } else if (n == 1) {
                                        stringResource(R.string.offline_status_one_result_for, searchQuery.trim())
                                    } else {
                                        stringResource(R.string.offline_status_results_for, n, searchQuery.trim())
                                    }
                                }
                                songs.isEmpty() -> stringResource(R.string.offline_status_no_downloads_yet)
                                songs.size == 1 -> stringResource(R.string.offline_status_one_song_available_offline)
                                else -> stringResource(R.string.offline_status_songs_available_offline, songs.size)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (!loading && songs.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.weight(1f)) {
                                    OfflineSearchField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        onSearchFocusChange = { searchFocused = it }
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                CompactIconButton(
                                    icon = TablerIcons.Trash,
                                    contentDescription = stringResource(R.string.offline_desc_delete_all_downloads),
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                    onClick = { confirmDeleteAll = true },
                                    boxSize = 40.dp,
                                    iconSize = 20.dp
                                )
                            }
                        }
                    }

                    when {
                        loading -> Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                        searchQuery.isNotBlank() && visibleSongs.isEmpty() -> Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = TablerIcons.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = stringResource(R.string.offline_empty_no_results),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.offline_empty_nothing_matches, searchQuery.trim()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        songs.isEmpty() -> Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = TablerIcons.CloudOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = stringResource(R.string.offline_empty_nothing_here_yet),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.offline_empty_download_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        else -> LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 112.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(visibleSongs, key = { "${it.id}-${it.uri}" }) { song ->
                                val index = songs.indexOfFirst { it.id == song.id && it.uri == song.uri }
                                OfflineSongRow(
                                    song = song,
                                    isCurrent = index == currentIndex,
                                    onClick = {
                                        if (index == currentIndex) {
                                            if (durationMs > 0 || mediaPlayer.isPlaying) {
                                                togglePlayPause()
                                            } else {
                                                play(index)
                                            }
                                        } else {
                                            play(index)
                                        }
                                    },
                                    onDelete = { pendingDelete = song }
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = currentIndex >= 0,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(220)) + fadeIn(tween(220)),
                    exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(180)) + fadeOut(tween(180))
                ) {
                    val song = playerSong
                    if (song != null) {
                        NowPlayingBar(
                            song = song,
                            playing = isPlaying,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            scrubMs = scrubMs,
                            onScrub = { scrubMs = it },
                            onScrubFinished = {
                                if (scrubMs >= 0) seekTo(scrubMs.toLong())
                                scrubMs = -1
                            },
                            onTogglePlay = { togglePlayPause() },
                            onPrev = { step(-1) },
                            onNext = { step(1) },
                            onClose = { stopAndClear() }
                        )
                    }
                }

                if (hideTopBar) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Box(
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .size(44.dp)
                                .shadow(6.dp, CircleShape)
                                .clip(CircleShape)
                                .clickable { showQuickMenu = !showQuickMenu },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_launcher_playstore),
                                contentDescription = stringResource(R.string.offline_desc_quick_actions),
                                tint = Color.Unspecified,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        if (showQuickMenu) {
                            Popup(
                                alignment = Alignment.TopCenter,
                                offset = IntOffset(0, with(LocalDensity.current) { 64.dp.toPx() }.toInt()),
                                onDismissRequest = { showQuickMenu = false }
                            ) {
                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                    )
                                ) {
                                    Column(modifier = Modifier.width(220.dp)) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    showQuickMenu = false
                                                    settingsDialogOpen = true
                                                }
                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = TablerIcons.Settings,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Text(
                                                text = stringResource(R.string.offline_menu_settings),
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Icon(
                                                imageVector = TablerIcons.Menu2,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            )
                                        }
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                                        )
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    showQuickMenu = false
                                                    onExit()
                                                }
                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = TablerIcons.Logout,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Text(
                                text = stringResource(R.string.offline_menu_exit_offline_mode),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
    }

    val songToDelete = pendingDelete
    if (songToDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            shape = RoundedCornerShape(28.dp),
            title = {
                Text(
                    text = stringResource(R.string.offline_dialog_delete_song_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.offline_dialog_delete_song_message, songToDelete.title, songToDelete.artist.ifBlank { stringResource(R.string.offline_unknown_artist) }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    performDelete(songToDelete)
                }) {
                    Text(
                        text = stringResource(R.string.offline_dialog_delete),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.offline_dialog_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    if (confirmDeleteAll) {
        val total = songs.size
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            shape = RoundedCornerShape(28.dp),
            title = {
                Text(
                    text = stringResource(R.string.offline_dialog_delete_all_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (total == 1) {
                        stringResource(R.string.offline_dialog_delete_all_message_one)
                    } else {
                        stringResource(R.string.offline_dialog_delete_all_message_many, total)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    performDeleteAll()
                }) {
                    Text(
                        text = stringResource(R.string.offline_dialog_delete_all),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) {
                    Text(stringResource(R.string.offline_dialog_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}
            }
        }
    }

private fun playAt(
    mediaPlayer: MediaPlayer,
    context: android.content.Context,
    songs: List<OfflineSong>,
    index: Int,
    setCurrentIndex: (Int) -> Unit,
    setPlayerSong: (OfflineSong) -> Unit,
    setPlaying: (Boolean) -> Unit,
    setDuration: (Int) -> Unit,
    setPosition: (Int) -> Unit,
) {
    val song = songs.getOrNull(index) ?: return
    runCatching {
        mediaPlayer.reset()
        mediaPlayer.setDataSource(context, song.uri)
        mediaPlayer.prepare()
        mediaPlayer.start()
        setCurrentIndex(index)
        setPlayerSong(song)
        setPlaying(true)
        setDuration(mediaPlayer.duration)
        setPosition(0)
    }.onFailure {
        setPlaying(false)
    }
}

@Composable
private fun CompactIconButton(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    boxSize: Dp = 32.dp,
    iconSize: Dp = 18.dp
) {
    Box(
        modifier = modifier
            .size(boxSize)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
private fun OfflineSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearchFocusChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Icon(
                imageVector = TablerIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { onSearchFocusChange(it.isFocused) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.offline_search_placeholder),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    inner()
                }
            )
            if (value.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                CompactIconButton(
                    icon = TablerIcons.X,
                    contentDescription = stringResource(R.string.offline_desc_clear_search),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { onValueChange("") },
                    boxSize = 28.dp,
                    iconSize = 15.dp
                )
            }
        }
    }
}

@Composable
private fun SeekBar(
    positionMs: Int,
    durationMs: Int,
    scrubbing: Boolean,
    onScrub: (Int) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val total = durationMs.coerceAtLeast(1)
    val fraction = positionMs.coerceIn(0, total).toFloat() / total.toFloat()
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val progressColor = MaterialTheme.colorScheme.primary
    var widthPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }

    fun msAt(x: Float): Int =
        if (widthPx <= 0) 0 else ((x / widthPx) * total).toInt().coerceIn(0, total)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(22.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(total, widthPx) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        onScrub(msAt(offset.x))
                    },
                    onDragEnd = {
                        dragging = false
                        onScrubFinished()
                    },
                    onDragCancel = {
                        dragging = false
                        onScrubFinished()
                    }
                ) { change, _ ->
                    change.consume()
                    onScrub(msAt(change.position.x))
                }
            }
            .pointerInput(total, widthPx) {
                detectTapGestures { offset ->
                    onScrub(msAt(offset.x))
                    onScrubFinished()
                }
            }
            .drawBehind {
                val centerY = size.height / 2f
                val trackHeight = 3.dp.toPx()
                val radius = trackHeight / 2f
                val progressWidth = size.width * fraction.coerceIn(0f, 1f)
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(0f, centerY - radius),
                    size = Size(size.width, trackHeight),
                    cornerRadius = CornerRadius(radius, radius)
                )
                drawRoundRect(
                    color = progressColor,
                    topLeft = Offset(0f, centerY - radius),
                    size = Size(progressWidth, trackHeight),
                    cornerRadius = CornerRadius(radius, radius)
                )
                val thumbRadius = (if (dragging || scrubbing) 6.dp else 4.dp).toPx()
                drawCircle(
                    color = progressColor,
                    radius = thumbRadius,
                    center = Offset(
                        x = progressWidth.coerceIn(thumbRadius, size.width - thumbRadius),
                        y = centerY
                    )
                )
            }
    )
}

@Composable
private fun OfflineSongRow(
    song: OfflineSong,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp)
    ) {
        SongCover(song = song, size = 44.dp, corner = 8.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val unknownArtist = stringResource(R.string.offline_unknown_artist)
            val explicitLabel = stringResource(R.string.offline_label_explicit)
            val subtitle = buildString {
                append(song.artist.ifBlank { unknownArtist })
                song.album.ifBlank { "" }.takeIf { it.isNotBlank() }?.let { append(" • $it") }
                song.durationSec?.takeIf { it > 0 }?.let { append(" • ${formatSeconds(it)}") }
                if (song.explicit) append(" • $explicitLabel")
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        CompactIconButton(
            icon = TablerIcons.Trash,
            contentDescription = stringResource(R.string.offline_desc_delete),
            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
            onClick = onDelete,
            boxSize = 30.dp,
            iconSize = 17.dp
        )
    }
}

@Composable
private fun SongCover(song: OfflineSong, size: Dp, corner: Dp) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var bitmap by remember(song.id, song.uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(song.id, song.uri) {
        bitmap = withContext(Dispatchers.IO) { decodeCover(context, song) }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = TablerIcons.Music,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(size / 2)
            )
        }
    }
}

private fun decodeCover(context: android.content.Context, song: OfflineSong): Bitmap? {
    song.coverFile?.let { file ->
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / 256)
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }.getOrNull()?.let { return it }
    }
    return runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, song.uri)
            retriever.embeddedPicture?.let { data ->
                BitmapFactory.decodeByteArray(data, 0, data.size)
            }
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()
}

@Composable
private fun NowPlayingBar(
    song: OfflineSong,
    playing: Boolean,
    positionMs: Int,
    durationMs: Int,
    scrubMs: Int,
    onScrub: (Int) -> Unit,
    onScrubFinished: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    val scrubbing = scrubMs >= 0
    val shownPosition = if (scrubbing) scrubMs else positionMs

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 10.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SongCover(song = song, size = 40.dp, corner = 8.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = song.artist.ifBlank { stringResource(R.string.offline_unknown_artist) },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                CompactIconButton(
                    icon = TablerIcons.PlayerSkipBack,
                    contentDescription = stringResource(R.string.offline_desc_previous),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onPrev,
                    iconSize = 17.dp
                )
                CompactIconButton(
                    icon = if (playing) TablerIcons.PlayerPause else TablerIcons.PlayerPlay,
                    contentDescription = if (playing) stringResource(R.string.offline_desc_pause) else stringResource(R.string.offline_desc_play),
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = onTogglePlay,
                    boxSize = 36.dp,
                    iconSize = 22.dp
                )
                CompactIconButton(
                    icon = TablerIcons.PlayerSkipForward,
                    contentDescription = stringResource(R.string.offline_desc_next),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onNext,
                    iconSize = 17.dp
                )
                CompactIconButton(
                    icon = TablerIcons.X,
                    contentDescription = stringResource(R.string.offline_desc_close_player),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onClose,
                    iconSize = 16.dp
                )
            }
            Spacer(Modifier.height(2.dp))
            SeekBar(
                positionMs = shownPosition,
                durationMs = durationMs,
                scrubbing = scrubbing,
                onScrub = onScrub,
                onScrubFinished = onScrubFinished,
                modifier = Modifier.padding(end = 4.dp)
            )
            Row(modifier = Modifier.padding(end = 4.dp)) {
                Text(
                    text = formatTime(shownPosition),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatTime(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatTime(ms: Int): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

private fun formatSeconds(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
