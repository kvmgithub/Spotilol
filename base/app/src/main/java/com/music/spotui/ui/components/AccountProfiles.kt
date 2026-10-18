package com.music.spotui.ui.components

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.music.spotui.data.ProfileManager
import com.music.spotui.data.api.ProfileCache
import com.music.spotui.di.SessionActions
import com.music.spotui.ui.theme.AppAccent

private val Grey = Color(0xFFB3B3B3)
private val Sheet = Color(0xFF282828)

/**
 * Spotilol's account profiles in Settings: save the signed-in session (encrypted), switch to
 * a saved one without signing in again, delete one.
 */
@Composable
fun AccountProfiles() {
    val context = LocalContext.current
    var profiles by remember { mutableStateOf(ProfileManager.getProfiles(context)) }
    var naming by remember { mutableStateOf(false) }
    var switchTo by remember { mutableStateOf<ProfileManager.Profile?>(null) }
    var deleting by remember { mutableStateOf<ProfileManager.Profile?>(null) }
    LaunchedEffect(Unit) { ProfileCache.ensure(context) }
    fun toast(id: Int) = Toast.makeText(context, id, Toast.LENGTH_SHORT).show()
    fun reload() { profiles = ProfileManager.getProfiles(context) }

    val list = profiles
    if (list == null) {
        // Fail closed: an unreadable store is reported, never replaced.
        Text(stringResource(R.string.spotilol_profile_unavailable), color = Grey, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        return
    }
    list.forEach { p ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { switchTo = p }.padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(p.name, color = Color.White, fontSize = 16.sp)
                Text(DateUtils.getRelativeTimeSpanString(p.savedAt).toString(), color = Grey, fontSize = 12.sp)
            }
            IconButton(onClick = { deleting = p }) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.spotilol_profile_delete), tint = Grey)
            }
        }
    }
    Column(Modifier.fillMaxWidth().clickable { naming = true }.padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.spotilol_profile_save), color = AppAccent, fontSize = 16.sp)
        Text(stringResource(R.string.spotilol_profile_save_hint), color = Grey, fontSize = 12.sp)
    }

    if (naming) {
        var name by remember { mutableStateOf(ProfileCache.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { naming = false },
            containerColor = Sheet,
            title = { Text(stringResource(R.string.spotilol_profile_save), color = Color.White) },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(64) }, singleLine = true,
                    label = { Text(stringResource(R.string.spotilol_profile_name_hint)) },
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    naming = false
                    val cookies = ProfileManager.captureSession()
                    when {
                        cookies == null -> toast(R.string.spotilol_profile_save_failed)
                        runCatching { ProfileManager.saveProfile(context, name, cookies) }.isSuccess -> { toast(R.string.spotilol_profile_saved); reload() }
                        else -> toast(R.string.spotilol_profile_unavailable)
                    }
                }) { Text(stringResource(R.string.spotilol_profile_save_button), color = AppAccent) }
            },
            dismissButton = {
                TextButton(onClick = { naming = false }) { Text(stringResource(R.string.add_to_playlist_button_cancel), color = Color.White) }
            },
        )
    }

    switchTo?.let { p ->
        AlertDialog(
            onDismissRequest = { switchTo = null },
            containerColor = Sheet,
            title = { Text(stringResource(R.string.spotilol_profile_switch_title, p.name), color = Color.White) },
            text = { Text(stringResource(R.string.spotilol_profile_switch_detail), color = Grey) },
            confirmButton = {
                TextButton(onClick = {
                    switchTo = null
                    val cookies = ProfileManager.parseCookies(p.cookies)
                    if (cookies == null) toast(R.string.spotilol_profile_switch_failed)
                    else SessionActions.switchProfile(context, cookies) { toast(R.string.spotilol_profile_switch_failed) }
                }) { Text(stringResource(R.string.spotilol_profile_switch), color = AppAccent) }
            },
            dismissButton = {
                TextButton(onClick = { switchTo = null }) { Text(stringResource(R.string.add_to_playlist_button_cancel), color = Color.White) }
            },
        )
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = Sheet,
            title = { Text(stringResource(R.string.spotilol_profile_delete_title, p.name), color = Color.White) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    if (runCatching { ProfileManager.deleteProfile(context, p.name) }.isFailure) toast(R.string.spotilol_profile_unavailable)
                    reload()
                }) { Text(stringResource(R.string.spotilol_profile_delete), color = AppAccent) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.add_to_playlist_button_cancel), color = Color.White) }
            },
        )
    }
}
