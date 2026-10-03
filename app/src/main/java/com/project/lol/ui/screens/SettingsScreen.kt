package com.project.lol.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.webkit.WebViewCompat
import com.project.lol.R
import com.project.lol.offline.DownloadFolder
import com.project.lol.offline.DownloadFormat
import com.project.lol.offline.DownloadPrefs
import com.project.lol.profile.ProfileManager
import com.project.lol.proxy.LocalProxyManager
import com.project.lol.service.MediaNotificationService
import com.project.lol.ui.components.ChangelogDialog
import com.project.lol.ui.theme.SpotifyTheme
import com.project.lol.util.BuildInfo
import com.project.lol.util.GitHubApi
import com.project.lol.util.GitHubRelease
import com.project.lol.util.LogEntry
import com.project.lol.util.LogFilter
import com.project.lol.util.LogLevel
import com.project.lol.util.Logger
import com.project.lol.util.MarkdownText
import com.project.lol.webview.helpers.LyricsTheme
import compose.icons.TablerIcons
import compose.icons.tablericons.AlertTriangle
import compose.icons.tablericons.ArrowsMinimize
import compose.icons.tablericons.ArrowsSort
import compose.icons.tablericons.ArrowsUpDown
import compose.icons.tablericons.BrandDiscord
import compose.icons.tablericons.BrightnessUp
import compose.icons.tablericons.Brush
import compose.icons.tablericons.Bug
import compose.icons.tablericons.Car
import compose.icons.tablericons.Check
import compose.icons.tablericons.ChevronRight
import compose.icons.tablericons.Click
import compose.icons.tablericons.CloudOff
import compose.icons.tablericons.Code
import compose.icons.tablericons.ColorSwatch
import compose.icons.tablericons.DeviceMobile
import compose.icons.tablericons.Download
import compose.icons.tablericons.EyeOff
import compose.icons.tablericons.Flask
import compose.icons.tablericons.Folder
import compose.icons.tablericons.InfoCircle
import compose.icons.tablericons.Language
import compose.icons.tablericons.Link
import compose.icons.tablericons.Moon
import compose.icons.tablericons.Palette
import compose.icons.tablericons.PlayerPlay
import compose.icons.tablericons.Playlist
import compose.icons.tablericons.Power
import compose.icons.tablericons.RotateClockwise2
import compose.icons.tablericons.Shield
import compose.icons.tablericons.Trash
import compose.icons.tablericons.TrashOff
import compose.icons.tablericons.User
import compose.icons.tablericons.UserPlus
import compose.icons.tablericons.WaveSine
import compose.icons.tablericons.X
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PalettePresets = listOf(
    R.string.settings_palette_default to null,
    R.string.settings_palette_spotify_green to "#1DB954",
    R.string.settings_palette_purple to "#BB86FC",
    R.string.settings_palette_blue to "#2196F3",
    R.string.settings_palette_red to "#E53935",
    R.string.settings_palette_orange to "#FB8C00",
    R.string.settings_palette_pink to "#EC407A",
    R.string.settings_palette_teal to "#26A69A",
    R.string.settings_palette_yellow to "#FDD835",
    R.string.settings_palette_cyan to "#00BCD4"
)

private fun parsePaletteColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
}

private fun formatHex(color: Color): String {
    val r = (color.red * 255f).roundToInt()
    val g = (color.green * 255f).roundToInt()
    val b = (color.blue * 255f).roundToInt()
    return "#" + String.format(Locale.US, "%02X%02X%02X", r, g, b)
}
private const val DEBUG_UNLOCK_TAPS = 5

private enum class SettingsTab(@StringRes val labelRes: Int) {
    Appearance(R.string.settings_tab_appearance),
    Playback(R.string.settings_tab_playback),
    Content(R.string.settings_tab_content),
    Advanced(R.string.settings_tab_advanced),
    About(R.string.settings_tab_about)
}



