package com.music.spotui.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import com.music.spotui.di.Haptics
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.myspoti.engine.R
import com.music.spotui.data.BetaLogs

/**
 * The app's redacted logs of the last [BetaLogs.MINUTES] minutes, to read, copy or share.
 * Opened from Settings → App → Diagnostic logs (Spotilol's switchable diagnostics).
 */
@Composable
fun DiagnosticLogsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { text = BetaLogs.recent() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF121212)).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.beta_logs_title, BetaLogs.MINUTES), color = Color.White, fontSize = 17.sp,
                    modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.beta_logs_close), color = Color(0xFFB3B3B3)) }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                val logs = text
                if (logs == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color(0xFF1DB954))
                else SelectionContainer {
                    // The newest lines matter most: the view opens scrolled to the end.
                    val scroll = rememberScrollState()
                    LaunchedEffect(logs) { scroll.scrollTo(scroll.maxValue) }
                    Text(logs, color = Color(0xFFDDDDDD), fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 13.sp,
                        modifier = Modifier.fillMaxSize().verticalScroll(scroll))
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { text?.let { share(context, it) } }, enabled = text != null) {
                    Text(stringResource(R.string.beta_logs_share), color = Color.White)
                }
                TextButton(onClick = { text?.let { copy(context, it) } }, enabled = text != null,
                    modifier = Modifier.background(Color(0xFF1DB954), RoundedCornerShape(20.dp))) {
                    Text(stringResource(R.string.beta_logs_copy), color = Color.Black)
                }
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("MySpotilol logs", text))
    Toast.makeText(context, R.string.beta_logs_copied, Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
