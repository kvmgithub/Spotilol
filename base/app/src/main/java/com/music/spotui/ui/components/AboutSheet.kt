package com.music.spotui.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.BuildConfig
import app.myspoti.engine.R

private const val SOURCE_URL = "https://github.com/kvmgithub/Spotilol/tree/myspoti-ui"
private const val MYSPOTI_URL = "https://github.com/kota-zo/myspoti"
private const val SPOTILOL_URL = "https://github.com/kvmgithub/Spotilol"
private const val SPOTILOL_LITE_URL = "https://github.com/AdNenio/Spotilol-Lite"

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** About: the version, the source code and the three projects the app is built from (GPL-3.0). No donation prompts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF282828),
    ) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp)) {
                Image(
                    painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF121212)),
                )
                Column(Modifier.padding(start = 14.dp)) {
                    Text(stringResource(R.string.app_name), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("${stringResource(R.string.settings_item_version_title)} ${BuildConfig.VERSION_NAME}",
                        color = Color(0xFFB3B3B3), fontSize = 13.sp)
                }
            }
            Text(stringResource(R.string.spotilol_about_body), color = Color(0xFFB3B3B3), fontSize = 13.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp))
            AboutRow(stringResource(R.string.spotilol_about_source), stringResource(R.string.spotilol_about_source_detail)) { openUrl(context, SOURCE_URL) }
            Text(stringResource(R.string.spotilol_about_based_on), color = Color(0xFFB3B3B3), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 2.dp))
            AboutRow("Myspoti", stringResource(R.string.spotilol_about_myspoti_detail)) { openUrl(context, MYSPOTI_URL) }
            AboutRow("Spotilol", stringResource(R.string.spotilol_about_spotilol_detail)) { openUrl(context, SPOTILOL_URL) }
            AboutRow("Spotilol Lite", stringResource(R.string.spotilol_about_lite_detail)) { openUrl(context, SPOTILOL_LITE_URL) }
        }
    }
}

@Composable
private fun AboutRow(title: String, detail: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(title, color = Color.White, fontSize = 16.sp)
        Text(detail, color = Color(0xFFB3B3B3), fontSize = 12.sp)
    }
}
