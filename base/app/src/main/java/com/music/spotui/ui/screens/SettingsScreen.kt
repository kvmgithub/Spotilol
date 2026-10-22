package com.music.spotui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.music.spotui.ui.components.StreamingQualityPicker
import com.music.spotui.ui.theme.AppBackground

/**
 * Settings, on one page: streaming quality, Spotilol's encrypted account profiles, data and
 * battery, display, language, diagnostics and the player's own repair tools.
 */
@Composable
fun SettingsScreen(navController: NavController) {
    val energy by app.myspoti.power.EnergySettings.state.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize().background(AppBackground).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.accessibility_navigate_back), tint = Color.White)
            }
            Text(stringResource(R.string.settings_title), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Section(R.string.settings_section_playback)
            StreamingQualityPicker(title = R.string.quality_streaming_wifi_title)
            StreamingQualityPicker(cellular = true, title = R.string.quality_streaming_cell_title)
            var canvasWifiOnly by remember { mutableStateOf(com.music.spotui.data.preferences.isCanvasWifiOnly(ctx)) }
            OptionRow(
                label = stringResource(R.string.canvas_wifi_only),
                detail = stringResource(R.string.canvas_wifi_only_detail),
                checked = canvasWifiOnly,
                onChecked = { canvasWifiOnly = it; com.music.spotui.data.preferences.setCanvasWifiOnly(ctx, it) },
            )
            OptionRow(
                label = stringResource(R.string.settings_autoplay_title),
                detail = stringResource(R.string.settings_autoplay_detail),
                checked = com.music.spotui.data.preferences.AutoplayPref.on,
                onChecked = { com.music.spotui.data.preferences.AutoplayPref.set(ctx, it) },
            )
            CrossfadeRow()
            BoostRow()
            var keepPlaying by remember { mutableStateOf(app.myspoti.host.android.PlaybackService.keepsPlayingOnClose(ctx)) }
            OptionRow(
                label = stringResource(R.string.settings_keep_playing_title),
                detail = stringResource(R.string.settings_keep_playing_detail),
                checked = keepPlaying,
                onChecked = { keepPlaying = it; app.myspoti.host.android.PlaybackService.setKeepsPlayingOnClose(ctx, it) },
            )
            Divider()

            Section(R.string.spotilol_section_accounts)
            com.music.spotui.ui.components.AccountProfiles()
            Divider()

            Section(R.string.settings_section_data)
            OptionRow(
                label = stringResource(R.string.energy_saver_title),
                detail = stringResource(R.string.energy_saver_summary),
                checked = energy.manualSaver,
                onChecked = app.myspoti.power.EnergySettings::setSaver,
            )
            OptionRow(
                label = stringResource(R.string.data_saver_title),
                detail = stringResource(R.string.data_saver_summary),
                checked = com.music.spotui.data.DataSaver.enabled,
                onChecked = com.music.spotui.data.DataSaver::set,
            )
            // Battery optimization off = music and Connect keep going with the screen off.
            var exempt by remember { androidx.compose.runtime.mutableStateOf(app.myspoti.power.BatteryExemption.isExempt(ctx)) }
            androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                exempt = app.myspoti.power.BatteryExemption.isExempt(ctx)
            }
            OptionRow(
                label = stringResource(R.string.background_playback_title),
                detail = stringResource(if (exempt) R.string.background_playback_on else R.string.background_playback_off),
                checked = exempt,
                onChecked = {
                    if (exempt) ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    else app.myspoti.power.BatteryExemption.request(ctx)
                },
            )
            Divider()

            Section(R.string.settings_section_display)
            OptionRow(
                label = stringResource(R.string.settings_amoled_theme),
                detail = stringResource(R.string.settings_amoled_theme_subtitle),
                checked = energy.blackPreference,
                onChecked = app.myspoti.power.EnergySettings::setBlackBackground,
            )
            OptionRow(
                label = stringResource(R.string.settings_item_canvas_title),
                detail = stringResource(if (energy.policy.saverEnabled) R.string.energy_saver_summary else R.string.video_settings_canvas_subtitle),
                checked = energy.canvasEnabled && !energy.policy.saverEnabled,
                enabled = !energy.policy.saverEnabled,
                onChecked = app.myspoti.power.EnergySettings::setCanvasEnabled,
            )
            OptionRow(
                label = stringResource(R.string.settings_solid_nav_title),
                detail = stringResource(R.string.settings_solid_nav_detail),
                checked = com.music.spotui.data.preferences.SolidNavBar.on,
                onChecked = { com.music.spotui.data.preferences.SolidNavBar.set(ctx, it) },
            )
            AccentRow()
            OrientationRow()
            LanguageRow()
            Divider()

            Section(R.string.settings_section_app)
            OptionRow(
                label = stringResource(R.string.spotilol_diagnostics_title),
                detail = stringResource(R.string.spotilol_diagnostics_detail),
                checked = com.music.spotui.data.BetaLogs.enabled.value,
                onChecked = { com.music.spotui.data.BetaLogs.setEnabled(ctx, it) },
            )
            if (com.music.spotui.data.BetaLogs.enabled.value) {
                var showLogs by remember { androidx.compose.runtime.mutableStateOf(false) }
                Column(
                    Modifier.fillMaxWidth().clickable { showLogs = true }
                        .padding(start = 36.dp, end = 20.dp, top = 6.dp, bottom = 10.dp),
                ) {
                    Text(stringResource(R.string.spotilol_diagnostics_open), color = com.music.spotui.ui.theme.AppAccent, fontSize = 16.sp)
                    Text(stringResource(R.string.spotilol_diagnostics_open_detail, com.music.spotui.data.BetaLogs.MINUTES), color = Color(0xFFB3B3B3), fontSize = 12.sp)
                }
                if (showLogs) com.music.spotui.ui.components.DiagnosticLogsDialog(onDismiss = { showLogs = false })
            }
            // The hidden Web Player stuck or crashed: rebuild it without leaving the app.
            Column(
                Modifier.fillMaxWidth().clickable {
                    app.myspoti.host.android.MyspotiRuntime.host.reload()
                    // Reported: nothing said the restart happened.
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.restart_player_done), android.widget.Toast.LENGTH_SHORT).show()
                }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.restart_player_title), color = Color.White, fontSize = 16.sp)
                Text(stringResource(R.string.restart_player_detail), color = Color(0xFFB3B3B3), fontSize = 12.sp)
            }
            // The last rows scroll above the mini player, the tabs and the system bar.
            Spacer(Modifier.height(com.music.spotui.ui.components.bottomBarsSpace()))
        }
    }
}

