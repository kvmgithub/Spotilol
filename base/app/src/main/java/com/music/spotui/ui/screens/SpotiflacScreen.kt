package com.music.spotui.ui.screens

/*
 * Ported from Spotui's SpotiflacVerifyScreen (https://github.com/Spotui/Spotui), GPL-3.0,
 * and adapted to Myspoti (own theme, strings, navigation).
 */

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.music.spotui.ui.theme.AppBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SpotiFLAC verification, the way SpotiFLAC Mobile's extensions do it: bootstrap → the
 * service's own hosted challenge page (the user solves the Turnstile themselves)
 * → redirect with a grant → exchange the grant for a signing session.
 *
 * The app only loads the page and catches the redirect. The session is what gives
 * the lossless (FLAC) downloads their most reliable source. With a session the
 * screen shows the status; without it the verification opens in a browser tab.
 */
@Composable
fun SpotiflacScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableStateOf(0) }
    // One session per download server (Tidal, then Qobuz); verified once none is missing.
    // Tidal only: the best source, and a single page to solve (Qobuz would need its own).
    fun missing() = listOf(com.music.spotui.lossless.ZarzSession.Ext.TIDAL)
        .firstOrNull { com.music.spotui.data.preferences.getSpotiflacSession(context, it.appVersion) == null }
    var ext by remember { mutableStateOf(missing()) }
    var verified by remember { mutableStateOf(ext == null) }
    var message by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }
    val leave: () -> Unit = onClose

    fun res(id: Int): String = context.getString(id)

    // Reused so a retry keeps the same install; the service binds the session to it.
    val installId = remember { com.music.spotui.lossless.ZarzSession.installId(context) }
    // The state the challenge page must send back with its grant. Saveable: a
    // rotation while the browser is open must not lose it (the grant is then rejected).
    var state by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }

    fun finish(grant: String) = scope.launch {
        message = res(R.string.spotiflac_session_creating)
        val current = ext ?: return@launch
        val ok = withContext(Dispatchers.IO) {
            runCatching { com.music.spotui.lossless.ZarzSession.exchange(context, current, installId, grant) }
                .onFailure { android.util.Log.w("Spotiflac", "exchange: ${it.message}") }.isSuccess
        }
        if (ok) {
            // The next server, if one is still missing (often given at once to a verified install).
            ext = missing()
            if (ext == null) {
                verified = true
                done = true
                android.widget.Toast.makeText(context, R.string.spotiflac_session_ok, android.widget.Toast.LENGTH_LONG).show()
            }
        } else {
            message = res(R.string.spotiflac_session_failed)
        }
    }

    LaunchedEffect(attempt, ext) {
        val current = ext
        if (verified || current == null) return@LaunchedEffect
        message = ""
        val boot = withContext(Dispatchers.IO) {
            runCatching { com.music.spotui.lossless.ZarzSession.bootstrap(context, current, installId) }
                .onFailure { android.util.Log.w("Spotiflac", "bootstrap: ${it.message}") }.getOrNull()
        }
        if (boot == null) {
            message = res(R.string.spotiflac_verify_start_failed)
            return@LaunchedEffect
        }
        if (boot.challengeUrl == null) {
            // The servers gave a session straight away: nothing to solve for this one.
            ext = missing()
            if (ext == null) { verified = true; done = true }
            return@LaunchedEffect
        }
        message = res(R.string.spotiflac_verify_in_browser)
        state = boot.state
        // A browser tab, as SpotiFLAC Mobile opens it: Cloudflare's check runs in a real browser,
        // and the page hands the grant back through spotiflac://session-grant (ZarzSession.grants).
        runCatching {
            androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(true).build()
                .launchUrl(context, android.net.Uri.parse(boot.challengeUrl))
        }.onFailure {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(boot.challengeUrl))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        // Never leave the user on a page that silently stalled.
        delay(180_000)
        if (!done && !verified) message = res(R.string.spotiflac_verify_timeout)
    }

    // The grant coming back from the browser tab.
    LaunchedEffect(Unit) {
        com.music.spotui.lossless.ZarzSession.grants.collect { link ->
            if (link == null) return@collect
            com.music.spotui.lossless.ZarzSession.grants.value = null // take it, once
            val back = com.music.spotui.lossless.ZarzSession.parseGrant(link)
            if (back == null || state.isBlank() || back.second != state) {
                message = res(R.string.spotiflac_verify_rejected)
            } else finish(back.first)
        }
    }

    if (verified) {
        SpotiflacStatusScreen(leave)
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (message.isNotEmpty()) {
            Text(
                message,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .navigationBarsPadding().height(56.dp).background(Color.Black),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.retry), color = Color.White) }
            TextButton(onClick = leave) { Text(stringResource(R.string.spotiflac_skip), color = Color.White) }
        }

        Box(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().height(40.dp).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.spotiflac_verify_title),
                color = Color(0xFF00C7B7),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
        }
    }
}

/** The status page when a session exists: state, expiry, forget. */
@Composable
private fun SpotiflacStatusScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.spotiflac_status_title), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.spotiflac_status_verified), color = Color(0xFFB3B3B3), fontSize = 14.sp)
        for (e in com.music.spotui.lossless.ZarzSession.Ext.entries) {
            val session = com.music.spotui.data.preferences.getSpotiflacSession(context, e.appVersion) ?: continue
            Text(
                e.name.lowercase().replaceFirstChar { it.uppercase() } + " — " +
                    stringResource(R.string.spotiflac_session_expires, session.expiresAt.take(19).replace('T', ' ')),
                color = Color(0xFF00C7B7), fontSize = 13.sp,
            )
        }
        Button(
            onClick = {
                com.music.spotui.data.preferences.clearSpotiflacSessions(context)
                onClose()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E), contentColor = Color.White),
            shape = RoundedCornerShape(24.dp),
        ) { Text(stringResource(R.string.spotiflac_forget)) }
        Button(
            onClick = onClose,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954), contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text(stringResource(R.string.setup_continue), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
    }
}

private const val CALLBACK = com.music.spotui.lossless.ZarzSession.CALLBACK