@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(
    modifier: Modifier = Modifier,
    prefs: SharedPreferences,
    materialYou: Boolean,
    onMaterialYouChange: (Boolean) -> Unit,
    amoledThemeState: Boolean,
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
    onDebugToggle: (Boolean) -> Unit = {},
    blockServiceWorker: Boolean,
    onBlockServiceWorkerChange: (Boolean) -> Unit
) {
    var autoplayMode by remember { mutableStateOf(prefs.getString("APlayMode", "disabled") ?: "disabled") }
    var takeControl by remember { mutableStateOf(prefs.getBoolean("TakeControl", true)) }
    var andAuto by remember { mutableStateOf(prefs.getBoolean("AndAuto", true)) }
    var closeNowPlay by remember { mutableStateOf(prefs.getBoolean("CloseNowPlay", true)) }
    var guiMode by remember { mutableStateOf(prefs.getString("GuiMode", "csshack") ?: "csshack") }
    var customCss by remember { mutableStateOf(prefs.getString("CustomCss", "") ?: "") }
    var amoledTheme by remember { mutableStateOf(amoledThemeState) }
    var swipeStop by remember { mutableStateOf(prefs.getBoolean("SwipeStop", true)) }
    var btAutoPause by remember { mutableStateOf(prefs.getBoolean("BtAutoPause", false)) }
    var btAutoResume by remember { mutableStateOf(prefs.getBoolean("BtAutoResume", false)) }
    var hpAutoResume by remember { mutableStateOf(prefs.getBoolean("HpAutoResume", false)) }
    var playerMode by remember { mutableStateOf(prefs.getString("PlayerMode", "spotilol") ?: "spotilol") }
    var connectionMode by remember { mutableStateOf(prefs.getString("ConnectionMode", "normal") ?: "normal") }
    var offlineMode by remember { mutableStateOf(prefs.getBoolean("OfflineMode", false)) }
    var blockSW by remember { mutableStateOf(blockServiceWorker) }
    var hideEmptyPlayer by remember { mutableStateOf(prefs.getBoolean("HideEmptyPlayer", false)) }
    var playlistSortEnabled by remember { mutableStateOf(prefs.getBoolean("PlaylistSortEnabled", true)) }
    var showScrollbar by remember { mutableStateOf(prefs.getBoolean("ShowScrollbar", true)) }
    var lyricsStyle by remember { mutableStateOf(prefs.getString("LyricsStyle", LyricsTheme.DEFAULT_STYLE) ?: LyricsTheme.DEFAULT_STYLE) }

    val context = LocalContext.current
    val resources = LocalResources.current
    var profiles by remember { mutableStateOf(ProfileManager.getProfiles(context)) }

    var showConnectionModeDialog by remember { mutableStateOf(false) }
    var showSaveAccountDialog by remember { mutableStateOf(false) }
    var pendingCookies by remember { mutableStateOf<String?>(null) }
    var accountNameInput by remember { mutableStateOf("") }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showAutoPlayDialog by remember { mutableStateOf(false) }
    var showPlayerModeDialog by remember { mutableStateOf(false) }
    var showGuiModeDialog by remember { mutableStateOf(false) }
    var showCustomCssDialog by remember { mutableStateOf(false) }
    var showPaletteDialog by remember { mutableStateOf(false) }
    var showChangelogDialog by remember { mutableStateOf(false) }
    var loggingOn by remember { mutableStateOf(Logger.isEnabled()) }
    var showDevlogDialog by remember { mutableStateOf(false) }
    var debugUnlocked by remember { mutableStateOf(prefs.getBoolean("DebugUnlocked", false)) }
    var debugTapCount by remember { mutableStateOf(0) }
    var showLyricsStyleDialog by remember { mutableStateOf(false) }
    var showFormatDialog by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var dlFormat by remember {
        mutableStateOf(DownloadPrefs.format(context))
    }
    var dlFolderPath by remember {
        mutableStateOf(DownloadPrefs.folderDisplayPath(context))
    }
    var dlFolderLabel by remember {
        mutableStateOf(DownloadPrefs.folderLabel(context))
    }
    fun refreshFolderState() {
        dlFolderPath = DownloadPrefs.folderDisplayPath(context)
        dlFolderLabel = DownloadPrefs.folderLabel(context)
    }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (DownloadFolder.persist(context, uri)) {
            DownloadPrefs.setFolder(context, uri)
            refreshFolderState()
        } else {
            Toast.makeText(context, resources.getString(R.string.settings_folder_access_error), Toast.LENGTH_SHORT).show()
        }
    }
    var dlTags by remember { mutableStateOf(DownloadPrefs.writeTags(context)) }

    fun onDebugSecretTap() {
        if (debugUnlocked) return
        debugTapCount++
        val remaining = DEBUG_UNLOCK_TAPS - debugTapCount
        if (remaining <= 0) {
            debugTapCount = 0
            debugUnlocked = true
            prefs.edit().putBoolean("DebugUnlocked", true).apply()
            Toast.makeText(context, resources.getString(R.string.settings_debug_unlocked), Toast.LENGTH_SHORT).show()
        } else {
            val message = if (remaining == 1) {
                resources.getString(R.string.settings_debug_tap_one_left)
            } else {
                resources.getString(R.string.settings_debug_taps_left, remaining)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun onDebugSecretHold() {
        debugTapCount = 0
        if (debugUnlocked) {
            debugUnlocked = false
            prefs.edit().putBoolean("DebugUnlocked", false).apply()
            if (loggingOn) {
                loggingOn = false
                Logger.setEnabled(context, false)
                onDebugToggle(false)
            }
            Toast.makeText(context, resources.getString(R.string.settings_debug_hidden), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, resources.getString(R.string.settings_debug_is_hidden), Toast.LENGTH_SHORT).show()
        }
    }

    var settingsTab by remember { mutableStateOf(SettingsTab.Appearance) }
    val tabScrollStates = SettingsTab.entries.map { rememberScrollState() }
    val scrollState = tabScrollStates[settingsTab.ordinal]

    val pkg = remember { WebViewCompat.getCurrentWebViewPackage(context) }
    val packageInfo = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
    }
    val appVersionName = packageInfo?.versionName ?: "1.0.0"

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        SecondaryScrollableTabRow(
            selectedTabIndex = settingsTab.ordinal,
            containerColor = Color.Transparent,
            edgePadding = 4.dp,
            divider = {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
            }
        ) {
            SettingsTab.entries.forEach { tab ->
                Tab(
                    selected = tab == settingsTab,
                    onClick = { settingsTab = tab },
                    text = {
                        Text(
                            text = stringResource(tab.labelRes),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (settingsTab == SettingsTab.Appearance) {
                SettingSectionCard(
                    title = stringResource(R.string.settings_section_appearance),
                    icon = TablerIcons.Palette
                ) {
                    val guiLabel = when (guiMode) {
                        "csshack" -> stringResource(R.string.settings_gui_mode_css_js)
                        "bigwindow" -> stringResource(R.string.settings_gui_mode_wide)
                        "none" -> stringResource(R.string.settings_gui_mode_none)
                        else -> stringResource(R.string.settings_gui_mode_css_js)
                    }
                    SettingTile(
                        title = stringResource(R.string.settings_gui_hack_mode),
                        subtitle = guiLabel,
                        icon = TablerIcons.Palette,
                        onClick = { showGuiModeDialog = true }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    SettingTile(
                        title = stringResource(R.string.settings_custom_css),
                        subtitle = if (customCss.isBlank()) stringResource(R.string.settings_custom_css_none) else customCss,
                        icon = TablerIcons.Code,
                        onClick = { showCustomCssDialog = true }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    SettingSwitchTile(
                        title = stringResource(R.string.settings_material_you),
                        subtitle = stringResource(R.string.settings_material_you_subtitle),
                        icon = TablerIcons.ColorSwatch,
                        checked = materialYou,
                        onCheckedChange = { enabled ->
                            onMaterialYouChange(enabled)
                            prefs.edit().putBoolean("MaterialYou", enabled).apply()
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    val accentLabel = when {
                        materialYou -> stringResource(R.string.settings_accent_dynamic)
                        paletteSeed.isNullOrBlank() -> stringResource(R.string.settings_accent_default)
                        else -> stringResource(R.string.settings_accent_custom, paletteSeed)
                    }
                    SettingTile(
                        title = stringResource(R.string.settings_accent_color),
                        subtitle = accentLabel,
                        icon = TablerIcons.ColorSwatch,
                        onClick = { showPaletteDialog = true }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    SettingSwitchTile(
                        title = stringResource(R.string.settings_amoled_theme),
                        subtitle = stringResource(R.string.settings_amoled_theme_subtitle),
                        icon = TablerIcons.Moon,
                        checked = amoledTheme,
                        onCheckedChange = { enabled ->
                            amoledTheme = enabled
                            onAmoledThemeChange(enabled)
                            prefs.edit().putBoolean("AmoledTheme", enabled).apply()
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    SettingSwitchTile(
                        title = stringResource(R.string.settings_hide_top_bar),
                        subtitle = stringResource(R.string.settings_hide_top_bar_subtitle),
                        icon = TablerIcons.EyeOff,
                        checked = hideTopBar,
                        onCheckedChange = { enabled ->
                            onHideTopBarChange(enabled)
                            prefs.edit().putBoolean("HideTopBar", enabled).apply()
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    SettingSwitchTile(
                        title = stringResource(R.string.settings_landscape_mode),
                        subtitle = stringResource(R.string.settings_landscape_mode_subtitle),
                        icon = TablerIcons.RotateClockwise2,
                        checked = landscapeMode,
                        onCheckedChange = { enabled ->
                            onLandscapeModeChange(enabled)
                            prefs.edit().putBoolean("LandscapeMode", enabled).apply()
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    SettingSwitchTile(
                        title = stringResource(R.string.settings_keep_screen_on),
                        subtitle = stringResource(R.string.settings_keep_screen_on_subtitle),
                        icon = TablerIcons.BrightnessUp,
                        checked = keepScreenOn,
                        onCheckedChange = { enabled ->
                            onKeepScreenOnChange(enabled)
                            prefs.edit().putBoolean("KeepScreenOn", enabled).apply()
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    SettingSwitchTile(
                        title = stringResource(R.string.settings_playlist_scrollbar),
                        subtitle = stringResource(R.string.settings_playlist_scrollbar_subtitle),
                        icon = TablerIcons.ArrowsUpDown,
                        checked = showScrollbar,
                        onCheckedChange = { enabled ->
                            showScrollbar = enabled
                            prefs.edit().putBoolean("ShowScrollbar", enabled).apply()
                            MediaNotificationService.webView?.evaluateJavascript(
                                "if(window.splScrollbar){window.splScrollbar($enabled)}else{window.__splShowScrollbar=$enabled}",
                                null
                            )
                        }
                    )
                }
            }

            if (settingsTab == SettingsTab.Playback) {
                SettingSectionCard(
                    title = stringResource(R.string.settings_section_player),
                    icon = TablerIcons.PlayerPlay
                ) {
                    val autoplayLabel = when (autoplayMode) {
                        "disabled" -> stringResource(R.string.settings_autoplay_disabled)
                        "onetime" -> stringResource(R.string.settings_autoplay_onetime)
                        "permanent" -> stringResource(R.string.settings_autoplay_permanent)
                        else -> stringResource(R.string.settings_autoplay_onetime)
                    }
                    SettingTile(
                        title = stringResource(R.string.settings_autoplay_mode),
                        subtitle = autoplayLabel,
                        icon = TablerIcons.PlayerPlay,
                        onClick = { showAutoPlayDialog = true }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    val playerModeLabel = when (playerMode) {
                        "spotilol" -> stringResource(R.string.settings_player_spotilol)
                        "original" -> stringResource(R.string.settings_player_original)
                        else -> stringResource(R.string.settings_player_spotilol)
                    }
                    SettingTile(
                        title = stringResource(R.string.settings_player_mode),
                        subtitle = playerModeLabel,
                        icon = TablerIcons.PlayerPlay,
                        onClick = { showPlayerModeDialog = true }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    SettingSwitchTile(
                        title = stringResource(R.string.settings_hide_empty_player),
                        subtitle = stringResource(R.string.settings_hide_empty_player_subtitle),
                        icon = TablerIcons.EyeOff,
                        checked = hideEmptyPlayer,
                        onCheckedChange = {
                            hideEmptyPlayer = it
                            prefs.edit().putBoolean("HideEmptyPlayer", it).apply()
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    SettingSwitchTile(
                        title = stringResource(R.string.settings_playlist_sort),
                        subtitle = stringResource(R.string.settings_playlist_sort_subtitle),
                        icon = TablerIcons.ArrowsSort,
                        checked = playlistSortEnabled,
                        onCheckedChange = {
                            playlistSortEnabled = it
                            prefs.edit().putBoolean("PlaylistSortEnabled", it).apply()
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    val lyricsStyleLabel = stringResource(
                        LyricsTheme.STYLE_OPTIONS.firstOrNull { it.first == lyricsStyle }?.second
                            ?: R.string.settings_lyrics_style_fullscreen
                    )
                    SettingTile(
                        title = stringResource(R.string.settings_lyrics_style),
                        subtitle = lyricsStyleLabel,
                        icon = TablerIcons.Playlist,
                        onClick = { showLyricsStyleDialog = true }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    SettingSwitchTile(
                        title = stringResource(R.string.settings_take_control),
                        subtitle = stringResource(R.string.settings_take_control_subtitle),
                        icon = TablerIcons.Click,
                        checked = takeControl,
                        onCheckedChange = {
                            takeControl = it
                            prefs.edit().putBoolean("TakeControl", it).apply()
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    SettingSwitchTile(
                        title = stringResource(R.string.settings_android_auto),
                        subtitle = stringResource(R.string.settings_android_auto_subtitle),
                        icon = TablerIcons.Car,
                        checked = andAuto,
                        onCheckedChange = {
                            andAuto = it
                            prefs.edit().putBoolean("AndAuto", it).apply()
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    SettingSwitchTile(
                        title = stringResource(R.string.settings_close_now_playing),
                        subtitle = stringResource(R.string.settings_close_now_playing_subtitle),
                        icon = TablerIcons.ArrowsMinimize,
                        checked = closeNowPlay,
                        onCheckedChange = {
                            closeNowPlay = it
                            prefs.edit().putBoolean("CloseNowPlay", it).apply()
                        }
                    )
                }

                SettingSectionCard(
                    title = stringResource(R.string.settings_section_bluetooth),
                    icon = TablerIcons.DeviceMobile,
                    info = stringResource(R.string.settings_bluetooth_info)
                ) {
                    SettingSwitchTile(
                        title = stringResource(R.string.settings_pause_on_disconnect),
                        subtitle = stringResource(R.string.settings_pause_on_disconnect_subtitle),
                        icon = TablerIcons.DeviceMobile,
                        checked = btAutoPause,
                        onCheckedChange = {
                            btAutoPause = it
                            prefs.edit().putBoolean("BtAutoPause", it).apply()
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    SettingSwitchTile(
                        title = stringResource(R.string.settings_resume_on_connect),
                        subtitle = stringResource(R.string.settings_resume_on_connect_subtitle),
                        icon = TablerIcons.DeviceMobile,
                        checked = btAutoResume,
                        onCheckedChange = {
                            btAutoResume = it
                            prefs.edit().putBoolean("BtAutoResume", it).apply()
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    SettingSwitchTile(
                        title = stringResource(R.string.settings_resume_on_headphone_plug),
                        subtitle = stringResource(R.string.settings_resume_on_headphone_plug_subtitle),
                        icon = TablerIcons.DeviceMobile,
                        checked = hpAutoResume,
                        onCheckedChange = {
                            hpAutoResume = it
                            prefs.edit().putBoolean("HpAutoResume", it).apply()
                        }
                    )
                }
            }

            if (settingsTab == SettingsTab.Content) {
                SettingSectionCard(
                    title = stringResource(R.string.settings_section_offline),
                    icon = TablerIcons.CloudOff
                ) {
                    SettingSwitchTile(
                        title = stringResource(R.string.settings_offline_mode),
                        subtitle = if (offlineMode) {
                            stringResource(R.string.settings_offline_on_subtitle)
                        } else {
                            stringResource(R.string.settings_offline_off_subtitle)
                        },
                        icon = TablerIcons.CloudOff,
                        checked = offlineMode,
                        onCheckedChange = { enabled ->
                            offlineMode = enabled
                            onOfflineModeChange(enabled)
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    SettingSwitchTile(
                        title = stringResource(R.string.settings_block_service_worker),
                        subtitle = stringResource(R.string.settings_block_service_worker_subtitle),
                        icon = TablerIcons.Shield,
                        checked = blockSW,
                        onCheckedChange = { enabled ->
                            blockSW = enabled
                            onBlockServiceWorkerChange(enabled)
                        }
                    )
                }

                SettingSectionCard(
                    title = stringResource(R.string.settings_section_downloads),
                    icon = TablerIcons.Download
                ) {
                    SettingTile(
                        title = stringResource(R.string.settings_audio_format),
                        subtitle = if (dlFormat == DownloadFormat.MP3) stringResource(R.string.settings_format_mp3) else stringResource(R.string.settings_format_m4a),
                        icon = TablerIcons.WaveSine,
                        onClick = { showFormatDialog = true }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    SettingTile(
                        title = stringResource(R.string.settings_download_folder),
                        subtitle = stringResource(R.string.settings_download_folder_subtitle, dlFolderLabel),
                        icon = TablerIcons.Folder,
                        onClick = { showFolderDialog = true }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    SettingSwitchTile(
                        title = stringResource(R.string.settings_write_tags),
                        subtitle = stringResource(R.string.settings_write_tags_subtitle),
                        icon = TablerIcons.Playlist,
                        checked = dlTags,
                        onCheckedChange = { enabled ->
                            dlTags = enabled
                            DownloadPrefs.setWriteTags(context, enabled)
                        }
                    )
                }

                SettingSectionCard(
                    title = stringResource(R.string.settings_section_accounts),
                    icon = TablerIcons.UserPlus
                ) {
                    SettingTile(
                        title = stringResource(R.string.settings_save_account),
                        subtitle = stringResource(R.string.settings_save_account_subtitle),
                        icon = TablerIcons.UserPlus,
                        onClick = {
                            val cookies = ProfileManager.captureSession(context)
                            if (cookies == null) {
                                Toast.makeText(context, resources.getString(R.string.settings_login_first), Toast.LENGTH_SHORT).show()
                            } else {
                                pendingCookies = cookies
                                accountNameInput = prefs.getString("CurrentAccountName", "") ?: ""
                                showSaveAccountDialog = true
                            }
                        }
                    )
                    if (profiles.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                        profiles.forEachIndexed { index, profile ->
                            ProfileRow(
                                name = profile.name,
                                subtitle = stringResource(R.string.settings_profile_saved, SimpleDateFormat("MMM d, yyyy", resources.configuration.locales[0]).format(Date(profile.savedAt))),
                                onLoad = { onLoadProfile(profile.cookies) },
                                onDelete = {
                                    onDeleteProfile(profile.name)
                                    profiles = ProfileManager.getProfiles(context)
                                }
                            )
                            if (index < profiles.lastIndex) {
                                HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                            }
                        }
                    }
                }
            }

            if (settingsTab == SettingsTab.Advanced) {
                SettingSectionCard(
                    title = stringResource(R.string.settings_section_connection_mode),
                    icon = TablerIcons.Shield
                ) {
                    val modeLabel = if (connectionMode == "proxy") {
                        stringResource(R.string.settings_connection_proxy)
                    } else {
                        stringResource(R.string.settings_connection_normal)
                    }
                    SettingTile(
                        title = stringResource(R.string.settings_connection_mode),
                        subtitle = stringResource(R.string.settings_connection_mode_subtitle, modeLabel),
                        icon = TablerIcons.Shield,
                        onClick = { showConnectionModeDialog = true }
                    )
                }

                SettingSectionCard(
                    title = stringResource(R.string.settings_section_system),
                    icon = TablerIcons.Power
                ) {
                    SettingSwitchTile(
                        title = stringResource(R.string.settings_swipe_to_stop),
                        subtitle = stringResource(R.string.settings_swipe_to_stop_subtitle),
                        icon = TablerIcons.Power,
                        checked = swipeStop,
                        onCheckedChange = {
                            swipeStop = it
                            prefs.edit().putBoolean("SwipeStop", it).apply()
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    SettingTile(
                        title = stringResource(R.string.settings_empty_cache),
                        subtitle = stringResource(R.string.settings_empty_cache_subtitle),
                        icon = TablerIcons.Brush,
                        onClick = { showClearCacheDialog = true }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    SettingTile(
                        title = stringResource(R.string.settings_empty_cache_data),
                        subtitle = stringResource(R.string.settings_empty_cache_data_subtitle),
                        icon = TablerIcons.TrashOff,
                        onClick = { showClearDataDialog = true },
                        isDestructive = true
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    SettingTile(
                        title = stringResource(R.string.settings_open_links),
                        subtitle = stringResource(R.string.settings_open_links_subtitle),
                        icon = TablerIcons.Link,
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                val pkg = Uri.parse("package:${context.packageName}")
                                val specific = Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS).setData(pkg)
                                val generic = Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS)
                                val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(pkg)
                                val opened = listOf(specific, generic, appInfo).any { intent ->
                                    runCatching { context.startActivity(intent) }.isSuccess
                                }
                                if (!opened) {
                                    Toast.makeText(context, resources.getString(R.string.settings_open_links_unsupported), Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, resources.getString(R.string.settings_open_links_always_hint), Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                }

                if (connectionMode == "proxy") {
                    SettingSectionCard(
                        title = stringResource(R.string.settings_section_security_network),
                        icon = TablerIcons.Shield
                    ) {
                        SettingTile(
                            title = stringResource(R.string.settings_ca_certificate),
                            subtitle = stringResource(R.string.settings_ca_certificate_subtitle),
                            icon = TablerIcons.Shield,
                            onClick = {
                                val path = LocalProxyManager.exportCACert(context)
                                Toast.makeText(context, resources.getString(R.string.settings_cert_exported, path), Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                }
            }

            if (settingsTab == SettingsTab.About) {
                SettingSectionCard(
                    title = stringResource(R.string.settings_section_about),
                    icon = TablerIcons.InfoCircle
                ) {
                    SettingTile(
                        title = stringResource(R.string.settings_github_repository),
                        subtitle = stringResource(R.string.settings_github_subtitle),
                        painter = painterResource(id = R.drawable.ic_github),
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/kvmgithub/Spotilol"))
                            context.startActivity(intent)
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    SettingTile(
                        title = stringResource(R.string.settings_discord_server),
                        subtitle = stringResource(R.string.settings_discord_subtitle),
                        icon = TablerIcons.BrandDiscord,
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/95dAE2UkqP"))
                            context.startActivity(intent)
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    SettingTile(
                        title = stringResource(R.string.settings_version),
                        subtitle = stringResource(R.string.settings_version_format, appVersionName, BuildInfo.id),
                        icon = TablerIcons.DeviceMobile,
                        onClick = { showChangelogDialog = true }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                    SettingTile(
                        title = stringResource(R.string.settings_webview_engine),
                        subtitle = pkg?.versionName ?: stringResource(R.string.settings_webview_system),
                        icon = TablerIcons.Language,
                        onClick = { onDebugSecretTap() },
                        onLongClick = { onDebugSecretHold() }
                    )
                }

                if (debugUnlocked) {
                    SettingSectionCard(
                        title = stringResource(R.string.settings_section_experimental),
                        icon = TablerIcons.Flask
                    ) {
                        SettingSwitchTile(
                            title = stringResource(R.string.settings_collect_debug),
                            subtitle = stringResource(R.string.settings_collect_debug_subtitle),
                            icon = TablerIcons.Bug,
                            checked = loggingOn,
                            onCheckedChange = { enabled ->
                                loggingOn = enabled
                                Logger.setEnabled(context, enabled)
                                onDebugToggle(enabled)
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                        SettingTile(
                            title = stringResource(R.string.settings_open_logger),
                            subtitle = if (loggingOn) stringResource(R.string.settings_open_logger_on)
                            else stringResource(R.string.settings_open_logger_off),
                            icon = TablerIcons.Code,
                            onClick = { showDevlogDialog = true }
                        )

                        HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                        SettingTile(
                            title = stringResource(R.string.settings_crash_test),
                            subtitle = stringResource(R.string.settings_crash_test_subtitle),
                            icon = TablerIcons.AlertTriangle,
                            onClick = { throw IllegalStateException(resources.getString(R.string.settings_crash_test_exception)) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showPaletteDialog) {
        PaletteDialog(
            currentSeed = paletteSeed,
            onSave = { hex ->
                onPaletteSeedChange(hex)
                showPaletteDialog = false
            },
            onDismiss = { showPaletteDialog = false }
        )
    }

    if (showChangelogDialog) {
        ChangelogDialog(onDismiss = { showChangelogDialog = false })
    }

    if (showSaveAccountDialog) {
        AlertDialog(
            onDismissRequest = { showSaveAccountDialog = false },
            shape = RoundedCornerShape(28.dp),
            title = {
                Text(
                    text = stringResource(R.string.settings_save_account),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.settings_save_account_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = accountNameInput,
                        onValueChange = { accountNameInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.settings_account_name_label)) },
                        placeholder = { Text(stringResource(R.string.settings_account_name_placeholder)) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cookies = pendingCookies
                        if (cookies != null && accountNameInput.isNotBlank()) {
                            onSaveProfile(accountNameInput, cookies)
                            profiles = ProfileManager.getProfiles(context)
                        }
                        accountNameInput = ""
                        showSaveAccountDialog = false
                    },
                    enabled = accountNameInput.isNotBlank()
                ) {
                    Text(stringResource(R.string.settings_save), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveAccountDialog = false }) {
                    Text(stringResource(R.string.settings_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    if (showConnectionModeDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.settings_connection_mode),
            options = listOf(
                "normal" to stringResource(R.string.settings_connection_normal),
                "proxy" to stringResource(R.string.settings_connection_proxy)
            ),
            selected = connectionMode,
            onSelect = { value ->
                connectionMode = value
                onConnectionModeChange(value)
            },
            onDismiss = { showConnectionModeDialog = false }
        )
    }

    if (showAutoPlayDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.settings_autoplay_mode),
            options = listOf(
                "disabled" to stringResource(R.string.settings_autoplay_disabled),
                "onetime" to stringResource(R.string.settings_autoplay_onetime),
                "permanent" to stringResource(R.string.settings_autoplay_permanent)
            ),
            selected = autoplayMode,
            onSelect = { value ->
                autoplayMode = value
                prefs.edit().putString("APlayMode", value).apply()
            },
            onDismiss = { showAutoPlayDialog = false }
        )
    }

    if (showPlayerModeDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.settings_player_mode),
            options = listOf(
                "spotilol" to stringResource(R.string.settings_player_spotilol),
                "original" to stringResource(R.string.settings_player_original)
            ),
            selected = playerMode,
            onSelect = { value ->
                playerMode = value
                prefs.edit().putString("PlayerMode", value).apply()
            },
            onDismiss = { showPlayerModeDialog = false }
        )
    }

    if (showLyricsStyleDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.settings_lyrics_style),
            options = LyricsTheme.STYLE_OPTIONS.map { it.first to stringResource(it.second) },
            selected = lyricsStyle,
            onSelect = { value ->
                lyricsStyle = value
                prefs.edit().putString("LyricsStyle", value).apply()
            },
            onDismiss = { showLyricsStyleDialog = false }
        )
    }

    if (showFormatDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.settings_audio_format),
            options = listOf(
                DownloadFormat.M4A.name to stringResource(R.string.settings_format_m4a),
                DownloadFormat.MP3.name to stringResource(R.string.settings_format_mp3)
            ),
            selected = dlFormat.name,
            onSelect = { value ->
                val f = DownloadFormat.from(value)
                dlFormat = f
                DownloadPrefs.setFormat(context, f)
            },
            onDismiss = { showFormatDialog = false }
        )
    }

    if (showFolderDialog) {
        DownloadFolderDialog(
            currentPath = dlFolderPath,
            onPick = {
                showFolderDialog = false
                val tree = DownloadPrefs.folder(context)
                folderPicker.launch(tree?.let { DownloadFolder.documentUri(it) })
            },
            onUseDefault = {
                DownloadPrefs.setFolder(context, null)
                refreshFolderState()
                showFolderDialog = false
            },
            onDismiss = { showFolderDialog = false }
        )
    }

    if (showGuiModeDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.settings_gui_hack_mode),
            options = listOf(
                "csshack" to stringResource(R.string.settings_gui_mode_css_js),
                "bigwindow" to stringResource(R.string.settings_gui_mode_wide),
                "none" to stringResource(R.string.settings_gui_mode_none)
            ),
            selected = guiMode,
            onSelect = { value ->
                guiMode = value
                prefs.edit().putString("GuiMode", value).apply()
            },
            onDismiss = { showGuiModeDialog = false }
        )
    }

    if (showCustomCssDialog) {
        CustomCssDialog(
            initialCss = customCss,
            onSave = { css ->
                customCss = css
                prefs.edit().putString("CustomCss", css).apply()
                showCustomCssDialog = false
            },
            onDismiss = { showCustomCssDialog = false }
        )
    }

    if (showClearCacheDialog) {
        ConfirmationDialog(
            title = stringResource(R.string.settings_empty_cache),
            message = stringResource(R.string.settings_empty_cache_message),
            confirmText = stringResource(R.string.settings_clear_cache),
            onConfirm = {
                showClearCacheDialog = false
                onClearCache()
            },
            onDismiss = { showClearCacheDialog = false }
        )
    }

    if (showClearDataDialog) {
        ConfirmationDialog(
            title = stringResource(R.string.settings_empty_cache_data),
            message = stringResource(R.string.settings_empty_cache_data_message),
            confirmText = stringResource(R.string.settings_clear_all_data),
            isDestructive = true,
            onConfirm = {
                showClearDataDialog = false
                onClearData()
            },
            onDismiss = { showClearDataDialog = false }
        )
    }

    if (showDevlogDialog) {
        LoggerDialog(onDismiss = { showDevlogDialog = false })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaletteDialog(
    currentSeed: String?,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val initial = parsePaletteColor(currentSeed) ?: Color(0xFFE0E0E0)
    var red by remember { mutableFloatStateOf(initial.red * 255f) }
    var green by remember { mutableFloatStateOf(initial.green * 255f) }
    var blue by remember { mutableFloatStateOf(initial.blue * 255f) }
    var useDefault by remember { mutableStateOf(currentSeed.isNullOrBlank()) }
    val preview = Color(red / 255f, green / 255f, blue / 255f)

    fun pick(hex: String?) {
        val c = parsePaletteColor(hex)
        if (c == null) {
            useDefault = true
            red = 224f
            green = 224f
            blue = 224f
        } else {
            useDefault = false
            red = c.red * 255f
            green = c.green * 255f
            blue = c.blue * 255f
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = TablerIcons.ColorSwatch,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.settings_accent_color),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(preview)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.settings_custom_accent_color),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (useDefault) stringResource(R.string.settings_default_scheme) else formatHex(preview),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PalettePresets.forEach { (label, hex) ->
                        val color = parsePaletteColor(hex) ?: Color(0xFFE0E0E0)
                        val isSelected = if (hex == null) {
                            currentSeed.isNullOrBlank()
                        } else {
                            hex == currentSeed
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(52.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                        },
                                        shape = CircleShape
                                    )
                                    .clickable { pick(hex) },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = TablerIcons.Check,
                                        contentDescription = null,
                                        tint = if (color.luminance() > 0.5f) {
                                            Color(0xFF1A1A1A)
                                        } else {
                                            Color.White
                                        },
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(label),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                HorizontalDivider()

                ColorSlider(stringResource(R.string.settings_color_red), red, { red = it; useDefault = false }, Color(0xFFF44336))
                ColorSlider(stringResource(R.string.settings_color_green), green, { green = it; useDefault = false }, Color(0xFF4CAF50))
                ColorSlider(stringResource(R.string.settings_color_blue), blue, { blue = it; useDefault = false }, Color(0xFF2196F3))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(if (useDefault) null else formatHex(preview))
            }) {
                Text(stringResource(R.string.settings_apply), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@Composable
private fun ColorSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    color: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(52.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color,
                inactiveTrackColor = color.copy(alpha = 0.2f)
            )
        )
        Text(
            text = value.roundToInt().toString(),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(30.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun SettingSectionCard(
    title: String,
    icon: ImageVector,
    info: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, bottom = 6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            if (info != null) {
                Spacer(Modifier.width(6.dp))
                InfoTooltip(text = info)
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(content = content)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoTooltip(text: String) {
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()

    LaunchedEffect(tooltipState.isVisible) {
        if (tooltipState.isVisible) {
            delay(4000)
            tooltipState.dismiss()
        }
    }

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        state = tooltipState,
        onDismissRequest = { tooltipState.dismiss() },
        tooltip = {
            PlainTooltip(
                maxWidth = 240.dp,
                containerColor = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .clickable { scope.launch { tooltipState.show() } },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = TablerIcons.InfoCircle,
                contentDescription = stringResource(R.string.settings_about_debug_tools),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

@Composable
fun SettingTile(
    title: String,
    subtitle: String,
    icon: ImageVector? = null,
    painter: Painter? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    isDestructive: Boolean = false,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier else Modifier.alpha(0.38f))
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val iconTint = if (isDestructive) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        if (painter != null) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
        }
        if (painter != null || icon != null) Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = TablerIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
fun SettingSwitchTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
fun ProfileRow(
    name: String,
    subtitle: String,
    onLoad: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onLoad)
            .padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = TablerIcons.User,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = TablerIcons.Trash,
                contentDescription = stringResource(R.string.settings_delete),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun SingleChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { (value, label) ->
                    val isSelected = selected == value
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                onSelect(value)
                                onDismiss()
                            },
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                        } else {
                            Color.Transparent
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    onSelect(value)
                                    onDismiss()
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary,
                                    unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                ),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel), color = MaterialTheme.colorScheme.primary)
            }
        }
    )
}

@Composable
fun CustomCssDialog(
    initialCss: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var tempCss by remember { mutableStateOf(initialCss) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = TablerIcons.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.settings_custom_css),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_custom_css_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedTextField(
                    value = tempCss,
                    onValueChange = { tempCss = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    placeholder = {
                        Text(
                            stringResource(R.string.settings_custom_css_placeholder),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    ),
                    singleLine = false
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(tempCss) }
            ) {
                Text(stringResource(R.string.settings_save), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@Composable
fun DownloadFolderDialog(
    currentPath: String,
    onPick: () -> Unit,
    onUseDefault: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = TablerIcons.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.settings_download_folder),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column {
                Text(
                    text = currentPath,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.settings_download_folder_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    text = stringResource(R.string.settings_download_folder_default, DownloadPrefs.DEFAULT_SUBFOLDER),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onUseDefault() }
                        .padding(top = 14.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onPick) {
                Text(
                    stringResource(R.string.settings_choose_folder),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    confirmText: String,
    isDestructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmText,
                    fontWeight = FontWeight.Bold,
                    color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@Preview(showBackground = true)
@Composable
fun SettingsContentPreview() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val prefs = remember { context.getSharedPreferences("preview_prefs", Context.MODE_PRIVATE) }
    SpotifyTheme {
        SettingsContent(
            modifier = Modifier.fillMaxSize(),
            prefs = prefs,
            materialYou = false,
            onMaterialYouChange = {},
            amoledThemeState = false,
            onAmoledThemeChange = {},
            hideTopBar = false,
            onHideTopBarChange = {},
            landscapeMode = false,
            onLandscapeModeChange = {},
            keepScreenOn = false,
            onKeepScreenOnChange = {},
            paletteSeed = null,
            onPaletteSeedChange = {},
            onConnectionModeChange = {},
            onOfflineModeChange = {},
            onSaveProfile = { _, _ -> },
            onLoadProfile = {},
            onDeleteProfile = {},
            onClearCache = {},
            onClearData = {},
            blockServiceWorker = true,
            onBlockServiceWorkerChange = {}
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoggerDialog(onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var levelFilter by remember { mutableStateOf<LogLevel?>(null) }
    var entries by remember { mutableStateOf(Logger.entries()) }
    var expanded by remember { mutableStateOf(emptySet<Long>()) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val filtered = remember(entries, query, levelFilter) {
        LogFilter.apply(entries, query, levelFilter ?: LogLevel.VERBOSE)
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(350)
            val next = Logger.entries()
            val changed = next.size != entries.size || next.lastOrNull()?.id != entries.lastOrNull()?.id
            if (changed) entries = next
        }
    }

    LaunchedEffect(filtered.size, filtered.lastOrNull()?.id) {
        if (filtered.isNotEmpty()) listState.scrollToItem(filtered.size - 1)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.logger_title), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.logger_line_count, filtered.size, entries.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.logger_search_placeholder),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    imageVector = TablerIcons.X,
                                    contentDescription = stringResource(R.string.logger_clear_filter),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = levelFilter == null,
                        onClick = { levelFilter = null },
                        label = { Text(stringResource(R.string.logger_level_all), fontSize = 11.sp) }
                    )
                    LogLevel.entries.forEach { level ->
                        val color = logLevelColor(level)
                        FilterChip(
                            selected = levelFilter == level,
                            onClick = { levelFilter = level },
                            label = {
                                Text(
                                    text = level.letter.toString(),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = color,
                                selectedContainerColor = color.copy(alpha = 0.22f),
                                selectedLabelColor = color
                            )
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f)
                ) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        if (filtered.isEmpty()) {
                            item {
                                Text(
                                    text = if (entries.isEmpty()) stringResource(R.string.logger_empty_waiting)
                                    else stringResource(R.string.logger_empty_no_match),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                        items(filtered, key = { it.id }) { entry ->
                            LogRow(
                                entry = entry,
                                expanded = expanded.contains(entry.id),
                                onToggle = {
                                    expanded = if (expanded.contains(entry.id)) expanded - entry.id
                                    else expanded + entry.id
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    Logger.clear()
                    entries = emptyList()
                }) { Text(stringResource(R.string.logger_clear)) }

                TextButton(onClick = {
                    val text = LogFilter.text(filtered)
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipData.newPlainText("spotilol_logger", text).toClipEntry()
                        )
                    }
                }) { Text(stringResource(R.string.logger_copy)) }

                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.logger_close), fontWeight = FontWeight.Bold)
                }
            }
        }
    )
}

@Composable
private fun LogRow(entry: LogEntry, expanded: Boolean, onToggle: () -> Unit) {
    val color = logLevelColor(entry.level)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = dim)) {
                    append(entry.time)
                    append(' ')
                }
                withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
                    append(entry.level.letter)
                    append('/')
                    append(entry.tag)
                }
                withStyle(SpanStyle(color = color)) {
                    append(": ")
                    append(entry.message)
                }
            },
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 14.sp
        )
        if (expanded && entry.stack != null) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = entry.stack,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                lineHeight = 12.sp,
                color = dim
            )
        }
    }
}

private fun logLevelColor(level: LogLevel): Color = when (level) {
    LogLevel.VERBOSE -> Color(0xFF9AA0A6)
    LogLevel.DEBUG -> Color(0xFF64B5F6)
    LogLevel.INFO -> Color(0xFF81C784)
    LogLevel.SYS -> Color(0xFFBA68C8)
    LogLevel.WARN -> Color(0xFFFFB74D)
    LogLevel.ERROR -> Color(0xFFEF5350)
}