@Composable
private fun Section(title: Int) {
    Text(stringResource(title).uppercase(), color = com.music.spotui.ui.theme.AppAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
private fun Divider() = HorizontalDivider(color = Color(0xFF2A2A2A), modifier = Modifier.padding(top = 8.dp))

/** The app's accent colour, for everyone (the same palette the supporter sheet uses). */
@Composable
private fun AccentRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.supporter_accent), color = Color.White, fontSize = 16.sp)
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 10.dp)) {
            for (a in com.music.spotui.data.api.SupportReminder.Accent.entries) {
                val selected = com.music.spotui.data.api.SupportReminder.accent == a
                val name = stringResource(when (a) {
                    com.music.spotui.data.api.SupportReminder.Accent.GREEN -> R.string.color_green
                    com.music.spotui.data.api.SupportReminder.Accent.BLUE -> R.string.color_blue
                    com.music.spotui.data.api.SupportReminder.Accent.PURPLE -> R.string.color_purple
                    com.music.spotui.data.api.SupportReminder.Accent.PINK -> R.string.color_pink
                    com.music.spotui.data.api.SupportReminder.Accent.ORANGE -> R.string.color_orange
                })
                Box(
                    Modifier.size(40.dp)
                        .semantics { contentDescription = name; role = Role.RadioButton }
                        .clip(CircleShape).background(a.color)
                        .then(if (selected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                        .clickable { com.music.spotui.data.api.SupportReminder.setAccent(ctx, a) },
                )
            }
        }
    }
}

/**
 * "Crossfade": how long the end of a song sounds under the start of the next (the page's audio
 * graph keeps that much in reserve, see the bridge's "crossfade"). The default is 0: off.
 */
@Composable
private fun CrossfadeRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pref = com.music.spotui.data.preferences.CrossfadePref
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.settings_crossfade_title), color = Color.White, fontSize = 16.sp)
        Text(
            stringResource(R.string.settings_crossfade_detail),
            color = Color(0xFFB3B3B3), fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            for (v in listOf(0, 3, 6, 9, 12)) {
                val selected = pref.seconds == v
                Text(
                    if (v == 0) stringResource(R.string.settings_crossfade_off)
                    else stringResource(R.string.settings_crossfade_seconds, v),
                    color = if (selected) Color.Black else Color.White, fontSize = 14.sp,
                    modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .background(if (selected) com.music.spotui.ui.theme.AppAccent else Color(0xFF2A2A2A))
                        .clickable { pref.set(ctx, v) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * Volume boost: 100 % (off, the default) to 200 %, through a gain and a limiter in the page.
 * A note replaces nothing: it only says when the player turned the boost off by itself.
 */
@Composable
private fun BoostRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pref = com.music.spotui.data.preferences.BoostPref
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.spotilol_boost_title), color = Color.White, fontSize = 16.sp)
        Text(
            stringResource(R.string.spotilol_boost_detail),
            color = Color(0xFFB3B3B3), fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            for (v in listOf(100, 125, 150, 175, 200)) {
                val selected = pref.percent == v
                Text(
                    stringResource(R.string.spotilol_boost_percent, v),
                    color = if (selected) Color.Black else Color.White, fontSize = 14.sp,
                    modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .background(if (selected) com.music.spotui.ui.theme.AppAccent else Color(0xFF2A2A2A))
                        .clickable { pref.set(ctx, v) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
        if (pref.silent) Text(
            stringResource(R.string.spotilol_boost_silent),
            color = Color(0xFFB3B3B3), fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Screen rotation: portrait (default), automatic or landscape. */
@Composable
private fun OrientationRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pref = com.music.spotui.data.preferences.OrientationPref
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.settings_rotation_title), color = Color.White, fontSize = 16.sp)
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            for ((value, label) in listOf(
                pref.PORTRAIT to R.string.settings_rotation_portrait,
                pref.AUTO to R.string.settings_rotation_auto,
                pref.LANDSCAPE to R.string.settings_rotation_landscape,
            )) {
                val selected = pref.mode == value
                Text(
                    stringResource(label),
                    color = if (selected) Color.Black else Color.White, fontSize = 14.sp,
                    modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .background(if (selected) com.music.spotui.ui.theme.AppAccent else Color(0xFF2A2A2A))
                        .clickable { pref.set(ctx, value) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** "App language": Automatic (French or English) or any language with Spotify's labels. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LanguageRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val current = remember { com.music.spotui.data.AppLanguage.current(ctx) }
    var picking by remember { androidx.compose.runtime.mutableStateOf(false) }
    val auto = stringResource(R.string.language_auto)
    fun label(tag: String) = if (tag == com.music.spotui.data.AppLanguage.SYSTEM) auto else com.music.spotui.data.AppLanguage.nativeName(tag)
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().clickable { picking = true }.padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(stringResource(R.string.settings_item_app_language_title), color = Color.White, fontSize = 16.sp)
        Text(label(current), color = Color(0xFFB3B3B3), fontSize = 12.sp)
    }
    if (picking) androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = { picking = false },
        containerColor = Color(0xFF282828),
    ) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            item {
                Text(stringResource(R.string.settings_item_app_language_title), color = Color.White, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                Text(stringResource(R.string.language_partial_note), color = Color(0xFFB3B3B3), fontSize = 12.sp,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
            }
            items(com.music.spotui.data.AppLanguage.AVAILABLE.size) { i ->
                val tag = com.music.spotui.data.AppLanguage.AVAILABLE[i]
                val selected = tag == current
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                        picking = false
                        if (!selected) {
                            com.music.spotui.data.AppLanguage.apply(ctx, tag)
                            // Screens take the new language when the activity is rebuilt.
                            generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }
                                .filterIsInstance<android.app.Activity>().firstOrNull()?.recreate()
                        }
                    }.padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Text(label(tag), color = if (selected) com.music.spotui.ui.theme.AppAccent else Color.White, fontSize = 16.sp)
                        if (tag == com.music.spotui.data.AppLanguage.SYSTEM)
                            Text(stringResource(R.string.language_auto_detail), color = Color(0xFFB3B3B3), fontSize = 12.sp)
                    }
                    if (selected) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Check, null, tint = com.music.spotui.ui.theme.AppAccent)
                }
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, detail: String, checked: Boolean,
    enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChecked)
            .padding(horizontal = 20.dp, vertical = 10.dp)) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 16.sp)
            Text(detail, color = Color(0xFFB3B3B3), fontSize = 12.sp)
        }
        androidx.compose.material3.Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}
